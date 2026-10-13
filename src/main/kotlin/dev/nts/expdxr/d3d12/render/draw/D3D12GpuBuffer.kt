package dev.nts.expdxr.d3d12.render.draw

import com.mojang.blaze3d.buffers.GpuBuffer
import com.mojang.blaze3d.buffers.GpuBufferSlice
import dev.nts.expdxr.d3d12.extern.MCDR_ABI
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * D3D12-backed {@link GpuBuffer}.
 *
 * Mirrors the official {@code VulkanGpuBuffer.Direct}: the native side picks the
 * D3D12 heap type from the usage flags (UPLOAD for MAP_WRITE, READBACK for
 * MAP_READ, DEFAULT otherwise) and {@link #map} returns a direct ByteBuffer over
 * the persistently mapped memory.
 */
class D3D12GpuBuffer(usage: @Usage Int, size: Long, private val label: String = "") : GpuBuffer(usage, size) {
    private val handle: Long
    private var closed = false

    init {
        this.handle = MCDR_ABI.dx12CreateBuffer(usage, size)
        if (this.handle == 0L) {
            throw IllegalStateException("dx12CreateBuffer returned a null handle")
        }
    }

    /** Native handle ({@code Dx12Object*} as long). */
    fun handle(): Long {
        return this.handle
    }

    /**
     * P75：创建时由 vanilla 传下来的 debug label（`Device.createBuffer(Supplier&lt;String&gt;, ...)`）。
     *
     * 后端需要它来**识别具体是哪个 UBO**，因为 vanilla 的 uniform 名字（`"Projection"`）不区分
     * 缓冲，而 `ProjectionMatrixBuffer` 用 label 区分关卡/3D-HUD 两套投影：
     * `"Camera projection matrix UBO level"` / `"Camera projection matrix UBO 3d hud"`。
     * 空串 = vanilla 没给 label（或本模组自建的缓冲）。
     */
    fun label(): String {
        return this.label
    }

    override fun map(offset: Long, length: Long, read: Boolean, write: Boolean): GpuBufferSlice.MappedView {
        if (length > Int.MAX_VALUE) {
            throw IllegalArgumentException("Mapping buffer slice larger than 2GB is not supported")
        }
        val data: ByteBuffer = MCDR_ABI.dx12MapBuffer(this.handle, offset, length, read, write)
            ?: throw IllegalStateException("dx12MapBuffer returned null")
        // P26 fix：NewDirectByteBuffer 默认 BIG_ENDIAN，而 D3D12 期望小端字节序。
        // MC 的 DynamicUniformStorage.writeUniform 通过 Std140Builder.putFloat 写入
        // 该 buffer，若不加显式小端，UBO 内容会以 BIG_ENDIAN 写入（日志 rbBuf[ubo]
        // 读到 hex=[3F 80 00 00] 即大端 1.0f），HLSL 按小端读取后矩阵全垃圾，
        // 顶点被变换到裁剪区外 → colorTex 全黑。这里统一强制 LITTLE_ENDIAN。
        data.order(ByteOrder.LITTLE_ENDIAN)
        return GpuBufferSlice.MappedView(this.slice(offset, length), data
        ) { MCDR_ABI.dx12UnmapBuffer(this.handle) }
    }

    override fun close() {
        if (this.closed) {
            return
        }
        this.closed = true
        MCDR_ABI.dx12DestroyResource(this.handle)
    }

    override fun isClosed(): Boolean {
        return this.closed
    }
}
