package dev.nts.expdxr

import dev.nts.expdxr.utils.IllusionLogLevel
import dev.nts.expdxr.utils.IllusionLogger.i18n_LOG_KEY
import net.neoforged.neoforge.common.ModConfigSpec

object IllusionConfig {
    private val BUILDER = ModConfigSpec.Builder()

    /** `[logging] logLevel` — see [IllusionLogLevel]. */
    val LOG_LEVEL: ModConfigSpec.EnumValue<IllusionLogLevel>

    /** `[logging] nativeVerbose` — force the DLL to its DEBUG level on its own. */
    val NATIVE_VERBOSE: ModConfigSpec.BooleanValue

    /**
     * `[debug] pixCapture` — load Microsoft PIX's GPU capture layer at startup.
     *
     * Marked with [ModConfigSpec.Builder.gameRestart]: PIX's capture layer hooks Direct3D 12
     * from inside its own DLL, so it can only attach to devices created *after* it is loaded.
     */
    val PIX_CAPTURE: ModConfigSpec.BooleanValue

    init {
        BUILDER.comment(
            "How much this mod prints.",
            "ERROR (default) keeps the console usable: only errors are printed — by the",
            "renderer, by the debug mixins and by the native DLL.",
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
                "the game restart. PIX must be running to actually save a capture; point the",
                "environment variable MCDR_PIX_CAPTURER at the DLL (or its directory) when PIX is",
                "installed somewhere unusual."
            )
            .translation("dxr_mc.configuration.debug.pixCapture")
            .gameRestart()
            .define("pixCapture", false)

        BUILDER.pop()
    }

    val SPEC: ModConfigSpec = BUILDER.build()
}
