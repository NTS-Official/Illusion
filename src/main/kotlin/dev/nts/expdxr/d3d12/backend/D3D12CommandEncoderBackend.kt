package dev.nts.expdxr.d3d12.backend

import com.mojang.blaze3d.buffers.GpuBuffer
import com.mojang.blaze3d.buffers.GpuBufferSlice
import com.mojang.blaze3d.buffers.GpuFence
import com.mojang.blaze3d.systems.*
import com.mojang.blaze3d.textures.GpuTexture
import dev.nts.expdxr.misc.IllusionLogger
import dev.nts.expdxr.d3d12.extern.MCDR_ABI
import dev.nts.expdxr.d3d12.render.adapter.D3D12Device
import dev.nts.expdxr.d3d12.render.advanced.taa.D3D12TAA
import dev.nts.expdxr.d3d12.render.advanced.taa.D3D12Temporal
import dev.nts.expdxr.d3d12.render.advanced.D3D12PostSharpen
import dev.nts.expdxr.d3d12.render.advanced.taa.D3D12Velocity
import dev.nts.expdxr.d3d12.render.core.*
import org.joml.Vector4fc
import java.lang.foreign.MemorySegment
import java.nio.ByteBuffer
import java.util.*
import kotlin.math.roundToLong

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
class D3D12CommandEncoderBackend : CommandEncoderBackend {
    private val ctx: Long
    private val device: D3D12Device?
    private val transientMemory: D3D12TransientMemory
    private val pendingCallbacks: MutableList<Runnable> = ArrayList()
    private var currentRenderPass: D3D12RenderPassBackend? = null
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

    constructor(device: D3D12Device?) {
        this.device = device
        this.ctx = MCDR_ABI.D3D12CreateCommandEncoder()
        if (this.ctx == 0L) throw IllegalStateException("dx12CreateCommandEncoder returned a null handle")

        this.transientMemory = D3D12TransientMemory(this.ctx, Runnable { this.ensureListOpen() })
        // P33 fix：在构造函数中打开命令列表。beginCommandList 现在是幂等的
        // （C++ 侧若 listOpen=1 则跳过 Reset），因此 submit() 可安全再次调用而
        // 不会触发 allocator Reset E_FAIL。这确保了 self-test 等一次性路径的
        // recording 方法（writeToBuffer 等）能在 listOpen=1 的状态下执行。
        MCDR_ABI.D3D12BeginCommandList(this.ctx)
    }

    /** Native CommandContext* handle (used by surface blit + render pass). */
    fun nativeHandle(): Long = this.ctx

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
        // P33 fix：ASYNC 路径下 begin/Reset 由主线程负责（渲染线程不再触碰
        // allocator/list），因此这里直接打开即可，不存在与渲染线程竞争的问题。
        if (!MCDR_ABI.D3D12IsListOpen(this.ctx)) MCDR_ABI.D3D12BeginCommandList(this.ctx)
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
        if (!this.pendingSubmit) return
        this.pendingSubmit = false
        val prof = MCDR_ABI.PROF
        val t0 = if (prof) System.nanoTime() else 0L
        val completed = MCDR_ABI.D3D12AsyncRenderWaitComplete(this.ctx, 10_000L)
        if (prof) gProfWaitSubmitNs += System.nanoTime() - t0
        if (!completed) {
            IllusionLogger.warnMessage("[backend] [P33] asyncRenderWaitComplete timeout! ctx=0x${this.ctx.toULong().toString(16)}")
            System.err.flush()
        }
    }

    private fun textureHandle(texture: GpuTexture): Long = (texture as D3D12GpuTexture).handle()

    private fun bufferHandle(buffer: GpuBuffer): Long = (buffer as D3D12GpuBuffer).handle()

    /** P3 插桩：纳秒 -> 毫秒，保留两位小数。 */
    private fun profMs(ns: Double): Double {
        return (ns * 1e-6 * 100.0).roundToLong() / 100.0
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
        // P76a：挂速度附件时这四组数组会整体多一项（index = colorCount），因此用 var。
        var colorTextures = LongArray(colorCount)
        var colorMips = IntArray(colorCount)
        var colorClearFlags = ByteArray(colorCount)
        var clearColors = FloatArray(colorCount * 4)
        for (i in 0 until colorCount) {
            val attachment = colorAttachments.get(i)
            if (attachment == null) {
                colorTextures[i] = 0L  // withUnusedColorAttachment
                continue
            }
            val view = attachment.textureView()
            colorTextures[i] = (view.texture() as D3D12GpuTexture).handle()
            // P3b：把 view 的 base mip 传给 native RTV（TextureAtlas 对 mipViews[level]
            // 逐级上传；此前 RTV 恒绑 mip0 → 图集 mip1+ 从未写入，远处大 LOD 采样
            // 到未初始化内容）。
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
        // P76a 增量 2（用户 m04002 裁决）：速度（运动矢量）不再作为**地地形 pass 的第二个 RTV**
        // 挂在这里 —— vanilla 把物体变换烘进顶点，MRT 路线拿不到逐物体运动。速度改成在帧末
        // （blit 之前）由一条**独立的全屏 pass** 从主深度重投影出来，见
        // [D3D12Velocity.record] 与 [recordVelocityPass]。
        // P141 续四：mesh 包围球 pre-pass 必须在这里跑 —— compute dispatch 不允许出现在
        // render pass 内部（D3D12 硬限制）。此前这行在 D3D12BeginRenderPass **之后**，
        // 于是 Dispatch 落进已开的 pass，设备照样被移除 0x887A0005，白查了一天。
        // 排队的是上一帧记录的段表；包围球是对象空间的，延迟一帧不影响正确性。
        D3D12MeshBoundsService.flush(this.ctx)
        MCDR_ABI.D3D12BeginRenderPass(this.ctx, colorTextures, colorMips,
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
            area, w, h, hasDepth, if (colorCount > 0) colorTextures[0] else 0L, flipY,
            this.transientMemory,
        )
        this.currentRenderPass = pass
        return pass
    }

    /**
     * P76a 增量 2：把**独立的全屏速度 pass** 录进当前命令列表（用户 m04002 裁决）。
     *
     * 调用时机：`D3D12GpuSurface.blitFromTexture` 在 blit 之前 —— 那一刻本帧所有
     * 渲染已完成、主深度仍然有效，且距离 present 只有一次 blit，是插入"读深度、写速度"
     * 最自然的位置。命令列表可能已被上一帧的提交关闭，所以先 [ensureListOpen]。
     *
     * 未启用（`dxrmc.temporal.velocity` 默认 false）/ 首帧无历史 / 任何失败都只是 no-op
     * 或一次性自我关闭，绝不影响本帧渲染（见 [D3D12Velocity]）。
     */
    fun recordVelocityPass() {
        if (!D3D12Velocity.isActive()) {
            return
        }
        this.ensureListOpen()
        D3D12Velocity.record(this.ctx, this.device)
    }

    /**
     * P121：后置锐化（TAA / DLAA / DLSS 共用）。`src == dst`、锐化关闭、旧 DLL 都返回 false，
     * 调用方应回退到普通 blit。
     */
    fun recordSharpen(src: Long, dst: Long): Boolean {
        if (!D3D12PostSharpen.isEnabled()) {
            return false
        }
        this.ensureListOpen()
        return D3D12PostSharpen.record(this.ctx, src, dst)
    }

    /**
     * P104b：对主渲染目标**就地**应用 SMAA（世界画完、GUI 还没画的那一刻）。
     *
     * 调用时机与 [recordVelocityPass] 同一性质：往**本帧已经打开的那条**命令列表里追加
     * 命令，不新建也不提交（命令列表若已被上一帧的提交关闭，先 [ensureListOpen]）。
     *
     * 是否真的做抗锯齿由 native 判断（`aaMode == SMAA` 才动手），所以这里不需要再查
     * 一遍选项 —— 未启用时 native 立刻返回，代价只是一次 downcall。
     */
    fun recordSmaaWorldPass(texture: D3D12GpuTexture): Boolean {
        this.ensureListOpen()
        return MCDR_ABI.dx12ApplySmaaToTexture(this.ctx, texture.handle())
    }

    /**
     * P77/P152：把本帧的 **TAA resolve** 交给 native（历史乒乓与两趟 pass 都在
     * `MCDR\src\core\d3d12_device.cpp` 的 `applyTaaToTexture` 里）。调用点是世界画完、GUI 还没画的
     * 那一刻。
     *
     * 与 [recordSmaaWorldPass] 同一性质、同一挂点，两者互斥（TAA 与 SMAA 是同一个抗锯齿选项
     * 的两个取值）：往本帧已经打开的那条命令列表里追加命令，不新建也不提交。是否真的做由
     * [D3D12TAA.isSelected] 决定，未选中时立刻返回，代价只是一次 volatile 读。
     */
    fun recordTaaPass(world: D3D12GpuTexture): Boolean {
        this.ensureListOpen()
        return D3D12TAA.record(this.ctx, this.device, world)
    }

    /**
     * P108：把一帧 DLSS 参数包交给 native（`dev.nts.expdxr.dxrmc.dlss.D3D12Dlss` 填的
     * `MCDRLayout.DLSS_INPUT`）。native 侧 Create 与 Evaluate 是同一个入口
     * （`MCDR\src\c_abi\abi_command.cpp:206-221` 两个导出都调 `applyDlssToTexture`），所以每帧只走
     * 这一条。
     *
     * 与 [recordSmaaWorldPass] 同一性质：往本帧已经打开的那条命令列表里追加 NGX 的命令，不新建也不提交。
     * 返回值原样透传：1 = 这一帧真的评价了，0 = native 跳过，-1 = 参数非法，[Int.MIN_VALUE] = 没有该导出。
     */
    fun recordDlssEvaluate(input: MemorySegment): Int {
        this.ensureListOpen()
        return MCDR_ABI.D3D12EvaluateDlss(this.ctx, input)
    }

    /**
     * P108e：把**世界层深度**在当前命令列表上快照给 native（`mcdr_dx12SnapshotDlssDepth`），
     * 供稍后的 [recordDlssEvaluate] 优先使用。
     *
     * 与 [recordVelocityPass] 同一性质、同一时机：往**本帧已经打开的那条**命令列表里追加命令
     * （列表若已被上一帧的提交关闭，先 [ensureListOpen]），不新建也不提交。调用点必须在 vanilla
     * 的 `clearDepthTexture` 之前 —— 深度快照必须是**本帧世界层**的深度，清空之后拷贝到的是 0.0。
     *
     * 返回值原样透传：1 = 快照就位，0 = native 跳过（参数不可用），-1 = 创建/拷贝失败，
     * [Int.MIN_VALUE] = 本地 DLL 没有该导出（旧 DLL）。
     */
    fun recordDlssDepthSnapshot(depth: D3D12GpuTexture): Int {
        this.ensureListOpen()
        return MCDR_ABI.D3D12SnapshotDlssDepth(this.ctx, depth.handle())
    }

    /**
     * P106：把 `src` 画到 `dst`（整屏三角形 + 线性采样，允许两边尺寸不同），目标留在
     * RENDER_TARGET 供随后的 render pass 直接用。GUI 的独立窗口尺寸目标靠它接管世界层 ——
     * renderScale != 100 时主渲染目标是缩放尺寸，1:1 的 copy 搬不过去。
     *
     * 返回 false = 没搬成（旧 DLL 缺导出 / native 拒绝）：调用方应回退到原路径。
     */
    fun recordTextureBlit(src: D3D12GpuTexture, dst: D3D12GpuTexture): Boolean {
        this.ensureListOpen()
        return MCDR_ABI.dx12BlitTextureToTexture(this.ctx, src.handle(), dst.handle())
    }
    override fun submitRenderPass() {
        MCDR_ABI.D3D12EndRenderPass(this.ctx)
        this.currentRenderPass = null
    }

    // -----------------------------------------------------------------------
    // Clears
    // -----------------------------------------------------------------------

    override fun clearColorTexture(colorTexture: GpuTexture, clearColor: Vector4fc) {
        // P33：确保命令列表已打开
        this.ensureListOpen()
        MCDR_ABI.D3D12ClearColorTexture(this.ctx, textureHandle(colorTexture),
            clearColor.x(), clearColor.y(), clearColor.z(), clearColor.w())
    }

    override fun clearColorAndDepthTextures(colorTexture: GpuTexture, clearColor: Vector4fc,
        depthTexture: GpuTexture, clearDepth: Double) {
        // P33：确保命令列表已打开
        this.ensureListOpen()
        MCDR_ABI.D3D12ClearColorTexture(this.ctx, textureHandle(colorTexture),
            clearColor.x(), clearColor.y(), clearColor.z(), clearColor.w())
        MCDR_ABI.D3D12ClearDepthTexture(this.ctx, textureHandle(depthTexture), clearDepth)
    }

    override fun clearColorAndDepthTextures(colorTexture: GpuTexture, clearColor: Vector4fc,
        depthTexture: GpuTexture, clearDepth: Double, regionX: Int, regionY: Int,
        regionWidth: Int, regionHeight: Int) {
        // P33：确保命令列表已打开
        this.ensureListOpen()
        // P3b fix：GuiItemAtlas 槽位 STALE 重绘前只清该槽位矩形区域。原实现忽略
        // region 整图清空 → 滚动/翻页触发任意槽位重绘时把整张物品图集抹掉，其它
        // 已烘好的槽位图标（图集区域仍标记有效、不再触发重绘）随之消失。
        MCDR_ABI.D3D12ClearColorTextureRegion(this.ctx, textureHandle(colorTexture),
            clearColor.x(), clearColor.y(), clearColor.z(), clearColor.w(),
            regionX, regionY, regionWidth, regionHeight)
        MCDR_ABI.D3D12ClearDepthTextureRegion(this.ctx, textureHandle(depthTexture),
            clearDepth, regionX, regionY, regionWidth, regionHeight)
    }

    override fun clearDepthTexture(depthTexture: GpuTexture, clearDepth: Double) {
        // P33：确保命令列表已打开
        this.ensureListOpen()
        MCDR_ABI.D3D12ClearDepthTexture(this.ctx, textureHandle(depthTexture), clearDepth)
    }

    /**
     * 26.2 新增的 CommandEncoderBackend 抽象方法：清空模板纹理。
     *
     * 写法与 [clearDepthTexture] 完全一致：先 [ensureListOpen]（P33），再用
     * [textureHandle] 取 native 句柄（非 D3D12GpuTexture 会在 cast 处抛
     * ClassCastException，与 depth/color 清理路径的校验方式相同）。
     * native 侧返回 1=成功 / 0=失败，与 clearDepthTexture 一样不检查返回值。
     */
    override fun clearStencilTexture(texture: GpuTexture, value: Int) {
        // P33：确保命令列表已打开
        this.ensureListOpen()
        MCDR_ABI.D3D12ClearStencilTexture(this.ctx, textureHandle(texture), value)
    }

    // -----------------------------------------------------------------------
    // Copies
    // -----------------------------------------------------------------------

    override fun writeToBuffer(destination: GpuBufferSlice, data: ByteBuffer) {
        // P75：时间性渲染的写出拦截点。这里同时承担两件事：
        //  1) 关卡投影矩阵（label "Camera projection matrix UBO level"，64B）：注入亚像素 jitter；
        //  2) 相机 UBO（label "Global Settings UBO"，56B）：捕获 CameraBlockPos/CameraOffset。
        // 两者 usage 都是 136（UNIFORM|COPY_DST）**不可映射**，而 vanilla 每帧都经
        // `ProjectionMatrixBuffer.writeBuffer` / `GlobalSettingsUniform.update` 在这里重写它们，
        // 所以这是唯一能拿到逐帧新鲜字节的地方。
        // 未开启 / 不是这两个缓冲时返回 null，行为与改动前完全一致。
        val jittered = D3D12Temporal.interceptWrite(destination, data)
        val payload = jittered ?: data
        // P33：确保命令列表已打开
        this.ensureListOpen()
        // 与官方 VulkanCommandEncoder.writeToBuffer 对齐：直接上传，不做 NaN 检测/替换。
        // 若数据含 NaN（例如投影矩阵 near/far 异常），应在源头修复，而非在上传时暴力覆盖。
        // 暴力替换会把整块顶点数据也错误地覆写，导致黑屏（P7 根因定位）。
        val staging = this.transientMemory.uploadStaging(payload, 1L, GpuBuffer.USAGE_COPY_SRC)
        MCDR_ABI.D3D12CopyBuffer(this.ctx, bufferHandle(staging.buffer()), staging.offset(),
            bufferHandle(destination.buffer()), destination.offset(),
            payload.remaining().toLong())
    }

    override fun copyToBuffer(source: GpuBufferSlice, target: GpuBufferSlice) {
        // P33：确保命令列表已打开
        this.ensureListOpen()
        MCDR_ABI.D3D12CopyBuffer(this.ctx, bufferHandle(source.buffer()), source.offset(),
            bufferHandle(target.buffer()), target.offset(),
            source.length())
    }

    override fun writeToTexture(destination: GpuTexture, source: ByteBuffer, mipLevel: Int,
        depthOrLayer: Int, destX: Int, destY: Int, width: Int, height: Int) {
        // P33：确保命令列表已打开
        this.ensureListOpen()
        val staging = this.transientMemory.uploadStaging(source, 1L, GpuBuffer.USAGE_COPY_SRC)
        MCDR_ABI.D3D12WriteToTexture(this.ctx, bufferHandle(staging.buffer()), staging.offset(),
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
        // P33：确保命令列表已打开
        this.ensureListOpen()
        MCDR_ABI.D3D12CopyTextureToBuffer(this.ctx, textureHandle(source), mipLevel, 0,
            x, y, width, height, bufferHandle(destination), offset)
        // Run the callback after the next submit (mirror of the destroyQueue rotate).
        this.pendingCallbacks.add(callback)
    }

    override fun copyTextureToTexture(source: GpuTexture, destination: GpuTexture, mipLevel: Int,
        destX: Int, destY: Int, sourceX: Int, sourceY: Int, width: Int, height: Int) {
        // P33：确保命令列表已打开
        this.ensureListOpen()
        MCDR_ABI.D3D12CopyTextureToTexture(this.ctx, textureHandle(source),
            textureHandle(destination), mipLevel, 0, sourceX, sourceY, destX, destY, width, height)
    }
    // -----------------------------------------------------------------------
    // Fences & timestamps
    // -----------------------------------------------------------------------

    override fun createFence(): GpuFence {
        // 官方 VulkanCommandEncoder.createFence()：捕获当前 submit index，fence
        // 在该 encoder 的下一次提交完成后完成。D3D12 侧用**本 encoder 自己的**
        // per-command-list fence 复现：token = 当前序号 + 1，只有这个 encoder 的
        // 下一次 submit（native submitCommandList 里的 ++ctx->fenceValue）推进它。
        // 不要退回设备级 queueFenceValue：一次性 encoder（createBuffer(data) 的上传）
        // 提交时也会推进全局序列，而 vanilla 的 StagedVertexBuffer endFrame /
        // MappableRingBuffer rotate 是在帧中间取 token 的，全局序列会让这些 token
        // 在帧提交之前就完成、buffer 被提前回收（BUG-03）。
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
        // P33：确保命令列表已打开
        this.ensureListOpen()
        MCDR_ABI.D3D12WriteTimestamp(this.ctx, (pool as D3D12GpuQueryPool).nativeHandle(), index)
    }

    // -----------------------------------------------------------------------
    // Submit lifecycle
    // -----------------------------------------------------------------------

    override fun submit() {
        // P33 async：独立渲染线程流水线
        //
        // 流程：
        //   1. 记录提交前的 per-ctx fence 值（诊断用，见下方 fenceBefore）
        //   2. 请求渲染线程开始新帧（通知它主线程已录制完毕）
        //   3. 等待渲染线程发 RECORDING_READY
        //   4. 主线程继续录制命令（render pass / clear / copy / core）
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
        val fenceBefore = MCDR_ABI.D3D12GetFenceValue(this.ctx)

        // 步骤 2：请求渲染线程开始新帧。
        //
        // 只有帧 encoder（device != null，即 D3D12Device.createCommandEncoder() 返回的共享
        // encoder）允许进入异步帧管线。native 的 asyncRenderBeginFrame 接受任意 ctx，一次性
        // encoder（device == null：D3D12Device.createBuffer(data) 内部为上传临时新建的那个，
        // 以及 D3D12Backend 的自测 encoder）一旦走进来就会劫持整帧——渲染线程替它
        // Close+Execute+Signal、把 gAsyncOwnsPresent 置 true 并调用 presentSurface；若这次
        // 临时提交落在世界 blit 之后、本帧提交之前，就会提前 Present（闪旧画面/双 Present）。
        // 它还会把 frame encoder 的 fence token（createFence 捕获的 ++value）连同整帧
        // 提交一起推走，StagedVertexBuffer / MappableRingBuffer 的回收时机随之错位。
        // （token 本身已改成 per-ctx，见 createFence；这里仍不放过一次性 encoder，
        // 因为它连异步帧状态机都不该碰。）
        // 一次性 encoder 一律走下面的同步路径：提交立即完成、不碰 gAsyncRenderCtx。
        val asyncStarted = this.device != null && MCDR_ABI.D3D12AsyncRenderBeginFrame(this.ctx)
        if (!asyncStarted) {
            // 无 active surface（初始化阶段或窗口未创建），回退到同步路径
            // 同步路径：确保命令列表已打开（幂等：已打开则跳过 Reset），录制命令、提交
            MCDR_ABI.D3D12BeginCommandList(this.ctx)
            MCDR_ABI.D3D12Submit(this.ctx)
            this.transientMemory.rotate()
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

        // P3 插桩：记录本帧起点与相对上一帧的间隔（仅 DX12_PROF=1 生效）。
        val prof = MCDR_ABI.PROF
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
        val recordingReady = MCDR_ABI.D3D12AsyncRenderWaitRecordingReady(this.ctx, 5_000L)
        if (prof) gProfRecordingReadyNs += System.nanoTime() - profT0
        if (!recordingReady) {
            // P41：渲染线程卡死，降级到 SYNC 模式避免整个游戏崩溃
            IllusionLogger.warnMessage("[backend] [P41] asyncRenderWaitRecordingReady timeout after 5000ms — falling back to SYNC, ctx=0x"
                + this.ctx.toULong().toString(16))
            System.err.flush()
            // 清除 gAsyncRenderCtx，防止下一个 asyncBeginFrame 被 "previous frame not complete" 拒绝
            MCDR_ABI.D3D12ClearAsyncRenderCtx()
            MCDR_ABI.D3D12BeginCommandList(this.ctx)
            MCDR_ABI.D3D12Submit(this.ctx)
            this.transientMemory.rotate()
            val run = this.pendingCallbacks
            this.pendingCallbacks.clear()
            for (callback in run) {
                callback.run()
            }
            return
        }
        // 步骤 4：命令已由上层框架在此 submit() 之前录制完毕（render pass、clear、copy、core）
        //         无需额外操作。

        // 步骤 5：通知渲染线程所有命令已入队
        MCDR_ABI.D3D12AsyncSendCommandsReady(this.ctx)

        // 步骤 6（P61 多帧飞行-延迟结算）：本帧**不再**阻塞等待渲染线程完成提交。
        // 渲染线程此刻开始 Close+Execute+Signal+Present；主线程直接返回，把结算
        // 推迟到下一帧 ensureListOpen()/submit() 开头——中间隔着 MC 的 extract
        //（P50 实测 0.19-0.28ms），届时渲染线程早已完成，等待≈0。
        // 正确性：结算仍早于下一次 beginCommandList（见 settlePendingSubmit）。
        this.pendingSubmit = true

        // 步骤 7：post-submit 清理（与旧同步 submit 保持一致）
        this.transientMemory.rotate()
        // Run callbacks queued by the previous frame's copyTextureToBuffer.
        val run = this.pendingCallbacks
        this.pendingCallbacks.clear()
        for (callback in run) {
            callback.run()
        }
        // P3 插桩：每 PROF_INTERVAL 帧输出一次各阶段耗时均值（ms）。
        if (prof) {
            ++gProfAsyncFrames
            gProfTotalNs += System.nanoTime() - profStart
            if (gProfAsyncFrames % PROF_INTERVAL == 0L) {
                val inv = 1.0 / PROF_INTERVAL
                IllusionLogger.debugMessage("[backend-java] P3 prof n=${PROF_INTERVAL}"
                    + " frameInterval=${profMs(gProfIntervalNs * inv)}"
                    + "ms recordingReady=${profMs(gProfRecordingReadyNs * inv)}"
                    + "ms waitSubmit=${profMs(gProfWaitSubmitNs * inv)}"
                    + "ms submitTotal=${profMs(gProfTotalNs * inv)}ms")
                // P3 绘制热路径：判断帧时间花在 mod 绘制路径还是 MC 自身逻辑。
                IllusionLogger.debugMessage("[backend-java] P3 core: batches/frame=${D3D12RenderPassBackend.gProfMultiBatches * inv}"
                    + " draws/frame=${D3D12RenderPassBackend.gProfMultiDraws * inv}"
                    + " batchPath=${profMs(D3D12RenderPassBackend.gProfMultiPathNs * inv)}ms"
                    + " drawIndexedCalls/frame=${D3D12RenderPassBackend.gProfDrawIndexedCalls * inv}"
                    + " drawIndexed=${profMs(D3D12RenderPassBackend.gProfDrawIndexedNs * inv)}ms")
                // P3b：并行批量路径四段拆分（主线程串行 vs worker 并行）。
                IllusionLogger.debugMessage("[backend-java] P3 batch: build=${profMs(D3D12RenderPassBackend.gProfBuildNs * inv)}"
                    + "ms prepare=${profMs(D3D12RenderPassBackend.gProfPrepareNs * inv)}"
                    + "ms workers=${profMs(D3D12RenderPassBackend.gProfWorkersNs * inv)}"
                    + "ms execute=${profMs(D3D12RenderPassBackend.gProfExecuteNs * inv)}"
                    + "ms fallbacks=${D3D12RenderPassBackend.gProfFallbacks * inv}"
                    + " | P62 batchDraws=${D3D12RenderPassBackend.gProfSharedBatchDraws}"
                    + " vbSame=${D3D12RenderPassBackend.gProfSameVbBuf}"
                    + " vbSameOff=${D3D12RenderPassBackend.gProfSameVbOff}"
                    + " ibSame=${D3D12RenderPassBackend.gProfSameIbBuf}"
                    + " ibSameType=${D3D12RenderPassBackend.gProfSameIdxType}"
                    + " | batches=${D3D12RenderPassBackend.gProfBatches}"
                    + " sharedDesc=${D3D12RenderPassBackend.gProfSharedDescBatches}"
                    + " singleBundle=${D3D12RenderPassBackend.gProfSingleBundles}"
                    + " srvVaryFallback=${D3D12RenderPassBackend.gProfSrvVaryFallbacks}"
                    + " slots=${D3D12RenderPassBackend.gProfSlotsTotal}"
                    + " | P62b bc=${D3D12RenderPassBackend.gProfBcTotal}"
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
        // P61：close 前必须结算挂起的异步提交。两条路径都危险：
        //   1. 临时 encoder（device == null）：close() 末尾立即 dx12DestroyCommandEncoder，
        //      若渲染线程仍在 Close+Execute+Signal 该 ctx，销毁会与其竞争 → 悬空/崩溃。
        //   2. 共享 encoder：D3D12Device.close() 在 encoder.close() 之后同样立即
        //      dx12DestroyCommandEncoder。
        // 结算后 gAsyncRenderCtx 已置空，渲染线程不再触碰本 ctx，EndRenderPass/
        // EndCommandList/Destroy 才是安全的。
        settlePendingSubmit()
        if (this.currentRenderPass != null) {
            MCDR_ABI.D3D12EndRenderPass(this.ctx)
            this.currentRenderPass = null
        }
        MCDR_ABI.D3D12EndCommandList(this.ctx)
        // 共享 encoder（device != null）由 D3D12Device.close() 负责销毁 CommandContext，
        // 此处不调用 dx12DestroyCommandEncoder，避免 CubeMap.render() 等内部调用
        // close() 时意外销毁共享 ctx 导致后续渲染使用悬空指针。
        // 临时/一次性 encoder（device == null，如 createBuffer(data)）仍调用
        // dx12DestroyCommandEncoder，保证资源在 fence 等待后安全释放。
        if (this.device == null) {
            MCDR_ABI.D3D12DestroyCommandEncoder(this.ctx)
        }
        this.transientMemory.close()
    }

    companion object {
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
