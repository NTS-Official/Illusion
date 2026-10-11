package dev.nts.expdxr.dx12

import net.minecraft.client.PreferredGraphicsApi
import org.slf4j.LoggerFactory
import net.minecraft.network.chat.Component

/**
 * Runtime holder for the fourth [PreferredGraphicsApi] state that DXRMC adds:
 * the 3-state vanilla cycle button (Default / OpenGL / Vulkan) becomes a 4-state
 * one with Direct3D 12 last, i.e. right after Vulkan.
 *
 * Vanilla cannot be extended by adding a source-level enum constant - the mod
 * ships compiled against the same enum and `$VALUES` is already baked into
 * `<clinit>`. So [dev.nts.expdxr.mixin.PreferredGraphicsApiMixin] constructs the
 * constant reflectively through an `@Invoker("<init>")` accessor, republishes it
 * from `values()` and rebuilds the static `CODEC`.
 *
 * Everything here is looked up at runtime, so [constant] is null until the
 * enum's `<clinit>` has run through the mixin.
 */
object Dx12GraphicsApi {
    /** Serialized name written to `options.txt`. */
    const val SERIALIZED_NAME: String = "dx12"

    /** Translation key of the cycle-button caption. */
    const val CAPTION_KEY: String = "options.graphicsApi.dx12"

    /** Translation key of the extra tooltip paragraph (vanilla's "experimental" note). */
    const val TOOLTIP_KEY: String = "options.graphicsApi.tooltip.dx12"

    /** Ordinal handed to `Enum.<init>`; vanilla occupies 0, 1 and 2. */
    const val ORDINAL: Int = 3

    private const val ENUM_NAME: String = "DX12"

    private val LOGGER = LoggerFactory.getLogger("dx12")

    @Volatile
    private var installed: PreferredGraphicsApi? = null

    /** The appended constant, or null when the mixin has not run yet. */
    @JvmStatic
    fun constant(): PreferredGraphicsApi? = installed

    /** Called once from the enum's `<clinit>` tail. Idempotent. */
    @JvmStatic
    fun install(value: PreferredGraphicsApi) {
        installed = value
    }

    /**
     * Records the resulting cycle-button size. Called by the mixin right after `CODEC`
     * has been rebuilt, because that rebuild is what makes the appended entry survive a
     * round trip through `options.txt`.
     */
    @JvmStatic
    fun logInstalled(stateCount: Int) {
        LOGGER.info(
            "[dx12] graphics API \"{}\" appended; the cycle button now has {} states",
            SERIALIZED_NAME,
            stateCount
        )
    }

    /**
     * True only for the DXRMC constant; every vanilla value returns false.
     *
     * [value] is deliberately `Any?` rather than [PreferredGraphicsApi]: the tooltip
     * injection point is the shared `OptionInstance` constructor, so call sites pass
     * every option type the game has, and an erased lambda parameter would insert a
     * checkcast that throws ClassCastException on, say, an Integer.
     */
    @JvmStatic
    fun isDx12(value: Any?): Boolean {
        val current = installed ?: return false
        return value === current
    }

    /** The "experimental" tooltip paragraph, mirroring `options.graphicsApi.tooltip.vulkan`. */
    @JvmStatic
    fun dx12TooltipLine(): Component = Component.translatable(TOOLTIP_KEY)

    /** Enum constant name; kept here so the mixin does not repeat the string. */
    @JvmStatic
    fun enumName(): String = ENUM_NAME
}