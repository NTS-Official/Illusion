package io.github.dxegp.mixin.render;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.expression.Expression;
import io.github.dxegp.d3d12.render.advanced.mesh.D3D12SectionGeometry;
import io.github.dxegp.experimental.raytracing.D3D12RtTintInputs;
import net.minecraft.client.color.block.BlockTintCache;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(BlockTintCache.class)
public abstract class BlockTintCachePublicationMixin {
    @WrapMethod(method = "getColor")
    private int dxr_mc$readColor(BlockPos position, Operation<Integer> original) {
        try (var read = D3D12RtTintInputs.reading((BlockTintCache) (Object) this, position)) {
            int result = original.call(position);
            read.complete();
            return result;
        }
    }

    @Expression("?[?]")
    @WrapOperation(method = "getColor", at = @At("MIXINEXTRAS:EXPRESSION"))
    private int dxr_mc$readSlot(int[] array, int index, Operation<Integer> original) {
        return D3D12RtTintInputs.readSlot(array, index, () -> original.call(array, index));
    }

    @Expression("?[?] = ?")
    @WrapOperation(method = "getColor", at = @At("MIXINEXTRAS:EXPRESSION"))
    private void dxr_mc$writeSlot(int[] array, int index, int value, Operation<Void> original) {
        D3D12RtTintInputs.writeSlot(array, index, value, () -> original.call(array, index, value));
    }

    @WrapMethod(method = "invalidateForChunk")
    private void dxr_mc$publishChunkInvalidation(int chunkX, int chunkZ, Operation<Void> original) {
        D3D12SectionGeometry.publishTintCache((BlockTintCache) (Object) this,
                ChunkPos.pack(chunkX, chunkZ), () -> original.call(chunkX, chunkZ));
    }

    @WrapMethod(method = "invalidateAll")
    private void dxr_mc$publishInvalidation(Operation<Void> original) {
        D3D12SectionGeometry.publishTintCache((BlockTintCache) (Object) this, null, () -> original.call());
    }
}
