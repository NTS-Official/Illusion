package dev.nts.expdxr.d3d12.render.core

import dev.nts.expdxr.misc.IllusionLogger

import com.mojang.renderpearl.api.buffers.GpuBuffer
import com.mojang.renderpearl.api.buffers.GpuBufferSlice
import com.mojang.renderpearl.api.buffers.TransientMemory
import dev.nts.expdxr.d3d12.extern.MCDR_ABI
import java.nio.ByteBuffer
import java.util.ArrayList
import java.util.ArrayDeque
import java.util.Deque

/**
 * D3D12-backed {@link TransientMemory}, mirroring the official
 * {@code VulkanTransientMemory} lifecycle.
 *
 * <p>P54：staging 上传路径改用「块分配器」而非每次分配都新建 D3D12 committed buffer。
 * 此前 {@code uploadStaging} 每次调用都会 {@code new D3D12GpuBuffer} 两次（一个 UPLOAD
 * staging + 一个 DEFAULT 目标），而 {@code CommandEncoder.writeToBuffer} 每帧调用数十次
 * → 每帧上百次 {@code ID3D12CreateBuffer}（P53 采样中 {@code MCDR_ABI.ID3D12CreateBuffer}
 * 占渲染线程 ~16%）。官方 {@code VulkanTransientMemory} 用 512KB
 * {@code TransientBlockAllocator} 复用块，本类按同样的思路在**帧内 bump 分配**：
 * 一个 512KB 的 UPLOAD 块按偏移切分给多次上传，块随帧 {@link #rotate()} 在
 * {@link #FRAMES_IN_FLIGHT} 帧后释放。
 *
 * <p>Buffers allocated during one frame are retained for {@link #FRAMES_IN_FLIGHT}
 * frames (matching the native double-buffered submit) and released on
 * {@link #rotate()}, which the encoder calls on every {@code submit()}.
 *
 * <p>{@code allocateGpuMapped} uses a ring buffer of 3 UPLOAD heap buffers
 *（镜像 {@code MappableRingBuffer} 的 3 路轮换）：每帧 rotate() 切换到下一路，
 * 当前路的 offset 在该帧内单调递增。这保证了每帧 DynamicUniforms 等使用
 * allocateGpuMapped 的代码都能拿到新的独立偏移，而非复用同一缓冲起始处。
 * 这三路是**常驻**缓冲（归 {@link #uboRing} 所有、随 {@link #close()} 释放），
 * 不参与 {@link #frames} 队列的延迟释放 —— 否则第 3 次 rotate() 就会把它们关掉
 * （P174）。
 */
class D3D12TransientMemory(private val ctx: Long, private val ensureListOpen: Runnable) : TransientMemory {
    private val frames: Deque<MutableList<D3D12GpuBuffer>> = ArrayDeque()
    private var frame: MutableList<D3D12GpuBuffer> = ArrayList()
    private var closed: Boolean = false

    // allocateGpuMapped ring buffer
    private val uboRing: Array<D3D12GpuBuffer?> = arrayOfNulls(UBO_RING_COUNT)
    /** 当前正在写入的 ring buffer 路索引。 */
    private var uboRingIdx: Int = 0
    /** 当前路的已用字节数（从 0 开始单调递增，达到 BLOCK_SIZE 时 rotate）。 */
    private var uboRingOffset: Long = 0

    private val stagingPools: List<ArrayDeque<D3D12GpuBuffer>> = listOf(
        ArrayDeque<D3D12GpuBuffer>(), ArrayDeque<D3D12GpuBuffer>(), ArrayDeque<D3D12GpuBuffer>())
    /** 各池当前常驻字节数（与 {@link #stagingPools} 一一对应，用于容量控制）。 */
    private val stagingPoolBytes: LongArray = LongArray(STAGING_POOL_COUNT)
    private var stagingPoolIdx: Int = 0
    /** 本帧正在填充的 staging 块；为空表示本帧尚未分配。 */
    private var stagingBlock: D3D12GpuBuffer? = null
    /** 当前 staging 块是否可以归还进池（超大块不入池，交由 frames 生命周期释放）。 */
    private var stagingBlockPooled: Boolean = false
    private var stagingBlockUsed: Long = 0L
    /**
     * 本帧内「写满后被替换下来」的入池块。{@link #stagingBlock} 只保存当前正在填充的
     * 那一块；一帧内若上传量超过单块容量会出现多块，其余块暂存于此，待 {@link #rotate()}
     * 一并归还池（超大块在分配时已登记进 frame，无需在此跟踪）。
     */
    private val retiredPooledStagingBlocks: MutableList<D3D12GpuBuffer> = ArrayList()

    init {
        // 预分配 3 路 UPLOAD 缓冲（同 MappableRingBuffer 构造时的 3 个 buffer）。
        //
        // P174 fix：这三个是**常驻**缓冲，归 uboRing 所有，绝不能 register 进 frame ——
        // frame 会在第 3 次 rotate() 时整体 close（FRAMES_IN_FLIGHT=2），那样 ring 里
        // 留着的就是三个已销毁的缓冲，allocateGpuMapped 一调就 map 到已关闭对象上
        // （D3D12MapBuffer 返回 null → IllegalStateException）。代价只是三个 4 KiB 缓冲
        // 常驻，随本对象 close() 一起释放。
        for (i in 0 until UBO_RING_COUNT) {
            uboRing[i] = D3D12GpuBuffer(
                GpuBuffer.USAGE_MAP_WRITE or GpuBuffer.USAGE_COPY_SRC, UBO_RING_BLOCK_SIZE
            )
        }
    }

    private fun register(buffer: D3D12GpuBuffer) {
        this.frame.add(buffer)
    }

    override fun allocateCpu(size: Long, alignment: Long, minimumAllocation: Long, elementSize: Long): ByteBuffer {
        require(size <= Int.MAX_VALUE) { "allocateCpu larger than 2GB is not supported" }
        // P199f：原实现用 allocateDirect，每帧成百上千次 → 原生内存只靠 Cleaner 回收，
        // 实测在世界内 900MB+/min 线性上涨（主菜单平坦）。CPU 暂存最终会被拷进映射缓冲，
        // 用堆缓冲即可，GC 及时回收，语义不变（默认 BIG_ENDIAN 与原行为一致）。
        return ByteBuffer.allocate(size.toInt())
    }

    override fun allocateStaging(size: Long, alignment: Long,
        usage: @GpuBuffer.Usage Int, minimumAllocation: Long, elementSize: Long): GpuBufferSlice.MappedView {
        val offset = allocateInStagingBlock(size, alignment)
        return this.stagingBlock!!.map(offset, size, false, true)
    }

    override fun allocateGpu(size: Long, alignment: Long,
        usage: @GpuBuffer.Usage Int, minimumAllocation: Long, elementSize: Long): GpuBufferSlice {
        val buffer = D3D12GpuBuffer(usage or GpuBuffer.USAGE_COPY_DST, size)
        this.register(buffer)
        return buffer.slice()
    }

    override fun allocateGpuMapped(size: Long, alignment: Long,
        usage: @GpuBuffer.Usage Int, minimumAllocation: Long, elementSize: Long): GpuBufferSlice.MappedView {
        // 镜像 VulkanTransientMemory.allocateGpuMapped：在 ring buffer 当前路中分配，
        // 超过BLOCK_SIZE时自动切换到下一路（rotate）。
        // 与 MappableRingBuffer 语义对齐：每帧 rotate() 后切换到新路径，旧路径供 GPU 读取。
        check(!this.closed) { "allocateGpuMapped called on a closed D3D12TransientMemory" }
        var remaining = UBO_RING_BLOCK_SIZE - uboRingOffset
        if (size > remaining) {
            // 当前路剩余空间不足，切换到下一路
            uboRingIdx = (uboRingIdx + 1) % UBO_RING_COUNT
            uboRingOffset = 0
            remaining = UBO_RING_BLOCK_SIZE
        }
        val buf = uboRing[uboRingIdx]!!
        // P174：ring buffer 是常驻对象，只有 close() 会销毁它们。走到这里还关闭着就说明
        // 归属又被记错了（修前正是如此：init 把三个 ring buffer 登记进 frame，第 3 次
        // rotate() 就把它们关了），这里直接大声失败，而不是 map 到已销毁的缓冲上。
        check(!buf.isClosed()) {
            "allocateGpuMapped: ring buffer slot ${this.uboRingIdx} is already closed"
        }
        val offset = uboRingOffset
        uboRingOffset += size
        return buf.map(offset, size, false, true)
    }

    override fun uploadStaging(data: MutableList<ByteBuffer>, alignment: Long,
        usage: @GpuBuffer.Usage Int, minimumAllocation: Long, elementSize: Long): GpuBufferSlice {
        // P54：不再为每次上传新建 DEFAULT 目标缓冲 + UPLOAD staging 缓冲，而是直接把
        // 数据写进本帧的 512KB UPLOAD 块，返回块内切片供调用方做一次 CopyBufferRegion。
        // 官方 VulkanCommandEncoder.writeToBuffer 同样只做「staging -> 目标」一次拷贝。
        var total = 0L
        for (buffer in data) {
            total += buffer.remaining().toLong()
        }
        require(total != 0L) { "Cannot upload zero bytes" }
        // P108z（用户 m11203 的崩溃：`dx12MapBuffer returned null`）：staging 块不可映射
        // **绝不能**把整帧打断。分配 + 写入 + 换块重试全在 [writeIntoStagingBlock] 里。
        val write = writeIntoStagingBlock(total, alignment) { dst ->
            for (buffer in data) {
                dst.put(buffer.duplicate())
            }
            total
        }
        return write.getOrThrow().first
    }

    /**
     * P108z：在本帧 staging 块内分配 [total] 字节、把数据写进去，返回 `(切片, 写入的总字节数)`。
     *
     * <p>这是 [uploadStaging] / [multiUploadStaging] 共用的写入口。**映射失败不再直接抛异常** ——
     * 用户 m11203 的崩溃（`dx12MapBuffer returned null`）就发生在 `stagingBlock!!.map(...)` 上，
     * 它会把整个渲染帧打断。现在改成：
     * <ol>
     *   <li>映射 + 写入；成功就返回块内切片（与改动前逐字一样）；</li>
     *   <li>失败就 [reportStagingMapFailure] 打一行 WARN（块句柄 / 容量 / 是否已关闭 / 偏移 / 长度 /
     *       异常原文），[discardStagingBlock] 丢弃它，重新分配一块再试；</li>
     *   <li>连续失败 [MAX_STAGING_MAP_RETRIES] 次以上才抛出 —— 那是设备/内存层面的问题，早点报错
     *       比每帧刷屏好。</li>
     * </ol>
     *
     * @param write 收到可写缓冲；返回**实际写入的字节数**（`multiUploadStaging` 里可能为 0）。
     */
    private fun writeIntoStagingBlock(total: Long, alignment: Long,
        write: (ByteBuffer) -> Long): Result<Pair<GpuBufferSlice, Long>> {
        var attempt = 0
        while (true) {
            val offset = allocateInStagingBlock(total, alignment)
            val block = this.stagingBlock
                ?: error("transientMemory: no staging block after allocateInStagingBlock")
            var written = 0L
            val failure = try {
                block.map(offset, total, false, true).use { view ->
                    written = write(view.data())
                }
                null
            } catch (t: Throwable) {
                t
            }
            if (failure == null) {
                return Result.success(block.slice(offset, written) to written)
            }
            reportStagingMapFailure(block, offset, total, attempt, failure)
            if (attempt >= MAX_STAGING_MAP_RETRIES) {
                return Result.failure(IllegalStateException(
                    "staging block 0x${block.handle().toULong().toString(16)} (${block.size()} bytes, "
                        + "closed=${block.isClosed()}) could not be mapped at $offset+$total after "
                        + "${attempt + 1} attempt(s)", failure))
            }
            attempt++
            discardStagingBlock(block)
        }
    }

    /**
     * P108z：staging 块映射失败时**丢弃**它并让下一次 [allocateInStagingBlock] 取新的一块。
     *
     * <p>丢弃方式是 `close()`：native 侧的 `destroyObject`（`MCDR\src\core\d3d12_device.cpp:2104`）
     * 把释放排进 `gPendingDeletes`，按 queue fence 延迟真正 delete，所以这里立即 `close()` 不会
     * 打断在飞命令列表。Java 侧把引用直接丢掉（不入池、不登记 frame）——这是刻意的：这块已经
     * 被证明不可映射，绝不能再被 `acquireStagingBlock` 从池里取出来重演同一次崩溃。
     */
    private fun discardStagingBlock(block: D3D12GpuBuffer) {
        if (this.stagingBlock === block) {
            this.stagingBlock = null
            this.stagingBlockUsed = 0L
            this.stagingBlockPooled = false
        }
        // 若这块已经因为"写满"被退役进本帧待归还表，把它摘出来 —— 否则 rotate() 会把它还进池，
        // 下一次 acquireStagingBlock 又会取到同一块，重演同一次失败。
        this.retiredPooledStagingBlocks.removeIf { it === block }
        try {
            block.close()
        } catch (t: Throwable) {
            IllusionLogger.warnFormat(
                "[backend-java] transientMemory: closing an unmappable staging block threw %s%n", t)
        }
    }

    /** P108z：映射失败的取证行，每次失败一行（不刷屏：只有真的失败才打）。 */
    private fun reportStagingMapFailure(block: D3D12GpuBuffer, offset: Long, total: Long,
        attempt: Int, failure: Throwable) {
        IllusionLogger.warnFormat(
            "[backend-java] transientMemory: staging block 0x%x (%d bytes, closed=%s) could not be "
                + "mapped at offset=%d length=%d (attempt %d): %s — discarding it and taking a fresh "
                + "block; the upload continues. This used to crash the frame with "
                + "'dx12MapBuffer returned null'.%n",
            block.handle(), block.size(), block.isClosed().toString(), offset, total, attempt + 1,
            failure.toString())
        System.err.flush()
    }

    override fun uploadGpu(data: MutableList<ByteBuffer>, alignment: Long,
        usage: @GpuBuffer.Usage Int, minimumAllocation: Long, elementSize: Long): GpuBufferSlice {
        var total = 0L
        for (buffer in data) {
            total += buffer.remaining().toLong()
        }
        return this.upload(data, usage, total)
    }

    override fun multiUploadStaging(data: MutableList<ByteBuffer>,
        alignment: Long, usage: @GpuBuffer.Usage Int): MutableList<GpuBufferSlice> {
        if (data.isEmpty()) {
            return mutableListOf<GpuBufferSlice>()
        }
        // P54：每个输入在本帧 staging 块内独立占一段，全部返回块内切片。
        // P108z：与 [uploadStaging] 共用同一条"坏块就换一块重试"的写入口。
        val result = mutableListOf<GpuBufferSlice>()
        for (buffer in data) {
            val length = buffer.remaining()
            val write = writeIntoStagingBlock(length.toLong(), alignment) { dst ->
                if (length > 0) {
                    dst.put(buffer.duplicate())
                }
                length.toLong()
            }
            result.add(write.getOrThrow().first)
        }
        return result
    }

    override fun multiUploadGpu(data: MutableList<ByteBuffer>,
        alignment: Long, usage: @GpuBuffer.Usage Int): MutableList<GpuBufferSlice> {
        return this.multiUpload(data, usage)
    }

    /**
     * P54：在本帧 staging 块内按 {@code alignment}（至少
     * {@link #MIN_BLOCK_ALIGNMENT}）bump 分配 {@code size} 字节，返回块内偏移。
     * 当前块放不下时把旧块暂存起来、再取一块新的。新块优先复用本路池中的空闲块，
     * 池空（或块过小）才新建；超大块不入池，登记进本帧随 frame 生命周期释放。
     */
    private fun allocateInStagingBlock(size: Long, alignment: Long): Long {
        val align = alignment.coerceAtLeast(MIN_BLOCK_ALIGNMENT)
        if (this.stagingBlock == null) {
            this.stagingBlock = acquireStagingBlock(size, align)
        }
        var offset = roundUp(this.stagingBlockUsed, align)
        if (offset + size > this.stagingBlock!!.size()) {
            // 当前块写满：入池块暂存待 rotate 归还，超大块已登记 frame，均无需在此 close。
            if (this.stagingBlockPooled) {
                this.retiredPooledStagingBlocks.add(this.stagingBlock!!)
            }
            this.stagingBlock = acquireStagingBlock(size, align)
            offset = 0L
        }
        this.stagingBlockUsed = offset + size
        return offset
    }

    /**
     * 取得一块 staging（UPLOAD heap）并设置 {@link #stagingBlockPooled}：
     * <ul>
     *   <li>需要的大小 ≤ {@link #MAX_POOLED_BLOCK_SIZE}：优先复用本路池中足够大的块，
     *       否则新建一块（入池管理，不登记 frame）；</li>
     *   <li>更大：不入池，新建后登记进本帧，随 frame 在 {@link #FRAMES_IN_FLIGHT} 帧后释放。</li>
     * </ul>
     */
    private fun acquireStagingBlock(size: Long, align: Long): D3D12GpuBuffer {
        val blockSize = roundUp(size, align).coerceAtLeast(STAGING_BLOCK_SIZE)
        if (blockSize > MAX_POOLED_BLOCK_SIZE) {
            this.stagingBlockPooled = false
            val big = D3D12GpuBuffer(
                GpuBuffer.USAGE_MAP_WRITE or GpuBuffer.USAGE_COPY_SRC, blockSize
            )
            this.register(big)
            return big
        }
        this.stagingBlockPooled = true
        val pool = this.stagingPools[this.stagingPoolIdx]
        val pooled = pool.pollFirst()
        if (pooled != null) {
            this.stagingPoolBytes[this.stagingPoolIdx] -= pooled.size()
            if (pooled.isClosed()) {
                // P108z：池里绝不该有关闭的块（close() 只在 frames 队列超限与 close() 里发生，
                // 而池中块不在 frames 队列里）。真出现就说明块的归属记错了 —— 记一行，丢弃它，
                // 继续往下新建，避免把一块已关闭的缓冲交给 uploadStaging 去 map。
                IllusionLogger.warnFormat(
                    "[backend-java] transientMemory: the staging pool handed out an already-closed "
                        + "block 0x%x (%d bytes); discarding it instead of mapping it%n",
                    pooled.handle(), pooled.size())
                System.err.flush()
            } else if (pooled.size() >= size) {
                return pooled
            } else {
                // 池中块过小（仅在本路块尺寸演化时出现）：丢弃后新建更大块。此块已退役
                // ≥STAGING_POOL_COUNT 帧，GPU 早已执行完它的拷贝，close 安全。
                pooled.close()
            }
        }
        return D3D12GpuBuffer(
            GpuBuffer.USAGE_MAP_WRITE or GpuBuffer.USAGE_COPY_SRC, blockSize
        )
    }

    /**
     * Upload a set of CPU buffers into a single GPU-side (DEFAULT heap) buffer:
     * write them into one UPLOAD staging buffer, then one CopyBufferRegion.
     *
     * <p>P54：此路径仅供 {@code uploadGpu}/{@code multiUploadGpu} 使用（MC 未调用），
     * 仍保持每次新建缓冲的简单实现。
     */
    private fun upload(data: MutableList<ByteBuffer>, usage: Int, total: Long): GpuBufferSlice {
        require(total != 0L) { "Cannot upload zero bytes" }
        val staging = D3D12GpuBuffer(
            GpuBuffer.USAGE_MAP_WRITE or GpuBuffer.USAGE_COPY_SRC, total
        )
        this.register(staging)
        staging.map(0L, total, false, true).use { view ->
            val dst = view.data()
            for (buffer in data) {
                dst.put(buffer.duplicate())
            }
        }
        val gpu = D3D12GpuBuffer(usage or GpuBuffer.USAGE_COPY_DST, total)
        this.register(gpu)
        this.ensureListOpen.run()  // 帧外上传（资源重载）需先打开命令列表
        MCDR_ABI.D3D12CopyBuffer(this.ctx, staging.handle(), 0L, gpu.handle(), 0L, total)
        return gpu.slice()
    }

    /** Shared-block variant of {@link #upload}: returns one slice per input buffer. */
    private fun multiUpload(data: MutableList<ByteBuffer>, usage: Int): MutableList<GpuBufferSlice> {
        var total = 0L
        for (buffer in data) {
            total += buffer.remaining().toLong()
        }
        if (total == 0L) {
            return mutableListOf<GpuBufferSlice>()
        }
        val staging = D3D12GpuBuffer(
            GpuBuffer.USAGE_MAP_WRITE or GpuBuffer.USAGE_COPY_SRC, total
        )
        this.register(staging)
        staging.map(0L, total, false, true).use { view ->
            val dst = view.data()
            for (buffer in data) {
                dst.put(buffer.duplicate())
            }
        }
        val gpu = D3D12GpuBuffer(usage or GpuBuffer.USAGE_COPY_DST, total)
        this.register(gpu)
        this.ensureListOpen.run()  // 帧外上传（资源重载）需先打开命令列表
        MCDR_ABI.D3D12CopyBuffer(this.ctx, staging.handle(), 0L, gpu.handle(), 0L, total)

        val result = mutableListOf<GpuBufferSlice>()
        var offset = 0L
        for (buffer in data) {
            val length = buffer.remaining().toLong()
            result.add(gpu.slice(offset, length))
            offset += length
        }
        return result
    }

    /**
     * Called by the encoder on every {@code submit()}: retire this frame's
     * buffers (keep {@link #FRAMES_IN_FLIGHT} frames alive, matching the native
     * fence wait of value-2) and release the oldest frame.
     *
     * <p>同时 rotate allocateGpuMapped ring buffer：切换至下一路，使新帧的 uniform
     * 写入不会覆盖仍在被 GPU 读取的旧帧数据。
     */
    fun rotate() {
        if (this.closed) {
            return
        }
        // P54：归还本帧用过的 staging 块到本路池。必须在 frame 入队**之前**处理：
        // 超出池容量的块会被登记进本帧，从而随该帧在 FRAMES_IN_FLIGHT 帧后 close。
        recycleStagingBlocks()
        this.frames.addLast(this.frame)
        this.frame = ArrayList()
        while (this.frames.size > FRAMES_IN_FLIGHT) {
            for (buffer in this.frames.removeFirst()) {
                buffer.close()
            }
        }
        // Rotate the UBO ring buffer：每帧切到下一路，确保不同帧的 uniform 数据不重叠。
        this.uboRingIdx = (this.uboRingIdx + 1) % UBO_RING_COUNT
        this.uboRingOffset = 0
    }

    /**
     * P54：把本帧用过的入池 staging 块归还到本路池，并轮转 {@link #stagingPoolIdx}。
     * 本路下一次被取用是在 {@link #STAGING_POOL_COUNT}（=3）帧之后，晚于
     * {@link #FRAMES_IN_FLIGHT}（=2），因此块被复用时 GPU 早已执行完引用它的拷贝命令。
     * 超出 {@link #MAX_POOL_BYTES_PER_POOL} 的块不入池，改登记进本帧延迟释放，避免常驻内存失控。
     */
    private fun recycleStagingBlocks() {
        if (this.stagingBlockPooled && this.stagingBlock != null) {
            this.retiredPooledStagingBlocks.add(this.stagingBlock!!)
        }
        val pool = this.stagingPools[this.stagingPoolIdx]
        var bytes = this.stagingPoolBytes[this.stagingPoolIdx]
        for (block in this.retiredPooledStagingBlocks) {
            if (bytes + block.size() <= MAX_POOL_BYTES_PER_POOL) {
                pool.offerLast(block)
                bytes += block.size()
            } else {
                this.frame.add(block)
            }
        }
        this.retiredPooledStagingBlocks.clear()
        this.stagingPoolBytes[this.stagingPoolIdx] = bytes
        this.stagingBlock = null
        this.stagingBlockUsed = 0L
        this.stagingBlockPooled = false
        this.stagingPoolIdx = (this.stagingPoolIdx + 1) % STAGING_POOL_COUNT
    }

    fun close() {
        if (this.closed) {
            return
        }
        this.closed = true
        if (IllusionLogger.debugEnabled()) {
            IllusionLogger.debugMessage("[backend-java] transientMemory.close: current=${this.frame.size}"
                + " queuedFrames=${this.frames.size}")
        }
        for (buffer in this.frame) {
            buffer.close()
        }
        this.frame = ArrayList()
        for (old in this.frames) {
            for (buffer in old) {
                buffer.close()
            }
        }
        this.frames.clear()
        // P54：池内常驻块与尚未归还的当前块/退役块不在 frames 队列里，需显式释放。
        for (pool in this.stagingPools) {
            for (buffer in pool) {
                buffer.close()
            }
            pool.clear()
        }
        if (this.stagingBlockPooled && this.stagingBlock != null) {
            this.stagingBlock!!.close()
        }
        for (buffer in this.retiredPooledStagingBlocks) {
            buffer.close()
        }
        this.retiredPooledStagingBlocks.clear()
        this.stagingBlock = null
        // P174 fix：ring buffer 不归 frames 队列（见 init 注释），必须在这里显式释放；
        // 置 null 是为了让 close() 之后的 allocateGpuMapped 立刻 NPE 而不是用野指针。
        for (i in 0 until UBO_RING_COUNT) {
            uboRing[i]?.close()
            uboRing[i] = null
        }
        if (IllusionLogger.debugEnabled()) {
            IllusionLogger.debugMessage("[backend-java] transientMemory.close: done")
        }
    }

    companion object {
        const val FRAMES_IN_FLIGHT: Int = 2
        /** allocateGpuMapped ring buffer 路数（镜像 MappableRingBuffer.BUFFER_COUNT=3）。 */
        private const val UBO_RING_COUNT: Int = 3
        /** 每路 allocateGpuMapped 缓冲大小（4KB，足够容纳多组 std140 uniform 块）。 */
        private const val UBO_RING_BLOCK_SIZE: Long = 4096L

        /** P54：staging 块的基准大小（对齐官方 TransientBlockAllocator 的 512KB）。 */
        private const val STAGING_BLOCK_SIZE: Long = 512L * 1024L

        /**
         * P54：块内子分配的最小对齐。上传块同时服务 buffer 拷贝（{@code writeToBuffer}）
         * 与纹理上传（{@code writeToTexture}/{@code copyBufferToTexture}）。后者在 native
         * 侧以 {@code D3D12_TEXTURE_COPY_TYPE_PLACED_FOOTPRINT} 的 {@code Offset=srcOffset}
         * 直接做 {@code CopyTextureRegion}，而 D3D12 要求 Placement 偏移是
         * {@code D3D12_TEXTURE_DATA_PLACEMENT_ALIGNMENT}（512）的倍数。改动前每个 staging
         * 缓冲都是新建的（偏移恒为 0），块分配后必须显式保证 512 对齐，否则纹理上传偏移
         * 只有 256 对齐会触发校验失败/内容错乱。浪费的填充极有限（512KB 块可容纳 1024 个
         * 小分配）。
         */
        private const val MIN_BLOCK_ALIGNMENT: Long = 512L

        // P54：staging 块池（UPLOAD heap，主机可见）。
        // 早期实现每帧 rotate() 都把 stagingBlock 置空 → 下一帧首次上传必然
        // new D3D12GpuBuffer → 每帧至少一次 CreateCommittedResource（P53 采样中
        // dx12CreateBuffer 仍占 ~9%）。改为 3 路块池轮转：帧 N 用完的块归还到 P[N%3]，
        // 该池最早在帧 N+3 才再次被取用，天然保证「块被复用时 GPU 早已执行完帧 N 的
        // 拷贝」（3 > FRAMES_IN_FLIGHT=2，与既有 frames 队列的退役语义一致）。
        private const val STAGING_POOL_COUNT: Int = 3
        /** 单路池常驻字节上限：超出者不入池，改走 frame 生命周期延迟释放，防止常驻内存失控。 */
        private const val MAX_POOL_BYTES_PER_POOL: Long = 4L * 1024L * 1024L
        /** 超过该大小的单次上传块不入池，避免个别超大上传把常驻内存抬上去。 */
        private const val MAX_POOLED_BLOCK_SIZE: Long = 8L * 1024L * 1024L

        /**
         * P108z：staging 块映射失败后最多再换几块（总共 [MAX_STAGING_MAP_RETRIES] + 1 次尝试）。
         * 一次就够 —— 池里同时坏掉两块意味着设备/内存层面的问题，早点抛出比刷屏好。
         */
        private const val MAX_STAGING_MAP_RETRIES: Int = 1

        private fun roundUp(value: Long, alignment: Long): Long {
            return (value + alignment - 1L) / alignment * alignment
        }

    }
}