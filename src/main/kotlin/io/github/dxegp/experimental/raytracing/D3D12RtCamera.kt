package io.github.dxegp.experimental.raytracing

import org.joml.Matrix4f
import org.joml.Matrix4fc
import org.joml.Vector3f
import kotlin.math.cos
import kotlin.math.sin

object D3D12RtCamera {
    class Transform(
        val position: RtVec3,
        val offset: RtVec3,
        val right: RtVec3,
        val up: RtVec3,
        val forward: RtVec3,
        val view: FloatArray,
        val projection: FloatArray,
    )

    fun resolve(
        position: RtVec3,
        viewRotation: Matrix4fc,
        baseProjection: Matrix4fc,
        renderProjection: Matrix4fc,
    ): Transform {
        val relativeView = Matrix4f(baseProjection).invert().mul(renderProjection).mul(viewRotation)
        val relativeInverse = relativeView.invert(Matrix4f())
        val offset = relativeInverse.getTranslation(Vector3f())
        val right = relativeInverse.transformDirection(Vector3f(1f, 0f, 0f)).normalize()
        val up = relativeInverse.transformDirection(Vector3f(0f, 1f, 0f)).normalize()
        val forward = relativeInverse.transformDirection(Vector3f(0f, 0f, -1f)).normalize()
        return Transform(
            position + RtVec3(offset.x, offset.y, offset.z),
            RtVec3(offset.x, offset.y, offset.z),
            RtVec3(right.x, right.y, right.z),
            RtVec3(up.x, up.y, up.z),
            RtVec3(forward.x, forward.y, forward.z),
            relativeView.translate(-position.x, -position.y, -position.z).get(FloatArray(16)),
            baseProjection.get(FloatArray(16)),
        )
    }

    fun sunDirection(angleRadians: Float): RtVec3 = RtVec3(-sin(angleRadians), cos(angleRadians), 0f)
}
