package dev.nts.expdxr.d3d12.render.backend

import com.mojang.blaze3d.systems.GpuQueryPool
import dev.nts.expdxr.d3d12.extern.MCDR_ABI
import java.util.OptionalLong

/**
 * D3D12-backed {@link GpuQueryPool} (timestamp queries).
 *
 * Native counterpart: {@code dx12CreateQueryPool} / {@code dx12ReadQueryValue} /
 * {@code dx12ReadQueryValues}. Reads are blocking: the native side resolves the
 * heap into a readback buffer, submits it and waits for completion.
 */
class D3D12GpuQueryPool(size: Int) : GpuQueryPool {
    private val handle: Long
    private val size: Int
    private var closed = false

    init {
        this.handle = MCDR_ABI.dx12CreateQueryPool(size)
        if (this.handle == 0L) {
            throw IllegalStateException("dx12CreateQueryPool returned a null handle")
        }
        this.size = size
    }

    /** Native handle ({@code QueryPool*} as long). */
    fun nativeHandle(): Long {
        return this.handle
    }

    override fun size(): Int {
        return this.size
    }

    override fun getValue(index: Int): OptionalLong {
        this.ensureRange(index, 1)
        return OptionalLong.of(MCDR_ABI.dx12ReadQueryValue(this.handle, index))
    }

    override fun getValues(index: Int, count: Int): Array<OptionalLong> {
        this.ensureRange(index, count)
        val values = LongArray(count)
        MCDR_ABI.dx12ReadQueryValues(this.handle, index, count, values)
        val result = Array(count) { OptionalLong.empty() }
        for (i in 0 until count) {
            result[i] = OptionalLong.of(values[i])
        }
        return result
    }

    private fun ensureRange(index: Int, count: Int) {
        if (index < 0 || count < 0 || index + count > this.size) {
            throw IndexOutOfBoundsException(
                "Query range " + index + ".." + (index + count) + " exceeds pool size " + this.size)
        }
    }

    override fun close() {
        if (this.closed) {
            return
        }
        this.closed = true
        MCDR_ABI.dx12DestroyQueryPool(this.handle)
    }
}