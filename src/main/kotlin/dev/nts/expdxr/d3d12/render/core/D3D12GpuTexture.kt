package dev.nts.expdxr.d3d12.render.core

import com.mojang.blaze3d.GpuFormat
import com.mojang.blaze3d.textures.GpuTexture
import dev.nts.expdxr.d3d12.extern.MCDR_ABI

/**
 * D3D12-backed {@link GpuTexture}.
 *
 * Native counterpart: {@code dx12CreateTexture} in dx12_mc.dll, which creates a
 * committed D3D12 texture resource and returns a handle ({@code Dx12Object*}).
 */
class D3D12GpuTexture(usage: Int, label: String, format: GpuFormat, width: Int,
                      height: Int, depthOrLayers: Int, mipLevels: Int) :
    GpuTexture(usage, label, format, width, height, depthOrLayers, mipLevels) {
    private val handle: Long
    private var closed = false
    /** View 计数器（镜像 VulkanGpuTexture.views）。close() 时若 view=0 则入延迟销毁。 */
    private var views = 0

    init {
        this.handle = MCDR_ABI.ID3D12CreateTexture(usage, format.ordinal, width, height,
            depthOrLayers, mipLevels)
        if (this.handle == 0L) {
            throw IllegalStateException("dx12CreateTexture returned a null handle")
        }
    }

    companion object {
        /**
         * P108z（用户 m11203）：**未映射到资源状态的 `storage / UAV` 语义位**。
         *
         * <p>vanilla 的 [GpuTexture] 只定义了 4 个位：`USAGE_COPY_DST = 1`、`USAGE_COPY_SRC = 2`、
         * `USAGE_TEXTURE_BINDING = 4`、`USAGE_RENDER_ATTACHMENT = 8`（`javap -constants` 实测），
         * 16 起是 DXRMC 自己的约定（`D3D12Velocity` 用 16 之后的位）。这里取 **32**，因为 native
         * `textureFlags()`（`MCDR\src\core\d3d12_device.cpp:1876-1892`）正好判 `usage & 32`：
         *
         * ```cpp
         * if (usage & 32) {  // STORAGE / UAV
         *     flags |= D3D12_RESOURCE_FLAG_ALLOW_UNORDERED_ACCESS;
         * }
         * ```
         *
         * 没有这个位，NGX 就不肯写进那张资源（"NGX 只肯写进带 ALLOW_UNORDERED_ACCESS 的资源"）。
         * 该位只影响 `createTexture` 一处，未用到它的纹理行为与改动前逐字一致。
         */
        const val USAGE_STORAGE: Int = 32
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
            MCDR_ABI.ID3D12DestroyResource(this.handle)
        }
    }

    override fun close() {
        if (this.closed) {
            return
        }
        this.closed = true
        if (this.views == 0) {
            MCDR_ABI.ID3D12DestroyResource(this.handle)
        }
    }

    override fun isClosed(): Boolean {
        return this.closed
    }
}