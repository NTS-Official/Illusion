package dev.nts.expdxr.mixin.render;

import com.mojang.renderpearl.api.textures.GpuSampler;
import dev.nts.expdxr.d3d12.render.advanced.terrain.D3D12TerrainSampler;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * P220：只在**地形**的采样器参数上应用超分 mip bias。
 *
 * <p>`ChunkSectionsToRender.renderGroup` / `renderOit` 是 vanilla 26.3 地形层的唯一入口，
 * `sampler` 来自 `LevelRenderer.chunkLayerSampler`（专用采样器）。在这里换成带 bias 的版本，
 * 既不会碰 GUI/实体共享的 sampler cache，也不必改 `LevelRenderer` 的采样器生命周期：
 * 不需要 bias 时 {@link D3D12TerrainSampler#wrap} 原样返回 vanilla 对象。
 */
@Mixin(ChunkSectionsToRender.class)
public abstract class ChunkSectionsToRenderSamplerMixin {
    @ModifyVariable(method = "renderGroup", at = @At("HEAD"), argsOnly = true)
    private GpuSampler dxr_mc$biasGroupSampler(GpuSampler sampler) {
        GpuSampler biased = D3D12TerrainSampler.wrap(sampler);
        return biased != null ? biased : sampler;
    }

    @ModifyVariable(method = "renderOit", at = @At("HEAD"), argsOnly = true)
    private GpuSampler dxr_mc$biasOitSampler(GpuSampler sampler) {
        GpuSampler biased = D3D12TerrainSampler.wrap(sampler);
        return biased != null ? biased : sampler;
    }
}

