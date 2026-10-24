package dev.nts.expdxr.misc.block.client

import java.util.function.Consumer
import net.minecraft.client.renderer.FaceInfo
import net.minecraft.core.Direction
import org.joml.Vector3f
import org.joml.Vector3fc

/**
 * 天空方块立方体的几何：六个面、每面四个角点。
 *
 * 方块实体和物品模型画的是同一个单位立方体（`[0,1]³`），所以角点表只在这里维护一份：
 * - 方块实体：立方体处在方块坐标系里，`[0,1]³` 就是这个方块的体积。
 * - 物品模型：原版在 `ItemTransform.apply` 的最后统一平移 `-0.5`，于是 `[0,1]³` 正好被摆成
 *   以原点为中心的立方体，和普通方块模型（元素 `0..16`）的位置、缩放完全一致。这也是原版
 *   `AbstractEndPortalRenderer.submitSpecial` 直接提交 `[0,1]³` 而不自己平移的原因。
 *
 * 绕序复用 `FaceInfo` 而不是抄一张常量表：绕序由原版维护，我们不用自己记「哪个面从哪个角开始」。
 */
object SkyBlockGeometry {
    private val FROM = Vector3f(0.0f, 0.0f, 0.0f)
    private val TO = Vector3f(1.0f, 1.0f, 1.0f)

    /** 六个方向各四个角点（原版四边形绕序）。 */
    val FACE_CORNERS: Map<Direction, List<Vector3fc>> = buildMap {
        for (direction in Direction.values()) {
            val faceInfo = FaceInfo.fromFacing(direction)
            put(direction, List(4) { index -> faceInfo.getVertexInfo(index).select(FROM, TO) })
        }
    }

    /**
     * 八个角点。物品模型用它算包围盒：GUI 里给物品做旋转适配、视锥剔除都靠这个范围，
     * 不给的话原版会当成「没有体积」，物品可能在界面里被裁掉。
     */
    fun extents(output: Consumer<Vector3fc>) {
        for (x in 0..1) {
            for (y in 0..1) {
                for (z in 0..1) {
                    output.accept(Vector3f(x.toFloat(), y.toFloat(), z.toFloat()))
                }
            }
        }
    }
}
