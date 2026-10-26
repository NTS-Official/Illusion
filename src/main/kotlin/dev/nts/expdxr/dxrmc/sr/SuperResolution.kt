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
import dev.nts.expdxr.misc.D3D12Settings
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
import net.minecraft.network.chat.Component

/**
 * P282：超分抽象层的 JVM 门面：`[sr] backend` 选哪一家，就在这一层分流。
 *
 * **DLSS 分支原样委托给 [DeepLearningSuperSampling]**：那条路径有 2000 行状态机、P108/P112
 * 的交接约定和 Streamline 的打点，任何"顺手统一一下"的改动都会立刻变成实机回归。所以本类只做
 * 两件事：把配置翻成 native 的后端号、给 FSR / XeSS 打包 [MCDRLayout.SR_DISPATCH]。
 *
 * FSR / XeSS 的通路与 DLSS 完全不同：它们没有"feature 建一次、之后每帧 evaluate"之外的状态交接
 * （native 侧 `d3d12_fsr.cpp` / `d3d12_xess.cpp` 自己管上下文、自己发屏障），因此本类每帧只做
 * 四步：记速度通道、打包、`mcdr_dx12SrEvaluate`、成功就 `markWorldLayerDone()`。
 */
object SuperResolution {
    private const val PREFIX = "[SR]"

    /** 与 `Projection.setupPerspective(0.05F, ...)` 同源（`Camera.java:110`）。 */
    private const val NEAR_PLANE = 0.05f

    /** 载荷里的 `preExposure`：本工程没有曝光纹理，1.0 = 原样（P289 再谈）。 */
    private const val PRE_EXPOSURE = 1.0f

    /** 运动矢量已是像素空间（`D3D12Velocity` 写的是 `(uv - prevUv) * screenSize`），故为 1。 */
    private const val MOTION_VECTOR_SCALE = 1.0f

    /**
     * P211n 定下的上报约定：`(+Jx, -Jy)`。亚像素抖动只有一个源（`D3D12Temporal` 的 Halton(2,3)），
     * DLSS 侧实机 A/B 的结论是这套符号；FSR / XeSS 沿用同一份值，待真机复验（`sr_plan.md` A8/A9）。
     */
    private const val JITTER_SIGN_Y = -1.0f

    /** 兜底相机远平面：取不到 `CameraRenderState.depthFar` 时的值（与原版默认渲染距离同量级）。 */
    private const val FALLBACK_FAR = 512.0f

    private var lastState: String = ""

    /** 上一次成功的 (backend, 渲染尺寸, 显示尺寸)；变了就要求后端 reset 一次历史。 */
    private var lastKey: String = ""

    /** 已经为哪一组尺寸建过上下文。`mcdr_dx12SrCreate` 幂等，但没必要每帧付一次 ABI 往返。 */
    private var createdFor: String = ""

    private var lastFrameNanos: Long = 0L

    /** 渲染比例"100% 没活可干"的自愈只做一次（否则用户故意拖回 100% 时会跟他抢）。 */
    private var autoScaleAdjusted = false

    /** [cachedCaps] 的缓存与时间戳（地形路径每帧问一次，按秒缓存）。 */
    private var capsCache: MCDR_ABI.SrCaps? = null
    private var capsCacheNanos = 0L

    /**
     * P284b：录制一帧超分（含把载荷写进堆内存、跨 ABI 调用）与随后那次 blit 的**CPU 时间**。
     *
     * <p>这不是 GPU 时间：dispatch 是异步的，这里量的是渲染线程为这一帧花掉的墙上时间。
     * 要 GPU 时间得开 timestamp query（`mcdr_dx12WriteTimestamp` 已在，但需要成对的 query heap），
     * 那是另一条链（P284b 的下一半）。
     */
    private val evalSamples = LongArray(COST_SAMPLES)
    private val blitSamples = LongArray(COST_SAMPLES)
    private var sampleCursor = 0

    /** 让位动作是否已排到帧边界（避免每帧往主线程队列里塞一个 Runnable）。 */
    private var standDownScheduled = false

    /**
     * P284b GPU side: real super-resolution cost, measured with timestamp queries.
     *
     * <p>One TIMESTAMP before and after evaluate, then a single
     * [MCDR_ABI.D3D12ReadQueryValues] for the whole pool every [GPU_SAMPLES] frames. It must not be
     * read per frame: the native resolveQuery builds a command list and calls flushAndWait every time.
     */
    private var gpuPool = 0L
    private var gpuFrame = 0
    private var gpuPoolWarned = false
    private val gpuSamples = LongArray(GPU_SAMPLES)

    /** 数据面（jitter / 速度通道）是不是**我们**打开的 —— 关掉 SR 时要还原成配置原值。 */
    private var forcedTemporal = false
    private var forcedVelocity = false

    /** 速度通道闩锁的重试限速（P108e 的教训：一票否决的自我关闭只能靠外部清）。 */
    private var lastVelocityRetryNanos = Long.MIN_VALUE

    /** P282 诊断：上一次打"速度通道为什么没纹理"的时间戳（0 = 还没打过）。 */
    private var lastVelocityDiagNanos = 0L

    /** P282 诊断：上一次打"门面被调用"心跳的时间戳（0 = 还没打过）。 */
    private var lastEnterDiagNanos = 0L

    /** 那条诊断的最小间隔（1 秒）。 */
    private const val VELOCITY_DIAG_NANOS = 1_000_000_000L

    private const val VELOCITY_RETRY_NANOS = 1_000_000_000L

    /**
     * 切到 FSR / XeSS 时若渲染比例还在 100%，就降到这个值。
     *
     * <p>75 是**滑条实际能停住**的档位：非 DLSS 模式下取值域按 25% 步进吸附
     * （`D3D12RenderScale` 的 `PercentRange.snap`），写 67 会被吸附成 75，日志与生效值对不上。
     */
    private const val DEFAULT_RENDER_PERCENT = 75

    /** caps 缓存的存活时间（见 [cachedCaps]）。 */
    private const val CAPS_TTL_NANOS = 1_000_000_000L

    /** P284b：参与统计的帧数（环形缓冲长度）。 */
    private const val COST_SAMPLES = 128

    /** P284b：攒够这么多帧就打一行统计。 */
    private const val COST_LOG_EVERY = 120

    /** P284b GPU side: frames per timestamp batch (two queries per frame). */
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
     * 速度通道 / 相机矩阵的**生产者**仍住在 `DeepLearningSuperSampling.captureMotionVectors()` 里
     * （P76c / P108d 的历史落点：挂点、深度快照、MV 约定都在那），但 FSR / XeSS 一样需要这份数据。
     *
     * <p>那个挂点原先把闸门写成「DLSS 开着」，于是选了 FSR 却把 DLSS 关掉时，速度通道一帧都不跑 ——
     * 实测 `D3D12Velocity.debugState()` 会一直是 `recorded=-1 current=false previous=false`，
     * 门面只能每帧报 "no motion-vector texture"，一帧都升采样不了。这个谓词就是给那个闸门用的：
     * 「DLSS 关着、但另一家超分在跑」时它要继续工作。
     */
    @JvmStatic
    fun needsVelocityPass(): Boolean = when (currentBackend()) {
        SrBackend.FSR, SrBackend.XESS -> true
        else -> false
    }

    /**
     * 是否真的有一家超分在跑（地形 mip bias 与界面用它）。
     *
     * <p>DLSS 这一档不能只看后端号：`[sr] backend` 的默认值就是 DLSS，而 DLSS 自己的开关在 NVIDIA 段
     * 那一行。所以 DLSS 档要再问一次 [DeepLearningSuperSampling.isEnabled] —— 否则"选了 DLSS 却从没
     * 打开它"会被当成在超分，地形 mip bias 就先糊了（P220 的边界）。
     */
    @JvmStatic
    fun isEnabled(): Boolean = when (val wanted = currentBackend()) {
        SrBackend.NONE -> false
        SrBackend.DLSS -> DeepLearningSuperSampling.isEnabled()
        // 非 NVIDIA 两档还要问一次「这家到底能不能跑」：配置里写着 FSR 但运行库不在时，超分
        // 一帧都不会发生，可地形 mip bias 会照糊 —— P220 那条边界就破了。
        else -> cachedCaps(wanted.id)?.available == true
    }

    /**
     * caps 的一秒缓存。
     *
     * <p>[isEnabled] 在地形采样路径上**每帧**被问一次，而 [MCDR_ABI.querySrCaps] 是一次跨 ABI
     * 调用；结果一秒内不会变（运行库加载幂等、驱动也不会在帧中间换），所以按秒缓存。
     */
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
     * 一个后端的一行状态（给视频设置页的只读说明行用）：可用 / 不可用 + 原因。
     *
     * 数据源是 `mcdr_dx12SrQueryCaps` 的 `note`（native 侧如实写 "cannot load …"、
     * "not implemented yet" 等）。**不要在这里自己判断"运行库在不在"** —— 那会变成第二处真值，
     * 而 native 已经问过运行库了。
     *
     * 会触发一次 LoadLibrary 尝试（FSR / XeSS），native 侧首次之后有缓存；所以它只该在打开设置页时
     * 调，不要挂到每帧路径上。
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

    /**
     * 该后端现在是不是**缺东西**（运行库 / 驱动 / ABI 入口）。
     *
     * 只给界面上色用：缺东西时那行状态显示成红色 —— 灰色小字在一屏幕设置里太容易被
     * 当成装饰跳过，而「打了勾但根本跑不了」正是最需要被看见的一种状态。
     */
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
    // ------------------------------------------------------------------
    // 用户 m01923：把 SR 的开关接进「视频设置 → Illusion 设置」。
    // 两个厂商开关共用**一个**当前后端（[SrBackend]），所以点开一家会把另一家关掉；
    // DLSS 的开关留在 NVIDIA 段（它同时管渲染比例与挡位），不参与这套互斥。
    // ------------------------------------------------------------------

    /** `options.txt` 键：选中的后端（[SrBackend] 的枚举名）。 */
    const val OPTIONS_KEY_BACKEND: String = "dxrMcSrBackend"

    /** `options.txt` 键：锐度百分比（0..100，打包进载荷时除以 100）。 */
    const val OPTIONS_KEY_SHARPNESS: String = "dxrMcSrSharpness"

    /** 当前后端；`null` = 还没初始化（首次访问从 `[sr] backend` 取初始值）。 */
    @Volatile
    private var backend: SrBackend? = null

    /** 正在同步两个开关时置位，避免 `OptionInstance.set` 的回调打回来。 */
    private var syncingOptions = false

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
        { value -> IllusionLogger.debugMessage("$PREFIX sharpness: ${value}%") },
    )

    @JvmStatic
    fun fsrOption(): OptionInstance<Boolean> = FSR_OPTION

    @JvmStatic
    fun xessOption(): OptionInstance<Boolean> = XESS_OPTION

    @JvmStatic
    fun sharpnessOption(): OptionInstance<Int> = SHARPNESS_OPTION

    /** 当前锐度（0..1），打包载荷时用。 */
    @JvmStatic
    fun sharpness(): Float = SHARPNESS_OPTION.get().coerceIn(0, 100) / 100.0f

    /** 当前后端（界面与日志用它判断"谁在跑"）。 */
    @JvmStatic
    fun backend(): SrBackend = currentBackend()

    private fun currentBackend(): SrBackend {
        backend?.let { return it }
        // 首次访问：`[sr] backend` 是初始值。`options.txt` 里有记录时 [load] 会覆盖它，
        // 于是"配置文件给默认、游戏内改动落在 options.txt"两件事各归各位。
        val initial = D3D12Settings.srBackend
        backend = initial
        syncOptions(initial)
        return initial
    }

    private fun syncOptions(selected: SrBackend) {
        syncingOptions = true
        try {
            FSR_OPTION.set(selected == SrBackend.FSR)
            XESS_OPTION.set(selected == SrBackend.XESS)
        } finally {
            syncingOptions = false
        }
    }

    /**
     * 两个开关共用一个当前后端。
     *
     * <p>这里**不**调 `mcdr_dx12SrDestroy`：那会在可能还有打开命令列表时销毁上下文。换成把
     * [createdFor] / [lastKey] 清空 —— 下一帧 `createKey` 里带着后端号，native 的
     * `ensureProvider` 会先放掉旧 provider 再建新的（它自己保证 GPU 追平）。
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
            // DLAA 把渲染比例锁在 100%（锁在 [D3D12RenderScale.setPercent] 里），而 100% 时没有东西
            // 可以升采样。与其"勾了没反应"，不如明确拒绝并把原因写清楚。
            if (D3D12RenderScale.isLockedByDlaa()) {
                Illusion.LOGGER.warn(
                    "[dxrmc] {} cannot switch to {} while DLAA is active; turn DLAA off first",
                    PREFIX, requested,
                )
                syncOptions(previous)
                return
            }
            // 100% 时 FSR / XeSS 无事可做（门面会拦下并记一条 note）。用户刚在界面上勾了它，就把比例
            // 降到一个能用的默认档，而不是让画面看起来"什么都没发生"。
            if (D3D12RenderScale.percent() >= 100) {
                D3D12RenderScale.setPercentAtFrameBoundary(DEFAULT_RENDER_PERCENT)
                Illusion.LOGGER.info(
                    "[dxrmc] {} render scale 100% -> {}% so {} has something to upscale",
                    PREFIX, DEFAULT_RENDER_PERCENT, requested,
                )
            }
        }
        backend = requested
        syncOptions(requested)
        createdFor = ""
        lastKey = ""
        lastState = ""
        Illusion.LOGGER.info("[dxrmc] {} backend: {} -> {}", PREFIX, previous, requested)
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
                } else if (line.startsWith("$OPTIONS_KEY_SHARPNESS:")) {
                    val text = line.substring(OPTIONS_KEY_SHARPNESS.length + 1).trim().trim('"')
                    text.toIntOrNull()?.let { SHARPNESS_OPTION.set(it.coerceIn(0, 100)) }
                }
            }
        } catch (t: Throwable) {
            Illusion.LOGGER.warn("[dxrmc] could not read SR options from {}", file, t)
            return
        }
        createdFor = ""
        lastKey = ""
        lastState = ""
        // 读盘时就把「另一个后端要用」这件事落实：`options.txt` 里 backend 是 FSR / XeSS、而
        // `dxrMcDlss` 还是 true 是**升级上来最常见**的组合（DLSS 曾经是唯一的超分）。
        // 不在这里让位，DLSS 会先跑起来、把渲染缩放切到它自己的档（58%），再被门面在帧中间关掉 ——
        // 实测那种顺序会让速度通道此后一直没纹理（`recorded=-1 current=false`），FSR 一帧都评不了。
        if (currentBackend() != SrBackend.DLSS) {
            DeepLearningSuperSampling.standDown(
                "the SR backend in options.txt is ${currentBackend().name}, so DLSS must not also present")
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
                it.startsWith("$OPTIONS_KEY_BACKEND:") || it.startsWith("$OPTIONS_KEY_SHARPNESS:")
            }
            val text = (
                kept + listOf(
                    "$OPTIONS_KEY_BACKEND:${currentBackend().name}",
                    "$OPTIONS_KEY_SHARPNESS:${SHARPNESS_OPTION.get()}",
                )
                ).joinToString(eol) + eol
            file.writeText(text, StandardCharsets.UTF_8)
        } catch (t: Throwable) {
            Illusion.LOGGER.warn("[dxrmc] could not write SR options to {}", file, t)
        }
    }

    /**
     * 帧内挂点（`GameRendererSmaaWorldMixin`）：世界层已画完、GUI 还没开始的那一刻。
     *
     * DLSS 分支一行不改地委托出去；FSR / XeSS 走 [evaluateOther]。
     */
    @JvmStatic
    fun applyIfEnabled() {
        when (backendId()) {
            MCDR_ABI.SR_BACKEND_NONE -> releaseIfCreated()
            MCDR_ABI.SR_BACKEND_DLSS -> {
                // 从 FSR / XeSS 切回 DLSS：先把**我们**打开的数据面还原，让 DLSS 用自己的记账
                // 重新打开它（否则 ownership 落在本类手里，DLSS 退出时不会关掉 jitter，
                // 就成了 P218 那个形态）。同一帧内 DLSS 会立刻再置 true，不存在空档。
                if (forcedTemporal || forcedVelocity) {
                    releaseDataPlane()
                }
                DeepLearningSuperSampling.applyIfEnabled()
            }
            else -> {
                // 心跳只在调试级别：它每帧都会被调用，INFO 会刷屏。
                // 一家在写、另一家必须让位：DLSS 与 FSR/XeSS 都往同一张颜色图上写，而
                // `reconcileAntiAliasing` 每帧按 `options.dlss` 重新对齐 native 闸门，所以只关闸门不够。
                // 让位动作排到帧边界（`Minecraft.schedule`）：它会把渲染缩放从 DLSS 档切回普通档，
                // 帧中间改比例正是 P282c 那次 `copyDepthFrom` 崩溃的形态。
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
                        "$PREFIX enter: backend=${backendName(backendId())} " +
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

    /**
     * 换后端 / 关掉时把 native 的上下文放掉。`mcdr_dx12SrDestroy` 在 native 侧幂等
     * （provider 为空就什么都不做），所以这里不必重复判断。
     */
    @JvmStatic
    fun releaseIfCreated() {
        if (createdFor.isNotEmpty()) {
            createdFor = ""
            lastKey = ""
            MCDR_ABI.D3D12SrDestroy()
            IllusionLogger.debugMessage("$PREFIX released (backend is none)")
        }
        releaseDataPlane()
    }

    /**
     * 把**我们**打开的数据面还回去。
     *
     * 与 `DeepLearningSuperSampling.leaveDlssMode` 同一条纪律：只还原"是我们打开的"那两个字段，
     * 配置本来就要它们时不动。不还原会让 `[sr] backend = none` 之后 jitter / 速度通道继续跑 ——
     * 那正是 P218「关了超分画面还在抖」的形态。
     */
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

    /**
     * FSR / XeSS 与 DLSS 一样吃 jitter + 运动矢量两条数据，而这两条在构建期默认关闭
     * （`Illusion\build.gradle` 的 `dxrmc.temporal*`）。这里做 DLS 的
     * `ensureDataPlaneReady` + `retryVelocityIfLatched` 的等价动作，**幂等**且只在满足前提后调用
     * （否则就会重演 P218：没有任何消费者却一直在抖）。
     */
    private fun ensureDataPlane() {
        if (!D3D12Temporal.enabled) {
            D3D12Temporal.enabled = true
            forcedTemporal = true
            Illusion.LOGGER.info(
                "[dxrmc] $PREFIX {} needs the temporal data plane: sub-pixel projection jitter "
                    + "(dxrmc.temporal) is switched on while it is selected", backendName(backendId()))
        }
        if (!D3D12Velocity.enabled) {
            D3D12Velocity.enabled = true
            forcedVelocity = true
            Illusion.LOGGER.info(
                "[dxrmc] $PREFIX {} needs motion vectors: the velocity pass "
                    + "(dxrmc.temporal.velocity) is switched on while it is selected",
                backendName(backendId()))
        }
        if (!D3D12Velocity.isLatched()) {
            return
        }
        // 速度通道的自我关闭是一票否决的：只把 enabled 置回去不撤不回失败的构建步骤，
        // 必须清闩锁。限速一秒一次，别每帧重建资源（P108e）。
        val now = System.nanoTime()
        if (lastVelocityRetryNanos != Long.MIN_VALUE
            && now - lastVelocityRetryNanos < VELOCITY_RETRY_NANOS) {
            return
        }
        lastVelocityRetryNanos = now
        D3D12Velocity.forceEnable()
    }

    // ------------------------------------------------------------------
    // FSR / XeSS 的每帧路径
    // ------------------------------------------------------------------

    private fun evaluateOther() {
        val backend = backendId()
        val minecraft = Minecraft.getInstance()
        // 标题界面 / 加载界面：没有世界层，谈不上"世界层升采样"。
        if (minecraft.level == null) {
            return
        }
        val caps = MCDR_ABI.querySrCaps(backend)
        if (caps == null) {
            return note("the loaded MCDR.dll has no mcdr_dx12SrQueryCaps (it predates P271)")
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
        // 显示尺寸从 GUI 目标来：拆分启用时它就是窗口尺寸，最终界面也画进它。
        val guiTarget = D3D12GUISplit.targetForFrame() ?: D3D12GUISplit.ensureTargetForDlss()
        val guiColor = (guiTarget?.colorTextureView as? D3D12GpuTextureView)?.texture() as? D3D12GpuTexture
        if (guiColor == null) {
            return note("no display-sized target: FSR/XeSS need a separate input and output texture")
        }
        val encoder = D3D12Device.sharedCommandEncoder()
        if (encoder == null) {
            return note("no shared command encoder")
        }
        val renderWidth = color.getWidth(0)
        val renderHeight = color.getHeight(0)
        val displayWidth = guiColor.getWidth(0)
        val displayHeight = guiColor.getHeight(0)
        if (renderWidth <= 0 || renderHeight <= 0 || displayWidth <= 0 || displayHeight <= 0) {
            return note("degenerate sizes: render ${renderWidth}x$renderHeight, "
                + "display ${displayWidth}x$displayHeight")
        }
        // **输出必须是带 `USAGE_STORAGE` 的纹理**：native 的 `textureFlags()` 只有看到
        // `usage & 32` 才加 `D3D12_RESOURCE_FLAG_ALLOW_UNORDERED_ACCESS`，而 FSR / XeSS 都要
        // 往输出上写 UAV。GUI 目标是普通 `RenderTarget`（没有 STORAGE），直接当 `pColorOutput`
        // 会让那次 UNORDERED_ACCESS 屏障在 `Close()` 时报 E_INVALIDARG（实测
        // `endCommandList: Close HRESULT 0x80070057`），所以借用 DLSS 那张 JVM 建的 UAV 纹理，
        // 成功后再 blit 进 GUI 目标。
        val output = DLSSOutput.textureForFrame(displayWidth, displayHeight)
        if (output == null) {
            return note("could not create the display-sized UAV output texture")
        }
        if (renderWidth == displayWidth && renderHeight == displayHeight) {
            // 100% 时没有东西可以升采样。界面上的勾选（`requestBackend`）已经会顺手降档，但配置
            // 文件里写死 `backend = "FSR"` + 100% 时仍然会撞到这里，所以这里再自愈一次：降一次档，
            // 下一帧就有活干了。DLAA 锁死 100% 的情况除外（那本来就不该用 FSR / XeSS）。
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

        // 相位长度：把后端期望的样本数交给唯一的抖动源（P274）。DLSS 是"让后端接受我们的序列"，
        // FSR 换到它自己的相位表长度，XeSS 保持 8。
        D3D12Temporal.adoptPhaseCount(backend,
            MCDR_ABI.D3D12SrJitterPhaseCount(backend, renderWidth, displayWidth))

        // 数据面：jitter 与速度通道在构建期默认关闭，选了 SR 必须打开（否则 native 拿到空句柄）。
        // 放在所有"这次真的会升采样"的前提之后，避免重演 P218（没有消费者却在抖）。
        ensureDataPlane()

        // 速度通道：与 DLSS 同一个挂点、同一份兜底调用（本帧已录过时它自己会跳过）。
        // 首帧没有历史（上一帧没有 jitter 数据）→ 必然为空，第二帧起才有值。
        encoder.recordVelocityPass()
        val motionVectors = D3D12Velocity.currentHandle()
        if (motionVectors == 0L) {
            // 每帧都会到这里时，一行诊断比"什么都没跑"有用（按秒限流，不刷屏）。
            val now = System.nanoTime()
            if (now - lastVelocityDiagNanos > VELOCITY_DIAG_NANOS) {
                lastVelocityDiagNanos = now
                Illusion.LOGGER.info("[dxrmc] [SR] velocity diag: {}", D3D12Velocity.debugState())
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
        val farPlane = if (camera != null && camera.depthFar > 0.0f) camera.depthFar else FALLBACK_FAR
        val fov = fovFromProjection(D3D12Temporal.currentProjection())
        val sharpness = sharpness()

        // P276：可选 reactive / responsive 掩码。产生器必须与 evaluate 用同一条命令列表，
        // 所以在这里做（列表已打开、还没提交）；关掉配置时传 0 = "没有这张纹理"。
        val mask = if (D3D12Settings.srMask) D3D12SrMask.produce(encoder, renderWidth, renderHeight) else 0L
        if (D3D12Settings.srMask && mask == 0L) {
            note("the P276 mask producer did not run: " + D3D12SrMask.note())
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
                // P288 之前一律按 SDR 处理：native 侧据此决定要不要置 FSR 的
                // FFX_UPSCALE_ENABLE_HIGH_DYNAMIC_RANGE。
                desc.set(I32, OFF_HDR, 0)
                desc.set(I32, OFF_BACKEND, backend)
                desc.set(I32, OFF_FLAGS, 0)
                desc.set(PTR, OFF_COLOR_IN, MemorySegment.ofAddress(color.handle()))
                desc.set(PTR, OFF_COLOR_OUT, MemorySegment.ofAddress(output.handle()))
                desc.set(PTR, OFF_DEPTH, MemorySegment.ofAddress(depth.handle()))
                desc.set(PTR, OFF_MV, MemorySegment.ofAddress(motionVectors))
                // P276：掩码由 D3D12SrMask 在同一条列表上先画出（0 = 没做成，native 侧跳过）；
                // 曝光仍传 0（native 把 preExposure 当常数用）。
                desc.set(PTR, OFF_MASK, MemorySegment.ofAddress(mask))
                desc.set(PTR, OFF_EXPOSURE, MemorySegment.ofAddress(0L))
                desc.set(I32, OFF_DEPTH_FORMAT, depth.format.ordinal)
                desc.set(F32, OFF_ASPECT, displayWidth.toFloat() / displayHeight.toFloat())
                desc.set(F32, OFF_METERS, 1.0f)
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
                // 后端把升采样结果写进了那张 UAV 纹理；搬进 GUI 目标后，本帧的 prepareForGui
                // 必须跳过它自己的（线性采样）世界层 blit，否则会盖掉升采样结果。
                val blitStarted = System.nanoTime()
                val blitted = encoder.recordTextureBlit(output, guiColor)
                recordCost(evalNanos, System.nanoTime() - blitStarted)
                if (blitted) {
                    D3D12GUISplit.markWorldLayerDone()
                    lastState = ""
                } else {
                    note("the upscaled image could not be blitted into the GUI target")
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
            Illusion.LOGGER.warn("[dxrmc] [SR] GPU timing disabled: creating the query pool threw {}", t.toString())
            return false
        }
        if (pool == 0L) {
            gpuPoolWarned = true
            Illusion.LOGGER.warn("[dxrmc] [SR] GPU timing disabled: no mcdr_dx12CreateQueryPool")
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
            Illusion.LOGGER.warn("[dxrmc] [SR] GPU timing disabled: writing a timestamp threw {}", t.toString())
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
            Illusion.LOGGER.warn("[dxrmc] [SR] GPU timing disabled: writing a timestamp threw {}", t.toString())
            return
        }
        gpuFrame++
        if (gpuFrame >= GPU_SAMPLES) {
            flushGpuSamples()
            gpuFrame = 0
        }
    }

    /**
     * Read the whole pool back, drop implausible pairs, log GPU-side p50 / p95.
     *
     * <p>Filtering prefers losing a sample to reporting a fake one: a zero front value (the GPU has
     * not reached that query yet), back <= front, and anything above 100 ms (window rebuilds).
     */
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
            Illusion.LOGGER.warn("[dxrmc] [SR] GPU timing disabled: reading the query pool threw {}", t.toString())
            return
        }
        var accepted = 0
        val limit = frequency / 10
        for (i in 0 until GPU_SAMPLES) {
            val front = values[i * 2]
            val back = values[i * 2 + 1]
            if (front <= 0L || back <= front) {
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
        Illusion.LOGGER.info(
            "[dxrmc] [SR] gpu cost over {} frames: backend={} p50={}us p95={}us (max {}us, {} samples rejected)",
            accepted,
            backendLabel(backendId()),
            sorted[accepted / 2] * 1_000_000L / frequency,
            sorted[(accepted * 95) / 100] * 1_000_000L / frequency,
            sorted[accepted - 1] * 1_000_000L / frequency,
            GPU_SAMPLES - accepted,
        )
    }

    /**
     * 记一帧的录制成本；攒够 [COST_LOG_EVERY] 帧打一行 p50 / p95。
     *
     * <p>统计的是**渲染线程的墙上时间**：跨 ABI 调用 + 载荷打包。GPU 那边是异步的，
     * 所以这几微秒不等于超分的真实开销（真实开销要看 GPU timestamp，P284b 的下一半）。
     * 它的用处是排除我自己引入的 CPU 抖动（例如每帧多做几次 PathElement 查找）。
     */
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
        Illusion.LOGGER.info(
            "[dxrmc] [SR] record cost over {} frames: evaluate p50={}us p95={}us, blit p50={}us p95={}us (CPU only)",
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

    /** 透视投影反算垂直 FOV（弧度）：m11 = 1/tan(fovY/2)，列主序落 index 5。与 P275 的 DLSS 侧一致。 */
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
        Illusion.LOGGER.info("[dxrmc] $PREFIX {} did not run this frame: {}",
            backendName(backendId()), String.format(Locale.ROOT, "%s", message))
    }
}
