package io.github.dxegp.mixin.render;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.dxegp.d3d12.render.advanced.mesh.D3D12SectionGeometry;
import io.github.dxegp.experimental.raytracing.D3D12RtSectionArrays;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

import java.util.concurrent.atomic.AtomicReferenceArray;

@Mixin(targets = "net.minecraft.client.multiplayer.ClientChunkCache$Storage")
public abstract class ClientChunkStoragePublicationMixin {
    @Shadow
    @Final
    private ClientChunkCache this$0;

    @WrapOperation(method = {"onChunkRemoved", "onChunkAdded", "refreshEmptySections"}, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/chunk/LevelChunk;getSections:()[Lnet/minecraft/world/level/chunk/LevelChunkSection;"))
    private LevelChunkSection[] dxr_mc$borrow(LevelChunk chunk, Operation<LevelChunkSection[]> original) {
        try (var access = D3D12RtSectionArrays.borrow(chunk)) {
            return original.call(chunk);
        }
    }

    @WrapOperation(method = "drop", at = @At(value = "INVOKE", remap = false,
            target = "Ljava/util/concurrent/atomic/AtomicReferenceArray;compareAndSet(ILjava/lang/Object;Ljava/lang/Object;)Z"))
    private boolean dxr_mc$publishDrop(AtomicReferenceArray<LevelChunk> slots, int index, Object expected,
                                      Object next, Operation<Boolean> original) {
        return D3D12SectionGeometry.publishChunkDrop(this$0, this, slots, index, (LevelChunk) expected,
                (LevelChunk) next, () -> original.call(slots, index, expected, next));
    }

    @WrapOperation(method = "replace", at = @At(value = "INVOKE", remap = false,
            target = "Ljava/util/concurrent/atomic/AtomicReferenceArray;getAndSet(ILjava/lang/Object;)Ljava/lang/Object;"))
    private Object dxr_mc$publishReplacement(AtomicReferenceArray<LevelChunk> slots, int index, Object next,
                                            Operation<Object> original) {
        return D3D12SectionGeometry.publishChunkReplacement(this$0, this, slots, index, (LevelChunk) next,
                () -> (LevelChunk) original.call(slots, index, next));
    }
}
