package dev.nts.expdxr.dx12

import com.mojang.blaze3d.pipeline.BindGroupLayout
import com.mojang.blaze3d.pipeline.CompiledRenderPipeline
import com.mojang.blaze3d.pipeline.RenderPipeline
import java.util.ArrayList
import java.util.HashSet

/**
 * DX12 编译后的渲染管线：持有一条 {@link RenderPipeline} 对应的原生
 * {@code Dx12Pipeline*}（已含 D3DCompile 字节码 + root signature + PSO）。
 * 由 {@link Dx12Device#precompilePipeline} 创建，{@link Dx12Device#clearPipelineCache}
 * 统一销毁。
 *
 * bindings 不再在编译期固化，而是由 {@link #buildBindings()} 从
 * {@link RenderPipeline#getBindGroupLayouts()} + shader modules 动态推导，
 * 与官方 Blaze3D 的 BindGroupLayout 体系保持一致。
 */
class Dx12CompiledRenderPipeline(
    private val info: RenderPipeline,
    private val handle: Long,
    private val vertexShader: Dx12IntermediaryShaderModule,
    private val fragmentShader: Dx12IntermediaryShaderModule,
    private val vertexHlsl: String,
    private val fragmentHlsl: String,
) : CompiledRenderPipeline, AutoCloseable {
    /** P59：bindings 在管线生命周期内恒定，缓存以避免每帧重复构建（含 stream/集合分配）。 */
    @Volatile
    private var cachedBindings: List<Dx12BindGroupEntry>? = null

    fun info(): RenderPipeline {
        return this.info
    }

    fun handle(): Long {
        return this.handle
    }

    /**
     * 从 pipeline info 的 BindGroupLayout + shader modules 动态推导绑定条目列表，
     * 逻辑镜像 {@link Dx12ShaderCompiler#addToBindGroup}。
     */
    fun buildBindings(): List<Dx12BindGroupEntry> {
        val cached = this.cachedBindings
        if (cached != null) {
            return cached
        }
        val allUniforms =
            BindGroupLayout.flattenUniforms(this.info.bindGroupLayouts)
        val allSamplers =
            BindGroupLayout.flattenSamplers(this.info.bindGroupLayouts)
        val allNames = HashSet<String>()
        allUniforms.forEach { u -> allNames.add(u.name()) }
        allNames.addAll(allSamplers)

        val entries = ArrayList<Dx12BindGroupEntry>()
        val seen = HashSet<String>()
        for (shader in listOf(vertexShader, fragmentShader)) {
            for (buf in shader.uniformBuffers()) {
                val name = buf.name()
                if (!allNames.contains(name) || !seen.add(name)) continue
                entries.add(Dx12BindGroupEntry(Dx12BindGroupEntry.Type.UNIFORM_BUFFER, name, null))
            }
            for (sampler in shader.samplers()) {
                val name = sampler.name()
                if (!allNames.contains(name) || !seen.add(name)) continue
                val utbDesc = allUniforms.stream()
                    .filter { d -> d.name() == name }.findFirst().orElse(null)
                if (utbDesc != null) {
                    entries.add(Dx12BindGroupEntry(Dx12BindGroupEntry.Type.TEXEL_BUFFER, name,
                        utbDesc.gpuFormat()))
                } else {
                    entries.add(Dx12BindGroupEntry(Dx12BindGroupEntry.Type.SAMPLED_IMAGE, name, null))
                }
            }
        }
        val result = java.util.List.copyOf(entries)
        this.cachedBindings = result
        return result
    }

    override fun isValid(): Boolean {
        return this.handle != 0L
    }

    override fun close() {
        if (this.handle != 0L) {
            McdrAbi.dx12DestroyPipeline(this.handle)
        }
    }
}