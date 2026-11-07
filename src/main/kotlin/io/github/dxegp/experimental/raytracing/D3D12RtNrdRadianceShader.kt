package io.github.dxegp.experimental.raytracing

/**
 * 辐亮度缓冲 → NRD 输入纹理的入口描述（S4 4-c）。见 docs/rt/README.md §23.5
 */
internal object D3D12RtNrdRadianceShader {
    const val ENTRY = "RtNrdRadiance"

    const val SRV_COUNT = 10

    const val UAV_COUNT = 3

    private const val SOURCE = "rt_nrd_radiance.hlsl"

    fun build(): String = D3D12RtShaderSources.load(D3D12ShaderPacks.sourcePath(SOURCE))
}
