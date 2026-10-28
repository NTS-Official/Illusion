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
import dev.nts.expdxr.dxrmc.framegen.FrameGeneration
import dev.nts.expdxr.dxrmc.sr.SuperResolution
import net.minecraft.client.Minecraft
import org.joml.Vector4f

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
 *
 * <p>**P306ac（hudless 呈现）**：帧生成的 FSR-FG 要求「present 的世界层不含 UI，UI 由 SDK
 * 用注册的 UI 资源合成一次」。于是这里多了一种模式：
 *  - 世界层（[worldLayerThisFrame]，SR 的输出；没有 SR 时是主渲染目标）直接当**呈现源**，
 *    最终 blit 不再经过 GUI 目标；
 *  - GUI 仍然画进这张窗口尺寸目标，但它现在从**全透明**开始，于是 RGB 天然是「按自身 alpha
 *    预乘」、alpha 通道是正确覆盖度（原版 `BlendFunction.TRANSLUCENT` 的 alpha 通道是
 *    ONE/INV_SRC_ALPHA），正好对应 FFX 的 `USE_PREMUL_ALPHA` 合成；
 *  - 这张目标同时就是 [pushUiTextureToFrameGen] 交给后端的 UI 纹理。
 *
 * 模式只在帧边界（[armUiComposition]，present 之后）裁决一次：世界层是搬进 GUI 目标还是直接
 * 呈现，必须与 SR 本帧的行为一致，而 SR 的挂点排在 [prepareForGui] 之前。
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

    /**
     * P306ac：本帧 SR 升采样出的世界层（窗口尺寸、不含 UI）。hudless 模式下它就是呈现源；
     * 只在 SR 真的成功那一帧有值，帧边界由 [armUiComposition] 清空 —— 否则菜单/加载屏
     * 那一帧会把一张早已释放的纹理交给最终 blit。
     */
    @Volatile
    private var worldLayerThisFrame: D3D12GpuTexture? = null

    /**
     * P306ac：hudless 呈现**对这一帧是否生效**（呈现源与 UI 分工都按它来）。
     *
     * 它只在 present 之后（[armUiComposition]）更新，所以同一帧里 SR、[prepareForGui] 与最终
     * blit 看到的是同一个值：世界层该搬进 GUI 目标还是直接呈现，绝不能在一帧里选两次。
     */
    @Volatile
    private var hudlessArmed = false

    /** 已经推给 native 的 hudless 状态；只在**真的变化**时推一次（native 侧会打一行日志）。 */
    @Volatile
    private var hudlessPushed = false

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
    fun isActive(): Boolean = D3D12RenderScale.isEnabled() || hudlessArmed

    /**
     * P306ac：把本帧升采样出的世界层记下来（= 呈现源）。
     *
     * <p>hudless 模式下它同时是「世界层已经就位」的标记，[prepareForGui] 据此跳过搬移；
     * 合成模式下 SR 仍然要自己把它 blit 进 GUI 目标，这里只是顺手记着（不参与选择）。
     * **必须在 SR 成功写完之后、[prepareForGui] 之前调用**（`GameRendererSmaaWorldMixin` 的顺序）。
     */
    @JvmStatic
    fun markWorldLayer(texture: D3D12GpuTexture) {
        worldLayerThisFrame = texture
        worldLayerDoneThisFrame = true
    }

    /** P306ac：这一帧是否走 hudless 呈现。SR 与 [prepareForGui] 都读同一个 latch。 */
    @JvmStatic
    fun isHudlessArmed(): Boolean = hudlessArmed

    /**
     * P306ac：帧边界（present 之后）裁决一次 hudless 呈现，并把结果推给 native。
     *
     * <p>为什么放在 present 之后，而不是帧首或 [prepareForGui]：世界层的去向（搬进 GUI 目标 vs
     * 直接呈现）必须与**本帧** SR 的行为一致，而 SR 的挂点排在 [prepareForGui] 之前
     * （`GameRendererSmaaWorldMixin`）。只有在上一次 present 之后定下来，下一帧的 SR、界面与最终
     * blit 才可能看到同一个值。
     *
     * <p>要不要打开由 [FrameGeneration.hudlessWanted]（后端 + 有没有 3D 世界层）与 native 的
     * `D3D12FgUiCompositionReady` 共同决定。后者回答的是"**SDK 现在真的在合成 UI**吗"，判据是
     * 「链已包 + UI 纹理已交过去 + 已经发生过插帧 dispatch」——P306ad 之前只看前两条，于是在
     * "开了 FSR-FG 但没开超分/倍率还是 100%"的场合也照样武装：present 只送世界层，UI 注册却落在
     * 一条没人 Present 的代理链上，界面整层消失。它**不回答**「hudless 开没开」，否则第一次打开
     * 时会因为还没打开而永远打不开。
     */
    @JvmStatic
    fun armUiComposition() {
        // 本帧的世界层已经呈现过了；SR 下一帧不跑（菜单、世界切换）时这个引用会失效。
        worldLayerThisFrame = null
        // DLSS 超分（含 DLAA）把升采样结果**直接写进** GUI 目标（pInOutput 就是它），那条路径
        // 没有"独立的世界层纹理"可交出去；它同时也是 DLSS-G 的 hudless 来源，不能混用。
        val want = target != null &&
            FrameGeneration.hudlessWanted() &&
            SuperResolution.backendId() != MCDR_ABI.SR_BACKEND_DLSS
        val ready = if (want) MCDR_ABI.D3D12FgUiCompositionReady() else 0
        val able = want && ready == 1
        if (want && !able) {
            noteCompositionNotReady()
        }
        if (able != hudlessPushed && MCDR_ABI.D3D12FgSetUiComposition(able) == 1) {
            hudlessPushed = able
        }
        val next = able && hudlessPushed
        if (next == hudlessArmed) {
            return
        }
        hudlessArmed = next
        Illusion.LOGGER.info(
            "hudless presentation {} - presenting {}, the GUI is {}",
            if (next) "on" else "off",
            if (next) "the world layer only" else "the world + GUI composite",
            if (next) "composed by the frame generation backend" else "drawn by us into the presented image")
    }

    /**
     * P306ad：想开 hudless 但 native 说后端还没在合成 UI。
     *
     * <p>这条日志存在的理由：用户报"界面整层消失"时，唯一能区分"没武装"与"武装了但没人合成"的
     * 就是日志。它每 10 秒最多一行，正常进游戏（先开超分再开 FG）只会出现一两行。
     */
    private fun noteCompositionNotReady() {
        val now = System.nanoTime()
        if (now - lastNotReadyLogNanos < NOT_READY_LOG_INTERVAL_NANOS) return
        lastNotReadyLogNanos = now
        Illusion.LOGGER.info(
            "hudless presentation wanted, but the frame generation backend is not composing yet " +
                "(fg state={}, backend={}) - keeping the world + GUI composite",
            MCDR_ABI.D3D12FgState(),
            FrameGeneration.activeBackend())
    }

    private var lastNotReadyLogNanos = 0L

    /** P306ad：`noteCompositionNotReady` 的节流间隔（10 秒）。 */
    private const val NOT_READY_LOG_INTERVAL_NANOS = 10_000_000_000L

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
        // P306ac：hudless 模式下 GUI 目标就是 UI 层，哪怕主目标已经是窗口尺寸也不能回到主目标 ——
        // 回去就等于把 GUI 又画进世界层，后端插出来的帧会拖着 HUD。
        val current = frameTarget
            ?: if (!hudlessArmed && mainTargetMatchesWindow(minecraft)) return null else ensureTarget()
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
                    // P306ac：尺寸变了必须让 native 侧重新注册 —— FFX 在注册那一刻就把资源的
                    // 描述与状态记下来了，尺寸不对会直接踩坏合成（也可能换成了另一张纹理，但
                    // 句柄相同的情况同样要走一遍重推）。
                    pushedUiHandle = 0L
                    pushUiTextureToFrameGen(existing)
                }
                existing
            }
        } catch (t: Throwable) {
            givenUp = true
            Illusion.LOGGER.error(
                "could not create the GUI's own render target; forcing render scale back to 100% so the GUI cannot outgrow the render area",
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
            Illusion.LOGGER.warn("could not hand the GUI target to frame generation: {}", t.toString())
            return
        }
        when (status) {
            1 -> {
                pushedUiHandle = handle
                Illusion.LOGGER.info("handed the GUI target ({}) to frame generation as the UI texture", handle)
            }
            Int.MIN_VALUE -> if (!warnedNoUiTexture) {
                warnedNoUiTexture = true
                Illusion.LOGGER.info("this native library has no frame-generation UI texture entry point; UI tagging stays off")
            }
            else -> Illusion.LOGGER.warn("frame generation rejected the GUI target as UI texture (status={})", status)
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
            "GUI split {} - world {}x{} ({}%), GUI target {}x{}",
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
        // 每帧重新起算：只有本帧真的把世界层搬进 GUI 目标，合成模式下的最终呈现才换源。
        preparedThisFrame = false
        frameTarget = null
        // P108：DLSS 在更早的挂点（主目标深度被清空之前）就把升采样后的世界层写进这张目标了。
        // 标志在这里读走并清零，后面的分支据此跳过世界层 blit。
        val worldLayerAlreadyInTarget = worldLayerDoneThisFrame
        worldLayerDoneThisFrame = false
        if (givenUp) {
            return
        }
        val minecraft = Minecraft.getInstance()
        // P306ac：hudless 模式下 GUI 目标就是 UI 层，必须无条件启用拆分 —— 100% 缩放时也不能
        // 让界面落回世界层，否则后端插出来的帧会拖着 HUD。
        val hudless = hudlessArmed
        if (!hudless && mainTargetMatchesWindow(minecraft)) {
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
        if (hudless) {
            // UI 层必须从**全透明**开始：原版 `BlendFunction.TRANSLUCENT` 的 alpha 通道是
            // ONE / INV_SRC_ALPHA，所以"透明底 + 普通混合"得到的正好是「按自身 alpha 预乘的 RGB
            // + 正确的覆盖 alpha」，与 FFX `USE_PREMUL_ALPHA` 的合成约定一致
            // （见 FrameInterpolationSwapchainUiComposition.hlsl 的 premul 分支）。
            val ui = (gui.colorTextureView as? D3D12GpuTextureView)?.texture() as? D3D12GpuTexture
                ?: return degrade("GUI target is not a D3D12 texture")
            val encoder = D3D12Device.sharedCommandEncoder() ?: return degrade("no shared command encoder")
            D3D12VariableShadingRate.hardReset(encoder.nativeCtx())
            encoder.clearColorTexture(ui, Vector4f(0.0f, 0.0f, 0.0f, 0.0f))
            // preparedThisFrame 保持 false：这一帧的呈现源是世界层（或主目标），不是这张 UI 层。
            degrade(null)
            return
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

    /** 记录 / 清除降级状态；只在状态变化时打一行，避免每帧刷屏。 */
    private fun degrade(reason: String?) {
        if (lastFailure == reason) {
            return
        }
        lastFailure = reason
        if (reason == null) {
            Illusion.LOGGER.info(
                "GUI split healthy again - {}",
                if (hudlessArmed) {
                    "the GUI target carries the UI layer only (the world is presented separately)"
                } else {
                    "the world layer is blitted into the GUI target"
                })
        } else {
            Illusion.LOGGER.warn("GUI split degraded ({}); the GUI keeps drawing into its own window-sized target, this frame just presents the world target", reason)
        }
    }

    /**
     * P217：本帧最终会被 present 的那张目标；拆分没生效（或已放弃）时返回 null，调用方退回主目标。
     * 供 DLSS 的准星补画使用 —— 拆分启用时界面画在 GUI 目标上，画进主目标等于画进没人读的纹理。
     * P306ac：hudless 模式下呈现源是世界层，这里的 GUI 目标只是 UI 层，准星也不该补画进去。
     */
    @JvmStatic
    fun replayTarget(): RenderTarget? {
        if (!preparedThisFrame || givenUp || hudlessArmed) {
            return null
        }
        return target
    }

    /**
     * P306ac：最终 blit（呈现）该用哪张纹理。
     *
     *  1. hudless 呈现：世界层（SR 输出；SR 没跑就用 vanilla 传进来的主目标 —— 此刻界面已经
     *     画在 UI 层上，主目标里只剩世界层），UI 由帧生成后端合成；
     *  2. 合成模式且本帧真的把世界层搬进了 GUI 目标：GUI 目标（世界 + 界面）；
     *  3. 其余：vanilla 传进来的主目标（与原路径完全一致）。
     */
    @JvmStatic
    fun presentSourceTexture(fallback: D3D12GpuTexture): D3D12GpuTexture {
        if (!givenUp && hudlessArmed) {
            // 世界切换时旧的世界层纹理可能已经释放：宁可回退，也不能把野指针交给 blit。
            val layer = worldLayerThisFrame
            return if (layer != null && !layer.isClosed()) layer else fallback
        }
        if (!preparedThisFrame || givenUp) {
            return fallback
        }
        return (target?.colorTextureView as? D3D12GpuTextureView)?.texture() as? D3D12GpuTexture
            ?: fallback
    }
}
