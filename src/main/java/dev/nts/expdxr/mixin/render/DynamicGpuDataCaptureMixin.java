package dev.nts.expdxr.mixin.render;

import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import dev.nts.expdxr.d3d12.render.advanced.mesh.D3D12MeshDrawCapture;
import net.minecraft.client.renderer.DynamicGpuData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * P217（mesh 重构 Stage 2 的前置）：捕获 26.3 每帧写出的 MDI 段列表与逐段信息。
 *
 * <p>为什么选这两个方法：`DynamicGpuData` 的 `IndexedDraw` / `ChunkSectionInfo` 都是 **public**
 * record（不像 `LevelRenderer$ChunkDrawGroup` 那样是包私有），mixin 可以直接引用类型；
 * 而且 `writeChunkSectionCommands` 返回的 `GpuBufferSlice[]` 就是绘制时
 * `drawIndexedIndirect` 收到的那块间接参数缓冲 —— 用 `slice.offset()` 就能把
 * "这批参数是哪些段" 精确对回去（参数缓冲本身在 UPLOAD 堆、CPU 读不到，见 P194）。
 *
 * <p>只在 HEAD/RETURN 读实参，不改任何状态。
 */
@Mixin(DynamicGpuData.class)
public abstract class DynamicGpuDataCaptureMixin {

    @Inject(method = "writeChunkSectionCommands", at = @At("HEAD"))
    private void dxr_mc$beginFrame(List<List<DynamicGpuData.IndexedDraw>> groups,
                                   CallbackInfoReturnable<GpuBufferSlice[]> cir) {
        // 每帧写命令前清掉上一帧的批次：offset 会复用，且同一帧多个图层各有一份。
        D3D12MeshDrawCapture.beginFrame();
    }

    @Inject(method = "writeChunkSectionCommands", at = @At("RETURN"))
    private void dxr_mc$captureCommands(List<List<DynamicGpuData.IndexedDraw>> groups,
                                        CallbackInfoReturnable<GpuBufferSlice[]> cir) {
        GpuBufferSlice[] out = cir.getReturnValue();
        if (out == null || groups == null) {
            return;
        }
        int n = Math.min(out.length, groups.size());
        for (int i = 0; i < n; i++) {
            GpuBufferSlice slice = out[i];
            if (slice == null) {
                continue;
            }
            D3D12MeshDrawCapture.captureBatch(slice.offset(), groups.get(i));
        }
    }

    @Inject(method = "writeChunkSectionsInstanced", at = @At("HEAD"))
    private void dxr_mc$captureSections(List<DynamicGpuData.ChunkSectionInfo> infos,
                                        CallbackInfoReturnable<GpuBufferSlice> cir) {
        if (infos != null) {
            D3D12MeshDrawCapture.captureSections(infos);
        }
    }
}
