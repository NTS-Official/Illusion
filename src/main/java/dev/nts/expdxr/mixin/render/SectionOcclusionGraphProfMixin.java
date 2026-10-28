package dev.nts.expdxr.mixin.render;

import dev.nts.expdxr.d3d12.render.adapter.D3D12RenderProf;
import net.minecraft.client.renderer.SectionOcclusionGraph;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.ChunkLoadingRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 可见性与遮挡图每帧更新的耗时。 */
@Mixin(SectionOcclusionGraph.class)
public class SectionOcclusionGraphProfMixin {
    @Inject(method = "update", at = @At("HEAD"), remap = false)
    private void D3D12ProfOcclusionHead(CameraRenderState camera, int fov, ChunkLoadingRenderState chunkLoadingRenderState, CallbackInfo ci) {
        D3D12RenderProf.begin(D3D12RenderProf.OCCLUSION);
    }

    @Inject(method = "update", at = @At("RETURN"), remap = false)
    private void D3D12ProfOcclusionReturn(CameraRenderState camera, int fov, ChunkLoadingRenderState chunkLoadingRenderState, CallbackInfo ci) {
        D3D12RenderProf.end(D3D12RenderProf.OCCLUSION);
    }
}
