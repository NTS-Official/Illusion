package dev.nts.expdxr.mixin.render;

import com.mojang.blaze3d.pipeline.MainTarget;
import com.mojang.blaze3d.pipeline.RenderTarget;
import dev.nts.expdxr.d3d12.render.advanced.vrs.D3D12RenderScale;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * P105 补丁：{@link RenderTarget#createBuffers(int, int)} 才是**窗口尺寸变化**时的分配路径。
 *
 * <p>{@code MainTarget} 只在构造时走自己的 {@code createFrameBuffer} →
 * {@code allocateColorAttachment/allocateDepthAttachment}
 * （{@code MainTargetRenderScaleMixin} 挂在那里），而 {@code RenderTarget.resize(w, h)}
 * 走的是基类的 {@code destroyBuffers()} + {@code createBuffers(w, h)}，后者直接调
 * {@code device.createTexture(...)} —— 于是**任何一次窗口尺寸变化都会把主渲染目标悄悄
 * 还原成未缩放的全窗口尺寸**，render scale 静默失效且没有 P105 日志。
 *
 * <p>实机证据（2026-10-15 23:26 那局）：启动时窗口 854x480，主目标被正确分配成 640x360
 * （两行 P105 日志），随后窗口变成 2560x1334，主目标被 resize 回 2560x1334，
 * {@code P104: 世界层 SMAA 挂钩生效（世界 2560x1334）} 就是这么来的 —— 用户看到的
 * 与改动前完全一样。
 *
 * <p>只对 {@link MainTarget} 生效：界面自己的目标（{@code D3D12GUIRenderTarget}）、
 * 其它 vanilla 的 SCREEN_SIZE 目标都必须保持窗口尺寸。
 */
@Mixin(RenderTarget.class)
public abstract class RenderTargetCreateBuffersScaleMixin {

    @ModifyArg(
        method = "createBuffers",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/blaze3d/systems/GpuDevice;createTexture(Ljava/util/function/Supplier;ILcom/mojang/blaze3d/GpuFormat;IIII)Lcom/mojang/blaze3d/textures/GpuTexture;"
        ),
        index = 3
    )
    private int dxr_mc$scaleBuffersWidth(int width) {
        return dxr_mc$scaleBuffersDimension(width);
    }

    @ModifyArg(
        method = "createBuffers",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/blaze3d/systems/GpuDevice;createTexture(Ljava/util/function/Supplier;ILcom/mojang/blaze3d/GpuFormat;IIII)Lcom/mojang/blaze3d/textures/GpuTexture;"
        ),
        index = 4
    )
    private int dxr_mc$scaleBuffersHeight(int height) {
        return dxr_mc$scaleBuffersDimension(height);
    }

    private int dxr_mc$scaleBuffersDimension(int value) {
        // 只有主渲染目标参与缩放；界面/后处理辅助目标保持窗口尺寸（P106 的前提）。
        if (!((Object) this instanceof MainTarget)) {
            return value;
        }
        return D3D12RenderScale.scaledForAttachment(value);
    }
}