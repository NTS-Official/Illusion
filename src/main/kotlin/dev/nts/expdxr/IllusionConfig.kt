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

/** `[dlss] frameGeneration` — DLSS-G (frame generation). */
enum class DlssFrameGeneration {
    /** No generated frames. */
    OFF,

    /** Fixed multiplier (`frameGenerationFrames` per rendered frame). */
    ON,

    /** Dynamic multi-frame: the driver picks the count to hit the display rate (595.41+). */
    DYNAMIC,
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

/**
 * P117：光追可见性分层总开关。OFF 时不建 G-Buffer、不进光追分支（零行为变化）。
 * 非光追设备上即使设成别的值也会被 native 忽略，并在日志里说明原因。
 */
enum class RtMode {
    /** 关闭。 */
    OFF,

    /** 只做屏幕空间/光追 AO。 */
    AO,

    /** AO + 光追阴影。 */
    AO_SHADOWS,

    /** 全量：AO + 阴影 + 反射。 */
    FULL,
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
 * documented way — see [dev.nts.expdxr.misc.D3D12Settings], which resolves every switch.
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

    /**
     * `[logging] shutdownTrace`（P184）：关停路径的 native 进度标记
     * （`[P184] destroySurface/destroyDevice stage: …`，INFO 级）是否**无视 logLevel 恒定输出**。
     */
    val SHUTDOWN_TRACE: ModConfigSpec.BooleanValue

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

    /** P117：`[raytracing] mode` — 光追可见性分层总开关。 */
    val RAYTRACING_MODE: ModConfigSpec.EnumValue<RtMode>

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

    /** `[dlss] frameGeneration` — DLSS frame generation (DLSS-G). */
    val DLSS_FRAME_GENERATION: ModConfigSpec.EnumValue<DlssFrameGeneration>

    /** `[dlss] frameGenerationFrames` — generated frames per rendered frame (1-5). */
    val DLSS_FRAME_GENERATION_FRAMES: ModConfigSpec.IntValue

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

    /** `[debug] selfTestCompute` — run the compute self-test (P234: root UAV + root constants). */
    val SELF_TEST_COMPUTE: ModConfigSpec.BooleanValue

    /** `[debug] failDevice` — make device creation fail on purpose (P73 policy testing). */
    val FAIL_DEVICE: ModConfigSpec.BooleanValue

    /** `[debug] meshDiagnostics` — the one-shot mesh read-back isolation test. */
    val MESH_DIAGNOSTICS: ModConfigSpec.EnumValue<MeshDiagnostics>

    /** `[debug] noCrashPopup` — suppress the fatal-error dialog (automation). */
    val NO_CRASH_POPUP: ModConfigSpec.BooleanValue

    /** `[debug] dumpHlsl` — write each translated HLSL pair into `dxrmc/hlsl/` for inspection. */
    val DUMP_HLSL: ModConfigSpec.BooleanValue

    /** `[mesh] terrain` — the only terrain rasterization path (the IMR fallback was removed). */
    val MESH_TERRAIN: ModConfigSpec.BooleanValue

    /** `[mesh] terrainCull` — cull terrain meshlets with the amplification shader (P217). */
    val MESH_TERRAIN_CULL: ModConfigSpec.BooleanValue

    /** `[vrs] terrainNearBlocks` — terrain closer than this always shades at 1X1 (P233). */
    val VRS_TERRAIN_NEAR_BLOCKS: ModConfigSpec.DoubleValue

    /** `[vrs] terrainFarBlocks` — distance where the farthest shading-rate band starts (P233). */
    val VRS_TERRAIN_FAR_BLOCKS: ModConfigSpec.DoubleValue

    /** `[vrs] sri` — generate the Tier 2 shading-rate image every frame (P235/P237). */
    val VRS_SRI: ModConfigSpec.BooleanValue

    /** `[vrs] sriEdgePermille` — depth contrast that keeps a tile at 1X1, in per-mille. */
    val VRS_SRI_EDGE_PERMILLE: ModConfigSpec.IntValue

    /** `[vrs] sriMotionPixels` — screen-space motion above which a tile stays 1X1. */
    val VRS_SRI_MOTION_PIXELS: ModConfigSpec.IntValue

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

        SHUTDOWN_TRACE = BUILDER
            .comment(
                "Print the native shutdown trace (destroySurface/destroyDevice stage lines) at",
                "any log level. They are INFO lines, not errors - they used to be logged as ERROR,",
                "so every normal exit spammed the console.",
                "ON (default): they always show up. That is the only trail you get if quitting hangs.",
                "OFF: they become ordinary INFO lines, so the default ERROR level hides them."
            )
            .translation("$i18n_LOG_KEY.shutdownTrace")
            .define("shutdownTrace", true)

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
                "STREAMLINE is the default: sl.dlss is the layer-based path and the only one that",
                "gets correct per-frame resource tags. NGX stays as the fallback for machines whose",
                "Streamline runtime is missing (the DLL then falls back and says so in the log)."
            )
            .translation("dxr_mc.configuration.dlss.implementation")
            .gameRestart()
            .defineEnum("implementation", DlssImplementation.STREAMLINE)

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

        DLSS_FRAME_GENERATION = BUILDER
            .comment(
                "P213: DLSS frame generation (DLSS-G). OFF, ON = fixed multiplier or DYNAMIC.",
                "Needs Reflex enabled and the sl.dlss_g runtime next to MCDR.dll; the DLL reports",
                "the feature state in the log at startup ([P213] DLSS-G probe).",
            )
            .translation("dxr_mc.configuration.dlss.frameGeneration")
            .defineEnum("frameGeneration", DlssFrameGeneration.OFF)

        DLSS_FRAME_GENERATION_FRAMES = BUILDER
            .comment(
                "Frame generation multiplier when frameGeneration = ON: n extra frames means (n+1)x output, 1..5 -> 2x..6x.",
                "Streamline reports the real maximum in DLSSGState::numFramesToGenerateMax.",
            )
            .translation("dxr_mc.configuration.dlss.frameGenerationFrames")
            .defineInRange("frameGenerationFrames", 1, 1, 5)

        BUILDER.pop()

        BUILDER.comment(
            "P117: ray-traced visibility layering (G-Buffer + sampler feedback).",
            "OFF keeps the raster path untouched. AO / AO_SHADOWS / FULL progressively add",
            "ray-traced AO, shadows and reflections on tiles that the sampler feedback says are",
            "actually visible. Machines below D3D12 SamplerFeedbackTier 1 ignore this setting."
        ).translation("dxr_mc.configuration.raytracing").push("raytracing")

        RAYTRACING_MODE = BUILDER
            .comment(
                "OFF disables the whole path. AO traces ambient occlusion only, AO_SHADOWS adds",
                "ray-traced shadows, FULL adds reflections. Takes effect on the next frame."
            )
            .translation("dxr_mc.configuration.raytracing.mode")
            .defineEnum("mode", RtMode.OFF)

        BUILDER.pop()

        BUILDER.comment(
            "Terrain mesh-shader path (the mesh pipeline).",
            "Terrain no longer has an indexed-draw fallback: every terrain pipeline (solid,",
            "cutout and the OIT translucent stages) is rasterized by mesh shaders. Turning this",
            "off leaves terrain undrawn - it is only useful for isolating a mesh regression."
        ).translation("dxr_mc.configuration.mesh").push("mesh")

        MESH_TERRAIN = BUILDER
            .comment(
                "Rasterize terrain with mesh shaders (the only terrain path).",
                "Requires a device with mesh shaders (12_2 / tier 1). The original IMR fallback",
                "has been removed on purpose: when the mesh variant cannot be built, the terrain",
                "batch is skipped and an ERROR is logged instead of silently drawing it again."
            )
            .translation("dxr_mc.configuration.mesh.terrain")
            .define("terrain", true)

        MESH_TERRAIN_CULL = BUILDER
            .comment(
                "Cull terrain meshlets in the amplification shader before the mesh shader runs.",
                "OFF: every meshlet is dispatched. ON: each amplification-shader thread tests one",
                "meshlet against a 6-direction back-face mask and the view frustum, and only the",
                "surviving meshlets reach the mesh shader. Needs [mesh] terrain = true; ignored",
                "when the device has no mesh tier."
            )
            .translation("dxr_mc.configuration.mesh.terrain_cull")
            .define("terrainCull", false)

        BUILDER.pop()

        BUILDER.comment(
            "Distance bands for the per-primitive terrain shading rate (P233).",
            "The mesh shader gives every terrain meshlet a shading rate taken from its distance:",
            "closer than terrainNearBlocks stays 1X1, the band below terrainFarBlocks shades 1X2,",
            "and everything beyond it shades 2X2.",
            "These are read on every terrain batch, so editing them while playing applies on the",
            "next frame; the shading-rate option itself still has to be AUTO."
        ).translation("dxr_mc.configuration.vrs").push("vrs")

        VRS_TERRAIN_NEAR_BLOCKS = BUILDER
            .comment(
                "Terrain closer than this keeps full 1X1 shading, in blocks.",
                "Lowering it moves the coarser band towards the camera: more saved pixels, but the",
                "band edge lands on geometry the player is looking at."
            )
            .translation("dxr_mc.configuration.vrs.terrain_near_blocks")
            .defineInRange("terrainNearBlocks", 32.0, 0.0, 512.0)

        VRS_TERRAIN_FAR_BLOCKS = BUILDER
            .comment(
                "Distance where the farthest band starts, in blocks.",
                "Keep it well beyond the near band. Roughly half the view distance keeps the 2X2",
                "edge off the horizon line, which is where it is easiest to notice."
            )
            .translation("dxr_mc.configuration.vrs.terrain_far_blocks")
            .defineInRange("terrainFarBlocks", 96.0, 0.0, 2048.0)

        VRS_SRI = BUILDER
            .comment(
                "P235/P237: generate the Tier 2 screen-space shading rate image every frame.",
                "The image is what keeps the horizon stable: it judges every 16x16 tile from the",
                "previous frame's depth (plus motion vectors when the velocity pass exists) and",
                "only lets the terrain go coarser where the tile is flat. With this off, the",
                "per-primitive distance bands above are all that decide."
            )
            .translation("dxr_mc.configuration.vrs.sri")
            .define("sri", true)

        VRS_SRI_EDGE_PERMILLE = BUILDER
            .comment(
                "Depth contrast, in per-mille, that marks a tile as an edge and keeps it 1X1.",
                "Lower means more tiles count as edges (safer, less saving); 1000 disables it."
            )
            .translation("dxr_mc.configuration.vrs.sriEdgePermille")
            .defineInRange("sriEdgePermille", 50, 0, 1000)

        VRS_SRI_MOTION_PIXELS = BUILDER
            .comment(
                "Screen-space motion, in pixels per frame, above which a tile stays 1X1.",
                "Only consulted when the velocity pass runs (SMAA/TAA or DLSS enabled)."
            )
            .translation("dxr_mc.configuration.vrs.sriMotionPixels")
            .defineInRange("sriMotionPixels", 8, 0, 256)

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

        SELF_TEST_COMPUTE = BUILDER
            .comment(
                "Run the compute self-test (P234: compile cs_6_8, create a compute PSO, dispatch",
                "one thread group into a storage texture and read it back).",
                "Proves the compute path that the shading-rate image (P235) and NRC build on;",
                "it compiles HLSL on the spot, so it does not belong on the startup critical path."
            )
            .translation("dxr_mc.configuration.debug.selfTestCompute")
            .gameRestart()
            .define("selfTestCompute", false)

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



