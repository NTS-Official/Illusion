package dev.nts.expdxr.misc

import net.minecraft.network.chat.Component
import net.neoforged.neoforge.common.TranslatableEnum

/**
 * P238：VRS 的玩家可见取值 —— 只剩"关"与"自动"两档。
 *
 * <p>手动档位（1X1 / 1X2 / 2X1 / 2X2 / 2X4 / 4X2 / 4X4）在 P233 的实测之后删掉了：它们作用在
 * 整条绘制批次（或整个图元）上，距离判据分不清"平坦地形"与"地平线附近那条细几何"，于是
 * 无论选哪一档都会在地平线留下闪烁（取证见 `known_bugs.md` 的 P233 条目）。
 *
 * <p>真正该决定"这块像素粗不粗"的是 Tier 2 的着色率图像（P235）：它按 16x16 tile 判内容，
 * 平坦远景照样省、边缘保持 1X1。到那一步玩家没有"选一档"的余地，界面上只留自动。
 * 在那之前 [AUTO] 走的是 mesh shader 的逐图元距离带（P233），档位上限固定 2X2。
 */
enum class D3D12VRSSettings : TranslatableEnum {
    /** 关闭：一个 `RSSetShadingRate` 都不发，设备保持默认 1X1。 */
    OFF,

    /** 自动：由渲染器决定每块像素的着色率（当前 = 逐图元距离带，Tier 2 落地后 = 着色率图像）。 */
    AUTO;

    /** 视频设置里这个取值的显示名（`options.shadingRate` 的取值栏）。 */
    override fun getTranslatedName(): Component =
        Component.translatable("options.illusion.render.shadingRate.${this.name}")
}
