package dev.nts.expdxr.d3d12.backend

import com.mojang.renderpearl.api.pipeline.BindGroupLayout
import com.mojang.renderpearl.api.pipeline.UniformType
import com.mojang.renderpearl.backend.api.BackendRenderPipeline
import com.mojang.renderpearl.api.pipeline.RenderPipeline
import dev.nts.expdxr.d3d12.extern.MCDR_ABI
import dev.nts.expdxr.d3d12.render.shader.D3D12IntermediaryShaderModule

/**
 * DX12 编译后的渲染管线：持有一条 {@link RenderPipeline} 对应的原生
 * {@code Dx12Pipeline*}（已含 D3DCompile 字节码 + root signature + PSO）。
 * 由 {@link D3D12Device#precompilePipeline} 创建，{@link D3D12Device#clearPipelineCache}
 * 统一销毁。
 *
 * bindings 不再在编译期固化，而是由 {@link #buildBindings()} 从
 * {@link RenderPipeline#getBindGroupLayouts()} + shader modules 动态推导，
 * 与官方 Blaze3D 的 BindGroupLayout 体系保持一致。
 */
class D3D12CompiledRenderPipeline(
    private val info: RenderPipeline,
    private var handle: Long,
    private val vertexShader: D3D12IntermediaryShaderModule,
    private val fragmentShader: D3D12IntermediaryShaderModule,
    private val vertexHlsl: String,
    private val fragmentHlsl: String,
) : BackendRenderPipeline {
    /** P59：bindings 在管线生命周期内恒定，缓存以避免每帧重复构建（含 stream/集合分配）。 */
    @Volatile
    private var cachedBindings: List<D3D12BindGroupEntry>? = null

    /**
     * P217：meshlet 版地形的 PSO（`D3D12MeshletShader` 生成的 MS + 同一条 PS）。
     *
     * 每线程组 = 16 quad 的 meshlet、索引算术展开；根签名 = 地形布局 + b8/t14/t15/t16。
     */
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

    /** P217：meshlet 变体是否带 amplification shader（派发组数按 ceil(meshlet/32) 折算）。 */
    var meshletAs: Boolean = false
        private set

    /** P217：同会话 A/B 不再换 PSO，改由 globals 的 `gCullEnabled` 放行（见 AS 生成器）。 */
    fun meshletPipeline(): Long = this.meshletPso

    /**
     * P230：线管线的 **TRIANGLES 拓扑变体** PSO（同一套着色器、同一份顶点/索引，只换
     * `PrimitiveTopologyType`）。
     *
     * 为什么需要：D3D12 没有宽线（`D3D12_RASTERIZER_DESC` 无 line width），`LINELIST` 恒为
     * 1px；而 MC 的 Vulkan 后端用管线的 `lineWidth` 画宽线。`core/rendertype_lines.vsh` 只是把
     * 端点沿屏幕横移 `LineWidth/2`，所以在 D3D12 上退化成一细边 + 两端小横杠（转角像毛线团）。
     * 每段的 4 个顶点配 6 个索引（`RenderSystem.sharedSequentialLines`：端帽/端帽/长边）恰好
     * 可以摊成两个三角形、拼成一条 `LineWidth` 像素宽的带子 —— 只需换成三角形 PSO。
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

    /**
     * 从 pipeline info 的 BindGroupLayout + shader modules 动态推导绑定条目列表，
     * 逻辑镜像 {@link D3D12ShaderCompiler#addToBindGroup}。
     */
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
                // 26.3 的 BindGroupLayout 会为**每个**纹理 uniform 报一条描述（类型 COMBINED_IMAGE_SAMPLER），
                // 所以“名字出现在 allUniforms 里”不再等于“这是 texel buffer”：必须看 UniformType。
                // 26.2 用的是 BindGroupLayout.getSamplers()（只有 texel buffer 才列出来），
                // 26.3 删掉了 flattenSamplers，语义差别就在这里 —— 按类型判定而不是按存在性。
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
     * 26.3：前端把 `setUniform(String, …)` 解析成**管线 uniform 列表下标**后回调
     * `backend.setUniform(index, value)`。那个下标就是 [BackendRenderPipeline.CreateInfo.uniforms]
     * 的位置（`PipelineBuilder.generateBackendCreateInfo` 用 `computeIfAbsent(name) { map.size() }`
     * 按 SPIR-V 描述符出现顺序编号），**不是** `BindGroupLayout.flattenUniforms` 的顺序，
     * 所以不能自己从 layouts 推 —— 必须把 CreateInfo 的名单原样存下来（由
     * [dev.nts.expdxr.d3d12.render.adapter.D3D12Device.compilePipeline] 填充）。
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
        // P184：先取走再归零 —— 26.3 的 FrontendGpuDevice.close() 会走
        // PipelineBuilder.close() 逐条 close，本项目的缓存/桥接路径也可能再 close 一次；
        // 不归零就是同一根 Dx12Pipeline* 被 DestroyPipeline 两次（原生 double free），
        // 退出时 NTSTATUS 0xC0000374（堆损坏）。
        val pipeline = this.handle
        this.handle = 0L
        if (pipeline != 0L) {
            MCDR_ABI.D3D12DestroyPipeline(pipeline)
        }
    }
}
