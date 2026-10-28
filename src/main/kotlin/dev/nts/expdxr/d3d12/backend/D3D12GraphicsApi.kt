package dev.nts.expdxr.d3d12.backend

import net.minecraft.client.PreferredGraphicsApi
import net.minecraft.network.chat.Component
import org.slf4j.LoggerFactory

/**
 * DXRMC 追加的第四个 [PreferredGraphicsApi] 状态（Direct3D 12，排在 Vulkan 之后）。
 *
 * 枚举不能在源码层扩展（`$VALUES` 已烙进 vanilla 的 `<clinit>`），由
 * [dev.nts.expdxr.mixin.options.PreferredGraphicsApiMixin] 用 `@Invoker("<init>")` 反射构造后
 * 重发 `values()` 并重建 `CODEC`。故这里一切都在运行时查：[constant] 在 `<clinit>` 跑完前是 null。
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


    /** 由 mixin 在 `CODEC` 重建后调用：只有重建过，追加项才能经 options.txt 往返存活。 */
    @JvmStatic
    fun logInstalled(stateCount: Int) {
        LOGGER.info(
            "graphics API \"{}\" appended; the cycle button now has {} states",
            SERIALIZED_NAME,
            stateCount
        )
    }

    /**
     * 只对 DXRMC 的常量返回 true，vanilla 值一律 false。
     *
     * [value] 刻意是 `Any?` 而不是 [PreferredGraphicsApi]：注入点是共享的 `OptionInstance` 构造器，
     * 调用点会传各种 options 类型，erased lambda 参数会插 checkcast 而在 Integer 上抛 ClassCastException。
     */
    @JvmStatic
    fun isDx12(value: Any?): Boolean {
        val current = installed ?: return false
        return value === current
    }

    @JvmStatic fun dx12TooltipLine(): Component = Component.translatable(TOOLTIP_KEY)

    @JvmStatic fun enumName(): String = ENUM_NAME
}