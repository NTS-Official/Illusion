package dev.nts.expdxr.mixin.render;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.blaze3d.pipeline.MainTarget;
import dev.nts.expdxr.d3d12.render.advanced.vrs.D3D12RenderScale;
import com.mojang.renderpearl.api.textures.GpuTexture;
import java.util.function.Supplier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * `RenderTarget#createBuffers` 是**窗口尺寸变化**时的重分配路径。
 *
 * <p>`MainTarget` 的构造只走 `allocateColorAttachment/allocateDepthAttachment`（见
 * `MainTargetRenderScaleMixin`），`resize` 走的是基类 `createBuffers` —— 少了这条，任何一次窗口尺寸
 * 变化都会把主目标还原成全窗口尺寸，render scale 静默失效。**只缩 `MainTarget`**：`createBuffers`
 * 同时给 GUI / 模糊等窗口尺寸目标分配纹理，跟着缩会让 GUI 的 scissor 越界。
 * 见 docs/misc/mixins-and-earlywindow.md §3.4
 */
@Mixin(RenderTarget.class)
public abstract class RenderTargetCreateBuffersScaleMixin {
    @WrapOperation(method = "createBuffers", at = @At(value = "INVOKE",
            target = "Lcom/mojang/renderpearl/api/device/GpuDevice;" +
                    "createTexture(Ljava/util/function/Supplier;" +
                    "ILcom/mojang/renderpearl/api/GpuFormat;" +
                    "IIII)Lcom/mojang/renderpearl/api/textures/GpuTexture;"))
    private GpuTexture dxr_mc$scaleBuffersTexture(
            GpuDevice device, Supplier<String> label, int usage, GpuFormat format,
            int width, int height, int depthOrLayers, int mipLevels, Operation<GpuTexture> original) {
        // 只缩 MainTarget：GUI/模糊/轮廓目标用的是窗口坐标，跟着缩会让 scissor 越界。
        final boolean scaleToRenderSize = (Object) this instanceof MainTarget;
        return original.call(device, label, usage, format,
                scaleToRenderSize ? dxr_mc$scaleBuffersDimension(width) : width,
                scaleToRenderSize ? dxr_mc$scaleBuffersDimension(height) : height,
                depthOrLayers, mipLevels);
    }

    /** 100%（默认）时恒等；非 100% 必须生效，否则 resize 后 DLSS 只能做 1:1 推理。 */
    @Unique private int dxr_mc$scaleBuffersDimension(int value) {
        return D3D12RenderScale.scaledForAttachment(value);
    }
}
