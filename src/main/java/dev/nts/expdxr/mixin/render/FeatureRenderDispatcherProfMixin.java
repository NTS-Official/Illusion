package dev.nts.expdxr.mixin.render;

import dev.nts.expdxr.d3d12.render.adapter.D3D12RenderProf;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 实体/方块实体提交（feature）准备路径耗时：排序 + prepare + 共享顶点缓冲上传。 */
@Mixin(FeatureRenderDispatcher.class)
public class FeatureRenderDispatcherProfMixin {
    @Inject(method = "prepareFrame", at = @At("HEAD"), remap = false)
    private void D3D12ProfPrepareFeaturesHead(SubmitNodeStorage submitNodeStorage,
                                              CallbackInfoReturnable<FeatureRenderDispatcher.PreparedFrame> cir) {
        D3D12RenderProf.begin(D3D12RenderProf.PREPARE_FEATURES);
    }

    @Inject(method = "prepareFrame", at = @At("RETURN"), remap = false)
    private void D3D12ProfPrepareFeaturesReturn(SubmitNodeStorage submitNodeStorage,
                                                CallbackInfoReturnable<FeatureRenderDispatcher.PreparedFrame> cir) {
        D3D12RenderProf.end(D3D12RenderProf.PREPARE_FEATURES);
    }
}
