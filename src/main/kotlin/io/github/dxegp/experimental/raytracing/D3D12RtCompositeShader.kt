package io.github.dxegp.experimental.raytracing

/**
 * 合光（S2 2-e）的入口描述。
 * 见 docs/rt/README.md
 */
object D3D12RtCompositeShader {
    const val ENTRY = "RtResolve"

    /** t0 反照率 / t1 直接光 / t2 自发光 / t3 一次弹射 / t4 原版颜色 / t5 辅助。 */
    const val SRV_COUNT = 12

    const val UAV_COUNT = 1

    private const val SOURCE = "rt_resolve.hlsl"

    fun build(): String = D3D12RtShaderSources.load(D3D12ShaderPacks.sourcePath(SOURCE))
}
