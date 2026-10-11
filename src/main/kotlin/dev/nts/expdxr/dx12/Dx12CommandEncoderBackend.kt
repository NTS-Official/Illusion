package dev.nts.expdxr.dx12

import com.mojang.blaze3d.buffers.GpuBuffer
import com.mojang.blaze3d.buffers.GpuBufferSlice
import com.mojang.blaze3d.buffers.GpuFence
import com.mojang.blaze3d.systems.CommandEncoderBackend
import com.mojang.blaze3d.systems.GpuQueryPool
import com.mojang.blaze3d.systems.RenderPass
import com.mojang.blaze3d.systems.RenderPassBackend
import com.mojang.blaze3d.systems.RenderPassDescriptor
import com.mojang.blaze3d.systems.TransientMemory
import com.mojang.blaze3d.textures.GpuTexture
import com.mojang.blaze3d.textures.GpuTextureView
import java.nio.ByteBuffer
import java.util.Optional
import org.joml.Vector4fc

/**
 * D3D12 {@link CommandEncoderBackend}, mirroring the official
 * {@code VulkanCommandEncoder}.
 *
 * The native context owns two command allocators + a fence. {@link #submit()}
 * executes the recorded command list, signals the fence, and waits for the
 * value-2 completion (vanilla double-buffered submit).
 *
 * P3 scope: submit / copy / clear / render-pass lifecycle / timestamp queries.
 * Draw commands arrive with the P4 pipeline layer.
 */
class Dx12CommandEncoderBackend : CommandEncoderBackend {
    private val ctx: Long
    private val device: Dx12Device?
    private val transientMemory: Dx12TransientMemory
    private val pendingCallbacks: MutableList<Runnable> = ArrayList()
    private var currentRenderPass: Dx12RenderPassBackend? = null
    /**
     * P42：标记当前 submit() 走的是 ASYNC 路径。
     * P33 fix：命令列表的 begin / allocator reset 现在由**主线程**在帧开始时负责
     * （{@link #ensureListOpen()} → dx12BeginCommandList，内部按 fenceValue-2 等待
     * allocator 不再被 GPU 占用）。渲染线程只做 Close + ExecuteCommandLists + Present，
     * 因此不会与主线程的录制竞争同一 allocator。
     */
    private var inAsyncSubmit = false
    /**
     * P61（多帧飞行-延迟结算）：本帧提交已交给渲染线程、但尚未结算完成。
     *
     * <p>原实现在 submit() 末尾阻塞等待 dx12AsyncRenderWaitComplete（渲染线程
     * Close+Execute+Signal，实测 0.09-0.11ms/帧），该等待落在帧关键路径上。
     * 现在改为把结算推迟到下一帧真正开始录制时（{@link #ensureListOpen()}）——
     * 中间隔着 MC 的 extract（P50 实测 0.19-0.28ms），渲染线程早已完成提交，
     * 主线程基本不再阻塞。
     */
    private var pendingSubmit = false

    constructor() : this(null)

    constructor(device: Dx12Device?) {
        this.device = device
        this.ctx = McdrAbi.dx12CreateCommandEncoder()
        if (this.ctx == 0L) {
            throw IllegalStateException("dx12CreateCommandEncoder returned a null handle")
        }
        this.transientMemory = Dx12TransientMemory(this.ctx, Runnable { this.ensureListOpen() })
        // P33 fix：在构造函数中打开命令列表。beginCommandList 现在是幂等的
        // （C++ 侧若 listOpen=1 则跳过 Reset），因此 submit() 可安全再次调用而
        // 不会触发 allocator Reset E_FAIL。这确保了 self-test 等一次性路径的
        // recording 方法（writeToBuffer 等）能在 listOpen=1 的状态下执行。
        McdrAbi.dx12BeginCommandList(this.ctx)
    }

    /** Native CommandContext* handle (used by surface blit + render pass). */
    fun nativeHandle(): Long {
        return this.ctx
    }

    /**
     * P33：懒加载打开命令列表。dx12BeginCommandList 是幂等的（C++ 侧若 listOpen=1
     * 则跳过 Reset），因此重复调用安全；列表未打开时会执行 allocator reset（内部按
     * fenceValue-2 等待 GPU 释放该 allocator）并 Reset 命令列表，随后录制命令。
     */
    private fun ensureListOpen() {
        // P61：结算上一帧挂起的提交。必须在读取 listOpen **之前**完成——渲染线程
        // 的 submitCommandList 会 Close 旧列表（listOpen 1→0），若先读 listOpen
        // 可能读到它尚未 Close 的中间态而跳过 begin，主线程随即写进一条正在被
        // 渲染线程关闭/提交的列表（数据竞争）。
        settlePendingSubmit()
        if (!McdrAbi.dx12IsListOpen(this.ctx)) {
            // P33 fix：ASYNC 路径下 begin/Reset 由主线程负责（渲染线程不再触碰
            // allocator/list），因此这里直接打开即可，不存在与渲染线程竞争的问题。
            McdrAbi.dx12BeginCommandList(this.ctx)
        }
    }

    /**
     * P61：结算上一帧挂起的异步提交（{@link #pendingSubmit}）。
     *
     * <p>调用点必须早于下一帧的 dx12BeginCommandList：beginCommandList 会读写
     * ctx 的 fenceValue / currentListSlot / commandList / resourceState，这些状态
     * 同时被渲染线程的 submitCommandList（Close + ExecuteCommandLists + Signal）
     * 修改，未结算就 Reset 会产生数据竞争。
     */
    private fun settlePendingSubmit() {
        if (!this.pendingSubmit) {
            return
        }
        this.pendingSubmit = false
        val prof = McdrAbi.PROF
        val t0 = if (prof) System.nanoTime() else 0L
        val completed = McdrAbi.dx12AsyncRenderWaitComplete(this.ctx, 10_000L)
        if (prof) gProfWaitSubmitNs += System.nanoTime() - t0
        if (!completed) {
            System.err.println("[dx12] [P33] asyncRenderWaitComplete timeout! ctx=0x"
                + java.lang.Long.toHexString(this.ctx))
            System.err.flush()
        }
    }

    private fun textureHandle(texture: GpuTexture): Long {
        return (texture as Dx12GpuTexture).handle()
    }

    private fun bufferHandle(buffer: GpuBuffer): Long {
        return (buffer as Dx12GpuBuffer).handle()
    }

    /** P3 插桩：纳秒 -> 毫秒，保留两位小数。 */
    private fun profMs(ns: Double): Double {
        return Math.round(ns * 1e-6 * 100.0) / 100.0
    }

    // -----------------------------------------------------------------------
    // Transient memory
    // -----------------------------------------------------------------------

    override fun transientMemory(): TransientMemory {
        return this.transientMemory
    }
    // -----------------------------------------------------------------------
    // Render pass
    // -----------------------------------------------------------------------

    override fun createRenderPass(descriptor: RenderPassDescriptor): RenderPassBackend {
        val colorAttachments: List<RenderPassDescriptor.Attachment<Optional<Vector4fc>>?> =
            descriptor.colorAttachments()
        val colorCount = colorAttachments.size
        val colorTextures = LongArray(colorCount)
        val colorMips = IntArray(colorCount)
        val colorClearFlags = ByteArray(colorCount)
        val clearColors = FloatArray(colorCount * 4)
        for (i in 0 until colorCount) {
            val attachment = colorAttachments.get(i)
            if (attachment == null) {
                colorTextures[i] = 0L  // withUnusedColorAttachment
                continue
            }
            val view = attachment.textureView()
            colorTextures[i] = (view.texture() as Dx12GpuTexture).handle()
            // P3b：把 view 的 base mip 传给 native RTV（TextureAtlas 对 mipViews[level]
            // 逐级上传；此前 RTV 恒绑 mip0 → 图集 mip1+ 从未写入，远处大 LOD 采样
            // 到未初始化内容）。
            if (view is Dx12GpuTextureView) {
                colorMips[i] = view.baseMip()
            }
            if (attachment.clearValue().isPresent()) {
                val color = attachment.clearValue().get()
                colorClearFlags[i] = 1
                clearColors[i * 4] = color.x()
                clearColors[i * 4 + 1] = color.y()
                clearColors[i * 4 + 2] = color.z()
                clearColors[i * 4 + 3] = color.w()
            }
        }

        var depthTexture = 0L
        var depthMip = 0
        var depthClearFlag: Byte = 0
        var depthClearValue = 0.0
        val depthAttachment = descriptor.depthAttachment()
        if (depthAttachment != null) {
            val depthView = depthAttachment.textureView()
            depthTexture = (depthView.texture() as Dx12GpuTexture).handle()
            if (depthView is Dx12GpuTextureView) {
                depthMip = depthView.baseMip()
            }
            if (depthAttachment.clearValue().isPresent()) {
                depthClearFlag = 1
                depthClearValue = depthAttachment.clearValue().getAsDouble()
            }
        }

        val area = descriptor.renderArea
        var x = 0
        var y = 0
        var w = 0
        var h = 0
        if (area != null) {
            x = area.x()
            y = area.y()
            w = area.width()
            h = area.height()
        } else {
            // Fall back to the first attachment's size (vanilla asserts non-null).
            if (colorCount > 0) {
                val view = colorAttachments.get(0)!!.textureView()
                w = view.getWidth(0)
                h = view.getHeight(0)
            }
        }

        // P33：确保命令列表已打开
        this.ensureListOpen()
        McdrAbi.dx12BeginRenderPass(this.ctx, colorTextures, colorMips,
            colorClearFlags, clearColors, depthTexture, depthMip,
            depthClearFlag, depthClearValue, x, y, w, h)
        val hasDepth = depthTexture != 0L
        // P28/P31：识别「离屏图集合成 pass」（GuiItemAtlas / PictureInPicture），其特征是
        // color[0] 的 usage == 13 = COPY_DST|TEXTURE_BINDING|RENDER_ATTACHMENT（GpuTexture 位常量
        // 1|4|8）且带 depth attachment（color depth usage==9，lightmap 则无 depth）。
        // 这类 pass 用 invertY=true 的正交投影渲染，其内容随后以 GL 自底向上的 UV 约定采样，
        // 在 D3D12（NDC Y 向上）下需要 flipY 变体管线（shader 注入 gl_Position.y 取反）修正。
        //
        // 注意：绝不能用 usage == 15（=COPY_DST|COPY_SRC|TEXTURE_BINDING|RENDER_ATTACHMENT，主窗口
        // MainTarget / TextureAtlas 的通用特征）。此前误用 15 导致每帧主窗口 pass 被判定为
        // 「离屏图集 pass」，对 item_cutout/entity_cutout 注入 Y 翻转并翻转 scissor，使 GUI 图标
        // 镜像/错位、按钮底图裁剪区错乱（实测日志中 854x480 主 pass 命中 4920 次）。
        var flipY = false
        if (hasDepth && colorCount > 0) {
            val first = colorAttachments.get(0)
            if (first != null) {
                val firstView = first.textureView()
                if (firstView != null) {
                    val usage = (firstView.texture() as Dx12GpuTexture).usage()
                    if (usage == 13) {
                        flipY = true
                        System.err.println("[dx12-java] [P28] flipY=true for offscreen GUI atlas/PIP pass ("
                            + w + "x" + h + ")")
                        System.err.flush()
                    }
                }
            }
        }
        // P6 诊断：打印 pass 尺寸 + Java 调用来源（P29：getStackTrace() 开销大，
        // 仅 DX12_LOG_VERBOSE=1 时输出，避免图集上传时每帧数百次堆栈遍历）。
        if (McdrAbi.LOG_VERBOSE) {
            val st = Thread.currentThread().getStackTrace()
            val sb = StringBuilder()
            sb.append("createRenderPass: ").append(w).append('x').append(h)
                .append(" area=").append(x).append(',').append(y)
                .append(if (hasDepth) " depth=yes" else " depth=no").append(" from:")
            var shown = 0
            var i = 3
            while (i < st.size && shown < 6) {
                val cn = st[i].getClassName()
                val dot = cn.lastIndexOf('.')
                sb.append(' ').append(if (dot >= 0) cn.substring(dot + 1) else cn)
                    .append('.').append(st[i].getMethodName())
                shown++
                i++
            }
            System.err.println("[dx12-java] " + sb)
            System.err.flush()
        }
        val pass = Dx12RenderPassBackend(this.device, this.ctx,
            area, w, h, hasDepth, if (colorCount > 0) colorTextures[0] else 0L, flipY)
        this.currentRenderPass = pass
        return pass
    }
    override fun submitRenderPass() {
        val pass = this.currentRenderPass
        McdrAbi.dx12EndRenderPass(this.ctx)
        this.currentRenderPass = null
        // P27 诊断：animate_sprite_blit / interpolate 图集合成 pass 结束 → 记录待 dump 的 pass。
        // 注意：dx12EndRenderPass 只在命令列表上记录 draw，GPU 要等 submit() 的
        // dx12Submit 才真正执行。因此 dump 必须推迟到 submit() 之后执行，否则
        // dbgReadbackTexturePixels 的 deviceWaitIdle 等待不到未提交的命令，读回全 0。
        // P29：dump 含 deviceWaitIdle，整条 P27 链路仅在 DX12_LOG_VERBOSE=1 时启用。
        if (McdrAbi.LOG_VERBOSE && pass != null && gDumpedAtlas.size < MAX_ATLAS_DUMPS) {
            val loc = pass.pipelineLocation()
            if (loc != null && (loc.contains("animate") || loc.contains("sprite"))) {
                gPendingAtlasByCtx.computeIfAbsent(this.ctx) { ArrayList() }.add(pass)
                System.err.println("[dx12-java] P27 pending atlas dump ctx=0x"
                    + java.lang.Long.toHexString(this.ctx)
                    + " pass=" + loc + " colorTex=0x" + java.lang.Long.toHexString(pass.colorTargetHandle())
                    + " area=" + pass.areaX() + "," + pass.areaY()
                    + " " + pass.areaWidth() + "x" + pass.areaHeight())
                System.err.flush()
            }
        }
    }

    // -----------------------------------------------------------------------
    // Clears
    // -----------------------------------------------------------------------

    override fun clearColorTexture(colorTexture: GpuTexture, clearColor: Vector4fc) {
        // P33：确保命令列表已打开
        this.ensureListOpen()
        McdrAbi.dx12ClearColorTexture(this.ctx, textureHandle(colorTexture),
            clearColor.x(), clearColor.y(), clearColor.z(), clearColor.w())
    }

    override fun clearColorAndDepthTextures(colorTexture: GpuTexture, clearColor: Vector4fc,
        depthTexture: GpuTexture, clearDepth: Double) {
        // P33：确保命令列表已打开
        this.ensureListOpen()
        McdrAbi.dx12ClearColorTexture(this.ctx, textureHandle(colorTexture),
            clearColor.x(), clearColor.y(), clearColor.z(), clearColor.w())
        McdrAbi.dx12ClearDepthTexture(this.ctx, textureHandle(depthTexture), clearDepth)
    }

    override fun clearColorAndDepthTextures(colorTexture: GpuTexture, clearColor: Vector4fc,
        depthTexture: GpuTexture, clearDepth: Double, regionX: Int, regionY: Int,
        regionWidth: Int, regionHeight: Int) {
        // P33：确保命令列表已打开
        this.ensureListOpen()
        // P3b fix：GuiItemAtlas 槽位 STALE 重绘前只清该槽位矩形区域。原实现忽略
        // region 整图清空 → 滚动/翻页触发任意槽位重绘时把整张物品图集抹掉，其它
        // 已烘好的槽位图标（图集区域仍标记有效、不再触发重绘）随之消失。
        McdrAbi.dx12ClearColorTextureRegion(this.ctx, textureHandle(colorTexture),
            clearColor.x(), clearColor.y(), clearColor.z(), clearColor.w(),
            regionX, regionY, regionWidth, regionHeight)
        McdrAbi.dx12ClearDepthTextureRegion(this.ctx, textureHandle(depthTexture),
            clearDepth, regionX, regionY, regionWidth, regionHeight)
    }

    override fun clearDepthTexture(depthTexture: GpuTexture, clearDepth: Double) {
        // P33：确保命令列表已打开
        this.ensureListOpen()
        McdrAbi.dx12ClearDepthTexture(this.ctx, textureHandle(depthTexture), clearDepth)
    }

    /**
     * 26.2 新增的 CommandEncoderBackend 抽象方法：清空模板纹理。
     *
     * 写法与 [clearDepthTexture] 完全一致：先 [ensureListOpen]（P33），再用
     * [textureHandle] 取 native 句柄（非 Dx12GpuTexture 会在 cast 处抛
     * ClassCastException，与 depth/color 清理路径的校验方式相同）。
     * native 侧返回 1=成功 / 0=失败，与 clearDepthTexture 一样不检查返回值。
     */
    override fun clearStencilTexture(texture: GpuTexture, value: Int) {
        // P33：确保命令列表已打开
        this.ensureListOpen()
        McdrAbi.dx12ClearStencilTexture(this.ctx, textureHandle(texture), value)
    }

    // -----------------------------------------------------------------------
    // Copies
    // -----------------------------------------------------------------------

    override fun writeToBuffer(destination: GpuBufferSlice, data: ByteBuffer) {
        // P33：确保命令列表已打开
        this.ensureListOpen()
        // 与官方 VulkanCommandEncoder.writeToBuffer 对齐：直接上传，不做 NaN 检测/替换。
        // 若数据含 NaN（例如投影矩阵 near/far 异常），应在源头修复，而非在上传时暴力覆盖。
        // 暴力替换会把整块顶点数据也错误地覆写，导致黑屏（P7 根因定位）。
        val staging = this.transientMemory.uploadStaging(data, 1L,
            GpuBuffer.USAGE_COPY_SRC)
        McdrAbi.dx12CopyBuffer(this.ctx, bufferHandle(staging.buffer()), staging.offset(),
            bufferHandle(destination.buffer()), destination.offset(), data.remaining().toLong())
    }

    override fun copyToBuffer(source: GpuBufferSlice, target: GpuBufferSlice) {
        // P33：确保命令列表已打开
        this.ensureListOpen()
        // P22 诊断：记录 copyToBuffer 参数，排查缓冲区大小不足（P29：仅 verbose）。
        // 注意：copyToBuffer 由图集/区块/实体的 StagingBuffer 上传路径每帧调用数十次，
        // 且 System.err.printf 会绕过 log4j 直写控制台，无条件打印会造成每帧同步 I/O
        // （P53 采样中表现为 writeBytes 热点）——必须门控。
        if (McdrAbi.LOG_VERBOSE) {
            System.err.printf("[dx12-java] copyToBuf srcBuf=%x srcOff=%d srcLen=%d dstBuf=%x dstOff=%d%n",
                bufferHandle(source.buffer()), source.offset().toInt(), source.length().toInt(),
                bufferHandle(target.buffer()), target.offset().toInt())
            System.err.flush()
        }
        McdrAbi.dx12CopyBuffer(this.ctx, bufferHandle(source.buffer()), source.offset(),
            bufferHandle(target.buffer()), target.offset(), source.length())
    }

    override fun writeToTexture(destination: GpuTexture, source: ByteBuffer, mipLevel: Int,
        depthOrLayer: Int, destX: Int, destY: Int, width: Int, height: Int) {
        // P33：确保命令列表已打开
        this.ensureListOpen()
        val staging = this.transientMemory.uploadStaging(source, 1L,
            GpuBuffer.USAGE_COPY_SRC)
        McdrAbi.dx12WriteToTexture(this.ctx, bufferHandle(staging.buffer()), staging.offset(),
            width, height, textureHandle(destination), mipLevel, depthOrLayer, destX, destY)
    }

    override fun copyBufferToTexture(source: GpuBufferSlice, sourceX: Int, sourceY: Int,
        sourceWidth: Int, sourceHeight: Int, destination: GpuTexture, destinationX: Int,
        destinationY: Int, copyWidth: Int, copyHeight: Int, mipLevel: Int, arrayLayer: Int) {
        // P54 fix：必须显式确保命令列表已打开。此前该路径依赖 uploadStaging 内部的
        // staging->DEFAULT 拷贝顺带调用 ensureListOpen；P54 将 uploadStaging 改为纯
        // CPU 侧的块内映射写入（不再录制任何 GPU 命令）后，这里就会在帧外资源重载
        // （SpriteContents mip 上传 / 图集动画）时抛
        // "dx12CopyBufferToTexture: no open command list" → 图集/字体纹理上传失败
        // → 白屏 + 五颜六色闪烁。
        this.ensureListOpen()
        val texelSize = destination.getFormat().blockSize()
        val skipTexels = sourceX.toLong() + sourceY.toLong() * sourceWidth
        val skipBytes = skipTexels * texelSize.toLong()
        McdrAbi.dx12CopyBufferToTexture(this.ctx, bufferHandle(source.buffer()),
            source.offset() + skipBytes, sourceWidth, sourceHeight,
            textureHandle(destination), mipLevel, arrayLayer,
            destinationX, destinationY, copyWidth, copyHeight)
    }

    override fun copyTextureToBuffer(source: GpuTexture, destination: GpuBuffer, offset: Long,
        callback: Runnable, mipLevel: Int) {
        this.copyTextureToBuffer(source, destination, offset, callback, mipLevel,
            0, 0, source.getWidth(mipLevel), source.getHeight(mipLevel))
    }

    override fun copyTextureToBuffer(source: GpuTexture, destination: GpuBuffer, offset: Long,
        callback: Runnable, mipLevel: Int, x: Int, y: Int, width: Int, height: Int) {
        // P33：确保命令列表已打开
        this.ensureListOpen()
        McdrAbi.dx12CopyTextureToBuffer(this.ctx, textureHandle(source), mipLevel, 0,
            x, y, width, height, bufferHandle(destination), offset)
        // Run the callback after the next submit (mirror of the destroyQueue rotate).
        this.pendingCallbacks.add(callback)
    }

    override fun copyTextureToTexture(source: GpuTexture, destination: GpuTexture, mipLevel: Int,
        destX: Int, destY: Int, sourceX: Int, sourceY: Int, width: Int, height: Int) {
        // P33：确保命令列表已打开
        this.ensureListOpen()
        McdrAbi.dx12CopyTextureToTexture(this.ctx, textureHandle(source),
            textureHandle(destination), mipLevel, 0, sourceX, sourceY, destX, destY, width, height)
    }
    // -----------------------------------------------------------------------
    // Fences & timestamps
    // -----------------------------------------------------------------------

    override fun createFence(): GpuFence {
        // 官方 VulkanCommandEncoder.createFence()：捕获当前 submit index，fence
        // 在该 encoder 的下一次提交完成后完成。官方 createCommandEncoder() 返回
        // 共享 encoder，因此一次性 encoder 上创建的 fence token（queueFencedTask /
        // StagedVertexBuffer endFrame / MappableRingBuffer rotate）也随下一次提交
        // 完成。D3D12 侧用设备级队列 fence 复现该语义：目标 = 当前 queueFenceValue
        // + 1，任何 ctx 的下一次 submit 都会推进它（见 dx12WaitForFence）。
        val fenceValue = McdrAbi.dx12GetFenceValue(this.ctx)
        val target = fenceValue + 1
        return object : GpuFence {
            private var completed = false

            override fun awaitCompletion(timeoutMs: Long): Boolean {
                if (!this.completed) {
                    val timeoutNs = if (timeoutMs > Long.MAX_VALUE / 1_000_000L)
                        Long.MAX_VALUE
                        else timeoutMs * 1_000_000L
                    this.completed = McdrAbi.dx12WaitForFence(
                        this@Dx12CommandEncoderBackend.ctx, target, timeoutNs)
                }
                return this.completed
            }

            override fun close() {
                this.completed = true
            }
        }
    }

    override fun writeTimestamp(pool: GpuQueryPool, index: Int) {
        // P33：确保命令列表已打开
        this.ensureListOpen()
        McdrAbi.dx12WriteTimestamp(this.ctx, (pool as Dx12GpuQueryPool).nativeHandle(), index)
    }

    // -----------------------------------------------------------------------
    // Submit lifecycle
    // -----------------------------------------------------------------------

    override fun submit() {
        // P33 async：独立渲染线程流水线
        //
        // 流程：
        //   1. 记录当前 queue fence 值（= 上一帧的 submitQueueFence）
        //   2. 请求渲染线程开始新帧（通知它主线程已录制完毕）
        //   3. 等待渲染线程发 RECORDING_READY
        //   4. 主线程继续录制命令（render pass / clear / copy / draw）
        //   5. 通知渲染线程命令已就绪（set gEvtCommandsReady）
        //   6. 等待渲染线程完成提交 + present（submits + signal fence + present）
        //   7. 执行 post-submit 清理（transientMemory rotate、callback 等）
        //
        // P33 fix（帧序倒置修复）：allocator Reset + command list begin 由**主线程**
        //       在帧开始时完成（ensureListOpen → dx12BeginCommandList）。渲染线程
        //       只负责 Close + ExecuteCommandLists + Present，绝不再 Reset 列表——
        //       否则会丢弃主线程已录制的整帧命令，back buffer 永远不被写入，
        //       Present 只能显示未初始化内容（窗口闪烁各种颜色的根因）。

        // P61：先结算上一帧挂起的提交。正常路径已由 ensureListOpen() 结算，此处
        // 兜底（上一帧若没有任何绘制命令就不会走到 ensureListOpen）。必须早于
        // dx12AsyncRenderBeginFrame：后者要求 gAsyncRenderCtx 已被 waitComplete 置空。
        settlePendingSubmit()

        // P15 诊断：记录提交前的 fence 值
        val fenceBefore = McdrAbi.dx12GetFenceValue(this.ctx)

        // 步骤 2：请求渲染线程开始新帧
        val asyncStarted = McdrAbi.dx12AsyncRenderBeginFrame(this.ctx)
        if (!asyncStarted) {
            if (McdrAbi.LOG_VERBOSE) {
                System.err.println("[dx12-java] submit: SYNC fallback (no active surface or previous frame pending) fence=" + fenceBefore)
                System.err.flush()
            }
            // 无 active surface（初始化阶段或窗口未创建），回退到同步路径
            // 同步路径：确保命令列表已打开（幂等：已打开则跳过 Reset），录制命令、提交
            McdrAbi.dx12BeginCommandList(this.ctx)
            McdrAbi.dx12Submit(this.ctx)
            this.transientMemory.rotate()
            Dx12Device.dumpDebugLightmap()
            val run = this.pendingCallbacks
            this.pendingCallbacks.clear()
            for (callback in run) {
                callback.run()
            }
            return
        }

        // 异步路径：命令已由上层框架在本次 submit() 之前录制完毕，
        // 渲染线程只负责 Close + Execute + Present。
        // 注意：不在此调用 dx12BeginCommandList（列表已由录制阶段打开）。
        this.inAsyncSubmit = true
        if (McdrAbi.LOG_VERBOSE) {
            System.err.println("[dx12-java] submit: ASYNC path fence=" + fenceBefore)
            System.err.flush()
        }

        // P3 插桩：记录本帧起点与相对上一帧的间隔（仅 DX12_PROF=1 生效）。
        val prof = McdrAbi.PROF
        val profStart = System.nanoTime()
        if (prof) {
            // 累加而非覆盖：否则只统计到最后一个间隔。
            if (gProfLastSubmitNs != 0L) gProfIntervalNs += profStart - gProfLastSubmitNs
            gProfLastSubmitNs = profStart
        }

        // 步骤 3：等待渲染线程到达 RECORDING_READY。
        // 用 native 阻塞等待（WaitForSingleObject）取代原 sleep(1) 轮询：后者每帧在
        // 帧关键路径上引入约 1ms 的调度器休眠开销；阻塞等待为微秒级。超时保护不变。
        val profT0 = if (prof) System.nanoTime() else 0L
        val recordingReady = McdrAbi.dx12AsyncRenderWaitRecordingReady(this.ctx, 5_000L)
        if (prof) gProfRecordingReadyNs += System.nanoTime() - profT0
        if (!recordingReady) {
            // P41：渲染线程卡死，降级到 SYNC 模式避免整个游戏崩溃
            System.err.println("[dx12] [P41] asyncRenderWaitRecordingReady timeout after 5000ms — falling back to SYNC, ctx=0x"
                + java.lang.Long.toHexString(this.ctx))
            System.err.flush()
            // 清除 gAsyncRenderCtx，防止下一个 asyncBeginFrame 被 "previous frame not complete" 拒绝
            McdrAbi.dx12ClearAsyncRenderCtx()
            McdrAbi.dx12BeginCommandList(this.ctx)
            McdrAbi.dx12Submit(this.ctx)
            this.transientMemory.rotate()
            val run = this.pendingCallbacks
            this.pendingCallbacks.clear()
            for (callback in run) {
                callback.run()
            }
            return
        }
        // 步骤 4：命令已由上层框架在此 submit() 之前录制完毕（render pass、clear、copy、draw）
        //         无需额外操作。

        // 步骤 5：通知渲染线程所有命令已入队
        McdrAbi.dx12AsyncSendCommandsReady(this.ctx)

        // 步骤 6（P61 多帧飞行-延迟结算）：本帧**不再**阻塞等待渲染线程完成提交。
        // 渲染线程此刻开始 Close+Execute+Signal+Present；主线程直接返回，把结算
        // 推迟到下一帧 ensureListOpen()/submit() 开头——中间隔着 MC 的 extract
        //（P50 实测 0.19-0.28ms），届时渲染线程早已完成，等待≈0。
        // 正确性：结算仍早于下一次 beginCommandList（见 settlePendingSubmit）。
        this.pendingSubmit = true

        // 步骤 7：post-submit 清理（与旧同步 submit 保持一致）
        this.transientMemory.rotate()
        // P27: dx12Submit 已提交 GPU → 现在读回图集才是真实内容。
        if (McdrAbi.LOG_VERBOSE) {
            val atlasPasses = gPendingAtlasByCtx.remove(this.ctx)
            if (atlasPasses != null && !atlasPasses.isEmpty()) {
                for (dumpPass in atlasPasses) {
                    val h = dumpPass.colorTargetHandle()
                    if (!gDumpedAtlas.contains(h) && gDumpedAtlas.size < MAX_ATLAS_DUMPS) {
                        gDumpedAtlas.add(h)
                        System.err.println("[dx12-java] P27 dump atlas (after submit) ctx=0x"
                            + java.lang.Long.toHexString(this.ctx)
                            + " pass=" + dumpPass.pipelineLocation()
                            + " size=" + dumpPass.outputWidth() + "x" + dumpPass.outputHeight()
                            + " lastArea=" + dumpPass.areaX() + "," + dumpPass.areaY()
                            + " " + dumpPass.areaWidth() + "x" + dumpPass.areaHeight()
                            + " colorTex=0x" + java.lang.Long.toHexString(h))
                        System.err.flush()
                        McdrAbi.dx12DumpTextureToFile(h,
                            "atlas_" + dumpPass.outputWidth() + "x" + dumpPass.outputHeight()
                            + "_" + java.lang.Long.toHexString(h and 0xFFFFL))
                    }
                }
            }
        }
        // P38 诊断：共享 encoder（device != null）的 submit 对应整帧提交；此时 lightmap 的
        // "Update light" pass 已执行，读回 16×16 光照纹理是真实内容。最多 dump MAX_LIGHTMAP_DUMPS 次、
        // 间隔 LIGHTMAP_DUMP_INTERVAL 帧，降低 waitIdle 同步气泡与日志量。
        if (this.device != null && McdrAbi.LOG_VERBOSE
            && dx12DebugLightmapDumps < MAX_LIGHTMAP_DUMPS) {
            val lmh = Dx12Device.getDebugLightmapHandle()
            if (lmh != 0L) {
                ++dx12DebugLightmapTick
                // 整帧 submit 满 LIGHTMAP_DUMP_INTERVAL 次触发一次（近似每 ~120 帧）
                if (dx12DebugLightmapTick % LIGHTMAP_DUMP_INTERVAL == 0) {
                    ++dx12DebugLightmapDumps
                    System.err.println("[dx12-java] P38 dump lightmap #" + dx12DebugLightmapDumps
                        + " tex=0x" + java.lang.Long.toHexString(lmh) + " after submit")
                    System.err.flush()
                    McdrAbi.dx12DumpTextureToFile(lmh, "lightmap_" + dx12DebugLightmapDumps)
                }
            }
        }
        // Run callbacks queued by the previous frame's copyTextureToBuffer.
        val run = this.pendingCallbacks
        this.pendingCallbacks.clear()
        for (callback in run) {
            callback.run()
        }
        // P15: 每 30 帧打印一次 submit 摘要
        if (McdrAbi.LOG_VERBOSE && (fenceBefore % 30L) == 0L) {
            System.err.println("[dx12-java] submit(async): frame=" + fenceBefore
                + " ctx=" + java.lang.Long.toHexString(this.ctx))
            System.err.flush()
        }
        // P3 插桩：每 PROF_INTERVAL 帧输出一次各阶段耗时均值（ms）。
        if (prof) {
            ++gProfAsyncFrames
            gProfTotalNs += System.nanoTime() - profStart
            if (gProfAsyncFrames % PROF_INTERVAL == 0L) {
                val inv = 1.0 / PROF_INTERVAL
                System.err.println("[dx12-java] P3 prof n=" + PROF_INTERVAL
                    + " frameInterval=" + profMs(gProfIntervalNs * inv)
                    + "ms recordingReady=" + profMs(gProfRecordingReadyNs * inv)
                    + "ms waitSubmit=" + profMs(gProfWaitSubmitNs * inv)
                    + "ms submitTotal=" + profMs(gProfTotalNs * inv) + "ms")
                // P3 绘制热路径：判断帧时间花在 mod 绘制路径还是 MC 自身逻辑。
                System.err.println("[dx12-java] P3 draw: batches/frame=" + (Dx12RenderPassBackend.gProfMultiBatches * inv)
                    + " draws/frame=" + (Dx12RenderPassBackend.gProfMultiDraws * inv)
                    + " batchPath=" + profMs(Dx12RenderPassBackend.gProfMultiPathNs * inv) + "ms"
                    + " drawIndexedCalls/frame=" + (Dx12RenderPassBackend.gProfDrawIndexedCalls * inv)
                    + " drawIndexed=" + profMs(Dx12RenderPassBackend.gProfDrawIndexedNs * inv) + "ms")
                // P3b：并行批量路径四段拆分（主线程串行 vs worker 并行）。
                System.err.println("[dx12-java] P3 batch: build=" + profMs(Dx12RenderPassBackend.gProfBuildNs * inv)
                    + "ms prepare=" + profMs(Dx12RenderPassBackend.gProfPrepareNs * inv)
                    + "ms workers=" + profMs(Dx12RenderPassBackend.gProfWorkersNs * inv)
                    + "ms execute=" + profMs(Dx12RenderPassBackend.gProfExecuteNs * inv)
                    + "ms fallbacks=" + (Dx12RenderPassBackend.gProfFallbacks * inv)
                    + " | P62 batchDraws=" + Dx12RenderPassBackend.gProfSharedBatchDraws
                    + " vbSame=" + Dx12RenderPassBackend.gProfSameVbBuf
                    + " vbSameOff=" + Dx12RenderPassBackend.gProfSameVbOff
                    + " ibSame=" + Dx12RenderPassBackend.gProfSameIbBuf
                    + " ibSameType=" + Dx12RenderPassBackend.gProfSameIdxType
                    + " | batches=" + Dx12RenderPassBackend.gProfBatches
                    + " sharedDesc=" + Dx12RenderPassBackend.gProfSharedDescBatches
                    + " singleBundle=" + Dx12RenderPassBackend.gProfSingleBundles
                    + " srvVaryFallback=" + Dx12RenderPassBackend.gProfSrvVaryFallbacks
                    + " slots=" + Dx12RenderPassBackend.gProfSlotsTotal
                    + " | P62b bc=" + Dx12RenderPassBackend.gProfBcTotal
                    + " cbvVary=" + Dx12RenderPassBackend.gProfCbvVary
                    + " cbvSame=" + Dx12RenderPassBackend.gProfCbvSame
                    + " srvVary=" + Dx12RenderPassBackend.gProfSrvVary
                    + " srvSame=" + Dx12RenderPassBackend.gProfSrvSame
                    + " vbSlotSame=" + Dx12RenderPassBackend.gProfVbSlotSame)
                System.err.flush()
                Dx12RenderPassBackend.gProfMultiBatches = 0L
                Dx12RenderPassBackend.gProfMultiDraws = 0L
                Dx12RenderPassBackend.gProfMultiPathNs = 0L
                Dx12RenderPassBackend.gProfDrawIndexedCalls = 0L
                Dx12RenderPassBackend.gProfDrawIndexedNs = 0L
                Dx12RenderPassBackend.gProfBuildNs = 0L
                Dx12RenderPassBackend.gProfPrepareNs = 0L
                Dx12RenderPassBackend.gProfWorkersNs = 0L
                Dx12RenderPassBackend.gProfExecuteNs = 0L
                Dx12RenderPassBackend.gProfFallbacks = 0L
                Dx12RenderPassBackend.gProfSharedBatchDraws = 0L
                Dx12RenderPassBackend.gProfSameVbBuf = 0L
                Dx12RenderPassBackend.gProfSameVbOff = 0L
                Dx12RenderPassBackend.gProfSameIbBuf = 0L
                Dx12RenderPassBackend.gProfSameIdxType = 0L
                Dx12RenderPassBackend.gProfBatches = 0L
                Dx12RenderPassBackend.gProfSharedDescBatches = 0L
                Dx12RenderPassBackend.gProfSingleBundles = 0L
                Dx12RenderPassBackend.gProfSrvVaryFallbacks = 0L
                Dx12RenderPassBackend.gProfSlotsTotal = 0L
                Dx12RenderPassBackend.gProfBcTotal = 0L
                Dx12RenderPassBackend.gProfCbvVary = 0L
                Dx12RenderPassBackend.gProfCbvSame = 0L
                Dx12RenderPassBackend.gProfSrvVary = 0L
                Dx12RenderPassBackend.gProfSrvSame = 0L
                Dx12RenderPassBackend.gProfVbSlotSame = 0L
                gProfIntervalNs = 0L; gProfRecordingReadyNs = 0L
                gProfWaitSubmitNs = 0L; gProfTotalNs = 0L
            }
        }
        this.inAsyncSubmit = false
    }
    fun close() {
        if (McdrAbi.LOG_VERBOSE) {
            System.err.println("[dx12-java] close: begin")
            System.err.flush()
        }
        // P61：close 前必须结算挂起的异步提交。两条路径都危险：
        //   1. 临时 encoder（device == null）：close() 末尾立即 dx12DestroyCommandEncoder，
        //      若渲染线程仍在 Close+Execute+Signal 该 ctx，销毁会与其竞争 → 悬空/崩溃。
        //   2. 共享 encoder：Dx12Device.close() 在 encoder.close() 之后同样立即
        //      dx12DestroyCommandEncoder。
        // 结算后 gAsyncRenderCtx 已置空，渲染线程不再触碰本 ctx，EndRenderPass/
        // EndCommandList/Destroy 才是安全的。
        settlePendingSubmit()
        if (this.currentRenderPass != null) {
            McdrAbi.dx12EndRenderPass(this.ctx)
            this.currentRenderPass = null
        }
        McdrAbi.dx12EndCommandList(this.ctx)
        // 共享 encoder（device != null）由 Dx12Device.close() 负责销毁 CommandContext，
        // 此处不调用 dx12DestroyCommandEncoder，避免 CubeMap.render() 等内部调用
        // close() 时意外销毁共享 ctx 导致后续渲染使用悬空指针。
        // 临时/一次性 encoder（device == null，如 createBuffer(data)）仍调用
        // dx12DestroyCommandEncoder，保证资源在 fence 等待后安全释放。
        if (this.device == null) {
            McdrAbi.dx12DestroyCommandEncoder(this.ctx)
            if (McdrAbi.LOG_VERBOSE) {
                System.err.println("[dx12-java] close: after destroyCommandEncoder")
                System.err.flush()
            }
        }
        this.transientMemory.close()
        if (McdrAbi.LOG_VERBOSE) {
            System.err.println("[dx12-java] close: after transientMemory.close")
            System.err.flush()
        }
    }

    companion object {
        /** P27：图集合成 pass 结束后 dump 图集纹理（定位按钮纹理错乱）。 */
        private const val MAX_ATLAS_DUMPS = 14
        private val gDumpedAtlas: MutableSet<Long> = HashSet()
        /**
         * P27: 按 ctx 记录待 submit 后 dump 的图集 pass。
         * 必须按 ctx 分组 + 只消费自己 ctx 的列表：atlas 上传（uploadInitialContents）在
         * 一次性 encoder 上连续创建多个 blit/interpolate pass，若用单一 pending 会被后续
         * pass 覆盖；若用全局 static 列表会被其它 encoder 的 submit() 抢消费（此时本 ctx
         * 命令尚未提交 GPU，读回全 0）。submit() 里 dx12Submit 提交 GPU 后执行 dump，
         * deviceWaitIdle 才能读到真实内容。
         */
        private val gPendingAtlasByCtx: MutableMap<Long, MutableList<Dx12RenderPassBackend>> =
            HashMap()

        /** P38 诊断：submit()（共享 encoder = 每帧）后读回 16×16 lightmap，验证内容与朝向。 */
        private const val MAX_LIGHTMAP_DUMPS = 5
        private const val LIGHTMAP_DUMP_INTERVAL = 120
        private var dx12DebugLightmapTick = 0
        private var dx12DebugLightmapDumps = 0

        // -----------------------------------------------------------------------
        // P3 帧时间插桩（仅 DX12_PROF=1 时启用，正常游玩零开销）
        // 目的：量化 submit() 内部各同步阶段（RECORDING_READY 握手 / 等提交完成）
        // 与帧间隔的耗时，用于判断多帧飞行 FrameManager 的实际可回收收益。
        // -----------------------------------------------------------------------
        private const val PROF_INTERVAL = 300
        private var gProfAsyncFrames = 0L
        private var gProfLastSubmitNs = 0L
        private var gProfIntervalNs = 0L
        private var gProfRecordingReadyNs = 0L
        private var gProfWaitSubmitNs = 0L
        private var gProfTotalNs = 0L
    }
}
