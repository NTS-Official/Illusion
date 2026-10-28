package dev.nts.expdxr.mixin.render;

import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.frontend.FrontendGpuDevice;
import dev.nts.expdxr.d3d12.backend.D3D12PipelineBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * 把 vanilla 的 `RenderPipeline`（连同 GLSL 源）登记进 `D3D12PipelineBridge`。
 *
 * <p>本后端的编译链要 GLSL 源码与 `shaderDefines`，而 `GpuDeviceBackend.compilePipeline` 只收到
 * SPIR-V + 状态；`ShaderSource` 是逐次传入的，`createDevice` 已经不收它了 —— 只登记管线的话首帧
 * 编译拿不到源码。只读两个参数，不改变 vanilla 行为。
 */
@Mixin(FrontendGpuDevice.class)
public class FrontendGpuDeviceCompilePipelineMixin {
    @Inject(
        method = "compilePipeline(Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;"
            + "Lcom/mojang/renderpearl/api/pipeline/ShaderSource;Ljava/util/concurrent/Executor;)"
            + "Ljava/util/concurrent/CompletableFuture;",
        at = @At("HEAD"))
    private void dxr_mc$registerPipeline(RenderPipeline pipeline, ShaderSource shaderSource,
        Executor executor, CallbackInfoReturnable<CompletableFuture<CompiledRenderPipeline.Pending>> cir) {
        D3D12PipelineBridge.register(pipeline, shaderSource);
    }
}