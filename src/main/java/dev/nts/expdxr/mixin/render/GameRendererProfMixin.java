package dev.nts.expdxr.mixin.render;

import dev.nts.expdxr.d3d12.render.adapter.D3D12RenderProf;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 三段计时：`extract`（状态提取）/ `render`（命令录制）/ `renderLevel`（世界渲染，render 的子集）。 */
@Mixin(GameRenderer.class)
public class GameRendererProfMixin {
    @Inject(method = "extract", at = @At("HEAD"), remap = false)
    private void D3D12ProfExtractHead(DeltaTracker deltaTracker, boolean advanceGameTime, CallbackInfo ci) {
        D3D12RenderProf.begin(D3D12RenderProf.EXTRACT);
    }

    @Inject(method = "extract", at = @At("RETURN"), remap = false)
    private void D3D12ProfExtractReturn(DeltaTracker deltaTracker, boolean advanceGameTime, CallbackInfo ci) {
        D3D12RenderProf.end(D3D12RenderProf.EXTRACT);
    }

    @Inject(method = "render", at = @At("HEAD"), remap = false)
    private void D3D12ProfRenderHead(CallbackInfo ci) {
        D3D12RenderProf.begin(D3D12RenderProf.RENDER);
    }

    @Inject(method = "render", at = @At("RETURN"), remap = false)
    private void D3D12ProfRenderReturn(CallbackInfo ci) {
        D3D12RenderProf.end(D3D12RenderProf.RENDER);
    }

    @Inject(method = "renderLevel", at = @At("HEAD"), remap = false)
    private void D3D12ProfRenderLevelHead(CallbackInfo ci) {
        D3D12RenderProf.begin(D3D12RenderProf.WORLD);
    }

    @Inject(method = "renderLevel", at = @At("RETURN"), remap = false)
    private void D3D12ProfRenderLevelReturn(CallbackInfo ci) {
        D3D12RenderProf.end(D3D12RenderProf.WORLD);
    }
}
