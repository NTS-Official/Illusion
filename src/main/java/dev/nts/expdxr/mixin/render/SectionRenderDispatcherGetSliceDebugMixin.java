package dev.nts.expdxr.mixin.render;

import dev.nts.expdxr.utils.IllusionLogger;

import dev.nts.expdxr.d3d12.render.adapter.D3D12Device;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * P32 diagnostic: logs getRenderSectionSlice null returns in SectionRenderDispatcher.
 * When slice is null, the chunk's vertex buffer hasn't been uploaded yet — these
 * chunks will be skipped during rendering, causing distant chunks to appear black.
 */
@Mixin(SectionRenderDispatcher.class)
public class SectionRenderDispatcherGetSliceDebugMixin {

    @Unique private int D3D12_SliceCheckCount = 0;
    @Unique private int D3D12_NullSliceCount = 0;

    @Inject(method = "getRenderSectionSlice", at = @At("TAIL"), remap = false)
    private void dx12_getSliceDebug(SectionMesh sectionMesh, ChunkSectionLayer layer,
            CallbackInfoReturnable<SectionRenderDispatcher.RenderSectionBufferSlice> cir) {
        D3D12_SliceCheckCount++;
        SectionRenderDispatcher.RenderSectionBufferSlice slice = cir.getReturnValue();
        if (slice == null) {
            D3D12_NullSliceCount++;
            // Log first 3 nulls, then sample every 60 checks to avoid spam
            if (D3D12_NullSliceCount <= 3 || D3D12_SliceCheckCount % 60 == 0) {
                String meshStatus = (sectionMesh == null) ? "null"
                        : (sectionMesh == CompiledSectionMesh.UNCOMPILED) ? "UNCOMPILED" : "compiled";
                IllusionLogger.debugFormat("[backend-p32] getRenderSectionSlice: NULL slice (layer=%s mesh=%s) totalChecks=%d nullCount=%d backend=%s%n",
                        layer, meshStatus, D3D12_SliceCheckCount, D3D12_NullSliceCount,
                        D3D12Device.isInitialized() ? "DX12" : "NONE");
                System.err.flush();
            }
        }
    }
}
