package dev.nts.expdxr.dxrmc.sr

import dev.nts.expdxr.Illusion
import dev.nts.expdxr.SrBackend
import dev.nts.expdxr.d3d12.extern.MCDR_ABI
import dev.nts.expdxr.d3d12.extern.MCDRLayout
import dev.nts.expdxr.d3d12.extern.MCDRLayout.F32
import dev.nts.expdxr.d3d12.extern.MCDRLayout.I32
import dev.nts.expdxr.d3d12.extern.MCDRLayout.PTR
import dev.nts.expdxr.d3d12.backend.D3D12CommandEncoderBackend
import dev.nts.expdxr.d3d12.render.adapter.D3D12Device
import dev.nts.expdxr.d3d12.render.advanced.smaa.D3D12GUISplit
import dev.nts.expdxr.d3d12.render.advanced.taa.D3D12Temporal
import dev.nts.expdxr.d3d12.render.advanced.taa.D3D12Velocity
import dev.nts.expdxr.d3d12.render.advanced.vrs.D3D12RenderScale
import dev.nts.expdxr.d3d12.render.core.D3D12GpuTexture
import dev.nts.expdxr.d3d12.render.core.D3D12GpuTextureView
import dev.nts.expdxr.dxrmc.sr.dlss.DLSSOutput
import dev.nts.expdxr.dxrmc.sr.dlss.DeepLearningSuperSampling
import dev.nts.expdxr.misc.settings.D3D12Settings
import dev.nts.expdxr.misc.IllusionLogger
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.io.File
import java.util.Locale
import java.nio.charset.StandardCharsets
import kotlin.math.atan
import kotlin.math.roundToInt
import net.minecraft.client.Minecraft
import net.minecraft.client.OptionInstance
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.client.gui.components.CycleButton
import com.mojang.serialization.Codec
import java.util.Optional
import net.minecraft.network.chat.Component
import kotlin.math.ceil

/**
 * 超分门面：按 `[sr] backend` 分流。DLSS 原样委托 [DeepLearningSuperSampling]（那条链路的状态机不可合并）；
 * FSR / XeSS 每帧只做：打包 -> `mcdr_dx12SrEvaluate` -> blit / 交接。见 docs/superresolution/README.md §2。
 */
object SuperResolution {

    private const val NEAR_PLANE = 0.05f

    private const val PRE_EXPOSURE = 1.0f

    private const val MOTION_VECTOR_SCALE = 1.0f

    /** 上报给后端的 jitter 固定 `(+Jx, -Jy)`：只翻 y。 */
    private const val JITTER_SIGN_Y = -1.0f

    private const val FALLBACK_FAR = 512.0f

    private var lastState: String = ""

    /** 上一组 (backend, 尺寸, 比例)；变化即要求后端 reset 历史。 */
    private var lastKey: String = ""

    /** 已建上下文的 key：`mcdr_dx12SrCreate` 幂等，但不必每帧付一次 ABI 往返。 */
    private var createdFor: String = ""

    private var lastFrameNanos: Long = 0L

    /** 「100% 没活可干」的自愈只做一次，否则会跟故意拖回 100% 的用户抢。 */
    private var autoScaleAdjusted = false

    private var capsCache: MCDR_ABI.SrCaps? = null
    private var capsCacheNanos = 0L

    /** 录制一帧（打包 + 跨 ABI 调用）与随后 blit 的 **CPU 墙上时间**，不是 GPU 开销。 */
    private val evalSamples = LongArray(COST_SAMPLES)
    private val blitSamples = LongArray(COST_SAMPLES)
    private var sampleCursor = 0

    /** 让位已排到帧边界，避免每帧往主线程队列塞 Runnable。 */
    private var standDownScheduled = false
    private var startupChecked = false

    /**
     * GPU 侧真实开销：evaluate 前后各一次 TIMESTAMP，每 [GPU_SAMPLES] 帧读回一次整池。
     * 不能每帧读：native 的 resolve 会建命令列表并 flushAndWait。
     */
    private var gpuPool = 0L
    private var gpuFrame = 0
    private var gpuPoolWarned = false
    private val gpuSamples = LongArray(GPU_SAMPLES)

    /** 数据面是**我们**打开的 —— 关掉 SR 时要还原成配置原值。 */
    private var forcedTemporal = false
    private var forcedVelocity = false

    private var lastVelocityRetryNanos = Long.MIN_VALUE

    /** 诊断心跳的限速时间戳（0 = 还没打过）。 */
    private var lastVelocityDiagNanos = 0L

    private var lastEnterDiagNanos = 0L

    private const val VELOCITY_DIAG_NANOS = 1_000_000_000L

    private const val VELOCITY_RETRY_NANOS = 1_000_000_000L

    /** 后两家的最小渲染尺寸（与 native 能力面的 320x180 一致）。 */
private const val MIN_RENDER_WIDTH = 320
private const val MIN_RENDER_HEIGHT = 180

private const val DEFAULT_RENDER_PERCENT = 75

    private const val CAPS_TTL_NANOS = 1_000_000_000L

    private const val COST_SAMPLES = 128

    private const val COST_LOG_EVERY = 120

    private const val GPU_SAMPLES = 120

    // 载荷字段偏移：一次性查好，别每帧做 28 次 PathElement 查找。
    private val OFF_RENDER_W = off("renderWidth")
    private val OFF_RENDER_H = off("renderHeight")
    private val OFF_DISPLAY_W = off("displayWidth")
    private val OFF_DISPLAY_H = off("displayHeight")
    private val OFF_JITTER_X = off("jitterOffsetX")
    private val OFF_JITTER_Y = off("jitterOffsetY")
    private val OFF_MV_SCALE_X = off("motionVectorScaleX")
    private val OFF_MV_SCALE_Y = off("motionVectorScaleY")
    private val OFF_PRE_EXPOSURE = off("preExposure")
    private val OFF_FRAME_MS = off("frameTimeDeltaMs")
    private val OFF_NEAR = off("cameraNear")
    private val OFF_FAR = off("cameraFar")
    private val OFF_FOV = off("cameraVerticalFov")
    private val OFF_SHARPNESS = off("sharpness")
    private val OFF_RESET = off("reset")
    private val OFF_HDR = off("hdrInput")
    private val OFF_BACKEND = off("backend")
    private val OFF_FLAGS = off("flags")
    private val OFF_COLOR_IN = off("pColorInput")
    private val OFF_COLOR_OUT = off("pColorOutput")
    private val OFF_DEPTH = off("pDepth")
    private val OFF_MV = off("pMotionVectors")
    private val OFF_MASK = off("pReactiveMask")
    private val OFF_EXPOSURE = off("pExposure")
    private val OFF_DEPTH_FORMAT = off("depthFormatOrdinal")
    private val OFF_ASPECT = off("cameraAspectRatio")
    private val OFF_METERS = off("viewSpaceToMetersFactor")

    private fun off(name: String): Long = MCDRLayout.offset(MCDRLayout.SR_DISPATCH, name)

    /** 当前后端的 native 编号（`mcdr_sr_caps.backend` 的取值，0 = 关）。 */
    @JvmStatic
    fun backendId(): Int = currentBackend().id

    /**
     * 速度通道 / 相机矩阵的生产者仍在 [DeepLearningSuperSampling.captureMotionVectors] 里，FSR / XeSS 也要用；
     * 这个谓词供那个挂点的闸门使用：DLSS 关着但另一家超分在跑时它必须继续工作。
     */
    @JvmStatic
    fun needsVelocityPass(): Boolean = when (currentBackend()) {
        SrBackend.FSR, SrBackend.XESS -> true
        else -> false
    }

    /**
     * 是否真有一家超分在跑（地形 mip bias 与界面用）。DLSS 档要再问 [DeepLearningSuperSampling.isEnabled]；
     * FSR / XeSS 档必须问能力面，否则「配了但运行库不在」会先把地形糊掉。
     */
    @JvmStatic
    fun isEnabled(): Boolean = when (val wanted = currentBackend()) {
        SrBackend.NONE -> false
        SrBackend.DLSS -> DeepLearningSuperSampling.isEnabled()
        // 非 NVIDIA 两档还要问一次「这家到底能不能跑」：配置里写着 FSR 但运行库不在时，超分
        // 一帧都不会发生，可地形 mip bias 会照糊。
        else -> cachedCaps(wanted.id)?.available == true
    }

    /** caps 的一秒缓存：[isEnabled] 在地形路径上每帧被问，而 querySrCaps 是一次跨 ABI 调用。 */
    private fun cachedCaps(backend: Int): MCDR_ABI.SrCaps? {
        val now = System.nanoTime()
        val cached = capsCache
        if (cached != null && cached.backend == backend && now - capsCacheNanos < CAPS_TTL_NANOS) {
            return cached
        }
        val fresh = MCDR_ABI.querySrCaps(backend)
        capsCache = fresh
        capsCacheNanos = now
        return fresh
    }

    /**
     * 一个后端的一行状态。内容全部来自 native 的 `note`：**不要在这里自己判断运行库在不在**（那是第二处真值）。
     * 会触发一次 LoadLibrary，所以只在设置页打开时调。
     */
    @JvmStatic
    fun statusLine(backend: Int): Component {
        val label = backendLabel(backend)
        val caps = MCDR_ABI.querySrCaps(backend)
            ?: return Component.translatable("dxr_mc.video.sr.noabi", label)
        return if (caps.available) {
            Component.translatable("dxr_mc.video.sr.available", label, caps.note)
        } else {
            Component.translatable("dxr_mc.video.sr.unavailable", label, caps.note)
        }
    }

    /** 运行中的一句话状态（native `mcdr_dx12SrQuery` 直出）；只在设置页打开时调。 */
    @JvmStatic
    fun runtimeLine(backend: Int): Component {
        if (backendId() != backend) {
            return Component.empty()
        }
        return try {
            Arena.ofConfined().use { arena ->
                val buffer = arena.allocate(384L)
                val written = MCDR_ABI.D3D12SrQuery(buffer, 384)
                if (written <= 0) {
                    Component.empty()
                } else {
                    Component.literal(buffer.getString(0, Charsets.UTF_8).trim())
                }
            }
        } catch (t: Throwable) {
            Component.empty()
        }
    }

    /** 该后端是否缺东西（运行库 / 驱动 / ABI）；界面据此标红。 */
    @JvmStatic
    fun isBackendMissing(backend: Int): Boolean = MCDR_ABI.querySrCaps(backend)?.available == false
    /** 后端 id → 显示名（**专有名词不翻译**：DLSS / FSR / XeSS 在各语言里都这么写）。 */
    @JvmStatic
    fun backendLabel(backend: Int): String = when (backend) {
        MCDR_ABI.SR_BACKEND_DLSS -> "DLSS"
        MCDR_ABI.SR_BACKEND_FSR -> "AMD FidelityFX"
        MCDR_ABI.SR_BACKEND_XESS -> "Intel XeSS"
        else -> "Super Resolution"
    }

    /** 后端自己报的可用性/原因；DLL 没有该导出时返回 null。 */
    @JvmStatic
    fun caps(): MCDR_ABI.SrCaps? = MCDR_ABI.querySrCaps(backendId())
    // 两个厂商开关共用一个当前后端（互斥）；DLSS 的开关在 NVIDIA 段，不参与。

    /** `options.txt` 键：选中的后端（[SrBackend] 的枚举名）。 */
    const val OPTIONS_KEY_BACKEND: String = "dxrMcSrBackend"

    /** `options.txt` 键：锐度百分比（0..100，打包进载荷时除以 100）。 */
    const val OPTIONS_KEY_SHARPNESS: String = "dxrMcSrSharpness"
    /** 半透明遮罩开关；未设置时跟随 TOML `[sr] mask`。 */
    const val OPTIONS_KEY_MASK: String = "dxrMcSrMask"

    /** 当前后端；`null` = 还没初始化（首次访问从 `[sr] backend` 取初始值）。 */
    @Volatile
    private var backend: SrBackend? = null

    /** 正在同步两个开关时置位，避免 `OptionInstance.set` 的回调打回来。 */
    private var syncingOptions = false

    /** null = 未被 options.txt / 界面覆盖过，此时跟随 TOML `[sr] mask`。 */
    @Volatile
    private var maskOverride: Boolean? = null

    private val MASK_OPTION: OptionInstance<Boolean> = OptionInstance.createBoolean(
        "options.sr.mask",
        { on -> Tooltip.create(Component.translatable("options.sr.mask.tooltip")) },
        D3D12Settings.srMask,
    ) { on -> maskOverride = on }

    private val FSR_OPTION: OptionInstance<Boolean> = OptionInstance.createBoolean(
        "options.sr.fsr",
        { Tooltip.create(Component.translatable("options.sr.fsr.tooltip")) },
        false,
        { on -> requestBackend(if (on) SrBackend.FSR else SrBackend.NONE) },
    )

    private val XESS_OPTION: OptionInstance<Boolean> = OptionInstance.createBoolean(
        "options.sr.xess",
        { Tooltip.create(Component.translatable("options.sr.xess.tooltip")) },
        false,
        { on -> requestBackend(if (on) SrBackend.XESS else SrBackend.NONE) },
    )

    private val SHARPNESS_OPTION: OptionInstance<Int> = OptionInstance(
        "options.sr.sharpness",
        { Tooltip.create(Component.translatable("options.sr.sharpness.tooltip")) },
        { _, value -> Component.literal("$value%") },
        OptionInstance.IntRange(0, 100),
        (D3D12Settings.srSharpness * 100.0f).roundToInt().coerceIn(0, 100),
        { value -> IllusionLogger.debugMessage("sharpness: ${value}%") },
    )

    /** `options.txt` 键：FSR 的质量档（[QUALITY_NAMES] 下标）。 */
    const val QUALITY_OPTIONS_KEY_FSR: String = "dxrMcSrFsrQuality"

    /** `options.txt` 键：XeSS 的质量档（[QUALITY_NAMES] 下标）。 */
    const val QUALITY_OPTIONS_KEY_XESS: String = "dxrMcSrXessQuality"

    /** 与 DLSS `QUALITY_NAMES` 同序同名；下标 4 = 原生分辨率（不降比例）。 */
    private val QUALITY_NAMES: List<String> =
        listOf("ultra_performance", "performance", "balanced", "quality", "native_aa")

    /** 「原生分辨率」那一档的下标；选中它就不替用户降渲染比例。 */
    private const val NATIVE_AA_TIER: Int = 4

    /** 质量档 -> 渲染比例（前四档与 DLSS `percentForTier` 一致）。 */
    private fun percentForQuality(tier: Int): Int = when {
        // 极致性能档必须 34 不能 33：NGX 要求 render >= target/3，33% 会报 Dynamic scaling error 并交回空纹理（世界全黑）。
        tier <= 0 -> 34
        tier == 1 -> 50
        tier == 2 -> 59
        tier == 3 -> 67
        else -> 100
    }

    /** 循环按钮的值域（照 DLSS `QualityValues` 的形状；`CycleableValueSet` 才会画成循环按钮）。 */
    private object QualityValues : OptionInstance.CycleableValueSet<Int> {
        override fun validateValue(value: Int): Optional<Int> =
            if (value in QUALITY_NAMES.indices) Optional.of(value) else Optional.empty()

        override fun valueListSupplier(): CycleButton.ValueListSupplier<Int> =
            CycleButton.ValueListSupplier.create(QUALITY_NAMES.indices.toList())

        override fun codec(): Codec<Int> = Codec.intRange(0, QUALITY_NAMES.lastIndex)
    }

    private val FSR_QUALITY_OPTION: OptionInstance<Int> = OptionInstance(
        "options.sr.fsr.quality",
        { Tooltip.create(Component.translatable("options.sr.fsr.quality.tooltip")) },
        { _, value -> Component.translatable("options.sr.quality.${QUALITY_NAMES[value.coerceIn(0, QUALITY_NAMES.lastIndex)]}") },
        QualityValues,
        2,
        { value -> applyQuality(SrBackend.FSR, value) },
    )

    private val XESS_QUALITY_OPTION: OptionInstance<Int> = OptionInstance(
        "options.sr.xess.quality",
        { Tooltip.create(Component.translatable("options.sr.xess.quality.tooltip")) },
        { _, value -> Component.translatable("options.sr.quality.${QUALITY_NAMES[value.coerceIn(0, QUALITY_NAMES.lastIndex)]}") },
        QualityValues,
        2,
        { value -> applyQuality(SrBackend.XESS, value) },
    )

    /** FSR 的质量档那一行（与 DLSS 的质量档同一套语义：拨它就会改渲染比例）。 */
    @JvmStatic
    fun fsrQualityOption(): OptionInstance<Int> = FSR_QUALITY_OPTION

    /** XeSS 的质量档那一行。 */
    @JvmStatic
    fun xessQualityOption(): OptionInstance<Int> = XESS_QUALITY_OPTION

    /** 某个后端当前选中的质量档下标（DLSS 走它自己的 `quality()`）。 */
    private fun qualityOf(target: SrBackend): Int = when (target) {
        SrBackend.FSR -> FSR_QUALITY_OPTION.get().coerceIn(0, QUALITY_NAMES.lastIndex)
        SrBackend.XESS -> XESS_QUALITY_OPTION.get().coerceIn(0, QUALITY_NAMES.lastIndex)
        else -> 2
    }

    /**
     * 拨档位时把渲染比例推到该档标称值；只在该后端**正在跑**时动手（否则会改掉别家的比例）。
     * 走 [D3D12RenderScale.setPercentAtFrameBoundary]：帧中间改会让 copyDepthFrom 抛尺寸不匹配。
     */
    private fun applyQuality(target: SrBackend, tier: Int) {
        if (currentBackend() != target) {
            return
        }
        val percent = percentForQuality(tier)
        D3D12RenderScale.setPercentAtFrameBoundary(percent)
        Illusion.LOGGER.info(
            "quality = {} (render scale {}%)",
            QUALITY_NAMES.getOrElse(tier) { "quality" }, percent,
        )
    }

    /** `key:value` 里取质量档：档名与下标都接受（手改 `options.txt` 时两种都可能）。 */
    private fun parseTier(line: String, key: String): Int? {
        val raw = line.substring(key.length + 1).trim().trim('"').lowercase(Locale.ROOT)
        val index = QUALITY_NAMES.indexOf(raw)
        val value = if (index >= 0) index else raw.toIntOrNull()
        return value?.takeIf { it in QUALITY_NAMES.indices }
    }


    @JvmStatic
    fun fsrOption(): OptionInstance<Boolean> = FSR_OPTION

    @JvmStatic
    fun xessOption(): OptionInstance<Boolean> = XESS_OPTION

    @JvmStatic
    fun sharpnessOption(): OptionInstance<Int> = SHARPNESS_OPTION
    @JvmStatic
    fun maskOption(): OptionInstance<Boolean> = MASK_OPTION

    /** 界面 / options.txt 的覆盖优先，没覆盖就跟随 TOML。 */
    @JvmStatic
    fun mask(): Boolean = maskOverride ?: D3D12Settings.srMask

    /** 当前后端是否吃遮罩；DLSS 的 `takesMasks == 0`，设置页据此置灰。 */
    @JvmStatic
    fun maskSupported(): Boolean = (cachedCaps(backendId())?.takesMasks ?: 0) != 0

    /**
     * 启动期配置自检：只读配置、不碰设备，把「档位与渲染比例不匹配」这类会变成黑屏的组合提前说出来。
     */
    @JvmStatic
    fun validateAtStartup() {
        // 只能在客户端建好之后调（模组构造期 Minecraft.getInstance() 为 null）。
        val wanted = currentBackend()
        if (wanted != SrBackend.DLSS) {
            return
        }
        val tier = DeepLearningSuperSampling.quality()
        val needed = percentForQuality(tier)
        val current = D3D12RenderScale.percent()
        if (current < needed) {
            Illusion.LOGGER.error(
                "DLSS tier {} needs a render scale of at least {}% but the saved value is {}% (entering a world will raise it automatically)",
                QUALITY_NAMES[tier.coerceIn(0, QUALITY_NAMES.lastIndex)], needed, current)
        }
    }

    /** 当前锐度（0..1），打包载荷时用。 */
    @JvmStatic
    fun sharpness(): Float = SHARPNESS_OPTION.get().coerceIn(0, 100) / 100.0f

    /** 当前后端（界面与日志用它判断"谁在跑"）。 */
    @JvmStatic
    fun backend(): SrBackend = currentBackend()

    /**
     * DLSS 开关拨动时认领 / 归还「当前后端」：后端 = NONE 而 DLSS 勾上会让 [isEnabled] 恒 false、那行又置灰（死锁）。
     */
    @JvmStatic
    fun onDlssToggle(enabled: Boolean) {
        if (enabled) {
            if (currentBackend() != SrBackend.DLSS) {
                requestBackend(SrBackend.DLSS)
            }
        } else if (currentBackend() == SrBackend.DLSS) {
            requestBackend(SrBackend.NONE)
        }
    }

    private fun currentBackend(): SrBackend {
        backend?.let { return it }
        // 首次访问取 `[sr] backend`；options.txt 的 [load] 会覆盖它。
        val initial = D3D12Settings.srBackend
        backend = initial
        syncOptions(initial)
        pushBackendToNative(initial)
        return initial
    }

    private fun syncOptions(selected: SrBackend) {
        // `OptionInstance.set` 会问 `Minecraft.getInstance().isRunning()`，模组构造期那个单例是 null。
        syncingOptions = true
        try {
            FSR_OPTION.set(selected == SrBackend.FSR)
            XESS_OPTION.set(selected == SrBackend.XESS)
        } finally {
            syncingOptions = false
        }
    }

    /**
     * 把「用户选的是哪一家」推给 native：帧生成的可用性判据读它。推的是**选中**的后端，不是正在跑的那个。
     */
    private fun pushBackendToNative(selected: SrBackend) {
        try {
            MCDR_ABI.D3D12SrPick(selected.id)
        } catch (t: Throwable) {
            Illusion.LOGGER.debug("could not push the SR backend {} to native: {}", selected, t.toString())
        }
    }

    /**
     * 切后端。**不调 `mcdr_dx12SrDestroy`**：那一刻可能还有打开的命令列表；只清 [createdFor] / [lastKey]，
     * 由 native 的 `ensureProvider` 放掉旧 provider（它自己保证 GPU 已追平）。
     */
    private fun requestBackend(requested: SrBackend) {
        if (syncingOptions) {
            return
        }
        val previous = currentBackend()
        if (requested == previous) {
            return
        }
        if (requested == SrBackend.FSR || requested == SrBackend.XESS) {
            // DLAA 把比例锁在 100%，那里没有可放大的东西：明确拒绝并写清原因。
            if (D3D12RenderScale.isLockedByDlaa()) {
                Illusion.LOGGER.warn(
                    "cannot switch to {} while DLAA is active; turn DLAA off first",
                    requested,
                )
                syncOptions(previous)
                return
            }
            // 勾上这一家 = 按它自己的质量档出图：不对齐会让「行的显示值」与实际比例不符（如从 DLSS 的 58% 切来）。
            val tier = qualityOf(requested)
            val nominal = percentForQuality(tier)
            if (tier != NATIVE_AA_TIER && D3D12RenderScale.percent() != nominal) {
                Illusion.LOGGER.info(
                    "render scale {}% -> {}% to match the {} quality tier",
                    D3D12RenderScale.percent(), nominal, QUALITY_NAMES[tier],
                )
                D3D12RenderScale.setPercentAtFrameBoundary(nominal)
            }
        }
        backend = requested
        pushBackendToNative(requested)
        syncOptions(requested)
        createdFor = ""
        lastKey = ""
        lastState = ""
        Illusion.LOGGER.info("backend: {} -> {}", previous, requested)
    }

    /** `options.txt` 里的后端名 → [SrBackend]；认不出的按 NONE，不要静默退回 DLSS。 */
    private fun backendFromName(name: String): SrBackend =
        SrBackend.entries.firstOrNull { it.name == name } ?: SrBackend.NONE

    /** 与 VSR / 抗锯齿同一条管道（`OptionsRenderOptionsMixin` 的 `Options.load` 尾部）。 */
    @JvmStatic
    fun load(file: File) {
        if (!file.isFile) {
            return
        }
        try {
            for (line in file.readLines(StandardCharsets.UTF_8)) {
                if (line.startsWith("$OPTIONS_KEY_BACKEND:")) {
                    val name = line.substring(OPTIONS_KEY_BACKEND.length + 1).trim().trim('"')
                    val loaded = backendFromName(name)
                    backend = loaded
                    syncOptions(loaded)
                    pushBackendToNative(loaded)
                } else if (line.startsWith("$OPTIONS_KEY_SHARPNESS:")) {
                    val text = line.substring(OPTIONS_KEY_SHARPNESS.length + 1).trim().trim('"')
                    text.toIntOrNull()?.let { SHARPNESS_OPTION.set(it.coerceIn(0, 100)) }
                } else if (line.startsWith("$QUALITY_OPTIONS_KEY_FSR:")) {
                    parseTier(line, QUALITY_OPTIONS_KEY_FSR)?.let { FSR_QUALITY_OPTION.set(it) }
                } else if (line.startsWith("$OPTIONS_KEY_MASK:")) {
                val text = line.substring(OPTIONS_KEY_MASK.length + 1).trim().trim('"')
                text.toBooleanStrictOrNull()?.let { MASK_OPTION.set(it) }
            } else if (line.startsWith("$QUALITY_OPTIONS_KEY_XESS:")) {
                    parseTier(line, QUALITY_OPTIONS_KEY_XESS)?.let { XESS_QUALITY_OPTION.set(it) }
                }
            }
        } catch (t: Throwable) {
            Illusion.LOGGER.warn("could not read SR options from {}", file, t)
            return
        }
        createdFor = ""
        lastKey = ""
        lastState = ""
        // options.txt 里 backend = FSR/XeSS 而 dxrMcDlss 仍为 true 是升级用户最常见的组合：必须在这里让位，
        // 否则 DLSS 先跑起来改了比例再被中途关掉，速度通道此后一直没纹理。NONE 不算「另一家」。
        val wanted = currentBackend()
        if (wanted == SrBackend.NONE && DeepLearningSuperSampling.isEnabled()) {
            requestBackend(SrBackend.DLSS)
        } else if (wanted != SrBackend.DLSS) {
            DeepLearningSuperSampling.standDown(
                "the SR backend in options.txt is ${wanted.name}, so DLSS must not also present")
        }
    }

    /** 写回 `options.txt`（vanilla 每次保存都整份重写，所以要在 `Options.save` 尾部补两行）。 */
    @JvmStatic
    fun save(file: File) {
        if (!file.isFile) {
            return
        }
        try {
            val eol = System.lineSeparator()
            val kept = file.readLines(StandardCharsets.UTF_8).filterNot {
                it.startsWith("$OPTIONS_KEY_BACKEND:") ||
                    it.startsWith("$OPTIONS_KEY_SHARPNESS:") ||
                    it.startsWith("$QUALITY_OPTIONS_KEY_FSR:") ||
                    it.startsWith("$QUALITY_OPTIONS_KEY_XESS:")
                || it.startsWith("$OPTIONS_KEY_MASK:")
            }
            val text = (
                kept + listOf(
                    "$OPTIONS_KEY_BACKEND:${currentBackend().name}",
                    "$OPTIONS_KEY_SHARPNESS:${SHARPNESS_OPTION.get()}",
                    "$QUALITY_OPTIONS_KEY_FSR:${FSR_QUALITY_OPTION.get()}",
                    "$QUALITY_OPTIONS_KEY_XESS:${XESS_QUALITY_OPTION.get()}",
                "$OPTIONS_KEY_MASK:${mask()}",
            )
                ).joinToString(eol) + eol
            file.writeText(text, StandardCharsets.UTF_8)
        } catch (t: Throwable) {
            Illusion.LOGGER.warn("could not write SR options to {}", file, t)
        }
    }

    /** 帧内挂点（`GameRendererSmaaWorldMixin`：SMAA 之后、`prepareForGui` 之前）。DLSS 原样委托。 */
    @JvmStatic
    fun applyIfEnabled() {
        when (backendId()) {
            MCDR_ABI.SR_BACKEND_NONE -> releaseIfCreated()
            MCDR_ABI.SR_BACKEND_DLSS -> {
                // 从 FSR / XeSS 切回 DLSS：先还原我们打开的数据面，让 DLSS 用自己的记账重新打开。
                if (forcedTemporal || forcedVelocity) {
                    releaseDataPlane()
                }
                DeepLearningSuperSampling.applyIfEnabled()
            }
            else -> {
                // 一家在写、另一家必须让位：只关闸门不够（`reconcileAntiAliasing` 每帧按 options.dlss 对齐）。
                // 让位排到帧边界：它会把渲染比例从 DLSS 档切回普通档，帧中间改会打穿 `copyDepthFrom`。
                if (DeepLearningSuperSampling.isDlaaActive()) {
                    return note(
                        "DLAA is engaged (anti-aliasing = DLAA); switch it off to let " +
                            "${backendName(backendId())} run")
                }
                if (!standDownScheduled && DeepLearningSuperSampling.isEnabled()) {
                    standDownScheduled = true
                    val minecraft = try {
                        Minecraft.getInstance()
                    } catch (t: Throwable) {
                        null
                    }
                    minecraft?.schedule {
                        standDownScheduled = false
                        DeepLearningSuperSampling.standDown(
                            "the SR backend is ${backendName(backendId())}, so DLSS must not also present")
                    }
                }
                val now = System.nanoTime()
                if (now - lastEnterDiagNanos > VELOCITY_DIAG_NANOS) {
                    lastEnterDiagNanos = now
                    IllusionLogger.debugMessage(
                        "enter: backend=${backendName(backendId())} " +
                            "created=${createdFor.ifEmpty { "no" }} " +
                            "velocity=${D3D12Velocity.isActive()} " +
                            "temporal=${D3D12Temporal.isActive()}",
                    )
                }
                try {
                    evaluateOther()
                } catch (t: Throwable) {
                    note("evaluateOther threw: $t")
                }
            }
        }
    }

    /** 放掉 native 上下文（`mcdr_dx12SrDestroy` 幂等）并还回数据面。 */
    @JvmStatic
    fun releaseIfCreated() {
        if (createdFor.isNotEmpty()) {
            createdFor = ""
            lastKey = ""
            MCDR_ABI.D3D12SrDestroy()
            IllusionLogger.debugMessage("released (backend is none)")
        }
        releaseDataPlane()
    }

    /** 只还原**我们**打开的那两个字段；否则 backend=none 之后 jitter / 速度通道还在跑（画面还在抖）。 */
    private fun releaseDataPlane() {
        if (forcedTemporal) {
            forcedTemporal = false
            D3D12Temporal.enabled = D3D12Settings.temporalChain
        }
        if (forcedVelocity) {
            forcedVelocity = false
            D3D12Velocity.enabled = D3D12Settings.temporalChain
        }
        lastVelocityRetryNanos = Long.MIN_VALUE
    }

    /** 幂等：补齐 jitter + 速度通道（构建期默认关闭）。只在这一帧真的会升采样之后调。 */
    private fun ensureDataPlane() {
        if (!D3D12Temporal.enabled) {
            D3D12Temporal.enabled = true
            forcedTemporal = true
            Illusion.LOGGER.info(
                "{} needs the temporal data plane: sub-pixel projection jitter "
                    + "(dxrmc.temporal) is switched on while it is selected", backendName(backendId()))
        }
        if (!D3D12Velocity.enabled) {
            D3D12Velocity.enabled = true
            forcedVelocity = true
            Illusion.LOGGER.info(
                "{} needs motion vectors: the velocity pass "
                    + "(dxrmc.temporal.velocity) is switched on while it is selected",
                backendName(backendId()))
        }
        if (!D3D12Velocity.isLatched()) {
            return
        }
        // 速度通道的自我关闭是一票否决：必须清闩锁，且限速一秒一次，别每帧重建资源。
        val now = System.nanoTime()
        if (lastVelocityRetryNanos != Long.MIN_VALUE
            && now - lastVelocityRetryNanos < VELOCITY_RETRY_NANOS) {
            return
        }
        lastVelocityRetryNanos = now
        D3D12Velocity.forceEnable()
    }

    // FSR / XeSS 的每帧路径

    private fun evaluateOther() {
        // 启动自检放在这里：第一次真要跑超分的那一帧，客户端与配置都已就位。
        if (!startupChecked) {
            startupChecked = true
            try {
                validateAtStartup()
            } catch (t: Throwable) {
                Illusion.LOGGER.warn("the start-up configuration check failed: {}", t.toString())
            }
        }
        val backend = backendId()
        val minecraft = Minecraft.getInstance()
        // 标题界面 / 加载界面：没有世界层，谈不上"世界层升采样"。
        if (minecraft.level == null) {
            return
        }
        val caps = MCDR_ABI.querySrCaps(backend)
        if (caps == null) {
            return note("the loaded MCDR.dll has no mcdr_dx12SrQueryCaps (the DLL predates this abstraction)")
        }
        if (!caps.available) {
            return note("backend ${backendName(backend)} is not available: ${caps.note}")
        }
        val renderTarget = minecraft.gameRenderer.mainRenderTarget()
        val color = (renderTarget.colorTextureView as? D3D12GpuTextureView)?.texture() as? D3D12GpuTexture
        if (color == null) {
            return note("the world layer is not a D3D12 texture")
        }
        val depth = renderTarget.depthTexture as? D3D12GpuTexture
        if (depth == null) {
            return note("the main render target has no D3D12 depth texture")
        }
        // 世界切换 / 维度加载会整批重建主目标：Java 对象可能还在但 native 资源已释放，塞进 dispatch 会在
        // native 里撞野指针。宁可这一帧不升采样。
        if (color.isClosed() || depth.isClosed()) {
            return note("the world layer's color/depth was released this frame (world transition)")
        }
        // 显示尺寸从 GUI 目标来：拆分启用时它就是窗口尺寸，最终界面也画进它。
        val guiTarget = D3D12GUISplit.targetForFrame() ?: D3D12GUISplit.ensureTargetForDlss()
        val guiColor = (guiTarget?.colorTextureView as? D3D12GpuTextureView)?.texture() as? D3D12GpuTexture
        if (guiColor == null) {
            return note("no display-sized target: FSR/XeSS need a separate input and output texture")
        }
        if (guiColor.isClosed()) {
            return note("the display target was released this frame (world transition)")
        }
        val encoder = D3D12Device.sharedCommandEncoder()
        if (encoder == null) {
            return note("no shared command encoder")
        }
        val renderWidth = color.getWidth(0)
        val renderHeight = color.getHeight(0)
        val displayWidth = guiColor.getWidth(0)
        val displayHeight = guiColor.getHeight(0)
        // 低于能力下限（320x180）后两端都会被 SDK 拒绝：抬一次比例（只做一次，否则会跟故意拖小的玩家抢）。
        // SrCaps 没透出 min 尺寸，这里与 native 写死的值保持一致。
        val minRenderWidth = MIN_RENDER_WIDTH
        val minRenderHeight = MIN_RENDER_HEIGHT
        if (renderWidth < minRenderWidth || renderHeight < minRenderHeight) {
            if (!autoScaleAdjusted) {
                val needed = maxOf(
                    ceil(minRenderWidth * 100.0 / renderWidth).toInt(),
                    ceil(minRenderHeight * 100.0 / renderHeight).toInt(),
                ).coerceIn(D3D12RenderScale.minimumPercent(), 100)
                autoScaleAdjusted = true
                D3D12RenderScale.setPercentAtFrameBoundary(needed)
                return note("render ${renderWidth}x$renderHeight is below the ${backendName(backend)} minimum of ${minRenderWidth}x$minRenderHeight; raising the render scale to $needed%")
            }
            return note("render ${renderWidth}x$renderHeight is below the ${backendName(backend)} minimum of ${minRenderWidth}x$minRenderHeight; raise the render scale")
        }
        if (displayWidth <= 0 || displayHeight <= 0) {
            return note("degenerate sizes: render ${renderWidth}x$renderHeight, "
                + "display ${displayWidth}x$displayHeight")
        }
        // **输出必须是带 `USAGE_STORAGE` 的纹理**：native 的 textureFlags() 只在 `usage & 32` 时加
        // ALLOW_UNORDERED_ACCESS；拿 GUI 目标当 pColorOutput 会让 UAV 屏障在 Close() 报 E_INVALIDARG。
        val output = DLSSOutput.textureForFrame(displayWidth, displayHeight)
        if (output == null) {
            return note("could not create the display-sized UAV output texture")
        }
        if (renderWidth == displayWidth && renderHeight == displayHeight) {
            // 100% 没有可放大的内容（options.txt 里写死 backend + 100% 会撞到这里）：降一次档到滑条吸附值 75。
            if (!autoScaleAdjusted && D3D12RenderScale.percent() >= 100
                && !D3D12RenderScale.isLockedByDlaa()
            ) {
                autoScaleAdjusted = true
                D3D12RenderScale.setPercentAtFrameBoundary(DEFAULT_RENDER_PERCENT)
                return note("render scale was 100%; lowering it to ${DEFAULT_RENDER_PERCENT}% "
                    + "so ${backendName(backend)} has something to upscale")
            }
            return note("render size equals display size (${renderWidth}x$renderHeight); "
                + "set the render scale below 100% so there is a real upscale to do")
        }

        // 相位长度交给唯一的抖动源：DLSS 接受我们的序列，FSR / XeSS 用自己相位表的长度。
        D3D12Temporal.adoptPhaseCount(backend,
            MCDR_ABI.D3D12SrJitterPhaseCount(backend, renderWidth, displayWidth))

        // 数据面：jitter / 速度通道构建期默认关闭；放在「这一帧真会升采样」之后，避免没有消费者却在抖。
        ensureDataPlane()

        // 与 DLSS 同一个挂点、同一份兜底（本帧已录过时会自己跳过）；首帧没有历史必然为空。
        encoder.recordVelocityPass()
        val motionVectors = D3D12Velocity.currentHandle()
        if (motionVectors == 0L) {
            // 每帧都到这里时，一行诊断比「什么都没跑」有用（按秒限流）。
            val now = System.nanoTime()
            if (now - lastVelocityDiagNanos > VELOCITY_DIAG_NANOS) {
                lastVelocityDiagNanos = now
            }
            return note("no motion-vector texture (the velocity pass has no history yet)")
        }

        val createKey = "$backend:$renderWidth:$renderHeight:$displayWidth:$displayHeight"
        if (createdFor != createKey) {
            val created = MCDR_ABI.D3D12SrCreate(encoder.nativeHandle(), backend,
                renderWidth, renderHeight, displayWidth, displayHeight)
            when (created) {
                1 -> createdFor = createKey
                0 -> {
                    createdFor = ""
                    return note("mcdr_dx12SrCreate refused backend ${backendName(backend)}")
                }
                Int.MIN_VALUE -> return note("the loaded DLL has no mcdr_dx12SrCreate")
                else -> return note("mcdr_dx12SrCreate returned $created")
            }
        }

        val jitter = D3D12Temporal.jitter()
        val frameKey = "$createKey:${D3D12RenderScale.percent()}"
        val reset = frameKey != lastKey
        lastKey = frameKey

        val camera = minecraft.gameRenderer.gameRenderState().levelRenderState.cameraRenderState
        val farPlane = if (camera.depthFar > 0.0f) camera.depthFar else FALLBACK_FAR
        val fov = fovFromProjection(D3D12Temporal.currentProjection())
        val sharpness = sharpness()

        // 掩码产生器必须与 evaluate 同一条命令列表；关掉配置时传 0（= 没有这张纹理）。
        val mask = if (mask()) D3D12SrMask.produce(encoder, renderWidth, renderHeight) else 0L
        if (mask() && mask == 0L) {
            note("the super-resolution mask producer did not run: " + D3D12SrMask.note())
        }

        val gpuTracked = writeGpuFront(encoder)
        val evalStarted = System.nanoTime()
        val result = try {
            Arena.ofConfined().use { arena ->
                val desc = arena.allocate(MCDRLayout.SR_DISPATCH)
                desc.set(I32, OFF_RENDER_W, renderWidth)
                desc.set(I32, OFF_RENDER_H, renderHeight)
                desc.set(I32, OFF_DISPLAY_W, displayWidth)
                desc.set(I32, OFF_DISPLAY_H, displayHeight)
                desc.set(F32, OFF_JITTER_X, jitter[0].toFloat())
                desc.set(F32, OFF_JITTER_Y, jitter[1].toFloat() * JITTER_SIGN_Y)
                desc.set(F32, OFF_MV_SCALE_X, MOTION_VECTOR_SCALE)
                desc.set(F32, OFF_MV_SCALE_Y, MOTION_VECTOR_SCALE)
                desc.set(F32, OFF_PRE_EXPOSURE, PRE_EXPOSURE)
                desc.set(F32, OFF_FRAME_MS, frameTimeMs())
                desc.set(F32, OFF_NEAR, NEAR_PLANE)
                desc.set(F32, OFF_FAR, farPlane)
                desc.set(F32, OFF_FOV, fov)
                desc.set(F32, OFF_SHARPNESS, sharpness)
                desc.set(I32, OFF_RESET, if (reset) 1 else 0)
                // 一律按 SDR 处理：native 据此决定要不要置 FSR 的 HIGH_DYNAMIC_RANGE。
                desc.set(I32, OFF_HDR, 0)
                desc.set(I32, OFF_BACKEND, backend)
                desc.set(I32, OFF_FLAGS, 0)
                desc.set(PTR, OFF_COLOR_IN, MemorySegment.ofAddress(color.handle()))
                desc.set(PTR, OFF_COLOR_OUT, MemorySegment.ofAddress(output.handle()))
                desc.set(PTR, OFF_DEPTH, MemorySegment.ofAddress(depth.handle()))
                desc.set(PTR, OFF_MV, MemorySegment.ofAddress(motionVectors))
                // 掩码在同一条列表上先画好（0 = 没做成，native 跳过）；曝光传 0，native 用 preExposure。
                desc.set(PTR, OFF_MASK, MemorySegment.ofAddress(mask))
                desc.set(PTR, OFF_EXPOSURE, MemorySegment.ofAddress(0L))
                desc.set(I32, OFF_DEPTH_FORMAT, depth.format.ordinal)
                desc.set(F32, OFF_ASPECT, displayWidth.toFloat() / displayHeight.toFloat())
                desc.set(F32, OFF_METERS, 1.0f)
                // 帧生成常量不依赖 SR 后端：每次真出帧都推一份，jitter 用与 dispatch 同一份值。
                FrameGenerationConstants.push(jitter[0].toFloat(), jitter[1].toFloat() * JITTER_SIGN_Y,
                    reset)
                MCDR_ABI.D3D12SrEvaluate(encoder.nativeHandle(), desc)
            }
        } catch (t: Throwable) {
            return note("packing/evaluating the SR payload threw: $t")
        } finally {
            writeGpuBack(encoder, gpuTracked)
        }

        val evalNanos = System.nanoTime() - evalStarted
        when (result) {
            1 -> {
                // hudless 下这张 UAV 就是呈现源（不能搬进 GUI 目标，那是 UI 层）；否则搬进 GUI 目标，
                // 本帧的 prepareForGui 据此跳过世界层 blit。
                val blitStarted = System.nanoTime()
                if (D3D12GUISplit.isHudlessArmed()) {
                    D3D12GUISplit.markWorldLayer(output)
                    recordCost(evalNanos, System.nanoTime() - blitStarted)
                    lastState = ""
                } else {
                    val blitted = encoder.recordTextureBlit(output, guiColor)
                    recordCost(evalNanos, System.nanoTime() - blitStarted)
                    if (blitted) {
                        D3D12GUISplit.markWorldLayerDone()
                        lastState = ""
                    } else {
                        note("the upscaled image could not be blitted into the GUI target")
                    }
                }
            }
            0 -> note("native skipped this frame (backend not ready)")
            -1 -> note("native rejected the payload (-1: invalid arguments)")
            Int.MIN_VALUE -> note("the loaded DLL has no mcdr_dx12SrEvaluate")
            else -> note("mcdr_dx12SrEvaluate returned an unexpected value $result")
        }
    }

    /** Create the timestamp pool lazily; if that fails, GPU timing stays off forever. */
    private fun ensureGpuPool(): Boolean {
        if (gpuPool != 0L) {
            return true
        }
        if (gpuPoolWarned) {
            return false
        }
        val pool = try {
            MCDR_ABI.D3D12CreateQueryPool(GPU_SAMPLES * 2)
        } catch (t: Throwable) {
            gpuPoolWarned = true
            Illusion.LOGGER.warn("GPU timing disabled: creating the query pool threw {}", t.toString())
            return false
        }
        if (pool == 0L) {
            gpuPoolWarned = true
            Illusion.LOGGER.warn("GPU timing disabled: no mcdr_dx12CreateQueryPool")
            return false
        }
        gpuPool = pool
        return true
    }

    /** Write the front timestamp; false means this frame is not sampled. */
    private fun writeGpuFront(encoder: D3D12CommandEncoderBackend): Boolean {
        if (gpuFrame >= GPU_SAMPLES || !ensureGpuPool()) {
            return false
        }
        return try {
            MCDR_ABI.D3D12WriteTimestamp(encoder.nativeHandle(), gpuPool, gpuFrame * 2)
            true
        } catch (t: Throwable) {
            gpuPoolWarned = true
            Illusion.LOGGER.warn("GPU timing disabled: writing a timestamp threw {}", t.toString())
            false
        }
    }

    /** Write the back timestamp; a full batch is read back and logged. */
    private fun writeGpuBack(encoder: D3D12CommandEncoderBackend, tracked: Boolean) {
        if (!tracked) {
            return
        }
        try {
            MCDR_ABI.D3D12WriteTimestamp(encoder.nativeHandle(), gpuPool, gpuFrame * 2 + 1)
        } catch (t: Throwable) {
            gpuPoolWarned = true
            Illusion.LOGGER.warn("GPU timing disabled: writing a timestamp threw {}", t.toString())
            return
        }
        gpuFrame++
        if (gpuFrame >= GPU_SAMPLES) {
            flushGpuSamples()
            gpuFrame = 0
        }
    }

    /** 读回整池、丢掉不可信样本，打 GPU 侧 p50 / p95：宁可丢样本也不报假数据（前值 0 / back<=front / >100ms）。 */
    private fun flushGpuSamples() {
        if (gpuPool == 0L) {
            return
        }
        val frequency = try {
            MCDR_ABI.D3D12GetTimestampFrequency()
        } catch (t: Throwable) {
            0L
        }
        if (frequency <= 0L) {
            return
        }
        val values = LongArray(GPU_SAMPLES * 2)
        try {
            MCDR_ABI.D3D12ReadQueryValues(gpuPool, 0, GPU_SAMPLES * 2, values)
        } catch (t: Throwable) {
            gpuPoolWarned = true
            Illusion.LOGGER.warn("GPU timing disabled: reading the query pool threw {}", t.toString())
            return
        }
        var accepted = 0
        val limit = frequency / 10
        for (i in 0 until GPU_SAMPLES) {
            val front = values[i * 2]
            val back = values[i * 2 + 1]
            if (front !in 1..<back) {
                continue
            }
            val ticks = back - front
            if (ticks > limit) {
                continue
            }
            gpuSamples[accepted++] = ticks
        }
        if (accepted == 0) {
            return
        }
        val sorted = gpuSamples.copyOf(accepted).also { it.sort() }
        Illusion.LOGGER.debug(
            "gpu cost over {} frames: backend={} p50={}us p95={}us (max {}us, {} samples rejected)",
            accepted,
            backendLabel(backendId()),
            sorted[accepted / 2] * 1_000_000L / frequency,
            sorted[(accepted * 95) / 100] * 1_000_000L / frequency,
            sorted[accepted - 1] * 1_000_000L / frequency,
            GPU_SAMPLES - accepted,
        )
    }

    /** 记一帧的**渲染线程墙上时间**（跨 ABI + 打包），攒够 [COST_LOG_EVERY] 帧打一行；不是 GPU 开销。 */
    private fun recordCost(evalNanos: Long, blitNanos: Long) {
        evalSamples[sampleCursor % COST_SAMPLES] = evalNanos
        blitSamples[sampleCursor % COST_SAMPLES] = blitNanos
        sampleCursor++
        if (sampleCursor % COST_LOG_EVERY != 0) {
            return
        }
        val frames = minOf(sampleCursor, COST_SAMPLES)
        val eval = evalSamples.copyOf(frames).also { it.sort() }
        val blit = blitSamples.copyOf(frames).also { it.sort() }
        Illusion.LOGGER.debug(
            "record cost over {} frames: evaluate p50={}us p95={}us, blit p50={}us p95={}us (CPU only)",
            frames,
            eval[frames / 2] / 1_000,
            eval[(frames * 95) / 100] / 1_000,
            blit[frames / 2] / 1_000,
            blit[(frames * 95) / 100] / 1_000,
        )
    }

    /** 一帧多少毫秒。不用 `frameTimeNs`（26.3 里是私有字段），本地量即可。 */
    private fun frameTimeMs(): Float {
        val now = System.nanoTime()
        val previous = lastFrameNanos
        lastFrameNanos = now
        if (previous == 0L) {
            return 16.7f
        }
        val ms = (now - previous) / 1_000_000.0
        return ms.toFloat().coerceIn(1.0f, 200.0f)
    }

    /** 透视投影反算垂直 FOV（弧度）：m11 = 1/tan(fovY/2)，列主序在 index 5。 */
    private fun fovFromProjection(proj: FloatArray?): Float {
        if (proj != null && proj.size > 5) {
            val m11 = proj[5]
            if (m11.isFinite() && m11 > 1.0e-4f) {
                return 2.0f * atan(1.0f / m11)
            }
        }
        return Math.toRadians(Minecraft.getInstance().options.fov().get().toDouble()).toFloat()
    }

    private fun backendName(backend: Int): String = when (backend) {
        MCDR_ABI.SR_BACKEND_NONE -> "none"
        MCDR_ABI.SR_BACKEND_DLSS -> "dlss"
        MCDR_ABI.SR_BACKEND_FSR -> "fsr"
        MCDR_ABI.SR_BACKEND_XESS -> "xess"
        else -> "backend#$backend"
    }

    /** 跳过原因：只在变化时打一行，绝不每帧刷屏。 */
    private fun note(message: String) {
        if (lastState == message) {
            return
        }
        lastState = message
        Illusion.LOGGER.info("{} did not run this frame: {}",
            backendName(backendId()), String.format(Locale.ROOT, "%s", message))
    }
}
