package dev.nts.expdxr.misc.settings

import net.minecraft.network.chat.Component
import net.neoforged.neoforge.common.TranslatableEnum

/**
 * VRS 的玩家可见取值只有"关"与"自动"：手动档作用在整条绘制批次上，距离判据分不清平坦地形与
 * 地平线附近的细几何，任何一档都会在地平线闪烁，因此删除。
 */
enum class D3D12VRSSettings : TranslatableEnum {
    /** 关闭：一个 `RSSetShadingRate` 都不发，设备保持默认 1X1。 */
    OFF,

    /** 自动：逐图元距离带（上限 2X2）；Tier 2 着色率图像落地后改由它按 16x16 tile 判定。 */
    AUTO;

    override fun getTranslatedName(): Component =
        Component.translatable("options.illusion.render.shadingRate.${this.name}")
}
