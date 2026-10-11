package dev.nts.expdxr.dx12

import com.mojang.blaze3d.textures.GpuTexture
import com.mojang.blaze3d.textures.GpuTextureView

/**
 * D3D12-backed {@link GpuTextureView} (an SRV over a {@link Dx12GpuTexture}).
 */
class Dx12GpuTextureView(texture: GpuTexture, baseMipLevel: Int, mipLevels: Int) :
    GpuTextureView(texture, baseMipLevel, mipLevels) {
    private var handle: Long
    private var closed = false
    /** P3b：本 view 的 base mip（render pass 绑 RTV 时须用，图集逐级上传 mip）。 */
    private val baseMip: Int

    init {
        this.baseMip = baseMipLevel
        val dx12Texture = texture as Dx12GpuTexture
        // 镜像 VulkanGpuTextureView：构造时注册 view 引用，close() 时 removeViews
        dx12Texture.addViews()
        this.handle = McdrAbi.dx12CreateTextureView(dx12Texture.handle(), baseMipLevel, mipLevels)
        if (this.handle == 0L) {
            throw IllegalStateException("dx12CreateTextureView returned a null handle")
        }
    }

    /** Native handle ({@code Dx12Object*} as long). */
    fun handle(): Long {
        return this.handle
    }

    /** 该 view 指向的 base mip slice（render pass 附件 RTV/DSV 绑定用）。 */
    fun baseMip(): Int {
        return this.baseMip
    }

    override fun close() {
        if (this.closed) {
            return
        }
        this.closed = true
        // 镜像 VulkanGpuTextureView.close()：先通知 texture 移除本 view 引用，
        // 再销毁 native 层描述符（入 gPendingDeletes 延迟销毁，等 GPU 空闲后释放）。
        (this.texture() as Dx12GpuTexture).removeViews()
        McdrAbi.dx12DestroyResource(this.handle)
        this.handle = 0L
    }

    override fun isClosed(): Boolean {
        return this.closed
    }
}