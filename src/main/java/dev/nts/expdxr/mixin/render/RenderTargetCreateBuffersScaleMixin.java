package dev.nts.expdxr.mixin.render;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.GpuTexture;
import java.util.function.Supplier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

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
 * <p><b>现状：挂钩点已就位，缩放动作尚未接上。</b>
 * {@link #dxr_mc$scaleBuffersDimension(int)} 目前是恒等实现（原样返回入参），
 * 所以本 mixin 对任何 {@link RenderTarget} 都是零副作用的。要真正修好上面那个
 * 「窗口变化后 render scale 失效」，需要在处理器里补上「这次分配的是不是主目标」的判断
 * （{@code createBuffers} 同时也是 {@code D3D12GUIRenderTarget} 与其它 vanilla SCREEN_SIZE
 * 目标的分配路径，这些都必须保持窗口尺寸），再委托
 * {@code D3D12RenderScale.scaledForAttachment(int)}。
 *
 * <p>注入方式：一个 {@code @WrapOperation} 覆盖 {@code createBuffers} 里
 * depth / color 两次 {@code createTexture(...)} 调用（原先是 width/height 两个
 * {@code @ModifyArg(index = 3/4)}），宽高在这里成对改写，不再依赖实参下标。
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
        return original.call(device, label, usage, format,
                dxr_mc$scaleBuffersDimension(width), dxr_mc$scaleBuffersDimension(height),
                depthOrLayers, mipLevels);
    }

    @Unique private int dxr_mc$scaleBuffersDimension(int value) { return value; }
}
