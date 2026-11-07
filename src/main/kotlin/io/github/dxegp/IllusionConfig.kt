package io.github.dxegp

import io.github.dxegp.misc.IllusionLogLevel
import io.github.dxegp.misc.IllusionLogger
import net.neoforged.neoforge.common.ModConfigSpec

/** `[renderer] commandBundles` — how one multi-draw batch is split across command bundles. */
enum class CommandBundleMode {
    AUTO,
    SERIAL,
    PARALLEL,
}

/** `[dlss] implementation` — which upscaler backend the native DLL drives. */
enum class DlssImplementation {
    NGX,
    STREAMLINE,
}

/** `[dlss] frameGeneration` — DLSS-G (frame generation). */
enum class DlssFrameGeneration {
    OFF,
    ON,
    DYNAMIC,
}

/**
 * `[sr] backend` — which super-resolution backend the abstraction layer drives; `id` is the number
 * handed to native, so do not renumber.
 */
enum class SrBackend(val id: Int) {
    NONE(0),
    DLSS(1),
    FSR(2),
    XESS(3),
}

/** `[renderer] hdr` — native HDR (scRGB) presentation; AUTO follows Windows advanced colour. */
enum class HdrMode {
    AUTO,
    FORCE_ON,
    FORCE_OFF,
}

/**
 * `[rt] mode` — visibility layering. OFF builds no G-buffer and takes no ray-tracing
 * branch; devices below SamplerFeedbackTier 1 ignore every value but OFF.
 */
enum class RtMode {
    OFF,
    AO,
    AO_SHADOWS,
    FULL,
}

/**
 * `[rt] backend` — which ray-tracing backend the path tracer drives.
 *
 * AUTO selects the available hardware implementation. WG currently falls back to DXR.
 * Hardware ray tracing is required; unsupported devices keep the vanilla renderer.
 */
enum class RtBackend {
    AUTO,
    DXR,
    WG,
}

/**
 * `[rt] qualityProfile` — a named bundle of quality knobs.
 *
 * VALIDATION is the reproducible profile (1 spp, fixed seed, no temporal accumulation); BALANCED
 * and QUALITY only change sampling parameters, never which passes run. A profile must never be
 * able to skip a gate: see rt_plan.md §7.
 */
enum class RtQualityProfile {
    VALIDATION,
    BALANCED,
    QUALITY,
}

/** `[rt] samplingMode` — how the next bounce direction is chosen. */
enum class RtSamplingMode {
    BSDF,
    COSINE,
    RESERVOIR,
}

/**
 * `[rt] debugView` — which intermediate signal replaces the final image.
 *
 * The list grows with the passes that produce each signal; a view with no producer renders the
 * MISSING colour instead of a black frame, so a half-built pipeline is still readable.
 */
enum class RtDebugView {
    NONE,

    DEPTH,

    NORMAL,

    ALBEDO,

    ROUGHNESS,

    HIT_DISTANCE,

    DIRECT,

    /** 覆盖率：G-Buffer 写过的像素白，其余黑。 */
    COVERAGE,

    /** 阴影掩码：被太阳挡住红、没挡住深灰。 */
    SHADOW,

    /** 整屏常量品红：只验证「合光 + 上屏」这条通路。 */
    COMPOSITE_MARK,

    INDIRECT,

    CACHE_CONFIDENCE,

    REACTIVE_MASK,

    MOTION_VECTORS,

    HISTORY_REJECTION,

    TRAVERSAL_STEPS,

    DUAL_TRACK,

    GEOMETRY,

    /** 加速结构的实例盒线框：确认 BLAS/TLAS 里的盒子就是几何池里的段。见 docs/rt/README.md 14.6 节 */
    BLAS,

    PRIMARY_VALIDITY,

    DIRECT_VALIDITY,

    DIFFUSE_VALIDITY,

    SPECULAR_VALIDITY,
}

/**
 * `[rt] denoise` — how the noisy path-traced image becomes a presentable one.
 *
 * The three named tracks trade coupling for control: `RR` hands denoise and upscale to one
 * DLSS-D call at render resolution, `NRD_SR` denoises first and lets a separate super-resolution
 * pass scale up, `RAW` is the un-denoised reference. See docs/rt/README.md §18.
 */
enum class RtDenoise {
    OFF,

    RR,

    NRD_SR,

    RAW,
}

enum class RtExposureMode {
    MANUAL,
    AUTO,
}

/** `[debug] meshDiagnostics` — one-shot isolation test for "texture write vs read-back". */
enum class MeshDiagnostics {    NONE,

    CLEAR,

    PASS,
}

/**
 * Every switch the player can change, in `config/dxr_mc-client.toml`; `D3D12Settings` resolves them
 * into one immutable snapshot.
 *
 * <p>**Changing a default means changing three places**: the `define*` default here, the `Snapshot`
 * fallback, and the `config(fallback)` argument in `build()`. `.gameRestart()` follows one rule:
 * read at the moment of use = hot-switchable, read before the device or swapchain exists = restart.
 * See docs/misc/settingscreen/README.md §2.4 / §5.1.
 */
object IllusionConfig {
    private val BUILDER = ModConfigSpec.Builder()

    /** `[logging] logLevel` — see [io.github.dxegp.misc.IllusionLogLevel]. */
    val LOG_LEVEL: ModConfigSpec.EnumValue<IllusionLogLevel>

    /** `[logging] nativeVerbose` — force the DLL to its DEBUG level on its own. */
    val NATIVE_VERBOSE: ModConfigSpec.BooleanValue

    val SHUTDOWN_TRACE: ModConfigSpec.BooleanValue

    /** `[logging] nativeLogFile` — also mirror the native log into this file (`""` = off). */
    val NATIVE_LOG_FILE: ModConfigSpec.ConfigValue<String>

    /** `[renderer] glContext` — the escape hatch that keeps a real OpenGL context alive. */
    val GL_CONTEXT: ModConfigSpec.BooleanValue

    /** `[renderer] asyncCommandLists` — record command lists on the worker pool. */
    val ASYNC_COMMAND_LISTS: ModConfigSpec.BooleanValue

    val ASYNC_WORKERS: ModConfigSpec.IntValue

    val COMMAND_BUNDLES: ModConfigSpec.EnumValue<CommandBundleMode>

    val HDR: ModConfigSpec.EnumValue<HdrMode>

    /** `[renderer] allowTearing` — allow a `FLIP_DISCARD + ALLOW_TEARING` swapchain. */
    val ALLOW_TEARING: ModConfigSpec.BooleanValue

    /** `[renderer] presentChildWindow` — build the swapchain on a self-owned child window. */
    val PRESENT_CHILD_WINDOW: ModConfigSpec.BooleanValue

    /** `[rt] mode` — see [RtMode]. */
    val RAYTRACING_MODE: ModConfigSpec.EnumValue<RtMode>

    val RAYTRACING_BACKEND: ModConfigSpec.EnumValue<RtBackend>

    /** `[rt] denoise` — see [RtDenoise]. */
    val RAYTRACING_DENOISE: ModConfigSpec.EnumValue<RtDenoise>

    /** `[rt] renderScale` — render resolution as a fraction of the display, in percent. */
    val RAYTRACING_RENDER_SCALE: ModConfigSpec.IntValue
    val RAYTRACING_BUILD_CPU_MICROS: ModConfigSpec.IntValue
    val RAYTRACING_BUILD_GPU_MICROS: ModConfigSpec.IntValue
    val RAYTRACING_BUILD_TRIANGLES: ModConfigSpec.IntValue

    val RAYTRACING_EXPOSURE_MODE: ModConfigSpec.EnumValue<RtExposureMode>
    val RAYTRACING_EXPOSURE_EV: ModConfigSpec.DoubleValue
    val RAYTRACING_EXPOSURE_SPEED: ModConfigSpec.DoubleValue

    val RAYTRACING_QUALITY_PROFILE: ModConfigSpec.EnumValue<RtQualityProfile>

    val RAYTRACING_SPP: ModConfigSpec.IntValue

    val RAYTRACING_MAX_BOUNCES: ModConfigSpec.IntValue

    val RAYTRACING_SAMPLING_MODE: ModConfigSpec.EnumValue<RtSamplingMode>

    val RAYTRACING_VOXEL_TRUST_RADIUS: ModConfigSpec.DoubleValue

    val RAYTRACING_HZB: ModConfigSpec.ConfigValue<Boolean>

    val RAYTRACING_HISTORY_FRAMES: ModConfigSpec.IntValue

    val RAYTRACING_DEBUG_VIEW: ModConfigSpec.EnumValue<RtDebugView>

    val RAYTRACING_INVALIDATION_LOG: ModConfigSpec.ConfigValue<Boolean>

    /** `[materials] enabled` — 读每张精灵旁边的 labPBR `_s` / `_n` 贴图。 */
    val MATERIALS_ENABLED: ModConfigSpec.ConfigValue<Boolean>

    /** `[materials] packPath` — 直接读的材质包目录；空 = 用游戏里启用的资源包。 */
    val MATERIALS_PACK_PATH: ModConfigSpec.ConfigValue<String>
    val MATERIALS_PARALLAX_DEPTH: ModConfigSpec.DoubleValue

    /** `[shaderpack] active` — 当前生效的光影包名（空 = 无光影，光追保持关闭）。 */
    val SHADERPACK_ACTIVE: ModConfigSpec.ConfigValue<String>

    /** `[renderer] sharpenAmount` — post-sharpen strength for DLAA/DLSS. */
    val SHARPEN_AMOUNT: ModConfigSpec.DoubleValue

    val TAA_SHARPEN_AMOUNT: ModConfigSpec.DoubleValue

    val DLSS_IMPLEMENTATION: ModConfigSpec.EnumValue<DlssImplementation>

    /** `[dlss] outputHandover` — the JVM owns DLSS's output texture. */
    val DLSS_OUTPUT_HANDOVER: ModConfigSpec.BooleanValue

    /** `[dlss] reflexFrameLimitUs` — Reflex's own frame-rate limiter, microseconds per frame. */
    val DLSS_REFLEX_FRAME_LIMIT_US: ModConfigSpec.IntValue

    val DLSS_FRAME_GENERATION: ModConfigSpec.EnumValue<DlssFrameGeneration>
    /** `[sr] backend` — which backend runs; a switch takes effect on the next frame, no restart. */
    val SR_BACKEND: ModConfigSpec.EnumValue<SrBackend>

    /** `[sr] sharpness` — 后端自带的锐化强度（0 = 关）。FSR / XeSS 直接吃这个值。 */
    val SR_SHARPNESS: ModConfigSpec.DoubleValue
    val SR_MASK: ModConfigSpec.BooleanValue

    /** `[dlss] frameGenerationFrames` — generated frames per rendered frame (1-5). */
    val DLSS_FRAME_GENERATION_FRAMES: ModConfigSpec.IntValue

    /**
     * `[debug] pixCapture` — load PIX's GPU capture layer at startup. It hooks D3D12 from inside
     * its own DLL, so it can only attach to devices created after it is loaded.
     */
    val PIX_CAPTURE: ModConfigSpec.BooleanValue

    val PIX_TIMING_CAPTURE: ModConfigSpec.BooleanValue

    /** `[debug] pixCaptureFrames` — frames recorded by one key-press capture. */
    val PIX_CAPTURE_FRAMES: ModConfigSpec.IntValue

    /** `[debug] pixDirectory` — empty means the default install locations; no env var (docs §5.3). */
    val PIX_DIRECTORY: ModConfigSpec.ConfigValue<String>

    val DEBUG_LAYER: ModConfigSpec.BooleanValue

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

    val SELF_TEST_MESH: ModConfigSpec.BooleanValue

    /** `[debug] selfTestCompute` — run the compute self-test (root UAV + root constants). */
    val SELF_TEST_COMPUTE: ModConfigSpec.BooleanValue

    /** `[debug] failDevice` — make device creation fail on purpose (failure-policy test). */
    val FAIL_DEVICE: ModConfigSpec.BooleanValue

    val MESH_DIAGNOSTICS: ModConfigSpec.EnumValue<MeshDiagnostics>

    val NO_CRASH_POPUP: ModConfigSpec.BooleanValue

    val DUMP_HLSL: ModConfigSpec.BooleanValue

    /** `[debug] shaderConverter` — convert every shader with the standalone DLL beside the shaderc chain. */
    val SHADER_CONVERTER: ModConfigSpec.BooleanValue

    /** `[debug] shaderConverterDll` — location of `illusion_shader.dll`, relative to the game directory. */
    val SHADER_CONVERTER_DLL: ModConfigSpec.ConfigValue<String>

    /**
     * `[debug] shaderConverterPrimary` — render with the converter's HLSL where the host can match
     * its bindings, falling back to the shaderc chain everywhere else.
     */
    val SHADER_CONVERTER_PRIMARY: ModConfigSpec.BooleanValue

    /**
     * `[debug] shaderConverterPipelines` — comma-separated pipeline id fragments that may render
     * through the converter; empty means every pipeline that passes the binding check.
     */
    val SHADER_CONVERTER_PIPELINES: ModConfigSpec.ConfigValue<String>

    /** `[mesh] terrain` — the only terrain rasterization path (the IMR fallback was removed). */
    val MESH_TERRAIN: ModConfigSpec.BooleanValue

    /** `[mesh] terrainCull` — cull terrain meshlets in the amplification shader. */
    val MESH_TERRAIN_CULL: ModConfigSpec.BooleanValue

    /** `[vrs] terrainNearBlocks` — terrain closer than this always shades at 1X1. */
    val VRS_TERRAIN_NEAR_BLOCKS: ModConfigSpec.DoubleValue

    /** `[vrs] terrainFarBlocks` — distance where the farthest shading-rate band starts. */
    val VRS_TERRAIN_FAR_BLOCKS: ModConfigSpec.DoubleValue

    /** `[vrs] sri` — generate the Tier 2 shading-rate image every frame. */
    val VRS_SRI: ModConfigSpec.BooleanValue

    /** `[vrs] sriEdgePermille` — depth contrast that keeps a tile at 1X1, in per-mille. */
    val VRS_SRI_EDGE_PERMILLE: ModConfigSpec.IntValue

    /** `[vrs] sriMotionPixels` — screen-space motion above which a tile stays 1X1. */
    val VRS_SRI_MOTION_PIXELS: ModConfigSpec.IntValue

    val VRS_SRI_TEMPORAL_CLAMP: ModConfigSpec.BooleanValue

    init {
        BUILDER.comment(
            "How much this mod prints.",
            "ERROR (default) keeps the console usable: only errors are printed — by the",
            "renderer and by the native DLL.",
            "WARN adds warnings; INFO adds per-load diagnostics; DEBUG/TRACE add the",
            "high-frequency dumps (shader sources, PSO/pipeline dumps, per-frame tracing).",
            "The environment variable DX12_LOG_VERBOSE=1 still forces DEBUG for one run."
        ).translation(IllusionLogger.i18n_LOG_KEY).push("logging")

        LOG_LEVEL = BUILDER
            .comment("Level for everything this mod prints, Java and native alike.")
            .translation("${IllusionLogger.i18n_LOG_KEY}.logLevel")
            .defineEnum("logLevel", IllusionLogLevel.ERROR)

        NATIVE_VERBOSE = BUILDER
            .comment(
                "Force the native DLL to DEBUG regardless of logLevel.",
                "Kept separate because the DLL is by far the loudest writer, and bisecting it",
                "on its own (without turning the renderer logs back on) is the common case."
            )
            .translation("${IllusionLogger.i18n_LOG_KEY}.nativeVerbose")
            .define("nativeVerbose", false)

        SHUTDOWN_TRACE = BUILDER
            .comment(
                "Print the native shutdown trace (destroySurface/destroyDevice stage lines) at",
                "any log level. They are INFO lines, not errors - they used to be logged as ERROR,",
                "so every normal exit spammed the console.",
                "ON (default): they always show up. That is the only trail you get if quitting hangs.",
                "OFF: they become ordinary INFO lines, so the default ERROR level hides them."
            )
            .translation("${IllusionLogger.i18n_LOG_KEY}.shutdownTrace")
            .define("shutdownTrace", true)

        NATIVE_LOG_FILE = BUILDER
            .comment(
                "Also write every native log line into this file (empty = console only).",
                "Relative paths resolve against the game directory. Useful when the console",
                "scrolls too fast, or when the interesting part is the gap before the first frame."
            )
            .translation("${IllusionLogger.i18n_LOG_KEY}.nativeLogFile")
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

        ALLOW_TEARING = BUILDER
            .comment(
                "Allow an ALLOW_TEARING (composed-flip bypassing) swapchain.",
                "ON (default) is what low-latency/VRR wants: Present(0, ALLOW_TEARING) lets the",
                "chain go independent flip. OFF keeps the chain in DWM composed flip — the mode",
                "overlays such as RTSS/Afterburner draw their OSD in. Takes effect on the next",
                "swapchain creation."
            )
            .translation("dxr_mc.configuration.renderer.allowTearing")
            .define("allowTearing", true)

        PRESENT_CHILD_WINDOW = BUILDER
            .comment(
                "Build the swapchain on a self-owned child window (SeamlessWindowHost) that never",
                "touches OpenGL.",
                "ON (default) is what overlays such as RTSS/Afterburner need to draw their OSD: the",
                "child window always goes through DWM composition and can never enter independent",
                "flip. It is also what keeps the D3D12 surface physically isolated from the GLFW",
                "OpenGL context.",
                "OFF creates the chain on the SDL top-level window. That is required for exclusive",
                "fullscreen, which a child window cannot own.",
                "Takes effect on the next swapchain creation."
            )
            .translation("dxr_mc.configuration.renderer.presentChildWindow")
            .define("presentChildWindow", true)

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
                "DLSS frame generation (DLSS-G). OFF, ON = fixed multiplier or DYNAMIC.",
                "Needs Reflex enabled and the sl.dlss_g runtime next to MCDR.dll; the DLL reports",
                "the feature state in the log at startup (the DLSS-G probe line).",
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
            "Which super-resolution backend the native DLL drives.",
            "DLSS keeps the existing DeepLearningSuperSampling path byte-for-byte; FSR and XESS go",
            "through the shared SR abstraction (mcdr_dx12Sr*). Changing this needs no restart:",
            "the next frame picks it up (switching to FSR/XeSS waits for the GPU once).",
            "FSR / XeSS only upscale when the render scale is below 100% (they need separate",
            "input and output textures), so pair the backend with a render scale below 100%.",
            "Unavailable backends say why in the log and fall back to no upscaling."
        ).translation("dxr_mc.configuration.sr").push("sr")

        SR_BACKEND = BUILDER
            .comment(
                "NONE = no super resolution. DLSS = the NVIDIA path (needs an RTX card).",
                "FSR = AMD FidelityFX. Needs amd_fidelityfx_upscaler_dx12.dll next to MCDR.dll",
                "(dev tree only). FSR4 additionally needs an RDNA4 card; older cards get FSR 3.1",
                "from the same runtime. XESS = Intel XeSS, whose libxess.dll ships in the JAR.",
                "Switching needs no restart, but the first frame on a new backend waits for the",
                "GPU to catch up once (the old context is destroyed)."
            )
            .translation("dxr_mc.configuration.sr.backend")
            .defineEnum("backend", SrBackend.DLSS)

        SR_SHARPNESS = BUILDER
            .comment(
                "Sharpening strength handed to the backend itself (0 = off, 1 = maximum).",
                "Only FSR and XeSS consume it; the DLSS path keeps its own post-sharpen filter",
                "([renderer] sharpenAmount)."
            )
            .translation("dxr_mc.configuration.sr.sharpness")
            .defineInRange("sharpness", 0.0, 0.0, 1.0)
    SR_MASK = BUILDER
        .comment(
            "Feeds a reactive (FSR) / responsive (XeSS) mask made from the OIT transmittance buffer.",
            "Off by default: it is a quality refinement, not a requirement of any of the three SDKs."
        )
        .translation("dxr_mc.configuration.sr.mask")
        .define("mask", false)

        BUILDER.pop()

        BUILDER.comment(
            "Ray-traced visibility layering (G-Buffer + sampler feedback).",
            "OFF keeps the raster path untouched. AO / AO_SHADOWS / FULL progressively add",
            "ray-traced AO, shadows and reflections on tiles that the sampler feedback says are",
            "actually visible. Machines below D3D12 SamplerFeedbackTier 1 ignore this setting."
        ).translation("dxr_mc.configuration.rt").push("rt")

        RAYTRACING_MODE = BUILDER
            .comment(
                "OFF disables the whole path. AO traces ambient occlusion only, AO_SHADOWS adds",
                "ray-traced shadows, FULL adds reflections. Takes effect on the next frame."
            )
            .translation("dxr_mc.configuration.rt.mode")
            .defineEnum("mode", RtMode.OFF)

        RAYTRACING_BACKEND = BUILDER
            .comment(
                "Hardware ray-tracing backend (AUTO / DXR / WG).",
                "AUTO uses DXR. WG currently falls back to DXR until its implementation is ready.",
                "Ray tracing stays off when hardware DXR is unavailable."
            )
            .translation("dxr_mc.configuration.rt.backend")
            .defineEnum("backend", RtBackend.AUTO)

        RAYTRACING_DENOISE = BUILDER
            .comment(
                "How the noisy image is denoised and upscaled (OFF / RR / NRD_SR / RAW).",
                "RR combines DLSS-D denoising and upscaling. NRD_SR uses NRD with independent SR.",
                "RAW keeps hardware path tracing without denoising or temporal reconstruction.",
                "Off by default. RR needs the Streamline DLSS-D runtime; when Streamline",
                "rejects the plugin the track stays on NRD for the rest of the session."
            )
            .translation("dxr_mc.configuration.rt.denoise")
            .defineEnum("denoise", RtDenoise.OFF)

        RAYTRACING_RENDER_SCALE = BUILDER
            .comment(
                "Path-traced render resolution as a percentage of the display, 25 to 100.",
                "Below 100 the trace targets and the G-buffer shrink together. RR or spatial",
                "upsampling produces the display size. The denoise row of docs/rt/README.md §18 says",
                "which track allows which value. OFF keeps native resolution; RAW scales."
            )
            .translation("dxr_mc.configuration.rt.render_scale")
            .defineInRange("renderScale", 100, 25, 100)

        RAYTRACING_BUILD_CPU_MICROS = BUILDER
            .comment("CPU recording time for incremental scene AS work per frame, in microseconds.",
                "Work stops between BLAS builds. One indivisible build and publication may exceed this value.")
            .translation("dxr_mc.configuration.rt.build_cpu_micros")
            .defineInRange("buildCpuMicros", 3000, 100, 1000000)

        RAYTRACING_BUILD_GPU_MICROS = BUILDER
            .comment("Estimated GPU time for incremental scene AS work per frame, in microseconds.",
                "Completed timestamp measurements calibrate admission without waiting for the GPU.")
            .translation("dxr_mc.configuration.rt.build_gpu_micros")
            .defineInRange("buildGpuMicros", 1000, 100, 1000000)

        RAYTRACING_BUILD_TRIANGLES = BUILDER
            .comment("Triangle allowance for new scene BLAS builds per frame.",
                "An oversized oldest record proceeds alone and is reported as an overrun.")
            .translation("dxr_mc.configuration.rt.build_triangles")
            .defineInRange("buildTriangles", 65536, 1, 1000000000)

        RAYTRACING_EXPOSURE_MODE = BUILDER
            .comment("Exposure metering: MANUAL uses a fixed multiplier; AUTO adapts to traced surfaces.",
                "Both modes apply exposure once before tone mapping. Takes effect immediately.")
            .translation("dxr_mc.configuration.rt.exposure_mode")
            .defineEnum("exposureMode", RtExposureMode.MANUAL)

        RAYTRACING_EXPOSURE_EV = BUILDER
            .comment("Exposure compensation in stops, -8 to 8. Each +1 doubles brightness.",
                "AUTO targets middle gray 0.18, with a final multiplier clamped to 1/256 through 256.")
            .translation("dxr_mc.configuration.rt.exposure_ev")
            .defineInRange("exposureEv", 0.0, -8.0, 8.0)

        RAYTRACING_EXPOSURE_SPEED = BUILDER
            .comment("Automatic exposure response rate per second, 0.1 to 10.",
                "Higher values adapt faster. World changes and camera cuts reset adaptation.")
            .translation("dxr_mc.configuration.rt.exposure_speed")
            .defineInRange("exposureSpeed", 1.5, 0.1, 10.0)

        RAYTRACING_QUALITY_PROFILE = BUILDER
            .comment(
                "Named bundle of sampling parameters (VALIDATION / BALANCED / QUALITY).",
                "VALIDATION is the reproducible profile: 1 spp, fixed seed, no temporal",
                "accumulation - the one CI compares against the CPU reference. The other two",
                "only change sampling, never which passes run.",
                "The B-DXR backend ignores this today: one bounce at 1 spp, no profile."
            )
            .translation("dxr_mc.configuration.rt.quality_profile")
            .defineEnum("qualityProfile", RtQualityProfile.BALANCED)

        RAYTRACING_SPP = BUILDER
            .comment(
                "Samples per pixel for the primary hit, 1 to 16.",
                "1 is the validation profile; raising it trades frame time for noise. This is an",
                "architecture limit, not a target: the resolver must stay correct at 1.",
                "The B-DXR backend ignores this today and always traces 1 sample."
            )
            .translation("dxr_mc.configuration.rt.spp")
            .defineInRange("spp", 1, 1, 16)

        RAYTRACING_MAX_BOUNCES = BUILDER
            .comment(
                "Maximum path length (1 = direct lighting only, up to 8).",
                "Bounce count is authored in the shader; this clamps it from the JVM side so a",
                "lower value also lowers the recursion depth registered for the work graph.",
                "The B-DXR backend ignores this today: its shader always takes one bounce."
            )
            .translation("dxr_mc.configuration.rt.max_bounces")
            .defineInRange("maxBounces", 2, 1, 8)

        RAYTRACING_SAMPLING_MODE = BUILDER
            .comment(
                "How the next bounce direction is chosen (BSDF / COSINE / RESERVOIR).",
                "BSDF is the reference (importance sampled, correct PDF); COSINE trades accuracy",
                "for cheaper shadowed diffuse; RESERVOIR is the spatiotemporal reuse path.",
                "The B-DXR backend ignores this today and always samples the cosine lobe."
            )
            .translation("dxr_mc.configuration.rt.sampling_mode")
            .defineEnum("samplingMode", RtSamplingMode.BSDF)

        RAYTRACING_VOXEL_TRUST_RADIUS = BUILDER
            .comment(
                "How far, in blocks, the voxel track is trusted to be authoritative.",
                "Only applies to geometry classes the capability matrix marks as expressible in",
                "both tracks; inside the radius the voxel DDA answers, outside it the triangle",
                "track does. 0 disables the voxel track entirely.",
                "The B-DXR backend runs the triangle track only, so this has no effect yet."
            )
            .translation("dxr_mc.configuration.rt.voxel_trust_radius")
            .defineInRange("voxelTrustRadius", 8.0, 0.0, 32.0)

        RAYTRACING_HZB = BUILDER
            .comment(
                "Cull ray-traced instances with last frame's depth pyramid.",
                "Off by default: the first frame after a camera cut has no valid pyramid, and a",
                "stale one shows up as popping geometry. Costs one pyramid build per frame.",
                "No backend builds or culls with a depth pyramid yet."
            )
            .translation("dxr_mc.configuration.rt.hzb")
            .define("hzb", false)

        RAYTRACING_HISTORY_FRAMES = BUILDER
            .comment(
                "Upper bound on temporal history length, 1 to 64 frames.",
                "The denoiser and the radiance cache both clamp to this; raising it sharpens",
                "static scenes and lengthens the ghost after a cut. Invalidations ignore it.",
                "Nothing consumes it yet: there is no denoiser and no radiance cache."
            )
            .translation("dxr_mc.configuration.rt.history_frames")
            .defineInRange("historyFrames", 16, 1, 64)

        RAYTRACING_DEBUG_VIEW = BUILDER
            .comment(
                "Replace the final image with one intermediate signal (NONE = normal output).",
                "A view whose producer is not built yet shows the MISSING colour rather than a",
                "black frame, so a partially built pipeline stays readable.",
                "PRIMARY_VALIDITY: green = retained hit, cyan = known hit passed through,",
                "magenta = unknown primary, black = no primary hit.",
                "DIRECT_VALIDITY: red = unknown shadow, dark gray = known primary with no direct gap.",
                "DIFFUSE_VALIDITY and SPECULAR_VALIDITY: red = unknown geometry, orange = unknown shadow,",
                "yellow = both, dark gray = known primary with no selected gap; magenta/black match PRIMARY_VALIDITY.",
                "Validity views show trace gaps before whole-pixel invalidation; they do not repair missing samples."
            )
            .translation("dxr_mc.configuration.rt.debug_view")
            .defineEnum("debugView", RtDebugView.NONE)

        RAYTRACING_INVALIDATION_LOG = BUILDER
            .comment(
                "Log temporal history resets with a reason mask and frame number.",
                "Geometry updates that preserve history do not produce reset messages.",
                "Sample trace validity up to four times per second into dxrmc/rt_validity.jsonl.",
                "Samples include scene versions and primary/lobe gaps; GPU readback never waits."
            )
            .translation("dxr_mc.configuration.rt.invalidation_log")
            .define("invalidationLog", false)

        BUILDER.pop()

        BUILDER.comment(
            "LabPBR material maps (`_s` smoothness/metal, `_n` normal) for ray-traced materials.",
            "Empty `packPath` reads them from the resource packs enabled in-game, so a release",
            "build ships no textures at all. A non-empty `packPath` reads that directory directly",
            "(`assets/<namespace>/textures/<path>_s.png`), which is how a development checkout",
            "links the material pack it keeps next to the shader packs instead of copying it."
        ).translation("dxr_mc.configuration.materials").push("materials")

        MATERIALS_ENABLED = BUILDER
            .comment(
                "Apply `_s` / `_n` maps per hit: roughness, F0, metal, emission and tangent normals.",
                "Off uses built-in material factors and geometric normals."
            )
            .translation("dxr_mc.configuration.materials.enabled")
            .define("enabled", true)

        MATERIALS_PACK_PATH = BUILDER
            .comment(
                "Directory of a resource pack to read material maps from; empty = enabled packs.",
                "Absolute, or relative to the game directory. Nothing is copied: the path is read",
                "as-is, so the development client can point straight at its own pack folder."
            )
            .translation("dxr_mc.configuration.materials.pack_path")
            .define("packPath", "")

        MATERIALS_PARALLAX_DEPTH = BUILDER
            .comment("Height-map relief in blocks. Zero disables parallax and relief self-shadowing.")
            .translation("dxr_mc.configuration.materials.parallax_depth")
            .defineInRange("parallaxDepth", 0.25, 0.0, 0.5)

        BUILDER.pop()

        BUILDER.comment(
            "Shader pack selection (the shader pack screen writes this).",
            "A user pack lives in `dxrmc/shaderpacks/<name>/shaders/`; bundled packs ship inside",
            "the jar. Only the ray-tracing HLSL comes from the pack, and a pack runs only when it",
            "carries every entry point the backend builds. Empty means no pack: ray tracing stays",
            "off."
        ).translation("dxr_mc.configuration.shaderpack").push("shaderpack")

        SHADERPACK_ACTIVE = BUILDER
            .comment(
                "Name of the shader pack that supplies the ray-tracing HLSL.",
                "Applying a pack sets its declared hardware backend; applying `none` puts it",
                "back to AUTO. Pipelines keep the bytecode they were built with, so the change",
                "lands on the next world load."
            )
            .translation("dxr_mc.configuration.shaderpack.active")
            .define("active", "B-DXR")

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
            "Distance bands for the per-primitive terrain shading rate.",
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
                "Generate the Tier 2 screen-space shading rate image every frame.",
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

        VRS_SRI_TEMPORAL_CLAMP = BUILDER
            .comment(
                "Never let a tile get coarser than it was in the previous frame's",
                "shading-rate image. Stops edges from breathing under a static camera,",
                "at the cost of keeping a tile at 1X1 for one frame after it gets finer",
                "(and of one extra ping-pong image)."
            )
            .translation("dxr_mc.configuration.vrs.sriTemporalClamp")
            .define("sriTemporalClamp", true)

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
                "Lets the SDK validate every call at a large frame-rate cost. Needs a",
                "`d3d12SDKLayers.dll` of the same version as `D3D12Core.dll` next to it",
                "(vcpkg `installed\\x64-windows\\debug\\bin\\`); without one the log says",
                "`dx12 debug layer: <path> not found - validation stays off` and the game runs."
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

        SHADER_CONVERTER = BUILDER
            .comment(
                "Convert every shader with the standalone GLSL -> HLSL converter (illusion_shader.dll)",
                "beside the shaderc + SPIRV-Cross chain, and record how each one converts.",
                "The host keeps rendering through the old chain, so this produces evidence only:",
                "per-shader success, the manifest resource counts and the HLSL under `dxrmc/converter/`.",
                "This is the first step of moving the shader pipeline onto that converter."
            )
            .translation("dxr_mc.configuration.debug.shaderConverter")
            .define("shaderConverter", false)

        SHADER_CONVERTER_DLL = BUILDER
            .comment(
                "Where `illusion_shader.dll` sits, relative to the game directory.",
                "Build it with `cargo build --release` in Illusion-shaders-extension and copy",
                "`target/release/illusion_shader.dll` to `dxrmc/`."
            )
            .translation("dxr_mc.configuration.debug.shaderConverterDll")
            .define("shaderConverterDll", "dxrmc/illusion_shader.dll")

        SHADER_CONVERTER_PRIMARY = BUILDER
            .comment(
                "Render with the converter's HLSL instead of SPIRV-Cross output.",
                "Only pipelines whose resource names and registers line up with the host's binding",
                "list are switched; everything else - Y-flipped passes, mesh variants, texel",
                "buffers, unknown resources - keeps using the old chain, so an unsupported",
                "pipeline degrades to the previous behaviour instead of failing."
            )
            .translation("dxr_mc.configuration.debug.shaderConverterPrimary")
            .define("shaderConverterPrimary", false)

        SHADER_CONVERTER_PIPELINES = BUILDER
            .comment(
                "Comma-separated pipeline id fragments allowed to use the converter for rendering",
                "(for example `position,gui`). Empty means every pipeline that passes the",
                "resource check. Use it to try one pipeline at a time during visual verification."
            )
            .translation("dxr_mc.configuration.debug.shaderConverterPipelines")
            .define("shaderConverterPipelines", "")

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
                "Run the compute self-test (compile cs_6_8, create a compute PSO, dispatch",
                "one thread group into a storage texture and read it back).",
                "Proves the compute path that the shading-rate image and NRC build on;",
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



