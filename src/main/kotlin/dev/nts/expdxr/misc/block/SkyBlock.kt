package dev.nts.expdxr.misc.block

import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.BaseEntityBlock
import net.minecraft.world.level.block.RenderShape
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState

/**
 * 天空方块 —— 一个「看不见的立方体」，内部投影出真正的天空。
 *
 * 方块本体不画任何东西（[RenderShape.INVISIBLE] 表示连方块模型都不提交），真正的画面来自
 * `dev.nts.expdxr.misc.block.client.SkyBlockEntityRenderer`：它把这一帧主渲染目标里已经画好的
 * 天空当成贴图，按屏幕像素重新贴到这个立方体的六个面上。于是站在方块内部抬头，看到的就是
 * 当前维度的天空（太阳、月亮、星星、云、雨雪自然都在），而不是一张静态天空盒贴图。
 *
 * 之所以要方块实体而不是只用一个模型：屏幕空间投影必须知道「这个方块哪个面真的露在外面」，
 * 那需要邻居方块状态，而 `BlockState` 本身不持有它。
 */
class SkyBlock(properties: BlockBehaviour.Properties) : BaseEntityBlock(properties) {
    override fun getRenderShape(state: BlockState): RenderShape = RenderShape.INVISIBLE

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity = SkyBlockEntity(pos, state)
}
