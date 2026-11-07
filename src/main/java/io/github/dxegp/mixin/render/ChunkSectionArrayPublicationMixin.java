package io.github.dxegp.mixin.render;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.dxegp.experimental.raytracing.D3D12RtSectionArrays;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ChunkAccess.class)
public abstract class ChunkSectionArrayPublicationMixin {
    @WrapMethod(method = "getSections")
    private LevelChunkSection[] dxr_mc$expose(Operation<LevelChunkSection[]> original) {
        return D3D12RtSectionArrays.expose((ChunkAccess) (Object) this, original.call());
    }

    @WrapOperation(method = {"getSection", "getHighestFilledSectionIndex"}, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/chunk/ChunkAccess;getSections:()[Lnet/minecraft/world/level/chunk/LevelChunkSection;"))
    private LevelChunkSection[] dxr_mc$borrow(ChunkAccess chunk, Operation<LevelChunkSection[]> original) {
        try (var access = D3D12RtSectionArrays.borrow(chunk)) {
            return original.call(chunk);
        }
    }
}
