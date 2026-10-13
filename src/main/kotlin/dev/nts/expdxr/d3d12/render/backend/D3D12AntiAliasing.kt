package dev.nts.expdxr.d3d12.render.backend

import com.mojang.serialization.Codec
import dev.nts.expdxr.Illusion
import dev.nts.expdxr.d3d12.render.adapter.D3D12AdapterInfo
import java.io.File
import java.nio.charset.StandardCharsets
import net.minecraft.client.OptionInstance
import net.minecraft.client.Options
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.network.chat.Component

/**
 * The Direct3D 12 backend's anti-aliasing mode, exposed as an ordinary vanilla
 * video setting instead of a mod-config entry.
 *
 * The Fabric build kept this value in its own `config/gl4dx12.properties`, and
 * the NeoForge port then moved it into a
 * [net.neoforged.neoforge.common.ModConfigSpec] entry (which put an
 * "Anti-Aliasing" field on the mod's own config screen). It now lives where
 * every other rendering knob lives: the option is appended to the quality
 * section of the vanilla video-settings screen by
 * [dev.nts.expdxr.mixin.options.VideoSettingsScreenMixin], and it is persisted in
 * vanilla's own `options.txt` by
 * [dev.nts.expdxr.mixin.options.OptionsAntiAliasingMixin].
 *
 * The DX12 renderer reads the selection through [current]; the native backend
 * does not implement anti-aliasing yet, so today the value only records the
 * user's choice.
 */
object D3D12AntiAliasing {
    const val OFF: Int = 0
    const val FXAA: Int = 1
    const val SMAA: Int = 2
    const val TAA: Int = 3

    /** Cycle order of the video-settings button. */
    @JvmField
    val MODES: List<Int> = listOf(OFF, FXAA, SMAA, TAA)

    /** Key used inside `options.txt`; must stay stable, it is user-visible state. */
    const val OPTIONS_KEY: String = "dxrMcAaMode"

    private val OPTION: OptionInstance<Int> = OptionInstance(
        "options.antiAliasing",
        OptionInstance.TooltipSupplier<Int> {
            Tooltip.create(Component.translatable("options.antiAliasing.tooltip"))
        },
        // Only the value is returned here: the option is a cycle button, and
        // vanilla's CycleButton already prefixes the caption itself
        // (CommonComponents.optionNameValue in CycleButton.Builder.create),
        // so including it here renders the label twice.
        OptionInstance.CaptionBasedToString<Int> { _, value ->
            Component.translatable(modeKey(value))
        },
        OptionInstance.Enum<Int>(MODES, Codec.INT),
        FXAA,
        OptionInstance.NO_ACTION
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

    /**
     * Restores the mode from vanilla's `options.txt`. Called at the tail of
     * [Options.load]; the line format is vanilla's own `key:jsonValue`.
     */
    @JvmStatic
    fun load(file: File) {
        if (!file.isFile) {
            return
        }
        try {
            for (line in file.readLines(StandardCharsets.UTF_8)) {
                if (!line.startsWith(OPTIONS_KEY + ":")) {
                    continue
                }
                val mode = line.substring(OPTIONS_KEY.length + 1).trim().toIntOrNull()
                if (mode != null && MODES.contains(mode)) {
                    OPTION.set(mode)
                }
                return
            }
        } catch (t: Throwable) {
            Illusion.LOGGER.warn("[dx12] could not read {} from {}", OPTIONS_KEY, file, t)
        }
    }

    /**
     * Writes the mode back into vanilla's `options.txt`.
     *
     * Vanilla rewrites that file from scratch on every save, so the line has to
     * be re-added afterwards; a stale copy is dropped first so repeated saves
     * cannot pile up duplicates.
     */
    @JvmStatic
    fun save(file: File) {
        if (!file.isFile) {
            return
        }
        try {
            val eol = System.lineSeparator()
            val kept = file.readLines(StandardCharsets.UTF_8)
                .filterNot { it.startsWith(OPTIONS_KEY + ":") }
            val text = (kept + (OPTIONS_KEY + ":" + OPTION.get())).joinToString(eol) + eol
            file.writeText(text, StandardCharsets.UTF_8)
        } catch (t: Throwable) {
            Illusion.LOGGER.warn("[dx12] could not write {} to {}", OPTIONS_KEY, file, t)
        }
    }
}