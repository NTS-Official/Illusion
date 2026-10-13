package dev.nts.expdxr.mixin.render;

import dev.nts.expdxr.d3d12.render.adapter.D3D12RenderProf;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** P51：地形顶点/索引每帧上传到 GPU 的耗时（可能是同步气泡）。 */
@Mixin(SectionRenderDispatcher.class)
public class SectionRenderDispatcherProfMixin {

    @Inject(method = "uploadTerrainBuffersToGpu", at = @At("HEAD"), remap = false)
    private void dx12_profUploadTerrainHead(CallbackInfo ci) {
        D3D12RenderProf.begin(D3D12RenderProf.UPLOAD_TERRAIN);
    }

    @Inject(method = "uploadTerrainBuffersToGpu", at = @At("RETURN"), remap = false)
    private void dx12_profUploadTerrainReturn(CallbackInfo ci) {
        D3D12RenderProf.end(D3D12RenderProf.UPLOAD_TERRAIN);
    }
}
