package dev.nts.expdxr.mixin.render;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.mojang.blaze3d.pipeline.RenderTarget;
import dev.nts.expdxr.d3d12.render.advanced.smaa.D3D12GUISplit;
import net.minecraft.client.gui.render.GuiRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * P106：把界面的渲染目标从「与世界共用的主渲染目标」换成 GUI 自己的窗口尺寸目标。
 *
 * GuiRenderer.draw() 里只在开头取一次 minecraft.gameRenderer.mainRenderTarget()
 * （GuiRenderer.java:181），后面两个 draw range、清深度、模糊都用这个局部变量，
 * 所以一处 {@code @ModifyExpressionValue} 就覆盖了整段界面绘制。
 *
 * 未启用（renderScale == 100%，或 GUI 目标不可用）时返回原表达式的结果（主渲染目标本身），
 * 与 vanilla 完全一致。
 */
@Mixin(GuiRenderer.class)
public class GuiRendererTargetMixin {
    @ModifyExpressionValue(method = "draw", at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/renderer/GameRenderer;" +
                             "mainRenderTarget()Lcom/mojang/blaze3d/pipeline/RenderTarget;"),
            remap = false)
    private RenderTarget dxr_mc$GuiTargetInsteadOfMain(RenderTarget original) {
        RenderTarget split = D3D12GUISplit.targetForFrame();
        return split != null ? split : original;
    }
}
