package dev.nts.expdxr.mixin.render;

import com.mojang.blaze3d.textures.GpuSampler;
import dev.nts.expdxr.d3d12.render.adapter.D3D12RenderProf;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** P51：区块实际绘制 pass（OPAQUE + TRANSLUCENT 合计），含 multidraw 批量提交。 */
@Mixin(ChunkSectionsToRender.class)
public class ChunkSectionsToRenderProfMixin {
    @Inject(method = "renderGroup", at = @At("HEAD"), remap = false)
    private void D3D12ProfRenderGroupHead(ChunkSectionLayerGroup group, GpuSampler sampler, CallbackInfo ci) {
        D3D12RenderProf.begin(D3D12RenderProf.RENDER_GROUP);
    }

    @Inject(method = "renderGroup", at = @At("RETURN"), remap = false)
    private void D3D12ProfRenderGroupReturn(ChunkSectionLayerGroup group, GpuSampler sampler, CallbackInfo ci) {
        D3D12RenderProf.end(D3D12RenderProf.RENDER_GROUP);
    }
}
