package dev.nts.expdxr.d3d12.backend


import com.mojang.renderpearl.api.pipeline.IndexType
import com.mojang.renderpearl.api.buffers.GpuBuffer
import com.mojang.renderpearl.api.buffers.GpuBufferSlice
import com.mojang.renderpearl.api.pipeline.BindGroupLayout
import com.mojang.renderpearl.backend.api.BackendRenderPipeline
import com.mojang.renderpearl.api.commands.GpuQueryPool
import com.mojang.renderpearl.api.commands.RenderPass
import com.mojang.renderpearl.api.commands.RenderPass.RenderArea
import com.mojang.renderpearl.backend.api.RenderPassBackend
import com.mojang.renderpearl.api.textures.GpuSampler
import com.mojang.renderpearl.api.textures.GpuTextureView
import dev.nts.expdxr.CommandBundleMode
import dev.nts.expdxr.misc.settings.D3D12Settings
import dev.nts.expdxr.d3d12.extern.MCDR_ABI
import dev.nts.expdxr.d3d12.render.adapter.D3D12Device
import dev.nts.expdxr.d3d12.render.advanced.mesh.D3D12MeshDrawCapture
import dev.nts.expdxr.d3d12.render.advanced.taa.D3D12Temporal
import dev.nts.expdxr.d3d12.render.core.D3D12AsyncContext
import dev.nts.expdxr.d3d12.render.core.D3D12GpuBuffer
import dev.nts.expdxr.d3d12.render.core.D3D12GpuQueryPool
import dev.nts.expdxr.d3d12.render.core.D3D12GpuTextureView
import dev.nts.expdxr.d3d12.render.core.D3D12TransientMemory
import dev.nts.expdxr.d3d12.render.shader.D3D12GpuSampler
import dev.nts.expdxr.d3d12.render.advanced.vrs.D3D12VariableShadingRate
import dev.nts.expdxr.misc.IllusionLogger
import org.lwjgl.PointerBuffer
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.IntBuffer
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Supplier


/** 地形批次里唯一逐 draw 变化的 uniform 名：[D3D12RenderPassBackend.setUniform] 拿它当「材质没变」的判据。 */
private const val CHUNK_SECTION_UNIFORM = "ChunkSection"


/** mesh 跳过理由的去重必须**跨帧**（每个 pass 每帧都会新建 backend）；只服务日志。 */
private val gMeshSkipLogReasons: MutableSet<String> = HashSet()
/** 其余均为日志去重/限流状态，同样必须全局（实例字段每帧都会被重置）。 */
private var gMeshletLogNanos = 0L
private val gMeshTerrainMissingLog: MutableSet<String> = HashSet()
private val gDrawIndirectLocations: MutableSet<String> = HashSet()
private var gMeshletLastCull: Boolean? = null
private var gMeshletSolid = 0L
private var gMeshletCutout = 0L
private var gMeshletTranslucent = 0L

/**
 * D3D12 render pass 后端（镜像官方 VulkanRenderPass）：uniform/texture 暂存在本类 map，core 之前按需
 * 推入原生瞬时描述符堆（[pushDescriptors]）；顶点/索引缓冲与 draw 直接下发 native。
 */
class D3D12RenderPassBackend(
    private val device: D3D12Device?,
    private val ctx: Long,
    private val renderArea: RenderArea?,
    private val outputWidth: Int,
    private val outputHeight: Int,
    private val hasDepth: Boolean,
    private val colorTargetHandle: Long,
    /** 是否使用 shader Y-flip 变体管线（GUI 离屏 pass，由 CommandEncoder 按 texture usage 判断）。 */
    private val flipY: Boolean,
    /** 本 encoder 的瞬态上传区：meshlet 派发要上传段表/meshlet 表/小常量，只有 `uploadStaging` 能给 COPY_SRC 与 256 对齐偏移。 */
    private val transientMemory: D3D12TransientMemory,
) : RenderPassBackend {
    private var pushedDebugGroups = 0

    /** mesh 派发是否启用（唯一入口是 `[mesh] terrain` 配置项）。 */
    private val meshDispatchEnabled: Boolean
        get() = D3D12Settings.meshTerrain

    /** 最近一次 slot0 顶点切片的字节偏移（MDI 每段的 `baseVertex` 相对它）。 */
    private var lastVertexSliceOffset = -1L

    /** 最近一次 slot0 顶点切片的缓冲（meshlet 派发要把它当 t14 的 RAW SRV 绑定）。 */
    private var lastVertexBuffer: GpuBuffer? = null

    /** 早期显示会**先绑顶点缓冲、后设管线**：stride 只能从管线推导，故先挂起等 [setPipeline] 落实；抛异常会让加载屏整条 draw 被吞掉。 */
    private val pendingVertexBuffers = LinkedHashMap<Int, Pair<D3D12GpuBuffer, Long>>()

    private var pipeline: D3D12CompiledRenderPipeline? = null
    private var anyDescriptorDirty = false

    /** 当前管线的 uniform 名字表（顺序 = CreateInfo.uniforms，非 layouts 展平顺序）。 */
    private var uniformNames: List<String> = emptyList()
    private var pushConstantsWarned = false

    /** 除 `ChunkSection` 外的 uniform 变过：宁可多算（退化回每 draw 一次），不能少算（会把不同材质混进同一批）。 */
    private var materialDirty = true
    private val uniforms: MutableMap<String, GpuBufferSlice> = HashMap()

    /** 最近一次设置的 `ChunkSection` 切片（mesh 批次 per-draw 循环直接读字段，省两次哈希查找）。 */
    private var chunkSectionSlice: GpuBufferSlice? = null

    /** 逐段 uniform 上传的接收器：只捕获 this，提到字段上一次建好，避免每 draw 现造 lambda。 */
    private val uniformUploader: RenderPass.UniformUploader = object : RenderPass.UniformUploader {
        override fun setUniform(name: String, value: GpuBufferSlice) {
            this@D3D12RenderPassBackend.setUniformByName(name, value)
        }

        override fun pushConstants(data: ByteBuffer) {
            this@D3D12RenderPassBackend.pushConstants(data)
        }
    }
    private val textures: MutableMap<String, TextureViewAndSampler> = HashMap()

    private data class TextureViewAndSampler(val view: D3D12GpuTextureView, val sampler: D3D12GpuSampler)

    /** 当前管线的 location 字符串（如 minecraft:pipeline/animate_sprite_blit）。 */
    fun pipelineLocation(): String? {
        val p = this.pipeline ?: return null
        return p.info().location.toString()
    }

    fun colorTargetHandle(): Long {
        return this.colorTargetHandle
    }

    /** 本 pass 是否是 GUI 离屏图集 pass（usage==13 且带深度，几何整体 Y 翻转）。 */
    fun isFlipY(): Boolean {
        return this.flipY
    }

    fun outputWidth(): Int {
        return this.outputWidth
    }

    fun outputHeight(): Int {
        return this.outputHeight
    }

    fun areaX(): Int {
        val area = this.renderArea
        return area?.x() ?: 0
    }

    fun areaY(): Int {
        val area = this.renderArea
        return area?.y() ?: 0
    }

    fun areaWidth(): Int {
        val area = this.renderArea
        return area?.width() ?: this.outputWidth
    }

    fun areaHeight(): Int {
        val area = this.renderArea
        return area?.height() ?: this.outputHeight
    }

    override fun pushDebugGroup(label: Supplier<String>) {
        this.pushedDebugGroups++
    }

    override fun popDebugGroup() {
        this.pushedDebugGroups--
    }

    override fun writeTimestamp(pool: GpuQueryPool, index: Int) {
        MCDR_ABI.D3D12WriteTimestamp(this.ctx,
            (pool as D3D12GpuQueryPool).nativeHandle(), index)
    }
    // ---- pipeline + descriptors ----
    override fun setPipeline(pipeline: BackendRenderPipeline) {
        val device = this.device
            ?: throw IllegalStateException("No D3D12 device bound to this render pass")
        val base = pipeline as? D3D12CompiledRenderPipeline
            ?: throw IllegalStateException(
                "Pipeline ${pipeline.javaClass.name} is not a D3D12 compiled pipeline")
        // 前端缓存里可能是上一次资源重载**前**编译的对象（vanilla 重载会把它们 close、handle 归 0 ⇒
        // isValid()==false）→ 会抛 "not valid" 且没有 COMPILE FAILED 日志。故 flipY 变体或失效对象都重新取。
        val compiled = if (this.flipY || !base.isValid()) {
            device.getOrCompilePipeline(base.info(), this.flipY)
        } else {
            base
        }
        if (!compiled.isValid()) {
            throw IllegalStateException(
                "Pipeline ${base.info().location} is not valid (shader compilation failed)")
        }
        this.pipeline = compiled

        // vanilla 按「管线 uniform 列表下标」回调 setUniform(Int)，下标来自 `CreateInfo.uniforms`（≠
        // flattenUniforms(bindGroupLayouts) 的顺序）。flipY 变体是另一个实例，把 base 的名单复制过去即可。
        if (compiled !== base) {
            compiled.setUniformNames(base.uniformNamesSnapshot())
        }
        this.uniformNames = compiled.uniformNamesSnapshot()
        if (this.uniformNames.isEmpty()) {
            // 兜底：管线只经自检路径编译（无 CreateInfo）时名单为空，用 layouts 展平顺序顶上。
            this.uniformNames = BindGroupLayout.flattenUniforms(compiled.info().bindGroupLayouts)
                .map { it.name() }
        }
        this.anyDescriptorDirty = true
        // useDepth 必须看**管线**有没有 DepthStencilState，不能只看 pass 有没有深度附件：GUI 管线没有
        // depthStencilState，套上 withDepth PSO 会让 GREATER_EQUAL 丢掉所有 fragment → 黑屏。
        val pipelineHasDepth = compiled.info().depthStencilState != null
        val useDepth = this.hasDepth && pipelineHasDepth
        val ok = MCDR_ABI.D3D12SetPipeline(this.ctx, compiled.handle(), useDepth)
        if (!ok) {
            throw IllegalStateException("dx12SetPipeline failed for ${base.info().location}")
        }
        // 落实 setPipeline 之前挂起的顶点缓冲（早期显示是"先绑 buffer、后设管线"）。
        if (this.pendingVertexBuffers.isNotEmpty()) {
            val formatBindings = compiled.info().vertexFormatBindings
            val pending = this.pendingVertexBuffers.entries.map { it.key to it.value }
            this.pendingVertexBuffers.clear()
            for ((slot, value) in pending) {
                val stride = formatBindings.getOrNull(slot)?.vertexSize ?: 0
                if (stride == 0) {
                    LOGGER.warn(
                        "vertex buffer slot {} has no format in pipeline {} (dropped)",
                        slot, base.info().location)
                    continue
                }
                this.applyVertexBuffer(slot, value.first, value.second, stride)
            }
        }
    }

    /** 把回调进来的 uniform 下标记翻回名字（本类内部按名字绑定描述符，与 native `pushDescriptors` 契约一致）。 */
    override fun setUniform(index: Int, value: Any?) {
        val name = this.uniformNames.getOrNull(index)
        if (name == null) {
            LOGGER.warn("setUniform({}) ignored: pipeline has {} uniforms",
                index, this.uniformNames.size)
            return
        }
        when (value) {
            null -> {
                this.textures.remove(name)
                this.anyDescriptorDirty = true
            }
            is com.mojang.renderpearl.util.TextureViewAndSampler ->
                this.bindTexture(name, value.view(), value.sampler())
            is GpuBufferSlice -> this.setUniformByName(name, value)
            is GpuBuffer -> this.setUniformByName(name, value.slice())
            else -> throw IllegalArgumentException(
                "Unsupported uniform value for '$name': ${value.javaClass.name}")
        }
    }

    /** 26.3 新增的 push constant 通道：native 没有根常量路径，先告警一次而不是静默丢数据。 */
    override fun pushConstants(data: ByteBuffer) {
        if (!this.pushConstantsWarned) {
            this.pushConstantsWarned = true
            LOGGER.warn("pushConstants({} bytes) ignored: D3D12 backend has no root-constant path",
                data.remaining())
        }
    }

    private fun bindTexture(name: String, textureView: GpuTextureView?, sampler: GpuSampler?) {
        if (textureView == null || sampler == null) {
            if (textureView != null || sampler != null) {
                throw IllegalArgumentException("Both texture and sampler must be null or non-null")
            }
            this.textures.remove(name)
            return
        }
        this.textures[name] = TextureViewAndSampler(
            textureView as D3D12GpuTextureView, sampler as D3D12GpuSampler
        )
        this.anyDescriptorDirty = true
    }

    private fun setUniformByName(name: String, value: GpuBufferSlice) {
        // 捕获时间性渲染要的逐物体 UBO（"DynamicTransforms" / "ChunkSection"）；vanilla 的 DynamicTransforms
        // 环由 CPU 直接写（usage 130），map(read) 安全。只在有深度附件的 pass 记录：GUI/图集 blit 也 set 这两个名字，混进来会让配对序号失真。
        if (this.hasDepth) {
            D3D12Temporal.captureUniform(name, value)
        }
        if (name != CHUNK_SECTION_UNIFORM) {
            this.materialDirty = true
        } else {
            this.chunkSectionSlice = value
        }
        this.uniforms[name] = value
        this.anyDescriptorDirty = true
    }
    /**
     * 转发到 native 的 RSSetViewports。与 [enableScissor] 不同，这里**不**做 Y 翻转：直接透传调用方给的 y。
     */
    override fun setViewport(x: Int, y: Int, width: Int, height: Int) {
        if (!MCDR_ABI.D3D12SetViewport(this.ctx, x, y, width, height)) {
            LOGGER.error("dx12SetViewport failed ({} {} {} {})", x, y, width, height)
        }
    }

    /** 镜像官方 pushDescriptors：把当前 uniform/texture 写入原生瞬时描述符堆并绑定 root table。 */
    private fun pushDescriptors() {
        if (!this.anyDescriptorDirty) {
            return
        }
        this.anyDescriptorDirty = false
        val pipeline = this.pipeline
        if (pipeline == null || !pipeline.isValid()) {
            LOGGER.warn("pushDescriptors: SKIP (pipeline null or invalid)")
            return
        }
        val bindings = pipeline.buildBindings()
        val count = bindings.size
        if (count == 0) {
            LOGGER.warn("pushDescriptors: SKIP (0 bindings)")
            return
        }
        val types = IntArray(count)
        val buffers = LongArray(count)
        val offsets = LongArray(count)
        val lengths = LongArray(count)
        val texelFormats = IntArray(count)
        val views = LongArray(count)
        for (i in 0 until count) {
            val entry = bindings[i]
            when (entry.type) {
                D3D12BindGroupEntry.Type.UNIFORM_BUFFER -> {
                    val value = requireUniform(entry.name)
                    val buffer = value.buffer()
                    types[i] = 0
                    buffers[i] = (buffer as D3D12GpuBuffer).handle()
                    offsets[i] = value.offset()
                    lengths[i] = value.length()
                }
                D3D12BindGroupEntry.Type.SAMPLED_IMAGE -> {
                    val texture = this.textures[entry.name]
                        ?: throw IllegalStateException("Texture '${entry.name}' was not bound before core")
                    if (texture.view.isClosed) {
                        throw IllegalStateException("Texture '${entry.name}' is closed")
                    }
                    types[i] = 1
                    views[i] = texture.view.handle()
                }
                D3D12BindGroupEntry.Type.TEXEL_BUFFER -> {
                    val value = requireUniform(entry.name)
                    val buffer = value.buffer()
                    types[i] = 2
                    buffers[i] = (buffer as D3D12GpuBuffer).handle()
                    offsets[i] = value.offset()
                    lengths[i] = value.length()
                    // Java 里此处直接解引用 texelBufferFormat()（null 会 NPE），用 !! 保留同样语义。
                    texelFormats[i] = entry.texelBufferFormat!!.ordinal
                }
            }
        }
        if (!MCDR_ABI.D3D12PushDescriptors(this.ctx, types, buffers, offsets, lengths, texelFormats, views)) {
            // 带上 native 的具体原因：最常见的是 CBV 地址没按 256 字节对齐，只报 "failed" 会丢掉那条 offset 信息。
            throw IllegalStateException("dx12PushDescriptors failed: ${MCDR_ABI.D3D12LastError()}")
        }
    }

    private fun requireUniform(name: String): GpuBufferSlice {
        val value = this.uniforms[name] ?: throw IllegalStateException("Uniform '${name}' was not set before core")
        val buffer = value.buffer()
        if (buffer.isClosed) throw IllegalStateException("Uniform '${name}' buffer is closed")
        return value
    }
    // ---- scissor ----

    override fun enableScissor(x: Int, y: Int, width: Int, height: Int) {
        // GL 约定（左下原点）× RSSetScissorRects（左上原点），是否互为镜像取决于本 pass 有没有做几何 Y 翻转：
        //  * flipY=false（主窗口）：几何不翻 → 必须换算 outputHeight - y - height，否则列表项被上移裁剪。
        //  * flipY=true（GUI 图集 / PIP）：顶点着色器已取反 gl_Position.y → 原样直通，再换算会让物品整块空白。
        val scissorY = if (this.flipY) y else this.outputHeight - y - height
        if (!MCDR_ABI.D3D12SetScissor(this.ctx, x, scissorY, width, height)) {
            LOGGER.error("dx12SetScissor failed ({} {} {} {})", x, scissorY, width, height)
        }
    }

    override fun disableScissor() {
        val area = this.renderArea
        if (area != null) this.enableScissor(area.x(), area.y(), area.width(), area.height())
        else this.enableScissor(0, 0, this.outputWidth, this.outputHeight)
    }

    // ---- vertex / index buffers + draws ----

    override fun setVertexBuffer(slot: Int, vertexBuffer: GpuBufferSlice?) {
        if (vertexBuffer == null) return

        val buffer = vertexBuffer.buffer()
        if (buffer.isClosed) throw IllegalStateException("Vertex buffer at slot ${slot} has been closed!")

        if ((buffer.usage() and USAGE_VERTEX) == 0) throw IllegalStateException("Vertex buffer at slot ${slot} doesn't have GpuBuffer.USAGE_VERTEX flag!")

        val pipeline = this.pipeline
        var stride = 0
        if (pipeline != null) {
            val info = pipeline.info()
            val bindings = info.vertexFormatBindings
            if (slot < bindings.size) {
                val binding = bindings[slot]
                if (binding != null) {
                    stride = binding.vertexSize
                }
            }
        }
        if (stride == 0) {
            // 管线还没设（早期显示先绑 buffer 后设管线）或该槽位没声明顶点布局 → 挂起；抛异常会让加载屏整条 draw 失败。
            this.pendingVertexBuffers[slot] = (buffer as D3D12GpuBuffer) to vertexBuffer.offset()
            return
        }
        this.applyVertexBuffer(slot, buffer as D3D12GpuBuffer, vertexBuffer.offset(), stride)
    }

    /** 按已知 stride 真正下发顶点缓冲绑定，并维护 slot0 的 meshlet 记账。 */
    private fun applyVertexBuffer(slot: Int, buffer: D3D12GpuBuffer, offset: Long, stride: Int) {
        if (!MCDR_ABI.D3D12SetVertexBuffer(this.ctx, slot, buffer.handle(), offset, stride)) {
            throw IllegalStateException("dx12SetVertexBuffer failed")
        }
        if (slot == 0) {
            // MDI 批次里每个段的 `baseVertex` 都相对这块切片，Stage 2 建段表要用。
            this.lastVertexSliceOffset = offset
            this.lastVertexBuffer = buffer
        }
    }

    override fun setIndexBuffer(indexBuffer: GpuBuffer, indexType: IndexType) {
        if (indexBuffer.isClosed) throw IllegalStateException("Index buffer has been closed!")

        if ((indexBuffer.usage() and USAGE_INDEX) == 0) throw IllegalStateException("Index buffer doesn't have GpuBuffer.USAGE_INDEX flag!")

        if (!MCDR_ABI.D3D12SetIndexBuffer(this.ctx, (indexBuffer as D3D12GpuBuffer).handle(), if (indexType == IndexType.INT) 1 else 0)) throw IllegalStateException("dx12SetIndexBuffer failed")
    }

    // ---- 绘制热路径插桩：PROF=1 时由 D3D12CommandEncoderBackend 每 300 帧汇总打印并清零 ----
    companion object {
        /** 准星补画期间的诊断开关：打印这一笔绑了什么管线。 */
        @JvmStatic
        var replayProbe: Boolean = false
        @JvmStatic
        var replayBinds: Int = 0
        private val LOGGER: Logger = LoggerFactory.getLogger("dxrmc")

        private const val USAGE_VERTEX = 0x20
        private const val USAGE_INDEX = 0x40
        private const val USAGE_UNIFORM = 0x80
        private const val USAGE_UNIFORM_TEXEL_BUFFER = 0x100

        /** DEBUG: true 时强制所有 pass 使用 withoutDepth PSO（禁用深度测试/写入），用于排查黑屏是否由深度问题导致。 */
        private const val DEBUG_DISABLE_DEPTH_TEST = false


    @JvmField var gProfMultiBatches = 0L      // drawMultipleIndexed 批次数
        @JvmField var gProfMultiDraws = 0L        // 批次内 draw 总数
        @JvmField var gProfMultiPathNs = 0L       // 批量入口主线程墙钟耗时
        @JvmField var gProfDrawIndexedCalls = 0L  // drawIndexed 调用次数
        @JvmField var gProfDrawIndexedNs = 0L     // drawIndexed（pushDescriptors + downcall）耗时
        // 并行批量路径四段拆分：定位主线程串行开销（8 worker 相对 4 worker 无收益）。
        @JvmField var gProfBuildNs = 0L
        @JvmField var gProfPrepareNs = 0L
        @JvmField var gProfWorkersNs = 0L
        @JvmField var gProfExecuteNs = 0L
        @JvmField var gProfFallbacks = 0L
        // 区块批量内 VB/IB 共享率：共享则可用 ExecuteIndirect 替代逐 core 录制。
        @JvmField var gProfSharedBatchDraws = 0L
        @JvmField var gProfSameVbBuf = 0L
        @JvmField var gProfSameVbOff = 0L
        @JvmField var gProfSameIbBuf = 0L
        @JvmField var gProfSameIdxType = 0L
        @JvmField var gProfBatches = 0L
        @JvmField var gProfSharedDescBatches = 0L
        @JvmField var gProfSingleBundles = 0L
        @JvmField var gProfSlotsTotal = 0L
        // 逐 binding 变异性：决定描述符写入能否从 n*bc 降到 n*变动数。
        @JvmField var gProfBcTotal = 0L
        @JvmField var gProfCbvVary = 0L
        @JvmField var gProfCbvSame = 0L
        @JvmField var gProfSrvVary = 0L
        @JvmField var gProfSrvSame = 0L
        @JvmField var gProfVbSlotSame = 0L
        @JvmField var gProfSrvVaryFallbacks = 0L

        /** 低于此批量时并行调度开销高于收益，直接走串行路径。 */
        private const val PARALLEL_MIN_DRAWS = 24

        /**
         * 并行调度的固定开销（线程唤醒 + CountDownLatch）约 0.2ms/帧且与 worker 数无关：一批的录制量摊不薄它时，
         * 改为「在调用线程内联录一个 bundle」——同样只走一次录制 JNI，省去线程池往返。
         */
        private const val SERIAL_BUNDLE_MAX_DRAWS = 512

        /** 由配置 `[renderer] commandBundles` 决定：serial 强制单分区、parallel 强制并行、auto 按批量自适应。 */
        private val BUNDLE_MODE: Int = when (D3D12Settings.commandBundles) {
            CommandBundleMode.SERIAL -> 1
            CommandBundleMode.PARALLEL -> -1
            CommandBundleMode.AUTO -> 0
        }
    }

    override fun drawIndexed(indexCount: Int, instanceCount: Int, firstIndex: Int,
        vertexOffset: Int, firstInstance: Int) {
        val profT0 = if (MCDR_ABI.PROF) System.nanoTime() else 0L
        // 线管线有三角形变体 ⇒ 切过去：同一份索引在 TRIANGLES 下摊成两个三角形，拼出 `LineWidth` 带宽；LINELIST 恒 1px。
        val lineQuad = this.pipeline?.takeIf { it.hasLineQuadVariant() }
        val lineQuadDepth = lineQuad?.let { it.info().depthStencilState != null } ?: false
        if (lineQuad != null) {
            if (!MCDR_ABI.D3D12SetPipeline(this.ctx, lineQuad.lineQuadPipeline(), lineQuadDepth)) {
                throw IllegalStateException("dx12SetPipeline (line quad variant) failed")
            }
        }
        this.pushDescriptors()
        if (!MCDR_ABI.D3D12DrawIndexed(this.ctx, indexCount, instanceCount,
            firstIndex, vertexOffset, firstInstance)) {
            throw IllegalStateException("dx12DrawIndexed failed")
        }
        if (lineQuad != null) {
            // 恢复本 pass 的常规管线（描述符表随 PSO 变化，需要重推）。
            if (!MCDR_ABI.D3D12SetPipeline(this.ctx, lineQuad.handle(), lineQuadDepth)) {
                throw IllegalStateException("dx12SetPipeline (restore after line quad) failed")
            }
            this.pushDescriptors()
        }
        if (profT0 != 0L) {
            gProfDrawIndexedCalls++
            gProfDrawIndexedNs += System.nanoTime() - profT0
        }
    }

    /** DispatchMesh 入口：先推描述符再下发；没有 IA 状态、没有顶点/索引缓冲。 */
    fun dispatchMesh(x: Int, y: Int, z: Int) {
        this.pushDescriptors()
        if (!MCDR_ABI.D3D12DispatchMesh(this.ctx, x, y, z)) throw IllegalStateException("dx12DispatchMesh(${x}, ${y}, ${z}) failed")
    }

    override fun multiDrawIndexed(drawParameters: IntBuffer, instanceCount: Int,
        firstInstance: Int, drawCount: Int) {
        for (i in 0 until drawCount) {
            val firstIndex = drawParameters.get()
            val indexCount = drawParameters.get()
            val baseVertex = drawParameters.get()
            this.drawIndexed(indexCount, instanceCount, firstIndex, baseVertex, firstInstance)
        }
    }

    override fun multiDrawIndexed(firstIndexOffsets: PointerBuffer, indexCounts: IntBuffer,
        vertexOffsets: IntBuffer, drawCount: Int) {
        throw UnsupportedOperationException(
            "multiDrawDirectSeparate is not supported by the D3D12 backend")
    }

    override fun drawIndexedIndirect(commands: GpuBufferSlice, drawCount: Int) {
        val location = this.pipeline?.info()?.location?.toString() ?: ""
        if (gDrawIndirectLocations.add(location)) {
            // 作用域白名单按管线名判：名字错了整条通路就是静默空转，所以每个新名字打一行方便核对。
            LOGGER.info("MDI terrain candidate '{}' terrain={} rate={}", location,
                isTerrain(location), D3D12VariableShadingRate.terrainRateFor(location))
        }
        // 地形**全部**（不透明 + OIT 半透明）只走 meshlet，无 IMR 回退；OIT 顺序无关，mesh 的线程组顺序才无所谓。
        if (isTerrain(location)) {
            // 着色率唯一作用域：整批地形包在一次 scope 内，白名单在 `terrainRateFor`；档位 OFF 时零 native 调用。
            var drawn = false
            D3D12VariableShadingRate.scope(this.ctx, location) {
                drawn = tryMeshletDraw(commands, drawCount)
            }
            if (!drawn) {
                meshTerrainMissing("$location draws=$drawCount")
            }
            return
        }
        this.pushDescriptors()
        if (!MCDR_ABI.D3D12DrawIndexedIndirect(this.ctx,
            (commands.buffer() as D3D12GpuBuffer).handle(), commands.offset(), drawCount)) {
            throw IllegalStateException("dx12DrawIndexedIndirect failed")
        }
    }

    /** 管线名 → 上传注册表里的图层 key（`ChunkSectionLayer.name()`）。 */
    private fun meshletLayerFor(location: String): String? = when {
        location.contains("solid_terrain") -> "SOLID"
        location.contains("cutout_terrain") -> "CUTOUT"
        location.contains("translucent") || location.contains("oit_") -> "TRANSLUCENT"
        else -> null
    }

    /** 地形管线（含 OIT 三段）；shadow 不属于本路径。 */
    private fun isTerrain(location: String): Boolean =
        location.contains("terrain") && !location.contains("shadow")

    /** 地形批次的 mesh 派发失败只记一次（已无 IMR 回退，不会再画一遍原版路径）。 */
    private fun meshTerrainMissing(reason: String) {
        if (gMeshTerrainMissingLog.add(reason)) {
            LOGGER.error("terrain batch not drawn (mesh path unavailable): {}", reason)
        }
    }

    /** `tryMeshletDraw` 的静默前置失败也要留证（去重，一次运行每理由一条）。 */
    private fun meshletSkip(reason: String): Boolean {
        if (gMeshSkipLogReasons.add("meshlet:" + reason)) {
            LOGGER.info("meshlet skipped: {}", reason)
        }
        return false
    }

    /**
     * meshlet 版地形派发：一次 `DispatchMesh(meshletCount)` 覆盖整批 MDI 段。返回 false 表示这批不归 mesh 管；
     * 所有前置失败都记去重日志 —— 静默跳过等于画面缺块，必须能定位。
     */
    private fun tryMeshletDraw(commands: GpuBufferSlice, drawCount: Int): Boolean {
        if (!meshDispatchEnabled) return meshletSkip("mesh dispatch disabled")
        val compiled = this.pipeline ?: return meshletSkip("no pipeline bound")
        if (!compiled.hasMeshletVariant()) {
            return meshletSkip("pipeline has no meshlet variant (${compiled.info().location})")
        }
        // 带 AS 的旧段表变体用的是 4-range 根签名（多一个 t17），新 MS 只写 3 个 range，不能混用。
        val batch = D3D12MeshDrawCapture.batch(commands.offset())
            ?: return meshletSkip("no captured MDI list for offset ${commands.offset()}")
        if (batch.drawCount != drawCount) {
            return meshletSkip("drawCount mismatch captured=${batch.drawCount} call=$drawCount")
        }
        val layer = meshletLayerFor(compiled.info().location.toString())
            ?: return meshletSkip("unknown terrain layer ${compiled.info().location}")
        val vertexBuffer = this.lastVertexBuffer ?: return meshletSkip("no slot0 vertex buffer")
        if (this.lastVertexSliceOffset < 0L) return meshletSkip("vertex slice offset unset")
        val tables = D3D12MeshDrawCapture.buildTables(batch, layer, this.lastVertexSliceOffset)
            ?: return meshletSkip("meshlet tables incomplete (layer=$layer draws=${batch.drawCount})")

        // 同会话 A/B：`run/dxrmc/meshlet_no_as` 存在时把 b8 的 `gCullEnabled` 置 0 —— 同一 PSO、同一次派发，差异只来自剔除。
        val cullOn = !java.nio.file.Files.exists(
            java.nio.file.Path.of(System.getProperty("user.dir"), "dxrmc", "meshlet_no_as"))
        if (gMeshletLastCull != cullOn) {
            gMeshletLastCull = cullOn
            LOGGER.info("AS cull toggle: cull={} meshletAs={}", cullOn, compiled.meshletAs)
        }

        val sectionsSlice = this.transientMemory.uploadStaging(
            mutableListOf(ByteBuffer.wrap(tables.sections)), 256L, GpuBuffer.USAGE_COPY_SRC)
        val meshletsSlice = this.transientMemory.uploadStaging(
            mutableListOf(ByteBuffer.wrap(tables.meshlets)), 256L, GpuBuffer.USAGE_COPY_SRC)
        val globalsBytes = ByteBuffer.allocate(256).order(ByteOrder.LITTLE_ENDIAN)
        globalsBytes.putInt(tables.meshletCount)
        globalsBytes.putInt(if (cullOn) 1 else 0)
        // b8 的 gVrsBand（近带 / 中带 / 保留 / 启用位）：启用位=0 时 MS 恒写 1X1，与档位 OFF 等价。
        val band = D3D12VariableShadingRate.msBand()
        globalsBytes.putFloat(band[0])
        globalsBytes.putFloat(band[1])
        globalsBytes.putFloat(band[2])
        globalsBytes.putFloat(band[3])
        globalsBytes.flip()
        val globalsSlice = this.transientMemory.uploadStaging(
            mutableListOf(globalsBytes), 256L, GpuBuffer.USAGE_COPY_SRC)
        val sectionsHandle = (sectionsSlice.buffer() as? D3D12GpuBuffer)?.handle() ?: return false
        val meshletsHandle = (meshletsSlice.buffer() as? D3D12GpuBuffer)?.handle() ?: return false
        val globalsHandle = (globalsSlice.buffer() as? D3D12GpuBuffer)?.handle() ?: return false
        if (globalsSlice.offset() % 256L != 0L) {
            return meshletSkip("meshlet globals offset ${globalsSlice.offset()} is not 256-aligned")
        }

        val useDepth = this.hasDepth && compiled.info().depthStencilState != null
        if (!MCDR_ABI.D3D12SetPipeline(this.ctx, compiled.meshletPipeline(), useDepth)) {
            return meshletSkip("bind meshlet PSO failed: ${MCDR_ABI.D3D12LastError()}")
        }
        // 先换 PSO 再 pushDescriptors：native 的 SetGraphicsRootSignature 会清掉已绑的根描述符表。
        this.anyDescriptorDirty = true
        this.pushDescriptors()
        val dispatched = MCDR_ABI.D3D12MeshDrawMeshlets(this.ctx,
            (vertexBuffer as D3D12GpuBuffer).handle(), this.lastVertexSliceOffset,
            sectionsHandle, sectionsSlice.offset(), sectionsSlice.length(),
            meshletsHandle, meshletsSlice.offset(), meshletsSlice.length(),
            globalsHandle, globalsSlice.offset(),
            // 带 AS 时 meshlet 数放在 b8 常量里，派发的是 AS 线程组数（每组 32 个 meshlet）。
            if (compiled.meshletAs) (tables.meshletCount + 31) / 32 else tables.meshletCount)
        val failure = if (dispatched) null else MCDR_ABI.D3D12LastError()
        // 恢复 IMR PSO：本 pass 后续的 draw 仍复用这条管线。
        MCDR_ABI.D3D12SetPipeline(this.ctx, compiled.handle(), useDepth)
        this.anyDescriptorDirty = true
        if (failure != null) {
            return meshletSkip("meshlet dispatch failed: $failure")
        }
        when (layer) {
            "SOLID" -> gMeshletSolid++
            "CUTOUT" -> gMeshletCutout++
            "TRANSLUCENT" -> gMeshletTranslucent++
        }
        if (System.nanoTime() - gMeshletLogNanos > 2_000_000_000L) {
            gMeshletLogNanos = System.nanoTime()
            LOGGER.info("meshlet dispatch: draws={} meshlets={} layer={} " +
                "totals[solid={} cutout={} translucent={}]",
                drawCount, tables.meshletCount, layer, gMeshletSolid, gMeshletCutout, gMeshletTranslucent)
        }
        return true
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> materialize(draws: Collection<RenderPass.Draw<T>>): List<RenderPass.Draw<T>> {
        if (draws is List<*>) return draws as List<RenderPass.Draw<T>>

        return ArrayList(draws)
    }

    /** 从当前 pipeline 的 vertexFormatBindings 推导 slot 的顶点步长；无法推导返回 0。 */
    private fun vertexStrideFor(slot: Int): Int {
        val p = this.pipeline ?: return 0
        val bindings = p.info().vertexFormatBindings
        if (slot < bindings.size) {
            val binding = bindings[slot]
            if (binding != null) {
                return binding.vertexSize
            }
        }
        return 0
    }
    /**
     * 把整批 draw 按 worker 数切段，各 worker 在自己的描述符分区录一个 BUNDLE，最后由主列表按序 `ExecuteBundle`。
     * bundle 内禁止 ResourceBarrier，故 CBV 缓冲/纹理视图先由主列表统一过渡（`AsyncPrepare*`）。
     *
     * @return false = 未处理，调用方须走串行路径
     */
    private fun <T : Any> tryParallelDrawMultipleIndexed(draws: List<RenderPass.Draw<T>>,
        defaultIndexBuffer: GpuBuffer?, defaultIndexType: IndexType?,
        uniformArgument: T): Boolean {
        val async = D3D12AsyncContext.get()
        if (async == null || !async.available()) {
            return false
        }
        val n = draws.size
        if (n < PARALLEL_MIN_DRAWS) {
            return false
        }
        val pl = this.pipeline!!
        val bindings = pl.buildBindings()
        val bc = bindings.size
        if (bc == 0) {
            return false
        }
        val profP0 = if (MCDR_ABI.PROF) System.nanoTime() else 0L
        val bType = IntArray(bc)
        // 绑定名在整批内恒定：预先取出，避免在 n 层循环里重复 List.get + name()。
        val bName = Array(bc) { "" }
        for (j in 0 until bc) {
            val e = bindings.get(j)
            bName[j] = e.name
            val type = e.type
            if (type == D3D12BindGroupEntry.Type.TEXEL_BUFFER) {
                return false  // 分区堆没有 texel buffer SRV 写入原语 -> 串行
            }
            bType[j] = if (type == D3D12BindGroupEntry.Type.UNIFORM_BUFFER) 0 else 1
        }

        val idxBuf = LongArray(n)
        val idxType = IntArray(n)
        val idxCount = IntArray(n)
        val firstIdx = IntArray(n)
        val baseVert = IntArray(n)
        val vbSlot = IntArray(n)
        val vbBuf = LongArray(n)
        val vbOff = LongArray(n)
        val vbStride = IntArray(n)
        // 每 (core, binding) 的描述符取值；n*bc 以 long 计算，防 int 溢出。
        val cells = n.toLong() * bc
        if (cells > Int.MAX_VALUE) {
            return false
        }
        val bBuf = LongArray(cells.toInt())
        val bOff = LongArray(bBuf.size)
        val bLen = LongArray(bBuf.size)
        val bView = LongArray(bBuf.size)
        val cbvBuffers = linkedSetOf<Long>()
        val textureViews = linkedSetOf<Long>()
        // bundle 内禁止 barrier：顶点/索引缓冲也必须在主列表先过渡；顶点缓冲目标状态与 CBV 相同，索引缓冲单独一组。
        val vbBuffers = linkedSetOf<Long>()
        val indexBuffers = linkedSetOf<Long>()
        // 整批绑定完全一致时（区块 renderGroup 的典型情形）描述符块只写一份，每 core 省掉 bc 次 CBV 写入。
        var blockUniform = true
        // CBV 走 root descriptor（可逐 core 变化），SRV 汇入单个 descriptor table 且整段只写一份 → 只要求 SRV 恒定。
        var srvUniform = true

        // 方法引用在整批内恒定：提到循环外，避免每 core 一次 lambda 对象分配。
        val uniformSetter = this.uniformUploader
        for (d in 0 until n) {
            val draw = draws.get(d)
            val uploader = draw.uniformUploaderConsumer()
            uploader?.accept(uniformArgument, uniformSetter)
            val indexBuffer = if (draw.indexBuffer() != null) draw.indexBuffer() else defaultIndexBuffer
            val indexType = if (draw.indexType() != null) draw.indexType() else defaultIndexType
            if (indexBuffer == null || indexType == null || indexBuffer.isClosed()
                || (indexBuffer.usage() and USAGE_INDEX) == 0) {
                return false
            }
            idxBuf[d] = (indexBuffer as D3D12GpuBuffer).handle()
            idxType[d] = if (indexType == IndexType.INT) 1 else 0
            indexBuffers.add(idxBuf[d])
            idxCount[d] = draw.indexCount()
            firstIdx[d] = draw.firstIndex()
            baseVert[d] = draw.baseVertex()

            val vb = draw.vertexBuffer().slice()
            val vbBuffer = vb.buffer()
            val stride = this.vertexStrideFor(draw.slot())
            if (vbBuffer.isClosed || (vbBuffer.usage() and USAGE_VERTEX) == 0 || stride == 0) {
                return false
            }
            vbSlot[d] = draw.slot()
            vbBuf[d] = (vbBuffer as D3D12GpuBuffer).handle()
            vbOff[d] = vb.offset()
            vbStride[d] = stride
            vbBuffers.add(vbBuf[d])

            val o = d * bc
            for (j in 0 until bc) {
                val name = bName[j]
                if (bType[j] == 0) {
                    val value = this.uniforms.get(name)
                    if (value == null || value.buffer().isClosed()) {
                        return false
                    }
                    bBuf[o + j] = (value.buffer() as D3D12GpuBuffer).handle()
                    bOff[o + j] = value.offset()
                    bLen[o + j] = value.length()
                    cbvBuffers.add(bBuf[o + j])
                } else {
                    val texture = this.textures.get(name)
                    if (texture == null || texture.view.isClosed()) {
                        return false
                    }
                    bView[o + j] = texture.view.handle()
                    // SRV 视图集合不在此累加：srvUniform 保证整批一致，稍后只按 core 0 收集。
                }
            }
            // 与 core 0 的块比对：blockUniform = 整块一致；srvUniform = 仅 SRV 一致（CBV 走 root descriptor）。
            if (d > 0 && (blockUniform || srvUniform)) {
                for (j in 0 until bc) {
                    val q = o + j
                    if (bType[j] == 0) {
                        if (blockUniform && (bBuf[q] != bBuf[j] || bOff[q] != bOff[j]
                            || bLen[q] != bLen[j])) {
                            blockUniform = false
                        }
                    } else if (bView[q] != bView[j]) {
                        blockUniform = false
                        srvUniform = false
                    }
                    if (!blockUniform && !srvUniform) {
                        break
                    }
                }
            }
        }
        if (MCDR_ABI.PROF) {
            // 本批是否共享 VB/IB（第二遍纯比对，仅 PROF 时执行）。
            var sameVb = true
            var sameVbOff = true
            var sameIb = true
            var sameType = true
            var sameSlot = true
            for (d in 1 until n) {
                if (vbBuf[d] != vbBuf[0]) {
                    sameVb = false
                }
                if (vbOff[d] != vbOff[0]) {
                    sameVbOff = false
                }
                if (idxBuf[d] != idxBuf[0]) {
                    sameIb = false
                }
                if (idxType[d] != idxType[0]) {
                    sameType = false
                }
                if (vbSlot[d] != vbSlot[0]) {
                    sameSlot = false
                }
            }
            gProfSharedBatchDraws += n
            if (sameVb) {
                gProfSameVbBuf += n
            }
            if (sameVb && sameVbOff) {
                gProfSameVbOff += n
            }
            if (sameIb) {
                gProfSameIbBuf += n
            }
            if (sameIb && sameType) {
                gProfSameIdxType += n
            }
            if (sameSlot) {
                gProfVbSlotSame += n
            }
            // 统计每个 binding 是否逐 core 变化（仅 PROF）：若只有 1 个 CBV 变化，不变的 SRV 可只写一份。
            gProfBcTotal += bc
            for (j in 0 until bc) {
                var vary = false
                for (d in 1 until n) {
                    if (bType[j] == 0) {
                        if (bBuf[d * bc + j] != bBuf[j] || bOff[d * bc + j] != bOff[j]
                            || bLen[d * bc + j] != bLen[j]) {
                            vary = true
                            break
                        }
                    } else if (bView[d * bc + j] != bView[j]) {
                        vary = true
                        break
                    }
                }
                if (bType[j] == 0) {
                    if (vary) {
                        gProfCbvVary++
                    } else {
                        gProfCbvSame++
                    }
                } else if (vary) {
                    gProfSrvVary++
                } else {
                    gProfSrvSame++
                }
            }
        }

        // SRV 汇入单个 descriptor table，要求整批 SRV 完全一致；否则回退串行（串行路径逐 core 推送，语义正确）。
        if (!srvUniform) {
            if (MCDR_ABI.PROF) {
                gProfSrvVaryFallbacks++
            }
            return false
        }
        // srvUniform 成立 ⇒ 整批 SRV 与 core 0 一致，主列表过渡只需收集 core 0 的 srvCount 个视图。
        for (j in 0 until bc) {
            if (bType[j] != 0) {
                textureViews.add(bView[j])
            }
        }

        val frameSlot = async.frameSlot(this.ctx)
        val frameValue = async.frameValue(this.ctx)
        if (frameSlot < 0 || frameValue < 0) {
            return false
        }
        val profP1 = if (MCDR_ABI.PROF) System.nanoTime() else 0L
        if (profP1 != 0L) gProfBuildNs += profP1 - profP0
        // bundle 内禁止 barrier：本批所有 CBV/顶点/索引缓冲与纹理视图先在主列表过渡；失败则回退串行（串行路径自行 transition）。
        cbvBuffers.addAll(vbBuffers)
        if (!MCDR_ABI.AsyncPrepareCBVBuffers(this.ctx, toLongArray(cbvBuffers))
            || !MCDR_ABI.AsyncPrepareIndexBuffers(this.ctx, toLongArray(indexBuffers))
            || !MCDR_ABI.AsyncPrepareTextureViews(this.ctx, toLongArray(textureViews))) {
            return false
        }
        val profP2 = if (MCDR_ABI.PROF) System.nanoTime() else 0L
        if (profP2 != 0L) gProfPrepareNs += profP2 - profP1

        val pool = async.bundlePool()
        val alloc = async.descriptorAlloc()
        val pipelineHandle = pl.handle()
        val useDepth = this.hasDepth && pl.info().depthStencilState != null
        // CBV 走 root descriptor 不占描述符堆槽位；每段只需 srvCount 个槽放 SRV 表，故单分区几乎总能容纳整批。
        val sharedDesc = blockUniform
        var srvCount = 0
        for (j in 0 until bc) {
            if (bType[j] != 0) {
                srvCount++
            }
        }
        // 分配器将 count==0 视为失败，用 1 兜底（无 SRV 的管线实际不写任何描述符）。
        val slotsNeeded = srvCount.coerceAtLeast(1)
        val singleBundle = BUNDLE_MODE > 0
            || (BUNDLE_MODE == 0 && n <= SERIAL_BUNDLE_MAX_DRAWS && slotsNeeded <= 2048)
        if (MCDR_ABI.PROF) {
            gProfBatches++
            if (sharedDesc) {
                gProfSharedDescBatches++
            }
            if (singleBundle) {
                gProfSingleBundles++
            }
            gProfSlotsTotal += slotsNeeded
        }
        var workerCount = if (singleBundle) 1
            else async.workerCount().coerceAtMost(n).coerceAtLeast(1)
        if (workerCount == 1) {
            // 单分区：在调用线程内联录一个 bundle，与并行路径共用录制原语与描述符分区，只省掉线程池往返。
            var bundle = 0L
            var ok = false
            try {
                val base = MCDR_ABI.ID3D12AsyncDescriptorAllocate(alloc, frameSlot, 0,
                    slotsNeeded)
                ok = base >= 0
                    && MCDR_ABI.D3D12AsyncBundleBegin(pool, 0, frameSlot, frameValue)
                    && MCDR_ABI.D3D12AsyncBundleSetPipelineState(pool, 0, pipelineHandle,
                        useDepth)
                    && (if (blockUniform)
                        MCDR_ABI.AsyncBundleRecordPartitionShared(alloc, pool, 0, base,
                            bc, 0, n,
                            bType, bBuf, bOff, bLen, bView,
                            idxBuf, idxType, idxCount, firstIdx, baseVert,
                            vbSlot, vbBuf, vbOff, vbStride)
                    else
                        MCDR_ABI.AsyncBundleRecordPartition(alloc, pool, 0, base, bc,
                            0, n,
                            bType, bBuf, bOff, bLen, bView,
                            idxBuf, idxType, idxCount, firstIdx, baseVert,
                            vbSlot, vbBuf, vbOff, vbStride))
                if (ok) {
                    bundle = MCDR_ABI.D3D12AsyncBundleEnd(pool, 0)
                }
            } catch (t: Throwable) {
                bundle = 0L
            }
            if (!ok || bundle == 0L) {
                MCDR_ABI.D3D12AsyncBundleEnd(pool, 0)  // 复位录制器
                gProfFallbacks++
                // worker 0 分区每帧仅 3072 槽，而单分区内联录制是 bump 累积的：一帧多批（区块 3 批 ≈ 6100 槽）
                // 必然耗尽 → 直接 return false 会掉进「全串行逐 core JNI」（drawIndexedCalls 13/帧 → 345/帧）。
                // 故改为升级到真正的并行分区（各 worker 独立区域），代价仅 ~0.2ms 调度开销。
                workerCount = async.workerCount().coerceAtMost(n).coerceAtLeast(2)
                if (IllusionLogger.debugEnabled()) {
                    LOGGER.warn("drawMultipleIndexed: single-bundle alloc failed,"
                        + " escalating to parallel (draws={}, bindings={}, frameSlot={})",
                        n, bc, frameSlot)
                }
            } else {
                val profRec = if (MCDR_ABI.PROF) System.nanoTime() else 0L
                if (profRec != 0L) gProfWorkersNs += profRec - profP2
                if (!MCDR_ABI.D3D12ExecuteBundle(this.ctx, bundle)) {
                    throw IllegalStateException("dx12ExecuteBundle failed (single bundle)")
                }
                if (profRec != 0L) gProfExecuteNs += System.nanoTime() - profRec
                // bundle 内改写了根描述符表/PSO 等父列表状态，强制下次撤销快速路径。
                this.anyDescriptorDirty = true
                return true
            }
        }
        val perWorker = (n + workerCount - 1) / workerCount
        val bundles = LongArray(workerCount)
        val failed = AtomicBoolean()
        val done = CountDownLatch(workerCount)
        val poolExec = async.workers() ?: return false
        for (w in 0 until workerCount) {
            val start = w * perWorker
            val end = (start + perWorker).coerceAtMost(n)
            poolExec.execute {
                try {
                    if (failed.get()) {
                        return@execute
                    }
                    val count = end - start
                    if (count <= 0) {
                        return@execute
                    }
                    // 一次性为本段分配 SRV 表槽位（CBV 走 root descriptor，不占槽）。
                    val base = MCDR_ABI.ID3D12AsyncDescriptorAllocate(alloc, frameSlot,
                        w, slotsNeeded)
                    // 整段合并为单次 JNI（否则每 core ~8 次穿越：bc 次写描述符 + 取句柄 + 根表 + 缓冲 + DrawIndexed）。
                    if (base < 0
                        || !MCDR_ABI.D3D12AsyncBundleBegin(pool, w, frameSlot,
                            frameValue)
                        || !MCDR_ABI.D3D12AsyncBundleSetPipelineState(pool, w,
                            pipelineHandle, useDepth)
                        || !(if (sharedDesc)
                            MCDR_ABI.AsyncBundleRecordPartitionShared(
                                alloc, pool, w, base, bc, start, count,
                                bType, bBuf, bOff, bLen, bView,
                                idxBuf, idxType, idxCount, firstIdx, baseVert,
                                vbSlot, vbBuf, vbOff, vbStride)
                        else
                            MCDR_ABI.AsyncBundleRecordPartition(
                                alloc, pool, w, base, bc, start, count,
                                bType, bBuf, bOff, bLen, bView,
                                idxBuf, idxType, idxCount, firstIdx, baseVert,
                                vbSlot, vbBuf, vbOff, vbStride))) {
                        failed.set(true)
                        return@execute
                    }
                    bundles[w] = MCDR_ABI.D3D12AsyncBundleEnd(pool, w)
                    if (bundles[w] == 0L) failed.set(true)
                } catch (t: Throwable) {
                    failed.set(true)
                    if (IllusionLogger.debugEnabled()) LOGGER.warn("parallel bundle worker {} failed", w, t)
                } finally { done.countDown() }
            }
        }
        try { done.await()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            failed.set(true)
        }
        val profP3 = if (MCDR_ABI.PROF) System.nanoTime() else 0L
        if (profP3 != 0L) gProfWorkersNs += profP3 - profP2

        if (failed.get()) {
            // 丢弃已录制的 bundle，交回串行路径重录（分区内的描述符随后会被覆盖）。
            for (w in 0 until workerCount) {
                if (bundles[w] == 0L) {
                    MCDR_ABI.D3D12AsyncBundleEnd(pool, w)
                }
            }
            D3D12AsyncContext.countFallback()
            gProfFallbacks++
            if (IllusionLogger.debugEnabled()) {
                LOGGER.warn("drawMultipleIndexed: parallel recording failed, falling back to serial"
                    + " (draws={}, bindings={}, worker={}/{})", n, bc, frameSlot, workerCount)
            }
            return false
        }
        for (w in 0 until workerCount) {
            if (bundles[w] != 0L && !MCDR_ABI.D3D12ExecuteBundle(this.ctx, bundles[w])) {
                // 已经回放了一部分 bundle，不能再回退串行（会重复绘制），直接抛错。
                throw IllegalStateException("dx12ExecuteBundle failed for worker $w")
            }
        }
        if (MCDR_ABI.PROF) gProfExecuteNs += System.nanoTime() - profP3
        // bundle 内改写了根描述符表/PSO 等父列表状态：强制撤销 anyDescriptorDirty 快速路径，让后续 core 重推描述符。
        this.anyDescriptorDirty = true
        return true
    }

    private fun toLongArray(values: Set<Long>): LongArray {
        val out = LongArray(values.size)
        var i = 0
        for (v in values) {
            out[i++] = v
        }
        return out
    }
    // 多批次索引绘制已上移到前端 RenderPass（自己按 Draw 摊平成 multiDrawIndexed），backend SPI 不再有这个方法。
    fun <T : Any> drawMultipleIndexed(draws: Collection<RenderPass.Draw<T>>,
        defaultIndexBuffer: GpuBuffer?, defaultIndexType: IndexType?,
        dynamicUniforms: Collection<String>, uniformArgument: T) {
        if (this.pipeline == null || !this.pipeline!!.isValid()) {
            throw IllegalStateException("drawMultipleIndexed called without a valid pipeline")
        }
        val profT0 = if (MCDR_ABI.PROF) System.nanoTime() else 0L
        val batch = materialize(draws)
        // 地形不再走这里（MDI 地形走 drawIndexedIndirect），只剩非地形管线（水/粒子/实体）的 IMR。
        // 批量足够大时走并行 bundle 录制；条件不满足或录制失败都回退下面的串行路径，保证功能等价。
        val parallel = this.tryParallelDrawMultipleIndexed(batch, defaultIndexBuffer,
            defaultIndexType, uniformArgument)
        if (!parallel) {
            this.drawSerially(batch, defaultIndexBuffer, defaultIndexType, uniformArgument)
        }
        if (profT0 != 0L) {
            gProfMultiBatches++
            gProfMultiDraws += batch.size
            gProfMultiPathNs += System.nanoTime() - profT0
        }
    }

    /** 串行 IMR 逐 draw 绘制（回退路径与"淡入中的段"共用）。 */
    private fun <T : Any> drawSerially(draws: List<RenderPass.Draw<T>>, defaultIndexBuffer: GpuBuffer?,
        defaultIndexType: IndexType?, uniformArgument: T) {
        for (draw in draws) {
            val uploader = draw.uniformUploaderConsumer()
            uploader?.accept(uniformArgument, this.uniformUploader)
            val indexBuffer = if (draw.indexBuffer() != null) draw.indexBuffer() else defaultIndexBuffer
            val indexType = if (draw.indexType() != null) draw.indexType() else defaultIndexType
            if (indexBuffer == null || indexType == null) {
                throw IllegalStateException("No index buffer was set for core")
            }
            this.setIndexBuffer(indexBuffer, indexType)
            this.setVertexBuffer(draw.slot(), draw.vertexBuffer().slice())
            this.pushDescriptors()
            this.drawIndexed(draw.indexCount(), 1, draw.firstIndex(), draw.baseVertex(), 0)
        }
    }

    override fun draw(vertexCount: Int, instanceCount: Int, firstVertex: Int, firstInstance: Int) {
        this.pushDescriptors()
        if (!MCDR_ABI.D3D12Draw(this.ctx, vertexCount, instanceCount, firstVertex, firstInstance)) {
            throw IllegalStateException("dx12Draw failed")
        }
    }

    override fun multiDraw(drawParameters: IntBuffer, instanceCount: Int, firstInstance: Int,
        drawCount: Int) {
        for (i in 0 until drawCount) {
            val firstVertex = drawParameters.get()
            val vertexCount = drawParameters.get()
            this.draw(vertexCount, instanceCount, firstVertex, firstInstance)
        }
    }

    override fun multiDraw(firstVertices: IntBuffer, vertexCounts: IntBuffer, drawCount: Int) {
        throw UnsupportedOperationException("multiDrawDirectSeparate is not supported by the D3D12 backend")
    }

    override fun drawIndirect(commands: GpuBufferSlice, drawCount: Int) {
        this.pushDescriptors()
        if (!MCDR_ABI.D3D12DrawIndirect(this.ctx,
            (commands.buffer() as D3D12GpuBuffer).handle(), commands.offset(), drawCount)) {
            throw IllegalStateException("dx12DrawIndirect failed")
        }
    }
}

