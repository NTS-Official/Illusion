package io.github.dxegp.mixin.render;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.dxegp.experimental.raytracing.D3D12RtLightArrays;
import io.github.dxegp.experimental.raytracing.D3D12RtSectionArrays;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ClientPacketListener.class)
public abstract class ClientLightArrayPublicationMixin {
    @WrapOperation(method = "enableChunkLight", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/chunk/LevelChunk;getSections:()[Lnet/minecraft/world/level/chunk/LevelChunkSection;"))
    private LevelChunkSection[] dxr_mc$borrowSections(LevelChunk chunk, Operation<LevelChunkSection[]> original) {
        try (var access = D3D12RtSectionArrays.borrow(chunk)) {
            return original.call(chunk);
        }
    }

    @WrapOperation(method = "readSectionList", at = @At(value = "NEW",
            target = "([B)Lnet/minecraft/world/level/chunk/DataLayer;"))
    private DataLayer dxr_mc$ownPacket(byte[] bytes, Operation<DataLayer> original) {
        DataLayer result = original.call(bytes);
        D3D12RtLightArrays.own(result, bytes);
        return result;
    }
}
