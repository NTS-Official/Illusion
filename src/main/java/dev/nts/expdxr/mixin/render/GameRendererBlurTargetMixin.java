package dev.nts.expdxr.mixin.render;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.mojang.blaze3d.pipeline.RenderTarget;
import dev.nts.expdxr.d3d12.render.advanced.smaa.D3D12GUISplit;
import net.minecraft.client.renderer.GameRenderer;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * GUI 背景模糊要作用在 GUI 自己的目标上。
 *
 * <p>拆分启用后主渲染目标里只有世界层；不换这里的字段读取，物品栏 / 暂停菜单这类「模糊背景 + 叠加
 * 界面」的界面就看不到模糊。`PostChain.process` 会把传入目标当作 "main" 导入、尺寸也取它的。
 */
@Mixin(GameRenderer.class)
public class GameRendererBlurTargetMixin {
    @ModifyExpressionValue(method = "processBlurEffect", at = @At(value = "FIELD",
                     target = "Lnet/minecraft/client/renderer/GameRenderer;" +
                             "mainRenderTarget:Lcom/mojang/blaze3d/pipeline/RenderTarget;",
                     opcode = Opcodes.GETFIELD), remap = false)
    private RenderTarget dxr_mc$guiTargetForBlur(RenderTarget original) {
        RenderTarget split = D3D12GUISplit.targetForFrame();
        return split != null ? split : original;
    }
}
