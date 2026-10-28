package dev.nts.expdxr.mixin.render;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.blaze3d.pipeline.MainTarget;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.GpuTexture;
import dev.nts.expdxr.d3d12.render.advanced.vrs.D3D12RenderScale;
import java.util.function.Supplier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 主渲染目标的颜色 / 深度附件按 `[render] renderScale` 分配。
 *
 * <p>只改 `createTexture` 的 width/height 实参，不动 `this.width/height`：那是原版回填的逻辑尺寸，
 * 逻辑尺寸一变，`GameRenderer` 每帧的 `windowRenderState.width != mainRenderTarget.width` 比较就会
 * 为真、每帧重建主目标。
 *
 * <p>**必须与 `RenderTargetCreateBuffersScaleMixin` 成对存在**：这条只覆盖构造路径，窗口尺寸变化走的是
 * 基类 `createBuffers`，少了那条一次 resize 就会还原成全窗口尺寸。
 * 见 docs/misc/mixins-and-earlywindow.md §3.4
 */
@Mixin(MainTarget.class)
public class MainTargetRenderScaleMixin {
    @WrapOperation(method = {"allocateColorAttachment", "allocateDepthAttachment"},
            at = @At(value = "INVOKE",
                    target = "Lcom/mojang/renderpearl/api/device/GpuDevice;" +
                            "createTexture(Ljava/util/function/Supplier;" +
                            "ILcom/mojang/renderpearl/api/GpuFormat;" +
                            "IIII)Lcom/mojang/renderpearl/api/textures/GpuTexture;"))
    private static GpuTexture dxr_mc$scaleAttachmentTexture(
            GpuDevice device, Supplier<String> label, int usage, GpuFormat format,
            int width, int height, int depthOrLayers, int mipLevels, Operation<GpuTexture> original) {
        return original.call(device, label, usage, format,
                dxr_mc$scaleAttachmentDimension(width), dxr_mc$scaleAttachmentDimension(height),
                depthOrLayers, mipLevels);
    }

    @Unique private static int dxr_mc$scaleAttachmentDimension(int value) { return D3D12RenderScale.scaledForAttachment(value);}
}
