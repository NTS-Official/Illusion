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
 * D3D12 版 [TransientMemory]：帧内 bump 分配 + [FRAMES_IN_FLIGHT] 帧延迟释放（镜像官方 Vulkan 实现）。
 *
 * staging 上传走 512KiB 块分配器：一次上传只切一块、返回块内切片，块随帧 [rotate] 释放。
 * `allocateGpuMapped` 用 3 路常驻 UPLOAD ring（[uboRing]，随 [close] 释放、不参与 [frames] 队列）。
 * 见 docs/misc/backend-and-device.md §4。
 */
class D3D12TransientMemory(private val ctx: Long, private val ensureListOpen: Runnable) : TransientMemory {
    private val frames: Deque<MutableList<D3D12GpuBuffer>> = ArrayDeque()
    private var frame: MutableList<D3D12GpuBuffer> = ArrayList()
    private var closed: Boolean = false

    private val uboRing: Array<D3D12GpuBuffer?> = arrayOfNulls(UBO_RING_COUNT)
    /** 当前路的已用字节数；超出 BLOCK_SIZE 时切下一路。 */
    private var uboRingIdx: Int = 0
    private var uboRingOffset: Long = 0

    private val stagingPools: List<ArrayDeque<D3D12GpuBuffer>> = listOf(
        ArrayDeque<D3D12GpuBuffer>(), ArrayDeque<D3D12GpuBuffer>(), ArrayDeque<D3D12GpuBuffer>())
    /** 各池当前常驻字节数（与 [stagingPools] 一一对应，用于容量控制）。 */
    private val stagingPoolBytes: LongArray = LongArray(STAGING_POOL_COUNT)
    private var stagingPoolIdx: Int = 0
    private var stagingBlock: D3D12GpuBuffer? = null
    /** 当前块能否归还进池：超大块不入池，走 frame 生命周期释放。 */
    private var stagingBlockPooled: Boolean = false
    private var stagingBlockUsed: Long = 0L
    /** 本帧「写满被替换下来」的入池块，[rotate] 时一并归还池。 */
    private val retiredPooledStagingBlocks: MutableList<D3D12GpuBuffer> = ArrayList()

    init {
        // 这三路是**常驻**缓冲，归 uboRing 所有，绝不能 register 进 frame：frame 队列在第 3 次
        // rotate() 时整体 close（FRAMES_IN_FLIGHT=2），之后 allocateGpuMapped 会 map 到已销毁对象。
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
        // 用堆缓冲而非 allocateDirect：后者靠 Cleaner 回收，实测世界内常驻内存 900MB+/min 线性上涨。
        // CPU 暂存最终会被拷进映射缓冲，语义不变（默认 BIG_ENDIAN 与原行为一致）。
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
        // 在 ring 当前路内 bump 分配；放不下就切下一路，旧路留给 GPU 读完才由 rotate 回收。
        check(!this.closed) { "allocateGpuMapped called on a closed D3D12TransientMemory" }
        var remaining = UBO_RING_BLOCK_SIZE - uboRingOffset
        if (size > remaining) {
            // 当前路剩余空间不足，切换到下一路
            uboRingIdx = (uboRingIdx + 1) % UBO_RING_COUNT
            uboRingOffset = 0
            remaining = UBO_RING_BLOCK_SIZE
        }
        val buf = uboRing[uboRingIdx]!!
        // ring 只由 close() 销毁；还关着说明归属记错了，宁可抛异常也别 map 已销毁缓冲。
        check(!buf.isClosed()) {
            "allocateGpuMapped: ring buffer slot ${this.uboRingIdx} is already closed"
        }
        val offset = uboRingOffset
        uboRingOffset += size
        return buf.map(offset, size, false, true)
    }

    override fun uploadStaging(data: MutableList<ByteBuffer>, alignment: Long,
        usage: @GpuBuffer.Usage Int, minimumAllocation: Long, elementSize: Long): GpuBufferSlice {
        // 直接写进本帧 512KiB UPLOAD 块并返回块内切片，调用方只做一次 CopyBufferRegion。
        var total = 0L
        for (buffer in data) {
            total += buffer.remaining().toLong()
        }
        require(total != 0L) { "Cannot upload zero bytes" }
        // staging 块不可映射**绝不能**打断整帧：分配 + 写入 + 换块重试全在 [writeIntoStagingBlock]。
        val write = writeIntoStagingBlock(total, alignment) { dst ->
            for (buffer in data) {
                dst.put(buffer.duplicate())
            }
            total
        }
        return write.getOrThrow().first
    }

    /**
     * 在本帧 staging 块内分配 [total] 字节并写入，返回（切片, 实际写入字节数）。
     * 映射失败不抛：打一行 WARN、丢弃坏块、换块重试；连续失败 [MAX_STAGING_MAP_RETRIES] 次才抛
     * （那是设备/内存层面的问题，早点报错好过每帧刷屏）。
     * @param write 收到可写缓冲；返回实际写入的字节数（multiUploadStaging 里可能为 0）。
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
     * 丢弃映射失败的 staging 块：close() 走 native 延迟删除，不会打断在飞命令列表；引用直接丢掉
     * （不入池、不登记 frame）—— 这块已被证明不可映射，绝不能再被 [acquireStagingBlock] 取出来重演。
     */
    private fun discardStagingBlock(block: D3D12GpuBuffer) {
        if (this.stagingBlock === block) {
            this.stagingBlock = null
            this.stagingBlockUsed = 0L
            this.stagingBlockPooled = false
        }
        // 若已作为「写满」退役进待归还表，先摘出来，否则 rotate() 会把它还进池、下次又取到同一块。
        this.retiredPooledStagingBlocks.removeIf { it === block }
        try {
            block.close()
        } catch (t: Throwable) {
            IllusionLogger.warnFormat(
                "transientMemory: closing an unmappable staging block threw %s%n", t)
        }
    }

    /** 映射失败取证行：每次失败一行，不刷屏。 */
    private fun reportStagingMapFailure(block: D3D12GpuBuffer, offset: Long, total: Long,
        attempt: Int, failure: Throwable) {
        IllusionLogger.warnFormat(
            "transientMemory: staging block 0x%x (%d bytes, closed=%s) could not be "
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
        // 每个输入在本帧 staging 块内独立占一段，全部返回块内切片（共用「坏块就换一块」写入口）。
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
     * 在本帧 staging 块内按 `alignment`（至少 [MIN_BLOCK_ALIGNMENT]）bump 分配，返回块内偏移；放不下就换块。
     * 新块优先复用本路池的空闲块，池空或过小才新建；超大块不入池，登记进本帧延迟释放。
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
     * 取一块 staging（UPLOAD heap）并设置 [stagingBlockPooled]：≤ [MAX_POOLED_BLOCK_SIZE] 优先复用本路池中
     * 足够大的块（入池管理、不登记 frame）；更大则不入池，新建后登记进本帧延迟释放。
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
                // 池里绝不该有关闭的块（close() 只发生在 frames 超限与 close()）：出现即归属记错，丢弃并新建。
                IllusionLogger.warnFormat(
                    "transientMemory: the staging pool handed out an already-closed "
                        + "block 0x%x (%d bytes); discarding it instead of mapping it%n",
                    pooled.handle(), pooled.size())
                System.err.flush()
            } else if (pooled.size() >= size) {
                return pooled
            } else {
                // 池中块过小（仅本路块尺寸演化时出现）：已退役 ≥STAGING_POOL_COUNT 帧，close 安全。
                pooled.close()
            }
        }
        return D3D12GpuBuffer(
            GpuBuffer.USAGE_MAP_WRITE or GpuBuffer.USAGE_COPY_SRC, blockSize
        )
    }

    /** 把一组 CPU 缓冲写进一块 UPLOAD staging，再一次 CopyBufferRegion 进单个 GPU 缓冲（仅供 uploadGpu）。 */
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
     * 每次 submit() 由编码器调用：本帧缓冲入队，保持 [FRAMES_IN_FLIGHT] 帧存活（对齐 native 的
     * value-2 围栏等待）后释放最旧一帧；同时旋转 UBO ring，使新帧 uniform 不覆盖仍在读的旧帧。
     */
    fun rotate() {
        if (this.closed) {
            return
        }
        // 必须在 frame 入队**之前**归还：超出池容量的块会登记进本帧，随该帧延迟释放。
        recycleStagingBlocks()
        this.frames.addLast(this.frame)
        this.frame = ArrayList()
        while (this.frames.size > FRAMES_IN_FLIGHT) {
            for (buffer in this.frames.removeFirst()) {
                buffer.close()
            }
        }
        // 每帧切到下一路，确保不同帧的 uniform 数据不重叠。
        this.uboRingIdx = (this.uboRingIdx + 1) % UBO_RING_COUNT
        this.uboRingOffset = 0
    }

    /**
     * 把本帧入池块归还本路池并轮转 [stagingPoolIdx]：该路下次被取用是 [STAGING_POOL_COUNT] 帧后，
     * 晚于 [FRAMES_IN_FLIGHT]，复用时 GPU 早已执行完引用它的拷贝。超出 [MAX_POOL_BYTES_PER_POOL] 的不入池。
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
            IllusionLogger.debugMessage("transientMemory.close: current=${this.frame.size}"
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
        // 池内块与未归还的当前块/退役块不在 frames 队列里，必须显式释放。
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
        // ring 不归 frames 队列，必须显式释放；置 null 让之后的 allocateGpuMapped 立刻 NPE 而不是用野指针。
        for (i in 0 until UBO_RING_COUNT) {
            uboRing[i]?.close()
            uboRing[i] = null
        }
        if (IllusionLogger.debugEnabled()) {
            IllusionLogger.debugMessage("transientMemory.close: done")
        }
    }

    companion object {
        const val FRAMES_IN_FLIGHT: Int = 2
        /** allocateGpuMapped 的 ring 路数；每路 4KiB，足够多组 std140 uniform 块。 */
        private const val UBO_RING_COUNT: Int = 3
        private const val UBO_RING_BLOCK_SIZE: Long = 4096L

        /** staging 块基准大小（对齐官方 TransientBlockAllocator 的 512KiB）。 */
        private const val STAGING_BLOCK_SIZE: Long = 512L * 1024L

        /**
         * 块内子分配的最小对齐，必须 512（`D3D12_TEXTURE_DATA_PLACEMENT_ALIGNMENT`）：纹理上传以
         * `PLACED_FOOTPRINT.Offset = srcOffset` 直接 CopyTextureRegion，偏移只有 256 对齐会校验失败/内容错乱。
         */
        private const val MIN_BLOCK_ALIGNMENT: Long = 512L

        // staging 块池（UPLOAD heap）：3 路轮转，帧 N 用完的块归还 P[N%3]，最早帧 N+3 才再被取用
        // （3 > FRAMES_IN_FLIGHT=2，复用时 GPU 早已执行完帧 N 的拷贝），避免每帧新建 committed buffer。
        private const val STAGING_POOL_COUNT: Int = 3
        /** 单路池常驻字节上限：超出者不入池，改走 frame 延迟释放。 */
        private const val MAX_POOL_BYTES_PER_POOL: Long = 4L * 1024L * 1024L
        /** 超过该大小的单次上传块不入池，避免个别超大上传抬高常驻内存。 */
        private const val MAX_POOLED_BLOCK_SIZE: Long = 8L * 1024L * 1024L

        /** 映射失败后最多再换几块：同时坏两块意味着设备/内存问题，早点抛比刷屏好。 */
        private const val MAX_STAGING_MAP_RETRIES: Int = 1

        private fun roundUp(value: Long, alignment: Long): Long {
            return (value + alignment - 1L) / alignment * alignment
        }

    }
}