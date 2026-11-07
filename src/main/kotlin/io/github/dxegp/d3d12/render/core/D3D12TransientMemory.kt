package io.github.dxegp.d3d12.render.core

import io.github.dxegp.misc.IllusionLogger

import com.mojang.renderpearl.api.buffers.GpuBuffer
import com.mojang.renderpearl.api.buffers.GpuBufferSlice
import com.mojang.renderpearl.api.buffers.TransientMemory
import io.github.dxegp.d3d12.extern.MCDR_ABI
import java.nio.ByteBuffer
import java.util.ArrayList
import java.util.ArrayDeque
import java.util.Deque

class D3D12TransientMemory(private val ctx: Long, private val ensureListOpen: Runnable) : TransientMemory {
    private data class RetiredBuffer(val buffer: D3D12GpuBuffer, val fence: Long)

    private val frames: Deque<MutableList<RetiredBuffer>> = ArrayDeque()
    private var frame = mutableListOf<RetiredBuffer>()
    private var closed: Boolean = false

    private val stagingPools = List(STAGING_POOL_COUNT) { ArrayDeque<RetiredBuffer>() }
    /** 各池当前常驻字节数（与 [stagingPools] 一一对应，用于容量控制）。 */
    private val stagingPoolBytes: LongArray = LongArray(STAGING_POOL_COUNT)
    private var stagingPoolIdx: Int = 0
    private var stagingBlock: D3D12GpuBuffer? = null
    /** 当前块能否归还进池：超大块不入池，走 frame 生命周期释放。 */
    private var stagingBlockPooled: Boolean = false
    private var stagingBlockUsed: Long = 0L
    private var stagingBlockFence: Long = 0L
    /** 本帧「写满被替换下来」的入池块，[rotate] 时一并归还池。 */
    private val retiredPooledStagingBlocks = mutableListOf<RetiredBuffer>()

    private fun recordingFence(): Long {
        check(!this.closed) { "Transient memory is closed" }
        this.ensureListOpen.run()
        check(MCDR_ABI.D3D12IsListOpen(this.ctx)) { "Transient command list is closed" }
        return MCDR_ABI.D3D12GetFenceValue(this.ctx) + 1L
    }

    private fun register(buffer: D3D12GpuBuffer, fence: Long) {
        this.frame.add(RetiredBuffer(buffer, fence))
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
        val fence = recordingFence()
        val buffer = D3D12GpuBuffer(usage or GpuBuffer.USAGE_COPY_DST, size)
        this.register(buffer, fence)
        return buffer.slice()
    }

    override fun allocateGpuMapped(size: Long, alignment: Long,
        usage: @GpuBuffer.Usage Int, minimumAllocation: Long, elementSize: Long): GpuBufferSlice.MappedView {
        return allocateStaging(size, alignment, usage, minimumAllocation, elementSize)
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
            reportStagingMapFailure(offset, total, attempt, failure)
            if (attempt >= MAX_STAGING_MAP_RETRIES) {
                return Result.failure(IllegalStateException(
                    "staging block (${block.size()} bytes) could not be mapped at $offset+$total "
                        + "after ${attempt + 1} attempt(s)", failure))
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
            this.stagingBlockFence = 0L
            this.stagingBlockPooled = false
        }
        // 若已作为「写满」退役进待归还表，先摘出来，否则 rotate() 会把它还进池、下次又取到同一块。
        this.retiredPooledStagingBlocks.removeIf { it.buffer === block }
        try {
            block.close()
        } catch (t: Throwable) {
            IllusionLogger.warnFormat(
                "transientMemory: closing an unmappable staging block threw %s%n", t)
        }
    }

    /** 连续映射失败的计数：前 3 次逐条，之后每 64 次一行。 */
    private var stagingMapFailures = 0

    /** 映射失败取证行：前 3 次逐条，之后每 64 次一行（不刷屏）。 */
    private fun reportStagingMapFailure(offset: Long, total: Long,
        attempt: Int, failure: Throwable) {
        this.stagingMapFailures++
        if (this.stagingMapFailures > 3 && (this.stagingMapFailures and 63) != 0) {
            return
        }
        IllusionLogger.warnFormat(
            "staging map failed at %d+%d (attempt %d, total %d): %s",
            offset, total, attempt + 1, this.stagingMapFailures, failure.toString())
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
        check(!this.closed) { "Transient memory is closed" }
        require(size in 0L..Int.MAX_VALUE.toLong()) { "Invalid mapped allocation size" }
        require(alignment > 0L) { "Mapped allocation alignment must be positive" }
        val factor = MIN_BLOCK_ALIGNMENT / alignment.takeLowestOneBit().coerceAtMost(MIN_BLOCK_ALIGNMENT)
        require(alignment <= Long.MAX_VALUE / factor) { "Mapped allocation alignment overflow" }
        val align = alignment * factor
        val fence = recordingFence()
        if (this.stagingBlock == null) {
            this.stagingBlock = acquireStagingBlock(size, align)
        }
        var offset = roundUp(this.stagingBlockUsed, align)
        if (size > this.stagingBlock!!.size() - offset) {
            if (this.stagingBlockPooled) {
                this.retiredPooledStagingBlocks.add(RetiredBuffer(this.stagingBlock!!, this.stagingBlockFence))
            } else {
                this.register(this.stagingBlock!!, this.stagingBlockFence)
            }
            this.stagingBlock = acquireStagingBlock(size, align)
            offset = 0L
        }
        this.stagingBlockUsed = offset + size
        this.stagingBlockFence = fence
        return offset
    }

    private fun acquireStagingBlock(size: Long, align: Long): D3D12GpuBuffer {
        val blockSize = roundUp(size, align).coerceAtLeast(STAGING_BLOCK_SIZE)
        if (blockSize > MAX_POOLED_BLOCK_SIZE) {
            this.stagingBlockPooled = false
            return D3D12GpuBuffer(
                GpuBuffer.USAGE_MAP_WRITE or GpuBuffer.USAGE_COPY_SRC, blockSize
            )
        }
        this.stagingBlockPooled = true
        val pool = this.stagingPools[this.stagingPoolIdx]
        val candidates = pool.iterator()
        while (candidates.hasNext()) {
            val candidate = candidates.next()
            val buffer = candidate.buffer
            if (buffer.isClosed()) {
                candidates.remove()
                this.stagingPoolBytes[this.stagingPoolIdx] -= buffer.size()
                IllusionLogger.warnMessage("transient memory: pooled buffer is closed")
                continue
            }
            if (!MCDR_ABI.D3D12WaitForFence(this.ctx, candidate.fence, 0L)) continue
            candidates.remove()
            this.stagingPoolBytes[this.stagingPoolIdx] -= buffer.size()
            if (buffer.size() >= size) return buffer
            buffer.close()
        }
        return D3D12GpuBuffer(
            GpuBuffer.USAGE_MAP_WRITE or GpuBuffer.USAGE_COPY_SRC, blockSize
        )
    }

    /** 把一组 CPU 缓冲写进一块 UPLOAD staging，再一次 CopyBufferRegion 进单个 GPU 缓冲（仅供 uploadGpu）。 */
    private fun upload(data: MutableList<ByteBuffer>, usage: Int, total: Long): GpuBufferSlice {
        require(total != 0L) { "Cannot upload zero bytes" }
        val fence = recordingFence()
        val staging = D3D12GpuBuffer(
            GpuBuffer.USAGE_MAP_WRITE or GpuBuffer.USAGE_COPY_SRC, total
        )
        this.register(staging, fence)
        staging.map(0L, total, false, true).use { view ->
            val dst = view.data()
            for (buffer in data) {
                dst.put(buffer.duplicate())
            }
        }
        val gpu = D3D12GpuBuffer(usage or GpuBuffer.USAGE_COPY_DST, total)
        this.register(gpu, fence)
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
        val fence = recordingFence()
        val staging = D3D12GpuBuffer(
            GpuBuffer.USAGE_MAP_WRITE or GpuBuffer.USAGE_COPY_SRC, total
        )
        this.register(staging, fence)
        staging.map(0L, total, false, true).use { view ->
            val dst = view.data()
            for (buffer in data) {
                dst.put(buffer.duplicate())
            }
        }
        val gpu = D3D12GpuBuffer(usage or GpuBuffer.USAGE_COPY_DST, total)
        this.register(gpu, fence)
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

    fun rotate() {
        if (this.closed) {
            return
        }
        // 必须在 frame 入队**之前**归还：超出池容量的块会登记进本帧，随该帧延迟释放。
        recycleStagingBlocks()
        this.frames.addLast(this.frame)
        this.frame = ArrayList()
        while (this.frames.size > FRAMES_IN_FLIGHT) {
            val retired = this.frames.first
            val fence = retired.maxOfOrNull { it.fence } ?: 0L
            if (fence > 0L && !MCDR_ABI.D3D12WaitForFence(this.ctx, fence, 0L)) break
            for (block in this.frames.removeFirst()) {
                block.buffer.close()
            }
        }
    }

    private fun recycleStagingBlocks() {
        this.stagingBlock?.let { block ->
            if (this.stagingBlockPooled) {
                this.retiredPooledStagingBlocks.add(RetiredBuffer(block, this.stagingBlockFence))
            } else {
                this.register(block, this.stagingBlockFence)
            }
        }
        val pool = this.stagingPools[this.stagingPoolIdx]
        var bytes = this.stagingPoolBytes[this.stagingPoolIdx]
        for (block in this.retiredPooledStagingBlocks) {
            if (bytes + block.buffer.size() <= MAX_POOL_BYTES_PER_POOL) {
                pool.offerLast(block)
                bytes += block.buffer.size()
            } else {
                this.frame.add(block)
            }
        }
        this.retiredPooledStagingBlocks.clear()
        this.stagingPoolBytes[this.stagingPoolIdx] = bytes
        this.stagingBlock = null
        this.stagingBlockUsed = 0L
        this.stagingBlockFence = 0L
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
        for (block in this.frame) {
            block.buffer.close()
        }
        this.frame = ArrayList()
        for (old in this.frames) {
            for (block in old) {
                block.buffer.close()
            }
        }
        this.frames.clear()
        // 池内块与未归还的当前块/退役块不在 frames 队列里，必须显式释放。
        for (pool in this.stagingPools) {
            for (block in pool) {
                block.buffer.close()
            }
            pool.clear()
        }
        this.stagingBlock?.close()
        for (block in this.retiredPooledStagingBlocks) {
            block.buffer.close()
        }
        this.retiredPooledStagingBlocks.clear()
        this.stagingBlock = null
        if (IllusionLogger.debugEnabled()) {
            IllusionLogger.debugMessage("transientMemory.close: done")
        }
    }

    companion object {
        const val FRAMES_IN_FLIGHT: Int = 2
        /** staging 块基准大小（对齐官方 TransientBlockAllocator 的 512KiB）。 */
        private const val STAGING_BLOCK_SIZE: Long = 512L * 1024L

        /**
         * 块内子分配的最小对齐，必须 512（`D3D12_TEXTURE_DATA_PLACEMENT_ALIGNMENT`）：纹理上传以
         * `PLACED_FOOTPRINT.Offset = srcOffset` 直接 CopyTextureRegion，偏移只有 256 对齐会校验失败/内容错乱。
         */
        private const val MIN_BLOCK_ALIGNMENT: Long = 512L

        private const val STAGING_POOL_COUNT: Int = 3
        /** 单路池常驻字节上限：超出者不入池，改走 frame 延迟释放。 */
        private const val MAX_POOL_BYTES_PER_POOL: Long = 4L * 1024L * 1024L
        /** 超过该大小的单次上传块不入池，避免个别超大上传抬高常驻内存。 */
        private const val MAX_POOLED_BLOCK_SIZE: Long = 8L * 1024L * 1024L

        /** 映射失败后最多再换几块：同时坏两块意味着设备/内存问题，早点抛比刷屏好。 */
        private const val MAX_STAGING_MAP_RETRIES: Int = 1

        private fun roundUp(value: Long, alignment: Long): Long {
            val remainder = value % alignment
            val padding = if (remainder == 0L) 0L else alignment - remainder
            require(value <= Long.MAX_VALUE - padding) { "Mapped allocation size overflow" }
            return value + padding
        }

    }
}
