package dev.nts.expdxr.options

import net.minecraft.network.chat.Component
import net.neoforged.neoforge.common.TranslatableEnum

enum class D3D12VRSEnumSettings(val nativeRate: Int) : TranslatableEnum {
    /** 关闭：不下发任何着色速率调用，设备保持默认 1x1。 */
    OFF(-1),
    RATE_1X1(0x0),
    RATE_1X2(0x1),
    RATE_2X1(0x4),
    RATE_2X2(0x5),
    /** 2x4 起要求 `AdditionalShadingRatesSupported`。 */
    RATE_2X4(0x6),
    RATE_4X2(0x9),
    RATE_4X4(0xA);

    /** 视频设置里这个档位的显示名（`D3D12ShadingRate` 的循环按钮取值，即`options.shadingRate` 的取值栏） */
    override fun getTranslatedName(): Component =
        Component.translatable("options.illusion.render.shadingRate.${this.name}")
}