package dev.nts.expdxr.misc

import dev.nts.expdxr.IllusionConfig
import dev.nts.expdxr.misc.IllusionLogger.i18n_LOG_KEY
import dev.nts.expdxr.d3d12.extern.MCDR_ABI
import net.minecraft.network.chat.Component
import net.neoforged.neoforge.common.TranslatableEnum
import org.apache.logging.log4j.Level
import org.apache.logging.log4j.core.config.Configurator
import org.slf4j.LoggerFactory

/**
 * The mod's log level, mirrored to three places:
 *
 * - **Java/Kotlin logging** - the log4j2 level of every logger this mod owns (see
 *   [IllusionLogger.OWN_LOGGERS]); the renderer logs under `dxr_mc`, the temporal modules under
 *   `dxrmc-*`.
 * - **Diagnostic prints** - [IllusionLogger.debugMessage] / [IllusionLogger.debugFormat], routed
 *   through the configured level instead of writing straight to `System.err` (which bypassed
 *   every configuration).
 * - **Native** - pushed through `mcdrSetLogLevel` into `d3d12_mc::setLogLevel`, which gates
 *   the DLL's own `dbgLogError` / `dbgLog` / `dbgLogInfo` / `dbgLogDebug` channels. The
 *   native ordinal is the same 0..3 (error/warn/info/debug) as [IllusionLogLevel.nativeLevel].
 *
 * The default is [IllusionLogLevel.ERROR]: a normal run prints errors (and nothing else) from the
 * whole stack. `DX12_LOG_VERBOSE=1` in the environment stays a developer escape hatch that
 * overrides the config for a single run - it is what the pre-config builds used.
 */
enum class IllusionLogLevel(val nativeLevel: Int) : TranslatableEnum {
    ERROR(0),
    WARN(1),
    INFO(2),
    DEBUG(3),
    TRACE(3);

    /**
     * Config 界面（NeoForge 的 `ConfigurationScreen`）里这个枚举的显示名。
     *
     * 那个界面用 `displayvalue instanceof TranslatableEnum ? getTranslatedName() : literal(name())`
     * 渲染循环按钮，所以不实现本接口就只会显示常量名（ERROR/WARN/…）。键名与
     * [IllusionConfig.LOG_LEVEL] 的 translation key 同前缀，见 `assets/dxr_mc/lang` 下的
     * `en_us.json` 与 `zh_cn.json`。（注意别在注释里写通配路径：Kotlin 的块注释可嵌套，
     * 路径里的斜杠加星号会吞掉后面整个文件。）
     */
    override fun getTranslatedName(): Component =
        Component.translatable("$i18n_LOG_KEY.logLevel.${this.name}")

    fun toLog4j(): Level = when (this) {
        ERROR -> Level.ERROR
        WARN -> Level.WARN
        INFO -> Level.INFO
        DEBUG -> Level.DEBUG
        TRACE -> Level.TRACE
    }
}

/**
 * Single gateway for "should this line be printed at all?".
 *
 * State is deliberately a `@Volatile` field read through getters instead of `val`s: the class
 * is touched during mod construction (before the config file is read) and again from the
 * config events, and a stale `val` captured at class-init would freeze the level at its
 * startup default forever.
 */
object IllusionLogger {
    private val LOGGER = LoggerFactory.getLogger("dxr_mc")

    /** Developer escape hatch, same variable the native layer used to read itself. */
    private val ENV_VERBOSE = "1" == System.getenv("D3D12_LOG_VERBOSE")

    /**
     * Every logger name this mod logs through. Minecraft's own log4j2 config has no entries
     * for them, so without this they inherit the root level (INFO, or DEBUG in a Loom dev
     * run) and print regardless of what the player configured.
     *
     * `dev.nts.expdxr` is the package logger: it covers the class-derived names that
     * `LogUtils.getLogger()` hands out (`dev.nts.expdxr.Illusion`, `…backend.D3D12Backend`, …)
     * which are *not* spelled out here one by one. The remaining entries are the explicit
     * names used by the renderer and the temporal modules.
     */
    private val OWN_LOGGERS: List<String> =
        listOf("dev.nts.expdxr", "backend", "dxr_mc-temporal", "dxr_mc-velocity", "dxr_mc")

    /** Native ordinal for "give me everything" (see [IllusionLogLevel.nativeLevel]). */
    private const val NATIVE_DEBUG = 3

    @Volatile
    private var configured: IllusionLogLevel =
        if (ENV_VERBOSE) IllusionLogLevel.DEBUG
        else IllusionLogLevel.ERROR

    @Volatile
    private var nativeVerbose: Boolean = false

    /**
     * Last banner this object printed, so the unavoidable double call (both `@Mod` entry
     * classes apply the startup default, and whichever FML constructs first may not be the
     * one that owns the config) reports the level once instead of twice. A later config load
     * that *changes* the level still prints its own line.
     */
    @Volatile
    private var lastBanner: String? = null

    /** Current level (after the config, or the error default before it is read). */
    @JvmStatic
    fun level() = configured

    /** DEBUG and above - gates the high-frequency dumps. */
    @JvmStatic
    fun debugEnabled(): Boolean = ENV_VERBOSE || configured.ordinal >= IllusionLogLevel.DEBUG.ordinal

    /**
     * Diagnostic print. Earlier builds wrote these to `System.err` unconditionally; the
     * *formatting* is intentionally left to the caller inside a [debugEnabled] guard so a
     * disabled line costs nothing.
     */
    @JvmStatic
    fun debugMessage(message: String) {
        if (debugEnabled()) LOGGER.debug(message)
    }

    /** [String.format]-style variant of [debugMessage]; `%n` and `%s` behave as `printf` did. */
    @JvmStatic
    fun debugFormat(format: String, vararg args: Any?) {
        if (debugEnabled()) LOGGER.debug(format.format(*args))
    }

    /**
     * Something went wrong but the engine keeps running (a fallback, a dropped frame, a
     * rejected shader edit). Unlike [debugMessage] this is *not* gated on the mod's own level -
     * log4j's configured level decides, so `logLevel = WARN` shows it and the default
     * `ERROR` hides it.
     */
    @JvmStatic
    fun warnMessage(message: String) {
        LOGGER.warn(message)
    }

    /** [String.format]-style variant of [warnMessage]. */
    @JvmStatic
    fun warnFormat(format: String, vararg args: Any?) {
        LOGGER.warn(format.format(*args))
    }

    /** Apply the values currently held by `config/dxr_mc-client.toml`. */
    fun applyFromConfig() {
        applyOpt(IllusionConfig.LOG_LEVEL.get(), IllusionConfig.NATIVE_VERBOSE.get())
    }

    /**
     * Apply the built-in default immediately, so anything logged between mod construction
     * and the config-load event is already filtered.
     */
    fun applyDefaults() {
        applyOpt(if (ENV_VERBOSE) IllusionLogLevel.DEBUG else IllusionLogLevel.ERROR, false)
    }

    fun applyOpt(level: IllusionLogLevel, nativeVerbose: Boolean) {
        // `D3D12_LOG_VERBOSE=1` stays an escape hatch that *wins over the config file*, so a
        // developer gets a verbose run without editing (or remembering) the toml.
        val effective = if (ENV_VERBOSE) IllusionLogLevel.DEBUG else level
        configured = effective
        this.nativeVerbose = nativeVerbose
        for (name in OWN_LOGGERS) {
            Configurator.setLevel(name, effective.toLog4j())
        }
        val nativeLevel = if (ENV_VERBOSE || nativeVerbose) NATIVE_DEBUG else effective.nativeLevel
        pushNative(nativeLevel)
        // Reported at INFO: a default run stays silent, while a verbose run can confirm that
        // the *config* (and not a leftover environment variable) set the level.
        val source = if (ENV_VERBOSE) "D3D12_LOG_VERBOSE" else "config/dxr_mc-client.toml"
        val banner = "[dxrmc] log level = $effective (native $nativeLevel, source: $source)"
        if (banner != lastBanner) {
            lastBanner = banner
            LOGGER.info(banner)
        }
    }

    private fun pushNative(level: Int) {
        try {
            // Loading the native library here (first call) is intentional and safe: it only
            // LoadLibrary's MCDR.dll and binds exports, and isAvailable() reports a missing
            // DLL instead of throwing. Pushing before dx12CreateDevice is what the native
            // side expects.
            if (MCDR_ABI.isAvailable()) {
                MCDR_ABI.D3D12SetLogLevel(level)
            }
        } catch (t: Throwable) {
            LOGGER.warn("[dxrmc] could not push the native log level {}", level, t)
        }
    }

    const val i18n_LOG_KEY = "dxr_mc.configuration.logging"
}