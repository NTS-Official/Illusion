package dev.nts.expdxr.d3d12.render.core

import com.mojang.renderpearl.api.textures.GpuTexture
import com.mojang.renderpearl.api.textures.GpuTextureView
import com.mojang.renderpearl.backend.common.BaseGpuTextureView
import dev.nts.expdxr.d3d12.extern.MCDR_ABI

/**
 * D3D12-backed {@link GpuTextureView} (an SRV over a {@link D3D12GpuTexture}).
 *
 * <p>26.3 起继承 {@code com.mojang.renderpearl.backend.common.BaseGpuTextureView}：
 * 前端会做同款强转，纯接口实现会在首帧绘制时 ClassCastException。
 */
class D3D12GpuTextureView(private val tex: GpuTexture, private val baseMip: Int, private val levelCount: Int) :
    BaseGpuTextureView(tex, baseMip, levelCount) {
    private var handle: Long
    private var closed = false

    init {
        val dx12Texture = tex as D3D12GpuTexture
        // 镜像 VulkanGpuTextureView：构造时注册 view 引用，close() 时 removeViews
        dx12Texture.addViews()
        this.handle = MCDR_ABI.ID3D12CreateTextureView(dx12Texture.handle(), baseMip, levelCount)
        if (this.handle == 0L) {
            throw IllegalStateException("dx12CreateTextureView returned a null handle")
        }
    }

    // texture()/baseMipLevel()/mipLevels()/getWidth()/getHeight() 由基类按构造参数提供；
    // 这里只保留 render pass 附件绑定用的 handle()/baseMip() 与生命周期。

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
        (this.texture() as D3D12GpuTexture).removeViews()
        MCDR_ABI.ID3D12DestroyResource(this.handle)
        this.handle = 0L
    }

    override fun isClosed(): Boolean {
        return this.closed
    }
}