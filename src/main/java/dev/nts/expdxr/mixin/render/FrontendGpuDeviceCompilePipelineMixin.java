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
 * 26.3：把 vanilla 的 {@link RenderPipeline} 登记进 {@link D3D12PipelineBridge}。
 *
 * <p>26.3 的 `GpuDeviceBackend.compilePipeline` 只收到 `BackendRenderPipeline.CreateInfo`
 * （SPIR-V + 状态），而本后端的编译链要拿 GLSL 源码、`shaderDefines` 和 mesh 变体判据。
 * vanilla 每次编译都先走 `FrontendGpuDevice.compilePipeline(RenderPipeline, ShaderSource,
 * Executor)`（`RenderSystem.getDevice().compilePipeline(...)` 的落点），所以在 HEAD 处把
 * **管线与源一起**登记：`ShaderSource` 在 26.3 是逐次传入的，后端 `createDevice` 已经不收它了，
 * 只登记管线的话首帧编译拿不到 GLSL 源码。只读两个参数，不改变 vanilla 行为。
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