package dev.nts.expdxr.d3d12.backend

import com.mojang.renderpearl.api.GpuFormat
import org.jspecify.annotations.Nullable

/**
 * 绑定资源的声明顺序。rebind 按 addToBindGroup 顺序把 SPIR-V 的 binding decoration 重写为
 * 0..n-1（UBO→b{index}，sampled image / texel buffer→t{index}），顺序错了绑定整体错位。
 */
data class D3D12BindGroupEntry(
    val type: Type,
    val name: String,
    val texelBufferFormat: @Nullable GpuFormat?, ) {
    enum class Type {
        UNIFORM_BUFFER,
        SAMPLED_IMAGE,
        TEXEL_BUFFER
    }
}