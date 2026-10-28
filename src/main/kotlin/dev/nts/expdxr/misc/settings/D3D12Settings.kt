package dev.nts.expdxr.misc.settings

import dev.nts.expdxr.CommandBundleMode
import dev.nts.expdxr.DlssFrameGeneration
import dev.nts.expdxr.DlssImplementation
import dev.nts.expdxr.HdrMode
import dev.nts.expdxr.RtMode
import dev.nts.expdxr.SrBackend
import dev.nts.expdxr.IllusionConfig
import dev.nts.expdxr.MeshDiagnostics
import dev.nts.expdxr.d3d12.extern.MCDR_ABI
import dev.nts.expdxr.misc.IllusionLogger
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 渲染开关的唯一解析处：NeoForge 配置优先，旧来源（环境变量 / `-D` 属性 / marker 文件）只作弃用逃生口。
 *
 * <p>快照不可变、经 `@Volatile` 发布，渲染线程与录制 worker 并发读；[reload] 由配置加载/重载事件调用。
 * 读配置在每帧路径上，所以必须缓存 —— 但在配置读进来**之前**建的快照绝不缓存（渲染器比 toml 早启动）。
 * 见 docs/misc/settingscreen/README.md §3。
 */
object D3D12Settings {
    private const val LEGACY_DLSS_IMPL = "MCDR_DLSS_IMPL"

    private const val LEGACY_STREAMLINE = "MCDR_STREAMLINE"

    private class Snapshot(
        val glContext: Boolean,
        val asyncCommandLists: Boolean,
        val asyncWorkers: Int,
        val commandBundles: CommandBundleMode,
        val sharpenAmount: Float,
        val taaSharpenAmount: Float,
        val dlssImplementation: DlssImplementation,
        val dlssOutputHandover: Boolean,
        val srBackend: SrBackend,
        val srSharpness: Float,
    val srMask: Boolean,
        val reflexFrameLimitUs: Int,
        val meshTerrain: Boolean,
        val meshTerrainCull: Boolean,
        val dumpHlsl: Boolean,
        val debugLayer: Boolean,
        val profiling: Boolean,
        val skipGlSwap: Boolean,
        val asyncRenderThread: Boolean,
        val temporalChain: Boolean,
        val temporalJitter: Boolean,
        val selfTestSurface: Boolean,
        val selfTestMesh: Boolean,
        val selfTestCompute: Boolean,
        val failDevice: Boolean,
        val meshDiagnostics: MeshDiagnostics,
        val noCrashPopup: Boolean,
        val nativeLogFile: String,
        val pixTimingCapture: Boolean,
        val pixCaptureFrames: Int,
        val pixDirectory: String,
        val hdrMode: Int,

        /** ALLOW_TEARING 交换链 / 是否强制建在自建呈现子窗口上。 */
        val allowTearing: Boolean,
        val presentChildWindow: Boolean,


    /** 帧生成（0=off 1=on 2=dynamic）与每帧生成张数。 */
    val dlssgMode: Int,
    val dlssgFrames: Int,

    /** 光追可见性分层（0=off 1=ao 2=ao_shadows 3=full）。 */
    val rtMode: Int,
    )

    @Volatile
    private var snapshot: Snapshot? = null

    @Volatile
    private var pushed: String? = null

    private val warned: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** 保留一个真 GL 上下文（否则向 GLFW 要 `GLFW_NO_API`）；设备创建前读。 */
    val glContext: Boolean get() = snapshot().glContext

    val asyncCommandLists: Boolean get() = snapshot().asyncCommandLists

    val asyncWorkers: Int get() = snapshot().asyncWorkers

    val commandBundles: CommandBundleMode get() = snapshot().commandBundles

    val sharpenAmount: Float get() = snapshot().sharpenAmount

    val taaSharpenAmount: Float get() = snapshot().taaSharpenAmount

    val dlssImplementation: DlssImplementation get() = snapshot().dlssImplementation

    val dlssOutputHandover: Boolean get() = snapshot().dlssOutputHandover

    /** `[sr] backend` —— 超分抽象层当前驱动哪一家。 */
    val srBackend: SrBackend get() = snapshot().srBackend

    val srSharpness: Float get() = snapshot().srSharpness

    val srMask: Boolean get() = snapshot().srMask
    /** `[dlss] reflexFrameLimitUs` — Reflex 自己的限帧器（微秒/帧，0 = 关）。 */
    val reflexFrameLimitUs: Int get() = snapshot().reflexFrameLimitUs
    /** `[mesh] terrain` — 地形走 mesh 管线（已无 IMR 回退，这是唯一的地形光栅化路径）。 */
    val meshTerrain: Boolean get() = snapshot().meshTerrain

    /** `[mesh] terrainCull` —— 用 AS 剔除地形 meshlet（依赖 `[mesh] terrain`）。 */
    val meshTerrainCull: Boolean get() = snapshot().meshTerrainCull

    val dumpHlsl: Boolean get() = snapshot().dumpHlsl

    val debugLayer: Boolean get() = snapshot().debugLayer

    val profiling: Boolean get() = snapshot().profiling

    /** 只在 `glContext` 开着时有意义：别让残留的 GL surface 去 swap 一个不属于它的窗口。 */
    val skipGlSwap: Boolean get() = snapshot().skipGlSwap

    val asyncRenderThread: Boolean get() = snapshot().asyncRenderThread

    val temporalChain: Boolean get() = snapshot().temporalChain

    val temporalJitter: Boolean get() = snapshot().temporalJitter

    /** 自检链会抢走 RTSS/Afterburner 绑定的第一条呈现交换链，所以默认关。 */
    val selfTestSurface: Boolean get() = snapshot().selfTestSurface

    val selfTestMesh: Boolean get() = snapshot().selfTestMesh

    val selfTestCompute: Boolean get() = snapshot().selfTestCompute

    val failDevice: Boolean get() = snapshot().failDevice

    val meshDiagnostics: MeshDiagnostics get() = snapshot().meshDiagnostics

    val noCrashPopup: Boolean get() = snapshot().noCrashPopup

    val nativeLogFile: String get() = snapshot().nativeLogFile


    /** 由游戏自己驱动 PIX 捕获：PIX 的连续录制从不落盘，只有进程内调 PIXBeginCapture 才写进 `run/pix`。 */
    val pixTimingCapture: Boolean get() = snapshot().pixTimingCapture

    /** 一次按键捕获录多少帧（按下热键那一刻读，属热切换）。 */
    val pixCaptureFrames: Int get() = snapshot().pixCaptureFrames

    /** PIX 安装目录或其中一个捕获 DLL；空 = 用默认安装位置。 */
    val pixDirectory: String get() = snapshot().pixDirectory

    /** 丢掉缓存快照，下一次读重新解析全部开关。 */
    fun reload() {
        snapshot = null
        pushed = null
    }
    /**
     * 把开关交给 DLL。幂等：比较用的字符串必须包含每一个推下去的值，漏一个就漏推。
     *
     * <p>配置加载时 ABI 可能还不可用（那次推送被丢掉），所以设备建好后要再调一次。见 docs/misc/settingscreen/README.md §3.5。
     */
    fun pushToNative() {
        if (!MCDR_ABI.isAvailable()) return
        val s = snapshot()
        val wanted = buildString {
            append(s.debugLayer).append('|')
            append(s.profiling).append('|')
            append(s.dlssImplementation).append('|')
            append(s.reflexFrameLimitUs).append('|')
            append(s.nativeLogFile).append('|')
            append(s.pixDirectory).append('|')
            append(s.hdrMode)
            append('|').append(s.rtMode)
            append('|').append(s.allowTearing)
            append('|').append(s.presentChildWindow)
        }
        if (wanted == pushed) return
        pushed = wanted
        MCDR_ABI.D3D12SetEnvironment("DX12_DEBUG_LAYER", if (s.debugLayer) "1" else null)
        MCDR_ABI.D3D12SetEnvironment("DX12_PROF", if (s.profiling) "1" else null)
        MCDR_ABI.D3D12SetEnvironment(
            LEGACY_DLSS_IMPL,
            if (s.dlssImplementation == DlssImplementation.STREAMLINE) "streamline" else "ngx",
        )
        MCDR_ABI.D3D12SetEnvironment(
            LEGACY_STREAMLINE,
            if (s.dlssImplementation == DlssImplementation.STREAMLINE) "1" else null,
        )
        MCDR_ABI.D3D12SetEnvironment("DX12_NATIVE_LOG_FILE", s.nativeLogFile.ifEmpty { null })
        // 下面这些走专用 ABI：配置是唯一来源，没有环境变量入口（见 docs/misc/settingscreen/README.md §3.6）。
        MCDR_ABI.D3D12SetPIXDirectory(s.pixDirectory)
        MCDR_ABI.D3D12SetReflexFrameLimit(s.reflexFrameLimitUs)
        // 0/1/2 = AUTO/FORCE_ON/FORCE_OFF。HDR 与交换链选项都是建链参数，下一次建链才有用。
        MCDR_ABI.D3D12SetHdrMode(s.hdrMode)
        MCDR_ABI.D3D12SetSwapchainOptions(s.allowTearing, s.presentChildWindow)
        MCDR_ABI.D3D12SetRaytracingMode(s.rtMode)
        // 动态模式（2）只能配 1 张额外帧，实际张数由驱动决定 —— ABI 约束，不是界面逻辑。
    MCDR_ABI.D3D12SetDlssgMode(s.dlssgMode, if (s.dlssgMode == 2) 1 else s.dlssgFrames)
    }

    private fun snapshot(): Snapshot = snapshot ?: build()

    private fun build(): Snapshot {
        var configAvailable = true

        fun <T> config(fallback: T, read: () -> T): T = try {
            read()
        } catch (t: Throwable) {
            // 配置还没读进来（模组构造、DLL 初始化）：用出厂默认值，且**不写回缓存**。
            configAvailable = false
            fallback
        }

        val asyncWorkerLegacy =
            (System.getenv("DX12_ASYNC_WORKERS") ?: System.getProperty("backend.async.workers"))
                ?.trim()?.toIntOrNull()

        val bundleModeLegacy = (System.getenv("DX12_BUNDLE_MODE") ?: System.getProperty("backend.bundleMode"))
            ?.trim()?.lowercase()

        val legacySharpen = System.getProperty("dxrmc.sharpen")?.toFloatOrNull()
            ?.takeUnless { it.isNaN() }
            ?.coerceIn(0.0f, 1.0f)

        val legacyImpl = when {
            System.getenv(LEGACY_DLSS_IMPL)?.trim()?.equals("streamline", ignoreCase = true) == true ->
                DlssImplementation.STREAMLINE
            System.getenv(LEGACY_STREAMLINE)?.trim() == "1" -> DlssImplementation.STREAMLINE
            else -> null
        }

        val s = Snapshot(
            glContext = config(false) { IllusionConfig.GL_CONTEXT.get() } ||
                legacy("glContext") {
                    System.getProperty("dxrmc.noApi") == "false" ||
                        System.getenv("DXR_MC_GL_CONTEXT") == "1" ||
                        File("gl_context.flag").exists()
                },
            asyncCommandLists = config(true) { IllusionConfig.ASYNC_COMMAND_LISTS.get() } &&
                !legacy("asyncCommandLists") {
                    System.getenv("DX12_ASYNC")?.trim() == "0" ||
                        System.getProperty("backend.async") == "0"
                },
            asyncWorkers = config(8) { IllusionConfig.ASYNC_WORKERS.get() }
                .let { configured ->
                    if (configured == 8 && asyncWorkerLegacy != null) asyncWorkerLegacy else configured
                }
                .coerceIn(1, 8),
            commandBundles = config(CommandBundleMode.AUTO) { IllusionConfig.COMMAND_BUNDLES.get() }
                .takeUnless {
                    it == CommandBundleMode.AUTO &&
                        legacy("commandBundles") { bundleModeLegacy != null }
                }
                ?: when (bundleModeLegacy) {
                    "serial", "1" -> CommandBundleMode.SERIAL
                    "parallel", "0" -> CommandBundleMode.PARALLEL
                    else -> CommandBundleMode.AUTO
                },
            sharpenAmount = legacySharpen
                ?: config(0.67f) { IllusionConfig.SHARPEN_AMOUNT.get().toFloat() },
            taaSharpenAmount = legacySharpen
                ?: config(0.816f) { IllusionConfig.TAA_SHARPEN_AMOUNT.get().toFloat() },
            dlssImplementation = config(DlssImplementation.NGX) { IllusionConfig.DLSS_IMPLEMENTATION.get() }
                .let { configured ->
                    val overridden = configured == DlssImplementation.NGX &&
                        legacy("dlssImplementation") { legacyImpl != null }
                    if (overridden) legacyImpl ?: configured else configured
                },
            reflexFrameLimitUs = config(0) { IllusionConfig.DLSS_REFLEX_FRAME_LIMIT_US.get() }
                .takeUnless { it == 0 && legacy("reflexFrameLimitUs") { System.getenv("MCDR_REFLEX_FRAME_LIMIT_US") != null } }
                ?: (System.getenv("MCDR_REFLEX_FRAME_LIMIT_US")?.trim()?.toIntOrNull() ?: 0),
            meshTerrain = config(true) { IllusionConfig.MESH_TERRAIN.get() },
            meshTerrainCull = config(false) { IllusionConfig.MESH_TERRAIN_CULL.get() },
            dumpHlsl = config(false) { IllusionConfig.DUMP_HLSL.get() },
            dlssOutputHandover = config(true) { IllusionConfig.DLSS_OUTPUT_HANDOVER.get() } &&
                !legacy("dlssOutputHandover") {
                    System.getProperty("dxrmc.dlssOutput")?.trim()?.equals("off", ignoreCase = true) == true
                },
            debugLayer = config(false) { IllusionConfig.DEBUG_LAYER.get() } ||
                legacy("debugLayer") { System.getenv("DX12_DEBUG_LAYER") == "1" },
            profiling = config(false) { IllusionConfig.PROFILING.get() } ||
                legacy("profiling") {
                    System.getenv("DX12_PROF") == "1" || System.getenv("D3D12_LOG_VERBOSE") == "1"
                },
            skipGlSwap = config(false) { IllusionConfig.SKIP_GL_SWAP.get() } ||
                legacy("skipGlSwap") { System.getenv("DXR_MC_SKIP_GL_SWAP") != null },
            asyncRenderThread = config(true) { IllusionConfig.ASYNC_RENDER_THREAD.get() } &&
                !legacy("asyncRenderThread") { System.getenv("DX12_NO_ASYNC") == "1" },
            temporalChain = config(false) { IllusionConfig.TEMPORAL_CHAIN.get() } ||
                legacy("temporalChain") { System.getProperty("dxrmc.temporal") == "true" },
            temporalJitter = config(true) { IllusionConfig.TEMPORAL_JITTER.get() } &&
                !legacy("temporalJitter") { System.getProperty("dxrmc.temporal.jitter") == "false" },
            selfTestSurface = config(false) { IllusionConfig.SELF_TEST_SURFACE.get() } ||
                legacy("selfTestSurface") { System.getProperty("dxrmc.selfTestSurface") == "true" },
            selfTestMesh = config(false) { IllusionConfig.SELF_TEST_MESH.get() } ||
                legacy("selfTestMesh") { System.getProperty("dxrmc.selfTestMesh") == "true" },
            selfTestCompute = config(false) { IllusionConfig.SELF_TEST_COMPUTE.get() } ||
                legacy("selfTestCompute") { System.getProperty("dxrmc.selfTestCompute") == "true" },
            failDevice = config(false) { IllusionConfig.FAIL_DEVICE.get() } ||
                legacy("failDevice") { System.getProperty("dxrmc.failDevice") == "true" },
            meshDiagnostics = config(MeshDiagnostics.NONE) { IllusionConfig.MESH_DIAGNOSTICS.get() }
                .takeUnless { it == MeshDiagnostics.NONE }
                ?: when (System.getenv("DX12_MESH_DIAG")?.trim()?.lowercase()) {
                    "clear" -> MeshDiagnostics.CLEAR
                    "pass" -> MeshDiagnostics.PASS
                    else -> MeshDiagnostics.NONE
                },
            noCrashPopup = config(false) { IllusionConfig.NO_CRASH_POPUP.get() } ||
                legacy("noCrashPopup") { System.getProperty("dxrmc.noPopup") == "true" },
            nativeLogFile = config("") { IllusionConfig.NATIVE_LOG_FILE.get() }
                .ifEmpty { System.getenv("DX12_NATIVE_LOG_FILE") ?: "" },
            pixTimingCapture = config(false) { IllusionConfig.PIX_TIMING_CAPTURE.get() },
            pixCaptureFrames = config(600) { IllusionConfig.PIX_CAPTURE_FRAMES.get() },
            pixDirectory = config("") { IllusionConfig.PIX_DIRECTORY.get() },
            // 0 = AUTO / 1 = FORCE_ON / 2 = FORCE_OFF：native 只认序号，不要在这里重排。
            hdrMode = config(HdrMode.AUTO) { IllusionConfig.HDR.get() }.ordinal,
            allowTearing = config(true) { IllusionConfig.ALLOW_TEARING.get() },
            presentChildWindow = config(false) { IllusionConfig.PRESENT_CHILD_WINDOW.get() },
            rtMode = config(RtMode.OFF) { IllusionConfig.RAYTRACING_MODE.get() }.ordinal,
            dlssgMode = config(DlssFrameGeneration.OFF) { IllusionConfig.DLSS_FRAME_GENERATION.get() }.ordinal,
            dlssgFrames = config(1) { IllusionConfig.DLSS_FRAME_GENERATION_FRAMES.get() },
            srBackend = config(SrBackend.DLSS) { IllusionConfig.SR_BACKEND.get() },
            srSharpness = config(0.0) { IllusionConfig.SR_SHARPNESS.get() }.toFloat(),
        srMask = config(false) { IllusionConfig.SR_MASK.get() },
        )

        if (configAvailable) {
            snapshot = s
        }
        return s
    }

    /** 旧来源命中时 warn 一次；调用点只在配置仍是出厂默认值时看它（见 docs/misc/settingscreen/README.md §3.7）。 */
    private inline fun legacy(key: String, check: () -> Boolean): Boolean {
        if (!check()) return false
        if (warned.add(key)) {
            IllusionLogger.warnMessage(
                "`$key` is enabled by its old environment variable / system property / " +
                    "marker file. Move it to config/dxr_mc-client.toml - the old source is still " +
                    "honoured only so existing scripts keep working.",
            )
        }
        return true
    }
}
