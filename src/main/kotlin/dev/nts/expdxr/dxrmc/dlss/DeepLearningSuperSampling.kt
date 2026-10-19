package dev.nts.expdxr.dxrmc.dlss

import dev.nts.expdxr.Illusion
import dev.nts.expdxr.d3d12.backend.D3D12CommandEncoderBackend
import dev.nts.expdxr.d3d12.D3D12Settings
import dev.nts.expdxr.d3d12.extern.MCDR_ABI
import dev.nts.expdxr.d3d12.extern.MCDRLayout
import dev.nts.expdxr.d3d12.extern.MCDRLayout.F32
import dev.nts.expdxr.d3d12.extern.MCDRLayout.I32
import dev.nts.expdxr.d3d12.extern.MCDRLayout.PTR
import dev.nts.expdxr.d3d12.render.adapter.D3D12AdapterInfo
import dev.nts.expdxr.d3d12.render.adapter.D3D12Device
import dev.nts.expdxr.d3d12.render.advanced.D3D12AntiAliasing
import dev.nts.expdxr.d3d12.render.advanced.D3D12Sharpen
import dev.nts.expdxr.d3d12.render.advanced.smaa.D3D12GUISplit
import dev.nts.expdxr.d3d12.render.advanced.taa.D3D12Temporal
import dev.nts.expdxr.d3d12.render.advanced.taa.D3D12Velocity
import dev.nts.expdxr.d3d12.render.advanced.vrs.D3D12RenderScale
import dev.nts.expdxr.d3d12.render.core.D3D12GpuTexture
import dev.nts.expdxr.d3d12.render.core.D3D12GpuTextureView
import com.mojang.serialization.Codec
import java.io.File
import java.lang.foreign.Arena
import java.lang.foreign.MemoryLayout.PathElement
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.lang.ref.WeakReference
import java.nio.charset.StandardCharsets
import java.util.Optional
import net.minecraft.client.Minecraft
import net.minecraft.client.OptionInstance
import net.minecraft.client.gui.components.CycleButton
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.client.renderer.DebugCrosshairRenderer
import net.minecraft.client.renderer.state.level.CameraRenderState
import net.minecraft.network.chat.Component

/**
 * P108：DLSS 的**每帧数据面** —— 把这一帧 NGX 要用的东西填进一个缓存好的 native 结构体
 * （`MCDRLayout.DLSS_INPUT`，C sizeof = 112，见 `MCDR\include\mcdr_abi.h:349-400`），
 * 然后在世界层画完、界面还没画的那一刻交给 `mcdr_dx12EvaluateDlss`。
 *
 * P108b（用户 m10920）：DLSS **不再占用抗锯齿的循环档位**（`dxrMcAaMode` 回到 0..3），开关是
 * 这里自己的 [OPTION]（`options.txt` 键 [OPTIONS_KEY] = `dxrMcDlss`，默认 false），挂在原生视频
 * 设置界面第四组"Illusion 设置"里单开的一行按钮上（见
 * `dev.nts.expdxr.mixin.options.VideoSettingsScreenMixin`），持久化由
 * `dev.nts.expdxr.mixin.options.OptionsRenderOptionsMixin` 在 `Options.load`/`save` 尾部接管
 * （[load] / [save]）。闸门因此从"抗锯齿模式 == 4"变成 [isEnabled]。
 *
 * **DLSS 与抗锯齿互斥（用户 m05600）**：DLSS 自带时间性抗锯齿，在它下面再跑一遍我们的 AA 只是把
 * 输入先糊掉再交给网络重建，所以 DLSS 开启期间抗锯齿被锁为 OFF 且界面置灰
 * （`D3D12AntiAliasing.setDlssLock` / `isLockedByDlss`，界面见
 * `dev.nts.expdxr.mixin.options.VideoSettingsScreenMixin`），关掉 DLSS 时还原玩家原先的选择。
 * 这与 P108b（用户 m10920）当时"两者独立、都开时先 SMAA 再 DLSS"的接法不同 —— P108b 解耦的是
 * **native 侧的闸门**（不再共用 `aaMode` 一个数），m05600 解的是**用户可见的取值域**，两者不矛盾。
 * 按钮在 D3D12 后端没驱动渲染时置灰（[isAvailable]，与渲染缩放/着色粒度同一判据）。
 *
 * 接线与取舍（都是实测/反编译得出，不是猜的）：
 *
 * 1. **数据面拆成两处挂点（P108d，用户 m11364）**：注入点见
 *    `mixin.render.GameRendererSmaaWorldMixin` 的两处 `@Inject`：
 *    - **运动矢量**挂在 `clearDepthTexture` 之前（`at = INVOKE …, shift = BEFORE`），也就是
 *      vanilla 在 `GameRenderer.render` 里清主目标深度之前 —— 速度 pass 要读本帧**深度**，
 *      而那一刻深度还有内容（`:443 clearDepthTexture` 早于 `:447 GuiRenderer.render`）。
 *    - **DLSS Evaluate** 改挂在 SMAA 那一处（`GuiRenderer.render()` 之前），**排在 SMAA 之后** ——
 *      因为只有那一刻主目标的**颜色**里才有本帧世界层。原先把 Evaluate 也挂在清深度之前是错的：
 *      实测（native `DXRMC_P108X_COPY_COLOR=1` 把 pInColor 原样拷进输出目标）显示那一时刻的
 *      颜色整张是清屏色 190,179,167（min==max），DLSS 拿常量输入只能得到常量输出（用户看到的
 *      "世界区域整片米白"）。两处注入各判各的开关，互不影响。
 * 2. **运动矢量 pass 必须在清深度之前那处先录一遍**：`D3D12Velocity` 原先只在整帧最后的
 *    `blitFromTexture` 里录（`D3D12GpuSurface.kt:145`），那一刻深度早被清空；而且命令列表是按
 *    录制顺序执行的，晚录 = NGX 读到的是两帧前的旧内容（乒乓）。`D3D12Velocity.record` 里加了
 *    "每帧只录一遍"的闸门，所以 [applyIfEnabled] 里那次兜底调用会变成空操作。
 * 3. **pInOutput = P106 的窗口尺寸目标**（`D3D12GUISplit`）：升采样结果直接写进界面随后要用的
 *    那张纹理，省掉原本的线性 blit；因此 `prepareForGui` 在本帧必须跳过世界层 blit
 *    （`D3D12GUISplit.markWorldLayerDone()`），否则会把 DLSS 的结果盖掉。顺序必须是
 *    SMAA → DLSS → `prepareForGui`（`prepareForGui` 里读走并清零那个标志）。
 *
 * **深度的"清空前快照"（P108e，已接线）**：DLSS 的数据面里**颜色**要等 SMAA 那一刻才齐，而
 * **深度**在 SMAA 那一刻已经被 vanilla 清成 0.0（`GameRenderer.render:443 clearDepthTexture`），
 * 任一 JVM 挂点都无法同时满足两者。native 为此新增了纯追加导出
 * `mcdr_dx12SnapshotDlssDepth(ctx, depth)`（`MCDR_ABI_VERSION` 仍为 2）：在**清深度之前**那次
 * 挂点（[captureMotionVectors]，与运动矢量同一时刻）把深度拷进一张私有 D32 快照纹理并把
 * `depth` 的状态恢复原样，之后 [applyIfEnabled] 的评价优先用快照而不是 `pInDepth`。JVM 侧这里
 * 只多一次 downcall（旧 DLL 没有该导出 ⇒ `Int.MIN_VALUE`，静默退回"用清空后的深度"）。
 *
 *
 * 数据面依赖（P108c，用户 m11208）：运动矢量与亚像素 jitter 分别由 `dxrmc.temporal.velocity` /
 * `dxrmc.temporal` 两个**构建期**属性决定（`Illusion\build.gradle:60-107`，默认都是 **false**），
 * 它们只给 [D3D12Temporal.enabled] / [D3D12Velocity.enabled] 两个**运行期**字段赋初值。打开 DLSS
 * 开关就意味着这两样都必须有，所以本类在**开关的两条路径**（界面回调 [push]、`options.txt` 载入
 * [load]）都把这两个字段置 true，并在渲染开始后按需清掉速度通道的自我关闭闩锁
 * （[retryVelocityIfLatched]）—— 于是"选了 DLSS 就有数据面"成立，**不需要任何 `-P` 参数**。
 * 关掉开关时只还回**自己打开的那两个**（用户原本就开着的不动），并打一行 INFO。
 * 不这样做的话 `pInMotionVectors` 恒为空句柄，native 会在 `d3d12_dlss.cpp:886` 直接返回 0，
 * DLSS 永远不评价。
 *
 * **投影 jitter 与 TAA 共用同一份序列（P110）**：`D3D12Temporal` 每帧把 Halton(2,3) 的亚像素偏移
 * 注入关卡投影，本类只把同一个值转发给 NGX（`InJitterOffsetX/Y`）。历史记录里"冻结 jitter 才不塌屏"
 * 的结论已作废：那次实验的干扰项是 GPU 写映射读回（已永久关闭）与 `readFloats` 的字节序 bug —— 后者
 * 让注入的一直是两个字节反转的垃圾 float，等于从没抖过。
 *
 * 拿不到/降级（诚实清单）：
 *  - `nearZ`/`farZ` 填 0.0 并打一行说明：26.2 的关卡 `Projection` 在 JVM 侧没有可达对象
 *    （`GameRenderer` 只有 `hudProjection` 字段，关卡投影是别处的局部对象），而 native 只用这两个
 *    字段写日志与做变化检测（`d3d12_dlss.cpp:678-711`），不进任何 NGX 参数。
 *  - `dxrMcRenderScale == 100%` 时没有独立的窗口尺寸目标，没有第二张纹理承接升采样结果 ⇒ 本帧跳过
 *    并说明原因（NGX 要求输入与输出是两张资源）。
 *  - 逐物体运动矢量、透明掩码、GPU 曝光纹理都不在本轮范围（native 已按"相机重投影 + 常数曝光"实现）。
 *
 * 一切失败（尺寸退化、写包抛异常、native 返回 0/-1）都只是**跳过这一帧**：不新建 pass、不改
 * 资源状态、不让这一帧渲染不出来。返回 1 之后四个指针的资源状态由 native 负责留在
 * `PIXEL_SHADER_RESOURCE`（颜色/深度/运动矢量）与 `RENDER_TARGET`（输出），JVM 侧不再手动 transition。
 */
object DeepLearningSuperSampling {

    /** 日志前缀：与 native 侧的 `P108:` 行用同一个标签，实机复验只看 latest.log 就够。 */
    private const val PREFIX: String = "P108:"

    /**
     * `mvScaleX/Y = 1.0`：`D3D12Velocity` 写出的已经是 `(uv - prevUv) * ScreenSize` 的**像素**
     * 位移（`MCDR\include\mcdr_abi.h:374`）。
     */
    private const val MV_SCALE: Float = 1.0f

    /** 常数曝光：1.0 = 颜色缓冲已经是显示单位（`mcdr_abi.h:379`）。 */
    private const val PRE_EXPOSURE: Float = 1.0f

    /**
     * P108z 失效保护：native 连续 evaluate 失败多少帧后打一行 WARN。失败帧不会跳过世界层 blit
     * （见 [countEvaluateFailure] / [onEvaluated]），所以这个阈值只影响日志，不影响画面。
     */
    private const val EVALUATE_FAILURE_WARN_THRESHOLD: Int = 30

    /** 只有 [EXPOSURE_MODE_NONE] 时 native 才读它（`mcdr_abi.h:382`）。 */
    private const val EXPOSURE_SCALE: Float = 1.0f

    /** `MCDR_DLSS_EXPOSURE_NONE`（`mcdr_abi.h:346`）：没有 GPU 曝光纹理，走常数曝光。 */
    private const val EXPOSURE_MODE_NONE: Int = 0

    /** 本渲染器是 reverse-Z，native 要求恒为 1（`mcdr_abi.h:389`）。 */
    private const val DEPTH_INVERTED: Int = 1

    /** 世界层不是带 alpha 的合成目标，不升采样 alpha（`mcdr_abi.h:384`）。 */
    private const val ALPHA_UPSCALING: Int = 0

    /**
     * 近/远平面：**JVM 侧取不到**（见类注释）。native 只把它们写进 `P108: DLSS input:` 诊断行，
     * 不参与任何 NGX 参数，所以填 0.0 表示"未知"，而不是编一个数。
     */
    private const val NEAR_Z: Float = 0.0f
    private const val FAR_Z: Float = 0.0f

    /** `dx12QueryDlss` 的说明文本缓冲（native 用 snprintf 写，最多 255 字符 + NUL）。 */
    private const val NOTE_CAPACITY: Int = 256

    /** 成功闸门的哨兵（[lastState] 用），与"没跑"的各种原因串区分开。 */
    private const val STATUS_EVALUATED: String = "evaluated"

    /**
     * 速度通道闩锁的**限速重试**间隔（1 秒，P108e）。
     *
     * 额度式的"每会话一次"（P108c 的实现）有闭环风险：首帧速度通道就闩锁 ⇒ 没有
     * 运动矢量 ⇒ DLSS 永远不评价 ⇒ 永远不批额度 ⇒ [D3D12Velocity.forceEnable] 永远不被调用。
     * 改成"闩锁期间最多每 1 秒重试一次"：既能自救，也不会每帧重建资源 + 刷屏。
     */
    private const val VELOCITY_RETRY_INTERVAL_NANOS: Long = 1_000_000_000L

    // ------------------------------------------------------------------
    // 会话状态（只在渲染线程上读写）
    // ------------------------------------------------------------------

    /** 本会话唯一的一块 native 内存 + 里面的两个 segment（**不每帧新建 Arena**）。 */
    private var arena: Arena? = null
    private var packetSegment: MemorySegment? = null
    private var noteSegment: MemorySegment? = null

    /** 写包/调用抛出异常时置位：只报一次，之后本会话不再喂数据。 */
    private var broken = false

    /**
     * 是否已经在 DLSS 模式下跑过（用来判断"离开 DLSS"要不要复位状态）。
     *
     * `@Volatile`：DLSS 开关的回调（`options.txt` 载入 / 视频设置界面按按钮）跑在**主线程**，
     * 而 [captureMotionVectors] / [applyIfEnabled] / [leaveDlssMode] 在**渲染线程**读写本字段 ——
     * 游戏跑着的时候也能开 DLSS，两块线程确实会重叠。
     */
    @Volatile
    private var modeActive = false

    /**
     * 数据面开关是不是我们自己打开的（只还自己打开的那些）。
     *
     * `@Volatile`：`options.txt` 载入与视频设置界面按下按钮时由**主线程**写这些字段，而
     * [applyIfEnabled] 在**渲染线程**读/写它们（两块线程确实会重叠：游戏跑着的时候也能开 DLSS）。
     */
    @Volatile
    private var forcedTemporal = false

    @Volatile
    private var forcedVelocity = false

    /**
     * 上一次限速重试速度通道的 `System.nanoTime()`（P108e）；[Long.MIN_VALUE] = 本会话还没重试过
     * （首次立即允许）。用 nanoTime 而不是帧计数：这是**渲染线程**上的节流，不需要另一个线程的
     * 帧号，也不会因为掉帧而放宽到几秒。[leaveDlssMode] 把它复位成 [Long.MIN_VALUE]。
     *
     * 只在渲染线程读写（[retryVelocityIfLatched]），所以不需要 `@Volatile`。
     */
    private var lastVelocityRetryNanos: Long = Long.MIN_VALUE

    /**
     * `dx12QueryDlss` 是否已经探过（离开 DLSS 后复位，允许回到 DLSS 时重探一次）。
     *
     * `@Volatile`：开关回调（主线程）里的 [push] 路径会在"旧 DLL"分支上把 DLSS 重新拉起来，
     * 而 [ensureAvailable] 在渲染线程用本字段做"每会话只探一次"的闸门（[available] / [ngxSummary]
     * 同理 —— 它们是本字段的伴生结论，渲染线程的日志与决策都要读到主线程推过开关之后的值）。
     */
    @Volatile
    private var availabilityProbed = false

    @Volatile
    private var available = false

    @Volatile
    private var ngxSummary: String = "(not queried)"

    /** 每会话一次的"数据真的喂进去了"结论行。 */
    private var loggedFed = false

    private var lastState: String? = null

    /** 深度快照（P108e）最后一次的结论串。与 [lastState] **分开**记：快照在清深度之前那次挂点、评价在 SMAA 那次挂点，同一帧两个时刻共用一个"上次状态"会互相覆盖，结果两边都刷屏。 */
    private var lastDepthSnapshotState: String? = null


    /** 上一次**真正评价成功**时的输入状态，用来判 `reset`。 */
    private var evaluatedOnce = false
    private var lastRenderWidth = -1
    private var lastRenderHeight = -1
    private var lastTargetWidth = -1
    private var lastTargetHeight = -1
    private var lastScalePercent = -1
    private var lastWindowWidth = -1
    private var lastWindowHeight = -1
    private var lastLevel: WeakReference<Any>? = null

    // ------------------------------------------------------------------
    // 选项：视频设置里的开关 + options.txt 持久化（P108b）
    // ------------------------------------------------------------------

    /** `options.txt` 里的键名；与 `dxrMcAaMode`/`dxrMcRenderScale`/`dxrMcShadingRate` 同一套管道，取值是 vanilla 的布尔字面量（`true`/`false`）。*/
    const val OPTIONS_KEY: String = "dxrMcDlss"

    /**
     * 视频设置第四组"Illusion 设置"里的 DLSS 开关
     *
     * 用原版的 [OptionInstance.createBoolean]：它给出一个 ON/OFF 的循环按钮，取值名直接用原版
     * `options.on`/`options.off`，不必为本项目新增取值格式键；`createButton` 造出来的按钮会被
     * `OptionsList.findOption` 按**同一个 OptionInstance 实例**认出来，[dev.nts.expdxr.mixin.options.VideoSettingsScreenMixin]
     * 的置灰才找得到它。回调里把开关推给 native（[push]），`options.txt` 的读写由 [load]/[save] 负责。
     */
    private val OPTION: OptionInstance<Boolean> = OptionInstance.createBoolean(
        "options.dlss",
        { Tooltip.create(Component.translatable("options.dlss.tooltip")) },
        false,
        { value -> push(value) },
    )

    @JvmStatic
    fun option(): OptionInstance<Boolean> = OPTION

    // ------------------------------------------------------------------
    // P108y（用户 m11147）：DLSS 挡位 —— Performance / Balanced / Quality
    // ------------------------------------------------------------------

    /** `options.txt` 里的挡位键。 */
    const val QUALITY_OPTIONS_KEY: String = "dxrMcDlssQuality"

    /**
     * 挡位的取值名，**下标即下发给 native 的编号**（`pushQuality` 用 `ordinal`）——不要重排。
     *
     * 展示名走原版 lang 键 `options.dlss.quality.<name>`（中英各一份）。
     */
    private val QUALITY_NAMES: List<String> = listOf("performance", "balanced", "quality")

    /** 默认挡位 = Quality（= 2）：与本机此前 native `chooseQuality` 选出的档位一致，行为不变。 */
    private const val QUALITY_DEFAULT: Int = 2

    /**
     * 挡位按钮（用户 m11147 原话："dlss 的'性能、平衡、质量'挡位单独做成按钮调整"）。
     *
     * <p>用原版 [OptionInstance] 的**循环按钮**路径：值域实现 `OptionInstance.CycleableValueSet`
     * （照 `OptionInstance.LazyEnum` 的形状），取值文案由 `toString` 给出 —— `options.dlss.quality`
     * 是选项名，三个取值名各自一条 lang 键。与 DLSS 开关同一套管道（[load] / [save] /
     * `VideoSettingsScreenMixin` 的置灰与 `list.findOption`）。回调 [pushQuality] 把编号推给 native；
     * native 还没有 `mcdr_dx12SetDlssQuality` 时静默容忍并只打一次 INFO。
     */
    private object QualityValues : OptionInstance.CycleableValueSet<Int> {
        override fun validateValue(value: Int): Optional<Int> =
            if (value in QUALITY_NAMES.indices) Optional.of(value) else Optional.empty()

        override fun valueListSupplier(): CycleButton.ValueListSupplier<Int> =
            CycleButton.ValueListSupplier.create(QUALITY_NAMES.indices.toList())

        override fun codec(): Codec<Int> = Codec.intRange(0, QUALITY_NAMES.size - 1)
    }

    private val QUALITY_OPTION: OptionInstance<Int> = OptionInstance(
        "options.dlss.quality",
        { Tooltip.create(Component.translatable("options.dlss.quality.tooltip")) },
        { _, value -> Component.translatable("options.dlss.quality.${QUALITY_NAMES[value]}") },
        QualityValues,
        QUALITY_DEFAULT,
        { value -> pushQuality(value) },
    )

    @JvmStatic
    fun qualityOption(): OptionInstance<Int> = QUALITY_OPTION

    /** 界面里的当前挡位（0=Performance 1=Balanced 2=Quality），也是写回 `options.txt` 的值。 */
    @JvmStatic
    fun quality(): Int = QUALITY_OPTION.get()

    /**
     * 把挡位推给 native（`mcdr_dx12SetDlssQuality`，编号 = [QUALITY_NAMES] 下标）。
     *
     * <p>**返回值契约（用户 m11858，从 native 读出来的，别再猜）**：
     * `d3d12_mc::setDlssQuality`（`MCDR\src\core\d3d12_device.cpp`）在成功时**返回传进来的档位本身**
     * （0=Performance / 1=Balanced / 2=Quality / 3=DLAA），非法档位返回 **-1**。
     * 所以 **返回 0 是"Performance 档已生效"，不是失败** —— 旧代码把 0 当"DLSS 不能跑"并打 WARN，
     * 于是玩家一选 Performance 就会看到假警告。
     *
     * <p>本地 DLL 还没有这条导出时（`Int.MIN_VALUE`）只打**一次** INFO 说明仍由 native 按比例就近
     * 选择，不刷屏、不影响其它功能；`MCDR_ABI_VERSION` 保持 2（走 `skipOptional`）。
     */
    private fun pushQuality(value: Int) {
        val status = MCDR_ABI.D3D12SetDlssQuality(value)
        when {
            status == value -> Illusion.LOGGER.info(
                "[dxrmc] $PREFIX DLSS quality = {}", QUALITY_NAMES[value])
            status == -1 -> Illusion.LOGGER.warn(
                "[dxrmc] $PREFIX mcdr_dx12SetDlssQuality({}) was rejected (-1: out of range)", value)
            status == Int.MIN_VALUE -> if (!loggedQualityUnsupported) {
                loggedQualityUnsupported = true
                Illusion.LOGGER.info(
                    "[dxrmc] $PREFIX the loaded MCDR.dll has no mcdr_dx12SetDlssQuality, so the "
                        + "Performance/Balanced/Quality button is remembered but native keeps picking "
                        + "the quality level from the render ratio")
            }
            else -> Illusion.LOGGER.warn(
                "[dxrmc] $PREFIX mcdr_dx12SetDlssQuality({}) returned {} (expected the tier back, "
                    + "or -1 when out of range)", value, status)
        }
    }

    /** 「本地 DLL 没有挡位导出」只报一次。 */
    @Volatile
    private var loggedQualityUnsupported = false

    // ------------------------------------------------------------------
    // P108ac（用户 m08033）：DLSS 模型 —— 换 render preset
    // ------------------------------------------------------------------

    /** `options.txt` 里的模型键。 */
    const val PRESET_OPTIONS_KEY: String = "dxrMcDlssPreset"

    /**
     * 模型按钮的取值名，**下标是界面上的循环顺序，不是下发给 native 的编号**（映射见 [PRESET_VALUES]）。
     *
     * 用户原话（m08033）："剩下的噪点闪烁是 dlss 自身的问题，所有使用 dlss 的游戏都会出现这种现象，
     * 优化方法是更换 dlss 的模型"。所以这里把 NVIDIA 文档里点名的 transformer 预设做成按钮，
     * 而不是由我们擅自改默认值。
     *
     * - `default`：不设提示键，交给 NGX 按挡位自选 —— 历史行为，默认值。
     * - `j`：与 K 同代，官方注释说"残影更少、但闪烁更多"。
     * - `k`：官方注释说"A/B 里推荐的画质最佳"（DLAA/Balanced/Quality 档的默认值）。
     * - `l` / `m`：分别是 Ultra Performance / Performance 档的官方默认值。
     *
     * CNN 时代的 E/F 已弃用，G/H/I 被文档标为 reserved，都不放上来。
     */
    private val PRESET_NAMES: List<String> = listOf("default", "j", "k", "l", "m")

    /**
     * [PRESET_NAMES] 各下标对应的 `NVSDK_NGX_DLSS_Hint_Render_Preset` 值：
     * 0 = Default、10 = J、11 = K、12 = L、13 = M。native 侧按这些值设 NGX 参数。
     */
    private val PRESET_VALUES: List<Int> = listOf(0, 10, 11, 12, 13)

    /** 默认 = `default`（不改变 native 的历史行为，只有玩家主动选才换模型）。 */
    private const val PRESET_DEFAULT: Int = 0

    /**
     * 模型按钮的值域（照 `OptionInstance.LazyEnum` 的形状，与挡位按钮同一套管道）。
     *
     * <p>预设是 `CreateFeature` 的输入，所以换模型时 native 会**重建特征**（会有一帧的世界重建，
     * 与换挡位完全一样）；这一点写进 tooltip，免得玩家以为卡了。
     */
    private object PresetValues : OptionInstance.CycleableValueSet<Int> {
        override fun validateValue(value: Int): Optional<Int> =
            if (value in PRESET_NAMES.indices) Optional.of(value) else Optional.empty()

        override fun valueListSupplier(): CycleButton.ValueListSupplier<Int> =
            CycleButton.ValueListSupplier.create(PRESET_NAMES.indices.toList())

        override fun codec(): Codec<Int> = Codec.intRange(0, PRESET_NAMES.size - 1)
    }

    private val PRESET_OPTION: OptionInstance<Int> = OptionInstance(
        "options.dlss.preset",
        { Tooltip.create(Component.translatable("options.dlss.preset.tooltip")) },
        { _, value -> Component.translatable("options.dlss.preset.${PRESET_NAMES[value]}") },
        PresetValues,
        PRESET_DEFAULT,
        { value -> pushPreset(value) },
    )

    @JvmStatic
    fun presetOption(): OptionInstance<Int> = PRESET_OPTION

    /** 界面里的当前模型（[PRESET_NAMES] 下标），也是写回 `options.txt` 的值。 */
    @JvmStatic
    fun preset(): Int = PRESET_OPTION.get()

    /**
     * 把模型推给 native（`mcdr_dx12SetDlssRenderPreset`，参数是 [PRESET_VALUES] 里的 NGX 枚举值）。
     *
     * <p>返回值契约：`d3d12_mc::setDlssRenderPreset` 成功时**返回记录的预设值本身**（所以
     * `default` 档返回 **0**，那是成功、不是失败），越界返回 **-1**；旧 DLL 没有这条导出时
     * `MCDR_ABI` 返回 [Int.MIN_VALUE]，这里只打一次 INFO。
     */
    private fun pushPreset(index: Int) {
        val value = PRESET_VALUES[index]
        val status = MCDR_ABI.D3D12SetDlssRenderPreset(value)
        when {
            status == value -> Illusion.LOGGER.info(
                "[dxrmc] $PREFIX DLSS model preset = {} (NGX render preset {})",
                PRESET_NAMES[index], value)
            status == -1 -> Illusion.LOGGER.warn(
                "[dxrmc] $PREFIX mcdr_dx12SetDlssRenderPreset({}) was rejected (-1: out of range)",
                value)
            status == Int.MIN_VALUE -> if (!loggedPresetUnsupported) {
                loggedPresetUnsupported = true
                Illusion.LOGGER.info(
                    "[dxrmc] $PREFIX the loaded MCDR.dll has no mcdr_dx12SetDlssRenderPreset, so "
                        + "the DLSS model button is remembered but native keeps NGX's own per-tier "
                        + "preset")
            }
            else -> Illusion.LOGGER.warn(
                "[dxrmc] $PREFIX mcdr_dx12SetDlssRenderPreset({}) returned {} (expected the preset "
                    + "back, or -1 when out of range)", value, status)
        }
    }

    /** 「本地 DLL 没有模型导出」只报一次。 */
    @Volatile
    private var loggedPresetUnsupported = false

    /**
     * DLSS 是否在工作：独立开关 [OPTION] **或** 抗锯齿停在 DLAA（P108aa，用户 m11796 —— DLAA
     * 就是"原生分辨率的 DLSS"，由抗锯齿选项接管，此时那个独立开关在界面上置灰）。
     */
    @JvmStatic
    fun isEnabled(): Boolean = OPTION.get() || dlaaActive

    /** True while the game really renders through DXRMC's D3D12 backend（非 D3D12 时按钮置灰）。 */
    @JvmStatic
    fun isAvailable(): Boolean = D3D12AdapterInfo.current != null

    /**
     * 用户 m05600：**DLSS 开启时抗锯齿锁为 OFF 且不可调**。
     *
     * 为什么：DLSS 自带时间性抗锯齿，在它下面再跑一遍我们的 AA 只是把输入先糊掉、再交给网络去重建。
     * 锁成 OFF 后世界层原样交给 DLSS，与 NVIDIA 的推荐接法一致。
     *
     * 这里只做"把状态推给抗锯齿那一侧"，取值快照与还原都在 `D3D12AntiAliasing.setDlssLock` 里
     * （本类不碰它的 `OptionInstance`；依赖只能单向）。调用点是**所有**能改变开关的路径 ——
     * 界面上点按钮（[push]）与从 `options.txt` 恢复（[load]）—— 外加每帧一次兜底重算
     * （[reconcileAntiAliasing]，挡住"两条 TAIL 注入顺序不定"与画质预设那两个旁路）。
     */
    private fun reconcileAntiAliasing() {
        // P108aa（用户 m11796）：DLAA 是"原生分辨率的 DLSS"，由**抗锯齿选项**接管。先把这件事
        // 同步过来，再决定 DLSS→AA 的锁定要不要施加。
        syncDlaaFromAntiAliasing()
        // P130（p95/p96 的日志事实：同一次 options 处理里 native 先收到 enabled、紧接着又收到
        // disabled）：`syncDlaaFromAntiAliasing` 只在**状态翻转**时推一次闸门，
        // 而 `push(false)`（`options.txt` 里 `dxrMcDlss=false` 的加载、或用户关掉 DLSS 开关）
        // 会随后把它关掉，之后没有任何路径再打开 —— native 的 `applyDlssToTexture` 在
        // `!gCtx.dlssEnabled` 时直接 `return false`，连 `mcdr_dlss_evaluate` 都不会进
        // （所以 P128/P124 一行都没有，而 JVM 侧 P121 一直在推常量）。
        // 闸门因此必须**每帧对齐**到"DLAA 或 DLSS 开关"，而不是只在翻转时推一次。
        // `setDlssEnabled` 只在值变化时打日志，所以每帧对齐不会刷屏。
        //
        // p96 实测：**不能**用"缓存上一次推的值"来省这次调用 —— 同一次 options 处理里
        // `push(false)` 会在本函数之后把 native 改成 0，而缓存里仍是 true，于是下一帧
        // 认为"已经一致"不再推，闸门永久关死（P129 的 23 条 `gate=dlss-switch-off` 就是这么来的）。
        // 每帧无条件对齐，谁改过都不影响。
        forceDlssGate(dlaaActive || OPTION.get())
        // DLAA 生效时**不能**把抗锯齿锁成 OFF（那样会立刻退出 DLAA，自相矛盾）。
        if (dlaaActive) {
            return
        }
        D3D12AntiAliasing.setDlssLock(isEnabled())
    }

    /**
     * P108aa（用户 m11796）：把"抗锯齿选项是不是 DLAA"同步成 [dlaaActive]。
     *
     * <p>放在这里而不是让 `D3D12AntiAliasing` 反向调本类，是为了保持既有的单向依赖
     * （本类读 AA 的取值，AA 不知道本类存在）。[reconcileAntiAliasing] 每帧都会跑，所以
     * 用界面按钮、`options.txt` 恢复、画质预设这三条路径都能覆盖到。
     */
    private fun syncDlaaFromAntiAliasing() {
        val want = D3D12AntiAliasing.current() == D3D12AntiAliasing.DLAA
        if (want == dlaaActive) {
            return
        }
        dlaaActive = want
        // DLAA 必须 render == target：把缩放缓条钉在 100；离开时恢复锁定前该模式的用户值
        // （DLAA 期间 `OPTION` 的 100 不写回两个记忆键，见 `D3D12RenderScale.apply/save`）。
        D3D12RenderScale.setDlaaLock(want)
        if (want) {
            // 让 native 打开 DLSS 闸门（DLAA 走同一条 DLSS 特性链路），并请求 DLAA 档位。
            forceDlssGate(true)
            // P108aa（A3）：这里只在状态翻转时推**一次** 3，绝不每帧重推 —— native 已经把
            // "本轮实际接受的档位"当作基线：若这一帧两者都还没到 100%（缩放重建排在下一帧），
            // native 只降级一次并只打一条 ERROR；等主目标真正到 100%、尺寸变化触发重建时，
            // `requestedQuality == 3` 仍在，native 会自己升级回 DLAA。JVM 侧因此没有任何
            // 每帧 downcall 路径，也不需要在降级后反复推 3。
            val pinned = D3D12RenderScale.percent()
            if (pinned != 100) {
                Illusion.LOGGER.warn(
                    "[dxrmc] $PREFIX DLAA lock did not pin the render scale to 100% (got {}%); "
                        + "native will accept one downgrade and self-heal when the scale reaches 100%",
                    pinned,
                )
            }
            pushForcedQuality(DLAA_QUALITY)
            Illusion.LOGGER.info(
                "[dxrmc] $PREFIX DLAA engaged: anti-aliasing = DLAA, render scale pinned to 100% "
                    + "(render == target is required), DLSS feature requested at the DLAA tier. "
                    + "P110b: the projection carries the same Halton jitter sequence as TAA now, "
                    + "so NGX gets real sub-pixel samples (InJitterOffset + MVJittered) instead of "
                    + "the jitter-free accumulation this path used to fall back to.")
        } else {
            // 还原用户自己选的挡位；缩放缓条由 setDlaaLock(false) 恢复到锁定前的用户值。
            pushForcedQuality(QUALITY_OPTION.get())
            Illusion.LOGGER.info(
                "[dxrmc] $PREFIX DLAA released: quality back to the user's own tier ({})",
                QUALITY_NAMES[QUALITY_OPTION.get()])
            // 退出 DLAA 后 DLSS 是否该开，交回独立开关（下一帧的 reconcile 会处理锁定/还原）。
        }
    }

    /** DLAA 在 NGX 里的挡位值（native 侧 `NVSDK_NGX_PerfQuality_Value_DLAA`）。 */
    private const val DLAA_QUALITY: Int = 3

    /** 只推 DLSS 闸门、不碰缩放模式（DLAA 用）。 */
    private fun forceDlssGate(enabled: Boolean) {
        val flag = if (enabled) 1 else 0
        try {
            // 契约：返回的是**调用后的状态**（1=开 / 0=关），0 不是错误（见 [push] 的说明）。
            when (val status = MCDR_ABI.D3D12SetDlssEnabled(flag)) {
                1 -> if (!enabled) Illusion.LOGGER.warn(
                    "[dxrmc] $PREFIX asked to open the DLSS gate for DLAA but native reports it is off")
                0 -> if (enabled) Illusion.LOGGER.warn(
                    "[dxrmc] $PREFIX asked to open the DLSS gate for DLAA but native reports it is off")
                Int.MIN_VALUE -> Illusion.LOGGER.info(
                    "[dxrmc] $PREFIX the loaded MCDR.dll has no mcdr_dx12SetDlssEnabled; "
                        + "DLAA relies on the legacy aaMode bridge")
                else -> Illusion.LOGGER.warn(
                    "[dxrmc] $PREFIX mcdr_dx12SetDlssEnabled({}) returned {}", flag, status)
            }
        } catch (t: Throwable) {
            Illusion.LOGGER.warn("[dxrmc] $PREFIX pushing the DLSS gate for DLAA threw: {}", t)
        }
    }

    /**
     * 直接下发一个挡位（0=Performance 1=Balanced 2=Quality 3=DLAA），绕过 [QUALITY_OPTION] ——
     * DLAA 的 3 不是用户可选值，用户自己的选择仍然由那个按钮记着（离开 DLAA 时还原）。
     */
    private fun pushForcedQuality(value: Int) {
        try {
            // 契约同 [pushQuality]：成功时返回**传进来的档位本身**（0 = Performance 也是成功），
            // -1 = 越界，MIN_VALUE = 没有该导出。
            val status = MCDR_ABI.D3D12SetDlssQuality(value)
            when {
                status == value -> Illusion.LOGGER.info(
                    "[dxrmc] $PREFIX DLSS quality = {}", QUALITY_NAMES.getOrElse(value) { "DLAA" })
                status == -1 -> Illusion.LOGGER.warn(
                    "[dxrmc] $PREFIX mcdr_dx12SetDlssQuality({}) was rejected (-1: out of range)", value)
                status == Int.MIN_VALUE -> Illusion.LOGGER.info(
                    "[dxrmc] $PREFIX the loaded MCDR.dll has no mcdr_dx12SetDlssQuality; the "
                        + "quality tier stays whatever native picked")
                else -> Illusion.LOGGER.warn(
                    "[dxrmc] $PREFIX mcdr_dx12SetDlssQuality({}) returned {} (expected the tier back, "
                        + "or -1 when out of range)", value, status)
            }
        } catch (t: Throwable) {
            Illusion.LOGGER.warn("[dxrmc] $PREFIX pushing DLSS quality {} threw: {}", value, t)
        }
    }

    /**
     * 每帧的兜底重算。放在运动矢量挂点（`GameRenderer.render` 的 `clearDepthTexture` 之前）
     * 而不是 [applyIfEnabled]：后者只在 DLSS 开着时才有意义，而"DLSS 关掉 => 还原抗锯齿"
     * 恰恰发生在开关为 false 的时候，挂在那里会永远还原不了。
     *
     * 幂等且几乎零成本：`setDlssLock` 只在状态真的翻转时动手，稳态下就是一次 volatile 读。
     */
    @JvmStatic
    fun reconcileAntiAliasingEveryFrame() {
        reconcileAntiAliasing()
    }

    /**
     * 开关 -> native：优先走 P108b 的新导出 `mcdr_dx12SetDlssEnabled`，它让"DXRMC 的 DLSS 开关"与
     * "抗锯齿模式"在 native 侧彻底解耦（两者都开时先 SMAA 再让 DLSS 升采样）。
     *
     * <p>**返回值契约（用户 m11858，从 native 读出来的，别再猜）**：
     * `d3d12_mc::setDlssEnabled`（`MCDR\src\core\d3d12_device.cpp:7090-7103`）返回的是
     * **调用后的状态**：`1 = 现在是开` / `0 = 现在是关`。所以 `push(false)` 拿到 **0 是正常的**，
     * 它表示"已记录为关闭"，**不是**"本机跑不了 DLSS"。
     * 旧代码把 0 当成"不可用"并打 WARN —— 于是用户**每次关掉 DLSS 都会看到一条假的
     * "DLSS cannot run on this machine"**（m11858 就是这么来的）。
     *
     * <p>"本机到底能不能跑"只由 `mcdr_dx12QueryDlss` 回答（见 [noteState] 那条可用性探测）。
     *
     * @return 1 = 现在是开，0 = 现在是关，[Int.MIN_VALUE] = 本地 DLL 没有这个导出 ⇒ 走
     *     [legacyPush]。（native 不会返回 -1。）
     */
    private fun push(enabled: Boolean) {
        // 用户 m05600：开关一变先把抗锯齿那一侧锁上/解开，再推 native —— 两个选项在界面上必须
        // 立刻自洽（玩家刚点完 DLSS，回头就该看到抗锯齿已置灰）。
        reconcileAntiAliasing()
        val flag = if (enabled) 1 else 0
        when (val status = MCDR_ABI.D3D12SetDlssEnabled(flag)) {
            1 -> Illusion.LOGGER.info("[dxrmc] $PREFIX DLSS upscaling = on")
            0 -> Illusion.LOGGER.info(
                "[dxrmc] $PREFIX DLSS upscaling = off (the world layer goes through the linear blit)")
            Int.MIN_VALUE -> legacyPush(enabled)
            else -> Illusion.LOGGER.warn(
                "[dxrmc] $PREFIX mcdr_dx12SetDlssEnabled({}) returned {} (expected the post-call "
                    + "state: 1 = on, 0 = off)", flag, status)
        }
        // P108y（用户 m11139 / m11147）：渲染缩放的有效集合随 DLSS 开关切换（DLSS 关 25..400 步进 25、
        // DLSS 开 50..90 步进 10），两种模式各自记住自己的值并各自持久化。
        // 这里用**只置标志**的静态入口，绝不去初始化 DLSS 对象（避免类初始化顺序耦合）。
        D3D12RenderScale.setDlssMode(enabled)
    }

    // ------------------------------------------------------------------
    // 临时桥：旧 DLL 只认 aaMode == 4（等 native 换成 mcdr_dx12SetDlssEnabled 后整段删除）
    // ------------------------------------------------------------------

    /**
     * 旧 DLL 里"DLSS 打开"的表达方式。[D3D12AntiAliasing] 的 `MODES` 已经不含 4，所以只能直接下探，
     * 不能经 [D3D12AntiAliasing.option]（它的 `validateValue` 会把 4 当非法值拒掉）。
     */
    private const val LEGACY_DLSS_AA_MODE: Int = 4

    /** 本地 DLL 是不是还没有 `mcdr_dx12SetDlssEnabled`（第一次下探就能确定，之后不变）。 */
    @Volatile
    private var legacyBridge = false

    /**
     * 临时桥：上一次下探时读到的**抗锯齿选项**值。它一变就说明有人（[D3D12AntiAliasing.push]：
     * 玩家点了抗锯齿按钮，或 `Options.load` 里那次回推）往 native 推了"非 4"的模式，把 DLSS 的
     * 闸门顶掉了 —— 见 [reassertLegacyAaMode]。
     */
    @Volatile
    private var legacyAaObserved = Int.MIN_VALUE

    /**
     * 临时桥（**等 native 换成 `mcdr_dx12SetDlssEnabled` 之后整段删除**）：旧 `MCDR.dll` 没有那个
     * 导出，它只认"`aaMode == 4` 就是 DLSS"，所以这里代它把抗锯齿模式当开关用。
     *
     * 三个必须写清的细节：
     *  - **不走 [D3D12AntiAliasing.OPTION]**：`MODES` 里已经不再有 4，直接调
     *    [MCDR_ABI.D3D12SetAntiAliasing] 才是绕开选项校验的那条路。
     *  - 关掉 DLSS 时要把抗锯齿**还回去**：SMAA 选项还在就还 1，否则还 0（TAA/DLAA 在 native 侧
     *    本来就会被降级成 OFF，所以不必区分）。
     *  - **旧 DLL 上 SMAA 与 DLSS 无法共存**：`aaMode == 4` 时 native 的 `applySmaaToTexture`
     *    一趟都不跑，所以这一路径下"两者都开"实际只有 DLSS 生效（新 DLL 没有这个限制）。
     */
    private fun legacyPush(enabled: Boolean) {
        legacyBridge = true
        legacyAaObserved = D3D12AntiAliasing.current()
        if (enabled) {
            MCDR_ABI.D3D12SetAntiAliasing(LEGACY_DLSS_AA_MODE)
            Illusion.LOGGER.warn("[dxrmc] $PREFIX the loaded MCDR.dll has no mcdr_dx12SetDlssEnabled "
                + "(it predates P108b), so DLSS goes through the legacy aaMode == 4 path; SMAA cannot "
                + "run at the same time on that DLL")
        } else {
            MCDR_ABI.D3D12SetAntiAliasing(
                if (legacyAaObserved == D3D12AntiAliasing.SMAA) D3D12AntiAliasing.SMAA
                else D3D12AntiAliasing.OFF,
            )
        }
    }

    /**
     * 临时桥：每帧进入 [applyIfEnabled] 时确认一次 DLSS 的闸门没被顶掉。
     *
     * 为什么需要：`Options.load` 上有两个 TAIL 注入（`OptionsRenderOptionsMixin` 与
     * `OptionsAntiAliasingMixin`），它们的先后顺序不由我们决定；后跑的那个如果推的是抗锯齿模式，
     * 启动时就等于把 `aaMode == 4` 抹掉了。玩家在 DLSS 开着时改抗锯齿同理。判据用"抗锯齿**选项**值
     * 有没有变过"——它每次下探都会被推给 native，而 DLSS 用的 4 永远不会出现在这个选项里 ——
     * 于是这两种情况最多各多推一次，正常帧一次 native 调用都不发。
     */
    private fun reassertLegacyAaMode() {
        val aa = D3D12AntiAliasing.current()
        if (aa == legacyAaObserved) {
            return
        }
        legacyAaObserved = aa
        MCDR_ABI.D3D12SetAntiAliasing(LEGACY_DLSS_AA_MODE)
    }

    // ------------------------------------------------------------------
    // options.txt（键 [OPTIONS_KEY]，行格式 `key:jsonValue`）
    // ------------------------------------------------------------------

    /**
     * 从 vanilla 的 `options.txt` 恢复开关。`Options.load` 时游戏还没 `isRunning()`，
     * `OptionInstance.set` 不会触发回调（与 [D3D12AntiAliasing.load] 同理），所以末尾自己推一次
     * native；这时设备通常还没建，native 只记下开关，下一帧/下次 blit 才生效。
     */
    @JvmStatic
    fun load(file: File) {
        if (!file.isFile) {
            return
        }
        try {
            val lines = file.readLines(StandardCharsets.UTF_8)
            for (line in lines) {
                if (!line.startsWith("$OPTIONS_KEY:")) {
                    continue
                }
                // vanilla 写布尔用 true/false；1/0 是手改过的文件，一并认下来，其余当没写。
                val value = when (line.substring(OPTIONS_KEY.length + 1).trim().lowercase()) {
                    "true", "1" -> true
                    "false", "0" -> false
                    else -> null
                }
                if (value != null) {
                    OPTION.set(value)
                }
                break
            }
            // P108y：挡位（`dxrMcDlssQuality`），认名字（performance/balanced/quality）与编号两种写法。
            for (line in lines) {
                if (!line.startsWith("$QUALITY_OPTIONS_KEY:")) {
                    continue
                }
                val raw = line.substring(QUALITY_OPTIONS_KEY.length + 1).trim().lowercase()
                val value = QUALITY_NAMES.indexOf(raw).takeIf { it >= 0 } ?: raw.toIntOrNull()
                if (value != null && value in QUALITY_NAMES.indices) {
                    QUALITY_OPTION.set(value)
                }
                break
            }
            // P108ac：模型（`dxrMcDlssPreset`），认名字（default/j/k/l/m）与 NGX 数值
            // （0/10/11/12/13）两种写法 —— 手改过文件的人多半写数字。
            for (line in lines) {
                if (!line.startsWith("$PRESET_OPTIONS_KEY:")) {
                    continue
                }
                val raw = line.substring(PRESET_OPTIONS_KEY.length + 1).trim().lowercase()
                val byName = PRESET_NAMES.indexOf(raw).takeIf { it >= 0 }
                val byValue = raw.toIntOrNull()?.let { ngxValue -> PRESET_VALUES.indexOf(ngxValue) }
                    ?.takeIf { it >= 0 }
                val value = byName ?: byValue
                if (value != null) {
                    PRESET_OPTION.set(value)
                }
                break
            }
        } catch (t: Throwable) {
            Illusion.LOGGER.warn("[dxrmc] could not read $OPTIONS_KEY from $file", t)
            return
        }
        // 文件里没有这一行时（首次运行 / 用户删过）也要推一次，让 native 与界面取值一致。
        push(OPTION.get())
        // P108ac：模型同理 —— 游戏未运行时 `OptionInstance.set` 不触发回调，只能在这里补推，
        // 否则文件里的选择要等玩家再点一次按钮才生效。
        pushPreset(PRESET_OPTION.get())
        // P108y：**先**按文件里的开关切模式，`D3D12RenderScale` 才会用"DLSS 模式"那套集合与键；
        // [push] 末尾其实已经调过一次，这里再调是幂等的（模式没变直接返回 false），
        // 目的是覆盖"文件里没有 dxrMcDlss 行、`OPTION.set` 没触发回调"的情形。
        D3D12RenderScale.setDlssMode(OPTION.get())
        // 从 options.txt 恢复不算"玩家手改"：`OptionInstance.set` 在游戏未运行时不会触发回调，
        // 所以 [push] 里那次同步可能没跑到，这里补一次，锁的结论与文件内容才对得上。
        reconcileAntiAliasing()
    }

    /**
     * 把开关写回 vanilla 的 `options.txt`（vanilla 每次保存都整份重写，所以要在 `Options.save` 尾部
     * 补行；先丢掉自己的旧行，重复保存不会堆叠）。
     *
     * <p>P108y：挡位（`dxrMcDlssQuality`）也在这里落盘。渲染缩放的两个键由
     * `D3D12RenderScale.save` 负责（它在同一个 TAIL 注入里，晚于本方法）。
     *
     * <p>P108ac：模型（`dxrMcDlssPreset`）同样在这里落盘 —— 漏掉它时按钮每次进游戏都回到
     * `default`，而日志里看不出任何异常（native 只是收到 0）。
     */
    @JvmStatic
    fun save(file: File) {
        if (!file.isFile) {
            return
        }
        try {
            val eol = System.lineSeparator()
            val owned = setOf(OPTIONS_KEY, QUALITY_OPTIONS_KEY, PRESET_OPTIONS_KEY)
            val kept = file.readLines(StandardCharsets.UTF_8)
                .filterNot { line -> owned.any { key -> line.startsWith("$key:") } }
            val written = listOf(
                "${OPTIONS_KEY}:${OPTION.get()}",
                "${QUALITY_OPTIONS_KEY}:${QUALITY_NAMES[QUALITY_OPTION.get()]}",
                "${PRESET_OPTIONS_KEY}:${PRESET_NAMES[PRESET_OPTION.get()]}",
            )
            val text = (kept + written).joinToString(eol) + eol
            file.writeText(text, StandardCharsets.UTF_8)
        } catch (t: Throwable) {
            Illusion.LOGGER.warn("[dxrmc] could not write $OPTIONS_KEY to $file", t)
        }
    }

    // ------------------------------------------------------------------
    // 入口（Java mixin 调用）
    // ------------------------------------------------------------------

    /**
     * 每帧的**第一次**入口（世界层画完、主目标深度被 vanilla 清空**之前**，见类注释的挂点说明）：
     * 把这一帧的运动矢量录下来，并（P108e）把这一帧的**深度**交给 native 做一次私有快照 ——
     * 那一刻深度还在（`GameRenderer.render` 的清深度在 `GuiRenderer.render` 之前），而速度 pass
     * 与深度快照都必须读那份深度。
     *
     * P108d（用户 m11364）：**DLSS 的 Evaluate 不再挂在这里**。实测（native `DXRMC_P108X_COPY_COLOR=1`
     * 把 pInColor 原样拷进输出目标）证明：在这个时刻主目标的**颜色**里还没有本帧的世界层
     * （整张 640x360 是清屏色 190,179,167，min==max），而世界的颜色要等到 SMAA 那一刻才齐 ——
     * 于是 DLSS 拿到的是常量输入，输出自然也常量（用户看到的"整片米白"）。所以数据面拆成两半：
     * 这里录运动矢量，[applyIfEnabled] 在 SMAA 挂点（世界层颜色完整）再评价。
     */
    @JvmStatic
    fun captureMotionVectors() {
        if (!isEnabled()) {
            return
        }
        if (broken) {
            return
        }
        val minecraft = Minecraft.getInstance()
        if (minecraft.level == null) {
            return
        }
        modeActive = true
        ensureDataPlane()
        // P108c：速度通道若已自我关闭，趁这一帧把闩锁清掉（限速：最多每 1 秒一次），下一帧才有运动矢量。
        retryVelocityIfLatched()
        // P108d：相机的 model-view 从 vanilla 的 **CPU 渲染状态**取（`renderLevel` 用的就是它），
        // 不再从 vanilla 的 UBO 环做 GPU 写映射读回 —— 那条读回会把整帧打成常量清屏色。
        captureCameraView(minecraft)
        val encoder = D3D12Device.sharedCommandEncoder() ?: return
        // P76c（用户 m07942）：挂点已从 `GameRenderer.render` 的清深度之前挪到**帧图 main pass 的 RETURN**
        // （`mixin.render.LevelRendererMainPassDepthMixin`）。旧挂点按该 mixin 类注释里的执行序排在三次清深度
        // 中的 (1)(2) 之后，实测深度是 `mean=0.0/0.0/0.0/0.0` —— 速度 pass 拿全 0 深度会把整张运动矢量写成 0，
        // DLSS 于是把移动画面当静止画面累积（"只有开 DLSS 才有、只随运动出现的拖影"）。
        // 现在这一刻深度里正是本帧世界几何，且排在所有清深度之前。
        encoder.recordVelocityPass()
        // P108x：深度快照与这里**同一个挂点、同一份深度**，由 `LevelRendererMainPassDepthMixin` 紧接着调用
        // [captureDepthSnapshot]（先速度 pass、后快照，只为了日志顺序）。旧注释里的
        // `addAlwaysOnTopPass` 不是唯一选择：那个 pass 只在 `hasAnyAlwaysOnTop()` 为真时才进帧图，
        // 实测那一趟为假，快照一次都没发生 —— 见 [captureDepthSnapshot] 的说明。
    }

    /**
     * P108x（用户 m11686）：深度快照的挂点 —— **帧图 main pass 的 RETURN**
     * （`lambda$addMainPass$0`，源码 `_port\mcsrc\...\LevelRenderer.java:482`，即 `pass.executes(...)` 那个
     * lambda 的结尾），由 `mixin.render.LevelRendererMainPassDepthMixin` 调用。
     *
     * 为什么必须在那里（反编译 + 实测证据见该 mixin 的类注释）：
     *  - `GameRenderer.render` 里那次清深度（`javap` 偏移 **356**，源码 :443）之前，深度**已经**是 0。
     *    26.2 的关卡渲染是**帧图**：`LevelRenderer.render` 只负责 addPass/`frame.execute`，真正清深度的
     *    是 `addAlwaysOnTopPass` 的 pass 体（`lambda$addAlwaysOnTopPass$0` 偏移 **52**，源码 :571）与
     *    `GameRenderer.renderLevel` 的手部清深度（源码 :578）。
     *  - `addAlwaysOnTopPass` 本身不能依赖：它只在 `FeatureRenderDispatcher.PreparedFrame.hasAnyAlwaysOnTop()`
     *    为真时才进帧图（源码 :557），实机那一趟为假，快照一次都没发生。
     *  - main pass 的 RETURN 实测读到 `640x360 fmt=40 mean=14.6/16.4/13.5/6.9 max=255/254/255/57`，
     *    正是本帧世界几何的深度；`addMainPass`（源码 :394）无条件执行。
     *
     * 顺序（帧图执行序）：clear → sky → **main（世界几何）** → clouds → weather → 透明链 →
     * always_on_top（若加了：清深度 + 实体置顶）→ 手部（清深度 + 手）→ `GameRenderer.render` 清深度
     * → GUI。
     *
     * P76c（用户 m07942）：运动矢量也在这个挂点录（[captureMotionVectors]，同一 mixin 里先调它）。
     * 旧挂点深度全 0 ⇒ 运动矢量整张 0 ⇒ DLSS 把移动画面当静止画面累积。
     * [applyIfEnabled] 的评价时机不动（仍在 SMAA 那处，世界层颜色完整）。旧 DLL 没有
     * `mcdr_dx12SnapshotDlssDepth` ⇒ `Int.MIN_VALUE`，静默退回"用 JVM 传的深度"。
     */
    @JvmStatic
    fun captureDepthSnapshot() {
        if (!isEnabled()) {
            // 关掉 DLSS 时把结论串复位，重新开启时能再报一次（否则沿用上次的"旧 DLL"结论）。
            lastDepthSnapshotState = null
            return
        }
        if (broken) {
            return
        }
        val minecraft = Minecraft.getInstance()
        if (minecraft.level == null) {
            return
        }
        modeActive = true
        ensureDataPlane()
        val encoder = D3D12Device.sharedCommandEncoder() ?: return
        snapshotDlssDepth(encoder, minecraft)
    }

    /**
     * P108e（用户 m11460 的深度快照导出）：把**本帧世界层的深度**交给 native 做一次快照
     * （`mcdr_dx12SnapshotDlssDepth`）。
     *
     * 时序是这个调用存在的唯一理由：NGX 的评价挂在 SMAA 那一刻（世界层颜色齐了），而 vanilla 在
     * `GameRenderer.render:443 clearDepthTexture` 就把主目标深度清成 0.0（reverse-Z 的"远"）。
     * 拿清空后的深度评价 DLSS 等于把深度项变成常数、退化成纯颜色升采样。这一步在清深度**之前**把
     * 深度拷进 native 的私有 D32 纹理，之后 native 的评价优先用那张快照。
     *
     * 深度来源与 [applyIfEnabled] 填 `pInDepth` 的那张是**同一表达式**
     * （`minecraft.gameRenderer.mainRenderTarget().depthTexture`）：两侧必须是同一张纹理，否则
     * "快照"与"喂给 NGX 的深度"会指向不同资源。ctx/命令列表与 [D3D12Velocity] 那次录制共用
     * （`D3D12Device.sharedCommandEncoder()` 的 `D3D12CommandEncoderBackend`）。
     *
     * 任何不满足都只跳过这一帧：没有深度纹理 / 纹理已关闭 / native 拒绝（0、-1）/ 旧 DLL 没有该
     * 导出（[Int.MIN_VALUE]）—— 全部退回"用 JVM 传的深度"，绝不打断渲染；日志按状态变化去重。
     */
    private fun snapshotDlssDepth(encoder: D3D12CommandEncoderBackend, minecraft: Minecraft) {
        val depth = minecraft.gameRenderer.mainRenderTarget().depthTexture as? D3D12GpuTexture
        if (depth == null || depth.isClosed()) {
            noteDepthSnapshot("the main render target has no D3D12 depth texture")
            return
        }
        val result = try {
            encoder.recordDlssDepthSnapshot(depth)
        } catch (t: Throwable) {
            noteDepthSnapshot("dx12SnapshotDlssDepth threw: $t", true)
            return
        }
        when (result) {
            1 -> noteDepthSnapshot("captured this frame's world depth into the native D32 snapshot; "
                + "the DLSS evaluation later this frame uses it instead of the depth vanilla clears")
            0 -> noteDepthSnapshot("native skipped the snapshot (unusable arguments); DLSS falls back "
                + "to the JVM-supplied depth")
            -1 -> noteDepthSnapshot("native could not create/copy the snapshot; DLSS falls back to the "
                + "JVM-supplied depth", true)
            Int.MIN_VALUE -> noteDepthSnapshot("the loaded MCDR.dll has no mcdr_dx12SnapshotDlssDepth "
                + "(it predates this export), so the depth vanilla clears is used instead")
            else -> noteDepthSnapshot("dx12SnapshotDlssDepth returned an unexpected value $result", true)
        }
    }

    /** 深度快照的结论行（P108e）：只在结论变化时打一行（默认 INFO，[warn] 时 WARN）。 */
    private fun noteDepthSnapshot(message: String, warn: Boolean = false) {
        if (lastDepthSnapshotState == message) {
            return
        }
        lastDepthSnapshotState = message
        if (warn) {
            Illusion.LOGGER.warn("[dxrmc] $PREFIX DLSS depth snapshot: {}", message)
        } else {
            Illusion.LOGGER.info("[dxrmc] $PREFIX DLSS depth snapshot: {}", message)
        }
    }


    /** 是否启用了"把 JVM 建的纹理交给 native 当 DLSS 输出"的验收模式（默认关闭）。 */
    @JvmStatic
    fun isDlssOutputHandoverEnabled(): Boolean = dlssOutputHandoverEnabled()

    /**
     * P108z：DLSS 输出纹理交接的开关 —— **默认开启**，见配置 `[dlss] outputHandover`。
     *
     * <p>默认 `handover`：JVM 建 target 尺寸的 UAV 纹理、交给 native 当 NGX 的输出目标，评价完
     * 拷回 GUI 目标。这是正式路径 —— native 自建的 `CreateCommittedResource` 纹理存不住 NGX 的
     * 写入，模块 `createTexture` 建的可以（用户 m11787 实机确认不崩、且有可见效果）。
     *
     * <p>配置 `[dlss] outputHandover = false` 关闭：退回让 native 用它私有的 UAV 兜底（那条路在这台机器上
     * 写不进内容），`pInOutput` 仍是 `D3D12GUISplit.targetForFrame()`。仅用于排查。
     *
     * <p>失效保护与它无关、始终生效：只有"本帧 handover 成功 + native 报 evaluate 成功"时，
     * [onEvaluated] 才允许跳过世界层 blit（见 [countEvaluateFailure]）。
     */
    private fun dlssOutputHandoverEnabled(): Boolean {
        val enabled = D3D12Settings.dlssOutputHandover
        if (enabled != loggedHandoverMode) {
            loggedHandoverMode = enabled
            if (enabled) {
                Illusion.LOGGER.info(
                    "[dxrmc] $PREFIX DLSS output handover is ON ({}={}): the JVM builds a "
                        + "target-sized UAV texture and native writes NGX's output into it. If "
                        + "this ever makes the world black, check that the handover succeeded and "
                        + "that native reports 'Evaluate succeeded' (the blit is only skipped then).",
                    "outputHandover", enabled)
            } else {
                Illusion.LOGGER.warn(
                    "[dxrmc] $PREFIX DLSS output handover is OFF ({}={}): native falls back to its "
                        + "own private UAV, which cannot be written on this machine, so DLSS will "
                        + "have no visible effect. This is a troubleshooting setting only.",
                    "outputHandover", enabled)
            }
        }
        return enabled
    }

    /**
     * P108aa（用户 m11796）：抗锯齿选项是否停在 DLAA。DLAA 由本类的 DLSS 特性实现（render ==
     * target + 挡位 3），所以"DLSS 是否在工作" = 独立开关 **或** DLAA。由
     * [syncDlaaFromAntiAliasing] 每帧从 `D3D12AntiAliasing.current()` 同步。
     */
    @Volatile
    private var dlaaActive: Boolean = false

    /** 取证用：DLAA 当前是否生效。 */
    @JvmStatic
    fun isDlaaActive(): Boolean = dlaaActive

    /** 上一次看到的开关值（只在渲染线程读写）。 */
    private var loggedHandoverMode: Boolean = false

    /**
     * P108z 失效保护：本帧 handover 是否真的成功（纹理建出来 + native 收下）。只在渲染线程读写，
     * 由 [applyIfEnabled] 写、[onEvaluated] 读。
     */
    private var handoverOkThisFrame: Boolean = false

    /** P108z 失效保护：native evaluate 连续失败的帧数；成功一帧清零。 */
    private var consecutiveEvaluateFailures: Int = 0

    /** [noteHandoverNotSkippingBlit] 的限流标记（同一条连续区间只报一次）。 */
    private var handoverSkipNoteShown: Boolean = false

    /**
     * P108d：把本帧的相机 model-view 喂给 [D3D12Temporal]（见 `captureCameraView` 的说明）。 */
    private fun captureCameraView(minecraft: Minecraft) {
        val state = try {
            minecraft.gameRenderer.gameRenderState().levelRenderState.cameraRenderState
        } catch (t: Throwable) {
            return
        }
        val matrix = state.viewRotationMatrix
        val out = FloatArray(16)
        matrix.get(out)
        D3D12Temporal.captureCameraView(out)
        // P76g：投影矩阵走同一条权威路。UBO 写拦截读出的 64 字节实测是垃圾（非规格化数 +
        // 第三行 NaN），导致透视的 `m32 = -1` 不在位、`prevClip.w` 恒 0、运动矢量整张 0。
        val projection = state.projectionMatrix
        val proj = FloatArray(16)
        projection.get(proj)
        D3D12Temporal.captureProjection(proj)
    }

    /**
     * 每帧的**第二次**入口：与 SMAA 同一个挂点（世界层画完、GUI 还没画），由
     * `GameRendererSmaaWorldMixin` 在 SMAA 之后、`D3D12GUISplit.prepareForGui()` 之前调用 ——
     * 于是「先 SMAA、后 DLSS 升采样」的顺序成立，而且喂给 NGX 的是**真正的世界层颜色**。
     * 任何一步不满足都只是安静地跳过。
     */
    /** P117：PCL stats —— 帧开始（世界渲染之前）：建本帧 token + eSimulationStart。 */
    @JvmStatic
    fun pclFrameBegin() {
        MCDR_ABI.D3D12ReflexFrameBegin()
    }

    /** P117：世界层画完、即将提交本帧 —— eSimulationEnd + eRenderSubmitStart。 */
    @JvmStatic
    fun pclRenderSubmitStart() {
        MCDR_ABI.D3D12ReflexMarker(1)  // sl::PCLMarker::eSimulationEnd
        MCDR_ABI.D3D12ReflexMarker(2)  // sl::PCLMarker::eRenderSubmitStart
    }

    @JvmStatic
    fun applyIfEnabled() {
        // 闸门：只看 DLSS 自己的开关（`dxrMcDlss`）。关掉时把数据面与状态复位。
        // 与抗锯齿无关：抗锯齿的模式在本类这一侧只是被锁成 OFF（用户 m05600），两件事各判各的开关。
        if (!isEnabled()) {
            leaveDlssMode()
            return
        }
        if (legacyBridge) {
            // 临时桥（见 legacyPush）：旧 DLL 的闸门是 aaMode，先确认它没被抗锯齿那边顶掉。
            reassertLegacyAaMode()
        }
        if (broken) {
            return
        }
        val minecraft = Minecraft.getInstance()
        // 标题界面 / 加载界面：没有世界层，谈不上"世界层升采样"。
        val level = minecraft.level ?: return
        modeActive = true
        ensureDataPlane()
        // P108c：速度通道若已自我关闭，趁这一帧把闩锁清掉（每会话一次），下一帧才有运动矢量。
        retryVelocityIfLatched()
        if (!ensureAvailable()) {
            return
        }

        val renderTarget = minecraft.gameRenderer.mainRenderTarget()
        val color = (renderTarget.colorTextureView as? D3D12GpuTextureView)?.texture() as? D3D12GpuTexture
            ?: return noteState("the world layer is not a D3D12 texture")
        val depth = renderTarget.depthTexture as? D3D12GpuTexture
            ?: return noteState("the main render target has no D3D12 depth texture")
        // pInOutput：P106 的窗口尺寸目标（界面与最终呈现都用它）。`targetForFrame()` 会按需
        // 创建/校正它，renderScale == 100% 时返回 null —— 那种情况下世界层就是窗口尺寸的唯一
        // 纹理，没有第二张能承接升采样结果（NGX 要求输入与输出是两张资源）。
        //
        // P108aa（用户 m11796）：DLAA 恰好就是"renderScale == 100%"的情形，此时**没有** GUI
        // 拆分目标。DLAA 不需要放大，所以把 `pInOutput` 取成**主目标的颜色纹理**：native 会把它
        // 重定向到我们交接的那张 target 尺寸纹理（`s.externalOutput`，尺寸/格式都等于主目标），
        // 评价完再拷回主目标 —— 随后直接呈现，`prepareForGui` 在这条路径上本来就不做事。
        val guiOutput = (D3D12GUISplit.targetForFrame()?.colorTextureView as? D3D12GpuTextureView)
            ?.texture() as? D3D12GpuTexture
        val output = guiOutput ?: if (dlaaActive) {
            (minecraft.gameRenderer.mainRenderTarget().colorTextureView as? D3D12GpuTextureView)
                ?.texture() as? D3D12GpuTexture
        } else {
            null
        } ?: return noteState(
            "no window-sized output target (dxrMcRenderScale must not be 100% so that the world "
                + "layer and the presented target are two different textures)")
        val encoder = D3D12Device.sharedCommandEncoder() ?: return noteState("no shared command encoder")

        val renderWidth = color.getWidth(0)
        val renderHeight = color.getHeight(0)
        val targetWidth = output.getWidth(0)
        val targetHeight = output.getHeight(0)
        if (renderWidth <= 0 || renderHeight <= 0 || targetWidth <= 0 || targetHeight <= 0) {
            return noteState("degenerate sizes: render ${renderWidth}x${renderHeight}, "
                + "target ${targetWidth}x${targetHeight}")
        }

        // P108z：把 JVM 建的 target 尺寸 UAV 纹理交给 native 当 DLSS 输出 —— **默认路径**。
        //
        // 开关：配置 `[dlss] outputHandover = false` 才关闭（默认 handover）。
        //
        // 为什么是默认：native 自建的 `CreateCommittedResource` 纹理存不住 NGX 的写入，而模块
        // `createTexture` 建的（这张）可以 —— 用户 m11787 实机确认"不崩了、DLSS 有明显可见效果"。
        // 交给 native 之后它会**重定向** NGX 的输出到这张纹理（`s.externalOutput` 优先于
        // `pInOutput`），评价完再拷回 GUI 目标；因此本帧的 `prepareForGui` 必须跳过世界层 blit
        //（`markWorldLayerDone()`，见 [onEvaluated]）。
        //
        // `off` 只用于排查：退回让 native 用它私有的 UAV 兜底（那条路在这台机器上写不进内容，
        // 但不会碰我们的资源），`pInOutput` 仍是 `D3D12GUISplit.targetForFrame()`。
        //
        // **失效保护**：只有当"本帧 handover 成功 + native 报 evaluate 成功"时才跳过 blit；
        // 任何一步没成就照旧 blit 世界层（宁可少一帧 DLSS，也不让用户看到黑屏）。
        var handoverOkThisFrame = false
        if (dlssOutputHandoverEnabled()) {
            val dlssOutput = DLSSOutput.textureForFrame(targetWidth, targetHeight)
            if (dlssOutput != null) {
                handoverOkThisFrame = DLSSOutput.handOver(dlssOutput)
            }
        }
        this.handoverOkThisFrame = handoverOkThisFrame
        val packetOutput = output

        // P108d：运动矢量本该已经在**上一处挂点**（清深度之前）录好了 —— 那里深度还有内容。
        // 这一次调用是兜底（那一处没跑到时仍能出数据），同一帧已录过时 D3D12Velocity 的每帧闸门
        // 会直接返回，不会白跑一次全屏 pass（见类注释第 2 条）。
        encoder.recordVelocityPass()
        val motionVectors = D3D12Velocity.currentHandle()
        if (motionVectors == 0L) {
            // native 在 pInMotionVectors == nullptr 时直接返回 0（d3d12_dlss.cpp:886），
            // 所以这里不必为了一次必然的跳过再下探一次。
            return noteState("no motion-vector texture (the velocity pass has no history yet on the "
                + "frame DLSS is switched on, or it disabled itself - see the velocity log)")
        }

        val jitter = D3D12Temporal.jitter()
        val scalePercent = D3D12RenderScale.percent()
        val window = minecraft.window
        val resetReason = resetReason(level, renderWidth, renderHeight, targetWidth, targetHeight,
            scalePercent, window.width, window.height)
        val packet = try {
            packet()
        } catch (t: Throwable) {
            return disable("allocating the DLSS input packet failed: $t")
        }
        try {
            writePacket(packet, renderWidth, renderHeight, targetWidth, targetHeight,
                jitter[0].toFloat(), jitter[1].toFloat(), resetReason != null, depth.format.ordinal,
                color.handle(), depth.handle(), motionVectors, packetOutput.handle())
        } catch (t: Throwable) {
            return disable("writing the DLSS input packet failed: $t")
        }

        // P112：把本帧相机常量交给 native（Streamline 的 sl::Constants 数据源）。
        pushDlssConstants(renderWidth, renderHeight, targetWidth, targetHeight,
            jitter[0].toFloat(), jitter[1].toFloat(), resetReason != null)

        val result = try {
            encoder.recordDlssEvaluate(packet)
        } catch (t: Throwable) {
            return disable("dx12EvaluateDlss threw: $t")
        }
        when (result) {
            // 1 = 这一帧真的评价了：结果已经写进 pInOutput，本帧不能再做世界层 blit。
            1 -> {
                onEvaluated(level, renderWidth, renderHeight, targetWidth, targetHeight, scalePercent,
                    window.width, window.height, jitter[0].toFloat(), jitter[1].toFloat(), resetReason)
                // P121：升采样之后的后置锐化（SDK 自带 sharpness 已废弃）。
                sharpenUpscaledFrame(encoder, output, targetWidth, targetHeight)
            }
            // 0 = native 自己跳过（模式/尺寸/特征未就绪），它已经打过自己的 WARN；这里只在状态
            // 变化时报一行，绝不每帧刷屏。
            0 -> {
                countEvaluateFailure()
                noteState("native skipped this frame (feature not ready, or the sizes changed "
                    + "without a successful create)")
            }
            -1 -> {
                countEvaluateFailure()
                noteProblem("native rejected the input packet (-1: invalid arguments). "
                    + "Resource states are left untouched this frame")
            }
            Int.MIN_VALUE -> {
                countEvaluateFailure()
                noteProblem("the loaded MCDR.dll has no mcdr_dx12EvaluateDlss "
                    + "(it predates P108); DLSS stays off until a matching DLL is in place")
            }
            else -> {
                countEvaluateFailure()
                noteProblem("dx12EvaluateDlss returned an unexpected value $result")
            }
        }
    }

    // ------------------------------------------------------------------
    // P109：F3 的 3D 十字准星推迟到评价之后再画
    // ------------------------------------------------------------------

    /**
     * 本帧被 [deferCrosshair] 拦下的那一笔十字准星（每帧最多一笔，新的覆盖旧的）。
     *
     * 三个字段一起读写、只在渲染线程上，所以不加锁；[replayCrosshair] 无论画没画成都清空它们。
     */
    private var pendingCrosshairRenderer: DebugCrosshairRenderer? = null
    private var pendingCrosshairCamera: CameraRenderState? = null
    private var pendingCrosshairGuiScale: Int = 0

    /**
     * P109：把 F3 的 3D 十字准星**推迟**到本帧 DLSS/DLAA 评价之后（由
     * `mixin.render.DebugCrosshairRendererMixin` 在 `render` 的 HEAD 调进来，替掉原地的画）。
     *
     * <p>为什么：那个 gizmo 世界对齐、又没有运动矢量，画进 `mainRenderTarget` 就会被 NGX 当成
     * 静止的世界内容做时间累积（转视角时拖影）；而这版 NGX 运行库不支持 reactive mask，唯一的
     * 办法是不让它进输入。
     *
     * <p>只留一笔：vanilla 每帧只画一次（`GameRenderer.java:592-596`），多余的调用覆盖旧的，
     * 保证不会把上一帧的相机状态画到这一帧。
     */
    @JvmStatic
    fun deferCrosshair(renderer: DebugCrosshairRenderer, cameraState: CameraRenderState, guiScale: Int) {
        pendingCrosshairRenderer = renderer
        pendingCrosshairCamera = cameraState
        pendingCrosshairGuiScale = guiScale
    }

    /**
     * P109：补画 [deferCrosshair] 存下的十字准星，并清空待画槽（这一帧画不成也清，不留给下一帧）。
     *
     * <p>调用点是**固定的那一行** —— `mixin.render.GameRendererSmaaWorldMixin` 里
     * [applyIfEnabled] 的下一行。绝不能挪进 [applyIfEnabled] 内部：它有多条提前 `return`
     * （本帧不评价时直接退出），补画放在里面就会在那些帧把十字准星整个丢掉。
     */
    @JvmStatic
    fun replayCrosshair() {
        val renderer = pendingCrosshairRenderer ?: return
        val cameraState = pendingCrosshairCamera
        pendingCrosshairRenderer = null
        pendingCrosshairCamera = null
        if (cameraState != null) {
            renderer.render(cameraState, pendingCrosshairGuiScale)
        }
    }

    /**
     * P108z 失效保护：本帧 handover 没成功、于是**没有**跳过世界层 blit 时的取证行（限流：
     * 只在"开始出现"与"恢复"时各一行）。正常情况（handover 成功）根本不会调用它。
     */
    private fun noteHandoverNotSkippingBlit() {
        if (handoverSkipNoteShown) {
            return
        }
        handoverSkipNoteShown = true
        Illusion.LOGGER.warn(
            "[dxrmc] $PREFIX handover did not take effect this frame, so the world layer is still "
                + "blitted by prepareForGui (no black screen). DLSS keeps evaluating; this line "
                + "appears once per streak.")
    }

    /**
     * P108z 失效保护：native 的 evaluate 连续失败计数。失败帧本来就不会 `markWorldLayerDone()`，
     * 所以画面不会黑；这里只负责在"连败超过阈值"时打一行限流 WARN，让人知道 DLSS 实际上没在工作
     * （而不是以为它在跑）。成功一帧即清零（见 [onEvaluated]）。
     */
    private fun countEvaluateFailure() {
        if (consecutiveEvaluateFailures < Int.MAX_VALUE) {
            consecutiveEvaluateFailures++
        }
        handoverSkipNoteShown = false
        if (consecutiveEvaluateFailures == EVALUATE_FAILURE_WARN_THRESHOLD) {
            Illusion.LOGGER.warn(
                "[dxrmc] $PREFIX DLSS has failed to evaluate {} frames in a row; the world layer "
                    + "is being blitted every frame instead (this is the safe path, not a crash). "
                    + "Check the P108 lines above for the reason.",
                consecutiveEvaluateFailures)
        }
    }

    // ------------------------------------------------------------------
    // 成功路径：埋点 + 状态提交
    // ------------------------------------------------------------------

    private fun onEvaluated(level: Any, renderWidth: Int, renderHeight: Int, targetWidth: Int,
        targetHeight: Int, scalePercent: Int, windowWidth: Int, windowHeight: Int,
        jitterX: Float, jitterY: Float, resetReason: String?) {
        // P108z 失效保护：只有"本帧 handover 成功 + native 报 evaluate 成功"时，才允许让
        // [dev.nts.expdxr.d3d12.render.advanced.smaa.D3D12GuiSplit.markWorldLayerDone] 跳过世界层
        // blit。handover 开着但这一帧没交接成功时，我们并不知道世界层最终落在哪张纹理里，宁可
        // 照旧 blit 一次（画面绝对不会黑），也不要赌。
        val handoverEnabled = dlssOutputHandoverEnabled()
        val handoverOk = this.handoverOkThisFrame
        if (!handoverEnabled || handoverOk) {
            // 关键：DLSS 已经把升采样后的世界层写进 GUI 目标，本帧的 prepareForGui 必须跳过世界层 blit。
            D3D12GUISplit.markWorldLayerDone()
        } else {
            noteHandoverNotSkippingBlit()
        }
        consecutiveEvaluateFailures = 0
        lastState = STATUS_EVALUATED
        if (resetReason != null) {
            Illusion.LOGGER.info(
                "[dxrmc] $PREFIX reset=1 this frame - {} (render {}x{} -> target {}x{})",
                resetReason, renderWidth, renderHeight, targetWidth, targetHeight)
        }
        if (!loggedFed) {
            loggedFed = true
            // near/far 的降级说明只跟"第一次真的喂进去"一起出现一次。
            Illusion.LOGGER.info(
                "[dxrmc] $PREFIX near/far are not reachable from the JVM side, so 0.0 is reported "
                    + "for both; native only writes them into its own P108 diagnostic line")
            Illusion.LOGGER.info(
                "[dxrmc] $PREFIX DLSS inputs fed - render {}x{} -> target {}x{} jitter=({}, {}) "
                    + "mvScale=1.0 near={} far={} depthInverted=1 ngx={}",
                renderWidth, renderHeight, targetWidth, targetHeight, jitterX, jitterY,
                NEAR_Z, FAR_Z, ngxSummary)
        }
        commitState(level, renderWidth, renderHeight, targetWidth, targetHeight, scalePercent,
            windowWidth, windowHeight)
    }

    /**
     * `reset` 的判定：首帧（本 DLSS 会话第一次真正评价）、世界切换、渲染尺寸变化、
     * 目标尺寸变化、`dxrMcRenderScale` 变化、窗口尺寸变化。返回 null 表示这一帧不用丢历史。
     *
     * 只在**真正评价成功**之后才把当前值记下来（[commitState]），所以返回 0/-1 的帧不会把
     * "变化"吃掉。
     */
    /**
     * P112：把本帧相机常量写进 `MCDRLayout.DLSS_CONSTANTS` 并交给 native
     * （`mcdr_dx12SetDlssConstants`）。Streamline 的 `sl::Constants` 需要 viewProj/prevViewProj、
     * jitter、mvecScale（像素空间 MV ⇒ 1/renderSize）、相机基与位置；其余三个矩阵由 native 派生。
     * 旧 DLL 没有该导出时返回 `Int.MIN_VALUE`，这里静默跳过。
     */
    /**
     * P119：把相机平移烘进「纯旋转」的视图矩阵，得到真正的 world→view。
     *
     * `V_world = V_rot * T(-camPos)`：列主序下平移列在 `[12]/[13]/[14]`，其第 r 行等于
     * `-(row_r(V_rot) · camPos)`。速度场那套（`D3D12Velocity`）用相机相对空间就够了，但
     * `clipToPrevClip` 必须包含相机平移，否则「静止正常、一走动就拖影」。
     */
    private fun worldSpaceView(rotation: FloatArray, px: Float, py: Float, pz: Float): FloatArray {
        if (rotation.size < 16) {
            return rotation
        }
        val out = rotation.copyOf()
        for (row in 0 until 3) {
            val rx = rotation[row]
            val ry = rotation[4 + row]
            val rz = rotation[8 + row]
            out[12 + row] = -(rx * px + ry * py + rz * pz)
        }
        return out
    }

    /**
     * P131：DLAA / DLSS 的**升采样之后**锐化，目标由调用方给出（`output`）。
     *
     * `output` 有两种来源（见 `applyIfEnabled` 里 `val output = ...`）：
     * - `renderScale != 100%`：`D3D12GUISplit.targetForFrame()` 的窗口目标；
     * - **DLAA（renderScale == 100%）**：该函数**返回 null**，输出是主渲染目标的颜色纹理。
     *
     * 之前这里自己去取 `targetForFrame()`，于是 DLAA 永远在 `target == null` 静默返回
     * （p97 里 `P121: record() entered` 一条都没有，就是这个原因）。
     *
     * 两趟：native 的 `sharpenTextureToTexture`/`blitTextureToTexture` 都拒绝 `src == dst`，
     * 所以 `target -> scratch`（锐化）再 `scratch -> target`（blit）。
     */
    private fun sharpenUpscaledFrame(encoder: D3D12CommandEncoderBackend, target: D3D12GpuTexture,
        width: Int, height: Int) {
        if (!D3D12Sharpen.isEnabled()) {
            return
        }
        val scratch = D3D12Sharpen.scratchForFrame(width, height) ?: return
        if (!encoder.recordSharpen(target.handle(), scratch.handle())) {
            return
        }
        if (!MCDR_ABI.dx12BlitTextureToTexture(encoder.nativeHandle(), scratch.handle(),
                target.handle())) {
            noteState("post-sharpen: the blit back into the output target failed; the frame keeps "
                + "the unsharpenened DLSS output")
        }
    }
    private fun pushDlssConstants(renderWidth: Int, renderHeight: Int, targetWidth: Int,
        targetHeight: Int, jitterX: Float, jitterY: Float, reset: Boolean) {
        val proj = D3D12Temporal.currentProjection()
        val prevProj = D3D12Temporal.previousProjection()
        val view = D3D12Temporal.currentCameraView()
        val prevView = D3D12Temporal.previousCameraView()
        if (proj == null || prevProj == null || view == null || prevView == null) {
            return
        }
        // P119：`view` 只有旋转、没有平移（世界层是相机相对空间），而 `clipToPrevClip` 必须包含
        // **相机平移**，否则相机一走位，重投影就只算了旋转 —— 表现正是「静止正常、一走动就拖影」。
        // 这里把相机平移烘进视图矩阵（V_world = V_rot * T(-camPos)），VP 就成了真正的 world→clip；
        // 参考实现（HOMO `FGConstantsBuilder`）也是用相机相对位移构造 viewToViewPrev 的。
        val camPos = D3D12Temporal.currentCameraPosition()
        val prevCamPos = D3D12Temporal.previousCameraPosition()
        val posX = camPos?.get(0) ?: 0.0f
        val posY = camPos?.get(1) ?: 0.0f
        val posZ = camPos?.get(2) ?: 0.0f
        val worldView = worldSpaceView(view, posX, posY, posZ)
        val prevWorldView = worldSpaceView(prevView,
            prevCamPos?.get(0) ?: posX, prevCamPos?.get(1) ?: posY, prevCamPos?.get(2) ?: posZ)
        val viewProj = D3D12Temporal.multiply(proj, worldView)
        val prevViewProj = D3D12Temporal.multiply(prevProj, prevWorldView)
        // P118：`view` 是**列主序**的世界->相机旋转（相机在原点，见 `D3D12Velocity`）。
        // 相机在**世界空间**的 right/up/fwd 是这张矩阵的**行**：列主序下 (row r, col c) 在 r + c*4。
        // 旧代码按行主序取成了列（right=(view[0],view[1],view[2])），给 SL 的是一组被转置的基。
        val rightX = view[0]; val rightY = view[4]; val rightZ = view[8]
        val upX = view[1]; val upY = view[5]; val upZ = view[9]
        val fwdX = view[2]; val fwdY = view[6]; val fwdZ = view[10]
        val fovRadians = Math.toRadians(Minecraft.getInstance().options.fov().get().toDouble()).toFloat()
        val aspect = if (targetHeight > 0) targetWidth.toFloat() / targetHeight.toFloat() else 1.0f
        val layout = MCDRLayout.DLSS_CONSTANTS
        Arena.ofConfined().use { arena ->
            val seg = arena.allocate(layout)
            MemorySegment.copy(viewProj, 0, seg, ValueLayout.JAVA_FLOAT,
                layout.byteOffset(PathElement.groupElement("viewProjection")), 16)
            MemorySegment.copy(prevViewProj, 0, seg, ValueLayout.JAVA_FLOAT,
                layout.byteOffset(PathElement.groupElement("prevViewProjection")), 16)
            // P120：`cameraViewToClip` 的语义是 view->clip（就是这张投影），不是 world->clip 的 VP。
            MemorySegment.copy(proj, 0, seg, ValueLayout.JAVA_FLOAT,
                layout.byteOffset(PathElement.groupElement("projection")), 16)
            // P154（用户 m16527 让核对 jitter 的 x/y 方向）：**只有 y 反号**。
            //
            // 注入实测（P136）：`m02 -= 2J/W`、`m12 -= 2J/H`。换算到 D3D 的像素空间（y 向下）
            // 就是位移 `(-Jx, +Jy)` —— x 与 NDC 同向，y 因为屏幕 y 向下而反号。这一点在 TAA
            // 侧已被实测钉死：p115 起 `curUv = c - (Jx, -Jy)` 才能把画面换回未抖动网格。
            //
            // `sl::Constants::jitterOffset` 的注释是 "pixel space jitter offset"，即
            // D3D 像素空间（y 向下），所以这里应传 `(-Jx, +Jy)`。旧写法两轴都取负，等于少翻转
            // 了 y：DLSS 每帧会按 `2Jy` 多做一次竖直去抖，正是 DLAA 静态残余抖动（m15149）的
            // 量级。P132 的 A/B 只翻了**两轴一起**（180° 旋转），错误量的模长不变，所以当时
            // 测出"一样抖"（m14989）—— 那次实验设计不足以分辨单轴反号。
            val slJitterX = -jitterX
            val slJitterY = jitterY
            seg.set(F32, layout.byteOffset(PathElement.groupElement("jitterOffsetX")), slJitterX)
            seg.set(F32, layout.byteOffset(PathElement.groupElement("jitterOffsetY")), slJitterY)
            seg.set(F32, layout.byteOffset(PathElement.groupElement("mvecScaleX")),
                1.0f / renderWidth.toFloat())
            seg.set(F32, layout.byteOffset(PathElement.groupElement("mvecScaleY")),
                1.0f / renderHeight.toFloat())
            seg.set(F32, layout.byteOffset(PathElement.groupElement("cameraPosX")), posX)
            seg.set(F32, layout.byteOffset(PathElement.groupElement("cameraPosY")), posY)
            seg.set(F32, layout.byteOffset(PathElement.groupElement("cameraPosZ")), posZ)
            seg.set(F32, layout.byteOffset(PathElement.groupElement("cameraUpX")), upX)
            seg.set(F32, layout.byteOffset(PathElement.groupElement("cameraUpY")), upY)
            seg.set(F32, layout.byteOffset(PathElement.groupElement("cameraUpZ")), upZ)
            seg.set(F32, layout.byteOffset(PathElement.groupElement("cameraRightX")), rightX)
            seg.set(F32, layout.byteOffset(PathElement.groupElement("cameraRightY")), rightY)
            seg.set(F32, layout.byteOffset(PathElement.groupElement("cameraRightZ")), rightZ)
            seg.set(F32, layout.byteOffset(PathElement.groupElement("cameraFwdX")), fwdX)
            seg.set(F32, layout.byteOffset(PathElement.groupElement("cameraFwdY")), fwdY)
            seg.set(F32, layout.byteOffset(PathElement.groupElement("cameraFwdZ")), fwdZ)
            // P118：near/far 从投影矩阵解出来。列主序 m[10]=A=(f+n)/(n-f)、m[14]=B=2fn/(n-f)，
            // 于是 n = B/(A-1)、f = B/(A+1)。旧代码固定送 0.0，SL 拿到的是非法的近远平面。
            val projA = proj[10]
            val projB = proj[14]
            val nearRaw = if (projA != 1.0f && projA.isFinite() && projB.isFinite()) {
                projB / (projA - 1.0f)
            } else {
                0.0f
            }
            val farRaw = if (projA != -1.0f && projA.isFinite() && projB.isFinite()) {
                projB / (projA + 1.0f)
            } else {
                0.0f
            }
            // P120：MC 是 reverse-Z（构造投影时 near/far 是互换的），上面解出来的两个平面
            // 会跟着互换 —— 交给 SL 之前按大小改正，避免"近平面比远平面远"。
            val nearZ = if (nearRaw > farRaw) farRaw else nearRaw
            val farZ = if (nearRaw > farRaw) nearRaw else farRaw
            seg.set(F32, layout.byteOffset(PathElement.groupElement("cameraNear")), nearZ)
            seg.set(F32, layout.byteOffset(PathElement.groupElement("cameraFar")), farZ)
            seg.set(F32, layout.byteOffset(PathElement.groupElement("cameraFovRadians")), fovRadians)
            seg.set(F32, layout.byteOffset(PathElement.groupElement("cameraAspectRatio")), aspect)
            seg.set(I32, layout.byteOffset(PathElement.groupElement("depthInverted")), 1)
            seg.set(I32, layout.byteOffset(PathElement.groupElement("cameraMotionIncluded")), 1)
            // P119：我们的运动矢量是**屏幕空间**像素位移（`D3D12Velocity` 写 `(prevUv - uv) * ScreenSize`），
            // 不是 3D 世界空间速度。旧值 1 会让 SL 把 MV 当成 3D 速度再用相机矩阵投影一次 —— 静止时
            // 世界速度≈0 所以看不出来，一走动整幅画面的位移就被算错，表现为「运动时才有拖影」。
            seg.set(I32, layout.byteOffset(PathElement.groupElement("motionVectors3D")), 0)
            seg.set(I32, layout.byteOffset(PathElement.groupElement("reset")), if (reset) 1 else 0)
            seg.set(I32, layout.byteOffset(PathElement.groupElement("renderingGameFrames")), 1)
            // P134：MV 现在是真实运动（D3D12Velocity 不再加 PrevJitterPixels），所以必须报 0。
            // 之前报 1 等于告诉 DLSS"MV 里已经含 jitter 差"，它会去减一个不存在的量 —— 这正是
            // "关 jitter 不抖、开 jitter 抖、翻 jitterOffset 符号一样抖"的来源。
            seg.set(I32, layout.byteOffset(PathElement.groupElement("motionVectorsJittered")), 0)
            // 旧 DLL 没有该导出时返回 Int.MIN_VALUE；这里静默跳过（native 会打自己的 P112 行）。

            MCDR_ABI.D3D12SetDlssConstants(seg)
        }
    }
    private fun resetReason(level: Any, renderWidth: Int, renderHeight: Int, targetWidth: Int,
        targetHeight: Int, scalePercent: Int, windowWidth: Int, windowHeight: Int): String? = when {
        !evaluatedOnce -> "first evaluate of this DLSS session"
        lastLevel?.get() !== level -> "world switch"
        renderWidth != lastRenderWidth || renderHeight != lastRenderHeight ->
            "render size ${lastRenderWidth}x${lastRenderHeight} -> ${renderWidth}x${renderHeight}"
        targetWidth != lastTargetWidth || targetHeight != lastTargetHeight ->
            "target size ${lastTargetWidth}x${lastTargetHeight} -> ${targetWidth}x${targetHeight}"
        scalePercent != lastScalePercent -> "dxrMcRenderScale $lastScalePercent% -> $scalePercent%"
        windowWidth != lastWindowWidth || windowHeight != lastWindowHeight ->
            "window ${lastWindowWidth}x${lastWindowHeight} -> ${windowWidth}x${windowHeight}"
        else -> null
    }

    private fun commitState(level: Any, renderWidth: Int, renderHeight: Int, targetWidth: Int,
        targetHeight: Int, scalePercent: Int, windowWidth: Int, windowHeight: Int) {
        evaluatedOnce = true
        lastRenderWidth = renderWidth
        lastRenderHeight = renderHeight
        lastTargetWidth = targetWidth
        lastTargetHeight = targetHeight
        lastScalePercent = scalePercent
        lastWindowWidth = windowWidth
        lastWindowHeight = windowHeight
        lastLevel = WeakReference(level)
    }

    // ------------------------------------------------------------------
    // 数据面依赖 + 可用性
    // ------------------------------------------------------------------

    /**
     * DLSS 需要 jitter（投影）与运动矢量两条数据，两者都靠 [D3D12Temporal] / [D3D12Velocity] 里
     * 由 `dxrmc.temporal*` **构建期**属性初始化的**运行期**字段（`Illusion\\build.gradle:60-107`，
     * 默认都是 false）。选了 DLSS 就必须打开，否则 native 拿到的是空句柄。
     *
     * 只做两件**幂等**的事：把两个字段置 true（记下"是我们打开的"）。真正的资源复位/闩锁清理由
     * [retryVelocityIfLatched] 在渲染开始时做 —— `Options.load` 时设备还不存在，此刻去碰速度纹理
     * 只会白失败一次。
     */
    private fun ensureDataPlaneReady() {
        if (!D3D12Temporal.enabled) {
            D3D12Temporal.enabled = true
            forcedTemporal = true
            Illusion.LOGGER.info(
                "[dxrmc] $PREFIX DLSS needs the temporal data plane: sub-pixel projection jitter "
                    + "(dxrmc.temporal) is switched on while DLSS is selected")
        }
        if (!D3D12Velocity.enabled) {
            D3D12Velocity.enabled = true
            forcedVelocity = true
            Illusion.LOGGER.info(
                "[dxrmc] $PREFIX DLSS needs motion vectors: the velocity pass "
                    + "(dxrmc.temporal.velocity) is switched on while DLSS is selected")
        }
    }

    /** [applyIfEnabled] 每帧的幂等版（DLSS 关着时不碰任何东西）。 */
    private fun ensureDataPlane() {
        if (isEnabled()) {
            ensureDataPlaneReady()
        }
    }

    /**
     * 速度通道的"自我关闭"是**一票否决**的：`D3D12Velocity.record` 里任何失败都只 warn 一次
     * 然后 `enabled = false`，并且把失败的构建步骤（纹理/参数块、深度 SRV、管线）记成"不再重试"。
     * 于是只把 `enabled` 置回 true 是撤不回来的 —— 下一帧 `record` 仍然短路，`currentHandle()`
     * 恒为 0，native 在 `d3d12_dlss.cpp:886` 直接返回 0，DLSS 一帧都不会评价。
     *
     * 这里清一次闩锁（[D3D12Velocity.forceEnable] 会 [D3D12Velocity.reset] 并让构建步骤
     * 重新尝试）。
     *
     * P108e：**额度制改成限速制**。旧实现是"每会话一次"（额度只在 [onEvaluated] 里第一次评价
     * 成功之后批一次），那是个闭环死锁：首帧速度通道就闩锁 ⇒ 没有运动矢量 ⇒ DLSS 一帧都不评价
     * ⇒ 永远不批额度 ⇒ 这里永远返回 ⇒ 闩锁永远不清。现在只要 DLSS 开着且
     * [D3D12Velocity.isLatched]，就最多每 [VELOCITY_RETRY_INTERVAL_NANOS] 重试一次，于是
     * "偶发失败"与"首帧就失败"都能自救，"修不好的失败"也不会每帧重建资源；
     * [leaveDlssMode] 复位计时。
     */
    private fun retryVelocityIfLatched() {
        if (!D3D12Velocity.isLatched()) {
            return
        }
        val now = System.nanoTime()
        if (lastVelocityRetryNanos != Long.MIN_VALUE
            && now - lastVelocityRetryNanos < VELOCITY_RETRY_INTERVAL_NANOS) {
            return
        }
        lastVelocityRetryNanos = now
        D3D12Velocity.forceEnable()
    }

    /** 离开 DLSS：复位本会话的输入状态（下次回来必须 reset=1），并把我们打开的数据面还回去。 */
    private fun leaveDlssMode() {
        if (!modeActive && !forcedTemporal && !forcedVelocity) {
            return
        }
        modeActive = false
        availabilityProbed = false
        available = false
        // P108e：速度通道的限速重试计时复位，下次进入 DLSS 会话第一次闩锁就立刻可重试。
        lastVelocityRetryNanos = Long.MIN_VALUE
        resetInputState()
        if (forcedTemporal) {
            forcedTemporal = false
            // reset() 释放速度纹理与时间性状态（内部也会调 D3D12Velocity.reset()）。
            D3D12Temporal.reset()
            D3D12Temporal.enabled = false
            if (forcedVelocity) {
                forcedVelocity = false
                D3D12Velocity.enabled = false
            }
            Illusion.LOGGER.info("[dxrmc] $PREFIX DLSS is no longer selected: the temporal data plane "
                + "it had switched on is back to off")
        } else if (forcedVelocity) {
            forcedVelocity = false
            D3D12Velocity.reset()
            D3D12Velocity.enabled = false
            Illusion.LOGGER.info("[dxrmc] $PREFIX DLSS is no longer selected: the velocity pass it had "
                + "switched on is back to off")
        }
    }

    private fun resetInputState() {
        evaluatedOnce = false
        lastRenderWidth = -1
        lastRenderHeight = -1
        lastTargetWidth = -1
        lastTargetHeight = -1
        lastScalePercent = -1
        lastWindowWidth = -1
        lastWindowHeight = -1
        lastLevel = null
        lastState = null
        // P108e：深度快照的结论串也跟着复位，重进 DLSS 时能再报一次"旧 DLL 没有那个导出"。
        lastDepthSnapshotState = null
    }

    /**
     * `dx12QueryDlss` 探一次（每次进入 DLSS 模式一次）：答案是"不可用"时不必再每帧下探，
     * 也就能把"没有真实评价"的原因写进 JVM 日志（native 的说明文本一起带上）。
     */
    private fun ensureAvailable(): Boolean {
        if (availabilityProbed) {
            return available
        }
        availabilityProbed = true
        val note = try {
            noteSegment()
        } catch (t: Throwable) {
            disable("allocating the DLSS query buffer failed: $t")
            return false
        }
        val result = try {
            MCDR_ABI.D3D12QueryDlss(note, NOTE_CAPACITY)
        } catch (t: Throwable) {
            disable("dx12QueryDlss threw: $t")
            return false
        }
        val text = if (result == Int.MIN_VALUE) {
            "(the loaded MCDR.dll has no mcdr_dx12QueryDlss)"
        } else {
            // native 用 snprintf 写 NUL 结尾的 UTF-8；读失败就当"没有说明"。
            runCatching { note.getString(0) }.getOrDefault("").ifEmpty { "(no detail)" }
        }
        ngxSummary = "result=$result text=$text"
        available = result == 1
        if (available) {
            Illusion.LOGGER.info("[dxrmc] $PREFIX DLSS is available: {}", text)
        } else {
            // 一次性（每次进入 DLSS 模式一次）：不可用的原因 native 也打过一行，这里带上它的文本。
            Illusion.LOGGER.warn("[dxrmc] $PREFIX DLSS cannot run on this machine; the world layer "
                + "keeps going through the linear blit: {}", text)
        }
        return available
    }

    // ------------------------------------------------------------------
    // native 内存：一个 Arena、一个参数包（每帧复用）
    // ------------------------------------------------------------------

    /**
     * 本会话唯一的一块 native 内存。`Arena.ofConfined()` 的所有者是**第一次调用它的线程**
     * （这里是渲染线程，本类只在渲染线程上被调用），所以所有访问点都在同一线程上。
     */
    private fun memory(): Arena {
        this.arena?.let { return it }
        val created = Arena.ofConfined()
        this.arena = created
        return created
    }

    /** DLSS 参数包（复用同一个 `MemorySegment`，**不每帧新建 Arena**）。 */
    private fun packet(): MemorySegment {
        this.packetSegment?.let { return it }
        val created = memory().allocate(MCDRLayout.DLSS_INPUT)
        this.packetSegment = created
        return created
    }

    private fun noteSegment(): MemorySegment {
        this.noteSegment?.let { return it }
        val created = memory().allocate(NOTE_CAPACITY.toLong())
        this.noteSegment = created
        return created
    }

    /** `MCDRLayout.DLSS_INPUT` 里具名字段的字节偏移；名字与 `mcdr_abi.h:363-400` 逐字一致。 */
    private fun offset(name: String): Long =
        MCDRLayout.DLSS_INPUT.byteOffset(PathElement.groupElement(name))

    /**
     * 逐字段写参数包。每个字段的数据来源：
     *  - `renderWidth/Height` = 世界层颜色附件的**真实纹理尺寸**（= `dxrMcRenderScale` 缩放后）；
     *  - `targetWidth/Height` = pInOutput（P106 的 GUI/呈现目标）的真实纹理尺寸 = 窗口尺寸；
     *  - `jitterOffsetX/Y` = **x 取负、y 保持**（D3D 像素空间，y 向下；P154 起 y 单轴反号，
     *    因为该字段注释写明是 "pixel space jitter offset"，而注入在像素空间里是 `(-Jx, +Jy)`）；
     *  - `reset` = [resetReason] 是否非空；
     *  - `depthFormatOrdinal` = 主目标深度附件的 `GpuFormat` ordinal（诊断用）。
     */
    private fun writePacket(packet: MemorySegment, renderWidth: Int, renderHeight: Int,
        targetWidth: Int, targetHeight: Int, jitterX: Float, jitterY: Float, reset: Boolean,
        depthFormatOrdinal: Int, color: Long, depth: Long, motionVectors: Long, output: Long) {
        packet.set(I32, offset("renderWidth"), renderWidth)
        packet.set(I32, offset("renderHeight"), renderHeight)
        packet.set(I32, offset("targetWidth"), targetWidth)
        packet.set(I32, offset("targetHeight"), targetHeight)
        packet.set(F32, offset("jitterOffsetX"), -jitterX)   // P154：y 单轴反号，见 pushDlssConstants
        packet.set(F32, offset("jitterOffsetY"), jitterY)
        packet.set(F32, offset("mvScaleX"), MV_SCALE)
        packet.set(F32, offset("mvScaleY"), MV_SCALE)
        packet.set(F32, offset("preExposure"), PRE_EXPOSURE)
        packet.set(F32, offset("exposureScale"), EXPOSURE_SCALE)
        packet.set(I32, offset("exposureMode"), EXPOSURE_MODE_NONE)
        packet.set(I32, offset("alphaUpscaling"), ALPHA_UPSCALING)
        packet.set(F32, offset("nearZ"), NEAR_Z)
        packet.set(F32, offset("farZ"), FAR_Z)
        packet.set(I32, offset("depthInverted"), DEPTH_INVERTED)
        packet.set(I32, offset("reset"), if (reset) 1 else 0)
        packet.set(I32, offset("depthFormatOrdinal"), depthFormatOrdinal)
        // 四个 mcdr_object* 纹理句柄：native 侧就是 ID3D12Resource*（`d3d12_dlss.cpp:882-885`）。
        packet.set(PTR, offset("pInColor"), MemorySegment.ofAddress(color))
        packet.set(PTR, offset("pInDepth"), MemorySegment.ofAddress(depth))
        packet.set(PTR, offset("pInMotionVectors"), MemorySegment.ofAddress(motionVectors))
        packet.set(PTR, offset("pInOutput"), MemorySegment.ofAddress(output))
    }

    // ------------------------------------------------------------------
    // 低频日志（只在状态变化时打）
    // ------------------------------------------------------------------

    /** 跳过（原因由 native 决定）：只在原因变化时报一行，避免每帧刷屏。 */
    private fun noteState(message: String) {
        if (lastState == message) {
            return
        }
        lastState = message
        Illusion.LOGGER.info("[dxrmc] $PREFIX DLSS did not run this frame: {}", message)
    }

    /** 真问题（-1 / 没有导出 / 意外返回值）：同样只在状态变化时报一行，级别 WARN。 */
    private fun noteProblem(message: String) {
        if (lastState == message) {
            return
        }
        lastState = message
        Illusion.LOGGER.warn("[dxrmc] $PREFIX DLSS problem: {}", message)
    }

    /** 自我关闭：只报一次，之后本会话不再尝试喂数据。 */
    private fun disable(reason: String) {
        if (broken) {
            return
        }
        broken = true
        Illusion.LOGGER.warn("[dxrmc] $PREFIX DLSS input feeding is disabled for this session: {}", reason)
    }
}
