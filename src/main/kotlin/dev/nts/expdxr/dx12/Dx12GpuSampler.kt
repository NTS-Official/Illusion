package dev.nts.expdxr.dx12

import com.mojang.blaze3d.textures.AddressMode
import com.mojang.blaze3d.textures.FilterMode
import com.mojang.blaze3d.textures.GpuSampler
import java.util.OptionalDouble

/**
 * D3D12-backed {@link GpuSampler}.
 *
 * Native mapping (see dx12_mc.dll {@code dx12CreateSampler}):
 * address 0=REPEAT/1=CLAMP_TO_EDGE, filter 0=NEAREST/1=LINEAR.
 */
class Dx12GpuSampler(
    private val addressModeU: AddressMode,
    private val addressModeV: AddressMode,
    private val minFilter: FilterMode,
    private val magFilter: FilterMode,
    private val maxAnisotropy: Int,
    private val maxLod: OptionalDouble,
) : GpuSampler() {
    private val handle: Long
    private var closed = false

    init {
        val lod = maxLod.orElse(16.0).toFloat()
        this.handle = McdrAbi.dx12CreateSampler(addressModeU.ordinal, addressModeV.ordinal,
            minFilter.ordinal, magFilter.ordinal, maxAnisotropy, lod)
        if (this.handle == 0L) {
            throw IllegalStateException("dx12CreateSampler returned a null handle")
        }
    }

    /** Native handle ({@code Dx12Object*} as long). */
    fun handle(): Long {
        return this.handle
    }

    override fun getAddressModeU(): AddressMode {
        return this.addressModeU
    }

    override fun getAddressModeV(): AddressMode {
        return this.addressModeV
    }

    override fun getMinFilter(): FilterMode {
        return this.minFilter
    }

    override fun getMagFilter(): FilterMode {
        return this.magFilter
    }

    override fun getMaxAnisotropy(): Int {
        return this.maxAnisotropy
    }

    override fun getMaxLod(): OptionalDouble {
        return this.maxLod
    }

    override fun close() {
        if (this.closed) {
            return
        }
        this.closed = true
        McdrAbi.dx12DestroyResource(this.handle)
    }
}