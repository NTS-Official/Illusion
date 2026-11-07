package io.github.dxegp.d3d12.backend

import com.mojang.renderpearl.api.buffers.GpuBuffer
import com.mojang.renderpearl.api.buffers.GpuBufferSlice
import com.mojang.renderpearl.api.buffers.TransientMemory
import com.mojang.renderpearl.api.commands.GpuFence
import com.mojang.renderpearl.api.commands.GpuQueryPool
import com.mojang.renderpearl.api.commands.RenderPassDescriptor
import com.mojang.renderpearl.api.textures.GpuTexture
import com.mojang.renderpearl.backend.api.CommandEncoderBackend
import com.mojang.renderpearl.backend.api.RenderPassBackend
import io.github.dxegp.d3d12.render.core.*
import io.github.dxegp.d3d12.extern.MCDR_ABI
import io.github.dxegp.d3d12.render.adapter.D3D12Device
import io.github.dxegp.d3d12.render.advanced.D3D12PostSharpen
import io.github.dxegp.d3d12.render.advanced.taa.D3D12TAA
import io.github.dxegp.d3d12.render.advanced.taa.D3D12Temporal
import io.github.dxegp.d3d12.render.advanced.taa.D3D12Velocity
import io.github.dxegp.d3d12.render.core.D3D12GpuBuffer
import io.github.dxegp.d3d12.render.core.D3D12GpuTexture
import io.github.dxegp.d3d12.render.core.D3D12GpuTextureView
import io.github.dxegp.d3d12.render.core.D3D12TransientMemory
import io.github.dxegp.experimental.raytracing.D3D12RtFrame
import io.github.dxegp.experimental.raytracing.D3D12RtGBuffer
import io.github.dxegp.experimental.raytracing.D3D12RtPipelinePlan
import io.github.dxegp.experimental.raytracing.D3D12RtStats
import io.github.dxegp.misc.IllusionLogger
import org.joml.Vector4fc
import java.lang.foreign.MemorySegment
import java.nio.ByteBuffer
import java.util.*
import kotlin.math.roundToLong

/**
 * D3D12 [CommandEncoderBackend]（镜像官方 VulkanCommandEncoder）。native 侧持有两个 allocator + 一个 fence：
 * submit() 执行已录制的列表、signal fence、等 value-2（vanilla 双缓冲提交）。一个设备只有一个共享实例。
 * 见 docs/misc/backend-and-device.md §3.3。
 */
class D3D12CommandEncoderBackend : CommandEncoderBackend {
    private val ctx: Long

    /** GUI 的防御性 `RSSetShadingRate` 复位要用同一条列表的上下文（只给同包调用方）。 */
    internal fun nativeCtx(): Long = ctx
    /** 帧中间追加后处理（光追合光）要建纹理，需要拿到编码器所属的设备。 */
    val device: D3D12Device?
    private val transientMemory: D3D12TransientMemory
    private val pendingCallbacks = mutableListOf<Runnable>()
    private var currentRenderPass: D3D12RenderPassBackend? = null
    /** 当前 submit() 走 ASYNC 路径：begin/allocator reset 由主线程在 [ensureListOpen] 里做，渲染线程只 Close+Execute+Present。 */
    private var inAsyncSubmit = false
    /**
     * 本帧提交已交给渲染线程、尚未结算。改由 [ensureListOpen] 结算 —— 中间隔着 MC 的 extract，
     * 渲染线程早已提交，主线程基本不阻塞（原先在 submit() 末尾阻塞 0.09-0.11ms/帧）。
     */
    private var pendingSubmit = false
    internal var recordingIdentity: Any = Any()
        private set

    constructor() : this(null)

    constructor(device: D3D12Device?) {
        this.device = device
        this.ctx = MCDR_ABI.D3D12CreateCommandEncoder()
        if (this.ctx == 0L) throw IllegalStateException("dx12CreateCommandEncoder returned a null handle")

        this.transientMemory = D3D12TransientMemory(this.ctx, Runnable { this.ensureListOpen() })
        // 构造时打开命令列表：beginCommandList 幂等（C++ 侧 listOpen=1 则跳过 allocator Reset），
        // 自检等一次性路径才能直接录制。
        MCDR_ABI.D3D12BeginCommandList(this.ctx)
    }

    /** Native CommandContext* handle (used by surface blit + render pass). */
    fun nativeHandle(): Long = this.ctx

    /**
     * 懒加载打开命令列表（幂等）。列表未打开时会 allocator reset —— 内部按 fenceValue-2 等 GPU 释放该 allocator。
     */
    /** 光追派发要在 pass 之外自己开列表：调用方在帧中间也可能拿到还没打开的编码器。 */
    fun ensureListOpen() {
        // 结算上一帧挂起的提交必须早于读 listOpen：渲染线程的 submitCommandList 会 Close 旧列表（1→0），
        // 先读可能读到中间态而跳过 begin，主线程随即写进一条正在被关闭/提交的列表（数据竞争）。
        settlePendingSubmit()
        if (!MCDR_ABI.D3D12IsListOpen(this.ctx)) {
            D3D12RtFrame.cancelPendingSubmission(recordingIdentity)
            MCDR_ABI.D3D12BeginCommandList(this.ctx)
            check(MCDR_ABI.D3D12IsListOpen(this.ctx)) { "command list: open failed" }
            recordingIdentity = Any()
        }
    }

    /**
     * 结算上一帧挂起的异步提交。必须在下一帧 dx12BeginCommandList **之前**：begin 会读写 ctx 的
     * fenceValue / currentListSlot / commandList / resourceState，而这些同时被渲染线程的
     * submitCommandList（Close + Execute + Signal）修改，未结算就 Reset 会产生数据竞争。
     */
    private fun settlePendingSubmit() {
        if (!this.pendingSubmit) return
        val prof = MCDR_ABI.PROF
        val t0 = if (prof) System.nanoTime() else 0L
        val status = MCDR_ABI.D3D12AsyncRenderWaitStatus(this.ctx, 10_000L)
        if (prof) gProfWaitSubmitNs += System.nanoTime() - t0
        check(status != 0) { "async submit: completion timed out" }
        this.pendingSubmit = false
        this.inAsyncSubmit = false
        if (status != 1) {
            D3D12RtFrame.cancelPendingSubmission(recordingIdentity)
            error("async submit: failed")
        }
        D3D12RtFrame.commitPendingSubmission(recordingIdentity)
    }

    private fun textureHandle(texture: GpuTexture): Long = (texture as D3D12GpuTexture).handle()

    private fun bufferHandle(buffer: GpuBuffer): Long = (buffer as D3D12GpuBuffer).handle()

    /** 纳秒 -> 毫秒，保留两位小数。 */
    private fun profMs(ns: Double): Double {
        return (ns * 1e-6 * 100.0).roundToLong() / 100.0
    }

    override fun transientMemory(): TransientMemory {
        return this.transientMemory
    }

    // ---- render pass ----

    override fun createRenderPass(descriptor: RenderPassDescriptor): RenderPassBackend {
        val colorAttachments: List<RenderPassDescriptor.Attachment<Optional<Vector4fc>>?> =
            descriptor.colorAttachments()
        val colorCount = colorAttachments.size
        // 用 var 而非 val：数组长度可能随附件组合变化。
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
            colorTextures[i] = (view.texture() as D3D12GpuTexture).handle()
            // 必须把 view 的 base mip 传给 native RTV：TextureAtlas 逐级上传到 mipViews[level]，RTV 恒绑 mip0
            // 会让 mip1+ 永不写入，远处大 LOD 采样到未初始化内容。
            if (view is D3D12GpuTextureView) {
                colorMips[i] = view.baseMip()
            }
            if (attachment.clearValue().isPresent) {
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
            depthTexture = (depthView.texture() as D3D12GpuTexture).handle()
            if (depthView is D3D12GpuTextureView) {
                depthMip = depthView.baseMip()
            }
            if (depthAttachment.clearValue().isPresent) {
                depthClearFlag = 1
                depthClearValue = depthAttachment.clearValue().asDouble
            }
        }

        val area = descriptor.renderArea
        var x = 0
        var y = 0
        var w = 0
        var h = 0
        x = area.x()
        y = area.y()
        w = area.width()
        h = area.height()

        this.ensureListOpen()
        // 速度附件不挂在这里：vanilla 把物体变换烘进顶点，MRT 拿不到逐物体运动；速度由帧末独立全屏 pass
        // 从主深度重投影（见 [D3D12Velocity.record] 与 [recordVelocityPass]）。
        MCDR_ABI.D3D12BeginRenderPass(this.ctx, colorTextures, colorMips,
            colorClearFlags, clearColors, depthTexture, depthMip,
            depthClearFlag, depthClearValue, x, y, w, h)
        val hasDepth = depthTexture != 0L
        // 识别「离屏图集合成 pass」（GuiItemAtlas / PictureInPicture）：color[0] 的 usage == 13
        // （COPY_DST|TEXTURE_BINDING|RENDER_ATTACHMENT）且带 depth attachment。这类 pass 的内容随后按 GL
        // 自底向上 UV 采样，需 flipY 变体管线修正。**绝不能**用 usage == 15（主窗口 MainTarget /
        // TextureAtlas 的特征）：会把主 pass 判成图集 pass，GUI 图标镜像/错位、scissor 裁剪区错乱。
        var flipY = false
        if (hasDepth && colorCount > 0) {
            val first = colorAttachments[0]
            if (first != null) {
                val firstView = first.textureView()
                if ((firstView.texture() as D3D12GpuTexture).usage() == 13) {
                    flipY = true
                }
            }
        }
        val pass = D3D12RenderPassBackend(
            this.device, this.ctx,
            area, w, h, hasDepth, if (colorCount > 0) colorTextures[0] else 0L,
            flipY,
            this.transientMemory,
        )
        this.currentRenderPass = pass
        return pass
    }

    /**
     * 把独立的全屏速度 pass 录进当前命令列表。调用点是 blit 之前：那时本帧渲染已完成、主深度仍有效。
     * 未启用 / 首帧无历史 / 任何失败都只是 no-op 或一次性自我关闭，绝不影响本帧渲染。
     */
    fun recordVelocityPass() {
        if (!D3D12Velocity.isActive()) {
            return
        }
        this.ensureListOpen()
        D3D12Velocity.record(this.ctx, this.device)
    }

    /** 后置锐化（TAA / DLAA / DLSS 共用）。`src == dst`、锐化关闭、旧 DLL 都返回 false，调用方应回退普通 blit。 */
    fun recordSharpen(src: Long, dst: Long): Boolean {
        if (!D3D12PostSharpen.isEnabled()) {
            return false
        }
        this.ensureListOpen()
        return D3D12PostSharpen.record(this.ctx, src, dst)
    }

    /**
     * 对主渲染目标**就地**做 SMAA（世界画完、GUI 未画）。往本帧已打开的那条命令列表追加命令，
     * 不新建不提交；是否真做由 native 判断（`aaMode == SMAA`），未启用只花一次 downcall。
     */
    fun recordSmaaWorldPass(texture: D3D12GpuTexture): Boolean {
        this.ensureListOpen()
        return MCDR_ABI.dx12ApplySmaaToTexture(this.ctx, texture.handle())
    }

    /**
     * 把本帧的 TAA resolve 交给 native（历史乒乓与两趟 pass 都在 native）。与 [recordSmaaWorldPass] 同挂点、
     * 互斥（TAA 与 SMAA 是同一个抗锯齿选项的两个取值）；只往已打开的列表追加，不新建不提交。
     */
    fun recordTaaPass(world: D3D12GpuTexture): Boolean {
        this.ensureListOpen()
        return D3D12TAA.record(this.ctx, this.device, world)
    }

    /**
     * 把一帧 DLSS 参数包交给 native（Create 与 Evaluate 是同一个入口，每帧只走这一条）。
     * 返回值原样透传：1 = 真的评价了，0 = native 跳过，-1 = 参数非法，[Int.MIN_VALUE] = 没有该导出。
     */
    fun recordDlssEvaluate(input: MemorySegment): Int {
        this.ensureListOpen()
        return MCDR_ABI.D3D12EvaluateDlss(this.ctx, input)
    }

    /**
     * 把**世界层深度**在当前列表上快照给 native，供稍后的 [recordDlssEvaluate] 使用。调用点必须在 vanilla
     * 的 `clearDepthTexture` **之前** —— 清空后拷贝到的是 0.0。返回值：1 就位 / 0 跳过 / -1 失败 / INT_MIN 无导出。
     */
    fun recordDlssDepthSnapshot(depth: D3D12GpuTexture): Int {
        this.ensureListOpen()
        return MCDR_ABI.D3D12SnapshotDlssDepth(this.ctx, depth.handle())
    }

    /**
     * 把 `src` 画到 `dst`（整屏三角形 + 线性采样，允许两边尺寸不同），目标留在 RENDER_TARGET 供随后的 pass
     * 直接用。renderScale != 100 时主渲染目标是缩放尺寸，1:1 copy 搬不过去。返回 false = 没搬成，调用方回退。
     */
    fun recordTextureBlit(src: D3D12GpuTexture, dst: D3D12GpuTexture): Boolean {
        this.ensureListOpen()
        return MCDR_ABI.dx12BlitTextureToTexture(this.ctx, src.handle(), dst.handle())
    }
    override fun submitRenderPass() {
        val pass = this.currentRenderPass
        try {
            MCDR_ABI.D3D12EndRenderPass(this.ctx)
            this.currentRenderPass = null
            if (pass != null) this.recordGBufferPass(pass)
        } finally {
            pass?.releaseGBufferCapture()
        }
    }

    private var gbufferReplayFrames = 0

    /** 重放规模心跳：上次报时的时刻。 */
    private var gbufferReplayNanos = 0L

    private fun recordGBufferPass(pass: D3D12RenderPassBackend) {
        if (!pass.hasGBufferBatches()) {
            return
        }
        D3D12RtFrame.invalidateOutput()
        // 光追是开发档功能：任何异常都不许冒泡到帧图，游戏必须继续可玩。
        var replay: D3D12RtGBuffer.Targets? = null
        var replayed = false
        var ended = false
        val plan = D3D12RtPipelinePlan.configured(pass.areaWidth(), pass.areaHeight())
        if (!plan.enabled) return
        val camera = D3D12RtFrame.currentCamera() ?: return
        val projection = D3D12Temporal.rtReplayProjection(camera.frameNanos,
            plan.gbufferWidth, plan.gbufferHeight) ?: return
        try {
            D3D12RtStats.beginFrame(this, pass.areaWidth(), pass.areaHeight())
            this.ensureListOpen()
            replay = D3D12RtGBuffer.begin(this.device, this.ctx, camera.frameNanos,
                plan.gbufferWidth, plan.gbufferHeight) ?: return
            if (!pass.replayGBuffer(replay.revision, camera.transform.offset, projection)) return
            if (!D3D12Temporal.commitRtReplay(camera.frameNanos)) return
            replayed = true
            // 重放的批次/meshlet 规模要和主 pass 的 meshlet dispatch 对账：首帧一条，
            // 之后每 5 秒一条（按帧报会在高帧率下刷屏，meshlet 数在加载期又每帧都变）。
            val batches = pass.gbufferBatchCount()
            val meshlets = pass.gbufferMeshletCount()
            val now = System.nanoTime()
            val replayFrames = this.gbufferReplayFrames++
            if (replayFrames == 0 || now - this.gbufferReplayNanos >= 5_000_000_000L) {
                this.gbufferReplayNanos = now
                IllusionLogger.debugMessage("rt gbuffer: replay batches=$batches meshlets=$meshlets")
            }
            D3D12RtStats.mark(this, D3D12RtStats.SLOT_GBUFFER)
        } catch (t: Throwable) {
            D3D12RtFrame.reportFailure(t)
            return
        } finally {
            replay?.let { ended = D3D12RtGBuffer.end(this.ctx, it, replayed) }
        }
        if (!ended) return
        try {
            D3D12RtFrame.prepareFrame(this.device, this, camera, plan)
        } catch (t: Throwable) {
            D3D12RtFrame.reportFailure(t)
        }
    }

    // ---- clears ----

    override fun clearColorTexture(colorTexture: GpuTexture, clearColor: Vector4fc) {
        this.ensureListOpen()
        MCDR_ABI.D3D12ClearColorTexture(this.ctx, textureHandle(colorTexture),
            clearColor.x(), clearColor.y(), clearColor.z(), clearColor.w())
    }

    override fun clearColorAndDepthTextures(colorTexture: GpuTexture, clearColor: Vector4fc,
        depthTexture: GpuTexture, clearDepth: Double) {
        this.ensureListOpen()
        MCDR_ABI.D3D12ClearColorTexture(this.ctx, textureHandle(colorTexture),
            clearColor.x(), clearColor.y(), clearColor.z(), clearColor.w())
        MCDR_ABI.D3D12ClearDepthTexture(this.ctx, textureHandle(depthTexture), clearDepth)
    }

    override fun clearColorAndDepthTextures(colorTexture: GpuTexture, clearColor: Vector4fc,
        depthTexture: GpuTexture, clearDepth: Double, regionX: Int, regionY: Int,
        regionWidth: Int, regionHeight: Int, mipLevel: Int) {
        this.ensureListOpen()
        // 必须只清该槽位矩形区域：忽略 region 整图清空，会让滚动/翻页触发重绘时把其它已烘好的槽位图标
        // 一起抹掉（图集区域仍标记有效、不再重绘）。
        MCDR_ABI.D3D12ClearColorTextureRegion(this.ctx, textureHandle(colorTexture),
            clearColor.x(), clearColor.y(), clearColor.z(), clearColor.w(),
            regionX, regionY, regionWidth, regionHeight)
        MCDR_ABI.D3D12ClearDepthTextureRegion(this.ctx, textureHandle(depthTexture),
            clearDepth, regionX, regionY, regionWidth, regionHeight)
    }

    override fun clearDepthTexture(depthTexture: GpuTexture, clearDepth: Double) {
        this.ensureListOpen()
        MCDR_ABI.D3D12ClearDepthTexture(this.ctx, textureHandle(depthTexture), clearDepth)
    }

    /** 清模板纹理（26.3 已从接口移除，保留实现）。native 返回 1=成功 / 0=失败，与 depth/color 清理一样不检查。 */
    override fun clearStencilTexture(texture: GpuTexture, value: Int) {
        this.ensureListOpen()
        MCDR_ABI.D3D12ClearStencilTexture(this.ctx, textureHandle(texture), value)
    }

    // ---- copies ----

    override fun writeToBuffer(destination: GpuBufferSlice, data: ByteBuffer) {
        if (D3D12RtFrame.isActive() &&
            (destination.buffer() as? D3D12GpuBuffer)?.label() == D3D12Temporal.LEVEL_PROJECTION_LABEL) {
            D3D12RtFrame.captureProjection(data)
        }
        // 时间性渲染的写出拦截点：关卡投影矩阵（label "Camera projection matrix UBO level"，64B）注入亚像素
        // jitter，相机 UBO（"Global Settings UBO"，56B）捕获 CameraBlockPos/CameraOffset。两者 usage 136 不可映射、
        // 每帧都被重写，所以这是唯一能拿到逐帧新鲜字节的地方。未开启/不是这两个缓冲时返回 null。
        val jittered = D3D12Temporal.interceptWrite(destination, data)
        val payload = jittered ?: data
        this.ensureListOpen()
        // 与官方一致：直接上传，不做 NaN 检测/替换。数据有问题应在源头修；暴力替换会覆写整块顶点数据导致黑屏。
        val staging = this.transientMemory.uploadStaging(payload, 1L, GpuBuffer.USAGE_COPY_SRC)
        MCDR_ABI.D3D12CopyBuffer(this.ctx, bufferHandle(staging.buffer()), staging.offset(),
            bufferHandle(destination.buffer()), destination.offset(),
            payload.remaining().toLong())
    }

    override fun copyToBuffer(source: GpuBufferSlice, target: GpuBufferSlice) {
        this.ensureListOpen()
        MCDR_ABI.D3D12CopyBuffer(this.ctx, bufferHandle(source.buffer()), source.offset(),
            bufferHandle(target.buffer()), target.offset(),
            source.length())
    }

    override fun writeToTexture(destination: GpuTexture, source: ByteBuffer, mipLevel: Int,
        depthOrLayer: Int, destX: Int, destY: Int, width: Int, height: Int) {
        this.ensureListOpen()
        val staging = this.transientMemory.uploadStaging(source, 1L, GpuBuffer.USAGE_COPY_SRC)
        MCDR_ABI.D3D12WriteToTexture(this.ctx, bufferHandle(staging.buffer()), staging.offset(),
            width, height, textureHandle(destination), mipLevel, depthOrLayer, destX, destY)
    }

    override fun copyBufferToTexture(source: GpuBufferSlice, sourceX: Int, sourceY: Int,
        sourceWidth: Int, sourceHeight: Int, destination: GpuTexture, destinationX: Int,
        destinationY: Int, copyWidth: Int, copyHeight: Int, mipLevel: Int, arrayLayer: Int) {
        // 必须显式 ensureListOpen：uploadStaging 现在只做 CPU 侧块内映射，不再录制任何 GPU 命令。缺了它，
        // 帧外资源重载（SpriteContents mip 上传 / 图集动画）会抛 "no open command list" → 白屏 + 花屏闪烁。
        this.ensureListOpen()
        val texelSize = destination.format.blockSize()
        val skipTexels = sourceX.toLong() + sourceY.toLong() * sourceWidth
        val skipBytes = skipTexels * texelSize.toLong()
        MCDR_ABI.D3D12CopyBufferToTexture(this.ctx, bufferHandle(source.buffer()),
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
        this.ensureListOpen()
        MCDR_ABI.D3D12CopyTextureToBuffer(this.ctx, textureHandle(source), mipLevel, 0,
            x, y, width, height, bufferHandle(destination), offset)
        synchronized(this.pendingCallbacks) { this.pendingCallbacks.add(callback) }
        if (this.device == null) {
            this.submit()
        }
    }

    override fun copyTextureToTexture(source: GpuTexture, destination: GpuTexture, mipLevel: Int,
        destX: Int, destY: Int, sourceX: Int, sourceY: Int, width: Int, height: Int) {
        this.ensureListOpen()
        MCDR_ABI.D3D12CopyTextureToTexture(this.ctx, textureHandle(source),
            textureHandle(destination), mipLevel, 0, sourceX, sourceY, destX, destY, width, height)
    }

    // ---- fences & timestamps ----

    override fun createFence(): GpuFence {
        // token = 本 encoder 当前序号 + 1，只有本 encoder 的下一次 submit 推进它。**不能**退回设备级
        // queueFenceValue：一次性 encoder（createBuffer(data) 的上传）提交时也会推进全局序列，而 vanilla 在
        // 帧中间取 token，会让 StagedVertexBuffer / MappableRingBuffer 的缓冲在帧提交前就被提前回收。
        val fenceValue = MCDR_ABI.D3D12GetFenceValue(this.ctx)
        val target = fenceValue + 1
        return object : GpuFence {
            private var completed = false

            override fun awaitCompletion(timeoutMs: Long): Boolean {
                if (!this.completed) {
                    val timeoutNs = if (timeoutMs > Long.MAX_VALUE / 1_000_000L) Long.MAX_VALUE else timeoutMs * 1_000_000L
                    this.completed = MCDR_ABI.D3D12WaitForFence(this@D3D12CommandEncoderBackend.ctx, target, timeoutNs)
                }
                return this.completed
            }

            override fun close() { this.completed = true }
        }
    }

    override fun writeTimestamp(pool: GpuQueryPool, index: Int) {
        this.ensureListOpen()
        MCDR_ABI.D3D12WriteTimestamp(this.ctx, (pool as D3D12GpuQueryPool).nativeHandle(), index)
    }

    // ---- submit lifecycle ----

    private fun completeReadbacks() {
        if (synchronized(pendingCallbacks) { pendingCallbacks.isEmpty() }) return
        settlePendingSubmit()
        val fence = MCDR_ABI.D3D12GetFenceValue(ctx)
        check(fence > 0L && MCDR_ABI.D3D12WaitForFence(ctx, fence, 10_000_000_000L)) {
            "readback: GPU completion timed out"
        }
        val callbacks = synchronized(pendingCallbacks) {
            pendingCallbacks.toList().also { pendingCallbacks.clear() }
        }
        for (callback in callbacks) {
            try {
                callback.run()
            } catch (failure: Throwable) {
                IllusionLogger.warnMessage("readback: callback failed (${failure.javaClass.simpleName})")
            }
        }
    }

    override fun submit() {
        // ASYNC 路径：主线程录制完 → 通知渲染线程 → 等 RECORDING_READY → 通知命令就绪 → 渲染线程
        // Close+Execute+Present；结算推迟到下一帧（[settlePendingSubmit]）。
        // allocator Reset + begin 必须只由**主线程**做（[ensureListOpen]）：渲染线程若 Reset 会丢掉整帧
        // 已录制的命令，back buffer 永远不被写入 → Present 只能显示未初始化内容（花屏根因）。
        ensureListOpen()

        // 只有帧 encoder（device != null，即 D3D12Device.createCommandEncoder() 返回的共享 encoder）允许
        // 进入异步帧管线。一次性 encoder 走进来会劫持整帧：渲染线程替它 Close+Execute+Signal、把
        // gAsyncOwnsPresent 置 true 并 presentSurface（可能提前 Present → 闪旧画面/双 Present），还会把 frame
        // encoder 的 fence token 一起推走。它们一律走下面的同步路径。
        val asyncStatus = if (this.device != null) MCDR_ABI.D3D12AsyncRenderBeginStatus(this.ctx) else 0
        check(asyncStatus in 0..1) { "async submit: start failed" }
        if (asyncStatus == 0) {
            // 无 active surface（初始化阶段 / 窗口未创建）：走同步路径，提交立即完成。
            try {
                check(MCDR_ABI.D3D12Submit(this.ctx) > 0L) { "command submit: failed" }
            } catch (failure: Throwable) {
                D3D12RtFrame.cancelPendingSubmission(recordingIdentity)
                throw failure
            }
            D3D12RtFrame.commitPendingSubmission(recordingIdentity)
            this.transientMemory.rotate()
            completeReadbacks()
            return
        }

        // 异步路径：命令已录完，渲染线程只 Close+Execute+Present；这里不能再调 begin（列表已打开）。
        this.inAsyncSubmit = true
        this.pendingSubmit = true

        // 记录本帧起点与相对上一帧的间隔（仅 PROF=1 生效）。
        val prof = MCDR_ABI.PROF
        val profStart = System.nanoTime()
        if (prof) {
            // 累加而非覆盖：否则只统计到最后一个间隔。
            if (gProfLastSubmitNs != 0L) gProfIntervalNs += profStart - gProfLastSubmitNs
            gProfLastSubmitNs = profStart
        }

        // 等渲染线程到 RECORDING_READY：必须用 native 阻塞等待（WaitForSingleObject），
        // sleep(1) 轮询每帧在关键路径上多引入约 1ms 调度开销。超时保护不变。
        val profT0 = if (prof) System.nanoTime() else 0L
        val recordingReady = MCDR_ABI.D3D12AsyncRenderWaitRecordingReady(this.ctx, 5_000L)
        if (prof) gProfRecordingReadyNs += System.nanoTime() - profT0
        if (!recordingReady) {
            error("async submit: recording ready timed out")
        }

        // 通知渲染线程：所有命令已入队。
        check(MCDR_ABI.D3D12AsyncSendCommandsReady(this.ctx)) { "async submit: handoff failed" }

        // 本帧**不再**阻塞等待提交完成：渲染线程此刻 Close+Execute+Signal+Present，主线程直接返回，
        // 结算推迟到下一帧开头（仍早于下一次 beginCommandList，见 [settlePendingSubmit]）。
        // post-submit 清理。
        this.transientMemory.rotate()
        completeReadbacks()
        // 每 PROF_INTERVAL 帧输出一次各阶段耗时均值（ms）。
        if (prof) {
            ++gProfAsyncFrames
            gProfTotalNs += System.nanoTime() - profStart
            if (gProfAsyncFrames % PROF_INTERVAL == 0L) {
                val inv = 1.0 / PROF_INTERVAL
                IllusionLogger.debugMessage("prof n=${PROF_INTERVAL}"
                    + " frameInterval=${profMs(gProfIntervalNs * inv)}"
                    + "ms recordingReady=${profMs(gProfRecordingReadyNs * inv)}"
                    + "ms waitSubmit=${profMs(gProfWaitSubmitNs * inv)}"
                    + "ms submitTotal=${profMs(gProfTotalNs * inv)}ms")
                // 判断帧时间花在 mod 绘制路径还是 MC 自身逻辑。
                IllusionLogger.debugMessage("core: batches/frame=${D3D12RenderPassBackend.gProfMultiBatches * inv}"
                    + " draws/frame=${D3D12RenderPassBackend.gProfMultiDraws * inv}"
                    + " batchPath=${profMs(D3D12RenderPassBackend.gProfMultiPathNs * inv)}ms"
                    + " drawIndexedCalls/frame=${D3D12RenderPassBackend.gProfDrawIndexedCalls * inv}"
                    + " drawIndexed=${profMs(D3D12RenderPassBackend.gProfDrawIndexedNs * inv)}ms")
                // 并行批量路径四段拆分（主线程串行 vs worker 并行）。
                IllusionLogger.debugMessage("batch: build=${profMs(D3D12RenderPassBackend.gProfBuildNs * inv)}"
                    + "ms prepare=${profMs(D3D12RenderPassBackend.gProfPrepareNs * inv)}"
                    + "ms workers=${profMs(D3D12RenderPassBackend.gProfWorkersNs * inv)}"
                    + "ms execute=${profMs(D3D12RenderPassBackend.gProfExecuteNs * inv)}"
                    + "ms fallbacks=${D3D12RenderPassBackend.gProfFallbacks * inv}"
                    + " | batchDraws=${D3D12RenderPassBackend.gProfSharedBatchDraws}"
                    + " vbSame=${D3D12RenderPassBackend.gProfSameVbBuf}"
                    + " vbSameOff=${D3D12RenderPassBackend.gProfSameVbOff}"
                    + " ibSame=${D3D12RenderPassBackend.gProfSameIbBuf}"
                    + " ibSameType=${D3D12RenderPassBackend.gProfSameIdxType}"
                    + " | batches=${D3D12RenderPassBackend.gProfBatches}"
                    + " sharedDesc=${D3D12RenderPassBackend.gProfSharedDescBatches}"
                    + " singleBundle=${D3D12RenderPassBackend.gProfSingleBundles}"
                    + " srvVaryFallback=${D3D12RenderPassBackend.gProfSrvVaryFallbacks}"
                    + " slots=${D3D12RenderPassBackend.gProfSlotsTotal}"
                    + " | bc=${D3D12RenderPassBackend.gProfBcTotal}"
                    + " cbvVary=${D3D12RenderPassBackend.gProfCbvVary}"
                    + " cbvSame=${D3D12RenderPassBackend.gProfCbvSame}"
                    + " srvVary=${D3D12RenderPassBackend.gProfSrvVary}"
                    + " srvSame=${D3D12RenderPassBackend.gProfSrvSame}"
                    + " vbSlotSame=${D3D12RenderPassBackend.gProfVbSlotSame}")
                System.err.flush()
                D3D12RenderPassBackend.gProfMultiBatches = 0L
                D3D12RenderPassBackend.gProfMultiDraws = 0L
                D3D12RenderPassBackend.gProfMultiPathNs = 0L
                D3D12RenderPassBackend.gProfDrawIndexedCalls = 0L
                D3D12RenderPassBackend.gProfDrawIndexedNs = 0L
                D3D12RenderPassBackend.gProfBuildNs = 0L
                D3D12RenderPassBackend.gProfPrepareNs = 0L
                D3D12RenderPassBackend.gProfWorkersNs = 0L
                D3D12RenderPassBackend.gProfExecuteNs = 0L
                D3D12RenderPassBackend.gProfFallbacks = 0L
                D3D12RenderPassBackend.gProfSharedBatchDraws = 0L
                D3D12RenderPassBackend.gProfSameVbBuf = 0L
                D3D12RenderPassBackend.gProfSameVbOff = 0L
                D3D12RenderPassBackend.gProfSameIbBuf = 0L
                D3D12RenderPassBackend.gProfSameIdxType = 0L
                D3D12RenderPassBackend.gProfBatches = 0L
                D3D12RenderPassBackend.gProfSharedDescBatches = 0L
                D3D12RenderPassBackend.gProfSingleBundles = 0L
                D3D12RenderPassBackend.gProfSrvVaryFallbacks = 0L
                D3D12RenderPassBackend.gProfSlotsTotal = 0L
                D3D12RenderPassBackend.gProfBcTotal = 0L
                D3D12RenderPassBackend.gProfCbvVary = 0L
                D3D12RenderPassBackend.gProfCbvSame = 0L
                D3D12RenderPassBackend.gProfSrvVary = 0L
                D3D12RenderPassBackend.gProfSrvSame = 0L
                D3D12RenderPassBackend.gProfVbSlotSame = 0L
                gProfIntervalNs = 0L; gProfRecordingReadyNs = 0L
                gProfWaitSubmitNs = 0L; gProfTotalNs = 0L
            }
        }
        this.inAsyncSubmit = false
    }
    fun close() {
        // close 前必须结算挂起的异步提交：两条路径都会在 close 之后立刻 dx12DestroyCommandEncoder，
        // 若渲染线程仍在 Close+Execute+Signal 该 ctx，销毁会与之竞争 → 悬空/崩溃。结算后渲染线程不再触碰本 ctx。
        settlePendingSubmit()
        if (this.currentRenderPass != null) {
            MCDR_ABI.D3D12EndRenderPass(this.ctx)
            this.currentRenderPass = null
        }
        MCDR_ABI.D3D12EndCommandList(this.ctx)
        // 共享 encoder（device != null）由 D3D12Device.close() 销毁 CommandContext，这里**不能**调
        // dx12DestroyCommandEncoder（CubeMap.render() 等内部 close 会顺手销毁共享 ctx）；一次性 encoder 仍要销毁。
        if (this.device == null) {
            MCDR_ABI.D3D12DestroyCommandEncoder(this.ctx)
        }
        this.transientMemory.close()
    }

    companion object {

        // 帧时间插桩（PROF=1 才启用，正常游玩零开销）：量化 submit() 内部各同步阶段耗时。
        private const val PROF_INTERVAL = 300
        private var gProfAsyncFrames = 0L
        private var gProfLastSubmitNs = 0L
        private var gProfIntervalNs = 0L
        private var gProfRecordingReadyNs = 0L
        private var gProfWaitSubmitNs = 0L
        private var gProfTotalNs = 0L
    }
}



