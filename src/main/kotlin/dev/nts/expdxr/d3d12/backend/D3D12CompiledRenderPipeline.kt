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
     * P137：mesh 变体 PSO（`Dx12Pipeline*`，0 = 没有变体）。
     *
     * <p>同一个 `Dx12Pipeline` 对象里只换了 `withDepth/withoutDepth` 指向的 PSO，根签名与
     * 绑定点布局和地形管线一致（多出 b8/t14/t15/t16 三个尾参），所以 `D3D12SetPipeline`
     * 传的就是它；`D3D12PushDescriptors` 照旧推地形那套绑定。所有权在本对象，`close()` 释放。
     */
    var meshPso: Long = 0L
        private set

    /** P137：mesh HLSL 解顶点时用的步长（`VertexFormat.vertexSize`）。 */
    var meshVertexStride: Int = 0
        private set

    /**
     * P138：这个 mesh 变体的 PS 是否按段读淡入系数（VS→PS 插值）。
     *
     * <p>true 时整批段（包括正在做「区块淡入」的）一次派发即可；false 表示补丁没打上，
     * 派发侧必须继续把淡入未完成的段交给 IMR 逐段画（P137b 的旧路径）。
     */
    var meshPerSectionVisibility: Boolean = false
        private set

    /**
     * P140：这个 mesh 变体是否带 amplification shader（meshlet 剔除）。
     *
     * <p>true 时根签名多出 t17 + b9，派发必须走 `D3D12MeshDrawSegmentsCulled`：组数按
     * `ceil(组数/128)` 算，`DispatchMesh` 的实参由 AS 自己决定。
     */
    var meshMeshletCull: Boolean = false
        private set

    /** 挂上 mesh 变体（重复挂会先释放旧的）。*/
    fun attachMeshVariant(pso: Long, vertexStride: Int, perSectionVisibility: Boolean,
                          meshletCull: Boolean = false) {
        if (this.meshPso != 0L && this.meshPso != pso) {
            MCDR_ABI.D3D12DestroyPipeline(this.meshPso)
        }
        this.meshPso = pso
        this.meshVertexStride = vertexStride
        this.meshPerSectionVisibility = perSectionVisibility
        this.meshMeshletCull = meshletCull
    }

    /**
     * P141：生成 meshlet 包围球的 compute 管线（`bounds_main`），只在 [meshMeshletCull] 时有值。
     *
     * <p>与 [meshPso] 一样属于本对象，`close()` 释放；根签名随 PSO 一起由 native 持有引用，
     * 这里只留 PSO 句柄（native 侧 `Dx12Pipeline` 自己 AddRef 了那份签名）。
     */
    var boundsPipeline: Long = 0L
        private set

    /** 挂上包围球 compute 管线（重复挂会先释放旧的）。*/
    fun attachBoundsPipeline(pipeline: Long) {
        if (this.boundsPipeline != 0L && this.boundsPipeline != pipeline) {
            MCDR_ABI.D3D12DestroyPipeline(this.boundsPipeline)
        }
        this.boundsPipeline = pipeline
    }

    fun hasMeshVariant(): Boolean {
        return this.meshPso != 0L
    }

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
        if (this.boundsPipeline != 0L) {
            MCDR_ABI.D3D12DestroyPipeline(this.boundsPipeline)
            this.boundsPipeline = 0L
        }
        if (this.meshPso != 0L) {
            MCDR_ABI.D3D12DestroyPipeline(this.meshPso)
            this.meshPso = 0L
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