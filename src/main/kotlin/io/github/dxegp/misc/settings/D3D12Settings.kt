package io.github.dxegp.misc.settings

import io.github.dxegp.CommandBundleMode
import io.github.dxegp.DlssFrameGeneration
import io.github.dxegp.DlssImplementation
import io.github.dxegp.HdrMode
import io.github.dxegp.RtBackend
import io.github.dxegp.RtDebugView
import io.github.dxegp.RtDenoise
import io.github.dxegp.RtExposureMode
import io.github.dxegp.RtMode
import io.github.dxegp.RtQualityProfile
import io.github.dxegp.RtSamplingMode
import io.github.dxegp.SrBackend
import io.github.dxegp.IllusionConfig
import io.github.dxegp.MeshDiagnostics
import io.github.dxegp.d3d12.extern.MCDR_ABI
import io.github.dxegp.d3d12.render.adapter.D3D12AdapterInfo
import io.github.dxegp.misc.IllusionLogger
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
        val shaderConverter: Boolean,
        val shaderConverterDll: String,
        val shaderConverterPrimary: Boolean,
        val shaderConverterPipelines: String,
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

    /** 后端（0=auto 1=dxr 2=wg）、质量档（0=validation 1=balanced 2=quality）。 */
    val rtBackend: Int,
    val rtQualityProfile: Int,

    /** 降噪与超分轨道（[RtDenoise] 的序号）、渲染倍率百分比。 */
    val rtDenoise: Int,
    val rtRenderScale: Int,
    val rtBuildCpuMicros: Int,
    val rtBuildGpuMicros: Int,
    val rtBuildTriangles: Int,
    val rtExposureMode: RtExposureMode,
    val rtExposureEv: Float,
    val rtExposureSpeed: Float,

    /** 采样：每像素样本数、最大弹射数、方向选择（0=bsdf 1=cosine 2=reservoir）。 */
    val rtSpp: Int,
    val rtMaxBounces: Int,
    val rtSamplingMode: Int,

    /** 体素轨信任半径（方块）、上一帧 HZB 剔除、时域历史上限、调试视图、失效日志。 */
    val rtVoxelTrustRadius: Float,
    val rtHzb: Boolean,
    val rtHistoryFrames: Int,
    val rtDebugView: Int,
    val rtInvalidationLog: Boolean,

    /** `[materials]`：是否读 labPBR 图，以及直接读的包目录（空 = 用启用的资源包）。 */
    val materialsEnabled: Boolean,
    val materialsPackPath: String,
    val materialsParallaxDepth: Float,

    /** 光影包名（空 = 无光影）；着色器入口按它解析路径。 */
    val shaderpack: String,
    )

    @Volatile
    private var snapshot: Snapshot? = null

    @Volatile
    private var pushed: String? = null

    /** The capability face is read once per process: it cannot change without a device recreate. */
    private var capsLogged = false

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
    val rtBackend: Int get() = snapshot().rtBackend

    /** `[rt] mode` —— 光追档位。`OFF` 时整条链不跑。 */
    val rtMode: Int get() = snapshot().rtMode

    /** `[rt] denoise` —— 降噪与超分走哪条轨道（见 `D3D12RtPipelinePlan`）。 */
    val rtDenoise: RtDenoise get() = RtDenoise.entries[snapshot().rtDenoise]

    /** `[rt] renderScale` —— 光追渲染分辨率占显示分辨率的百分比（25–100）。 */
    val rtRenderScale: Int get() = snapshot().rtRenderScale
    data class RtBuildBudget(val cpuMicros: Int, val gpuMicros: Int, val triangles: Int)

    val rtBuildBudget: RtBuildBudget get() = snapshot().let { settings ->
        RtBuildBudget(settings.rtBuildCpuMicros, settings.rtBuildGpuMicros, settings.rtBuildTriangles)
    }

    val rtExposureMode: RtExposureMode get() = snapshot().rtExposureMode
    val rtExposureEv: Float get() = snapshot().rtExposureEv
    val rtExposureSpeed: Float get() = snapshot().rtExposureSpeed

    /**
     * 光追是否真的要出图：后端不是 `AUTO` 且档位不是 `OFF`。
     *
     * 两个条件缺一不可——档位是光影包自己声明的开关（`configs.json` 的 `mode`），后端是选哪条实现；
     * 只看后端会让「档位 OFF」形同虚设，只看档位又区分不出无光影。
     */
    /** Compute path traced against the acceleration structure instead of the mirrored AABB. */

    val rtEnabled: Boolean
        get() = shaderpack.isNotEmpty() && rtMode != RtMode.OFF.ordinal &&
            hardwareRtSupported()

    private var hardwareAdapter: Any? = null
    private var hardwareRtAvailable: Boolean? = null

    private fun hardwareRtSupported(): Boolean {
        val adapter = D3D12AdapterInfo.current
        if (hardwareAdapter !== adapter) {
            hardwareAdapter = adapter
            hardwareRtAvailable = null
        }
        hardwareRtAvailable?.let { return it }
        val caps = MCDR_ABI.D3D12QueryRtCaps() ?: return false
        val available = caps.inlineRayQuerySupported
        hardwareRtAvailable = available
        if (!available) IllusionLogger.warnMessage("rt: DXR 1.1 unavailable, tracing disabled")
        return available
    }

    /** `[rt] debugView` —— 光追调试视图（把 G-Buffer 的哪一张铺到主目标上）。 */
    val rtDebugView: Int get() = snapshot().rtDebugView

    val rtInvalidationLog: Boolean get() = snapshot().rtInvalidationLog

    /** `[materials] enabled` —— 是否把 `_s` / `_n` 材质图读进材质表。 */
    val materialsEnabled: Boolean get() = snapshot().materialsEnabled

    /** `[materials] packPath` —— 直接读的材质包目录（空 = 用游戏里启用的资源包）。 */
    val materialsPackPath: String get() = snapshot().materialsPackPath
    val materialsParallaxDepth: Float get() = snapshot().materialsParallaxDepth

    /** `[shaderpack] active` —— 当前光影包名（空 = 无光影，光追保持关闭）。 */
    val shaderpack: String get() = snapshot().shaderpack

    val meshTerrain: Boolean get() = snapshot().meshTerrain

    /** `[mesh] terrainCull` —— 用 AS 剔除地形 meshlet（依赖 `[mesh] terrain`）。 */
    val meshTerrainCull: Boolean get() = snapshot().meshTerrainCull

    val dumpHlsl: Boolean get() = snapshot().dumpHlsl

    /** `[debug] shaderConverter` — convert every shader with the standalone DLL as a shadow of the SPIRV-Cross chain. */
    val shaderConverter: Boolean get() = snapshot().shaderConverter

    /** `[debug] shaderConverterDll` — `illusion_shader.dll` location, relative to the game directory. */
    val shaderConverterDll: String get() = snapshot().shaderConverterDll

    /** `[debug] shaderConverterPrimary` — render with the converter output where the bindings match. */
    val shaderConverterPrimary: Boolean get() = snapshot().shaderConverterPrimary

    /** `[debug] shaderConverterPipelines` — comma-separated pipeline id fragments allowed to use it. */
    val shaderConverterPipelines: String get() = snapshot().shaderConverterPipelines

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
        if (!capsLogged) {
            MCDR_ABI.D3D12QueryRtCaps()?.let { caps ->
                capsLogged = true
                IllusionLogger.infoMessage(
                    "RT caps: raytracing=${caps.raytracingTier} mesh=${caps.meshShaderTier} " +
                        "wg=${caps.workGraphsTier}(${if (caps.options21Queried) "queried" else "unqueried"}) " +
                        "vrs=${caps.shadingRateTier} sm=0x${caps.shaderModel.toString(16)} " +
                        "ser=${caps.serActuallyReorders} dxc=${caps.dxcAvailable} " +
                        "sched=${caps.preferredBackend} note=${caps.note}"
                )
                IllusionLogger.infoMessage(
                    "RT config: backend=${s.rtBackend} mode=${s.rtMode} profile=${s.rtQualityProfile} " +
                        "spp=${s.rtSpp} bounces=${s.rtMaxBounces} sampling=${s.rtSamplingMode} " +
                        "hzb=${s.rtHzb} history=${s.rtHistoryFrames} " +
"view=${s.rtDebugView} " +
                        "invlog=${s.rtInvalidationLog} trustRadius=${s.rtVoxelTrustRadius} " +
                        "pack=${s.shaderpack.ifEmpty { "none" }}"
                )
            }
        }
        if (wanted == pushed) return
        pushed = wanted
        // 调试层与 profiler 只在设备创建时读一次：晚于设备创建再推就等于没推，日志要能看出这件事。
        // 调试层走专用 ABI（`mcdr_dx12SetDebugLayer`）——JVM 侧的 _putenv_s 不保证对 native 的
        // getenv() 可见，之前正是这条路径让 `[debug] debugLayer` 静默失效。
        if (s.debugLayer) {
            IllusionLogger.infoMessage(
                "settings: [debug] debugLayer requested, it attaches if the device is created after this point")
        }
        MCDR_ABI.D3D12SetDebugLayer(s.debugLayer)
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
            shaderConverter = config(false) { IllusionConfig.SHADER_CONVERTER.get() },
            shaderConverterDll = config("dxrmc/illusion_shader.dll") {
                IllusionConfig.SHADER_CONVERTER_DLL.get()
            },
            shaderConverterPrimary = config(false) { IllusionConfig.SHADER_CONVERTER_PRIMARY.get() },
            shaderConverterPipelines = config("") { IllusionConfig.SHADER_CONVERTER_PIPELINES.get() },
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
            rtBackend = config(RtBackend.AUTO) { IllusionConfig.RAYTRACING_BACKEND.get() }.ordinal,
            rtQualityProfile = config(RtQualityProfile.BALANCED) {
                IllusionConfig.RAYTRACING_QUALITY_PROFILE.get()
            }.ordinal,
            rtDenoise = config(RtDenoise.OFF) { IllusionConfig.RAYTRACING_DENOISE.get() }.ordinal,
            rtRenderScale = config(100) { IllusionConfig.RAYTRACING_RENDER_SCALE.get() },
            rtBuildCpuMicros = config(3000) { IllusionConfig.RAYTRACING_BUILD_CPU_MICROS.get() },
            rtBuildGpuMicros = config(1000) { IllusionConfig.RAYTRACING_BUILD_GPU_MICROS.get() },
            rtBuildTriangles = config(65536) { IllusionConfig.RAYTRACING_BUILD_TRIANGLES.get() },
            rtExposureMode = config(RtExposureMode.MANUAL) { IllusionConfig.RAYTRACING_EXPOSURE_MODE.get() },
            rtExposureEv = config(0.0) { IllusionConfig.RAYTRACING_EXPOSURE_EV.get() }.toFloat(),
            rtExposureSpeed = config(1.5) { IllusionConfig.RAYTRACING_EXPOSURE_SPEED.get() }.toFloat(),
            rtSpp = config(1) { IllusionConfig.RAYTRACING_SPP.get() },
            rtMaxBounces = config(2) { IllusionConfig.RAYTRACING_MAX_BOUNCES.get() },
            rtSamplingMode = config(RtSamplingMode.BSDF) {
                IllusionConfig.RAYTRACING_SAMPLING_MODE.get()
            }.ordinal,
            rtVoxelTrustRadius = config(8.0) { IllusionConfig.RAYTRACING_VOXEL_TRUST_RADIUS.get() }
                .toFloat(),
            rtHzb = config(false) { IllusionConfig.RAYTRACING_HZB.get() },
            rtHistoryFrames = config(16) { IllusionConfig.RAYTRACING_HISTORY_FRAMES.get() },
            rtDebugView = config(RtDebugView.NONE) {
                IllusionConfig.RAYTRACING_DEBUG_VIEW.get()
            }.ordinal,
            rtInvalidationLog = config(false) { IllusionConfig.RAYTRACING_INVALIDATION_LOG.get() },
            materialsEnabled = config(true) { IllusionConfig.MATERIALS_ENABLED.get() },
            materialsPackPath = config("") { IllusionConfig.MATERIALS_PACK_PATH.get().trim() },
            materialsParallaxDepth = config(0.25f) { IllusionConfig.MATERIALS_PARALLAX_DEPTH.get().toFloat() },
            shaderpack = config("B-DXR") { IllusionConfig.SHADERPACK_ACTIVE.get().trim() },
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

