package dev.nts.expdxr.d3d12.render.advanced.smaa

import com.mojang.renderpearl.api.GpuFormat
import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.renderpearl.api.textures.GpuTextureView
import dev.nts.expdxr.Illusion
import dev.nts.expdxr.d3d12.render.adapter.D3D12Device
import dev.nts.expdxr.d3d12.render.advanced.vrs.D3D12RenderScale
import dev.nts.expdxr.d3d12.render.advanced.vrs.D3D12VariableShadingRate
import dev.nts.expdxr.d3d12.render.core.D3D12GpuTexture
import dev.nts.expdxr.d3d12.extern.MCDR_ABI
import dev.nts.expdxr.d3d12.render.core.D3D12GpuTextureView
import net.minecraft.client.Minecraft

/**
 * P106：GUI 的独立全尺寸渲染目标（世界层与界面解耦的最后一块）。
 *
 * 背景（known_bugs.md 的 B10）：GUI 与世界共用同一个主渲染目标，而 GUI 的 scissor 是按
 * **窗口像素**算的。renderScale 一旦不是 100%，两者就对不上：
 *  - 小于 100%：渲染区域（附件尺寸）比窗口小，vanilla 的 RenderPass.enableScissor 直接抛
 *    IllegalArgumentException: Scissor ... is out of bounds for render area ...，进世界
 *    第一帧画 HUD 就崩（所以此前小于 100% 只能夹回 100%）；
 *  - 大于 100%：校验能过（区域更大），但界面几何铺满放大后的目标、scissor 只圈窗口大小，
 *    于是界面被裁到左上角、其余是黑的。
 *
 * 这里给 GUI 一个**窗口尺寸**的独立目标：
 *  1. 世界照旧画在（缩放的）主渲染目标上，SMAA 也在那里生效；
 *  2. 世界画完、GUI 还没画时（prepareForGui）用一次带线性采样的纹理 blit 把世界层搬进
 *     GUI 目标，并清一次它的深度；
 *  3. GUI（GuiRenderer.draw）、标题界面的全景图（CubeMap.render）、GUI 背景模糊
 *     （GameRenderer.processBlurEffect）都改画到 GUI 目标上；
 *  4. 最终 blit（D3D12GpuSurface.blitFromTexture）的源换成 GUI 目标。
 *
 * 于是界面永远在窗口坐标系里工作：小于 100% 不再越界（世界放大后铺满窗口），大于 100% 不再
 * 被裁（界面保持 1:1，世界缩小后铺满窗口）。100% 时整体关闭，完全走 vanilla 原路径。
 *
 * 只有「拆分本身没启用」或「GUI 目标建不出来」才让界面回到主渲染目标；**世界层那次 blit 失败
 * 不算**——那种情况下界面仍然画进自己的窗口尺寸目标，只是本帧呈现仍用世界目标（界面可能少一帧）。
 * 原因是实测过一次崩溃（2026-10-16 12:12:53，`RenderPass.enableScissor` 抛
 * `Scissor at 1095, 986 with size 810x324 is out of bounds for render area RenderArea[x=0, y=0,
 * width=1920, height=1000]`）：GUI 的 scissor 按**窗口像素**算，一旦落进缩放后的世界目标
 * （1920x1000 < 窗口 2560x1334），vanilla 的越界校验必然抛异常。所以「宁可退回旧行为」这条
 * 兜底对 renderScale < 100% 是不安全的，界面必须始终待在自己的窗口尺寸目标里。
 */
class D3D12GUIRenderTarget(useStencil: Boolean) :
    // 26.3：RenderTarget 的构造器变成 (label, colorFormat, depthFormat)，不再有 clear/stencil 布尔。
    RenderTarget("DXRMC GUI", GpuFormat.RGBA8_UNORM,
        if (useStencil) GpuFormat.D32_FLOAT_S8_UINT else GpuFormat.D32_FLOAT)

object D3D12GUISplit {

    /** GUI 的窗口尺寸目标；懒创建，窗口尺寸变化时 resize。 */
    @Volatile
    private var target: D3D12GUIRenderTarget? = null

    /** 本帧是否已经把世界层搬进 GUI 目标（只有搬过才敢在最终 blit 里换源）。 */
    @Volatile
    private var preparedThisFrame = false

    /**
     * 本帧界面该画进的目标：只要拆分启用且目标可用就非空，**与那次 blit 成功与否无关**。
     * 界面永远待在窗口尺寸的目标里，是 scissor 越界校验不炸的唯一保证。
     */
    @Volatile
    private var frameTarget: D3D12GUIRenderTarget? = null

    /** 上一次报告的降级原因；只在变化时打一行，避免每帧刷屏。 */
    @Volatile
    private var lastFailure: String? = null

    /**
     * P108x（用户 m11686）取证：`worldLayerAlreadyInTarget` 这个读数的限流打点状态
     * （只渲染线程读写，不需要 `@Volatile`）。
     *
     * 打点分两段：前 [DLSS_FLAG_LOG_LIMIT] 次不限速（DLSS 刚打开那几帧），之后每
     * [DLSS_FLAG_LOG_INTERVAL_MILLIS] 毫秒最多一行 —— 既能看到"标记确实被读成 true"，
     * 也不会每帧刷屏。
     */
    private var dlssFlagLogs = 0
    private var dlssFlagLastLogMillis = 0L

    /** P108x 取证：不限速的前几行。 */
    private const val DLSS_FLAG_LOG_LIMIT: Int = 5

    /** P108x 取证：限流之后每行之间的最小间隔（1 秒）。 */
    private const val DLSS_FLAG_LOG_INTERVAL_MILLIS: Long = 1000L

    /** 创建失败只报一次，之后永久回退（不要每帧重试、每帧刷日志）。 */
    @Volatile
    private var givenUp = false

    /**
     * P108：本帧 DLSS 已经把升采样后的世界层**直接写进** GUI 目标（`pInOutput` 就是这张纹理）。
     * 置位后本帧的 [prepareForGui] 必须跳过那次世界层 blit —— 否则线性采样会把 DLSS 的结果盖掉。
     * 由 `dev.nts.expdxr.dxrmc.dlss.D3D12Dlss` 在 native 确认评价成功后置位。
     */
    @Volatile
    private var worldLayerDoneThisFrame = false

    /** P305c：上一次交给 native 的 UI 纹理句柄（同一张只推一次）+「老 DLL 没有这条导出」只警告一次。 */
    private var pushedUiHandle: Long = 0L
    private var warnedNoUiTexture: Boolean = false

    /** P108：见 [worldLayerDoneThisFrame]。 */
    @JvmStatic
    fun markWorldLayerDone() {
        worldLayerDoneThisFrame = true
    }

    /**
     * P108x（用户 m11686）取证：上一帧 [markWorldLayerDone] 置的标记还在不在。
     *
     * 只在 DLSS 的 `onEvaluated` 里、刚调完 [markWorldLayerDone] 之后读（同一帧、渲染线程），
     * 所以读到的一定是 `true`；它证明的是"标记本身没被别人清掉/写回 false"，
     * 而不是"世界层真的换源了"——后者要看 [presentSourceView] 那一侧的 `preparedThisFrame`。
     */
    @JvmStatic
    fun isWorldLayerDoneThisFrame(): Boolean = worldLayerDoneThisFrame

    /** 只有 renderScale 偏离 100% 才需要拆分；100% 时整体关闭、零行为变化。 */
    @JvmStatic
    fun isActive(): Boolean = D3D12RenderScale.isEnabled()

    /**
     * GUI（含标题界面全景图）这一帧该画到哪个目标。返回 null 表示调用方必须回退 vanilla 的
     * 主渲染目标（未启用、尺寸拿不到、创建失败）。
     *
     * 注意：RenderTarget.resize 会 assertOnRenderThread，所以只从渲染线程调用
     * （GuiRenderer / CubeMap / GameRenderer 的三个挂点都在渲染线程上）。
     */
    @JvmStatic
    fun targetForFrame(): RenderTarget? {
        val minecraft = try {
            Minecraft.getInstance()
        } catch (t: Throwable) {
            return null
        }
        // frameTarget 为空有两种情况：拆分没启用（主目标 = 窗口，vanilla 路径），或者 prepareForGui
        // 这一帧没跑到（挂点缺失/异常）。后一种情况下如果主目标其实是被缩放的，界面绝不能落进去，
        // 所以这里再判一次真实尺寸，必要时现场补一个 GUI 目标。
        val current = frameTarget
            ?: if (mainTargetMatchesWindow(minecraft)) return null else ensureTarget()
        val gui = current as? D3D12GUIRenderTarget ?: return null
        // 窗口可能在 prepareForGui 之后、GuiRenderer.draw 之前又变了尺寸；界面 scissor 是按窗口
        // 像素算的，目标只要小一点，vanilla 的 enableScissor 就会越界抛异常，所以这里再校一次。
        return try {
            val window = minecraft.window
            if (gui.width != window.width || gui.height != window.height) {
                gui.resize(window.width, window.height)
                logGeometry(minecraft, gui, "resized (late)")
            }
            gui
        } catch (t: Throwable) {
            degrade("late resize failed: $t")
            null
        }
    }

    /**
     * 主渲染目标的**真实附件尺寸**是否就等于窗口尺寸。判断用实际纹理尺寸而不是配置值：
     * 运行中把 renderScale 改回 100 时配置立刻变、主目标却要等 `GameRenderer.resize` 才重建，
     * 中间那几帧界面若落进仍被缩放的主目标就会撞上 vanilla 的 scissor 越界校验（2026-10-16
     * 实测崩溃：`Scissor at 1095, 986 with size 810x324 ... render area 1920x1000`）。
     * 读不到 D3D12 纹理时返回 true，即按 vanilla 原路径处理。
     */
    private fun mainTargetMatchesWindow(minecraft: Minecraft): Boolean {
        val world = (minecraft.gameRenderer.mainRenderTarget().colorTextureView as? D3D12GpuTextureView)
            ?.texture() as? D3D12GpuTexture ?: return true
        val window = minecraft.window
        return world.getWidth(0) == window.width && world.getHeight(0) == window.height
    }

    /**
     * P209：DLSS/DLAA 的 pInOutput 必须是**窗口尺寸**的目标，所以它不能走 [targetForFrame] 里
     * “主目标已经等于窗口 ⇒ 不需要拆分”那条早退。这里无条件建/校正 GUI 目标（幂等）。
     *
     * <p>为什么要单开一个入口：实测（2026-10-24）在 DLSS 评价时刻 `targetForFrame()` 会返回 null
     * （主目标逻辑尺寸 == 窗口 ⇒ 早退），于是每帧都记 “no window-sized output target”，DLSS 一次都
     * 没跑。判断“需不需要拆分”和“DLSS 需不需要输出目标”是两件事，不能共用同一个早退。
     */
    @JvmStatic
    fun ensureTargetForDlss(): RenderTarget? = try {
        ensureTarget()
    } catch (t: Throwable) {
        degrade("ensureTargetForDlss failed: $t")
        null
    }

    private fun ensureTarget(): RenderTarget? {
        if (givenUp) {
            return null
        }
        val minecraft = Minecraft.getInstance()
        val width = minecraft.window.width
        val height = minecraft.window.height
        if (width <= 0 || height <= 0) {
            return null
        }
        return try {
            val existing = target
            if (existing == null) {
                // 深度格式必须与主目标一致（主目标带模板 -> 这里也用 D32_FLOAT_S8_UINT），
                // 否则界面管线在两张目标之间来回切换时会不停重建 PSO。
                // 26.3：RenderTarget 已无 useStencil，改用 hasDepth() 判定。
                val created = D3D12GUIRenderTarget(minecraft.gameRenderer.mainRenderTarget().hasDepth())
                created.resize(width, height)
                target = created
                logGeometry(minecraft, created, "active")
                pushUiTextureToFrameGen(created)
                created
            } else {
                if (existing.width != width || existing.height != height) {
                    existing.resize(width, height)
                    logGeometry(minecraft, existing, "resized")
                    pushUiTextureToFrameGen(existing)
                }
                existing
            }
        } catch (t: Throwable) {
            givenUp = true
            Illusion.LOGGER.error(
                "[dxrmc] P106: could not create the GUI's own render target; forcing render scale back to 100% so the GUI cannot outgrow the render area",
                t)
            // 没有窗口尺寸的界面目标时，界面只能回到主渲染目标；主目标若比窗口小（renderScale < 100%），
            // 界面的窗口像素 scissor 就会越界抛异常。退回 100% 是这种情况下唯一安全的兜底。
            D3D12RenderScale.setPercent(100)
            runCatching {
                val mc = Minecraft.getInstance()
                mc.gameRenderer.resize(mc.window.width, mc.window.height)
            }.onFailure { degrade("render scale rollback failed: $it") }
            null
        }
    }

    /**
     * P305c：把 GPT 合成目标的颜色纹理当「UI 纹理」交给帧生成后端（用户 2026-10-27 裁决）。
     *
     * 包装型后端（XeSS-FG / FSR-FG）要把无 HUD 的世界层与 UI 分开插值，否则准星与文字会被插帧
     * 拉扯。我们的 GUI 合成进这张窗口尺寸的目标，所以直接把它交出去 —— **只在目标创建/重建时推
     * 一次**，不是每帧。`Int.MIN_VALUE` = 本地 DLL 没有这条导出（ABI 版本过旧），只记一次。
     */
    private fun pushUiTextureToFrameGen(target: RenderTarget) {
        val texture = (target.colorTextureView as? D3D12GpuTextureView)?.texture() as? D3D12GpuTexture ?: return
        val handle = texture.handle()
        if (handle == pushedUiHandle) {
            return
        }
        val status = try {
            MCDR_ABI.D3D12FgSetUiTexture(handle)
        } catch (t: Throwable) {
            Illusion.LOGGER.warn("[dxrmc] P305c: could not hand the GUI target to frame generation: {}", t.toString())
            return
        }
        when (status) {
            1 -> {
                pushedUiHandle = handle
                Illusion.LOGGER.info("[dxrmc] P305c: handed the GUI target ({}) to frame generation as the UI texture", handle)
            }
            Int.MIN_VALUE -> if (!warnedNoUiTexture) {
                warnedNoUiTexture = true
                Illusion.LOGGER.info("[dxrmc] P305c: this native library has no frame-generation UI texture entry point; UI tagging stays off")
            }
            else -> Illusion.LOGGER.warn("[dxrmc] P305c: frame generation rejected the GUI target as UI texture (status={})", status)
        }
    }

    /**
     * 取证用：把两边目标的真实纹理尺寸写进日志（世界给的是纹理尺寸，界面给的是窗口尺寸）。
     * 只在创建/尺寸变化时各打一次，所以既是自证也不会刷屏。
     */
    private fun logGeometry(minecraft: Minecraft, gui: RenderTarget, state: String) {
        val world = (minecraft.gameRenderer.mainRenderTarget().colorTextureView as? D3D12GpuTextureView)
            ?.texture() as? D3D12GpuTexture
        Illusion.LOGGER.info(
            "[dxrmc] P106: GUI split {} - world {}x{} ({}%), GUI target {}x{}",
            state,
            world?.getWidth(0) ?: -1, world?.getHeight(0) ?: -1, D3D12RenderScale.percent(),
            gui.width, gui.height)
    }

    /**
     * 世界层画完、GUI 还没画的那一刻调用（与 D3D12SMAAWorld.applyIfEnabled 同一挂点，
     * 排在它后面 —— SMAA 必须先在世界分辨率上做完，再放大/缩小进 GUI 目标）。
     */
    @JvmStatic
    fun prepareForGui() {
        // 每帧重新起算：只有本帧真的把世界层搬进 GUI 目标，最终呈现才换源。
        preparedThisFrame = false
        frameTarget = null
        // P108：DLSS 在更早的挂点（主目标深度被清空之前）就把升采样后的世界层写进这张目标了。
        // 标志在这里读走并清零，后面的分支据此跳过世界层 blit。
        val worldLayerAlreadyInTarget = worldLayerDoneThisFrame
        worldLayerDoneThisFrame = false
        // P108x（用户 m11686）取证：把在这里**读到**的标记值打出来（限流）。它与 DLSS 侧
        // `onEvaluated` 里那行一起构成闭环：一侧说"我置位了"，另一侧说"我读到的是 true/false"。
        logDlssFlag(worldLayerAlreadyInTarget)
        if (givenUp) {
            return
        }
        val minecraft = Minecraft.getInstance()
        if (mainTargetMatchesWindow(minecraft)) {
            // 主目标就是窗口尺寸：不需要拆分，界面照 vanilla 走主目标。
            return
        }
        val gui = ensureTarget() as? D3D12GUIRenderTarget ?: return degrade("no GUI target")
        // 先定下界面画到哪：这一步与那次 blit 无关，绝不能因为 blit 失败把界面塞回世界目标。
        frameTarget = gui
        // vanilla 在 GUI 之前会清一次主目标的深度（GameRenderer.render 的 clearDepthTexture）。
        // 界面现在画在另一张目标上，所以这里补同样的清：不清的话上一帧的界面深度会挡住这一帧。
        gui.depthTexture?.let {
            RenderSystem.getDevice().createCommandEncoder().clearDepthTexture(it, 0.0)
        }
        // P108：世界层已经在这张目标里（DLSS 升采样，pInOutput 就是它），本帧只需把界面画上去。
        // 这里绝不能再去 blit 世界层，否则会盖掉 DLSS 的结果。
        if (worldLayerAlreadyInTarget) {
            preparedThisFrame = true
            degrade(null)
            return
        }
        val world = (minecraft.gameRenderer.mainRenderTarget().colorTextureView as? D3D12GpuTextureView)
            ?.texture() as? D3D12GpuTexture ?: return degrade("world layer is not a D3D12 texture")
        val destination = (gui.colorTextureView as? D3D12GpuTextureView)?.texture() as? D3D12GpuTexture
            ?: return degrade("GUI target is not a D3D12 texture")
        val encoder = D3D12Device.sharedCommandEncoder() ?: return degrade("no shared command encoder")
        // P236：GUI 与世界上半部分共用同一条命令列表，这里做一次防御性复位 ——
        // 即使某个异常路径跳过了 `scope()` 的 finally，文字与 UI 也不会被粗着色。
        D3D12VariableShadingRate.hardReset(encoder.nativeCtx())
        // 世界层搬进 GUI 目标：小于 100% 是放大、大于 100% 是缩小，都是线性采样。
        // 只有 native 真的接下这一笔，最终呈现才改走 GUI 目标；没接下来就只是这一帧界面不见，
        // 界面本身依然画在自己的窗口尺寸目标里，所以不会触发 scissor 越界。
        if (!encoder.recordTextureBlit(world, destination)) {
            return degrade("native texture blit refused")
        }
        preparedThisFrame = true
        degrade(null)
    }

    /**
     * P108x（用户 m11686）取证：把 [prepareForGui] 里读到的 `worldLayerAlreadyInTarget` 打出来。
     *
     * 限流：前 [DLSS_FLAG_LOG_LIMIT] 行每帧一行（DLSS 刚打开那几帧），之后每
     * [DLSS_FLAG_LOG_INTERVAL_MILLIS] 毫秒最多一行。
     */
    private fun logDlssFlag(worldLayerAlreadyInTarget: Boolean) {
        val now = System.currentTimeMillis()
        val first = dlssFlagLogs < DLSS_FLAG_LOG_LIMIT
        if (!first && now - dlssFlagLastLogMillis < DLSS_FLAG_LOG_INTERVAL_MILLIS) {
            return
        }
        dlssFlagLastLogMillis = now
        dlssFlagLogs += 1
        Illusion.LOGGER.info(
            "[dxrmc] P106: prepareForGui read worldLayerAlreadyInTarget={} (worldLayerDoneThisFrame was "
                + "set by the super-resolution evaluate path this frame; false means the world layer is "
                + "blitted the old way)",
            worldLayerAlreadyInTarget)
    }

    /** 记录 / 清除降级状态；只在状态变化时打一行，避免每帧刷屏。 */
    private fun degrade(reason: String?) {
        if (lastFailure == reason) {
            return
        }
        lastFailure = reason
        if (reason == null) {
            Illusion.LOGGER.info("[dxrmc] P106: GUI split healthy again - the world layer is blitted into the GUI target")
        } else {
            Illusion.LOGGER.warn("[dxrmc] P106: GUI split degraded ({}); the GUI keeps drawing into its own window-sized target, this frame just presents the world target", reason)
        }
    }

    /**
     * 最终 blit（呈现）该用哪张纹理：本帧已经把世界层搬进 GUI 目标时用 GUI 目标，
     * 否则用 vanilla 传进来的主渲染目标纹理。
     */
    /**
     * P217：本帧最终会被 present 的那张目标；拆分没生效（或已放弃）时返回 null，调用方退回主目标。
     * 供 DLSS 的准星补画使用 —— 拆分启用时界面画在 GUI 目标上，画进主目标等于画进没人读的纹理。
     */
    @JvmStatic
    fun replayTarget(): RenderTarget? {
        if (!preparedThisFrame || givenUp) {
            return null
        }
        return target
    }

    @JvmStatic
    fun presentSourceView(fallback: GpuTextureView): GpuTextureView {
        if (!preparedThisFrame || givenUp) {
            return fallback
        }
        return target?.colorTextureView ?: fallback
    }
}
