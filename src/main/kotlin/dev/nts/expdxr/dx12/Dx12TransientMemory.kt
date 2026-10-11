package dev.nts.expdxr.dx12

import com.mojang.blaze3d.buffers.GpuBuffer
import com.mojang.blaze3d.buffers.GpuBufferSlice
import com.mojang.blaze3d.systems.TransientMemory
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import java.util.ArrayList
import java.util.ArrayDeque
import java.util.Deque

/**
 * D3D12-backed {@link TransientMemory}, mirroring the official
 * {@code VulkanTransientMemory} lifecycle.
 *
 * <p>P54：staging 上传路径改用「块分配器」而非每次分配都新建 D3D12 committed buffer。
 * 此前 {@code uploadStaging} 每次调用都会 {@code new Dx12GpuBuffer} 两次（一个 UPLOAD
 * staging + 一个 DEFAULT 目标），而 {@code CommandEncoder.writeToBuffer} 每帧调用数十次
 * → 每帧上百次 {@code dx12CreateBuffer}（P53 采样中 {@code McdrAbi.dx12CreateBuffer}
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
 */
class Dx12TransientMemory(private val ctx: Long, private val ensureListOpen: Runnable) : TransientMemory {
    private val frames: Deque<MutableList<Dx12GpuBuffer>> = ArrayDeque()
    private var frame: MutableList<Dx12GpuBuffer> = ArrayList()
    private var closed: Boolean = false

    // allocateGpuMapped ring buffer
    private val uboRing: Array<Dx12GpuBuffer?> = arrayOfNulls(UBO_RING_COUNT)
    /** 当前正在写入的 ring buffer 路索引。 */
    private var uboRingIdx: Int = 0
    /** 当前路的已用字节数（从 0 开始单调递增，达到 BLOCK_SIZE 时 rotate）。 */
    private var uboRingOffset: Long = 0

    private val stagingPools: List<ArrayDeque<Dx12GpuBuffer>> = listOf(
        ArrayDeque<Dx12GpuBuffer>(), ArrayDeque<Dx12GpuBuffer>(), ArrayDeque<Dx12GpuBuffer>())
    /** 各池当前常驻字节数（与 {@link #stagingPools} 一一对应，用于容量控制）。 */
    private val stagingPoolBytes: LongArray = LongArray(STAGING_POOL_COUNT)
    private var stagingPoolIdx: Int = 0
    /** 本帧正在填充的 staging 块；为空表示本帧尚未分配。 */
    private var stagingBlock: Dx12GpuBuffer? = null
    /** 当前 staging 块是否可以归还进池（超大块不入池，交由 frames 生命周期释放）。 */
    private var stagingBlockPooled: Boolean = false
    private var stagingBlockUsed: Long = 0L
    /**
     * 本帧内「写满后被替换下来」的入池块。{@link #stagingBlock} 只保存当前正在填充的
     * 那一块；一帧内若上传量超过单块容量会出现多块，其余块暂存于此，待 {@link #rotate()}
     * 一并归还池（超大块在分配时已登记进 frame，无需在此跟踪）。
     */
    private val retiredPooledStagingBlocks: MutableList<Dx12GpuBuffer> = ArrayList()

    init {
        // 预分配 3 路 UPLOAD 缓冲（同 MappableRingBuffer 构造时的 3 个 buffer）
        for (i in 0 until UBO_RING_COUNT) {
            uboRing[i] = Dx12GpuBuffer(
                GpuBuffer.USAGE_MAP_WRITE or GpuBuffer.USAGE_COPY_SRC, UBO_RING_BLOCK_SIZE)
            register(uboRing[i]!!)
        }
    }

    private fun register(buffer: Dx12GpuBuffer) {
        this.frame.add(buffer)
    }

    override fun allocateCpu(size: Long, alignment: Long, minimumAllocation: Long, elementSize: Long): ByteBuffer {
        if (size > Integer.MAX_VALUE) {
            throw IllegalArgumentException("allocateCpu larger than 2GB is not supported")
        }
        return ByteBuffer.allocateDirect(size.toInt())
    }

    override fun allocateStaging(size: Long, alignment: Long,
        usage: @GpuBuffer.Usage Int, minimumAllocation: Long, elementSize: Long): GpuBufferSlice.MappedView {
        val offset = allocateInStagingBlock(size, alignment)
        return this.stagingBlock!!.map(offset, size, false, true)
    }

    override fun allocateGpu(size: Long, alignment: Long,
        usage: @GpuBuffer.Usage Int, minimumAllocation: Long, elementSize: Long): GpuBufferSlice {
        val buffer = Dx12GpuBuffer(usage or GpuBuffer.USAGE_COPY_DST, size)
        this.register(buffer)
        return buffer.slice()
    }

    override fun allocateGpuMapped(size: Long, alignment: Long,
        usage: @GpuBuffer.Usage Int, minimumAllocation: Long, elementSize: Long): GpuBufferSlice.MappedView {
        // 镜像 VulkanTransientMemory.allocateGpuMapped：在 ring buffer 当前路中分配，
        // 超过BLOCK_SIZE时自动切换到下一路（rotate）。
        // 与 MappableRingBuffer 语义对齐：每帧 rotate() 后切换到新路径，旧路径供 GPU 读取。
        var remaining = UBO_RING_BLOCK_SIZE - uboRingOffset
        if (size > remaining) {
            // 当前路剩余空间不足，切换到下一路
            uboRingIdx = (uboRingIdx + 1) % UBO_RING_COUNT
            uboRingOffset = 0
            remaining = UBO_RING_BLOCK_SIZE
        }
        val buf = uboRing[uboRingIdx]!!
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
        if (total == 0L) {
            throw IllegalArgumentException("Cannot upload zero bytes")
        }
        if (McdrAbi.LOG_VERBOSE) {
            checkForNanInfinity(data, "uploadStaging")
        }
        val offset = allocateInStagingBlock(total, alignment)
        this.stagingBlock!!.map(offset, total, false, true).use { view ->
            val dst = view.data()
            for (buffer in data) {
                dst.put(buffer.duplicate())
            }
        }
        return this.stagingBlock!!.slice(offset, total)
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
            return ArrayList<GpuBufferSlice>()
        }
        if (McdrAbi.LOG_VERBOSE) {
            checkForNanInfinity(data, "multiUploadStaging")
        }
        // P54：每个输入在本帧 staging 块内独立占一段，全部返回块内切片。
        val result = ArrayList<GpuBufferSlice>(data.size)
        for (buffer in data) {
            val length = buffer.remaining()
            val offset = allocateInStagingBlock(length.toLong(), alignment)
            if (length > 0) {
                this.stagingBlock!!.map(offset, length.toLong(), false, true).use { view ->
                    view.data().put(buffer.duplicate())
                }
            }
            result.add(this.stagingBlock!!.slice(offset, length.toLong()))
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
        val align = Math.max(MIN_BLOCK_ALIGNMENT, alignment)
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
    private fun acquireStagingBlock(size: Long, align: Long): Dx12GpuBuffer {
        val blockSize = Math.max(STAGING_BLOCK_SIZE, roundUp(size, align))
        if (blockSize > MAX_POOLED_BLOCK_SIZE) {
            this.stagingBlockPooled = false
            val big = Dx12GpuBuffer(
                GpuBuffer.USAGE_MAP_WRITE or GpuBuffer.USAGE_COPY_SRC, blockSize)
            this.register(big)
            return big
        }
        this.stagingBlockPooled = true
        val pool = this.stagingPools[this.stagingPoolIdx]
        val pooled = pool.pollFirst()
        if (pooled != null) {
            this.stagingPoolBytes[this.stagingPoolIdx] -= pooled.size()
            if (pooled.size() >= size) {
                return pooled
            }
            // 池中块过小（仅在本路块尺寸演化时出现）：丢弃后新建更大块。此块已退役
            // ≥STAGING_POOL_COUNT 帧，GPU 早已执行完它的拷贝，close 安全。
            pooled.close()
        }
        return Dx12GpuBuffer(
            GpuBuffer.USAGE_MAP_WRITE or GpuBuffer.USAGE_COPY_SRC, blockSize)
    }

    /**
     * Upload a set of CPU buffers into a single GPU-side (DEFAULT heap) buffer:
     * write them into one UPLOAD staging buffer, then one CopyBufferRegion.
     *
     * <p>P54：此路径仅供 {@code uploadGpu}/{@code multiUploadGpu} 使用（MC 未调用），
     * 仍保持每次新建缓冲的简单实现。
     */
    private fun upload(data: MutableList<ByteBuffer>, usage: Int, total: Long): GpuBufferSlice {
        if (total == 0L) {
            throw IllegalArgumentException("Cannot upload zero bytes")
        }
        if (McdrAbi.LOG_VERBOSE) {
            checkForNanInfinity(data, "upload")
        }
        val staging = Dx12GpuBuffer(
            GpuBuffer.USAGE_MAP_WRITE or GpuBuffer.USAGE_COPY_SRC, total)
        this.register(staging)
        staging.map(0L, total, false, true).use { view ->
            val dst = view.data()
            for (buffer in data) {
                dst.put(buffer.duplicate())
            }
        }
        val gpu = Dx12GpuBuffer(usage or GpuBuffer.USAGE_COPY_DST, total)
        this.register(gpu)
        this.ensureListOpen.run()  // 帧外上传（资源重载）需先打开命令列表
        McdrAbi.dx12CopyBuffer(this.ctx, staging.handle(), 0L, gpu.handle(), 0L, total)
        return gpu.slice()
    }

    /** Shared-block variant of {@link #upload}: returns one slice per input buffer. */
    private fun multiUpload(data: MutableList<ByteBuffer>, usage: Int): MutableList<GpuBufferSlice> {
        var total = 0L
        for (buffer in data) {
            total += buffer.remaining().toLong()
        }
        if (total == 0L) {
            return ArrayList<GpuBufferSlice>()
        }
        if (McdrAbi.LOG_VERBOSE) {
            checkForNanInfinity(data, "multiUpload")
        }
        val staging = Dx12GpuBuffer(
            GpuBuffer.USAGE_MAP_WRITE or GpuBuffer.USAGE_COPY_SRC, total)
        this.register(staging)
        staging.map(0L, total, false, true).use { view ->
            val dst = view.data()
            for (buffer in data) {
                dst.put(buffer.duplicate())
            }
        }
        val gpu = Dx12GpuBuffer(usage or GpuBuffer.USAGE_COPY_DST, total)
        this.register(gpu)
        this.ensureListOpen.run()  // 帧外上传（资源重载）需先打开命令列表
        McdrAbi.dx12CopyBuffer(this.ctx, staging.handle(), 0L, gpu.handle(), 0L, total)

        val result = ArrayList<GpuBufferSlice>(data.size)
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
        if (McdrAbi.LOG_VERBOSE) {
            System.err.println("[dx12-java] transientMemory.close: current=" + this.frame.size
                + " queuedFrames=" + this.frames.size)
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
        if (McdrAbi.LOG_VERBOSE) {
            System.err.println("[dx12-java] transientMemory.close: done")
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
        // new Dx12GpuBuffer → 每帧至少一次 CreateCommittedResource（P53 采样中
        // dx12CreateBuffer 仍占 ~9%）。改为 3 路块池轮转：帧 N 用完的块归还到 P[N%3]，
        // 该池最早在帧 N+3 才再次被取用，天然保证「块被复用时 GPU 早已执行完帧 N 的
        // 拷贝」（3 > FRAMES_IN_FLIGHT=2，与既有 frames 队列的退役语义一致）。
        private const val STAGING_POOL_COUNT: Int = 3
        /** 单路池常驻字节上限：超出者不入池，改走 frame 生命周期延迟释放，防止常驻内存失控。 */
        private const val MAX_POOL_BYTES_PER_POOL: Long = 4L * 1024L * 1024L
        /** 超过该大小的单次上传块不入池，避免个别超大上传把常驻内存抬上去。 */
        private const val MAX_POOLED_BLOCK_SIZE: Long = 8L * 1024L * 1024L

        private fun roundUp(value: Long, alignment: Long): Long {
            return (value + alignment - 1L) / alignment * alignment
        }

        /**
         * 检查所有 input buffers 的 float 值是否含 NaN 或 Infinity。
         * 在上传到 GPU 之前调用，定位污染源。
         *
         * <p>注意：某些合法数据（如 self-test 的 0xFC,0xFD,0xFE,0xFF 字节模式）按
         * float 读恰为 NaN，因此这里仅提示一次，避免逐帧刷屏，也不应阻断上传。
         *
         * <p>P54：该扫描会遍历全部待上传 float，属上传热路径上的纯诊断开销，
         * 现仅在 {@code DX12_LOG_VERBOSE=1} 时执行。
         */
        private fun checkForNanInfinity(data: MutableList<ByteBuffer>, method: String) {
            if (nanWarned) return
            for (bi in data.indices) {
                val buf = data[bi]
                if (buf.remaining() < 4) continue
                // 使用 FloatBuffer 视图逐 float 检测（不修改原始 buffer position）
                val fb: FloatBuffer = buf.duplicate().order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                while (fb.hasRemaining()) {
                    val v = fb.get()
                    if (java.lang.Float.isNaN(v) || java.lang.Float.isInfinite(v)) {
                        System.err.printf("[dx12-java] NaN/Inf detected in %s: buffer[%d] floatIdx=%d value=%s (数据未修改；仅提示一次)%n",
                            method, bi, fb.position() - 1,
                            if (java.lang.Float.isNaN(v)) "NaN" else "Infinity")
                        System.err.flush()
                        nanWarned = true
                        return  // 只报一次
                    }
                }
            }
        }

        /** 进程内仅提示一次，避免合法字节模式（如 0xFC..0xFF）被反复误报刷屏。 */
        private var nanWarned: Boolean = false
    }
}
