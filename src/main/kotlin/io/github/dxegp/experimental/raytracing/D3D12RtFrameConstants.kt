package io.github.dxegp.experimental.raytracing

import org.joml.Matrix4f
import org.joml.Vector3f
import io.github.dxegp.d3d12.render.advanced.taa.D3D12TemporalSampling
import java.nio.ByteBuffer
import kotlin.math.atan

/**
 * 光追的常量缓冲：光影包 `WgaFrame`（b0，416 B）的宿主侧镜像。
 * 见 docs/rt/README.md
 */
object D3D12RtFrameConstants {
    const val BYTES = 416

    /** 太阳角半径（弧度），与 `kSunAngularRadius` 一致。 */
    const val SUN_ANGULAR_RADIUS = 0.0187f

    private const val VIEW_PROJ = 0
    private const val PREV_VIEW_PROJ = 64
    private const val INV_VIEW_PROJ = 128
    private const val CAMERA_POS = 192
    private const val SCREEN = 208
    private const val SUN_DIR = 224
    private const val SUN_RADIANCE = 240
    private const val MOON_RADIANCE = 256
    private const val TIME = 272
    private const val EXPOSURE = 288
    private const val INDIRECT = 304
    private const val WATER = 320
    private const val CLOUDS = 336
    private const val CLOUDS2 = 352
    private const val VOLUME = 368
    private const val FROXEL_PARAMS = 384
    private const val MISC = 400

    /** 列主序铺开一个 4×4（HLSL 的 cbuffer float4x4 也是列主序）。 */
    private fun putMatrix(target: ByteBuffer, offset: Int, values: FloatArray) {
        for (i in values.indices) target.putFloat(offset + i * 4, values[i])
    }

    /** SL 使用行向量和行主序；JOML 列主序数组等于转置矩阵的行主序。 */
    private fun putStreamlineMatrix(source: Matrix4f, target: FloatArray, at: Int) {
        source.get(target, at)
    }

    /**
     * `outSlConstants` 需要的浮点个数：四个重投影矩阵、相机参数与视图正逆矩阵。
     *
     * 带类型标注是故意的——`_port/frame_constants_check.py` 只把 `const val NAME = 数字` 当作 cbuffer
     * 字段，这个常量不属于 `WgaFrame`，写成带类型的形式可避免被当成多出来的字段。
     */
    const val SL_CONSTANTS_FLOATS: Int = 104

    private fun ByteBuffer.putFloatsAt(offset: Int, a: Float, b: Float, c: Float, d: Float) {
        putFloat(offset, a)
        putFloat(offset + 4, b)
        putFloat(offset + 8, c)
        putFloat(offset + 12, d)
    }

    /**
     * 只改 `g_misc.y`（调试模式），其余常量保持上一帧写好的值。
     *
     * 调试图与追踪共用同一个常量槽：追踪每帧都会把它写回 0，所以单通道档必须在派发前自己打一次，
     * 否则着色器永远按通道 0 取（`debugChannel` 的 channel 参数就成了摆设）。
     */
    fun patchDebugMode(target: ByteBuffer, mode: Int) {
        target.putFloat(MISC + 4, mode.toFloat())
    }

    /**
     * 写一帧常量 `WgaFrame`。
     *
     * `debugMode` 进 `g_misc.y`（光影包把这一位定义成调试模式；B-DXR 里 0..4 是调试图通道，
     * 8 是自检探针），`radius`/`recordCount` 占 `g_misc.z`/`g_misc.w`（misc 的 z/w 本就是备用）。
     * [previousViewProjection] 由调用方保存（运动矢量与重投影靠它），为空时按零位移处理；
     * [outViewProjection] 顺手把这一帧的矩阵交回给调用方，下次当上一帧用。
     */
    fun write(
        target: ByteBuffer,
        position: RtVec3,
        forward: RtVec3,
        up: RtVec3,
        tanHalfFovY: Float,
        aspect: Float,
        width: Int,
        height: Int,
        sunDir: RtVec3,
        sunRadiance: RtVec3,
        sunStrength: Float,
        frameIndex: Int,
        radius: Float,
        recordCount: Int,
        debugMode: Int,
        exposure: Float = 1f,
        previousViewProjection: FloatArray? = null,
        previousViewMatrix: FloatArray? = null,
        outViewProjection: FloatArray? = null,
        outNrdMatrices: FloatArray? = null,
        outSlConstants: FloatArray? = null,
        viewMatrix: FloatArray? = null,
        projectionMatrix: FloatArray? = null,
        farPlane: Float = 1024f,
        jitterX: Float = 0f,
        jitterY: Float = 0f,
        previousJitterX: Float = 0f,
        previousJitterY: Float = 0f,
    ) {
        val view = viewMatrix?.let { Matrix4f().set(it) } ?: Matrix4f().lookAt(
            Vector3f(position.x, position.y, position.z),
            Vector3f(position.x + forward.x, position.y + forward.y, position.z + forward.z),
            Vector3f(up.x, up.y, up.z),
        )
        val fovY = (2f * atan(tanHalfFovY.toDouble())).toFloat()
        val projection = projectionMatrix?.let { Matrix4f().set(it) }
            ?: Matrix4f().perspective(fovY, aspect, 0.05f, farPlane)
        val viewProjection = projection.mul(view, Matrix4f())
        val inverse = viewProjection.invert(Matrix4f())
        val sampleProjection = Matrix4f().set(D3D12TemporalSampling.jitterProjection(
            projection.get(FloatArray(16)), 2.0 * jitterX / width, 2.0 * jitterY / height,
        ))
        val sampleViewProjection = sampleProjection.mul(view, Matrix4f())

        // HLSL 的 cbuffer float4x4 默认列主序，JOML 的 get() 也按列铺开，直接对拷即可。
        val scratch = FloatArray(16)
        sampleViewProjection.get(scratch)
        putMatrix(target, VIEW_PROJ, scratch)
        // 上一帧的矩阵由调用方保存：运动矢量与重投影都要它，没给就退化成「零位移」。
        putMatrix(target, PREV_VIEW_PROJ, previousViewProjection?.let {
            D3D12TemporalSampling.jitterProjection(it,
                2.0 * previousJitterX / width, 2.0 * previousJitterY / height)
        } ?: scratch)
        if (outNrdMatrices != null && outNrdMatrices.size >= 64) {
            projection.get(outNrdMatrices, 0)
            if (previousViewProjection != null && previousViewMatrix != null) {
                Matrix4f().set(previousViewProjection)
                    .mul(Matrix4f().set(previousViewMatrix).invert())
                    .get(outNrdMatrices, 16)
            } else {
                projection.get(outNrdMatrices, 16)
            }
            view.get(outNrdMatrices, 32)
            if (previousViewMatrix != null) {
                previousViewMatrix.copyInto(outNrdMatrices, 48)
            } else {
                view.get(outNrdMatrices, 48)
            }
        }
        sampleViewProjection.invert(Matrix4f()).get(scratch)
        putMatrix(target, INV_VIEW_PROJ, scratch)

        if (outSlConstants != null && outSlConstants.size >= SL_CONSTANTS_FLOATS) {
            putStreamlineMatrix(projection, outSlConstants, 0)
            putStreamlineMatrix(projection.invert(Matrix4f()), outSlConstants, 16)
            if (previousViewProjection != null && previousViewProjection.size >= 16) {
                val previous = Matrix4f().set(previousViewProjection)
                val clipToPrev = previous.mul(inverse, Matrix4f())
                putStreamlineMatrix(clipToPrev, outSlConstants, 32)
                putStreamlineMatrix(clipToPrev.invert(Matrix4f()), outSlConstants, 48)
            } else {
                putStreamlineMatrix(Matrix4f(), outSlConstants, 32)
                putStreamlineMatrix(Matrix4f(), outSlConstants, 48)
            }
            outSlConstants[64] = 0.05f
            outSlConstants[65] = farPlane
            outSlConstants[66] = fovY
            outSlConstants[67] = aspect
            outSlConstants[68] = jitterX
            outSlConstants[69] = -jitterY
            outSlConstants[70] = if (previousViewProjection != null) previousJitterX else jitterX
            outSlConstants[71] = if (previousViewProjection != null) -previousJitterY else -jitterY
            putStreamlineMatrix(view, outSlConstants, 72)
            putStreamlineMatrix(view.invert(Matrix4f()), outSlConstants, 88)
        }

        target.putFloatsAt(CAMERA_POS, position.x, position.y, position.z, frameIndex.toFloat())
        target.putFloatsAt(SCREEN, width.toFloat(), height.toFloat(), 1f / width, 1f / height)
        target.putFloatsAt(SUN_DIR, sunDir.x, sunDir.y, sunDir.z, SUN_ANGULAR_RADIUS)
        target.putFloatsAt(SUN_RADIANCE, sunRadiance.x, sunRadiance.y, sunRadiance.z, sunStrength)
        target.putFloatsAt(MOON_RADIANCE, 0f, 0f, 0f, 0.0273f)
        target.putFloatsAt(TIME, 0f, 0f, 0f, 0f)
        target.putFloatsAt(EXPOSURE, exposure, 1f, 2f, 1f)
        target.putFloatsAt(INDIRECT, 1f, 2f, 8f, 1e-4f)
        target.putFloatsAt(WATER, 0.05f, 0.12f, 0.16f, 0.22f)
        target.putFloatsAt(CLOUDS, 3000f, 11000f, 0.5f, 1f)
        target.putFloatsAt(CLOUDS2, 0.30f, 0.60f, 0f, 0f)
        target.putFloatsAt(VOLUME, 50f, 10f, 0.012f, 64f)
        target.putFloatsAt(FROXEL_PARAMS, 160f, 90f, 32f, 1f)
        target.putFloatsAt(MISC, 0.12f, debugMode.toFloat(), radius, recordCount.toFloat())
        if (outViewProjection != null) {
            viewProjection.get(outViewProjection)
        }
    }
}
