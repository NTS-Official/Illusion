package dev.nts.expdxr.misc.block.client

import com.mojang.blaze3d.vertex.PoseStack
import dev.nts.expdxr.misc.block.SkyBlockEntity
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState
import net.minecraft.client.renderer.feature.ModelFeatureRenderer
import net.minecraft.client.renderer.state.level.CameraRenderState
import net.minecraft.core.Direction
import net.minecraft.world.level.block.Block
import net.minecraft.world.phys.Vec3

/**
 * 天空方块渲染器：把一个 1x1x1 的立方体按「屏幕空间投影」画出来。
 *
 * 顶点位置就是方块的六个面（相机相对坐标，由 `LevelRenderer` 在提交前平移好），而采样坐标由片元
 * 着色器用 `gl_FragCoord` 推出来 —— 于是这个面在屏幕上覆盖的每一个像素，取到的正是同一像素在天空
 * 离屏贴图里的颜色。结果是：站在方块里看，看到的是「把这一块立方体从视野里挖掉，露出后面的天空」，
 * 而不是把天空拉伸到方块面上的贴纸。这也是 literal-sky-block 的做法。
 *
 * 面可见性每帧重算（`Block.shouldRenderFace`）：被不透明邻居挡住的面不提交，方块之间相互贴合时
 * 不会因为共面而 z-fighting。
 */
class SkyBlockEntityRenderer : BlockEntityRenderer<SkyBlockEntity, SkyBlockRenderState> {
    override fun createRenderState(): SkyBlockRenderState = SkyBlockRenderState()

    override fun extractRenderState(
        blockEntity: SkyBlockEntity,
        state: SkyBlockRenderState,
        partialTicks: Float,
        cameraPosition: Vec3,
        breakProgress: ModelFeatureRenderer.CrumblingOverlay?
    ) {
        BlockEntityRenderState.extractBase(blockEntity, state, breakProgress)
        state.facesToShow.clear()
        val level = blockEntity.level ?: return
        val pos = blockEntity.blockPos
        val ownState = blockEntity.blockState
        for (direction in Direction.values()) {
            val neighborState = level.getBlockState(pos.relative(direction))
            if (Block.shouldRenderFace(level, pos, ownState, neighborState, direction)) {
                state.facesToShow.add(direction)
            }
        }
    }

    override fun submit(
        state: SkyBlockRenderState,
        poseStack: PoseStack,
        submitNodeCollector: SubmitNodeCollector,
        camera: CameraRenderState
    ) {
        if (state.facesToShow.isEmpty()) {
            return
        }
        // 离屏天空还没准备好（刚进世界的第一帧、或者资源重载）：整体跳过，而不是让动态贴图抛异常。
        if (!SkyBlockCapture.isReady()) {
            return
        }
        // 采样倍率的基准是**当前绘制目标**的尺寸：世界里就是离屏贴图自己的尺寸（开了渲染缩放时
        // 它比窗口小），界面里换成窗口尺寸。片元着色器的 gl_FragCoord 正好也是同一套像素坐标。
        // 本帧确实要画天空方块 —— 让天空 pass 结束后顺手拷一张离屏天空。
        SkyBlockCapture.requestCapture()
        val scaleX = SkyBlockCapture.projectedScaleX()
        val scaleY = SkyBlockCapture.projectedScaleY()
        val renderType = SkyBlockPipeline.renderType()
        submitNodeCollector.submitCustomGeometry(poseStack, renderType) { pose, buffer ->
            for (direction in state.facesToShow) {
                for (corner in SkyBlockGeometry.FACE_CORNERS.getValue(direction)) {
                    buffer.addVertex(pose, corner.x(), corner.y(), corner.z()).setUv(scaleX, scaleY)
                }
            }
        }
    }

    /** 与方块实体原版默认值（64）不同：天空方块通常是整间屋子的墙，远处也必须画。 */
    override fun getViewDistance(): Int = VIEW_DISTANCE

    private companion object {
        private const val VIEW_DISTANCE = 256
    }
}
