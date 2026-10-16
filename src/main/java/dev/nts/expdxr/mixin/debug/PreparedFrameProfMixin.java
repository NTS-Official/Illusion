package dev.nts.expdxr.mixin.debug;

import dev.nts.expdxr.d3d12.render.adapter.D3D12RenderProf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** P52：实体/方块实体等 feature 的实际绘制耗时（executeSolid）。 */
@Mixin(targets = "net.minecraft.client.renderer.feature.FeatureRenderDispatcher$PreparedFrame")
public class PreparedFrameProfMixin {

    @Inject(method = "executeSolid", at = @At("HEAD"), remap = false)
    private void D3D12ProfExecFeaturesHead(CallbackInfo ci) {
        D3D12RenderProf.begin(D3D12RenderProf.EXEC_FEATURES);
    }

    @Inject(method = "executeSolid", at = @At("RETURN"), remap = false)
    private void D3D12ProfExecFeaturesReturn(CallbackInfo ci) {
        D3D12RenderProf.end(D3D12RenderProf.EXEC_FEATURES);
    }
}
