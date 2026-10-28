package dev.nts.expdxr.d3d12.backend

import net.minecraft.client.PreferredGraphicsApi
import net.minecraft.network.chat.Component
import org.slf4j.LoggerFactory

/**
 * Runtime holder for the fourth [PreferredGraphicsApi] state that DXRMC adds:
 * the 3-state vanilla cycle button (Default / OpenGL / Vulkan) becomes a 4-state
 * one with Direct3D 12 last, i.e. right after Vulkan.
 *
 * Vanilla cannot be extended by adding a source-level enum constant - the mod
 * ships compiled against the same enum and `$VALUES` is already baked into
 * `<clinit>`. So [dev.nts.expdxr.mixin.options.PreferredGraphicsApiMixin] constructs the
 * constant reflectively through an `@Invoker("<init>")` accessor, republishes it
 * from `values()` and rebuilds the static `CODEC`.
 *
 * Everything here is looked up at runtime, so [constant] is null until the
 * enum's `<clinit>` has run through the mixin.
 */
object D3D12GraphicsApi {
    const val SERIALIZED_NAME = "d3d12"
    const val CAPTION_KEY = "options.graphicsApi.d3d12"
    const val TOOLTIP_KEY = "options.graphicsApi.tooltip.d3d12"
    const val ORDINAL = 3

    private const val ENUM_NAME: String = "D3D12"

    private val LOGGER = LoggerFactory.getLogger("backend")

    @Volatile private var installed: PreferredGraphicsApi? = null

    @JvmStatic fun constant(): PreferredGraphicsApi? = installed

    @JvmStatic fun install(value: PreferredGraphicsApi) { installed = value }


    /**
     * Records the resulting cycle-button size. Called by the mixin right after `CODEC`
     * has been rebuilt, because that rebuild is what makes the appended entry survive a
     * round trip through `options.txt`.
     */
    @JvmStatic
    fun logInstalled(stateCount: Int) {
        LOGGER.info(
            "graphics API \"{}\" appended; the cycle button now has {} states",
            SERIALIZED_NAME,
            stateCount
        )
    }

    /**
     * True only for the DXRMC constant; every vanilla value returns false.
     *
     * [value] is deliberately `Any?` rather than [PreferredGraphicsApi]: the tooltip
     * injection point is the shared `OptionInstance` constructor, so call sites pass
     * every options type the game has, and an erased lambda parameter would insert a
     * checkcast that throws ClassCastException on, say, an Integer.
     */
    @JvmStatic
    fun isDx12(value: Any?): Boolean {
        val current = installed ?: return false
        return value === current
    }

    @JvmStatic fun dx12TooltipLine(): Component = Component.translatable(TOOLTIP_KEY)

    @JvmStatic fun enumName(): String = ENUM_NAME
}