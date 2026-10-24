package dev.nts.expdxr.misc.block.client

import java.util.EnumSet
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState
import net.minecraft.core.Direction

/**
 * 天空方块的渲染状态：只携带本帧真正要画出来的面。
 *
 * 被相邻不透明方块挡住的面直接不提交 —— 一个方块最多 6 个四边形，过滤掉看不见的面既省顶点，
 * 也避免和邻居的面产生深度冲突（同一个平面上的两个面会 z-fighting）。
 */
class SkyBlockRenderState : BlockEntityRenderState() {
    /** 本帧需要绘制的面朝向。 */
    val facesToShow: MutableSet<Direction> = EnumSet.noneOf(Direction::class.java)
}
