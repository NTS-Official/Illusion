package dev.nts.expdxr.d3d12

import dev.nts.expdxr.CommandBundleMode
import dev.nts.expdxr.DlssImplementation
import dev.nts.expdxr.IllusionConfig
import dev.nts.expdxr.MeshDiagnostics
import dev.nts.expdxr.d3d12.extern.MCDR_ABI
import dev.nts.expdxr.misc.IllusionLogger
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * The one place a renderer switch is resolved: NeoForge config first, legacy source second.
 *
 * <p>Every switch used to be an environment variable, a JVM `-D` property or a marker file in
 * the working directory (`DXR_MC_GL_CONTEXT`, `gl_context.flag`, `DX12_ASYNC`,
 * `DX12_BUNDLE_MODE`, `dxrmc.temporal`, ...). Those are now options in
 * `config/dxr_mc-client.toml` ([IllusionConfig]); the old source still works, but only as a
 * deprecated escape hatch, and only when the config left the option at its default. A one-time
 * warning says which option was overridden that way, so it can be migrated.
 *
 * <p>Resolution is cached: the renderer reads these on per-frame paths, and a config lookup is
 * not free. The cache is dropped by [reload] from the config load/reload events, and a snapshot
 * built *before* the config file was read is never cached (the renderer starts before NeoForge
 * loads the toml).
 *
 * <p>Threading: the snapshot is immutable and published through a `@Volatile` field, so the
 * render thread and the recording workers may read it concurrently.
 */
object D3D12Settings {
    /** Env var that used to select the DLSS backend. */
    private const val LEGACY_DLSS_IMPL = "MCDR_DLSS_IMPL"

    /** Env var that used to enable Streamline wholesale. */
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
        val reflexFrameLimitUs: Int,
        val meshTerrain: Boolean,
        val meshTerrainCull: Boolean,
        val meshTerrainCullBounds: Boolean,
        val dumpHlsl: Boolean,
        val debugLayer: Boolean,
        val profiling: Boolean,
        val skipGlSwap: Boolean,
        val asyncRenderThread: Boolean,
        val temporalChain: Boolean,
        val temporalJitter: Boolean,
        val selfTestSurface: Boolean,
        val selfTestMesh: Boolean,
        val failDevice: Boolean,
        val meshDiagnostics: MeshDiagnostics,
        val noCrashPopup: Boolean,
        val nativeLogFile: String,
    )

    @Volatile
    private var snapshot: Snapshot? = null

    @Volatile
    private var pushed: String? = null

    private val warned: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Keep a real GL context alive instead of asking GLFW for `GLFW_NO_API`. */
    val glContext: Boolean get() = snapshot().glContext

    /** Record command lists on the worker pool. */
    val asyncCommandLists: Boolean get() = snapshot().asyncCommandLists

    /** Worker threads for [asyncCommandLists]. */
    val asyncWorkers: Int get() = snapshot().asyncWorkers

    /** Serial vs parallel command-bundle recording. */
    val commandBundles: CommandBundleMode get() = snapshot().commandBundles

    /** Post-sharpen strength for DLAA/DLSS. */
    val sharpenAmount: Float get() = snapshot().sharpenAmount

    /** Post-sharpen strength for TAA. */
    val taaSharpenAmount: Float get() = snapshot().taaSharpenAmount

    /** NGX or Streamline. */
    val dlssImplementation: DlssImplementation get() = snapshot().dlssImplementation

    /** The JVM owns DLSS's output texture. */
    val dlssOutputHandover: Boolean get() = snapshot().dlssOutputHandover
    /** `[dlss] reflexFrameLimitUs` — Reflex 自己的限帧器（微秒/帧，0 = 关）。 */
    val reflexFrameLimitUs: Int get() = snapshot().reflexFrameLimitUs
    /** `[mesh] terrain` — 地形走 mesh 管线。 */
    val meshTerrain: Boolean get() = snapshot().meshTerrain

    /** P140：`[mesh] terrainCull` —— 用 AS 剔除地形 meshlet（依赖 `[mesh] terrain`）。*/
    val meshTerrainCull: Boolean get() = snapshot().meshTerrainCull

    /**
     * P141：`[mesh] terrainCullBounds` —— 让 AS 读 compute pre-pass 生成的包围球表（boundsMode 0）。
     * 默认关：表内容还没验证过（续四实测开启后大片地形在特定朝向下闪）。
     */
    val meshTerrainCullBounds: Boolean get() = snapshot().meshTerrainCullBounds
    /** `[debug] dumpHlsl` — 把翻译后的 HLSL 落盘（`dxrmc/hlsl/`）。 */
    val dumpHlsl: Boolean get() = snapshot().dumpHlsl

    /** Attach the D3D12 debug layer. */
    val debugLayer: Boolean get() = snapshot().debugLayer

    /** Frame/batch timing instrumentation. */
    val profiling: Boolean get() = snapshot().profiling

    /** Turn the leftover GL surface's `present()` into a no-op. */
    val skipGlSwap: Boolean get() = snapshot().skipGlSwap

    /** Run the dedicated render thread. */
    val asyncRenderThread: Boolean get() = snapshot().asyncRenderThread

    /** Force the temporal chain on with no AA selected (developer switch). */
    val temporalChain: Boolean get() = snapshot().temporalChain

    /** Inject the sub-pixel jitter. */
    val temporalJitter: Boolean get() = snapshot().temporalJitter

    /** Run the swap-chain self-test. */
    val selfTestSurface: Boolean get() = snapshot().selfTestSurface

    /** Run the mesh-shader self-test. */
    val selfTestMesh: Boolean get() = snapshot().selfTestMesh

    /** Fail device creation on purpose. */
    val failDevice: Boolean get() = snapshot().failDevice

    /** The mesh read-back isolation test. */
    val meshDiagnostics: MeshDiagnostics get() = snapshot().meshDiagnostics

    /** Suppress the fatal-error dialog. */
    val noCrashPopup: Boolean get() = snapshot().noCrashPopup

    /** Mirror the native log into this file (empty = console only). */
    val nativeLogFile: String get() = snapshot().nativeLogFile

    /** Drop the cached snapshot; the next read re-resolves every option. */
    fun reload() {
        snapshot = null
        pushed = null
    }
    /**
     * Hand the native-side switches to the DLL.
     *
     * <p>The native layer reads a handful of values from the process environment at device
     * creation. Rather than growing an export per value, [MCDR_ABI.D3D12SetEnvironment] sets the
     * variable inside the process right before that, so the config is the only place a user has
     * to look while the native code keeps its plain `getenv` reads. Idempotent, and safe to call
     * before the config file has been read (the defaults are the shipped behaviour).
     */
    fun pushToNative() {
        if (!MCDR_ABI.isAvailable()) return
        val s = snapshot()
        val wanted = buildString {
            append(s.debugLayer).append('|')
            append(s.profiling).append('|')
            append(s.dlssImplementation).append('|')
            append(s.reflexFrameLimitUs).append('|')
            append(s.nativeLogFile)
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
        // P131：Reflex 的限帧器没有环境变量入口 —— 直接走 ABI，配置是唯一来源。
        MCDR_ABI.D3D12SetReflexFrameLimit(s.reflexFrameLimitUs)
    }

    private fun snapshot(): Snapshot = snapshot ?: build()

    private fun build(): Snapshot {
        var configAvailable = true

        fun <T> config(fallback: T, read: () -> T): T = try {
            read()
        } catch (t: Throwable) {
            // Called before NeoForge loaded the toml (mod construction, DLL init): use the
            // shipped default and do not cache the result.
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
            meshTerrain = config(false) { IllusionConfig.MESH_TERRAIN.get() },
            meshTerrainCull = config(false) { IllusionConfig.MESH_TERRAIN_CULL.get() },
            meshTerrainCullBounds = config(false) { IllusionConfig.MESH_TERRAIN_CULL_BOUNDS.get() },
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
        )

        if (configAvailable) {
            snapshot = s
        }
        return s
    }

    /**
     * True when the pre-config source asked for [key]; warns once so the stale script or JVM
     * argument can be migrated to `config/dxr_mc-client.toml`.
     */
    private inline fun legacy(key: String, check: () -> Boolean): Boolean {
        if (!check()) return false
        if (warned.add(key)) {
            IllusionLogger.warnMessage(
                "[dxrmc] `$key` is enabled by its old environment variable / system property / " +
                    "marker file. Move it to config/dxr_mc-client.toml - the old source is still " +
                    "honoured only so existing scripts keep working.",
            )
        }
        return true
    }
}