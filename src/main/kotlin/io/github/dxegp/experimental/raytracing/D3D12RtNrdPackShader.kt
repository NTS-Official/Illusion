package io.github.dxegp.experimental.raytracing

/**
 * NRD 输入编码转换的入口描述（S4 4-c）。着色器在包里：`Illusion-HPT-Shaders\B-DXR\shaders\rt_nrd_pack.hlsl`。
 *
 * 两个 SRV（法线-粗糙度、线性深度）两个 UAV（R10G10B10A2 打包值、viewZ），见 docs/rt/README.md §21。
 */
internal object D3D12RtNrdPackShader {
    const val SOURCE = "rt_nrd_pack.hlsl"

    const val ENTRY = "RtNrdPack"

    const val SRV_COUNT = 2

    const val UAV_COUNT = 2

    fun build(): String = D3D12RtShaderSources.load(D3D12ShaderPacks.sourcePath(SOURCE))
}
