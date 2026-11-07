package io.github.dxegp.mixin.render;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.dxegp.d3d12.render.advanced.mesh.D3D12MeshDrawCapture;
import io.github.dxegp.d3d12.render.advanced.mesh.D3D12SectionGeometry;
import io.github.dxegp.experimental.raytracing.D3D12RtGeometryRecovery;
import net.minecraft.client.renderer.DynamicGpuData;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.Map;

@Mixin(LevelRenderer.class)
public abstract class LevelRendererMeshCaptureMixin {
    @Inject(method = "compileSections", at = @At("TAIL"))
    private void dxr_mc$submitRecovery(CallbackInfo callback) {
        D3D12SectionGeometry.observeCurrentCompiler();
        D3D12RtGeometryRecovery.submit((LevelRenderer) (Object) this);
    }

    @Inject(method = "extractSectionDrawGroups", at = @At("HEAD"))
    private void dxr_mc$beginExtraction(CallbackInfoReturnable<Integer> callback) {
        D3D12SectionGeometry.observeCurrentCompiler();
        D3D12MeshDrawCapture.beginExtraction();
    }

    @WrapOperation(method = "extractSectionDrawGroups", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher;getRenderSectionSlice(Lnet/minecraft/client/renderer/chunk/SectionMesh;Lnet/minecraft/client/renderer/chunk/ChunkSectionLayer;)Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher$RenderSectionBufferSlice;"))
    private SectionRenderDispatcher.RenderSectionBufferSlice dxr_mc$captureSlice(
            SectionRenderDispatcher dispatcher, SectionMesh mesh, ChunkSectionLayer layer,
            Operation<SectionRenderDispatcher.RenderSectionBufferSlice> original) {
        SectionRenderDispatcher.RenderSectionBufferSlice slice = original.call(dispatcher, mesh, layer);
        if (slice != null) {
            D3D12MeshDrawCapture.captureSlice(mesh, layer.name(), slice.vertexBuffer(), slice.vertexBufferOffset());
        }
        return slice;
    }

    @Inject(method = "extractSectionDrawGroups", at = @At("RETURN"))
    private void dxr_mc$finishExtraction(boolean respectTranslucentOrder,
            List<DynamicGpuData.ChunkSectionInfo> sectionInfos, Map<ChunkSectionLayer, ?> drawGroups,
            CallbackInfoReturnable<Integer> callback) {
        D3D12MeshDrawCapture.finishExtraction(sectionInfos);
    }
}
