package dev.nts.expdxr.dx12

import com.mojang.blaze3d.GpuFormat
import com.mojang.blaze3d.textures.GpuTexture

/**
 * D3D12-backed {@link GpuTexture}.
 *
 * Native counterpart: {@code dx12CreateTexture} in dx12_mc.dll, which creates a
 * committed D3D12 texture resource and returns a handle ({@code Dx12Object*}).
 */
class Dx12GpuTexture(usage: Int, label: String, format: GpuFormat, width: Int,
    height: Int, depthOrLayers: Int, mipLevels: Int) :
    GpuTexture(usage, label, format, width, height, depthOrLayers, mipLevels) {
    private val handle: Long
    private var closed = false
    /** View 计数器（镜像 VulkanGpuTexture.views）。close() 时若 view=0 则入延迟销毁。 */
    private var views = 0

    init {
        this.handle = McdrAbi.dx12CreateTexture(usage, format.ordinal, width, height,
            depthOrLayers, mipLevels)
        if (this.handle == 0L) {
            throw IllegalStateException("dx12CreateTexture returned a null handle")
        }
    }

    /** Native handle ({@code Dx12Object*} as long). */
    fun handle(): Long {
        return this.handle
    }

    /** 镜像 {@code VulkanGpuTexture.addViews}：每创建一个 TextureView 时调用。 */
    fun addViews() {
        ++this.views
    }

    /** 镜像 {@code VulkanGpuTexture.removeViews}：关闭 TextureView 时调用；
     * 若 texture 本身也已 close 且无残留 view，则入延迟销毁队列。 */
    fun removeViews() {
        --this.views
        if (this.views < 0) {
            throw IllegalStateException("Too many views removed from texture")
        }
        if (this.closed && this.views == 0) {
            McdrAbi.dx12DestroyResource(this.handle)
        }
    }

    override fun close() {
        if (this.closed) {
            return
        }
        this.closed = true
        if (this.views == 0) {
            McdrAbi.dx12DestroyResource(this.handle)
        }
    }

    override fun isClosed(): Boolean {
        return this.closed
    }
}