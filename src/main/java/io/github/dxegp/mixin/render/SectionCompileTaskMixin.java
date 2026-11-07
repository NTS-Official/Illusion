package io.github.dxegp.mixin.render;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.dxegp.experimental.raytracing.D3D12SectionTaskState;
import io.github.dxegp.experimental.raytracing.D3D12SectionCaptureState;
import io.github.dxegp.experimental.raytracing.D3D12SectionCompileState;
import io.github.dxegp.experimental.raytracing.D3D12RtModelSupport;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.core.SectionPos;
import net.neoforged.neoforge.client.event.AddSectionGeometryEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(targets = "net.minecraft.client.renderer.chunk.SectionRenderDispatcher$RenderSection$CompileTask")
public abstract class SectionCompileTaskMixin implements D3D12SectionTaskState {
    @Unique
    private final D3D12SectionCaptureState dxr_mc$captureState = new D3D12SectionCaptureState();

    public D3D12SectionCaptureState illusion$getCaptureState() {
        return dxr_mc$captureState;
    }

    @WrapOperation(method = "doTask", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/chunk/SectionCompiler;compile(Lnet/minecraft/core/SectionPos;Lnet/minecraft/client/renderer/chunk/RenderSectionRegion;Lcom/mojang/blaze3d/vertex/VertexSorting;Lnet/minecraft/client/renderer/SectionBufferBuilderPack;Ljava/util/List;)Lnet/minecraft/client/renderer/chunk/SectionCompiler$Results;"))
    private SectionCompiler.Results dxr_mc$captureCompilerResources(
            SectionCompiler compiler, SectionPos position, RenderSectionRegion region, VertexSorting sorting,
            SectionBufferBuilderPack buffers, List<AddSectionGeometryEvent.AdditionalSectionRenderer> additionalRenderers,
            Operation<SectionCompiler.Results> original) {
        dxr_mc$captureState.captureCompiler(compiler);
        List<AddSectionGeometryEvent.AdditionalSectionRenderer> renderers = List.copyOf(additionalRenderers);
        dxr_mc$captureState.prepareSourceSupport(position, region,
                compiler.getClass() == SectionCompiler.class, !renderers.isEmpty());
        try (D3D12RtModelSupport.Session support = dxr_mc$captureState.beginSupportSession(
                region, compiler.getClass() == SectionCompiler.class,
                !renderers.isEmpty())) {
            SectionCompiler.Results result = original.call(compiler, position, region, sorting, buffers, renderers);
            dxr_mc$captureState.captureProducerManifest(support.finish());
            return result;
        } catch (Throwable failure) {
            dxr_mc$captureState.cancelSourceSupport();
            throw failure;
        }
    }

    @Inject(method = "cancel", at = @At("HEAD"))
    private void dxr_mc$cancelSourceSupport(CallbackInfo callback) {
        dxr_mc$captureState.cancelSourceSupport();
    }

    @ModifyExpressionValue(method = "doTask", at = @At(value = "NEW",
            target = "net/minecraft/client/renderer/chunk/CompiledSectionMesh"))
    private CompiledSectionMesh dxr_mc$trackResult(CompiledSectionMesh mesh) {
        dxr_mc$captureState.capture(mesh);
        return mesh;
    }

    @WrapOperation(method = "doTask", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher$RenderSection;setSectionMesh(Lnet/minecraft/client/renderer/chunk/SectionMesh;)Lnet/minecraft/client/renderer/chunk/SectionMesh;"))
    private SectionMesh dxr_mc$trackEmptyResult(SectionRenderDispatcher.RenderSection section,
                                               SectionMesh mesh, Operation<SectionMesh> original) {
        synchronized (section) {
            dxr_mc$captureState.capture(mesh);
            if (((D3D12SectionCompileState) section).getCompileTaskIdentity() != this
                    || ((SectionCompileTaskAccessor) this).dxr_mc$isCancelled().get()) {
                return mesh;
            }
            return original.call(section, mesh);
        }
    }
}
