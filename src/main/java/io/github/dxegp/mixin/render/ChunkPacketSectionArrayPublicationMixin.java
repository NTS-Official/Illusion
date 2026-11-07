package io.github.dxegp.mixin.render;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.dxegp.experimental.raytracing.D3D12RtSectionArrays;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ClientboundLevelChunkPacketData.class)
public abstract class ChunkPacketSectionArrayPublicationMixin {
    @WrapOperation(method = {"calculateChunkSize", "extractChunkData"}, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/chunk/LevelChunk;getSections:()[Lnet/minecraft/world/level/chunk/LevelChunkSection;"))
    private static LevelChunkSection[] dxr_mc$borrow(LevelChunk chunk, Operation<LevelChunkSection[]> original) {
        try (var access = D3D12RtSectionArrays.borrow(chunk)) {
            return original.call(chunk);
        }
    }
}
