package dev.nts.expdxr.d3d12.render.advanced.terrain

import com.mojang.renderpearl.api.textures.AddressMode
import com.mojang.renderpearl.api.textures.FilterMode
import com.mojang.renderpearl.api.textures.GpuSampler
import dev.nts.expdxr.d3d12.render.adapter.D3D12AdapterInfo
import dev.nts.expdxr.d3d12.render.advanced.vrs.D3D12RenderScale
import dev.nts.expdxr.d3d12.render.shader.D3D12GpuSampler
import dev.nts.expdxr.dxrmc.sr.dlss.DeepLearningSuperSampling
import dev.nts.expdxr.dxrmc.sr.SuperResolution
import java.util.OptionalDouble
import kotlin.math.log2

/**
 * 地形图集专用的 mip LOD bias：`log2(renderScale) - 0.5` 夹到 [-16, 0]，只在超分开启时生效，否则必须为 0。
 * 只挂地形采样器，不能改全局 sampler cache（GUI / 实体 / 字体会一起糊）。见 docs/mesh&vrs/README.md §4.1。
 */
object D3D12TerrainSampler {
    private data class Key(
        val addressModeU: AddressMode,
        val addressModeV: AddressMode,
        val minFilter: FilterMode,
        val magFilter: FilterMode,
        val maxAnisotropy: Int,
        val maxLod: OptionalDouble,
        val bias: Float,
    )

    private var cachedKey: Key? = null
    private var cachedSampler: GpuSampler? = null

    /** 旧采样器不能立刻 close：上一帧的命令列表可能还在引用它的描述符槽位。 */
    private val retired = ArrayList<GpuSampler>()

    /** 换成带 bias 的采样器；不需要 bias 时原样返回 [original]（对象身份不变）。 */
    @JvmStatic
    fun wrap(original: GpuSampler?): GpuSampler? {
        if (original == null) return null
        if (D3D12AdapterInfo.current == null) return original
        val bias = currentBias()
        if (bias == 0f) return original
        val key = Key(original.addressModeU, original.addressModeV, original.minFilter, original.magFilter,
            original.maxAnisotropy, original.maxLod, bias)
        cachedSampler?.let { if (cachedKey == key) return it }
        cachedSampler?.let { retired.add(it) }
        val created = D3D12GpuSampler(
            original.addressModeU, original.addressModeV, original.minFilter, original.magFilter,
            original.maxAnisotropy, original.maxLod, bias)
        cachedKey = key
        cachedSampler = created
        return created
    }

    /** 关掉所有本模组持有的地形采样器（设备关闭时）。 */
    @JvmStatic
    fun release() {
        cachedKey = null
        cachedSampler?.close()
        cachedSampler = null
        for (sampler in retired) {
            sampler.close()
        }
        retired.clear()
    }

    /** 当前应使用的 bias；不超分时恒为 0。 */
    @JvmStatic
    fun currentBias(): Float {
        if (D3D12AdapterInfo.current == null) return 0f
        // 闸门是"任何一家超分"而不是只有 DLSS：FSR / XeSS 的输入同样是降分辨率的世界层。
        if (!SuperResolution.isEnabled()) return 0f
        val scale = D3D12RenderScale.current() / 100.0
        if (!(scale > 0.0 && scale <= 1.0)) return 0f
        // 1:1（DLAA）时 FSR / XeSS 那条路径不跑（它们拒绝输入输出同资源），只有 DLSS 需要 bias。
        if (scale >= 1.0 && !DeepLearningSuperSampling.isEnabled()) return 0f
        return (log2(scale) - 0.5).toFloat().coerceIn(-16f, 0f)
    }
}
