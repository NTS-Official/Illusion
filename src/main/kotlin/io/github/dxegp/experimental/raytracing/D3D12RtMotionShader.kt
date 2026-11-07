package io.github.dxegp.experimental.raytracing

/**
 * 运动矢量（S4 4-b）的入口描述。
 * 见 docs/rt/README.md §19
 */
object D3D12RtMotionShader {
    const val ENTRY = "RtMotion"

    /** t0 = G-Buffer 的线性深度。 */
    const val SRV_COUNT = 1

    /** u0 = 运动矢量目标。 */
    const val UAV_COUNT = 1

    private const val SOURCE = "rt_motion.hlsl"

    fun build(): String = D3D12RtShaderSources.load(D3D12ShaderPacks.sourcePath(SOURCE))
}
