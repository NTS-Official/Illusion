package dev.nts.expdxr.d3d12.backend

import com.mojang.renderpearl.api.pipeline.BindGroupLayout
import com.mojang.renderpearl.api.pipeline.UniformType
import com.mojang.renderpearl.backend.api.BackendRenderPipeline
import com.mojang.renderpearl.api.pipeline.RenderPipeline
import dev.nts.expdxr.d3d12.extern.MCDR_ABI
import dev.nts.expdxr.d3d12.render.shader.D3D12IntermediaryShaderModule

/**
 * 编译后的渲染管线：持有 native 的 `Dx12Pipeline*`（D3DCompile 字节码 + root signature + PSO）。
 * 由 D3D12Device.precompilePipeline 创建、clearPipelineCache 统一销毁；bindings 由 [buildBindings] 动态推导。
 */
class D3D12CompiledRenderPipeline(
    private val info: RenderPipeline,
    private var handle: Long,
    private val vertexShader: D3D12IntermediaryShaderModule,
    private val fragmentShader: D3D12IntermediaryShaderModule,
    private val vertexHlsl: String,
    private val fragmentHlsl: String,
) : BackendRenderPipeline {
    /** 管线生命周期内恒定，缓存以避免每帧重复构建（含 stream/集合分配）。 */
    @Volatile
    private var cachedBindings: List<D3D12BindGroupEntry>? = null

    /** meshlet 版地形的 PSO（`D3D12MeshletShader` 的 MS + 同一条 PS）：每线程组 16 quad。 */
    var meshletPso: Long = 0L
        private set

    /** 挂上 meshlet 变体（重复挂会先释放旧的）。`asActive` = 该 PSO 带 AS 剔除。 */
    fun attachMeshletVariant(pso: Long, asActive: Boolean) {
        if (this.meshletPso != 0L && this.meshletPso != pso) {
            MCDR_ABI.D3D12DestroyPipeline(this.meshletPso)
        }
        this.meshletPso = pso
        this.meshletAs = asActive
    }

    fun hasMeshletVariant(): Boolean = this.meshletPso != 0L

    /** meshlet 变体是否带 AS（派发组数按 ceil(meshlet/32) 折算）。 */
    var meshletAs: Boolean = false
        private set

    fun meshletPipeline(): Long = this.meshletPso

    /**
     * 线管线的 **TRIANGLES 拓扑变体** PSO：同一套着色器/顶点/索引，只换 `PrimitiveTopologyType`。
     * D3D12 没有宽线（`LINELIST` 恒 1px），线宽只靠 vsh 把端点横移，必须摊成两个三角形才是一条带子。
     */
    private var lineQuadPso: Long = 0L

    /** 挂上三角形变体（重复挂会先释放旧的）。 */
    fun attachLineQuadVariant(pso: Long) {
        if (this.lineQuadPso != 0L && this.lineQuadPso != pso) {
            MCDR_ABI.D3D12DestroyPipeline(this.lineQuadPso)
        }
        this.lineQuadPso = pso
    }

    fun hasLineQuadVariant(): Boolean = this.lineQuadPso != 0L

    fun lineQuadPipeline(): Long = this.lineQuadPso


    fun info(): RenderPipeline {
        return this.info
    }

    fun handle(): Long {
        return this.handle
    }

    /** 从 BindGroupLayout + shader modules 推导绑定条目，逻辑镜像 D3D12ShaderCompiler.addToBindGroup。 */
    fun buildBindings(): List<D3D12BindGroupEntry> {
        val cached = this.cachedBindings
        if (cached != null) {
            return cached
        }
        val allUniforms =
            BindGroupLayout.flattenUniforms(this.info.bindGroupLayouts)
        val allNames = mutableSetOf<String>()
        allUniforms.forEach { u -> allNames.add(u.name()) }

        val entries = mutableListOf<D3D12BindGroupEntry>()
        val seen = mutableSetOf<String>()
        for (shader in listOf(vertexShader, fragmentShader)) {
            for (buf in shader.uniformBuffers()) {
                val name = buf.name()
                if (!allNames.contains(name) || !seen.add(name)) continue
                entries.add(D3D12BindGroupEntry(D3D12BindGroupEntry.Type.UNIFORM_BUFFER, name, null))
            }
            for (sampler in shader.samplers()) {
                val name = sampler.name()
                if (!allNames.contains(name) || !seen.add(name)) continue
                val utbDesc = allUniforms.stream()
                    .filter { d -> d.name() == name }.findFirst().orElse(null)
                // 26.3 起 BindGroupLayout 为每个纹理 uniform 都报一条描述，「名字在 allUniforms 里」
                // 不再等于「texel buffer」：必须按 UniformType 判定（26.2 是靠 getSamplers() 的存在性）。
                if (utbDesc != null && utbDesc.type() == UniformType.TEXEL_BUFFER) {
                    entries.add(D3D12BindGroupEntry(D3D12BindGroupEntry.Type.TEXEL_BUFFER, name,
                        utbDesc.gpuFormat()))
                } else {
                    entries.add(D3D12BindGroupEntry(D3D12BindGroupEntry.Type.SAMPLED_IMAGE, name, null))
                }
            }
        }
        val result = java.util.List.copyOf(entries)
        this.cachedBindings = result
        return result
    }

    /** 26.3 的 `CompiledRenderPipeline` 只剩 [isClosed]/[close]；这是本实现自己的有效性判据。 */
    fun isValid(): Boolean {
        return this.handle != 0L
    }

    /**
     * uniform 名列表必须原样存：前端回调 `setUniform(index, …)` 的下标是 CreateInfo.uniforms 的位置
     * （按 SPIR-V 描述符出现顺序编号），**不是** flattenUniforms 的顺序，不能自己从 layouts 推。
     */
    @Volatile
    private var uniformNameList: List<String> = emptyList()

    fun setUniformNames(names: List<String>) {
        this.uniformNameList = names
    }

    fun uniformNamesSnapshot(): List<String> {
        return this.uniformNameList
    }

    override fun isClosed(): Boolean {
        return this.handle == 0L
    }

    override fun close() {
        if (this.meshletPso != 0L) {
            MCDR_ABI.D3D12DestroyPipeline(this.meshletPso)
            this.meshletPso = 0L
        }
        // 先取走再归零：FrontendGpuDevice.close() 与缓存/桥接路径都可能再 close 一次，
        // 不归零就是同一个 Dx12Pipeline* 被销毁两次（原生 double free → 退出时 0xC0000374 堆损坏）。
        val pipeline = this.handle
        this.handle = 0L
        if (pipeline != 0L) {
            MCDR_ABI.D3D12DestroyPipeline(pipeline)
        }
    }
}
