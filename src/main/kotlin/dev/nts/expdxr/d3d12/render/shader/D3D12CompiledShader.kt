package dev.nts.expdxr.d3d12.render.shader

import dev.nts.expdxr.d3d12.render.draw.D3D12BindGroupEntry

/**
 * 一条 {@link RenderPipeline} 的 DX12 编译产物：顶点/片元 HLSL 源码 +
 * 实际绑定资源列表（顺序决定 SPIR-V binding 0..n-1，也决定原生层
 * root signature 的 CBV b{i} / SRV t{i} / sampler s{i} 布局）+
 * 顶点着色器声明过的输入变量名（用于推导 D3D12 input layout 的 TEXCOORD 语义）+
 * 对应的 HLSL semantic 名称（如 "POSITION"、"TEXCOORD0" 等，与 vertexShaderInputs 同序）。
 */
data class D3D12CompiledShader(
    val vertexHlsl: String,
    val fragmentHlsl: String,
    val entries: List<D3D12BindGroupEntry>,
    val vertexShaderInputs: List<String>,
    val semanticNames: MutableList<String>,
)