package dev.nts.expdxr.d3d12.render.core

import dev.nts.expdxr.utils.IllusionLogger

import com.mojang.blaze3d.systems.CommandEncoderBackend
import com.mojang.blaze3d.systems.GpuSurface
import com.mojang.blaze3d.systems.GpuSurfaceBackend
import com.mojang.blaze3d.systems.SurfaceException
import com.mojang.blaze3d.textures.GpuTextureView
import dev.nts.expdxr.d3d12.render.adapter.D3D12RenderProf
import dev.nts.expdxr.d3d12.render.advanced.smaa.D3D12GUISplit
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
    /** P6 诊断：每 ~60 帧读回一次 back buffer 采样像素（确认画面实际内容）。 */
    private var debugReadbackCounter = 0
    /** P6 诊断：缓存最近一次 blit 的 color texture handle，用于直接读回验证。 */
    private var lastColorTextureHandle = 0L
    /**
     * P101c（物品栏取证）：把整帧主颜色纹理（= 用户真正看到的画面）dump 成 BMP。
     * 只在 `MCDR_DUMP_TEX=1` 且 verbose 下启用，最多 [MAX_SCREEN_DUMPS] 次、
     * 每 [SCREEN_DUMP_INTERVAL] 帧一次 —— dump 内部有 deviceWaitIdle，属于同步气泡，
     * 绝不能无条件/高频调用（见 known_bugs §B「readback 类诊断必须有开关且先查 usage」）。
     */
    private var screenDumpCounter = 0
    private var screenDumps = 0
    /**
     * P48（多帧飞行）：{@link #acquireNextTexture()} 不再立即 acquire back buffer，而是把
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
        this.lastColorTextureHandle = tex.handle()
        // P76a 增量 2：速度（运动矢量）pass 在 blit 之前录进同一条命令列表 —— 此刻本帧渲染
        // 已全部完成、主深度仍然有效（见 D3D12Velocity）。开关关闭时是纯 no-op。
        encoder.recordVelocityPass()
        MCDR_ABI.D3D12BlitSurface(encoder.nativeHandle(), this.handle, tex.handle())
        D3D12RenderProf.end(D3D12RenderProf.BLIT)
        // P101c（物品栏取证）：把本帧主渲染目标落盘成 BMP，肉眼确认"用户看到什么"。
        // 触发方式有两种：
        //  ① mixin 打开物品栏后主动 requestScreenDumps(...)（确定性，正好抓到物品栏那几帧）；
        //  ② 兜底：每 SCREEN_DUMP_INTERVAL 次 blit 一次（只在开关打开时）。
        // 为什么不用 present 路径：异步渲染线程持有 Present，Kotlin 的 presentSurface()
        // 在世界阶段根本不会被调用（实测 MCDR_DUMP_TEX=1 时 present 路径一行都没打）。
        // 为什么间隔要从 150 降到 60：本 mod 在加载/入场阶段只有 ~5 fps（见 known_bugs A1），
        // 29 秒都凑不满 150 次 blit —— 之前那次"跑了整轮却一张图都没落盘"就是这个原因。
        // dump 内含 deviceWaitIdle（同步气泡）⇒ 只在显式开关下、且总次数受限时调用。
        if (sDumpScreen && this.lastColorTextureHandle != 0L
            && (sRequestedDumps > 0 || ++this.screenDumpCounter % SCREEN_DUMP_INTERVAL == 0)
            && this.screenDumps < MAX_SCREEN_DUMPS) {
            ++this.screenDumps
            val tag = if (sRequestedDumps > 0) {
                --sRequestedDumps
                "screen_${this.screenDumps}_${sRequestTag}"
            } else {
                "screen_${this.screenDumps}"
            }
            System.err.println("[backend-java] [P101c] dump ${tag}"
                + " (frame=${this.screenDumpCounter} tex=0x"
                + "${this.lastColorTextureHandle.toULong().toString(16)})")
            System.err.flush()
            MCDR_ABI.D3D12DumpTextureToFile(this.lastColorTextureHandle, tag)
        }
        // P6 诊断：blit 后从颜色纹理读回 3x3 像素（waitIdle 保证 GPU 已完成）。
        // 直接读 lastColorTextureHandle（shader 输出目标），而非 surface back buffer（被 blit 覆盖前可能含旧数据）。
        // P29：读回内部 deviceWaitIdle（每 30 帧同步气泡），仅 DX12_LOG_VERBOSE=1 时启用。
        if (IllusionLogger.debugEnabled() && this.lastColorTextureHandle != 0L && ++debugReadbackCounter % 30 == 0) {
            val rb = MCDR_ABI.D3D12ReadbackTexturePixels(this.lastColorTextureHandle)
            if (rb == null) {
                IllusionLogger.debugMessage("[backend-java] [DIAG] colorTex rb NULL handle=0x"
                    + (this.lastColorTextureHandle and 0xFFFFFFFFL).toULong().toString(16))
            } else {
                val len = rb.size
                IllusionLogger.debugFormat("[backend-java] [DIAG] colorTex rb ARRAY_LEN=%d handle=0x%08X%n",
                    len, (this.lastColorTextureHandle and 0xFFFFFFFFL).toInt())
                if (len >= 4)
                    IllusionLogger.debugFormat("  TL(%d,%d,%d,%d) TM(%d,%d,%d,%d) TR(%d,%d,%d,%d)%n",
                        rb[0], rb[1], rb[2], rb[3],
                        rb[4], rb[5], rb[6], rb[7],
                        rb[8], rb[9], rb[10], rb[11])
                if (len >= 12)
                    IllusionLogger.debugFormat("  ML(%d,%d,%d,%d) MC(%d,%d,%d,%d) MR(%d,%d,%d,%d)%n",
                        rb[12], rb[13], rb[14], rb[15],
                        rb[16], rb[17], rb[18], rb[19],
                        rb[20], rb[21], rb[22], rb[23])
                if (len >= 24)
                    IllusionLogger.debugFormat("  BL(%d,%d,%d,%d) BM(%d,%d,%d,%d) BR(%d,%d,%d,%d)%n",
                        rb[24], rb[25], rb[26], rb[27],
                        rb[28], rb[29], rb[30], rb[31],
                        rb[32], rb[33], rb[34], rb[35])
            }
            // P6 诊断：额外读回 back buffer，确认 blit 是否将绿色写入 swapchain。
            // 用 dx12GetActiveSurfaceHandle() 读游戏实际 surface（而非 this.handle，
            // 后者在 resize 后可能指向已失效的旧 self-test surface）。
            val gameSurface = MCDR_ABI.D3D12GetActiveSurfaceHandle()
            val bb = MCDR_ABI.D3D12ReadbackSurfacePixels(if (gameSurface != 0L) gameSurface else this.handle)
            if (bb != null && bb.size >= 12) {
                IllusionLogger.debugFormat("[backend-java] [DIAG] backbuf rb ARRAY_LEN=%d surface=0x%08X%n",
                    bb.size, ((if (gameSurface != 0L) gameSurface else this.handle) and 0xFFFFFFFFL).toInt())
                IllusionLogger.debugFormat("  TL(%d,%d,%d,%d) TM(%d,%d,%d,%d) TR(%d,%d,%d,%d)%n",
                    bb[0], bb[1], bb[2], bb[3],
                    bb[4], bb[5], bb[6], bb[7],
                    bb[8], bb[9], bb[10], bb[11])
                IllusionLogger.debugFormat("  ML(%d,%d,%d,%d) MC(%d,%d,%d,%d) MR(%d,%d,%d,%d)%n",
                    bb[12], bb[13], bb[14], bb[15],
                    bb[16], bb[17], bb[18], bb[19],
                    bb[20], bb[21], bb[22], bb[23])
                IllusionLogger.debugFormat("  BL(%d,%d,%d,%d) BM(%d,%d,%d,%d) BR(%d,%d,%d,%d)%n",
                    bb[24], bb[25], bb[26], bb[27],
                    bb[28], bb[29], bb[30], bb[31],
                    bb[32], bb[33], bb[34], bb[35])
            }
        }
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

    private companion object {
        /** P101c：只有显式 `MCDR_DUMP_TEX=1` 才落盘主画面（dump 内含 deviceWaitIdle）。 */
        private val sDumpScreen: Boolean = "1" == System.getenv("MCDR_DUMP_TEX")

        /** P101c：整轮最多 dump 多少次主画面（物品栏在进世界 6 s 后打开，间隔要够密）。 */
        private const val MAX_SCREEN_DUMPS = 10

        /**
         * P101c：兜底 dump 间隔（帧）。实测本 mod 在加载/入场阶段只有 ~5 fps，
         * 150 帧要 30 s 才到一次 —— 曾经出现过"跑了整轮一张图都没落盘"。
         */
        private const val SCREEN_DUMP_INTERVAL = 60

        /** P101d：mixin 主动请求的 dump 次数与标签（打开物品栏时用）。 */
        private var sRequestedDumps = 0
        private var sRequestTag = "req"

        /**
         * P101d：请求接下来 N 个 blit 各落盘一张 BMP（名字带 [tag]）。
         *
         * 由 mixin 在**打开物品栏之后**调用：那时正是用户看到 bug 的那几帧，
         * 比"按帧号碰运气"确定得多。调用点与 blit 在同一线程（runTick = 渲染线程），
         * 因此这两个计数器不需要同步。
         */
        @JvmStatic
        fun requestScreenDumps(count: Int, tag: String) {
            if (!sDumpScreen || count <= 0) return
            sRequestedDumps = count
            sRequestTag = if (tag.isEmpty()) "req" else tag
            System.err.println("[backend-java] [P101d] screen dump requested: count=${count}"
                + " tag=${sRequestTag}")
            System.err.flush()
        }
    }
}
