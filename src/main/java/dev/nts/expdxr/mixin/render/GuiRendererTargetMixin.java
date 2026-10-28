package dev.nts.expdxr.mixin.render;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.mojang.blaze3d.pipeline.RenderTarget;
import dev.nts.expdxr.d3d12.render.advanced.smaa.D3D12GUISplit;
import net.minecraft.client.gui.render.GuiRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 把界面的渲染目标从「与世界共用的主渲染目标」换成 GUI 自己的窗口尺寸目标。
 *
 * <p>`draw()` 只在开头取一次 `mainRenderTarget()`，后面两个 draw range、清深度、模糊都用这个局部变量，
 * 所以一处 `@ModifyExpressionValue` 就覆盖整段界面绘制。未启用时返回原值。
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
