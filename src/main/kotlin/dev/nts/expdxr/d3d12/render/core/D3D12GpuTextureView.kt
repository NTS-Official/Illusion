package dev.nts.expdxr.d3d12.render.core

import com.mojang.renderpearl.api.textures.GpuTexture
import com.mojang.renderpearl.api.textures.GpuTextureView
import com.mojang.renderpearl.backend.common.BaseGpuTextureView
import dev.nts.expdxr.d3d12.extern.MCDR_ABI

/**
 * 对 [D3D12GpuTexture] 的 SRV。
 *
 * 必须继承 BaseGpuTextureView：前端会做同款强转，纯接口实现会在首帧绘制时 ClassCastException。
 */
class D3D12GpuTextureView(private val tex: GpuTexture, private val baseMip: Int, private val levelCount: Int) :
    BaseGpuTextureView(tex, baseMip, levelCount) {
    private var handle: Long
    private var closed = false

    init {
        val dx12Texture = tex as D3D12GpuTexture
        dx12Texture.addViews()
        this.handle = MCDR_ABI.ID3D12CreateTextureView(dx12Texture.handle(), baseMip, levelCount)
        if (this.handle == 0L) {
            throw IllegalStateException("dx12CreateTextureView returned a null handle")
        }
    }

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
        // 顺序要求：先移除 texture 上的引用，再销毁 native 描述符（进入延迟删除，等 GPU 空闲）。
        (this.texture() as D3D12GpuTexture).removeViews()
        MCDR_ABI.ID3D12DestroyResource(this.handle)
        this.handle = 0L
    }

    override fun isClosed(): Boolean {
        return this.closed
    }
}