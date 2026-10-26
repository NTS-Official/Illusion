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
 * P220：**地形图集专用**的 mip LOD bias。
 *
 * <p>只有超分（DLSS 上采样 / DLAA）才需要负的 mip bias：渲染分辨率低于输出分辨率时，
 * 纹素覆盖的屏幕范围变大，不加 bias 会把纹理采样到更高的 mip，升采样后发糊。公式沿用
 * `known_bugs.md` P217 §二 记录的 `log2(render/display) - 0.5`。
 *
 * <p>**不使用超分时 bias 必须是 0**（用户 2026-10-25 要求）：普通渲染/仅改分辨率时，
 * 采样器与 vanilla 完全一致。bias 只挂在 `ChunkSectionsToRender` 的地形采样器参数上，
 * 不能改全局 sampler cache —— 否则 GUI、实体、字体一起糊。
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

    /**
     * 旧采样器不能立刻 close：上一帧的命令列表可能还在 GPU 上引用它的描述符槽位。
     * bias 变化很罕见，先留着，等设备关闭时统一释放。
     */
    private val retired = ArrayList<GpuSampler>()

    /**
     * 把 vanilla 的地形采样器换成带 bias 的版本；不需要 bias 时**原样返回** [original]
     * （对象身份不变，vanilla 的 sampler cache/生命周期继续生效）。
     */
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
        // P282：闸门从"DLSS 开着"扩到"任何一家超分开着" —— FSR / XeSS 的输入同样是降分辨率
        // 渲染的世界层，不加 bias 一样会在升采样后发糊。
        if (!SuperResolution.isEnabled()) return 0f
        val scale = D3D12RenderScale.current() / 100.0
        if (!(scale > 0.0 && scale <= 1.0)) return 0f
        // 1:1（DLAA）时 FSR / XeSS 那条路径不跑（它们拒绝输入输出同资源），只有 DLSS 需要 bias。
        if (scale >= 1.0 && !DeepLearningSuperSampling.isEnabled()) return 0f
        return (log2(scale) - 0.5).toFloat().coerceIn(-16f, 0f)
    }
}
