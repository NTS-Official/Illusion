package dev.nts.expdxr.d3d12.render.shader

import dev.nts.expdxr.d3d12.backend.D3D12BindGroupEntry

/**
 * 一条管线的编译产物；`entries` 下标 = SPIR-V binding = 根签名 `b/t/s{i}` 寄存器号，顺序不可乱。
 * 见 docs/shaders/README.md §10.1
 */
data class D3D12CompiledShader(
    val vertexHlsl: String,
    val fragmentHlsl: String,
    val entries: List<D3D12BindGroupEntry>,
    val vertexShaderInputs: List<String>,
    val semanticNames: MutableList<String>,
)
