package dev.nts.expdxr.mixin.render;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.mojang.blaze3d.pipeline.RenderTarget;
import dev.nts.expdxr.d3d12.render.advanced.smaa.D3D12GUISplit;
import net.minecraft.client.renderer.CubeMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * P106：标题界面的全景图也要画到 GUI 自己的目标上。
 *
 * CubeMap.render()（CubeMap.java:53）直接取 GameRenderer.mainRenderTarget() 开 render pass，
 * 而它是在 GuiRenderer.render() 的开头调用的 —— 比 GuiRendererTargetMixin 换目标的那一刻
 * 还早。不拦这一处，全景图就会画进主渲染目标、而界面画进 GUI 目标，标题界面只剩一片底色。
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
