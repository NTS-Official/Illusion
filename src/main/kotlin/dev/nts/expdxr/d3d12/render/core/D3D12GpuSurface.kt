package dev.nts.expdxr.d3d12.render.core

import dev.nts.expdxr.misc.IllusionLogger

import com.mojang.blaze3d.systems.CommandEncoderBackend
import com.mojang.blaze3d.systems.GpuSurface
import com.mojang.blaze3d.systems.GpuSurfaceBackend
import com.mojang.blaze3d.systems.SurfaceException
import com.mojang.blaze3d.textures.GpuTextureView
import dev.nts.expdxr.d3d12.render.D3D12FrameStats
import dev.nts.expdxr.d3d12.render.adapter.D3D12RenderProf
import dev.nts.expdxr.d3d12.render.advanced.smaa.D3D12GUISplit
import dev.nts.expdxr.d3d12.render.advanced.taa.D3D12Temporal
import dev.nts.expdxr.d3d12.extern.MCDR_ABI
import dev.nts.expdxr.d3d12.backend.D3D12CommandEncoderBackend
import java.util.ArrayList

/**
 * D3D12-backed {@link GpuSurfaceBackend} (DXGI flip-model swapchain).
 *
 * Mirrors {@code com.mojang.blaze3d.vulkan.VulkanGpuSurface}: the swapchain is
 * created lazily from the window HWND, resized in {@link #configure}, blitted
 * from the intermediate render target via the current command encoder, and
 * presented with vsync according to the configured {@link PresentMode}.
 */
class D3D12GpuSurface(hwnd: Long) : GpuSurfaceBackend {
    private val handle: Long
    private val presentModes: MutableList<GpuSurface.PresentMode> = ArrayList()
    private var closed = false
    /** P48（多帧飞行）：{@link #acquireNextTexture()} 不再立即 acquire back buffer，而是把
     * 真实 acquire 推迟到 blit 之前（{@link #ensureAcquired()}）。
     * MC 每帧顺序为 acquire → 整帧录制（~4-5ms）→ blit → submit → present，原实现在帧首
     * （acquire）就阻塞等待上一帧 Present 完成（实测 0.13-1.7ms）；推迟后该等待与本帧录制
     * 重叠，通常为 0ms。GpuSurface 包装层的 hasImageAcquired 不受影响（isAcquired 仍为 true）。
     */
    private var acquireDeferred = false
    /** 推迟的 acquire 已失败：下一帧 acquireNextTexture 抛 SurfaceException 触发 MC 重建 surface。 */
    private var acquireDeferredFailed = false
    /**
     * P95（叠帧/闪烁）：本帧的 blit 被跳过（推迟 acquire 失败 → 没有可写的 back buffer）。
     * vanilla 的包装层 `GpuSurface.hasBlittedTexture` 在后端调用之后**无条件**置 true，
     * 所以它并不能告诉我们"这一帧到底有没有画上去"；present() 必须依据这个标志跳过 Present，
     * 否则会把一张本帧从未写过的 back buffer flip 到屏幕上。
     */
    private var blitSkipped = false

    init {
        this.handle = MCDR_ABI.D3D12CreateSurface(hwnd)
        if (this.handle == 0L) {
            throw IllegalStateException("dx12CreateSurface returned a null handle")
        }
        val modes = MCDR_ABI.D3D12SurfacePresentModes()
        val all = GpuSurface.PresentMode.values()
        for (mode in modes) {
            if (mode >= 0 && mode < all.size) {
                this.presentModes.add(all[mode])
            }
        }
    }

    @Throws(SurfaceException::class)
    override fun configure(config: GpuSurface.Configuration) {
        // P48：reconfigure 会 ResizeBuffers，作废任何未完成的推迟 acquire。
        this.acquireDeferred = false
        this.acquireDeferredFailed = false
        this.blitSkipped = false
        val ok = MCDR_ABI.D3D12ConfigureSurface(this.handle, config.width(), config.height(),
            config.presentMode().ordinal)
        IllusionLogger.debugMessage("[backend-java] configureSurface: ${config.width()}x${config.height()}"
                + " mode=${config.presentMode()} ok=${ok}")
        if (!ok) {
            throw SurfaceException("Failed to configure DX12 surface to "
                + "${config.width()}x${config.height()}")
        }
    }

    override fun isSuboptimal(): Boolean {
        return MCDR_ABI.D3D12IsSurfaceSuboptimal(this.handle)
    }

    @Throws(SurfaceException::class)
    override fun acquireNextTexture() {
        // P48：上一帧推迟的 acquire 失败过 → 现在补报，让 MC 走 surfaceIsInvalid →
        // reconfigure 路径（在 blit 处抛异常会直接崩游戏）。
        if (this.acquireDeferredFailed) {
            this.acquireDeferredFailed = false
            throw SurfaceException("Failed to acquire DX12 back buffer (deferred)")
        }
        // P48（多帧飞行）：不在此处立即 acquire，推迟到 blit 之前——见 ensureAcquired()。
        this.acquireDeferred = true
    }

    /**
     * P48（多帧飞行）：在真正需要 back buffer 之前（blit 时）才执行 acquire。
     * 此时本帧录制已完成，上一帧 Present 通常早已结束 → acquire 内部的等待为 0ms。
     * 返回 false 表示 acquire 失败，本帧跳过 blit（画面停留上一帧，不崩游戏）。
     */
    private fun ensureAcquired(): Boolean {
        if (!this.acquireDeferred) {
            return true
        }
        this.acquireDeferred = false
        if (MCDR_ABI.D3D12AcquireSurface(this.handle)) {
            return true
        }
        IllusionLogger.warnMessage("[backend-java] [P48] deferred acquire failed — skipping blit this frame")
        System.err.flush()
        this.acquireDeferredFailed = true
        return false
    }

    override fun blitFromTexture(commandEncoder: CommandEncoderBackend, textureView: GpuTextureView) {
        D3D12RenderProf.begin(D3D12RenderProf.BLIT)
        // P48：真实 acquire 推迟到这里（本帧录制已完成 → 上一帧 Present 早已结束）。
        if (!ensureAcquired()) {
            // P95：本帧没有可写的 back buffer —— 记下来，present() 必须跟着跳过，
            // 否则会 flip 到一张本帧从未写过的 back buffer（启动加载界面与"刚进世界"
            // 那两处「上一帧和这一帧叠在一起 / OSD 重影」正是这条路径）。
            this.blitSkipped = true
            D3D12RenderProf.end(D3D12RenderProf.BLIT)
            return
        }
        val encoder = commandEncoder as D3D12CommandEncoderBackend
        // 传 texture.handle()（底层纹理对象），而非 view.handle()（SRV view 对象）。
        // view 是 texture 的视图包装，CopyTextureRegion 需要的是纹理资源本身。
        // P106：世界层与界面解耦后，GUI 画在自己的窗口尺寸目标上，呈现的源就不一定是 vanilla
        // 传进来的主渲染目标了。D3D12GUISplit 只在「拆分启用且本帧确实把世界层搬了过去」时换源，
        // 其余情况原样返回入参 ⇒ 100% 缩放或任何回退路径都与 vanilla 完全一致。
        val tex = (D3D12GUISplit.presentSourceView(textureView) as D3D12GpuTextureView).texture()
            as D3D12GpuTexture
        // P76a 增量 2：速度（运动矢量）pass 在 blit 之前录进同一条命令列表 —— 此刻本帧渲染
        // 已全部完成、主深度仍然有效（见 D3D12Velocity）。开关关闭时是纯 no-op。
        encoder.recordVelocityPass()
        MCDR_ABI.D3D12BlitSurface(encoder.nativeHandle(), this.handle, tex.handle())
        D3D12RenderProf.end(D3D12RenderProf.BLIT)
    }

    /**
     * 纯红色清空当前 back buffer（不依赖源纹理，用于渲染循环自检）。
     * 需要调用方先 acquireNextTexture()，本方法记录命令后返回。
     */
    fun clearToRed(encoder: D3D12CommandEncoderBackend) {
        // P48：real acquire 推迟到此处（与 blitFromTexture 一致）。
        if (!ensureAcquired()) {
            this.blitSkipped = true   // P95：异步路径同理，没有 back buffer 就不 present
            return
        }
        MCDR_ABI.D3D12BlitSurface(encoder.nativeHandle(), this.handle, 0L)
    }

    override fun present() {
        // 帧边界：时间性 jitter / 历史只在帧首滚动一次（P144）。必须在任何"本帧"的判断之前递增，
        // 这样即便本帧跳过了 blit，下一帧仍被视为新的一帧。
        D3D12Temporal.onFramePresented()
        // P95：本帧跳过过 blit（没有可写 back buffer）就绝不 present —— present 一张本帧
        // 没写过的 back buffer 正是「上一帧叠在本帧上 / OSD 重影」的来源。native
        // presentSurface() 也加了同一道守卫（依据 surface->blittedThisFrame），
        // 这里是纵深防御，顺带省掉一次无谓的调用。
        if (this.blitSkipped) {
            this.blitSkipped = false
            IllusionLogger.debugMessage("[backend-java] [P95] present skipped — no blit this frame")
            return
        }
        D3D12RenderProf.begin(D3D12RenderProf.PRESENT)
        MCDR_ABI.D3D12PresentSurface(handle)
        D3D12RenderProf.end(D3D12RenderProf.PRESENT)
        // F3 的 output 计数：这里是"真正提交给交换链"的那一行（帧生成接上后生成帧也得经过它）。
        D3D12FrameStats.onFramePresented()
    }

    override fun supportedPresentModes(): Collection<GpuSurface.PresentMode> {
        return this.presentModes
    }

    override fun close() {
        if (this.closed) {
            return
        }
        this.closed = true
        MCDR_ABI.D3D12DestroySurface(this.handle)
    }
}

