package dev.nts.expdxr.d3d12.render.core

import dev.nts.expdxr.misc.IllusionLogger

import com.mojang.renderpearl.backend.api.CommandEncoderBackend
import com.mojang.renderpearl.api.device.GpuSurface
import com.mojang.renderpearl.backend.api.GpuSurfaceBackend
import com.mojang.renderpearl.api.device.SurfaceException
import com.mojang.renderpearl.api.textures.GpuTextureView
import dev.nts.expdxr.d3d12.debug.D3D12FrameStats
import dev.nts.expdxr.d3d12.render.adapter.D3D12RenderProf
import dev.nts.expdxr.d3d12.render.advanced.smaa.D3D12GUISplit
import dev.nts.expdxr.d3d12.render.advanced.taa.D3D12Temporal
import dev.nts.expdxr.d3d12.extern.MCDR_ABI
import dev.nts.expdxr.d3d12.backend.D3D12CommandEncoderBackend
import java.util.ArrayList

/**
 * D3D12 交换链后端（DXGI flip model）：swapchain 由窗口 HWND 懒创建、[configure] 里重建，
 * blit 用当前命令编码器，present 按配置的 [GpuSurface.PresentMode] 走 vsync。
 */
class D3D12GpuSurface(hwnd: Long) : GpuSurfaceBackend {
    private val handle: Long
    private val presentModes: MutableList<GpuSurface.PresentMode> = ArrayList()
    private var closed = false
    /** acquire 推迟到 blit 之前（[ensureAcquired]）：帧首 acquire 会阻塞等上一帧 Present。包装层的 hasImageAcquired 不受影响。 */
    private var acquireDeferred = false
    /** 推迟的 acquire 已失败：下一帧 acquireNextTexture 抛 SurfaceException 触发 MC 重建 surface。 */
    private var acquireDeferredFailed = false
    /**
     * 本帧 blit 被跳过（推迟 acquire 失败）。vanilla 包装层的 hasBlittedTexture 无条件置 true，判断不了
     * "这帧到底画没画"；present() 必须依据本标志跳过，否则会 flip 一张本帧从未写过的 back buffer。
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
        this.acquireDeferred = false
        this.acquireDeferredFailed = false
        this.blitSkipped = false
        // 不再用 DXGI 独占（SetFullscreenState）：链建好后再切会卡死 Present、锁住 Alt+Tab。交给 SDL 切显示模式。
        val ok = MCDR_ABI.D3D12ConfigureSurface(this.handle, config.width(), config.height(),
            config.presentMode().ordinal)
        IllusionLogger.debugMessage("configureSurface: ${config.width()}x${config.height()}"
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
        // 补报上一帧推迟 acquire 的失败，让 MC 走 surfaceIsInvalid → reconfigure（在 blit 处抛会直接崩）。
        if (this.acquireDeferredFailed) {
            this.acquireDeferredFailed = false
            throw SurfaceException("Failed to acquire DX12 back buffer (deferred)")
        }
        this.acquireDeferred = true
    }

    /**
     * 在真正需要 back buffer 之前（blit 时）才 acquire：此时本帧录制已完成、上一帧 Present 通常已结束，
     * 等待为 0ms。返回 false = 本帧跳过 blit（画面停在上一帧，不崩）。
     */
    private fun ensureAcquired(): Boolean {
        if (!this.acquireDeferred) {
            return true
        }
        this.acquireDeferred = false
        if (MCDR_ABI.D3D12AcquireSurface(this.handle)) {
            return true
        }
        IllusionLogger.warnMessage("deferred acquire failed — skipping blit this frame")
        System.err.flush()
        this.acquireDeferredFailed = true
        return false
    }

    override fun blitFromTexture(commandEncoder: CommandEncoderBackend, textureView: GpuTextureView) {
        D3D12RenderProf.begin(D3D12RenderProf.BLIT)
        if (!ensureAcquired()) {
            // 没有可写 back buffer：present() 必须跟着跳过，否则会 flip 一张本帧从未写过的 back buffer。
            this.blitSkipped = true
            D3D12RenderProf.end(D3D12RenderProf.BLIT)
            return
        }
        val encoder = commandEncoder as D3D12CommandEncoderBackend
        // 传 texture.handle()（纹理资源），不是 view.handle()（SRV view）：CopyTextureRegion 要纹理本身。
        // 呈现源不一定是 vanilla 传入的主渲染目标：D3D12GUISplit 只在「拆分启用且本帧确实搬了世界层」时
        // 换成 GUI 目标，hudless 模式换成不含 UI 的世界层，其余原样返回入参。
        val fallback = (textureView as D3D12GpuTextureView).texture() as D3D12GpuTexture
        val tex = D3D12GUISplit.presentSourceTexture(fallback)
        // 速度（运动矢量）pass 必须在本帧渲染完成、主深度仍有效时录进同一条命令列表。
        encoder.recordVelocityPass()
        MCDR_ABI.D3D12BlitSurface(encoder.nativeHandle(), this.handle, tex.handle())
        D3D12RenderProf.end(D3D12RenderProf.BLIT)
    }

    /** 纯红色清空当前 back buffer（渲染循环自检用）。调用方需先 acquireNextTexture()；只录命令就返回。 */
    fun clearToRed(encoder: D3D12CommandEncoderBackend) {
        if (!ensureAcquired()) {
            this.blitSkipped = true
            return
        }
        MCDR_ABI.D3D12BlitSurface(encoder.nativeHandle(), this.handle, 0L)
    }

    override fun present() {
        // 必须在任何"本帧"判断之前递增：即便本帧跳过 blit，下一帧仍算新一帧（时间性 jitter/历史只在帧首滚动）。
        D3D12Temporal.onFramePresented()
        // 本帧跳过过 blit 就绝不 present：present 一张没写过的 back buffer 正是「上一帧叠在本帧上 / OSD 重影」；
        // native presentSurface() 也有一道同样的守卫（surface->blittedThisFrame），这里是纵深防御。
        if (this.blitSkipped) {
            this.blitSkipped = false
            IllusionLogger.debugMessage("present skipped — no blit this frame")
            return
        }
        D3D12RenderProf.begin(D3D12RenderProf.PRESENT)
        MCDR_ABI.D3D12PresentSurface(handle)
        D3D12RenderProf.end(D3D12RenderProf.PRESENT)
        // F3 的 output 计数：这里是真正提交给交换链的地方（生成帧也得经过它）。D3D12FgPresented 按
        // **当前生效的** provider 取数（XeSS-FG / FSR-FG 各自报），写死 DLSS-G 会让另两家恒为 1×。
        val presented = MCDR_ABI.D3D12FgPresented()
        D3D12FrameStats.onFramePresented(
            if (presented > 0) presented else MCDR_ABI.D3D12DlssgPresentedFrames()
        )
        // 帧边界裁决「下一帧走 hudless 还是合成层」，必须在 present 之后：世界层去向要与下一帧 SR 行为
        // 一致（SR 的挂点排在 prepareForGui 之前），同一帧里不能换模式。
        D3D12GUISplit.armUiComposition()
    }

    override fun supportedPresentModes(): Collection<GpuSurface.PresentMode> {
        return this.presentModes
    }

    override fun close() {
        if (this.closed) {
            return
        }
        this.closed = true
        // 关闭会等 pending present / deviceWaitIdle；慢在这里表现为「窗口先灰几秒才消失」。
        val started = System.nanoTime()
        IllusionLogger.debugMessage("destroySurface begin")
        MCDR_ABI.D3D12DestroySurface(this.handle)
        IllusionLogger.debugMessage("destroySurface took ${(System.nanoTime() - started) / 1_000_000} ms")
    }
}

