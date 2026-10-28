package dev.nts.expdxr.d3d12.render.core

import com.mojang.renderpearl.api.buffers.GpuBufferSlice
import com.mojang.renderpearl.backend.common.BaseGpuBuffer
import dev.nts.expdxr.d3d12.extern.MCDR_ABI
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicLong
/**
 * D3D12 缓冲。heap 类型由 native 按 usage 选（MAP_WRITE→UPLOAD，MAP_READ→READBACK，其余 DEFAULT），
 * [map] 返回持久映射内存上的 direct ByteBuffer。
 */
class D3D12GpuBuffer(private val usageFlags: Int, private val bufferSize: Long, private val label: String = "") :
    BaseGpuBuffer(usageFlags, bufferSize) {
    private val handle: Long
    private var closed = false

    init {
        this.handle = MCDR_ABI.ID3D12CreateBuffer(usageFlags, bufferSize)
        if (this.handle == 0L) {
            throw IllegalStateException("dx12CreateBuffer returned a null handle")
        }
    }


    // 必须继承 BaseGpuBuffer：FrontendRenderPass.validateDraw 会把 vertex/index buffer 强转成它。

    /** Native handle ({@code Dx12Object*} as long). */
    fun handle(): Long {
        return this.handle
    }

    /**
     * vanilla 传下来的 debug label。后端靠它区分 UBO（`Projection` 这类 uniform 名不区分缓冲）；
     * 空串 = vanilla 没给 label，或本模组自建的缓冲。
     */
    fun label(): String {
        return this.label
    }

    override fun map(offset: Long, length: Long, read: Boolean, write: Boolean): GpuBufferSlice.MappedView {
        require(length <= Int.MAX_VALUE) { "Mapping buffer slice larger than 2GB is not supported" }
        val data: ByteBuffer = MCDR_ABI.D3D12MapBuffer(this.handle, offset, length, read, write)
            ?: throw IllegalStateException(mapFailureMessage(offset, length, read, write))
        // 必须强制 LITTLE_ENDIAN：direct ByteBuffer 默认 BIG_ENDIAN，UBO 会按大端写入、矩阵全垃圾。
        data.order(ByteOrder.LITTLE_ENDIAN)
        return GpuBufferSlice.MappedView(this.slice(offset, length), data
        ) {
            MCDR_ABI.D3D12UnmapBuffer(this.handle)
        }
    }

    companion object {
        private val G_CLOSE_LOG = java.util.concurrent.atomic.AtomicInteger()
        /**
         * `Map` 失败最常见的原因是设备已被移除（`0x887A0005`）：那一刻起每个 GPU 调用都会失败，
         * 崩溃点只是恰好落在第一个 `Map` 上。单独识别它，避免被误读成"某个 buffer 有问题"。
         */
        private const val HRESULT_DEVICE_REMOVED: String = "887A0005"

        private const val HRESULT_DEVICE_HUNG: String = "887A0006"

        private const val HRESULT_DEVICE_RESET: String = "887A0007"

        /** 设备已移除的 ERROR 只打一次：之后每帧都会失败，重复打印只会淹没日志。 */
        @Volatile
        private var deviceLostReported: Boolean = false

        /** 原文里是否带「设备已移除/挂起/重置」这类致命 HRESULT。 */
        fun isDeviceLost(native: String): Boolean {
            val upper = native.uppercase()
            return upper.contains(HRESULT_DEVICE_REMOVED) ||
                upper.contains(HRESULT_DEVICE_HUNG) ||
                upper.contains(HRESULT_DEVICE_RESET) ||
                upper.contains("DEVICE_REMOVED") ||
                upper.contains("DEVICE_HUNG") ||
                upper.contains("DEVICE_RESET")
        }

        /** 只在第一次看到"设备已移除"时打一行 ERROR，返回是否是第一次。 */
        fun reportDeviceLostOnce(detail: String): Boolean {
            if (deviceLostReported) {
                return false
            }
            deviceLostReported = true
            System.err.println(
                "the D3D12 device was removed (DXGI_ERROR_DEVICE_REMOVED / 0x887A0005); "
                    + "every GPU call fails from here on, so the buffer that called Map() first is "
                    + "not the cause. Check the last GPU work submitted before this line. detail: $detail")
            System.err.flush()
            return true
        }
    }

    /**
     * `mcdr_dx12MapBuffer` 返回 null 的完整报错：native 对五种情况都返回 0（句柄无效 / 缺 `MAP_WRITE` /
     * 偏移越界 / 不是 UPLOAD|READBACK 堆 / 真正 Map 失败），具体哪一种在 `mcdrLastError` 里，必须带上。
     */
    private fun mapFailureMessage(offset: Long, length: Long, read: Boolean, write: Boolean): String {
        val handleHex = this.handle.toULong().toString(16)
        val native = try {
            MCDR_ABI.D3D12LastError().ifEmpty { "(mcdrLastError is empty)" }
        } catch (t: Throwable) {
            "(could not read mcdrLastError: $t)"
        }
        val base = "dx12MapBuffer returned null (handle=0x$handleHex, size=$bufferSize, " +
            "closed=$closed, offset=$offset, length=$length, read=$read, write=$write, " +
            "native: $native)"
        if (!isDeviceLost(native)) {
            return base
        }
        // 设备已移除：把「这个 buffer 没问题、整条 GPU 停了」写进异常本身，并且只报一次。
        reportDeviceLostOnce(base)
        return base + " — this is NOT a problem with this buffer: the D3D12 device has been " +
            "REMOVED (DXGI_ERROR_DEVICE_REMOVED / 0x887A0005), so every GPU call fails from here " +
            "on. Check the last GPU work submitted before this line (for 2026-10-17: the DLSS " +
            "evaluate that first used a real depth snapshot)."
    }

    override fun close() {
        if (this.closed) {
            return
        }
        this.closed = true
        // 临时取证：谁在关缓冲、关了几次。
        if (label.contains("Screenshot") || (usageFlags and 8 != 0 && G_CLOSE_LOG.getAndIncrement() < 4)) {
            System.err.println("GpuBuffer.close label='$label' size=$bufferSize")
        }
        MCDR_ABI.ID3D12DestroyResource(this.handle)
    }

    override fun isClosed(): Boolean {
        return this.closed
    }
}