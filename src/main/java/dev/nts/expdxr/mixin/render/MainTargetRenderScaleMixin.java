package dev.nts.expdxr.mixin.render;

import com.mojang.blaze3d.pipeline.MainTarget;
import dev.nts.expdxr.d3d12.render.advanced.vrs.D3D12RenderScale;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * P105：让主渲染目标按 {@code [render] renderScale} 分配纹理 —— 分辨率与画面着色解耦的第一步。
 *
 * <p>只改 {@code allocateColorAttachment} / {@code allocateDepthAttachment} 里
 * {@code createTexture(...)} 的 width/height 两个实参，因此：
 *
 * <ul>
 *   <li>{@code MainTarget.createFrameBuffer} 仍然用<b>未缩放</b>的
 *       {@code Dimension} 回填 {@code this.width/height}（那是循环里那个对象的字段，
 *       不是纹理的真实尺寸），于是 {@code GameRenderer} 每帧的
 *       {@code windowRenderState.width != mainRenderTarget.width} 比较依旧为假 ——
 *       不会每帧重建主目标。</li>
 *   <li>渲染 pass 的视口/裁剪取自 color/depth 附件的真实尺寸
 *       （{@code D3D12RenderPassBackend.outputWidth/outputHeight}），而 vanilla 主渲染路径
 *       从不调用 {@code RenderPass.setViewport} ⇒ 缩小后画面正好铺满整个小附件。</li>
 *   <li>P106 之后世界层由一次带线性采样的 texture→texture blit 铺满界面自己的窗口尺寸目标
 *       （{@code D3D12GUISplit}），升采样/降采样都在那一步完成。</li>
 * </ul>
 *
 * <p><b>必须与 {@code RenderTargetCreateBuffersScaleMixin} 成对存在</b>：这条只覆盖
 * {@code MainTarget} 的构造路径；窗口尺寸变化时 {@code RenderTarget.resize(w, h)} 走的是基类的
 * {@code createBuffers(w, h)}（直接调 {@code device.createTexture}），少了那一条，一次窗口变化
 * 就会把主目标还原成全窗口尺寸、render scale 静默失效（2026-10-15 实机踩到）。
 *
 * <p>100%（默认）时 {@link D3D12RenderScale#scaled(int)} 原样返回入参，
 * 因此这条 mixin 在默认配置下是完全无副作用的恒等变换。
 */
@Mixin(MainTarget.class)
public class MainTargetRenderScaleMixin {

    @ModifyArg(
        method = {"allocateColorAttachment", "allocateDepthAttachment"},
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/blaze3d/systems/GpuDevice;createTexture(Ljava/util/function/Supplier;ILcom/mojang/blaze3d/GpuFormat;IIII)Lcom/mojang/blaze3d/textures/GpuTexture;"
        ),
        index = 3
    )
    private int dxr_mc$scaleAttachmentWidth(int width) {
        return dxr_mc$scaleAttachmentDimension(width);
    }

    @ModifyArg(
        method = {"allocateColorAttachment", "allocateDepthAttachment"},
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/blaze3d/systems/GpuDevice;createTexture(Ljava/util/function/Supplier;ILcom/mojang/blaze3d/GpuFormat;IIII)Lcom/mojang/blaze3d/textures/GpuTexture;"
        ),
        index = 4
    )
    private int dxr_mc$scaleAttachmentHeight(int height) {
        return dxr_mc$scaleAttachmentDimension(height);
    }

    private static int dxr_mc$scaleAttachmentDimension(int value) {
        return D3D12RenderScale.scaledForAttachment(value);
    }
}
