package dev.nts.expdxr.mixin.render;

import dev.nts.expdxr.d3d12.render.adapter.D3D12RenderProf;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * P50：拆分 GameRenderer 的 extract（渲染状态提取）与 render（命令录制），
 * 并单独测量 renderLevel（世界渲染，render 的子集）。
 */
@Mixin(GameRenderer.class)
public class GameRendererProfMixin {

    @Inject(method = "extract", at = @At("HEAD"), remap = false)
    private void dx12_profExtractHead(DeltaTracker deltaTracker, boolean advanceGameTime, CallbackInfo ci) {
        D3D12RenderProf.begin(D3D12RenderProf.EXTRACT);
    }

    @Inject(method = "extract", at = @At("RETURN"), remap = false)
    private void dx12_profExtractReturn(DeltaTracker deltaTracker, boolean advanceGameTime, CallbackInfo ci) {
        D3D12RenderProf.end(D3D12RenderProf.EXTRACT);
    }

    @Inject(method = "render", at = @At("HEAD"), remap = false)
    private void dx12_profRenderHead(DeltaTracker deltaTracker, boolean advanceGameTime, CallbackInfo ci) {
        D3D12RenderProf.begin(D3D12RenderProf.RENDER);
    }

    @Inject(method = "render", at = @At("RETURN"), remap = false)
    private void dx12_profRenderReturn(DeltaTracker deltaTracker, boolean advanceGameTime, CallbackInfo ci) {
        D3D12RenderProf.end(D3D12RenderProf.RENDER);
    }

    @Inject(method = "renderLevel", at = @At("HEAD"), remap = false)
    private void dx12_profRenderLevelHead(DeltaTracker deltaTracker, CallbackInfo ci) {
        D3D12RenderProf.begin(D3D12RenderProf.WORLD);
    }

    @Inject(method = "renderLevel", at = @At("RETURN"), remap = false)
    private void dx12_profRenderLevelReturn(DeltaTracker deltaTracker, CallbackInfo ci) {
        D3D12RenderProf.end(D3D12RenderProf.WORLD);
    }
}
