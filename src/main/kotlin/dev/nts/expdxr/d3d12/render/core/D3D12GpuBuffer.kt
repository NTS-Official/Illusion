package dev.nts.expdxr.d3d12.render.core

import com.mojang.renderpearl.api.buffers.GpuBuffer
import com.mojang.renderpearl.api.buffers.GpuBufferSlice
import com.mojang.renderpearl.backend.common.BaseGpuBuffer
import dev.nts.expdxr.d3d12.extern.MCDR_ABI
import dev.nts.expdxr.d3d12.render.advanced.mesh.D3D12MeshSectionSnapshot
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicLong
/**
 * D3D12-backed {@link GpuBuffer}.
 *
 * Mirrors the official {@code VulkanGpuBuffer.Direct}: the native side picks the
 * D3D12 heap type from the usage flags (UPLOAD for MAP_WRITE, READBACK for
 * MAP_READ, DEFAULT otherwise) and {@link #map} returns a direct ByteBuffer over
 * the persistently mapped memory.
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


    // 26.3 起 `GpuBuffer` 的实现必须继承 `com.mojang.renderpearl.backend.common.BaseGpuBuffer`：
    // `FrontendRenderPass.validateDraw` 会直接把 draw 的 vertex/index buffer 强转成它
    // （不转就 ClassCastException）。size()/usage() 由基类按构造参数提供，这里不再 override。

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
        require(length <= Int.MAX_VALUE) { "Mapping buffer slice larger than 2GB is not supported" }
        val data: ByteBuffer = MCDR_ABI.D3D12MapBuffer(this.handle, offset, length, read, write)
            ?: throw IllegalStateException(mapFailureMessage(offset, length, read, write))
        // P26 fix：NewDirectByteBuffer 默认 BIG_ENDIAN，而 D3D12 期望小端字节序。
        // MC 的 DynamicUniformStorage.writeUniform 通过 Std140Builder.putFloat 写入
        // 该 buffer，若不加显式小端，UBO 内容会以 BIG_ENDIAN 写入（日志 rbBuf[ubo]
        // 读到 hex=[3F 80 00 00] 即大端 1.0f），HLSL 按小端读取后矩阵全垃圾，
        // 顶点被变换到裁剪区外 → colorTex 全黑。这里统一强制 LITTLE_ENDIAN。
        data.order(ByteOrder.LITTLE_ENDIAN)
        return GpuBufferSlice.MappedView(this.slice(offset, length), data
        ) {
            // P137：卸载前留一份映射区快照。mesh 段派发要把整批区块的 96 B `ChunkSection`
            // 拼成一个 ByteAddressBuffer（t16），而这条 vanilla uniform 环不允许事后做
            // GPU 读回（P108d：写映射读回会把整帧打成清屏色）。这里在 CPU 侧、数据失效
            // 之前抄一份，见 D3D12MeshSectionSnapshot。
            if ((this.usage() and GpuBuffer.USAGE_UNIFORM) != 0) {
                D3D12MeshSectionSnapshot.capture(this.handle, offset, length, data)
            }
            MCDR_ABI.D3D12UnmapBuffer(this.handle)
        }
    }

    companion object {
        private val G_CLOSE_LOG = java.util.concurrent.atomic.AtomicInteger()
        /**
         * P108z（2026-10-17 崩溃 `crash-2026-10-17_11.18.45-client.txt`）：`ID3D12Resource::Map` 失败
         * 时最常见、也最容易被误读的原因是**设备已被移除**（`DXGI_ERROR_DEVICE_REMOVED` =
         * `0x887A0005`）。它和"这个 buffer 有问题"完全不同：设备一死，**接下来每一个 GPU 调用都会
         * 失败**，而崩溃点只是恰好落在第一个 `Map` 上（那轮是 `DynamicUniformStorage.writeUniform`
         * 里一个 4 KB 的 UBO，`size=4096 / offset=0 / length=256 / closed=false`，全都没问题）。
         *
         * <p>所以这里单独识别它，把"设备已移除"这个事实、原始 HRESULT 与下一步该查什么写进异常和
         * 一行一次性 ERROR —— 否则它看起来就像"某个 buffer 映射失败"，会把人带向错误的排查方向。
         */
        private const val HRESULT_DEVICE_REMOVED: String = "887A0005"

        private const val HRESULT_DEVICE_HUNG: String = "887A0006"

        private const val HRESULT_DEVICE_RESET: String = "887A0007"

        /** 设备已移除的 ERROR 只打一次：之后每帧都会失败，重复打印只会淹没日志。 */
        @Volatile
        private var deviceLostReported: Boolean = false

        /** `native` 原文里是否带"设备已移除/挂起/重置"这类致命 HRESULT。 */
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
                "[backend-java] the D3D12 device has been REMOVED (DXGI_ERROR_DEVICE_REMOVED / "
                    + "0x887A0005). Every GPU call fails from here on, so the buffer that happened "
                    + "to call Map() first is NOT the problem. Look at the last GPU work submitted "
                    + "before this line (for the 2026-10-17 crash: the DLSS evaluate that started "
                    + "using a real depth snapshot, see the P108 lines around it). detail: $detail")
            System.err.flush()
            return true
        }
    }

    /**
     * P108z（用户 m11203）：`mcdr_dx12MapBuffer` 返回 null 时的**完整**报错。
     *
     * <p>native 的 `mapBuffer`（`MCDR\src\core\d3d12_device.cpp:2144-2172`）对**五种**情况都返回 0：
     * 句柄无效 / 缺 `MAP_WRITE` / 偏移越界 / 不是 UPLOAD|READBACK 堆 / 真正的 `ID3D12Resource::Map`
     * 失败。而 `mcdr::abi::failVoid("mcdr_dx12MapBuffer", r.error().message)`
     * （`MCDR\src\c_abi\abi_device.cpp`）已经把**具体是哪一种**写进了 `mcdrLastError`。
     * 改动前这里只抛一句 `"dx12MapBuffer returned null"`、把原文丢掉，于是"句柄失效"与"Map 失败"
     * 这两类根因在日志里无法区分 —— 那是 P108z 至今钉不死根因的直接原因。现在把
     * [MCDR_ABI.D3D12LastError] 的原文、句柄、偏移、长度、读写标志、关闭状态一起带上；取
     * `lastError` 本身失败（DLL 未载入等）也不影响原先的抛错。
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
        // 设备已移除：把"这个 buffer 没问题、整条 GPU 都停了"这件事写进异常本身，
        // 并只打一行 ERROR。见 companion 里 [isDeviceLost] 的说明。
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
        // P199h（临时取证）：谁在关缓冲、关了几次。
        if (label.contains("Screenshot") || (usageFlags and 8 != 0 && G_CLOSE_LOG.getAndIncrement() < 4)) {
            System.err.println("[P199h] GpuBuffer.close label='$label' size=$bufferSize")
        }
        MCDR_ABI.ID3D12DestroyResource(this.handle)
    }

    override fun isClosed(): Boolean {
        return this.closed
    }
}