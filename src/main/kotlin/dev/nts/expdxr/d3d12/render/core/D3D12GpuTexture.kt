package dev.nts.expdxr.d3d12.render.core

import com.mojang.renderpearl.api.GpuFormat
import com.mojang.renderpearl.api.textures.GpuTexture
import com.mojang.renderpearl.backend.common.BaseGpuTexture
import dev.nts.expdxr.d3d12.extern.MCDR_ABI

/**
 * D3D12 纹理（native `Dx12Object*`）。
 *
 * 必须继承 BaseGpuTexture：前端的 `validateDraw` / 资源表会强转成这个基类，纯接口实现会 ClassCastException。
 */
class D3D12GpuTexture(usage: Int, label: String, format: GpuFormat, width: Int,
                      height: Int, depthOrLayers: Int, mipLevels: Int) :
    BaseGpuTexture(usage, label, format, width, height, depthOrLayers, mipLevels) {
    private val usageFlags: Int = usage
    private val labelValue: String = label
    private val formatValue: GpuFormat = format
    private val widthValue: Int = width
    private val heightValue: Int = height
    private val layerCount: Int = depthOrLayers
    private val mipCount: Int = mipLevels
    private val handle: Long
    private var closed = false
    /** View 计数：close() 时 view 数为 0 才真正销毁，否则等最后一个 view 移除。 */
    private var views = 0

    init {
        this.handle = MCDR_ABI.ID3D12CreateTexture(usage, format.ordinal, width, height,
            depthOrLayers, mipLevels)
        if (this.handle == 0L) {
            throw IllegalStateException("dx12CreateTexture returned a null handle")
        }
    }

    override fun getWidth(level: Int): Int {
        return if (level <= 0) this.widthValue else (this.widthValue shr level).coerceAtLeast(1)
    }

    override fun getHeight(level: Int): Int {
        return if (level <= 0) this.heightValue else (this.heightValue shr level).coerceAtLeast(1)
    }

    override fun getDepthOrLayers(): Int {
        return this.layerCount
    }

    override fun getMipLevels(): Int {
        return this.mipCount
    }

    override fun getFormat(): GpuFormat {
        return this.formatValue
    }

    override fun getLabel(): String {
        return this.labelValue
    }

    override fun usage(): Int {
        return this.usageFlags
    }

    companion object {
        /**
         * 未映射到资源状态的 STORAGE / UAV 语义位（16 起是 DXRMC 自己的约定，这里取 32）。
         *
         * native 的 `textureFlags()` 判 `usage & 32` 才置 `ALLOW_UNORDERED_ACCESS`；
         * 缺这一位，NGX 不会写进这张资源。
         */
        const val USAGE_STORAGE: Int = 32
    }

    /** Native handle ({@code Dx12Object*} as long). */
    fun handle(): Long {
        return this.handle
    }

    /** 每个 TextureView 创建时 +1。 */
    fun addViews() {
        ++this.views
    }

    /** 关闭 TextureView 时 -1；texture 已 close 且无残留 view 时才真正销毁。 */
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