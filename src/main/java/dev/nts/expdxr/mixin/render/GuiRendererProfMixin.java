package dev.nts.expdxr.mixin.render;

import dev.nts.expdxr.d3d12.render.adapter.D3D12RenderProf;
import net.minecraft.client.gui.render.GuiRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * P50：给 GuiRenderer.render 计时（GameRenderer.render 的子集）。
 */
@Mixin(GuiRenderer.class)
public class GuiRendererProfMixin {

    @Inject(method = "render", at = @At("HEAD"), remap = false)
    private void D3D12ProfGuiHead(CallbackInfo ci) {
        D3D12RenderProf.begin(D3D12RenderProf.GUI);
    }

    @Inject(method = "render", at = @At("RETURN"), remap = false)
    private void D3D12ProfGuiReturn(CallbackInfo ci) {
        D3D12RenderProf.end(D3D12RenderProf.GUI);
    }
}
