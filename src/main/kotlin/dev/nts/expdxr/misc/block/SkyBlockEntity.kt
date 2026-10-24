package dev.nts.expdxr.misc.block

import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState

/**
 * 天空方块实体。
 *
 * 不保存任何数据：六个面到底露不露出来是每帧在客户端算的（只有玩家真正看得见的天空方块才会
 * 被提取渲染状态），所以这里不需要 [net.minecraft.world.level.Level] 的回调去失效任何缓存。
 */
class SkyBlockEntity(pos: BlockPos, state: BlockState) :
    BlockEntity(SkyBlocks.SKY_BLOCK_ENTITY.get(), pos, state)
