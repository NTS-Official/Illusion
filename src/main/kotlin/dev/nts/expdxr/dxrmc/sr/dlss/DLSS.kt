package dev.nts.expdxr.dxrmc.sr.dlss

import dev.nts.expdxr.Illusion
import dev.nts.expdxr.d3d12.backend.D3D12CommandEncoderBackend
import dev.nts.expdxr.misc.settings.D3D12Settings
import dev.nts.expdxr.d3d12.backend.D3D12RenderPassBackend
import dev.nts.expdxr.d3d12.extern.MCDR_ABI
import dev.nts.expdxr.d3d12.extern.MCDRLayout
import dev.nts.expdxr.d3d12.extern.MCDRLayout.F32
import dev.nts.expdxr.d3d12.extern.MCDRLayout.I32
import dev.nts.expdxr.d3d12.extern.MCDRLayout.PTR
import dev.nts.expdxr.d3d12.render.adapter.D3D12AdapterInfo
import dev.nts.expdxr.d3d12.render.adapter.D3D12Device
import dev.nts.expdxr.d3d12.render.advanced.D3D12AntiAliasing
import dev.nts.expdxr.d3d12.render.advanced.D3D12PostSharpen
import dev.nts.expdxr.d3d12.render.advanced.smaa.D3D12GUISplit
import dev.nts.expdxr.d3d12.render.advanced.taa.D3D12Temporal
import dev.nts.expdxr.misc.IllusionLogger
import dev.nts.expdxr.d3d12.render.advanced.taa.D3D12Velocity
import dev.nts.expdxr.d3d12.render.advanced.vrs.D3D12RenderScale
import dev.nts.expdxr.d3d12.render.core.D3D12GpuTexture
import dev.nts.expdxr.d3d12.render.core.D3D12GpuTextureView
import com.mojang.renderpearl.api.textures.GpuTextureView
import com.mojang.serialization.Codec
import dev.nts.expdxr.dxrmc.sr.FrameGenerationConstants
import dev.nts.expdxr.dxrmc.sr.SuperResolution
import java.io.File
import java.lang.foreign.Arena
import java.lang.foreign.MemoryLayout.PathElement
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.lang.ref.WeakReference
import java.nio.charset.StandardCharsets
import java.util.Locale
import kotlin.math.atan
import java.util.Optional
import net.minecraft.client.Minecraft
import net.minecraft.client.OptionInstance
import net.minecraft.client.gui.components.CycleButton
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.client.renderer.DebugCrosshairRenderer
import net.minecraft.client.renderer.state.level.CameraRenderState
import net.minecraft.network.chat.Component

/**
 * DLSS 每帧数据面：填 `MCDRLayout.DLSS_INPUT`（sizeof 112），在世界层画完、界面还没画时交给 `mcdr_dx12EvaluateDlss`。
 * 开关是 [OPTION]，与抗锯齿互斥（自带时域 AA）。挂点与本帧顺序见 docs/superresolution/dlss.md §5。
 */
object DeepLearningSuperSampling {


    /** NGX 的 `InMVScaleX/Y` = 1.0（MV 已是像素位移）；别与 Streamline 的 `mvecScale` 混淆 —— 那里填 `1.0 / renderWidth`。 */
    private const val MV_SCALE: Float = 1.0f

    /** 常数曝光：1.0 = 颜色缓冲已是显示单位。 */
    private const val PRE_EXPOSURE: Float = 1.0f

    /** evaluate 连续失败多少帧后打一行 WARN；只影响日志，不跳过世界层 blit。 */
    private const val EVALUATE_FAILURE_WARN_THRESHOLD: Int = 30

    /** 黑帧熔断的暖机期：首次 evaluate 返回 1 时 NGX 常还没写进输出，太早采样必然读到 0。 */
    private const val SAMPLE_WARMUP_FRAMES: Int = 60

    /** 判定黑/恢复都要**连续两次**采样（相隔这些帧），避免单帧的偶发（贴图切换、加载屏）误判。 */
    private const val SAMPLE_CONFIRM_FRAMES: Int = 60

    /** 熔断/健康状态下的复查间隔：熔断之后仍然定期看，能自愈（换世界、加载完、NGX 迟到）。 */
    private const val SAMPLE_RETRY_FRAMES: Int = 240

    /** 只有 [EXPOSURE_MODE_NONE] 时 native 才读它（`mcdr_abi.h:382`）。 */
    private const val EXPOSURE_SCALE: Float = 1.0f

    /** `MCDR_DLSS_EXPOSURE_NONE`（`mcdr_abi.h:346`）：没有 GPU 曝光纹理，走常数曝光。 */
    private const val EXPOSURE_MODE_NONE: Int = 0

    /** 本渲染器是 reverse-Z，native 要求恒为 1（`mcdr_abi.h:389`）。 */
    private const val DEPTH_INVERTED: Int = 1

    /** 世界层不是带 alpha 的合成目标，不升采样 alpha（`mcdr_abi.h:384`）。 */
    private const val ALPHA_UPSCALING: Int = 0

    /** 关卡投影在 JVM 侧取不到，填 0.0 = 未知（native 只用它们写诊断行）。 */
    private const val NEAR_Z: Float = 0.0f
    private const val FAR_Z: Float = 0.0f

    /** `dx12QueryDlss` 的说明文本缓冲（native 用 snprintf 写，最多 255 字符 + NUL）。 */
    private const val NOTE_CAPACITY: Int = 256

    /** 成功闸门的哨兵（[lastState] 用），与"没跑"的各种原因串区分开。 */
    private const val STATUS_EVALUATED: String = "evaluated"

    /** 速度通道闩锁的限速重试间隔（1 秒）：既能自救，也不会每帧重建资源 + 刷屏。 */
    private const val VELOCITY_RETRY_INTERVAL_NANOS: Long = 1_000_000_000L


    /** 本会话唯一的一块 native 内存 + 里面的两个 segment（**不每帧新建 Arena**）。 */
    private var arena: Arena? = null
    private var packetSegment: MemorySegment? = null
    private var noteSegment: MemorySegment? = null

    /** 写包/调用抛出异常时置位：只报一次，之后本会话不再喂数据。 */
    private var broken = false

    /** 是否已在 DLSS 模式下跑过。`@Volatile`：开关回调在主线程、读写在这里。 */
    @Volatile
    private var modeActive = false

    /** 数据面开关是否**我们**打开的。`@Volatile`：主线程写、渲染线程读写。 */
    @Volatile
    private var forcedTemporal = false

    @Volatile
    private var forcedVelocity = false

    /** 上次限速重试速度通道的 nanoTime；[Long.MIN_VALUE] = 还没重试过。只在渲染线程读写。 */
    private var lastVelocityRetryNanos: Long = Long.MIN_VALUE

    /** 本会话是否已探过 `dx12QueryDlss`。`@Volatile`：每会话只探一次的闸门。 */
    @Volatile
    private var availabilityProbed = false

    @Volatile
    private var available = false

    @Volatile
    private var ngxSummary: String = "(not queried)"

    /** 每会话一次的"数据真的喂进去了"结论行。 */
    private var loggedFed = false
    // 升采样结果是否全黑必须能自证：下一帧开头读回 pInOutput 的采样点；真黑时用 error 报一次。
    private var pendingOutputSample = false
    private var sampleTarget: D3D12GpuTexture? = null
    /** 采样那一刻 native 报的输出纹理状态（0 = 未知，退回不带状态的旧导出）。 */
    private var sampleFromState = 0

    /** 已经成功评价过的帧数：采样节奏以它为准，不看墙钟。 */
    private var evaluatedFrames = 0

    /** 下一次允许采样的 `evaluatedFrames` 值。`Int.MAX_VALUE` = 不再采样（已经健康）。 */
    private var nextSampleAt = SAMPLE_WARMUP_FRAMES

    /** 连续"全黑"的采样次数；连续两次才认定真的黑（见 [SAMPLE_CONFIRM_FRAMES]）。 */
    private var blackSamples = 0

    /** 熔断之后连续"有内容"的采样次数；连续两次就恢复 DLSS。 */
    private var goodSamples = 0

    /** 本次采样周期是否已经报过那行 INFO 证据（见 [reportOutputSample]）。 */
    private var firstSampleReported = false

    private var lastState: String? = null

    /** 深度快照的结论串。与 [lastState] 分开记：两个挂点在同一帧，共用会互相覆盖。 */
    private var lastDepthSnapshotState: String? = null


    /** 上一次**真正评价成功**时的输入状态，用来判 `reset`。 */
    private var evaluatedOnce = false

    /** 已经警告过「比例低于该档名义输入」，只警告一次。 */
    private var reportedBelowNominal = false
    /** 采样确认升采样结果是全黑之后，本会话改走线性 blit（宁可糊不要黑）。 */
    private var defuseBlackOutput = false


    private var lastRenderWidth = -1
    private var lastRenderHeight = -1
    private var lastTargetWidth = -1
    private var lastTargetHeight = -1
    private var lastScalePercent = -1
    private var lastWindowWidth = -1
    private var lastWindowHeight = -1
    private var lastLevel: WeakReference<Any>? = null


    const val OPTIONS_KEY: String = "dxrMcDlss"

    /**
     * 视频设置里的 DLSS 开关。必须用原版 [OptionInstance.createBoolean]：界面按**同一个实例** `findOption` 才找得到它（置灰靠这条）。
     */
    private val OPTION: OptionInstance<Boolean> = OptionInstance.createBoolean(
        "options.dlss",
        { Tooltip.create(Component.translatable("options.dlss.tooltip")) },
        false,
        { value ->
            // 先让门面认领「当前后端」，再推 native：`[sr] backend` 停在 NONE 时门面会认为没后端在跑。
            SuperResolution.onDlssToggle(value)
            push(value)
        },
    )

    @JvmStatic
    fun option(): OptionInstance<Boolean> = OPTION


    /** `options.txt` 里的挡位键。 */
    const val QUALITY_OPTIONS_KEY: String = "dxrMcDlssQuality"

    /** 下标即下发给 native 的编号（`ordinal`）—— 不要重排；展示名走 `options.dlss.quality.<name>`。 */
    private val QUALITY_NAMES: List<String> =
        listOf("ultra_performance", "performance", "balanced", "quality")

    /** 默认挡位 = 平衡（58% 输入）。 */
    private const val QUALITY_DEFAULT: Int = 2

    /** 挡位循环按钮（原版 `CycleableValueSet` 路径）；回调 [pushQuality] 把编号推给 native。 */
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
     * 各挡位的 canonical 输入比例（性能 50 / 平衡 58 / 质量 67 / DLAA 100）。喂给 NGX 的缓冲必须正好是它算出的 optimal 尺寸。
     */
    @JvmStatic
    fun inputPercent(): Int = percentForTier(quality())

    /** 挡位 -> 输入百分比的唯一映射点（[inputPercent] 与 [bindRenderScaleToDlss] 共用）。 */
    private fun percentForTier(tier: Int): Int = when (tier) {
        // 极致性能档必须 34 不能 33：NGX 要求 render >= target/3，33% 会报 Dynamic scaling error 并交回空纹理（世界全黑）。
        0 -> 34   // 1/3.00 = 33.33 -> 34
        1 -> 50   // 1/2.00 = 50.00 -> 50
        2 -> 59   // 1/1.72 = 58.14 -> 59（58 会留下 0.24% 的缺口，只靠 NGX 的容差活着）
        3 -> 67   // 1/1.50 = 66.67 -> 67
        else -> 100
    }

    /** 把渲染缩放**绑到 DLSS 的输入比例**上（DLAA 由 dlaaLock 锁 100%，这里不动）；挡位变或开关开时调一次。 */
    private fun bindRenderScaleToDlss(tier: Int = quality()) {
        if (!OPTION.get() || dlaaActive) {
            return
        }
        val percent = percentForTier(tier)
        // 必须走 `option().set(...)`：只调 `setPercent` 不动 OptionInstance，界面里的数字不会跟着变。
        if (percent == D3D12RenderScale.current()) {
            return
        }
        D3D12RenderScale.option().set(percent)
        Illusion.LOGGER.info(
            "render scale bound to the DLSS input: {}% (tier {})",
            percent, QUALITY_NAMES[tier],
        )
    }

    /**
     * 把挡位推给 native（编号 = [QUALITY_NAMES] 下标）。**返回 0 是「Performance 档已生效」不是失败**：越界 -1、旧 DLL 无导出 [Int.MIN_VALUE]。
     */
    private fun pushQuality(value: Int) {
        // 换挡位 = 让 NGX 重建 feature：给黑帧熔断一次重新判定的机会。
        resetOutputSampleSchedule()
        val status = MCDR_ABI.D3D12SetDlssQuality(value)
        when {
            status == value -> Illusion.LOGGER.info(
                "DLSS quality = {}", QUALITY_NAMES[value])
            status == -1 -> Illusion.LOGGER.warn(
                "mcdr_dx12SetDlssQuality({}) was rejected (-1: out of range)", value)
            status == Int.MIN_VALUE -> if (!loggedQualityUnsupported) {
                loggedQualityUnsupported = true
                Illusion.LOGGER.info(
                    "the loaded MCDR.dll has no mcdr_dx12SetDlssQuality, so the "
                        + "Performance/Balanced/Quality button is remembered but native keeps picking "
                        + "the quality level from the render ratio")
            }
            else -> Illusion.LOGGER.warn(
                "mcdr_dx12SetDlssQuality({}) returned {} (expected the tier back, "
                    + "or -1 when out of range)", value, status)
        }
        // 挡位决定输入比例，渲染缩放跟着走（只在上采样开着时）。
        bindRenderScaleToDlss(value)
    }

    /** 「本地 DLL 没有挡位导出」只报一次。 */
    @Volatile
    private var loggedQualityUnsupported = false


    /** `options.txt` 里的模型键。 */
    const val PRESET_OPTIONS_KEY: String = "dxrMcDlssPreset"

    /**
     * 模型按钮的取值名：**下标是界面循环顺序，不是下发给 native 的编号**（映射见 [PRESET_VALUES]）；default 交给 NGX 自选。
     */
    private val PRESET_NAMES: List<String> = listOf("default", "j", "k", "l", "m")

    /** 各下标对应的 `NVSDK_NGX_DLSS_Hint_Render_Preset` 值：0/10/11/12/13（Default/J/K/L/M）。 */
    private val PRESET_VALUES: List<Int> = listOf(0, 10, 11, 12, 13)

    /** 默认 = `default`（不改变 native 的历史行为，只有玩家主动选才换模型）。 */
    private const val PRESET_DEFAULT: Int = 0

    /** 预设是 `CreateFeature` 的输入：换模型会**重建特征**（一帧世界重建，与换挡位完全一样）。 */
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

    /** 把模型推给 native（参数是 [PRESET_VALUES] 的 NGX 枚举值）。`default` 档返回 0 是成功、越界 -1。 */
    private fun pushPreset(index: Int) {
        val value = PRESET_VALUES[index]
        val status = MCDR_ABI.D3D12SetDlssRenderPreset(value)
        when {
            status == value -> Illusion.LOGGER.info(
                "DLSS model preset = {} (NGX render preset {})",
                PRESET_NAMES[index], value)
            status == -1 -> Illusion.LOGGER.warn(
                "mcdr_dx12SetDlssRenderPreset({}) was rejected (-1: out of range)",
                value)
            status == Int.MIN_VALUE -> if (!loggedPresetUnsupported) {
                loggedPresetUnsupported = true
                Illusion.LOGGER.info(
                    "the loaded MCDR.dll has no mcdr_dx12SetDlssRenderPreset, so "
                        + "the DLSS model button is remembered but native keeps NGX's own per-tier "
                        + "preset")
            }
            else -> Illusion.LOGGER.warn(
                "mcdr_dx12SetDlssRenderPreset({}) returned {} (expected the preset "
                    + "back, or -1 when out of range)", value, status)
        }
    }

    /** 「本地 DLL 没有模型导出」只报一次。 */
    @Volatile
    private var loggedPresetUnsupported = false

    /** DLSS 是否在工作：独立开关 [OPTION] **或** 抗锯齿停在 DLAA（DLAA = 原生分辨率的 DLSS）。 */
    @JvmStatic
    fun isEnabled(): Boolean = OPTION.get() || dlaaActive

    /** 连续多帧评价失败时**没有任何东西消费 jitter**：世界层照旧 blit、jitter 仍在注入，整幅画面被 ±0.5px 来回平移。 */
    @JvmStatic
    fun isStalled(): Boolean = consecutiveEvaluateFailures >= 3

    /**
     * 让 DLSS 让位给 FSR/XeSS：只关「开关」这一层，不动挡位偏好。**必须在帧边界调用**：帧中间改比例会让世界层目标与主目标附件尺寸不一致。
     */
    @JvmStatic
    fun standDown(reason: String): Boolean {
        if (!OPTION.get()) {
            return false
        }
        Illusion.LOGGER.info("standing down: {}", reason)
        OPTION.set(false)
        return true
    }

    /** True while the game really renders through DXRMC's D3D12 backend（非 D3D12 时按钮置灰）。 */
    @JvmStatic
    fun isAvailable(): Boolean =
        D3D12AdapterInfo.current != null && MCDR_ABI.D3D12DlssAvailable() == 1

    /** DLSS 开启时抗锯齿锁为 OFF 且不可调（自带时域 AA，再跑一遍只是先把输入糊掉）。状态推给 `D3D12AntiAliasing.setDlssLock`。 */
    private fun reconcileAntiAliasing() {
        // DLAA 由**抗锯齿选项**接管：先同步过来，再决定 DLSS->AA 的锁定要不要施加。
        syncDlaaFromAntiAliasing()
        // 闸门必须**每帧对齐**到「DLAA 或 DLSS 开关」，不能只在翻转时推一次：`push(false)` 随后会把它关掉，
        // 之后没有路径再打开（native 在 `!dlssEnabled` 时直接返回）；缓存上一次的值也不行，会永久关死。
        forceDlssGate(dlaaActive || OPTION.get())
        // DLAA 生效时**不能**把抗锯齿锁成 OFF（那样会立刻退出 DLAA，自相矛盾）。
        if (dlaaActive) {
            return
        }
        D3D12AntiAliasing.setDlssLock(isEnabled())
    }

    /** 把「抗锯齿选项是不是 DLAA」同步成 [dlaaActive]（单向依赖：本类读 AA，AA 不知道本类）。 */
    private fun syncDlaaFromAntiAliasing() {
        val want = D3D12AntiAliasing.current() == D3D12AntiAliasing.DLAA
        if (want == dlaaActive) {
            return
        }
        dlaaActive = want
        // DLAA 必须 render == target：缩放缓条钉 100；离开时 setDlaaLock(false) 恢复锁定前的用户值。
        D3D12RenderScale.setDlaaLock(want)
        if (want) {
            // 让 native 打开 DLSS 闸门（DLAA 走同一条 DLSS 特性链路），并请求 DLAA 档位。
            forceDlssGate(true)
            // 只在状态翻转时推**一次** 3：native 把「本轮实际接受的档位」当基线，尺寸到 100% 触发重建后它自己升回 DLAA。
            val pinned = D3D12RenderScale.percent()
            if (pinned != 100) {
                Illusion.LOGGER.warn(
                    "DLAA lock did not pin the render scale to 100% (got {}%); "
                        + "native will accept one downgrade and self-heal when the scale reaches 100%",
                    pinned,
                )
            }
            pushForcedQuality(DLAA_QUALITY)
            Illusion.LOGGER.info(
                "DLAA engaged: anti-aliasing = DLAA, render scale pinned to 100% "
                    + "(render == target is required), DLSS feature requested at the DLAA tier. "
                    + "the projection carries the same Halton jitter sequence as TAA now, "
                    + "so NGX gets real sub-pixel samples (InJitterOffset + MVJittered) instead of "
                    + "the jitter-free accumulation this path used to fall back to.")
        } else {
            // 还原用户自己选的挡位；缩放缓条由 setDlaaLock(false) 恢复到锁定前的用户值。
            pushForcedQuality(QUALITY_OPTION.get())
            Illusion.LOGGER.info(
                "DLAA released: quality back to the user's own tier ({})",
                QUALITY_NAMES[QUALITY_OPTION.get()])
            // 退出 DLAA 后 DLSS 是否该开交回独立开关；DLAA 期间的 100% 会写进记忆键，这里显式按当前挡位重绑一次。
            bindRenderScaleToDlss()
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
                    "asked to open the DLSS gate for DLAA but native reports it is off")
                0 -> if (enabled) Illusion.LOGGER.warn(
                    "asked to open the DLSS gate for DLAA but native reports it is off")
                Int.MIN_VALUE -> Illusion.LOGGER.info(
                    "the loaded MCDR.dll has no mcdr_dx12SetDlssEnabled; "
                        + "DLAA relies on the legacy aaMode bridge")
                else -> Illusion.LOGGER.warn(
                    "mcdr_dx12SetDlssEnabled({}) returned {}", flag, status)
            }
        } catch (t: Throwable) {
            Illusion.LOGGER.warn("pushing the DLSS gate for DLAA threw: {}", t)
        }
    }

    /** 直接下发一个挡位（0/1/2/3=DLAA），绕过 [QUALITY_OPTION]：3 不是用户可选值。 */
    private fun pushForcedQuality(value: Int) {
        try {
            // 契约同 [pushQuality]：成功返回**传进来的档位本身**（0 = Performance 也是成功），-1 = 越界。
            val status = MCDR_ABI.D3D12SetDlssQuality(value)
            when {
                status == value -> Illusion.LOGGER.info(
                    "DLSS quality = {}", QUALITY_NAMES.getOrElse(value) { "DLAA" })
                status == -1 -> Illusion.LOGGER.warn(
                    "mcdr_dx12SetDlssQuality({}) was rejected (-1: out of range)", value)
                status == Int.MIN_VALUE -> Illusion.LOGGER.info(
                    "the loaded MCDR.dll has no mcdr_dx12SetDlssQuality; the "
                        + "quality tier stays whatever native picked")
                else -> Illusion.LOGGER.warn(
                    "mcdr_dx12SetDlssQuality({}) returned {} (expected the tier back, "
                        + "or -1 when out of range)", value, status)
            }
        } catch (t: Throwable) {
            Illusion.LOGGER.warn("pushing DLSS quality {} threw: {}", value, t)
        }
    }

    /** 每帧的兜底重算：挂在运动矢量挂点而不是 [applyIfEnabled]，因为「DLSS 关掉 => 还原抗锯齿」发生在开关为 false 时。 */
    @JvmStatic
    fun reconcileAntiAliasingEveryFrame() {
        reconcileAntiAliasing()
    }

    /**
     * 开关 -> native。**返回的是调用后的状态**（1 开 / 0 关），所以 `push(false)` 拿到 0 不是「跑不了 DLSS」；能不能跑只由 `mcdr_dx12QueryDlss` 回答。
     */
    private fun push(enabled: Boolean) {
        // 开关一变先锁上/解开抗锯齿，再推 native：两个选项在界面上必须立刻自洽。
        reconcileAntiAliasing()
        val flag = if (enabled) 1 else 0
        when (val status = MCDR_ABI.D3D12SetDlssEnabled(flag)) {
            1 -> Illusion.LOGGER.info("DLSS upscaling = on")
            0 -> Illusion.LOGGER.info(
                "DLSS upscaling = off (the world layer goes through the linear blit)")
            Int.MIN_VALUE -> legacyPush(enabled)
            else -> Illusion.LOGGER.warn(
                "mcdr_dx12SetDlssEnabled({}) returned {} (expected the post-call "
                    + "state: 1 = on, 0 = off)", flag, status)
        }
        // 渲染缩放的有效集合随开关切换（关 25..400 步进 25、开 50..90 步进 10），两种模式各自记住并持久化。
        D3D12RenderScale.setDlssMode(enabled)
        // 一开就把比例对齐到当前挡位的 DLSS 输入（关掉时 setDlssMode 已载回普通模式的记忆值）。
        if (enabled) {
            bindRenderScaleToDlss()
        }
    }


    /** 旧 DLL 里「DLSS 打开」的表达方式：[D3D12AntiAliasing] 的 `MODES` 已不含 4，只能直接下探。 */
    private const val LEGACY_DLSS_AA_MODE: Int = 4

    /** 本地 DLL 是不是还没有 `mcdr_dx12SetDlssEnabled`（第一次下探就能确定，之后不变）。 */
    @Volatile
    private var legacyBridge = false

    /** 上次下探读到的**抗锯齿选项**值；它一变就说明有人往 native 推了非 4 的模式，把 DLSS 的闸门顶掉了。 */
    @Volatile
    private var legacyAaObserved = Int.MIN_VALUE

    /**
     * 临时桥：旧 `MCDR.dll` 只认「`aaMode == 4` 就是 DLSS」。**旧 DLL 上 SMAA 与 DLSS 无法共存**（`aaMode == 4` 时 native 的 `applySmaaToTexture` 一趟都不跑）。
     */
    private fun legacyPush(enabled: Boolean) {
        legacyBridge = true
        legacyAaObserved = D3D12AntiAliasing.current()
        if (enabled) {
            MCDR_ABI.D3D12SetAntiAliasing(LEGACY_DLSS_AA_MODE)
            Illusion.LOGGER.warn("the loaded MCDR.dll has no mcdr_dx12SetDlssEnabled "
                + "(the DLL predates this), so DLSS goes through the legacy aaMode == 4 path; SMAA cannot "
                + "run at the same time on that DLL")
        } else {
            MCDR_ABI.D3D12SetAntiAliasing(
                if (legacyAaObserved == D3D12AntiAliasing.SMAA) D3D12AntiAliasing.SMAA
                else D3D12AntiAliasing.OFF,
            )
        }
    }

    /** 临时桥：每帧确认闸门没被顶掉（`Options.load` 有两个顺序不定的 TAIL 注入，后跑的推抗锯齿模式会抹掉 `aaMode == 4`）。 */
    private fun reassertLegacyAaMode() {
        val aa = D3D12AntiAliasing.current()
        if (aa == legacyAaObserved) {
            return
        }
        legacyAaObserved = aa
        MCDR_ABI.D3D12SetAntiAliasing(LEGACY_DLSS_AA_MODE)
    }

    // options.txt（键 [OPTIONS_KEY]，行格式 `key:jsonValue`）

    /** 从 `options.txt` 恢复开关。`Options.load` 时游戏还没 `isRunning()`，`OptionInstance.set` 不触发回调，所以末尾自己推一次 native。 */
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
            Illusion.LOGGER.warn("could not read $OPTIONS_KEY from $file", t)
            return
        }
        // 文件里没有这一行时（首次运行 / 用户删过）也要推一次，让 native 与界面取值一致。
        push(OPTION.get())
        pushPreset(PRESET_OPTION.get())
        D3D12RenderScale.setDlssMode(OPTION.get())
        reconcileAntiAliasing()
    }

    /** 把开关/挡位/模型写回 `options.txt`（先丢自己的旧行；渲染缩放的两个键由 `D3D12RenderScale.save` 负责）。 */
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
            Illusion.LOGGER.warn("could not write $OPTIONS_KEY to $file", t)
        }
    }


    /**
     * 每帧**第一次**入口（主目标深度被清空**之前**）：录运动矢量 + 深度快照。**Evaluate 不挂这里**（此刻颜色里还没有本帧世界层）。
     */
    @JvmStatic
    fun captureMotionVectors() {
        // FSR / XeSS 也要这份数据，所以闸门是「DLSS 开着 或 另一家超分在跑」。
        if (!isEnabled() && !SuperResolution.needsVelocityPass()) {
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
        // 世界层必须真的按 DLSS 输入比例出图：比例从模式键载入时不重建主目标，附件仍是窗口尺寸，DLSS 就做 1:1 推理。见 [ensureDataPlane]。
        
        ensureDataPlane()
        // 速度通道若已自我关闭，趁这一帧清掉闩锁（限速 1 秒），下一帧才有运动矢量。
        retryVelocityIfLatched()
        // 相机的 model-view 从 vanilla 的 **CPU 渲染状态**取，不做 GPU 写映射读回（那条读回会把整帧打成常量清屏色）。
        captureCameraView(minecraft)
        val encoder = D3D12Device.sharedCommandEncoder() ?: return
        // 挂点在**帧图 main pass 的 RETURN**：旧挂点（清深度之前）实测深度全 0 => 运动矢量整张 0 => DLSS 把移动画面当静止累积（拖影）。
        encoder.recordVelocityPass()
        // 深度快照与这里**同一个挂点、同一份深度**，紧接着调 [captureDepthSnapshot]（先速度 pass、后快照）。
    }

    /**
     * 深度快照的挂点：**帧图 main pass 的 RETURN**（`addMainPass` 无条件执行）—— 再晚深度就已被清成 0。见 docs/superresolution/dlss.md §5。
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
        // 世界层必须真的按 DLSS 输入比例出图（见 [ensureDataPlane]）。
        
        ensureDataPlane()
        val encoder = D3D12Device.sharedCommandEncoder() ?: return
        snapshotDlssDepth(encoder, minecraft)
    }

    /**
     * 把**本帧世界层的深度**交给 native 快照：评价在 SMAA 那一刻，而 vanilla 早已把深度清成 0.0（深度项会变成常数）。
     * 深度来源必须与 [applyIfEnabled] 填 `pInDepth` 的是**同一表达式**，否则快照与喂给 NGX 的指向不同资源。
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
        p206Counter++
        if (p206Counter % 120 == 0) {
            IllusionLogger.debugMessage("evaluate result=".format(Locale.ROOT) + result.toString())
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

    private fun noteDepthSnapshot(message: String, warn: Boolean = false) {
        if (lastDepthSnapshotState == message) {
            return
        }
        lastDepthSnapshotState = message
        if (warn) {
            Illusion.LOGGER.warn("DLSS depth snapshot: {}", message)
        } else {
            Illusion.LOGGER.info("DLSS depth snapshot: {}", message)
        }
    }


    /** 是否启用了"把 JVM 建的纹理交给 native 当 DLSS 输出"的验收模式（默认关闭）。 */
    @JvmStatic
    fun isDlssOutputHandoverEnabled(): Boolean = dlssOutputHandoverEnabled()

    /**
     * DLSS 输出纹理交接（默认开启，配置 `[dlss] outputHandover`）：JVM 建 target 尺寸 UAV，native 把 NGX 输出写进去（关掉只退回私有 UAV，仅供排查）。
     */
    private fun dlssOutputHandoverEnabled(): Boolean {
        val enabled = D3D12Settings.dlssOutputHandover
        if (enabled != loggedHandoverMode) {
            loggedHandoverMode = enabled
            if (enabled) {
                Illusion.LOGGER.info(
                    "DLSS output handover is ON ({}={}): the JVM builds a "
                        + "target-sized UAV texture and native writes NGX's output into it. If "
                        + "this ever makes the world black, check that the handover succeeded and "
                        + "that native reports 'Evaluate succeeded' (the blit is only skipped then).",
                    "outputHandover", enabled)
            } else {
                Illusion.LOGGER.warn(
                    "DLSS output handover is OFF ({}={}): native falls back to its "
                        + "own private UAV, which cannot be written on this machine, so DLSS will "
                        + "have no visible effect. This is a troubleshooting setting only.",
                    "outputHandover", enabled)
            }
        }
        return enabled
    }

    /** 抗锯齿选项是否停在 DLAA（由 DLSS 特性实现：render == target + 挡位 3），由 [syncDlaaFromAntiAliasing] 每帧同步。 */
    @Volatile
    private var dlaaActive: Boolean = false

    /** 取证用：DLAA 当前是否生效。 */
    @JvmStatic
    fun isDlaaActive(): Boolean = dlaaActive

    /** 上一次看到的开关值（只在渲染线程读写）。 */
    private var loggedHandoverMode: Boolean = false

    /** 本帧 handover 是否真的成功（纹理建出来 + native 收下）。只在渲染线程读写。 */
    private var handoverOkThisFrame: Boolean = false

    /** 失效保护：native evaluate 连续失败的帧数；成功一帧清零。 */
    private var consecutiveEvaluateFailures: Int = 0

    /** [noteHandoverNotSkippingBlit] 的限流标记（同一条连续区间只报一次）。 */
    private var handoverSkipNoteShown: Boolean = false

    /** 把本帧的相机 model-view / projection 喂给 [D3D12Temporal]（走 CPU 渲染状态，不做 UBO 写映射读回）。 */
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
        val projection = state.projectionMatrix
        val proj = FloatArray(16)
        projection.get(proj)
        D3D12Temporal.captureProjection(proj)
    }

    /** 每帧的**第二次**入口：SMAA 之后、`prepareForGui()` 之前 —— 保证「先 SMAA、后升采样」，且喂给 NGX 的是真正的世界层颜色。 */
    @JvmStatic
    fun pclFrameBegin() {
        MCDR_ABI.D3D12ReflexFrameBegin()
    }

    @JvmStatic
    fun pclRenderSubmitStart() {
        MCDR_ABI.D3D12ReflexMarker(1)  // sl::PCLMarker::eSimulationEnd
        MCDR_ABI.D3D12ReflexMarker(2)  // sl::PCLMarker::eRenderSubmitStart
    }

    @JvmStatic
    fun applyIfEnabled() {
        // 闸门只看 DLSS 自己的开关（`dxrMcDlss`）；关掉时把数据面与状态复位。抗锯齿在本类这侧只是被锁成 OFF。
        if (!isEnabled()) {
            leaveDlssMode()
            return
        }
        if (legacyBridge) {
            reassertLegacyAaMode()
        }
        if (broken) {
            return
        }
        val minecraft = Minecraft.getInstance()
        val level = minecraft.level ?: return
        if (pendingOutputSample) {
            pendingOutputSample = false
            reportOutputSample()
        }
        modeActive = true
        // 世界层必须真的按 DLSS 输入比例出图；附件尺寸与「窗口 × 当前比例」不符时排一次主目标重建（限频 2s）。
        
        ensureDataPlane()
        retryVelocityIfLatched()
        if (!ensureAvailable()) {
            return
        }

        // 救回「渲染比例没法用」的配置：100%（等于没升采样）与低于该档名义输入的比例（后者 NGX 交回空纹理：世界全黑）。
        if (!dlaaActive) {
            val currentPercent = D3D12RenderScale.percent()
            val nominalPercent = percentForTier(quality())
            if (currentPercent == 100 || (currentPercent > 0 && currentPercent < nominalPercent)) {
                if (currentPercent > 0 && currentPercent < nominalPercent) {
                    if (!reportedBelowNominal) {
                        reportedBelowNominal = true
                        Illusion.LOGGER.warn(
                            "render scale {}% is below what tier {} needs ({}%); raising it, " +
                                "otherwise NGX rejects the frame and the world goes black",
                            currentPercent, QUALITY_NAMES[quality()], nominalPercent,
                        )
                    }
                    // 走帧边界那一版：帧中间改比例会让本帧世界层目标与主目标附件尺寸不一致。
                    D3D12RenderScale.setPercentAtFrameBoundary(nominalPercent)
                } else {
                    bindRenderScaleToDlss()
                }
            }
        }

        val renderTarget = minecraft.gameRenderer.mainRenderTarget()
        val color = (renderTarget.colorTextureView as? D3D12GpuTextureView)?.texture() as? D3D12GpuTexture
            ?: return noteState("the world layer is not a D3D12 texture")
        val depth = renderTarget.depthTexture as? D3D12GpuTexture
            ?: return noteState("the main render target has no D3D12 depth texture")
        // 世界切换时主目标整批重建，旧纹理可能已被释放；把句柄交给 native 会在 `GetDesc()` 上撞野指针。
        if (color.isClosed() || depth.isClosed()) {
            return noteState("the world layer's color/depth was released this frame (world transition)")
        }
        // pInOutput：`targetForFrame()` 在 renderScale == 100% 时返回 null，而 NGX 要求输入输出是两张资源。
        // DLAA 正是 100%：取主目标颜色纹理，native 重定向到交接的 target 尺寸纹理，评价完再拷回。
        val guiTarget = D3D12GUISplit.targetForFrame() ?: D3D12GUISplit.ensureTargetForDlss()
        val guiOutput = (guiTarget?.colorTextureView as? D3D12GpuTextureView)
            ?.texture() as? D3D12GpuTexture
        if (guiOutput == null) {
            noteState("guiTarget=" + guiTarget + " view=" + guiTarget?.colorTextureView?.javaClass?.simpleName
                + " dlaa=" + dlaaActive)
        }
        val output = guiOutput ?: if (dlaaActive) {
            (minecraft.gameRenderer.mainRenderTarget().colorTextureView as? D3D12GpuTextureView)
                ?.texture() as? D3D12GpuTexture
        } else {
            null
        } ?: return noteState(
            "no window-sized output target: percent=" + D3D12RenderScale.percent()
                + " scaleEnabled=" + D3D12RenderScale.isEnabled()
                + " mainTarget=" + renderTarget.width + "x" + renderTarget.height
                + " window=" + minecraft.window.width + "x" + minecraft.window.height
                + " (dxrMcRenderScale must not be 100% so that the world layer and the presented "
                + "target are two different textures)")
        val encoder = D3D12Device.sharedCommandEncoder() ?: return noteState("no shared command encoder")

        val renderWidth = color.getWidth(0)
        val renderHeight = color.getHeight(0)
        val targetWidth = output.getWidth(0)
        val targetHeight = output.getHeight(0)
        if (renderWidth <= 0 || renderHeight <= 0 || targetWidth <= 0 || targetHeight <= 0) {
            return noteState("degenerate sizes: render ${renderWidth}x${renderHeight}, "
                + "target ${targetWidth}x${targetHeight}")
        }

        D3D12Temporal.adoptPhaseCount(
            MCDR_ABI.SR_BACKEND_DLSS,
            MCDR_ABI.D3D12SrJitterPhaseCount(MCDR_ABI.SR_BACKEND_DLSS, renderWidth, targetWidth),
        )

        // 把 JVM 建的 target 尺寸 UAV 交给 native 当 DLSS 输出 —— **默认路径**（配置 `[dlss] outputHandover = false` 才关）；
        // **失效保护**：只有「handover 成功 + native 报 evaluate 成功」才跳过世界层 blit，否则照旧 blit（绝不黑屏）。
        var handoverOkThisFrame = false
        if (dlssOutputHandoverEnabled()) {
            val dlssOutput = DLSSOutput.textureForFrame(targetWidth, targetHeight)
            if (dlssOutput != null) {
                handoverOkThisFrame = DLSSOutput.handOver(dlssOutput)
            }
        }
        this.handoverOkThisFrame = handoverOkThisFrame
        val packetOutput = output

        // 运动矢量本该在上一处挂点（清深度之前）录好，这里是兜底；同帧已录过时 D3D12Velocity 的每帧闸门直接返回。
        encoder.recordVelocityPass()
        val motionVectors = D3D12Velocity.currentHandle()
        if (motionVectors == 0L) {
            return noteState("no motion-vector texture (the velocity pass has no history yet on the "
                + "frame DLSS is switched on, or it disabled itself - see the velocity log)")
        }

        val jitter = D3D12Temporal.jitter()
        val scalePercent = D3D12RenderScale.percent()
        val window = minecraft.window
        val resetReason = resetReason(level, renderWidth, renderHeight, targetWidth, targetHeight,
            scalePercent, window.width, window.height)
        p205Counter++
        if (p205Counter % 120 == 0) {
            IllusionLogger.debugMessage(
                ("render=%dx%d target=%dx%d window=%dx%d scale=%d%% reset=%s "
                    + "jitter=(%.4f, %.4f) frameNs=%d once=%s")
                    .format(Locale.ROOT, renderWidth, renderHeight, targetWidth, targetHeight,
                        window.width, window.height, scalePercent, resetReason ?: "-",
                        jitter[0], jitter[1], minecraft.frameTimeNs, evaluatedOnce.toString()),
            )
        }
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

        pushDlssConstants(renderWidth, renderHeight, targetWidth, targetHeight,
            jitter[0].toFloat(), jitter[1].toFloat(), resetReason != null)

        val result = try {
            encoder.recordDlssEvaluate(packet)
        } catch (t: Throwable) {
            return disable("dx12EvaluateDlss threw: $t")
        }
        p206Counter++
        if (p206Counter % 120 == 0) {
            IllusionLogger.debugMessage("evaluate result=".format(Locale.ROOT) + result.toString())
        }
        when (result) {
            // 1 = 这一帧真的评价了：结果已经写进 pInOutput，本帧不能再做世界层 blit。
            1 -> {
                onEvaluated(level, renderWidth, renderHeight, targetWidth, targetHeight, scalePercent,
                    window.width, window.height, jitter[0].toFloat(), jitter[1].toFloat(), resetReason)
                // 升采样之后的后置锐化（SDK 自带 sharpness 已废弃）。
                sharpenUpscaledFrame(encoder, output, targetWidth, targetHeight)
                // 黑帧采样整条路径已停用：读回的是未定义内容，按它熔断等于拿噪声决定要不要关 DLSS（见 [reportOutputSample]）。
            }
            // 0 = native 自己跳过（模式/尺寸/特征未就绪），它已打过自己的 WARN；这里只在状态变化时报一行。
            0 -> {
                countEvaluateFailure()
                noteState("native skipped this frame (feature not ready, or the sizes changed "
                    + "without a successful create)")
            }
            // 2 = 结果落在 native 私有纹理上：prepareForGui 必须继续 blit，否则界面目标里没有世界内容（世界全黑、GUI 正常）。
            2 -> {
                countEvaluateFailure()
                noteState("DLSS evaluated into its private output texture (the JVM hand-over " 
                    + "texture was rejected); keeping the linear blit so the world stays visible")
            }
            -1 -> {
                countEvaluateFailure()
                noteProblem("native rejected the input packet (-1: invalid arguments). "
                    + "Resource states are left untouched this frame")
            }
            Int.MIN_VALUE -> {
                countEvaluateFailure()
                noteProblem("the loaded MCDR.dll has no mcdr_dx12EvaluateDlss "
                    + "(the DLL predates this); DLSS stays off until a matching DLL is in place")
            }
            else -> {
                countEvaluateFailure()
                noteProblem("dx12EvaluateDlss returned an unexpected value $result")
            }
        }
    }


    /** 本帧被 [deferCrosshair] 拦下的那一笔十字准星（每帧最多一笔）。只在渲染线程读写，不加锁。 */
    private var pendingCrosshairRenderer: DebugCrosshairRenderer? = null
    private var pendingCrosshairCamera: CameraRenderState? = null
    private var pendingCrosshairGuiScale: Int = 0

    /** 26.3：`render` 现在要求显式传目标视图，补画时原样用回这两张。 */
    private var pendingCrosshairColor: GpuTextureView? = null
    private var pendingCrosshairDepth: GpuTextureView? = null

    /**
     * 把 F3 的 3D 十字准星**推迟**到本帧评价之后：它世界对齐又没有运动矢量，画进主目标会被 NGX 当静止内容累积（转视角拖影）。
     */
    @JvmStatic
    fun deferCrosshair(renderer: DebugCrosshairRenderer, cameraState: CameraRenderState, guiScale: Int,
        colorTexture: GpuTextureView, depthTexture: GpuTextureView) {
        if (colorTexture.isClosed() || depthTexture.isClosed()) {
            return
        }
        pendingCrosshairRenderer = renderer
        pendingCrosshairCamera = cameraState
        pendingCrosshairGuiScale = guiScale
        pendingCrosshairColor = colorTexture
        pendingCrosshairDepth = depthTexture
    }

    /**
     * 补画 [deferCrosshair] 存下的准星并清空待画槽。调用点是 [applyIfEnabled] 的下一行，**不能挪进它**（它有多条提前 return，那些帧会丢准星）。
     */

    @JvmStatic
    fun replayCrosshair() {

        val renderer = pendingCrosshairRenderer ?: return
        val cameraState = pendingCrosshairCamera
        val guiScale = pendingCrosshairGuiScale
        pendingCrosshairRenderer = null
        pendingCrosshairCamera = null
        pendingCrosshairGuiScale = 0
        pendingCrosshairColor = null
        pendingCrosshairDepth = null
        if (cameraState == null) {
            return
        }
        // 补画目标必须是**这一刻真正会被 present 的主目标**：DLSS 跑起来后世界层已搬进 GUI 目标，画回输入目标等于画进没人读的纹理。
        val mainTarget = Minecraft.getInstance().gameRenderer.mainRenderTarget()
        val target = D3D12GUISplit.replayTarget() ?: mainTarget
        val colorView = target.colorTextureView ?: return
        val depthView = target.depthTextureView ?: return

        D3D12RenderPassBackend.replayProbe = true
        try {
            renderer.render(cameraState, guiScale, colorView, depthView)
        } catch (e: Throwable) {
            Illusion.LOGGER.warn("replay threw: {}", e.toString())
        } finally {
            D3D12RenderPassBackend.replayProbe = false
        }
    }

    /** handover 没成功、于是**没有**跳过世界层 blit 时的取证行（只在「开始」与「恢复」各一行）。 */
    private fun noteHandoverNotSkippingBlit() {
        if (handoverSkipNoteShown) {
            return
        }
        handoverSkipNoteShown = true
        Illusion.LOGGER.warn(
            "handover did not take effect this frame, so the world layer is still "
                + "blitted by prepareForGui (no black screen). DLSS keeps evaluating; this line "
                + "appears once per streak.")
    }

    /** evaluate 连续失败计数：失败帧不会 `markWorldLayerDone()`，画面不会黑；连败超阈值时打一行限流 WARN。 */
    private fun resetOutputSampleSchedule() {
        defuseBlackOutput = false
        pendingOutputSample = false
        sampleTarget = null
        sampleFromState = 0
        evaluatedFrames = 0
        blackSamples = 0
        goodSamples = 0
        firstSampleReported = false
        nextSampleAt = SAMPLE_WARMUP_FRAMES
    }

    /**
     * **已停用**：读回的判据用的是未定义内容（barrier 的 StateBefore 说错），实测恒为 0，会把每个会话误判成黑帧并永久退回线性 blit。
     */
    private fun reportOutputSample() {
        return
    }

    private fun countEvaluateFailure() {
        if (consecutiveEvaluateFailures < Int.MAX_VALUE) {
            consecutiveEvaluateFailures++
        }
        handoverSkipNoteShown = false
        if (consecutiveEvaluateFailures == EVALUATE_FAILURE_WARN_THRESHOLD) {
            Illusion.LOGGER.error(
                "DLSS has failed to evaluate {} frames in a row; the world layer "
                    + "is being blitted every frame instead (this is the safe path, not a crash). "
                    + "Set [logging] logLevel = INFO in config/dxr_mc-client.toml to see the DLSS reason.",
                consecutiveEvaluateFailures)
        }
    }


    private fun onEvaluated(level: Any, renderWidth: Int, renderHeight: Int, targetWidth: Int,
        targetHeight: Int, scalePercent: Int, windowWidth: Int, windowHeight: Int,
        jitterX: Float, jitterY: Float, resetReason: String?) {
        // 只有「本帧 handover 成功 + native 报 evaluate 成功」才允许跳过世界层 blit；否则宁可照旧 blit，绝不赌。
        val handoverEnabled = dlssOutputHandoverEnabled()
        val handoverOk = this.handoverOkThisFrame
        if ((!handoverEnabled || handoverOk) && !defuseBlackOutput) {
            // 关键：DLSS 已经把升采样后的世界层写进 GUI 目标，本帧的 prepareForGui 必须跳过世界层 blit。
            D3D12GUISplit.markWorldLayerDone()
        } else {
            noteHandoverNotSkippingBlit()
        }
        consecutiveEvaluateFailures = 0
        lastState = STATUS_EVALUATED
        if (resetReason != null) {
            Illusion.LOGGER.info(
                "reset=1 this frame - {} (render {}x{} -> target {}x{})",
                resetReason, renderWidth, renderHeight, targetWidth, targetHeight)
        }
        if (!loggedFed) {
            loggedFed = true
            // near/far 的降级说明只跟"第一次真的喂进去"一起出现一次。
            Illusion.LOGGER.info(
                "near/far are not reachable from the JVM side, so 0.0 is reported "
                    + "for both; native only writes them into its own DLSS diagnostic line")
            Illusion.LOGGER.info(
                "DLSS inputs fed - render {}x{} -> target {}x{} jitter=({}, {}) "
                    + "mvScale={} near={} far={} depthInverted=1 ngx={}",
                renderWidth, renderHeight, targetWidth, targetHeight, jitterX, jitterY,
                MV_SCALE, NEAR_Z, FAR_Z, ngxSummary)
        }
        commitState(level, renderWidth, renderHeight, targetWidth, targetHeight, scalePercent,
            windowWidth, windowHeight)
    }

    /** `reset` 的判定：首帧、世界切换、渲染/目标/窗口尺寸变化、比例变化；null = 不用丢历史（只在评价成功后记值）。 */
    /** 把本帧相机常量写进 `MCDRLayout.DLSS_CONSTANTS` 交给 native（Streamline `sl::Constants` 的数据源）；旧 DLL 无该导出则跳过。 */
    /** 把相机平移烘进「纯旋转」视图矩阵：`V_world = V_rot * T(-camPos)`。`clipToPrevClip` 必须含相机平移，否则「静止正常、一走动就拖影」。 */
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
     * 升采样**之后**的锐化。native 的 sharpen/blit 都拒绝 `src == dst`，所以两趟：`target -> scratch` 再 `scratch -> target`。DLAA 时 output 是主目标颜色纹理。
     */
    private fun sharpenUpscaledFrame(encoder: D3D12CommandEncoderBackend, target: D3D12GpuTexture,
        width: Int, height: Int) {
        if (!D3D12PostSharpen.isEnabled()) {
            return
        }
        val scratch = D3D12PostSharpen.scratchForFrame(width, height) ?: return
        if (!encoder.recordSharpen(target.handle(), scratch.handle())) {
            return
        }
        if (!MCDR_ABI.dx12BlitTextureToTexture(encoder.nativeHandle(), scratch.handle(),
                target.handle())) {
            noteState("post-sharpen: the blit back into the output target failed; the frame keeps "
                + "the unsharpenened DLSS output")
        }
    }

    @Volatile
    private var p203Counter: Int = 0
    private var p205Counter: Int = 0
    private var p206Counter: Int = 0

    /** 上报给 DLSS/DLAA 的像素空间 jitter 必须是 `(+Jx, -Jy)`：实机 A/B 定案，其余符号组合都会让静止画面持续轻晃。 */
    private fun reportedJitter(jx: Float, jy: Float): FloatArray = floatArrayOf(jx, -jy)

    /**
     * 从透视投影矩阵反算垂直 FOV：m11 = 1/tan(fovY/2)，列主序在 index 5。不能用 `options.fov()` 标称值（漏掉疾跑/药水等修饰符）。
     */
    private fun fovFromProjection(proj: FloatArray): Float {
        val m11 = proj[5]
        if (m11.isFinite() && m11 > 1.0e-4f) {
            return 2.0f * atan(1.0f / m11)
        }
        return Math.toRadians(Minecraft.getInstance().options.fov().get().toDouble()).toFloat()
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
        // `view` 只有旋转，而 `clipToPrevClip` 必须包含**相机平移**，否则相机一走位就只算了旋转（静止正常、一走动就拖影）。
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
        // 帧常量抽到 `FrameGenerationConstants` 与 SR 后端解耦：FSR/XeSS 超分时这段不跑，XeSS-FG 也要拿到相机矩阵。
        FrameGenerationConstants.push(jitterX, jitterY, reset)
        // `view` 是**列主序**的世界->相机旋转，相机在世界空间的 right/up/fwd 是它的**行**（(r,c) 在 r + c*4）。
        val rightX = view[0]; val rightY = view[4]; val rightZ = view[8]
        val upX = view[1]; val upY = view[5]; val upZ = view[9]
        val fwdX = view[2]; val fwdY = view[6]; val fwdZ = view[10]
        // FOV 从**实际投影矩阵**反算（标称值漏掉疾跑/药水/望远镜修饰符）；m11 = 1/tan(fovY/2)，列主序在 index 5。
        val fovRadians = fovFromProjection(proj)
        val aspect = if (targetHeight > 0) targetWidth.toFloat() / targetHeight.toFloat() else 1.0f
        val layout = MCDRLayout.DLSS_CONSTANTS
        Arena.ofConfined().use { arena ->
            val seg = arena.allocate(layout)
            MemorySegment.copy(viewProj, 0, seg, ValueLayout.JAVA_FLOAT,
                layout.byteOffset(PathElement.groupElement("viewProjection")), 16)
            MemorySegment.copy(prevViewProj, 0, seg, ValueLayout.JAVA_FLOAT,
                layout.byteOffset(PathElement.groupElement("prevViewProjection")), 16)
            // `cameraViewToClip` 的语义是 view->clip（就是这张投影），不是 world->clip 的 VP。
            MemorySegment.copy(proj, 0, seg, ValueLayout.JAVA_FLOAT,
                layout.byteOffset(PathElement.groupElement("projection")), 16)
            // `sl::Constants::jitterOffset` 是 D3D 像素空间（y 向下）；注入实测位移是 `(-Jx, +Jy)`，唯一正确的一档是 `(+Jx, -Jy)`。
            val reported = reportedJitter(jitterX, jitterY)
            val slJitterX = reported[0]
            val slJitterY = reported[1]
            p203Counter++
            if (p203Counter % 120 == 0) {
                val injected = D3D12Temporal.injectedJitter()
                // String.format 只认 %s/%f，写 `{}` 只会打出字面量。
                IllusionLogger.debugMessage(
                    "jitter current=(%.4f, %.4f) injected=(%.4f, %.4f) reported=(%.4f, %.4f) mismatch=%s"
                        .format(
                            Locale.ROOT,
                            jitterX, jitterY,
                            injected?.get(0) ?: Double.NaN, injected?.get(1) ?: Double.NaN,
                            slJitterX, slJitterY,
                            D3D12Temporal.injectedJitterMismatch().toString(),
                        ),
                )
            }
            seg.set(F32, layout.byteOffset(PathElement.groupElement("jitterOffsetX")), slJitterX)
            seg.set(F32, layout.byteOffset(PathElement.groupElement("jitterOffsetY")), slJitterY)
            // Streamline 的 `mvecScale` 要**归一化比例**（像素空间 MV => `1/renderWidth`），与输入包里 NGX 语义的 `mvScaleX` 不同。
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
            // near/far 从投影解出：列主序 m[10]=A=(f+n)/(n-f)、m[14]=B=2fn/(n-f)，于是 n=B/(A-1)、f=B/(A+1)。
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
            // MC 是 reverse-Z（构造投影时 near/far 互换），解出的两个平面会跟着互换 —— 交给 SL 前按大小改正。
            val nearZ = if (nearRaw > farRaw) farRaw else nearRaw
            val farZ = if (nearRaw > farRaw) nearRaw else farRaw
            seg.set(F32, layout.byteOffset(PathElement.groupElement("cameraNear")), nearZ)
            seg.set(F32, layout.byteOffset(PathElement.groupElement("cameraFar")), farZ)
            seg.set(F32, layout.byteOffset(PathElement.groupElement("cameraFovRadians")), fovRadians)
            seg.set(F32, layout.byteOffset(PathElement.groupElement("cameraAspectRatio")), aspect)
            seg.set(I32, layout.byteOffset(PathElement.groupElement("depthInverted")), 1)
            seg.set(I32, layout.byteOffset(PathElement.groupElement("cameraMotionIncluded")), 1)
            // 我们的 MV 是**屏幕空间**像素位移，不是 3D 世界空间速度；填 1 会让 SL 再投影一次（运动时才有拖影）。
            seg.set(I32, layout.byteOffset(PathElement.groupElement("motionVectors3D")), 0)
            seg.set(I32, layout.byteOffset(PathElement.groupElement("reset")), if (reset) 1 else 0)
            seg.set(I32, layout.byteOffset(PathElement.groupElement("renderingGameFrames")), 1)
            // MV 是真实运动（`D3D12Velocity` 不再加 PrevJitterPixels），必须报 0：报 1 等于让 DLSS 去减一个不存在的量。
            seg.set(I32, layout.byteOffset(PathElement.groupElement("motionVectorsJittered")), 0)

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
     * DLSS 需要 jitter 与运动矢量两条数据面（`dxrmc.temporal*` 构建期属性，默认 false）：选了 DLSS 就必须打开，否则 native 拿到空句柄。
     */
    private fun ensureDataPlaneReady() {
        if (!D3D12Temporal.enabled) {
            D3D12Temporal.enabled = true
            forcedTemporal = true
            Illusion.LOGGER.info(
                "DLSS needs the temporal data plane: sub-pixel projection jitter "
                    + "(dxrmc.temporal) is switched on while DLSS is selected")
        }
        if (!D3D12Velocity.enabled) {
            D3D12Velocity.enabled = true
            forcedVelocity = true
            Illusion.LOGGER.info(
                "DLSS needs motion vectors: the velocity pass "
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
     * 速度通道的「自我关闭」是一票否决：只把 `enabled` 置回 true 撤不回来（下一帧 `record` 仍短路、`currentHandle()` 恒 0），
     * 清闩锁必须走 [D3D12Velocity.forceEnable]，并限速 [VELOCITY_RETRY_INTERVAL_NANOS]。
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
        lastVelocityRetryNanos = Long.MIN_VALUE
        resetInputState()
        if (forcedTemporal) {
            forcedTemporal = false
            D3D12Temporal.reset()
            D3D12Temporal.enabled = false
            if (forcedVelocity) {
                forcedVelocity = false
                D3D12Velocity.enabled = false
            }
            Illusion.LOGGER.info("DLSS is no longer selected: the temporal data plane "
                + "it had switched on is back to off")
        } else if (forcedVelocity) {
            forcedVelocity = false
            D3D12Velocity.reset()
            D3D12Velocity.enabled = false
            Illusion.LOGGER.info("DLSS is no longer selected: the velocity pass it had "
                + "switched on is back to off")
        }
    }

    private fun resetInputState() {
        evaluatedOnce = false
        // 熔断与「已采样」都是**会话级**状态，退出 DLSS 时必须复位，否则一次黑帧会让之后每次进世界都退化成线性放大。
        resetOutputSampleSchedule()
        lastRenderWidth = -1
        lastRenderHeight = -1
        lastTargetWidth = -1
        lastTargetHeight = -1
        lastScalePercent = -1
        lastWindowWidth = -1
        lastWindowHeight = -1
        lastLevel = null
        lastState = null
        lastDepthSnapshotState = null
    }

    /** `dx12QueryDlss` 每次进入 DLSS 模式探一次：不可用时不必再每帧下探，原因（含 native 文本）进 JVM 日志。 */
    /** 「视频设置 -> Illusion 设置」DLSS 段的运行状态行，内容完全由 native 的 `mcdr_dx12QueryDlss` 给出；只应在界面打开时调用。 */
    @JvmStatic
    fun statusLine(): Component {
        val note = try {
            noteSegment()
        } catch (t: Throwable) {
            return Component.literal("DLSS: $t")
        }
        val result = try {
            MCDR_ABI.D3D12QueryDlss(note, NOTE_CAPACITY)
        } catch (t: Throwable) {
            return Component.literal("DLSS: query threw ($t)")
        }
        if (result == Int.MIN_VALUE) {
            return Component.translatable("dxr_mc.video.sr.noabi", "DLSS")
        }
        val text = runCatching { note.getString(0) }.getOrDefault("").ifEmpty { "(no detail)" }
        return Component.literal("DLSS: " + text)
    }

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
            runCatching { note.getString(0) }.getOrDefault("").ifEmpty { "(no detail)" }
        }
        ngxSummary = "result=$result text=$text"
        available = result == 1
        if (available) {
            Illusion.LOGGER.info("DLSS is available: {}", text)
        } else {
            Illusion.LOGGER.warn("DLSS cannot run on this machine; the world layer "
                + "keeps going through the linear blit: {}", text)
        }
        return available
    }

    // ------------------------------------------------------------------
    // native 内存：一个 Arena、一个参数包（每帧复用）
    // ------------------------------------------------------------------

    /** 本会话唯一的一块 native 内存。`Arena.ofConfined()` 的所有者是**第一次调用它的线程**（渲染线程），所以所有访问点都在同一线程上。 */
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

    /** `DLSS_INPUT` 里具名字段的字节偏移；名字与 `mcdr_abi.h:363-400` 逐字一致。 */
    private fun offset(name: String): Long =
        MCDRLayout.DLSS_INPUT.byteOffset(PathElement.groupElement(name))

    /**
     * 逐字段写参数包：`renderWidth/Height` = 世界层颜色附件的真实尺寸；`targetWidth/Height` = pInOutput 的真实尺寸；jitter 走 [reportedJitter]。
     */
    private fun writePacket(packet: MemorySegment, renderWidth: Int, renderHeight: Int,
        targetWidth: Int, targetHeight: Int, jitterX: Float, jitterY: Float, reset: Boolean,
        depthFormatOrdinal: Int, color: Long, depth: Long, motionVectors: Long, output: Long) {
        packet.set(I32, offset("renderWidth"), renderWidth)
        packet.set(I32, offset("renderHeight"), renderHeight)
        packet.set(I32, offset("targetWidth"), targetWidth)
        packet.set(I32, offset("targetHeight"), targetHeight)
        val reported = reportedJitter(jitterX, jitterY)
        packet.set(F32, offset("jitterOffsetX"), reported[0])
        packet.set(F32, offset("jitterOffsetY"), reported[1])
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
        packet.set(PTR, offset("pInColor"), MemorySegment.ofAddress(color))
        packet.set(PTR, offset("pInDepth"), MemorySegment.ofAddress(depth))
        packet.set(PTR, offset("pInMotionVectors"), MemorySegment.ofAddress(motionVectors))
        packet.set(PTR, offset("pInOutput"), MemorySegment.ofAddress(output))
    }


    /** 跳过（原因由 native 决定）：只在原因变化时报一行，避免每帧刷屏。 */
    private fun noteState(message: String) {
        if (lastState == message) {
            return
        }
        lastState = message
        Illusion.LOGGER.info("DLSS did not run this frame: {}", message)
    }

    /** 真问题（-1 / 没有导出 / 意外返回值）：同样只在状态变化时报一行，级别 WARN。 */
    private fun noteProblem(message: String) {
        if (lastState == message) {
            return
        }
        lastState = message
        Illusion.LOGGER.warn("DLSS problem: {}", message)
    }

    /** 自我关闭：只报一次，之后本会话不再尝试喂数据。 */
    private fun disable(reason: String) {
        if (broken) {
            return
        }
        broken = true
        Illusion.LOGGER.error("DLSS input feeding is disabled for this session: {}", reason)
    }
}

    /** 列主序 -> 行主序（XeSS-FG 的 frame constants 要求 row-major）。 */
private fun fgRowMajor(src: FloatArray): FloatArray {
    val out = FloatArray(16)
    for (r in 0 until 4) {
        for (c in 0 until 4) {
            out[r * 4 + c] = src[c * 4 + r]
        }
    }
    return out
}
