package io.github.dxegp.d3d12.render.shader

import io.github.dxegp.d3d12.backend.D3D12BindGroupEntry

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
    /** 转换器接管的 buffer texture：绑定条目要改成 [D3D12BindGroupEntry.Type.RAW_BUFFER]。 */
    val rawBuffers: Set<String> = emptySet(),
)
