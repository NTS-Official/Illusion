package dev.nts.expdxr

import dev.nts.expdxr.misc.IllusionLogLevel
import dev.nts.expdxr.misc.IllusionLogger.i18n_LOG_KEY
import net.neoforged.neoforge.common.ModConfigSpec

/** `[renderer] commandBundles` — how one multi-draw batch is split across command bundles. */
enum class CommandBundleMode {
    /** Pick per batch: one bundle for small batches, parallel recording for large ones. */
    AUTO,

    /** Always record one bundle (single command list, maximum compatibility). */
    SERIAL,

    /** Always split across the worker pool (lowest CPU cost when there are spare cores). */
    PARALLEL,
}

/** `[dlss] implementation` — which upscaler backend the native DLL drives. */
enum class DlssImplementation {
    /** NVIDIA NGX (the default, in-process DLSS). */
    NGX,

    /** Streamline (sl.dlss — the newer, layer-based path). */
    STREAMLINE,
}

/**
 * `[renderer] hdr` — native HDR (scRGB) presentation.
 *
 * AUTO follows the display's advanced-colour state (the same DXGI output query Windows
 * itself uses), FORCE_ON is for testing the HDR path on an SDR display, FORCE_OFF keeps the
 * 8-bit SDR swapchain even when the display is in HDR mode.
 */
enum class HdrMode {
    /** Follow the display: HDR when advanced colour is on, SDR otherwise. */
    AUTO,

    /** Always create the scRGB (R16G16B16A16_FLOAT) swapchain. */
    FORCE_ON,

    /** Always create the 8-bit SDR swapchain. */
    FORCE_OFF,
}

/** `[debug] meshDiagnostics` — one-shot isolation test for "texture write vs read-back". */
enum class MeshDiagnostics {
    /** Off (default). */
    NONE,

    /** Clear the target through a mesh dispatch, then read it back. */
    CLEAR,

    /** Run the full mesh pass, then read the target back. */
    PASS,
}

/**
 * Everything the player can change lives here, in `config/dxr_mc-client.toml`.
 *
 * <p>The switches that used to be environment variables, JVM `-D` properties or a marker file in
 * the working directory (`DXR_MC_GL_CONTEXT`, `gl_context.flag`, `DX12_ASYNC`,
 * `DX12_BUNDLE_MODE`, `dxrmc.selfTestMesh`, `dxrmc.temporal`, …) now have an option here. Those
 * old sources still work as a deprecated escape hatch for scripts, but the config is the
 * documented way — see [dev.nts.expdxr.d3d12.D3D12Settings], which resolves every switch.
 *
 * <p>Values are read once per session (a few of them say `gameRestart` because the renderer
 * cannot change them later); nothing here is per-frame.
 */
object IllusionConfig {
    private val BUILDER = ModConfigSpec.Builder()

    /** `[logging] logLevel` — see [IllusionLogLevel]. */
    val LOG_LEVEL: ModConfigSpec.EnumValue<IllusionLogLevel>

    /** `[logging] nativeVerbose` — force the DLL to its DEBUG level on its own. */
    val NATIVE_VERBOSE: ModConfigSpec.BooleanValue

    /** `[logging] nativeLogFile` — also mirror the native log into this file (`""` = off). */
    val NATIVE_LOG_FILE: ModConfigSpec.ConfigValue<String>

    /** `[renderer] glContext` — the escape hatch that keeps a real OpenGL context alive. */
    val GL_CONTEXT: ModConfigSpec.BooleanValue

    /** `[renderer] asyncCommandLists` — record command lists on the worker pool. */
    val ASYNC_COMMAND_LISTS: ModConfigSpec.BooleanValue

    /** `[renderer] asyncWorkers` — worker count for [ASYNC_COMMAND_LISTS]. */
    val ASYNC_WORKERS: ModConfigSpec.IntValue

    /** `[renderer] commandBundles` — serial vs parallel bundle recording. */
    val COMMAND_BUNDLES: ModConfigSpec.EnumValue<CommandBundleMode>

    /** `[renderer] hdr` — native HDR (scRGB) presentation mode. */
    val HDR: ModConfigSpec.EnumValue<HdrMode>

    /** `[renderer] sharpenAmount` — post-sharpen strength for DLAA/DLSS. */
    val SHARPEN_AMOUNT: ModConfigSpec.DoubleValue

    /** `[renderer] taaSharpenAmount` — post-sharpen strength for TAA. */
    val TAA_SHARPEN_AMOUNT: ModConfigSpec.DoubleValue

    /** `[dlss] implementation` — NGX or Streamline. */
    val DLSS_IMPLEMENTATION: ModConfigSpec.EnumValue<DlssImplementation>

    /** `[dlss] outputHandover` — the JVM owns DLSS's output texture. */
    val DLSS_OUTPUT_HANDOVER: ModConfigSpec.BooleanValue

    /** `[dlss] reflexFrameLimitUs` — Reflex's own frame-rate limiter, microseconds per frame. */
    val DLSS_REFLEX_FRAME_LIMIT_US: ModConfigSpec.IntValue

    /**
     * `[debug] pixCapture` — load Microsoft PIX's GPU capture layer at startup.
     *
     * Marked with [ModConfigSpec.Builder.gameRestart]: PIX's capture layer hooks Direct3D 12
     * from inside its own DLL, so it can only attach to devices created *after* it is loaded.
     */
    val PIX_CAPTURE: ModConfigSpec.BooleanValue

    /**
     * `[debug] pixTimingCapture` — arm the key binding that drives a PIX timing capture.
     *
     * Press it in game and the process itself calls PIXBeginCapture, so the file lands under
     * `run/pix/` with a name and a length we choose; PIX's own continuous recording never reaches
     * the disk, and the capture PIX starts itself writes to `%TEMP%`. No restart is involved: the
     * native side loads the timing runtime lazily on the first press, and the frame budget is read
     * at the moment of the press.
     */
    val PIX_TIMING_CAPTURE: ModConfigSpec.BooleanValue

    /** `[debug] pixCaptureFrames` — frames recorded by one key-press capture. */
    val PIX_CAPTURE_FRAMES: ModConfigSpec.IntValue

    /**
     * `[debug] pixDirectory` — where PIX is installed (its directory, or one of its DLLs).
     *
     * Empty means "look where PIX normally installs itself". There is deliberately no environment
     * variable for this: a switch that only exists as an env var is a switch nobody finds.
     */
    val PIX_DIRECTORY: ModConfigSpec.ConfigValue<String>

    /** `[debug] debugLayer` — create the device with the D3D12 debug layer. */
    val DEBUG_LAYER: ModConfigSpec.BooleanValue

    /** `[debug] profiling` — per-frame/per-batch timing instrumentation (`DX12_PROF`). */
    val PROFILING: ModConfigSpec.BooleanValue

    /** `[debug] skipGlSwap` — make the GL surface's `present()` a no-op. */
    val SKIP_GL_SWAP: ModConfigSpec.BooleanValue

    /** `[debug] asyncRenderThread` — keep the dedicated render thread (`false` = diagnose it away). */
    val ASYNC_RENDER_THREAD: ModConfigSpec.BooleanValue

    /** `[debug] temporalChain` — force the experimental temporal chain on outside AA selection. */
    val TEMPORAL_CHAIN: ModConfigSpec.BooleanValue

    /** `[debug] temporalJitter` — inject sub-pixel jitter into the level projection. */
    val TEMPORAL_JITTER: ModConfigSpec.BooleanValue

    /** `[debug] selfTestSurface` — run the swap-chain self-test (steals the RTSS OSD binding). */
    val SELF_TEST_SURFACE: ModConfigSpec.BooleanValue

    /** `[debug] selfTestMesh` — run the mesh-shader self-test (compiles HLSL with DXC). */
    val SELF_TEST_MESH: ModConfigSpec.BooleanValue

    /** `[debug] failDevice` — make device creation fail on purpose (P73 policy testing). */
    val FAIL_DEVICE: ModConfigSpec.BooleanValue

    /** `[debug] meshDiagnostics` — the one-shot mesh read-back isolation test. */
    val MESH_DIAGNOSTICS: ModConfigSpec.EnumValue<MeshDiagnostics>

    /** `[debug] noCrashPopup` — suppress the fatal-error dialog (automation). */
    val NO_CRASH_POPUP: ModConfigSpec.BooleanValue

    /** `[debug] dumpHlsl` — write each translated HLSL pair into `dxrmc/hlsl/` for inspection. */
    val DUMP_HLSL: ModConfigSpec.BooleanValue

    /** `[mesh] terrain` — drive terrain through the mesh-shader path instead of indexed draws. */
    val MESH_TERRAIN: ModConfigSpec.BooleanValue

    /** `[mesh] terrainCull` — cull terrain meshlets with an amplification shader (P140). */
    val MESH_TERRAIN_CULL: ModConfigSpec.BooleanValue

    /** `[mesh] terrainCullBounds` — feed the cull shader a compute-built bounding-sphere table (P141). */
    val MESH_TERRAIN_CULL_BOUNDS: ModConfigSpec.BooleanValue

    /** `[mesh] terrainCone` — drop whole back-facing meshlets with a normal-cone test in the AS (P141 B). */
    val MESH_TERRAIN_CONE: ModConfigSpec.BooleanValue

    init {
        BUILDER.comment(
            "How much this mod prints.",
            "ERROR (default) keeps the console usable: only errors are printed — by the",
            "renderer and by the native DLL.",
            "WARN adds warnings; INFO adds per-load diagnostics; DEBUG/TRACE add the",
            "high-frequency dumps (shader sources, PSO/pipeline dumps, per-frame tracing).",
            "The environment variable DX12_LOG_VERBOSE=1 still forces DEBUG for one run."
        ).translation(i18n_LOG_KEY).push("logging")

        LOG_LEVEL = BUILDER
            .comment("Level for everything this mod prints, Java and native alike.")
            .translation("$i18n_LOG_KEY.logLevel")
            .defineEnum("logLevel", IllusionLogLevel.ERROR)

        NATIVE_VERBOSE = BUILDER
            .comment(
                "Force the native DLL to DEBUG regardless of logLevel.",
                "Kept separate because the DLL is by far the loudest writer, and bisecting it",
                "on its own (without turning the renderer logs back on) is the common case."
            )
            .translation("$i18n_LOG_KEY.nativeVerbose")
            .define("nativeVerbose", false)

        NATIVE_LOG_FILE = BUILDER
            .comment(
                "Also write every native log line into this file (empty = console only).",
                "Relative paths resolve against the game directory. Useful when the console",
                "scrolls too fast, or when the interesting part is the gap before the first frame."
            )
            .translation("$i18n_LOG_KEY.nativeLogFile")
            .define("nativeLogFile", "")

        BUILDER.pop()

        BUILDER.comment(
            "How the Direct3D 12 renderer runs.",
            "Read once, when the renderer starts; changing a value needs a game restart."
        ).translation("dxr_mc.configuration.renderer").push("renderer")

        GL_CONTEXT = BUILDER
            .comment(
                "Keep a real OpenGL context alive in this process.",
                "OFF (default) asks GLFW for GLFW_NO_API and never creates a GL context. That is",
                "what makes RTSS/Afterburner draw its *Direct3D 12* overlay: with a GL context",
                "present they classify the process as OpenGL and the overlay never appears.",
                "Turn it ON only as an escape hatch, when a feature (or another mod) really needs",
                "a live GL context — the DXRMC renderer itself never draws through GL."
            )
            .translation("dxr_mc.configuration.renderer.glContext")
            .gameRestart()
            .define("glContext", false)

        ASYNC_COMMAND_LISTS = BUILDER
            .comment(
                "Record command lists in parallel on a worker pool.",
                "OFF forces single-threaded recording, which is slower on a busy scene but",
                "removes any recording-order question from the picture (diagnostic)."
            )
            .translation("dxr_mc.configuration.renderer.asyncCommandLists")
            .gameRestart()
            .define("asyncCommandLists", true)

        ASYNC_WORKERS = BUILDER
            .comment("Worker threads for async recording (1-8).")
            .translation("dxr_mc.configuration.renderer.asyncWorkers")
            .gameRestart()
            .defineInRange("asyncWorkers", 8, 1, 8)

        COMMAND_BUNDLES = BUILDER
            .comment(
                "How one multi-draw batch is split across command bundles.",
                "AUTO picks per batch; SERIAL always records a single bundle (maximum",
                "compatibility, more CPU); PARALLEL always splits across the worker pool."
            )
            .translation("dxr_mc.configuration.renderer.commandBundles")
            .gameRestart()
            .defineEnum("commandBundles", CommandBundleMode.AUTO)

        HDR = BUILDER
            .comment(
                "Native HDR presentation (scRGB swapchain).",
                "AUTO follows the display's Windows advanced-colour state. FORCE_ON creates the",
                "HDR swapchain even on an SDR display (for testing the path), FORCE_OFF keeps the",
                "8-bit SDR swapchain. Takes effect on the next swapchain creation (resize or",
                "fullscreen toggle), so a restart is not required."
            )
            .translation("dxr_mc.configuration.renderer.hdr")
            .defineEnum("hdr", HdrMode.AUTO)

        SHARPEN_AMOUNT = BUILDER
            .comment(
                "Post-sharpen strength after DLAA/DLSS upscaling, 0.0-1.0.",
                "0 disables the pass; the shipped default (0.67) is what play-testing settled on."
            )
            .translation("dxr_mc.configuration.renderer.sharpenAmount")
            .defineInRange("sharpenAmount", 0.67, 0.0, 1.0)

        TAA_SHARPEN_AMOUNT = BUILDER
            .comment(
                "Post-sharpen strength after TAA resolve, 0.0-1.0.",
                "TAA's own resolve is softer than DLAA's, so this one defaults higher (0.816)."
            )
            .translation("dxr_mc.configuration.renderer.taaSharpenAmount")
            .defineInRange("taaSharpenAmount", 0.816, 0.0, 1.0)

        BUILDER.pop()

        BUILDER.comment(
            "Which DLSS backend the native DLL drives.",
            "Read once, before the first DLSS evaluate; changing it needs a game restart."
        ).translation("dxr_mc.configuration.dlss").push("dlss")

        DLSS_IMPLEMENTATION = BUILDER
            .comment(
                "NGX is the in-process DLSS path (default). STREAMLINE drives sl.dlss instead.",
                "STREAMLINE needs the Streamline runtime next to MCDR.dll; when it is missing the",
                "DLL falls back to NGX and says so in the log."
            )
            .translation("dxr_mc.configuration.dlss.implementation")
            .gameRestart()
            .defineEnum("implementation", DlssImplementation.NGX)

        DLSS_OUTPUT_HANDOVER = BUILDER
            .comment(
                "Let the JVM own DLSS's output texture (the shipped path).",
                "OFF makes the native DLL use its private output texture instead. That path is",
                "kept as a fallback for diagnosis only: on some drivers NGX cannot write into it,",
                "which shows up as a black world."
            )
            .translation("dxr_mc.configuration.dlss.outputHandover")
            .define("outputHandover", true)

        DLSS_REFLEX_FRAME_LIMIT_US = BUILDER
            .comment(
                "Hand Reflex its own frame-rate limiter, in microseconds per frame (0 = off).",
                "144 fps is 6944; the limiter runs inside sl.reflex, so it stays accurate even when",
                "the CPU is far ahead. Independent of the Reflex mode in the video settings."
            )
            .translation("dxr_mc.configuration.dlss.reflexFrameLimitUs")
            .defineInRange("reflexFrameLimitUs", 0, 0, 100_000)

        BUILDER.pop()

        BUILDER.comment(
            "Terrain mesh-shader path (the mesh pipeline experiment).",
            "OFF keeps the indexed-draw path; ON lets the backend build the mesh PSO, fill the",
            "segment table and dispatch, then compare the frame against the indexed path."
        ).translation("dxr_mc.configuration.mesh").push("mesh")

        MESH_TERRAIN = BUILDER
            .comment(
                "Drive the solid terrain pipeline through DispatchMesh instead of DrawIndexed.",
                "Requires a device with mesh shaders (12_2 / tier 1); the log says which side of",
                "the gate the run landed on. The indexed path stays live as the reference frame."
            )
            .translation("dxr_mc.configuration.mesh.terrain")
            .define("terrain", false)

        MESH_TERRAIN_CULL = BUILDER
            .comment(
                "Cull terrain meshlets in an amplification shader before the mesh shader runs (P140).",
                "OFF: every meshlet is dispatched. ON: each amplification-shader thread tests one",
                "meshlet against four side view-frustum planes and only surviving meshlets reach the",
                "mesh shader. Needs [mesh] terrain = true; ignored when the device has no mesh tier.",
                "The first version computes the per-meshlet bounding sphere inside the shader, so it",
                "costs one extra pass over each meshlet's vertices."
            )
            .translation("dxr_mc.configuration.mesh.terrain_cull")
            .define("terrainCull", false)

        MESH_TERRAIN_CULL_BOUNDS = BUILDER
            .comment(
                "Let the amplification shader read per-meshlet bounding spheres from a table",
                "instead of computing them (P141, experimental).",
                "OFF: every amplification-shader thread walks its meshlet's vertices and builds",
                "an AABB (boundsMode 1). ON: a compute dispatch right before the terrain pass",
                "fills a sphere table and the shader only reads 16 B per meshlet (boundsMode 0).",
                "Needs [mesh] terrainCull = true.",
                "Default OFF on purpose: with the table in use (P141 continuation 4) large",
                "batches flicker when the camera faces some directions - the table content is",
                "not validated yet, so the shader-computed sphere stays the safe path."
            )
            .translation("dxr_mc.configuration.mesh.terrain_cull_bounds")
            .define("terrainCullBounds", false)

        MESH_TERRAIN_CONE = BUILDER
            .comment(
                "Cull whole back-facing meshlets with a normal cone built in the amplification",
                "shader (P141 stage B, experimental).",
                "Each thread derives the meshlet's normal cone from its triangles; when the whole",
                "cone points away from the camera the meshlet is dropped, so its vertices are never",
                "decoded by the mesh shader (up to 126 vertex fetches saved per culled meshlet).",
                "Only applied to pipelines whose rasterizer state also culls back faces, and only on",
                "the shader-computed bounding-sphere path (the table path has no vertex data).",
                "Needs [mesh] terrainCull = true.",
                "Default ON since P141 continuation 9: the winding sign was verified on hardware",
                "(a wrong sign culls front faces and terrain shows gaps)."
            )
            .translation("dxr_mc.configuration.mesh.terrain_cone")
            .define("terrainCone", true)

        BUILDER.pop()

        BUILDER.comment(
            "Diagnostics that have to be decided before the renderer starts.",
            "These are read once, when the config loads; nothing here applies mid-session."
        ).translation("dxr_mc.configuration.debug").push("debug")

        PIX_CAPTURE = BUILDER
            .comment(
                "Load Microsoft PIX's GPU capture layer (WinPixGpuCapturer.dll) at startup, so",
                "PIX can take GPU captures of this process.",
                "The layer has to be loaded before the first Direct3D 12 device is created, hence",
                "the game restart. PIX must be running to actually save a capture."
            )
            .translation("dxr_mc.configuration.debug.pixCapture")
            .gameRestart()
            .define("pixCapture", false)

        PIX_DIRECTORY = BUILDER
            .comment(
                "Where PIX lives: the install directory (holding WinPixGpuCapturer.dll,",
                "WinPixTimingCapturer.dll and WinPixEventRuntime_OneCore.dll) or one of those",
                "DLLs. Leave it empty to use the default install locations (Program Files), which",
                "is right for a normal install; fill it in when PIX sits somewhere else."
            )
            .translation("dxr_mc.configuration.debug.pixDirectory")
            .gameRestart()
            .define("pixDirectory", "")

        PIX_TIMING_CAPTURE = BUILDER
            .comment(
                "Arm the PIX *timing* capture key (Options - Controls - DXRMC, default F9).",
                "Press it in game and PIX starts recording a capture into",
                "run/pix/dxrmc-capture-N.wpix; press it again to stop early, or let it end after",
                "[debug] pixCaptureFrames frames. Every press writes a new file, and the frame",
                "budget is read at the moment of the press - no restart needed either way.",
                "This key exists because PIX's own recording is not usable for that: its",
                "continuous counters recording stays inside the PIX session until saved, and the",
                "timing capture PIX starts itself writes to %TEMP% under a name PIX picks. Only",
                "PIXBeginCapture, called from inside the process, writes where we ask.",
                "A timing capture records through ETW sessions, so the signed-in user has to be",
                "allowed to create them; PIX documents putting the user in the built-in",
                "`Performance Log Users` group for that, and elevation is not needed."
            )
            .translation("dxr_mc.configuration.debug.pixTimingCapture")
            .define("pixTimingCapture", false)

        PIX_CAPTURE_FRAMES = BUILDER
            .comment(
                "How many frames one key-press capture records (read when the key is pressed).",
                "600 frames is about ten seconds at 60 fps, which is already a large file: a",
                "capture with the memory events on runs to hundreds of megabytes."
            )
            .translation("dxr_mc.configuration.debug.pixCaptureFrames")
            .defineInRange("pixCaptureFrames", 600, 60, 20000)

        DEBUG_LAYER = BUILDER
            .comment(
                "Create the device with the Direct3D 12 debug layer attached.",
                "Lets the SDK validate every call at a large frame-rate cost; ignored when the",
                "Agility SDK's D3D12Core is in use (the inbox validation layer would not match)."
            )
            .translation("dxr_mc.configuration.debug.debugLayer")
            .gameRestart()
            .define("debugLayer", false)

        PROFILING = BUILDER
            .comment(
                "Per-frame and per-batch timing instrumentation (the old DX12_PROF=1).",
                "Prints CPU milliseconds for list recording, PSO binds and pass submission. It",
                "costs a few timer reads per draw batch, so it is off by default."
            )
            .translation("dxr_mc.configuration.debug.profiling")
            .define("profiling", false)

        DUMP_HLSL = BUILDER
            .comment(
                "Write every translated HLSL pair into `dxrmc/hlsl/` next to the game.",
                "The translated shaders are the only view into what the SPIR-V cross-compiler emits,",
                "so this is the switch to flip when a stage misbehaves. Capped at 60 pairs."
            )
            .translation("dxr_mc.configuration.debug.dumpHlsl")
            .define("dumpHlsl", false)

        SKIP_GL_SWAP = BUILDER
            .comment(
                "Make the GL surface's present() a no-op.",
                "Only meaningful when glContext is ON: it stops the leftover GL surface from",
                "swapping a window it does not own. Harmless (and unused) with the default",
                "GLFW_NO_API window."
            )
            .translation("dxr_mc.configuration.debug.skipGlSwap")
            .define("skipGlSwap", false)

        ASYNC_RENDER_THREAD = BUILDER
            .comment(
                "Run the dedicated render thread (acquire/present/fence waits off the main thread).",
                "OFF is a diagnostic: it isolates the render thread from the recording thread when",
                "the two are suspected of racing over a shared heap."
            )
            .translation("dxr_mc.configuration.debug.asyncRenderThread")
            .gameRestart()
            .define("asyncRenderThread", true)

        TEMPORAL_CHAIN = BUILDER
            .comment(
                "Force the temporal chain on (jitter, projection injection, velocity attachment).",
                "Never needed for gameplay: selecting TAA/DLAA/DLSS turns the chain on by itself.",
                "This is the developer switch for inspecting the chain with no AA selected."
            )
            .translation("dxr_mc.configuration.debug.temporalChain")
            .define("temporalChain", false)

        TEMPORAL_JITTER = BUILDER
            .comment(
                "Inject the sub-pixel jitter into the level projection matrix.",
                "OFF keeps the rest of the temporal chain alive (matrix capture, velocity) while",
                "removing the jitter itself — the A/B that separates 'jitter is wrong' from",
                "'the matrices are wrong'."
            )
            .translation("dxr_mc.configuration.debug.temporalJitter")
            .define("temporalJitter", true)

        SELF_TEST_SURFACE = BUILDER
            .comment(
                "Create, present and destroy a throw-away swap chain on the real window.",
                "OFF by default: RTSS/Afterburner bind their overlay to the first presented swap",
                "chain, so a self-test chain would steal it from the game for the whole session."
            )
            .translation("dxr_mc.configuration.debug.selfTestSurface")
            .gameRestart()
            .define("selfTestSurface", false)

        SELF_TEST_MESH = BUILDER
            .comment(
                "Run the mesh-shader self-test (DXC compile, mesh PSO, DispatchMesh, read-back).",
                "Proves the capability chain works; it compiles HLSL on the spot, so it does not",
                "belong on the startup critical path."
            )
            .translation("dxr_mc.configuration.debug.selfTestMesh")
            .gameRestart()
            .define("selfTestMesh", false)

        FAIL_DEVICE = BUILDER
            .comment(
                "Make device creation fail on purpose.",
                "End-to-end test of the failure policy: no GL/Vulkan fallback, preferred graphics",
                "backend reset, and a crash report that says why. Not for playing."
            )
            .translation("dxr_mc.configuration.debug.failDevice")
            .gameRestart()
            .define("failDevice", false)

        MESH_DIAGNOSTICS = BUILDER
            .comment(
                "One-shot mesh read-back isolation test.",
                "CLEAR writes a flat colour through a mesh dispatch and reads it back; PASS runs",
                "the full mesh pass first. Tells 'the read-back path is broken' apart from 'the",
                "pass never landed on the texture'."
            )
            .translation("dxr_mc.configuration.debug.meshDiagnostics")
            .gameRestart()
            .defineEnum("meshDiagnostics", MeshDiagnostics.NONE)

        NO_CRASH_POPUP = BUILDER
            .comment(
                "Suppress the fatal-error dialog and only write the crash report.",
                "For automated runs (self-tests, regressions): the dialog is modal and would wait",
                "forever for a click nobody is there to make."
            )
            .translation("dxr_mc.configuration.debug.noCrashPopup")
            .define("noCrashPopup", false)

        BUILDER.pop()
    }

    val SPEC: ModConfigSpec = BUILDER.build()
}
