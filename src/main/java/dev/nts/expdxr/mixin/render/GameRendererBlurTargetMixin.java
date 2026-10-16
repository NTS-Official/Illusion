package dev.nts.expdxr.mixin.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import dev.nts.expdxr.d3d12.render.advanced.smaa.D3D12GUISplit;
import net.minecraft.client.renderer.GameRenderer;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * P106：GUI 背景模糊要作用在 GUI 自己的目标上。
 *
 * GameRenderer.processBlurEffect()（GameRenderer.java:232-237）直接用字段 this.mainRenderTarget
 * 跑 BLUR 后处理链 —— 拆分启用后主渲染目标里只有世界层，界面已经搬到 GUI 目标上了，于是
 * 「模糊背景 + 叠加界面」的那种界面（物品栏、暂停菜单）会看不到模糊。
 *
 * PostChain.process(RenderTarget, ...) 会把传进去的目标当作 "main" 导入
 * （PostChain.java:164-165）、尺寸也取它的，所以直接把字段读换成 GUI 目标是正确的：
 * 模糊就地作用在「世界层副本 + 模糊前界面」上，随后模糊后的界面元素再叠加。
 */
@Mixin(GameRenderer.class)
public class GameRendererBlurTargetMixin {

    @Redirect(method = "processBlurEffect",
            at = @At(value = "FIELD",
                     target = "Lnet/minecraft/client/renderer/GameRenderer;mainRenderTarget:Lcom/mojang/blaze3d/pipeline/RenderTarget;",
                     opcode = Opcodes.GETFIELD),
            remap = false)
    private RenderTarget dx12_guiTargetForBlur(GameRenderer self) {
        RenderTarget split = D3D12GUISplit.targetForFrame();
        return split != null ? split : self.mainRenderTarget();
    }
}