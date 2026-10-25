package dev.nts.expdxr.misc.block.client

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.serialization.MapCodec
import java.util.function.Consumer
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.special.NoDataSpecialModelRenderer
import net.minecraft.client.renderer.special.SpecialModelRenderer
import net.minecraft.core.Direction
import org.joml.Vector3fc

/**
 * 天空方块的**物品**外观：和放下的方块画同一个立方体、同一套屏幕空间投影。
 *
 * 走的是原版 26.3 的「特殊物品模型」（special model renderer）通道：`items/sky_block.json` 里写
 * `{"model": {"type": "minecraft:special", "model": {"type": "dxr_mc:sky_block"}, "base": ...}}`，
 * 由本对象在 [SkyBlockClientSetup] 里注册进 `RegisterSpecialModelRendererEvent`。
 * 这样物品在手里、第三人称、掉落物、物品展示框和界面里走的都是同一条提交路径，和方块实体用的
 * 是同一个 [SkyBlockPipeline] RenderType —— GLSL、管线、贴图都不分叉。
 *
 * 采样坐标由片元着色器用 `gl_FragCoord × pixelScale` 算，所以每帧要告诉它「当前绘制目标有多大」：
 * - 世界里（手持、掉落物、展示框）：绘制目标是渲染缩放后的世界目标，基准就是离屏天空自己的尺寸；
 * - 界面里（物品栏、快捷栏）：绘制目标是窗口尺寸（本项目开了渲染缩放时界面单独一张全尺寸目标），
 *   基准换成窗口尺寸；此时方块面显示的是「屏幕上这个位置背后的天空」。
 * 这个判断完全在后端无关的层面做（见 [SkyBlockCapture.projectedScaleX]），D3D12 与 OpenGL/Vulkan
 * 跑的是同一段代码。
 *
 * 离屏天空还没准备好的第一帧直接跳过提交（而不是让动态贴图抛异常）。
 */
object SkyBlockSpecialRenderer : NoDataSpecialModelRenderer {
    override fun submit(
        poseStack: PoseStack,
        submitNodeCollector: SubmitNodeCollector,
        lightCoords: Int,
        overlayCoords: Int,
        hasFoil: Boolean,
        outlineColor: Int
    ) {
        if (!SkyBlockPipeline.isShaderPresent()) {
            return
        }
        if (!SkyBlockCapture.isReady()) {
            return
        }
        // 只要手上/界面里有天空方块，下一帧就要有新的离屏天空（当前帧的拷贝发生在世界渲染
        // 的天空 pass 之后，晚于手持物品的提交，所以这里置位的是「下一帧」）。
        SkyBlockCapture.requestCapture()
        val scaleX = SkyBlockCapture.projectedScaleX()
        val scaleY = SkyBlockCapture.projectedScaleY()
        val renderType = SkyBlockPipeline.renderType()
        submitNodeCollector.submitCustomGeometry(poseStack, renderType) { pose, buffer ->
            for (direction in Direction.values()) {
                for (corner in SkyBlockGeometry.FACE_CORNERS.getValue(direction)) {
                    buffer.addVertex(pose, corner.x(), corner.y(), corner.z()).setUv(scaleX, scaleY)
                }
            }
        }
    }

    override fun getExtents(output: Consumer<Vector3fc>) {
        SkyBlockGeometry.extents(output)
    }

    /**
     * 物品模型 JSON 里 `"type": "dxr_mc:sky_block"` 的反序列化器。
     *
     * 没有参数，所以用 `MapCodec.unit`：JSON 里除了 `type` 什么都不用写。
     */
    object Unbaked : NoDataSpecialModelRenderer.Unbaked {
        @JvmField
        val MAP_CODEC: MapCodec<Unbaked> = MapCodec.unit(this)

        override fun type(): MapCodec<out NoDataSpecialModelRenderer.Unbaked> = MAP_CODEC

        override fun bake(context: SpecialModelRenderer.BakingContext): SpecialModelRenderer<Void> =
            SkyBlockSpecialRenderer
    }
}
