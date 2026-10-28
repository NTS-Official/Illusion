package dev.nts.expdxr.mixin.render;

import dev.nts.expdxr.d3d12.render.adapter.D3D12RenderProf;
import dev.nts.expdxr.misc.settings.ImprovedTransparencyLock;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * `Minecraft.renderFrame` 整体计时，帧尾触发分段报告。
 *
 * <p>必须用 RETURN 而不是 TAIL：renderFrame 在 surface 已 acquire 时会提前 return，RETURN 覆盖所有
 * 返回路径，保证 begin / end 严格配对。
 */
@Mixin(Minecraft.class)
public class MinecraftRenderFrameProfMixin {
    @Inject(method = "renderFrame", at = @At("HEAD"), remap = false)
    private void D3D12ProfFrameHead(boolean advanceGameTime, CallbackInfo ci) {
        ImprovedTransparencyLock.ensure();
        D3D12RenderProf.frameStart();
        D3D12RenderProf.begin(D3D12RenderProf.FRAME);
    }

    @Inject(method = "renderFrame", at = @At("RETURN"), remap = false)
    private void D3D12ProfFrameReturn(boolean advanceGameTime, CallbackInfo ci) {
        D3D12RenderProf.end(D3D12RenderProf.FRAME);
        D3D12RenderProf.frameEnd();
    }
}
