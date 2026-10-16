package dev.nts.expdxr.mixin.render;

import dev.nts.expdxr.utils.IllusionLogger;

import dev.nts.expdxr.d3d12.render.adapter.D3D12Device;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.reflect.Field;
import java.util.Collection;

/**
 * P32 diagnostic: logs visible sections count and valid chunk mesh count
 * during prepareChunkRenders(). Samples every 60 frames to reduce log noise.
 */
@Mixin(LevelRenderer.class)
public class LevelRendererPrepareChunksDebugMixin {

    @Unique
    private int dxrmc$D3D12PrepareFrameCount = 0;

    @Inject(method = "prepareChunkRenders", at = @At("TAIL"), remap = false)
    private void D3D12PrepareChunkRendersDebug(Matrix4fc modelViewMatrix, CallbackInfoReturnable<ChunkSectionsToRender> cir) {
        dxrmc$D3D12PrepareFrameCount++;
        // P54 fix：原条件逻辑反了，count>5 后每帧都打印（+ 每帧反射）→ 同步 I/O 热点。
        if (dxrmc$D3D12PrepareFrameCount > 5 && dxrmc$D3D12PrepareFrameCount % 60 != 0) return;

        // Access visibleSections via reflection (package-private field in decompiled source)
        int visibleCount = 0;
        int validMeshCount = 0;
        int skippedCount = 0;
        int renderDistance = -1;
        float depthFar = -1f;

        try {
            Field vsField = LevelRenderer.class.getDeclaredField("visibleSections");
            vsField.setAccessible(true);
            Object vs = vsField.get(this);
            if (vs instanceof Collection) {
                visibleCount = ((Collection<?>) vs).size();
            }
            Field optsField = LevelRenderer.class.getDeclaredField("optionsRenderState");
            optsField.setAccessible(true);
            Object optsState = optsField.get(this);
            if (optsState != null) {
                Field rdField = optsState.getClass().getDeclaredField("renderDistance");
                rdField.setAccessible(true);
                renderDistance = rdField.getInt(optsState);
            }
        } catch (Exception ignored) {}

        // Access camera depthFar via GameRenderer
        try {
            Field lrField = LevelRenderer.class.getDeclaredField("gameRenderer");
            lrField.setAccessible(true);
            Object gr = lrField.get(this);
            if (gr != null) {
                Field mcField = gr.getClass().getDeclaredField("minecraft");
                mcField.setAccessible(true);
                Object mc = mcField.get(gr);
                if (mc != null) {
                    Field camField = mc.getClass().getDeclaredField("mainCamera");
                    camField.setAccessible(true);
                    Object cam = camField.get(mc);
                    if (cam != null) {
                        Field dfField = cam.getClass().getDeclaredField("depthFar");
                        dfField.setAccessible(true);
                        depthFar = dfField.getFloat(cam);
                    }
                }
            }
        } catch (Exception ignored) {}

        IllusionLogger.debugFormat("[backend-p32] prepareChunkRenders: visibleSections=%d renderDistance=%d depthFar=%.0f validMeshes=? frame=%d backend=%s%n",
                visibleCount, renderDistance, depthFar, dxrmc$D3D12PrepareFrameCount,
                D3D12Device.isInitialized() ? "DX12" : "NONE");
        System.err.flush();
    }
}
