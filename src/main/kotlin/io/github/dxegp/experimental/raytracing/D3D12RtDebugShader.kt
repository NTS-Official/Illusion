package io.github.dxegp.experimental.raytracing

/**
 * 调试图的两个入口描述：单通道拉伸，以及加速结构实例线框。
 * 见 docs/rt/README.md
 */
object D3D12RtDebugShader {
    const val ENTRY = "RtDebug"

    const val SRV_COUNT = 1
    const val UAV_COUNT = 1

    /**
     * BLAS 实例线框（`debugView = BLAS`）。
     *
     * 只画线、不拷底图，所以目标就是合成结果那张纹理：UAV 是同一张，SRV 换成实例表。
     * 槽数与单通道档相同（1 SRV + 1 UAV），但入口和源码不同，不能共用管线。
     */
    const val BLAS_ENTRY = "RtDebugBlasEdges"

    const val BLAS_SRV_COUNT = 1
    const val BLAS_UAV_COUNT = 1

    private const val SOURCE = "rt_debug.hlsl"

    private const val BLAS_SOURCE = "rt_debug_blas.hlsl"

    fun build(): String = D3D12RtShaderSources.load(D3D12ShaderPacks.sourcePath(SOURCE))

    fun buildBlas(): String = D3D12RtShaderSources.load(D3D12ShaderPacks.sourcePath(BLAS_SOURCE))
}
