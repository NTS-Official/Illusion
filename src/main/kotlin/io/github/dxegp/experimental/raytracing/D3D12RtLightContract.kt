package io.github.dxegp.experimental.raytracing

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** 光追数学够用的三维向量：只放 CPU 参考实现需要的运算。 */
data class RtVec3(val x: Float, val y: Float, val z: Float) {
    operator fun plus(o: RtVec3) = RtVec3(x + o.x, y + o.y, z + o.z)

    operator fun minus(o: RtVec3) = RtVec3(x - o.x, y - o.y, z - o.z)

    operator fun times(s: Float) = RtVec3(x * s, y * s, z * s)

    /** 逐分量相乘：着色器里 `sunColor * 亮度` 就是逐分量，CPU 参考必须一致。 */
    operator fun times(o: RtVec3) = RtVec3(x * o.x, y * o.y, z * o.z)

    fun dot(o: RtVec3): Float = x * o.x + y * o.y + z * o.z

    fun cross(o: RtVec3): RtVec3 = RtVec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)

    fun length(): Float = sqrt(dot(this))
}

/**
 * `rt_light_contract.hlsli` 的 JVM 镜像：随机数、余弦半球、轮盘赌、遮挡偏移。
 * 每一条都必须与 HLSL 逐式一致（同样的运算顺序、同样的 float32 精度），否则场景自检的。
 * 见 docs/rt/README.md
 */
object D3D12RtLightContract {
    const val PI: Float = 3.14159265358979f
    const val INV_PI: Float = 0.31830988618379f
    const val EPS: Float = 1e-6f

    /** uint32 语义的乘法常量（写成 Int 只是为了让溢出按位回绕）。 */
    private const val MUL_STATE: Int = -1403630843
    private const val MUL_WORD: Int = 277803737
    private const val MUL_STEP: Int = 747796405
    private const val GOLDEN: Int = -1640531527
    private const val BOUNCE_K: Int = -2048144789
    private const val SAMPLE_K: Int = -1028477387

    fun saturate(v: Float): Float = if (v < 0f) 0f else if (v > 1f) 1f else v

    fun normalize(v: RtVec3): RtVec3 = v * (1.0f / sqrt(v.dot(v)))

    /** `RtPcgHash`：纯位运算，无浮点。 */
    fun pcgHash(value: Int): Int {
        val state = value * MUL_STEP + MUL_STATE
        val word = ((state ushr ((state ushr 28) + 4)) xor state) * MUL_WORD
        return (word ushr 22) xor word
    }

    /** `RtSeed`：同一 (pixel, frame, bounce, sample, seed) 恒得同一序列。 */
    fun seed(px: Int, py: Int, frame: Int, bounce: Int, sample: Int, seed: Int): Int {
        var h = pcgHash(px xor pcgHash(py))
        h = pcgHash(h xor (frame * GOLDEN))
        h = pcgHash(h xor (bounce * BOUNCE_K))
        h = pcgHash(h xor (sample * SAMPLE_K))
        return pcgHash(h xor seed)
    }

    /** 可推进的随机状态（HLSL 侧是 `inout uint state`）。 */
    class Rng(var state: Int) {
        /** `RtRngNext`：返回 [0,1)。 */
        fun next(): Float {
            state = state * MUL_STEP + MUL_STATE
            val word = ((state ushr ((state ushr 28) + 4)) xor state) * MUL_WORD
            return ((word ushr 22) xor word).toUInt().toFloat() * (1.0f / 4294967296.0f)
        }

        /** `RtRngNext2`。 */
        fun next2(): Pair<Float, Float> = next() to next()
    }

    /** `RtCosineHemisphere`：单位半球上余弦加权采样，返回单位方向。 */
    fun cosineHemisphere(u: Pair<Float, Float>, n: RtVec3): RtVec3 {
        val r = sqrt(u.first)
        val phi = 2.0f * PI * u.second
        val t = if (abs(n.z) < 0.999f) {
            normalize(RtVec3(0f, 0f, 1f).cross(n))
        } else {
            RtVec3(1f, 0f, 0f)
        }
        val b = n.cross(t)
        return t * (r * cos(phi)) + b * (r * sin(phi)) + n * sqrt(saturate(1.0f - u.first))
    }

    /** `RtCosinePdf`。 */
    fun cosinePdf(cosTheta: Float): Float = (if (cosTheta > 0f) cosTheta else 0f) * INV_PI

    fun sampleGgxVndf(sample: Pair<Float, Float>, normal: RtVec3, view: RtVec3,
                      alpha: Float): Pair<RtVec3, RtVec3> {
        val up = if (abs(normal.z) < 0.999f) RtVec3(0f, 0f, 1f) else RtVec3(1f, 0f, 0f)
        val tangent = normalize(up.cross(normal))
        val bitangent = normal.cross(tangent)
        val localView = RtVec3(view.dot(tangent), view.dot(bitangent), view.dot(normal))
        val stretched = normalize(RtVec3(alpha * localView.x, alpha * localView.y, localView.z))
        val lengthSquared = stretched.x * stretched.x + stretched.y * stretched.y
        val basis1 = if (lengthSquared > 0f) {
            RtVec3(-stretched.y, stretched.x, 0f) * (1f / sqrt(lengthSquared))
        } else {
            RtVec3(1f, 0f, 0f)
        }
        val basis2 = stretched.cross(basis1)
        val radius = sqrt(sample.first)
        val angle = 2f * PI * sample.second
        val diskX = radius * cos(angle)
        val blend = 0.5f * (1f + stretched.z)
        val diskY = (1f - blend) * sqrt(saturate(1f - diskX * diskX)) + blend * radius * sin(angle)
        val projected = basis1 * diskX + basis2 * diskY +
            stretched * sqrt(saturate(1f - diskX * diskX - diskY * diskY))
        val localHalf = normalize(RtVec3(alpha * projected.x, alpha * projected.y,
            projected.z.coerceAtLeast(EPS) + EPS))
        val halfDirection = normalize(tangent * localHalf.x + bitangent * localHalf.y + normal * localHalf.z)
        return halfDirection * (2f * view.dot(halfDirection)) - view to halfDirection
    }

    fun ggxSmithG1(normalDotLight: Float, alpha: Float): Float {
        val alphaSquared = alpha * alpha
        return 2f * normalDotLight / (normalDotLight +
            sqrt(alphaSquared + (1f - alphaSquared) * normalDotLight * normalDotLight)).coerceAtLeast(1e-9f)
    }

    /** `RtRayBias`：沿法线偏移的距离，单位米。 */
    fun rayBias(hitDistance: Float): Float = 1e-3f + 0.01f * hitDistance
}
