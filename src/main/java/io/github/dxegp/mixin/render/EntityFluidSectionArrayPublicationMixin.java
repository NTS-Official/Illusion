package io.github.dxegp.mixin.render;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.dxegp.experimental.raytracing.D3D12RtSectionArrays;
import net.minecraft.world.entity.EntityFluidInteraction;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(EntityFluidInteraction.class)
public abstract class EntityFluidSectionArrayPublicationMixin {
    @WrapOperation(method = "hasFluidAndLoaded", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/chunk/ChunkAccess;getSections:()[Lnet/minecraft/world/level/chunk/LevelChunkSection;"))
    private static LevelChunkSection[] dxr_mc$borrow(ChunkAccess chunk, Operation<LevelChunkSection[]> original) {
        try (var access = D3D12RtSectionArrays.borrow(chunk)) {
            return original.call(chunk);
        }
    }
}
