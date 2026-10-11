package dev.nts.expdxr.dx12

import com.mojang.blaze3d.IndexType
import com.mojang.blaze3d.buffers.GpuBuffer
import com.mojang.blaze3d.buffers.GpuBufferSlice
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.systems.GpuQueryPool
import com.mojang.blaze3d.systems.RenderPass
import com.mojang.blaze3d.systems.RenderPass.RenderArea
import com.mojang.blaze3d.systems.RenderPassBackend
import com.mojang.blaze3d.textures.GpuSampler
import com.mojang.blaze3d.textures.GpuTexture
import com.mojang.blaze3d.textures.GpuTextureView
import java.nio.IntBuffer
import java.util.function.Supplier
import org.lwjgl.PointerBuffer
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * D3D12 render pass backend（P6 draw 全链路）。
 *
 * 镜像官方 {@code VulkanRenderPass}：uniform/texture 暂存在本类 map 中，draw
 * 前按需 push 到原生瞬时描述符堆（{@link #pushDescriptors()}）；setPipeline
 * 绑定 withDepth/withoutDepth PSO；顶点/索引缓冲与 draw 命令直接下发 native。
 */
class Dx12RenderPassBackend(
    private val device: Dx12Device?,
    private val ctx: Long,
    private val renderArea: RenderArea?,
    private val outputWidth: Int,
    private val outputHeight: Int,
    private val hasDepth: Boolean,
    /** P27：本 pass 的 color[0] 纹理 native handle（用于图集合成后 dump 验证）。 */
    private val colorTargetHandle: Long,
    /** P31：是否使用 shader Y-flip 变体管线（GUI 离屏 pass，由 CommandEncoder 根据 texture usage 判断）。 */
    private val flipY: Boolean,
) : RenderPassBackend {
    private var pushedDebugGroups = 0

    private var pipeline: Dx12CompiledRenderPipeline? = null
    private var anyDescriptorDirty = false
    private val uniforms: MutableMap<String, GpuBufferSlice> = HashMap()
    private val textures: MutableMap<String, TextureViewAndSampler> = HashMap()
    /** P22：帧计数，用于每帧打印一次 DynamicTransforms offset 诊断（确认 ring buffer rotate 生效）。 */
    private var frameCount = 0

    private data class TextureViewAndSampler(val view: Dx12GpuTextureView, val sampler: Dx12GpuSampler)

    /** P27：当前管线的 location 字符串（如 minecraft:pipeline/animate_sprite_blit）。 */
    fun pipelineLocation(): String? {
        val p = this.pipeline
        if (p == null || p.info().getLocation() == null) {
            return null
        }
        return p.info().getLocation().toString()
    }

    /** P27：本 pass 的 color[0] 纹理 native handle。 */
    fun colorTargetHandle(): Long {
        return this.colorTargetHandle
    }

    /** P27：本 pass 的渲染输出尺寸（用于 dump tag 区分不同图集）。 */
    fun outputWidth(): Int {
        return this.outputWidth
    }

    fun outputHeight(): Int {
        return this.outputHeight
    }

    /** P27：本 pass 的 renderArea 目标区域（图集 blit 时为 sprite 在图集中的位置）。 */
    fun areaX(): Int {
        val area = this.renderArea
        return if (area != null) area.x() else 0
    }

    fun areaY(): Int {
        val area = this.renderArea
        return if (area != null) area.y() else 0
    }

    fun areaWidth(): Int {
        val area = this.renderArea
        return if (area != null) area.width() else this.outputWidth
    }

    fun areaHeight(): Int {
        val area = this.renderArea
        return if (area != null) area.height() else this.outputHeight
    }

    override fun pushDebugGroup(label: Supplier<String>) {
        this.pushedDebugGroups++
    }

    override fun popDebugGroup() {
        this.pushedDebugGroups--
    }

    override fun writeTimestamp(pool: GpuQueryPool, index: Int) {
        McdrAbi.dx12WriteTimestamp(this.ctx,
            (pool as Dx12GpuQueryPool).nativeHandle(), index)
    }
    // -----------------------------------------------------------------------
    // Pipeline + descriptors
    // -----------------------------------------------------------------------

    override fun setPipeline(pipeline: RenderPipeline) {
        val device = this.device
            ?: throw IllegalStateException("No D3D12 device bound to this render pass")
        // 注：Java 里还有 `compiled == null` 分支；Dx12Device.getOrCompilePipeline 的
        // Kotlin 声明是非空返回，该分支已不可达，故此处只保留 isValid 校验。
        val compiled = device.getOrCompilePipeline(pipeline, this.flipY)
        if (!compiled.isValid()) {
            throw IllegalStateException(
                "Pipeline " + pipeline.getLocation() + " is not valid (shader compilation failed)")
        }
        this.pipeline = compiled
        this.anyDescriptorDirty = true
        // P22：desc.hasDepth 来自渲染 pass（是否有 depth attachment），但 PSO 变体选择
        // 应基于管线本身是否有 DepthStencilState。GUI 管线没有 depthStencilState，
        // 即使 pass 有 depth attachment 也不该启用深度测试（否则 withDepth PSO 的
        // GREATER_EQUAL 测试会丢弃所有 fragment → 黑屏）。
        val pipelineHasDepth = compiled.info().getDepthStencilState() != null
        val useDepth = this.hasDepth && pipelineHasDepth
        val ok = McdrAbi.dx12SetPipeline(this.ctx, compiled.handle(), useDepth)
        // P29：诊断打印仅在 DX12_LOG_VERBOSE=1 时输出，避免每帧同步 I/O。
        if (McdrAbi.LOG_VERBOSE) {
            System.err.println("[dx12-java] setPipeline: " + pipeline.getLocation()
                    + " pso=" + java.lang.Long.toHexString(compiled.handle())
                    + " passHasDepth=" + this.hasDepth + " pipelineHasDepth=" + pipelineHasDepth
                    + " useDepth=" + useDepth + " ok=" + ok)
        }
        if (!ok) {
            throw IllegalStateException("dx12SetPipeline failed for " + pipeline.getLocation())
        }
    }

    override fun bindTexture(name: String, textureView: GpuTextureView?,
        sampler: GpuSampler?) {
        if (textureView == null || sampler == null) {
            if (textureView != null || sampler != null) {
                throw IllegalArgumentException("Both texture and sampler must be null or non-null")
            }
            this.textures.remove(name)
            return
        }
        this.textures.put(name, TextureViewAndSampler(
            textureView as Dx12GpuTextureView, sampler as Dx12GpuSampler))
        this.anyDescriptorDirty = true
    }

    override fun setUniform(name: String, value: GpuBuffer) {
        this.uniforms.put(name, value.slice())
        this.anyDescriptorDirty = true
    }

    override fun setUniform(name: String, value: GpuBufferSlice) {
        // P22 诊断：检查 uniform 数据是否含 NaN/Inf（投影矩阵等异常值会污染着色器）。
        // P29：每帧多次 GPU buffer map + 扫描开销大，仅在 DX12_LOG_VERBOSE=1 时执行。
        if (McdrAbi.LOG_VERBOSE && value.length() >= 4) {
            try {
                value.buffer().map(value.offset(),
                    Math.min(value.length(), 64L), true, false).use { mv ->
                    val fb: java.nio.FloatBuffer =
                        mv.data().order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                    val limit = Math.min(fb.remaining(), 16)
                    for (i in 0 until limit) {
                        if (fb.hasRemaining()) {
                            val fv = fb.get()
                            if (fv.isNaN() || fv.isInfinite()) {
                                val buf = value.buffer()
                                val bufId = if (buf is Dx12GpuBuffer) buf.handle() else 0L
                                System.err.printf(
                                    "[dx12-java] NaN uniform '%s' buf=%x idx=%d val=%s%n",
                                    name, bufId, i,
                                    if (fv.isNaN()) "NaN" else "Inf")
                                System.err.flush()
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                // map 失败（如 READONLY buffer）时忽略
            }
        }
        this.uniforms.put(name, value)
        this.anyDescriptorDirty = true
    }

    /**
     * 26.2 新增的 RenderPassBackend 抽象方法：转发到 native 的 RSSetViewports，
     * 空值/失败处理风格与 [enableScissor] 一致（失败只记 error 日志，不抛异常）。
     *
     * 注意：此处与 enableScissor 不同，**未**做 D3D12 左上原点所需的 Y 翻转，
     * 直接透传调用方给的 y（与 native 侧 dx12SetViewport 的约定保持一致）。
     */
    override fun setViewport(x: Int, y: Int, width: Int, height: Int) {
        if (!McdrAbi.dx12SetViewport(this.ctx, x, y, width, height)) {
            LOGGER.error("dx12SetViewport failed ({} {} {} {})", x, y, width, height)
        }
    }

    /** 镜像官方 pushDescriptors：把当前 uniform/texture 写入原生瞬时描述符堆并绑定 root table。 */
    private fun pushDescriptors() {
        if (!this.anyDescriptorDirty) {
            return
        }
        this.anyDescriptorDirty = false
        // P22：每进入一次 pushDescriptors 视为一帧的新绘制批次
        this.frameCount++
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
        // P16 诊断：首帧打印 binding 名称列表（P29：仅 verbose 模式输出）
        if (McdrAbi.LOG_VERBOSE && System.err is java.io.PrintStream) {
            val sb = StringBuilder("[dx12-java] pushDesc pipeline=")
                .append(pipeline.info().getLocation()).append(" count=").append(count)
            var i = 0
            while (i < count && i < 8) {
                sb.append(" [").append(i).append("=").append(bindings.get(i).type)
                    .append(":").append(bindings.get(i).name).append("]")
                i++
            }
            System.err.println(sb)
            System.err.flush()
        }
        val types = IntArray(count)
        val buffers = LongArray(count)
        val offsets = LongArray(count)
        val lengths = LongArray(count)
        val texelFormats = IntArray(count)
        val views = LongArray(count)
        for (i in 0 until count) {
            val entry = bindings.get(i)
            when (entry.type) {
                Dx12BindGroupEntry.Type.UNIFORM_BUFFER -> {
                    val value = requireUniform(entry.name)
                    val buffer = value.buffer()
                    types[i] = 0
                    buffers[i] = (buffer as Dx12GpuBuffer).handle()
                    offsets[i] = value.offset()
                    lengths[i] = value.length()
                    // P20：诊断 binding 的 buffer 句柄、offset 和长度（P29：仅 verbose）
                    // P22：打印 DynamicTransforms 的 offset，验证 ring buffer rotate 是否生效
                    if (McdrAbi.LOG_VERBOSE && System.err is java.io.PrintStream) {
                        System.err.printf("[dx12-java] pushDesc binding[%d]: name=%s type=UNIFORM buf=%x off=%d len=%d heapType=?%n",
                            i, entry.name, buffers[i], offsets[i].toInt(), lengths[i].toInt())
                        if ("DynamicTransforms" == entry.name) {
                            System.err.printf("[dx12-java] pushDesc DynamicTransforms: frame=%d off=%d buf=%x%n",
                                frameCount, offsets[i].toInt(), buffers[i])
                        }
                        System.err.flush()
                    }
                }
                Dx12BindGroupEntry.Type.SAMPLED_IMAGE -> {
                    val texture = this.textures.get(entry.name)
                    if (texture == null) {
                        throw IllegalStateException("Texture '" + entry.name + "' was not bound before draw")
                    }
                    if (texture.view.isClosed()) {
                        throw IllegalStateException("Texture '" + entry.name + "' is closed")
                    }
                    types[i] = 1
                    views[i] = texture.view.handle()
                    // P22：打印 Sampler0 view handle，验证纹理视图是否正常传递（P29：仅 verbose）
                    if (McdrAbi.LOG_VERBOSE && "Sampler0" == entry.name
                        && System.err is java.io.PrintStream) {
                        val tex: GpuTexture = texture.view.texture()
                        System.err.printf("[dx12-java] pushDesc Sampler0: frame=%d view=%x closed=%b fmt=%s w=%d h=%d%n",
                            frameCount, views[i], texture.view.isClosed(),
                            tex.getFormat(), tex.getWidth(0), tex.getHeight(0))
                        System.err.flush()
                    }
                }
                Dx12BindGroupEntry.Type.TEXEL_BUFFER -> {
                    val value = requireUniform(entry.name)
                    val buffer = value.buffer()
                    types[i] = 2
                    buffers[i] = (buffer as Dx12GpuBuffer).handle()
                    offsets[i] = value.offset()
                    lengths[i] = value.length()
                    // Java 里此处直接解引用 texelBufferFormat()（null 会 NPE），用 !! 保留同样语义。
                    texelFormats[i] = entry.texelBufferFormat!!.ordinal
                }
            }
        }
        if (!McdrAbi.dx12PushDescriptors(this.ctx, types, buffers, offsets, lengths, texelFormats, views)) {
            throw IllegalStateException("dx12PushDescriptors failed")
        }
    }

    private fun requireUniform(name: String): GpuBufferSlice {
        val value = this.uniforms.get(name)
        if (value == null) {
            throw IllegalStateException("Uniform '" + name + "' was not set before draw")
        }
        val buffer = value.buffer()
        if (buffer.isClosed()) {
            throw IllegalStateException("Uniform '" + name + "' buffer is closed")
        }
        return value
    }
    // -----------------------------------------------------------------------
    // Scissor
    // -----------------------------------------------------------------------

    override fun enableScissor(x: Int, y: Int, width: Int, height: Int) {
        // RenderPass.enableScissor 沿用经典 OpenGL 约定：原点在左下角、Y 轴向上。
        // 调用方（GuiRenderer / GuiItemAtlas / RenderType 的 scissorState）传入的 y 已按
        // 该约定做过「GUI 自顶向下 → 自底向上」转换（如 window.height - bottom）。
        //
        // 而 D3D12 的 RSSetScissorRects 使用左上角原点、Y 轴向下，两者恰好互为垂直镜像，
        // 故此处必须再翻转一次（outputHeight - y - height）才能落到 D3D12 坐标系。
        // 若原样直通，裁剪区会沿屏幕中线镜像：列表项被上移裁剪（选中框缺上边框、首行文字
        // 被切），表现为「标签整体上移」。
        val scissorY = this.outputHeight - y - height
        if (!McdrAbi.dx12SetScissor(this.ctx, x, scissorY, width, height)) {
            LOGGER.error("dx12SetScissor failed ({} {} {} {})", x, scissorY, width, height)
        }
    }

    override fun disableScissor() {
        val area = this.renderArea
        if (area != null) {
            this.enableScissor(area.x(), area.y(), area.width(), area.height())
        } else {
            this.enableScissor(0, 0, this.outputWidth, this.outputHeight)
        }
    }

    // -----------------------------------------------------------------------
    // Vertex / index buffers + draws
    // -----------------------------------------------------------------------

    override fun setVertexBuffer(slot: Int, vertexBuffer: GpuBufferSlice?) {
        if (vertexBuffer == null) {
            return  // 与 Vulkan 语义一致：null 绑定不操作
        }
        val buffer = vertexBuffer.buffer()
        if (buffer.isClosed()) {
            throw IllegalStateException("Vertex buffer at slot " + slot + " has been closed!")
        }
        if ((buffer.usage() and USAGE_VERTEX) == 0) {
            throw IllegalStateException("Vertex buffer at slot " + slot + " doesn't have GpuBuffer.USAGE_VERTEX flag!")
        }
        val pipeline = this.pipeline
        var stride = 0
        if (pipeline != null) {
            val info = pipeline.info()
            val bindings = info.getVertexFormatBindings()
            if (bindings != null && slot < bindings.size) {
                val binding = bindings[slot]
                if (binding != null) {
                    stride = binding.getVertexSize()
                }
            }
        }
        if (stride == 0) {
            throw IllegalStateException(
                "setVertexBuffer: cannot derive stride for slot " + slot
                + " (pipeline=" + (if (pipeline != null) pipeline.info().getLocation() else "null")
                + "), vertexFormatBindings is empty or slot out of range")
        }
        // P22 诊断：记录顶点缓冲大小，用于排查 SizeInBytes 不足问题（P29：仅 verbose）
        if (McdrAbi.LOG_VERBOSE) {
            val bufSize = buffer.size()
            val vbOffset = vertexBuffer.offset()
            val vbRemaining = bufSize - vbOffset
            System.err.printf("[dx12-java] setVB slot=%d stride=%d bufSize=%d offset=%d remaining=%d pipeline=%s%n",
                slot, stride, bufSize.toInt(), vbOffset.toInt(), vbRemaining.toInt(),
                if (pipeline != null) pipeline.info().getLocation() else "null")
            System.err.flush()
        }
        if (!McdrAbi.dx12SetVertexBuffer(this.ctx, slot,
            (buffer as Dx12GpuBuffer).handle(), vertexBuffer.offset(), stride)) {
            throw IllegalStateException("dx12SetVertexBuffer failed")
        }
    }

    override fun setIndexBuffer(indexBuffer: GpuBuffer, indexType: IndexType) {
        if (indexBuffer.isClosed()) {
            throw IllegalStateException("Index buffer has been closed!")
        }
        if ((indexBuffer.usage() and USAGE_INDEX) == 0) {
            throw IllegalStateException("Index buffer doesn't have GpuBuffer.USAGE_INDEX flag!")
        }
        if (!McdrAbi.dx12SetIndexBuffer(this.ctx, (indexBuffer as Dx12GpuBuffer).handle(),
            if (indexType == IndexType.INT) 1 else 0)) {
            throw IllegalStateException("dx12SetIndexBuffer failed")
        }
    }

    // ---- P3 插桩：绘制热路径的调用次数与主线程墙钟耗时（DX12_PROF=1 时由
    // Dx12CommandEncoderBackend 每 300 帧汇总打印并清零）。用于判断帧时间到底
    // 花在「mod 的绘制/提交路径」还是「MC 自身逻辑」上。
    companion object {
        private val LOGGER: Logger = LoggerFactory.getLogger("gl4dx12")

        private const val USAGE_VERTEX = 0x20
        private const val USAGE_INDEX = 0x40
        private const val USAGE_UNIFORM = 0x80
        private const val USAGE_UNIFORM_TEXEL_BUFFER = 0x100

        /** DEBUG: true 时强制所有 pass 使用 withoutDepth PSO（禁用深度测试/写入），用于排查黑屏是否由深度问题导致。 */
        private const val DEBUG_DISABLE_DEPTH_TEST = false

        @JvmField
        var gProfMultiBatches = 0L      // drawMultipleIndexed 批次数
        @JvmField
        var gProfMultiDraws = 0L        // 批次内 draw 总数
        @JvmField
        var gProfMultiPathNs = 0L       // 批量入口主线程墙钟耗时
        @JvmField
        var gProfDrawIndexedCalls = 0L  // drawIndexed 调用次数
        @JvmField
        var gProfDrawIndexedNs = 0L     // drawIndexed（pushDescriptors + JNI）耗时
        // P3b：把并行批量路径拆成四段，定位主线程串行开销（8 worker 相对 4 worker 无收益，
        // 说明瓶颈在主线程串行段而非 worker 并行度）。
        @JvmField
        var gProfBuildNs = 0L      // materialize + 逐 draw 参数/描述符取值
        @JvmField
        var gProfPrepareNs = 0L    // dx12AsyncPrepare{CBV,Index,Texture} 三连 JNI
        @JvmField
        var gProfWorkersNs = 0L    // dispatch 8 worker + CountDownLatch.await
        @JvmField
        var gProfExecuteNs = 0L    // ExecuteBundle 回放
        @JvmField
        var gProfFallbacks = 0L    // 并行录制失败回退串行次数
        // P62 诊断：统计区块批量内各 draw 是否共享顶点/索引缓冲。若共享，则可用
        // ExecuteIndirect（单次 indirect 覆盖整批，配合统一 VB/IB 绑定）替代逐 draw
        // 录制，直接消掉 workers（bundle 内 1086 次 DrawIndexed）那 0.24ms。
        @JvmField
        var gProfSharedBatchDraws = 0L // 参与统计的 draw 总数
        @JvmField
        var gProfSameVbBuf = 0L        // vbBuf[d]==vbBuf[0] 的 draw 数
        @JvmField
        var gProfSameVbOff = 0L        // 且 vbOff[d]==vbOff[0]
        @JvmField
        var gProfSameIbBuf = 0L        // idxBuf[d]==idxBuf[0]
        @JvmField
        var gProfSameIdxType = 0L      // 且 idxType 一致
        @JvmField
        var gProfBatches = 0L          // 走批量路径的批次数
        @JvmField
        var gProfSharedDescBatches = 0L // 其中 blockUniform=true（P59 共享描述符）
        @JvmField
        var gProfSingleBundles = 0L    // 其中单分区内联录制数
        @JvmField
        var gProfSlotsTotal = 0L       // 累计描述符槽位需求
        // P62b：逐 binding 变异性 —— 决定描述符写入能否从 n*bc 降到 n*变动数。
        @JvmField
        var gProfBcTotal = 0L          // Σ bc
        @JvmField
        var gProfCbvVary = 0L          // 逐 draw 变化的 CBV 数
        @JvmField
        var gProfCbvSame = 0L          // 逐 draw 不变的 CBV 数
        @JvmField
        var gProfSrvVary = 0L          // 逐 draw 变化的 SRV 数
        @JvmField
        var gProfSrvSame = 0L          // 逐 draw 不变的 SRV 数
        @JvmField
        var gProfVbSlotSame = 0L       // vbSlot 全批一致的 draw 数
        // B（拆根签名）：SRV 汇入单个 descriptor table 后整段只写一份，要求整批 SRV
        // 绑定完全一致；不一致的批回退串行。此计数器统计回退批次数。
        @JvmField
        var gProfSrvVaryFallbacks = 0L

        /** 低于此批量时并行调度开销高于收益，直接走串行路径。 */
        private const val PARALLEL_MIN_DRAWS = 24

        /**
         * P58：并行调度的固定开销（8 线程唤醒 + CountDownLatch）约 0.2ms/帧，且与 worker
         * 数无关（实测 4 worker 相对 8 worker 无收益）。因此当一批 draw 的 native 录制量
         * 不足以摊薄该开销时，改为「在调用线程内联录一个 bundle」——同样只走一次
         * {@code dx12AsyncBundleRecordPartition} JNI，但省去线程池往返。
         */
        private const val SERIAL_BUNDLE_MAX_DRAWS = 512

        /** DX12_BUNDLE_MODE=serial/parallel 可强制单分区或并行分区；缺省按批量自适应。 */
        private val BUNDLE_MODE = parseBundleMode()

        private fun parseBundleMode(): Int {
            var raw = System.getenv("DX12_BUNDLE_MODE")
            if (raw == null || raw.isBlank()) {
                raw = System.getProperty("dx12.bundleMode")
            }
            if (raw == null) {
                return 0
            }
            raw = raw.trim().lowercase(java.util.Locale.ROOT)
            if (raw == "serial" || raw == "1") {
                return 1
            }
            if (raw == "parallel" || raw == "0") {
                return -1
            }
            return 0
        }
    }

    override fun drawIndexed(indexCount: Int, instanceCount: Int, firstIndex: Int,
        vertexOffset: Int, firstInstance: Int) {
        val profT0 = if (McdrAbi.PROF) System.nanoTime() else 0L
        // P17 诊断：记录每次 drawIndexed 调用（P29：仅 verbose）
        if (McdrAbi.LOG_VERBOSE && System.err is java.io.PrintStream) {
            // 尝试获取当前绑定的顶点缓冲信息
            val neededVerts = indexCount // 最坏情况：每个 index 引用一个独立顶点
            System.err.println("[dx12-java] drawIndexed pipeline=" + pipeline!!.info().getLocation()
                + " count=" + indexCount + " inst=" + instanceCount
                + " first=" + firstIndex + " base=" + vertexOffset
                + " neededVerts~=" + neededVerts
                + (if (indexCount == 0) " [ZERO-COUNT!]" else ""))
            System.err.flush()
        }
        this.pushDescriptors()
        if (!McdrAbi.dx12DrawIndexed(this.ctx, indexCount, instanceCount,
            firstIndex, vertexOffset, firstInstance)) {
            throw IllegalStateException("dx12DrawIndexed failed")
        }
        if (profT0 != 0L) {
            gProfDrawIndexedCalls++
            gProfDrawIndexedNs += System.nanoTime() - profT0
        }
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
        this.pushDescriptors()
        if (!McdrAbi.dx12DrawIndexedIndirect(this.ctx,
            (commands.buffer() as Dx12GpuBuffer).handle(), commands.offset(), drawCount)) {
            throw IllegalStateException("dx12DrawIndexedIndirect failed")
        }
    }
    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> materialize(draws: Collection<RenderPass.Draw<T>>): List<RenderPass.Draw<T>> {
        if (draws is List<*>) {
            return draws as List<RenderPass.Draw<T>>
        }
        return ArrayList(draws)
    }

    /** 从当前 pipeline 的 vertexFormatBindings 推导 slot 的顶点步长；无法推导返回 0。 */
    private fun vertexStrideFor(slot: Int): Int {
        val p = this.pipeline ?: return 0
        val bindings = p.info().getVertexFormatBindings()
        if (bindings != null && slot < bindings.size) {
            val binding = bindings[slot]
            if (binding != null) {
                return binding.getVertexSize()
            }
        }
        return 0
    }
    /**
     * 把整批 draw 按 worker 数切段：各 worker 在自己的描述符分区内写瞬时 CBV/SRV 并
     * 录制成 D3D12 BUNDLE，最后由主列表按原顺序 {@code ExecuteBundle} 回放。
     *
     * <p>bundle 内禁止 ResourceBarrier，因此本批用到的 CBV 缓冲与纹理视图先由主列表
     * 统一过渡（{@code dx12AsyncPrepare*}）。描述符互不重叠，worker 之间无数据竞争。
     *
     * @return true = 已并行录制并回放；false = 未处理，调用方须走串行路径
     */
    private fun <T : Any> tryParallelDrawMultipleIndexed(draws: List<RenderPass.Draw<T>>,
        defaultIndexBuffer: GpuBuffer?, defaultIndexType: IndexType?,
        uniformArgument: T): Boolean {
        val async = Dx12AsyncContext.get()
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
        val profP0 = if (McdrAbi.PROF) System.nanoTime() else 0L
        val bType = IntArray(bc)
        // 绑定名在整批内恒定：预先取出，避免在 n 层循环里重复 List.get + name()。
        val bName = Array(bc) { "" }
        for (j in 0 until bc) {
            val e = bindings.get(j)
            bName[j] = e.name
            val type = e.type
            if (type == Dx12BindGroupEntry.Type.TEXEL_BUFFER) {
                return false  // 分区堆没有 texel buffer SRV 写入原语 -> 串行
            }
            bType[j] = if (type == Dx12BindGroupEntry.Type.UNIFORM_BUFFER) 0 else 1
        }

        // 每 draw 的绘制参数
        val idxBuf = LongArray(n)
        val idxType = IntArray(n)
        val idxCount = IntArray(n)
        val firstIdx = IntArray(n)
        val baseVert = IntArray(n)
        val vbSlot = IntArray(n)
        val vbBuf = LongArray(n)
        val vbOff = LongArray(n)
        val vbStride = IntArray(n)
        // 每 (draw, binding) 的描述符取值；n*bc 以 long 计算，防 int 溢出
        val cells = n.toLong() * bc
        if (cells > Int.MAX_VALUE) {
            return false
        }
        val bBuf = LongArray(cells.toInt())
        val bOff = LongArray(bBuf.size)
        val bLen = LongArray(bBuf.size)
        val bView = LongArray(bBuf.size)
        val cbvBuffers = java.util.LinkedHashSet<Long>()
        val textureViews = java.util.LinkedHashSet<Long>()
        // bundle 内禁止 barrier：顶点/索引缓冲也必须在主列表上先过渡（与串行路径的
        // setVertexBuffer/setIndexBuffer 一致）。顶点缓冲目标状态与 CBV 相同，
        // 汇入同一个集合；索引缓冲单独一组，过渡到 INDEX_BUFFER。
        val vbBuffers = java.util.LinkedHashSet<Long>()
        val indexBuffers = java.util.LinkedHashSet<Long>()
        // P59：检测整批 draw 的绑定是否完全一致（区块 renderGroup 的典型情形）。
        // 一致时描述符块只需写一份，每 draw 省掉 bc 次 CreateConstantBufferView/
        // CopyDescriptorsSimple 与一次 SetGraphicsRootDescriptorTable。
        var blockUniform = true
        // B（拆根签名）：CBV 走 root descriptor（可逐 draw 变化），SRV 汇入单个
        // descriptor table 且整段只写一份 -> 只要求 SRV 在整批内恒定。
        var srvUniform = true

        // 方法引用在整批内恒定：提到循环外，避免每 draw 一次 lambda 对象分配。
        val uniformSetter = RenderPass.UniformUploader { name, value -> this.setUniform(name, value) }
        for (d in 0 until n) {
            val draw = draws.get(d)
            val uploader = draw.uniformUploaderConsumer()
            if (uploader != null) {
                uploader.accept(uniformArgument, uniformSetter)
            }
            val indexBuffer = if (draw.indexBuffer() != null) draw.indexBuffer() else defaultIndexBuffer
            val indexType = if (draw.indexType() != null) draw.indexType() else defaultIndexType
            if (indexBuffer == null || indexType == null || indexBuffer.isClosed()
                || (indexBuffer.usage() and USAGE_INDEX) == 0) {
                return false
            }
            idxBuf[d] = (indexBuffer as Dx12GpuBuffer).handle()
            idxType[d] = if (indexType == IndexType.INT) 1 else 0
            indexBuffers.add(idxBuf[d])
            idxCount[d] = draw.indexCount()
            firstIdx[d] = draw.firstIndex()
            baseVert[d] = draw.baseVertex()

            val vb = draw.vertexBuffer().slice()
            val vbBuffer = vb.buffer()
            val stride = this.vertexStrideFor(draw.slot())
            if (vbBuffer.isClosed() || (vbBuffer.usage() and USAGE_VERTEX) == 0 || stride == 0) {
                return false
            }
            vbSlot[d] = draw.slot()
            vbBuf[d] = (vbBuffer as Dx12GpuBuffer).handle()
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
                    bBuf[o + j] = (value.buffer() as Dx12GpuBuffer).handle()
                    bOff[o + j] = value.offset()
                    bLen[o + j] = value.length()
                    cbvBuffers.add(bBuf[o + j])
                } else {
                    val texture = this.textures.get(name)
                    if (texture == null || texture.view.isClosed()) {
                        return false
                    }
                    bView[o + j] = texture.view.handle()
                    // SRV 视图集合不在此累加：srvUniform 保证整批一致，稍后只按
                    // draw 0 收集，省掉 n×srvCount 次装箱入集合。
                }
            }
            // P59/B：与 draw 0 的块比对。blockUniform = 整块一致（CBV+SRV）；
            // srvUniform = 仅 SRV 一致（B 方案下 CBV 走 root descriptor，可逐 draw
            // 变化，但 SRV 表整段只写一份，故要求 SRV 恒定）。
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
        if (McdrAbi.PROF) {
            // P62 诊断：本批是否共享 VB/IB（第二遍纯比对，仅 PROF 时执行）。
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
            // P62b：统计每个 binding 是否逐 draw 变化（blockUniform 为 all-or-nothing，
            // 这里要的是逐 binding 明细：若只有 1 个 CBV 变化，则可把不变的 SRV 只写
            // 一份，描述符写入量从 n*bc 降到 n*变动数）。
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

        // B（拆根签名）：SRV 汇入单个 descriptor table 且整段只写一份，要求整批 SRV
        // 绑定完全一致；否则回退串行（串行路径逐 draw 推送描述符，语义正确）。
        if (!srvUniform) {
            if (McdrAbi.PROF) {
                gProfSrvVaryFallbacks++
            }
            return false
        }
        // srvUniform 成立 => 整批 SRV 与 draw 0 一致，主列表过渡只需收集 draw 0 的
        // srvCount 个视图。
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
        val profP1 = if (McdrAbi.PROF) System.nanoTime() else 0L
        if (profP1 != 0L) gProfBuildNs += profP1 - profP0
        // bundle 内禁止 barrier：本批所有 CBV 缓冲 / 顶点缓冲 / 索引缓冲 / 纹理视图
        // 先在主列表上完成过渡。过渡失败则说明主列表状态异常，直接回退串行
        // （串行路径会自行 transition）。
        cbvBuffers.addAll(vbBuffers)
        if (!McdrAbi.dx12AsyncPrepareCBVBuffers(this.ctx, toLongArray(cbvBuffers))
            || !McdrAbi.dx12AsyncPrepareIndexBuffers(this.ctx, toLongArray(indexBuffers))
            || !McdrAbi.dx12AsyncPrepareTextureViews(this.ctx, toLongArray(textureViews))) {
            return false
        }
        val profP2 = if (McdrAbi.PROF) System.nanoTime() else 0L
        if (profP2 != 0L) gProfPrepareNs += profP2 - profP1

        val pool = async.bundlePool()
        val alloc = async.descriptorAlloc()
        val pipelineHandle = pl.handle()
        val useDepth = this.hasDepth && pl.info().getDepthStencilState() != null
        // B（拆根签名）：CBV 走 root descriptor，不占描述符堆槽位；每段（单分区内联或
        // 每个并行 worker）只需 srvCount 个槽放 SRV 表。故单分区几乎总能容纳整批。
        val sharedDesc = blockUniform
        var srvCount = 0
        for (j in 0 until bc) {
            if (bType[j] != 0) {
                srvCount++
            }
        }
        // 分配器将 count==0 视为失败，用 1 兜底（无 SRV 的管线实际不写任何描述符）。
        val slotsNeeded = Math.max(1, srvCount)
        val singleBundle = BUNDLE_MODE > 0
            || (BUNDLE_MODE == 0 && n <= SERIAL_BUNDLE_MAX_DRAWS && slotsNeeded <= 2048)
        if (McdrAbi.PROF) {
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
            else Math.max(1, Math.min(async.workerCount(), n))
        if (workerCount == 1) {
            // P58：单分区——在调用线程内联录一个 bundle。与并行路径共用同一条
            // 录制原语 + 同一描述符分区，只是省掉线程池往返。
            var bundle = 0L
            var ok = false
            try {
                val base = McdrAbi.dx12AsyncDescriptorAllocate(alloc, frameSlot, 0,
                    slotsNeeded)
                ok = base >= 0
                    && McdrAbi.dx12AsyncBundleBegin(pool, 0, frameSlot, frameValue)
                    && McdrAbi.dx12AsyncBundleSetPipelineState(pool, 0, pipelineHandle,
                        useDepth)
                    && (if (blockUniform)
                        McdrAbi.dx12AsyncBundleRecordPartitionShared(alloc, pool, 0, base,
                            bc, 0, n,
                            bType, bBuf, bOff, bLen, bView,
                            idxBuf, idxType, idxCount, firstIdx, baseVert,
                            vbSlot, vbBuf, vbOff, vbStride)
                    else
                        McdrAbi.dx12AsyncBundleRecordPartition(alloc, pool, 0, base, bc,
                            0, n,
                            bType, bBuf, bOff, bLen, bView,
                            idxBuf, idxType, idxCount, firstIdx, baseVert,
                            vbSlot, vbBuf, vbOff, vbStride))
                if (ok) {
                    bundle = McdrAbi.dx12AsyncBundleEnd(pool, 0)
                }
            } catch (t: Throwable) {
                bundle = 0L
            }
            if (!ok || bundle == 0L) {
                McdrAbi.dx12AsyncBundleEnd(pool, 0)  // 复位录制器
                gProfFallbacks++
                // P62：worker 0 分区每帧仅 3072 槽（24576/8），而单分区内联录制是
                // bump 累积的——一帧内多批（区块 3 批 × ~2032 槽 ≈ 6100）必然把区域
                // 耗尽 → 此前直接 return false 会掉进「全串行逐 draw JNI」路径
                // （每 draw 一次 pushDescriptors + 3 次 JNI），实测 drawIndexedCalls
                // 从 13/帧暴涨到 345/帧。改为升级到真正的并行分区（各 worker 独立
                // 区域），代价仅 ~0.2ms 调度开销，远低于串行逐 draw。
                workerCount = Math.max(2, Math.min(async.workerCount(), n))
                if (McdrAbi.LOG_VERBOSE) {
                    LOGGER.warn("drawMultipleIndexed: single-bundle alloc failed,"
                        + " escalating to parallel (draws={}, bindings={}, frameSlot={})",
                        n, bc, frameSlot)
                }
            } else {
                val profRec = if (McdrAbi.PROF) System.nanoTime() else 0L
                if (profRec != 0L) gProfWorkersNs += profRec - profP2
                if (!McdrAbi.dx12ExecuteBundle(this.ctx, bundle)) {
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
        val failed = java.util.concurrent.atomic.AtomicBoolean()
        val done = java.util.concurrent.CountDownLatch(workerCount)
        val poolExec = async.workers()
        if (poolExec == null) {
            return false
        }
        for (w in 0 until workerCount) {
            val worker = w
            val start = worker * perWorker
            val end = Math.min(n, start + perWorker)
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
                    val base = McdrAbi.dx12AsyncDescriptorAllocate(alloc, frameSlot,
                        worker, slotsNeeded)
                    // P57：整段合并为单次 JNI（此前每 draw ~8 次穿越：bc 次写描述符 +
                    // 取 GPU 句柄 + 根描述符表 + 索引/顶点缓冲 + DrawIndexed）。
                    if (base < 0
                        || !McdrAbi.dx12AsyncBundleBegin(pool, worker, frameSlot,
                            frameValue)
                        || !McdrAbi.dx12AsyncBundleSetPipelineState(pool, worker,
                            pipelineHandle, useDepth)
                        || !(if (sharedDesc)
                            McdrAbi.dx12AsyncBundleRecordPartitionShared(
                                alloc, pool, worker, base, bc, start, count,
                                bType, bBuf, bOff, bLen, bView,
                                idxBuf, idxType, idxCount, firstIdx, baseVert,
                                vbSlot, vbBuf, vbOff, vbStride)
                        else
                            McdrAbi.dx12AsyncBundleRecordPartition(
                                alloc, pool, worker, base, bc, start, count,
                                bType, bBuf, bOff, bLen, bView,
                                idxBuf, idxType, idxCount, firstIdx, baseVert,
                                vbSlot, vbBuf, vbOff, vbStride))) {
                        failed.set(true)
                        return@execute
                    }
                    bundles[worker] = McdrAbi.dx12AsyncBundleEnd(pool, worker)
                    if (bundles[worker] == 0L) {
                        failed.set(true)
                    }
                } catch (t: Throwable) {
                    failed.set(true)
                    if (McdrAbi.LOG_VERBOSE) {
                        LOGGER.warn("parallel bundle worker {} failed", worker, t)
                    }
                } finally {
                    done.countDown()
                }
            }
        }
        try {
            done.await()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            failed.set(true)
        }
        val profP3 = if (McdrAbi.PROF) System.nanoTime() else 0L
        if (profP3 != 0L) gProfWorkersNs += profP3 - profP2

        if (failed.get()) {
            // 丢弃已录制的 bundle，交回串行路径重录（分区内的描述符随后会被覆盖）。
            for (w in 0 until workerCount) {
                if (bundles[w] == 0L) {
                    McdrAbi.dx12AsyncBundleEnd(pool, w)
                }
            }
            Dx12AsyncContext.countFallback()
            gProfFallbacks++
            if (McdrAbi.LOG_VERBOSE) {
                LOGGER.warn("drawMultipleIndexed: parallel recording failed, falling back to serial"
                    + " (draws={}, bindings={}, worker={}/{})", n, bc, frameSlot, workerCount)
            }
            return false
        }
        for (w in 0 until workerCount) {
            if (bundles[w] != 0L && !McdrAbi.dx12ExecuteBundle(this.ctx, bundles[w])) {
                // 已经回放了一部分 bundle，不能再回退串行（会重复绘制），直接抛错。
                throw IllegalStateException("dx12ExecuteBundle failed for worker " + w)
            }
        }
        if (McdrAbi.PROF) gProfExecuteNs += System.nanoTime() - profP3
        // bundle 内改写了根描述符表/PSO 等父列表状态，强制下次撤销 anyDescriptorDirty 快速路径，
        // 让后续 draw 重新推送描述符（否则会复用 bundle 留下的根表）。
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
    override fun <T : Any> drawMultipleIndexed(draws: Collection<RenderPass.Draw<T>>,
        defaultIndexBuffer: GpuBuffer?, defaultIndexType: IndexType?,
        dynamicUniforms: Collection<String>, uniformArgument: T) {
        if (this.pipeline == null || !this.pipeline!!.isValid()) {
            throw IllegalStateException("drawMultipleIndexed called without a valid pipeline")
        }
        val profT0 = if (McdrAbi.PROF) System.nanoTime() else 0L
        val batch = materialize(draws)
        // P33：批量足够大时走并行 bundle 录制（worker pool）；任何条件不满足或录制失败
        // 都回退到下面的串行路径，保证功能等价。
        val parallel = this.tryParallelDrawMultipleIndexed(batch, defaultIndexBuffer,
            defaultIndexType, uniformArgument)
        if (!parallel) {
            for (draw in batch) {
                val uploader = draw.uniformUploaderConsumer()
                if (uploader != null) {
                    uploader.accept(uniformArgument,
                        RenderPass.UniformUploader { name, value -> this.setUniform(name, value) })
                }
                val indexBuffer = if (draw.indexBuffer() != null) draw.indexBuffer() else defaultIndexBuffer
                val indexType = if (draw.indexType() != null) draw.indexType() else defaultIndexType
                if (indexBuffer == null || indexType == null) {
                    throw IllegalStateException("No index buffer was set for draw")
                }
                this.setIndexBuffer(indexBuffer, indexType)
                this.setVertexBuffer(draw.slot(), draw.vertexBuffer().slice())
                // P22 诊断：打印每个 Draw 的顶点缓冲信息（P29：仅 verbose）
                if (McdrAbi.LOG_VERBOSE) {
                    val vbSlice = draw.vertexBuffer().slice()
                    val vbBufSize = vbSlice.buffer().size()
                    System.err.printf("[dx12-java] drawMulti slot=%d idxCount=%d vbBufSize=%d vbOff=%d vbLen=%d pipeline=%s%n",
                        draw.slot(), draw.indexCount(), vbBufSize.toInt(), vbSlice.offset().toInt(), vbSlice.length().toInt(),
                        this.pipeline!!.info().getLocation())
                    System.err.flush()
                }
                this.pushDescriptors()
                this.drawIndexed(draw.indexCount(), 1, draw.firstIndex(), draw.baseVertex(), 0)
            }
        }
        if (profT0 != 0L) {
            gProfMultiBatches++
            gProfMultiDraws += batch.size
            gProfMultiPathNs += System.nanoTime() - profT0
        }
    }

    override fun draw(vertexCount: Int, instanceCount: Int, firstVertex: Int, firstInstance: Int) {
        // P22 诊断：记录非索引 draw（DrawInstanced 路径，P29：仅 verbose）
        if (McdrAbi.LOG_VERBOSE && System.err is java.io.PrintStream) {
            System.err.println("[dx12-java] draw (non-indexed) pipeline=" + pipeline!!.info().getLocation()
                + " vertCount=" + vertexCount + " inst=" + instanceCount
                + " first=" + firstVertex)
            System.err.flush()
        }
        this.pushDescriptors()
        if (!McdrAbi.dx12Draw(this.ctx, vertexCount, instanceCount, firstVertex, firstInstance)) {
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
        throw UnsupportedOperationException(
            "multiDrawDirectSeparate is not supported by the D3D12 backend")
    }

    override fun drawIndirect(commands: GpuBufferSlice, drawCount: Int) {
        this.pushDescriptors()
        if (!McdrAbi.dx12DrawIndirect(this.ctx,
            (commands.buffer() as Dx12GpuBuffer).handle(), commands.offset(), drawCount)) {
            throw IllegalStateException("dx12DrawIndirect failed")
        }
    }
}
