package dev.nts.expdxr.d3d12.render.advanced

import com.mojang.serialization.Codec
import dev.nts.expdxr.Illusion
import dev.nts.expdxr.d3d12.extern.MCDR_ABI
import dev.nts.expdxr.d3d12.render.adapter.D3D12AdapterInfo
import java.io.File
import java.nio.charset.StandardCharsets
import net.minecraft.client.OptionInstance
import net.minecraft.client.Minecraft
import net.minecraft.client.Options
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.client.gui.screens.options.OptionsSubScreen
import net.minecraft.network.chat.Component

/**
 * The D3D12 backend's antialiasing mode, exposed as a vanilla video setting (`options.txt`).
 *
 * Read through [current], pushed to native via [MCDR_ABI.D3D12SetAntiAliasing]; takes effect on the
 * next blit. See docs/antialiasing/README.md §2.
 */
object D3D12AntiAliasing {
    // Public (not private): the DLSS bridge restores these values, and they are this option's domain.
    const val OFF = 0
    const val SMAA = 1
    const val TAA = 2
    const val DLAA = 3

    /** Cycle order of the button (0..3); DLSS is a separate setting, not a 4th slot. */
    @JvmField
    val MODES: List<Int> = listOf(OFF, SMAA, TAA, DLAA)

    /** Key used inside `options.txt`; must stay stable, it is user-visible state. */
    const val OPTIONS_KEY: String = "dxrMcAaMode"

    private val OPTION: OptionInstance<Int> = OptionInstance(
        "options.antiAliasing",
        {
            Tooltip.create(Component.translatable("options.antiAliasing.tooltip"))
        },
        // Value only: CycleButton prefixes the caption itself, so passing it here renders it twice.
        { _, value ->
            Component.translatable(modeKey(value))
        },
        OptionInstance.Enum<Int>(MODES, Codec.INT),
        OFF,
        // Any change is pushed straight to native, which records it and applies it on the next blit.
        { value -> push(value); markPresetCustom() }
    )

    @JvmStatic
    fun option(): OptionInstance<Int> = OPTION

    /** The selected mode, for the renderer. */
    @JvmStatic
    fun current(): Int = OPTION.get()

    /** True while the game really renders through DXRMC's D3D12 backend. */
    @JvmStatic
    fun isAvailable(): Boolean = D3D12AdapterInfo.current != null

    private fun modeKey(mode: Int): String = "options.antiAliasing.mode.$mode"

    /** 预设正在写这个选项（或正在从 options.txt 恢复）：此时不要把画质预设打成"自定义"。 */
    private var suppressPresetCustom = false

    // DLSS lock: while on, this option is forced to OFF and greyed out in the UI.

    /**
     * Whether DLSS currently owns this option (pushed from the DLSS side; no import here — the
     * dependency is one-way). KDoc must use fully-qualified names: an unresolved link fails K2.
     */
    @Volatile
    private var dlssLock = false

    /** True only when the lock came from this class, i.e. there is a snapshot to restore. */
    private var dlssLockApplied = false

    /** "DLAA already owns DLSS" log, reported once. */
    @Volatile
    private var dlaaOwnsDlssReported = false

    /** Mode the player picked before the lock; [OFF] means there was nothing to snapshot. */
    private var dlssModeSnapshot = OFF

    /** Called by the DLSS side when its toggle changes: on => lock to [OFF], off => restore the pick. */
    @JvmStatic
    fun setDlssLock(locked: Boolean) {
        // DLAA owns the DLSS feature itself, so it must not be locked to OFF (the DLSS injection runs
        // after the AA one and used to undo a DLAA that had just engaged).
        if (locked && OPTION.get() == DLAA) {
            if (!dlaaOwnsDlssReported) {
                dlaaOwnsDlssReported = true
                Illusion.LOGGER.info(
                    "DLSS is on, but anti-aliasing is DLAA — DLAA owns the DLSS feature, "
                        + "so the anti-aliasing option is left untouched")
            }
            return
        }
        dlssLock = locked
        if (locked) {
            if (!dlssLockApplied) {
                // Snapshot only when it is not already OFF: "restore OFF" equals "untouched".
                dlssModeSnapshot = if (OPTION.get() == OFF) OFF else OPTION.get()
                if (OPTION.get() != OFF) {
                    setModeQuietly(OFF)
                }
                dlssLockApplied = true
                Illusion.LOGGER.info("DLSS is on: the anti-aliasing lock is OFF and this option cannot be changed in the video settings")
            }
            return
        }
        if (dlssLockApplied) {
            dlssLockApplied = false
            val restored = dlssModeSnapshot
            dlssModeSnapshot = OFF
            if (restored != OFF && OPTION.get() != restored) {
                setModeQuietly(restored)
            }
            Illusion.LOGGER.info("DLSS is off: anti-aliasing is adjustable again (restored to {})", modeName(restored))
        }
    }

    /** The UI greys the button out on this. */
    @JvmStatic
    fun isLockedByDlss(): Boolean = dlssLock

    /** Writes the value without touching the quality preset or re-pushing to native. */
    private fun setModeQuietly(mode: Int) {
        suppressPresetCustom = true
        try {
            OPTION.set(mode)
        } finally {
            suppressPresetCustom = false
        }
    }

    /**
     * Quality preset -> antialiasing: fast=OFF, fancy=SMAA, fabulous=TAA, anything else untouched.
     * Skip unless on the real D3D12 backend ([isAvailable]), and skip while DLSS holds the lock.
     */
    @JvmStatic
    fun applyFromPreset(serializedName: String) {
        if (!isAvailable()) {
            return
        }
        if (dlssLock) {
            return
        }
        val mode = when (serializedName) {
            "fast" -> OFF
            "fancy" -> SMAA
            "fabulous" -> TAA
            else -> return
        }
        setModeQuietly(mode)
        // Changing the value does not repaint the widget by itself.
        refreshScreenWidget()
    }

    /**
     * Makes the cycle button re-read the value: [OptionInstance.set] never touches the widget, only
     * `OptionsList.resetOption` -> `ResettableOptionWidget.resetValue()` repaints the caption.
     */
    private fun refreshScreenWidget() {
        // `gui` can be null before a screen exists; presets only change from the video-settings screen.
        runCatching {
            val screen = Minecraft.getInstance().gui.screen()
            if (screen is OptionsSubScreen) {
                screen.resetOption(OPTION)
            }
        }
    }

    /** A player edit marks the quality preset "custom", like any vanilla option. */
    private fun markPresetCustom() {
        if (suppressPresetCustom) {
            return
        }
        runCatching {
            val options = Minecraft.getInstance().options
            options.setGraphicsPresetToCustom()   // 可见性由 META-INF/accesstransformer.cfg 放宽
        }
    }

    /** Short name for log lines (lang keys are for the UI). */
    private fun modeName(mode: Int): String = when (mode) {
        OFF -> "OFF"
        SMAA -> "SMAA"
        TAA -> "TAA"
        DLAA -> "DLAA"
        else -> "UNKNOWN($mode)"
    }

    /**
     * Hands the mode to native (no device, no restart); a missing export means an old `MCDR.dll`.
     */
    private fun push(mode: Int) {
        // This is the "re-push" path, not a player edit: it must still be sent or native keeps the
        // old mode. DLAA is implemented by the DLSS feature, not the AA channel, so push OFF for it.
        val pushed = if (mode == DLAA) OFF else mode
        // Contract: the effective mode on success (0 == OFF is legal), 3 downgraded to 0, -1 invalid.
        val status = MCDR_ABI.D3D12SetAntiAliasing(pushed)
        when {
            status == pushed -> Illusion.LOGGER.info("anti-aliasing = {}", modeName(mode))
            status == 0 -> Illusion.LOGGER.warn(
                "anti-aliasing {} is not implemented yet; the renderer falls back to OFF",
                modeName(mode),
            )
            status == Int.MIN_VALUE -> Illusion.LOGGER.info(
                "the loaded MCDR.dll has no mcdrSetAntiAliasing; anti-aliasing stays off",
            )
            else -> Illusion.LOGGER.warn("mcdrSetAntiAliasing({}) returned {}", pushed, status)
        }
    }

    /** Re-pushes the stored selection, for callers that need a forced sync. */
    @JvmStatic
    fun applyCurrent() {
        push(OPTION.get())
    }

    /** Restores the mode from `options.txt` at the tail of [Options.load]; format is `key:jsonValue`. */
    @JvmStatic
    fun load(file: File) {
        if (!file.isFile) {
            return
        }
        try {
            for (line in file.readLines(StandardCharsets.UTF_8)) {
                if (!line.startsWith("$OPTIONS_KEY:")) {
                    continue
                }
                val mode = line.substring(OPTIONS_KEY.length + 1).trim().toIntOrNull()
                if (mode != null && MODES.contains(mode)) {
                    // A restore is not a player edit, so it must not mark the preset custom; while the
                    // DLSS lock is on the line is ignored outright — both injections share one TAIL.
                    if (dlssLock) {
                        Illusion.LOGGER.info(
                            "DLSS is on; ignoring the anti-aliasing mode {} restored from {}",
                            modeName(mode), OPTIONS_KEY,
                        )
                    } else {
                        suppressPresetCustom = true
                        try {
                            OPTION.set(mode)
                        } finally {
                            suppressPresetCustom = false
                        }
                    }
                }
                break
            }
        } catch (t: Throwable) {
            Illusion.LOGGER.warn("could not read {} from {}", OPTIONS_KEY, file, t)
            return
        }
        // Push anyway when the line is missing (first run / user deleted it): UI and native must agree.
        push(OPTION.get())
    }

    /** Writes the mode back into `options.txt`, which vanilla rewrites from scratch on every save. */
    @JvmStatic
    fun save(file: File) {
        if (!file.isFile) {
            return
        }
        try {
            val eol = System.lineSeparator()
            val kept = file.readLines(StandardCharsets.UTF_8)
                .filterNot { it.startsWith("$OPTIONS_KEY:") }
            val text = (kept + ("${OPTIONS_KEY}:${OPTION.get()}")).joinToString(eol) + eol
            file.writeText(text, StandardCharsets.UTF_8)
        } catch (t: Throwable) {
            Illusion.LOGGER.warn("could not write {} to {}", OPTIONS_KEY, file, t)
        }
    }
}