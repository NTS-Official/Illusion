package dev.nts.expdxr.mixin.render;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.mojang.blaze3d.pipeline.RenderTarget;
import dev.nts.expdxr.d3d12.render.advanced.smaa.D3D12GUISplit;
import net.minecraft.client.renderer.CubeMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 标题界面的全景图也要画到 GUI 自己的目标上。
 *
 * <p>`CubeMap.render()` 比换目标那一刻更早；不拦这处，全景图进主目标、界面进 GUI 目标，标题界面
 * 只剩一片底色。
 */
@Mixin(CubeMap.class)
public class CubeMapTargetMixin {

    @ModifyExpressionValue(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/GameRenderer;" +
                    "mainRenderTarget()Lcom/mojang/blaze3d/pipeline/RenderTarget;"),
            remap = false)
    private RenderTarget dxr_mc$guiTargetForPanorama(RenderTarget original) {
        RenderTarget split = D3D12GUISplit.targetForFrame();
        return split != null ? split : original;
    }
}
