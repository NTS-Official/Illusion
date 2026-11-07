package io.github.dxegp.mixin.render;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.dxegp.d3d12.render.advanced.mesh.D3D12SectionGeometry;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ClientChunkCache.class)
public abstract class ClientChunkCacheBiomePublicationMixin {
    @Shadow
    @Final
    private ClientLevel level;

    @WrapOperation(method = "replaceBiomes", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/chunk/LevelChunk;replaceBiomes(Lnet/minecraft/network/FriendlyByteBuf;)V"))
    private void dxr_mc$publishBiomes(LevelChunk chunk, FriendlyByteBuf buffer, Operation<Void> original) {
        ChunkPos position = chunk.getPos();
        try (var publication = D3D12SectionGeometry.beginBiomePublication(level,
                (ClientChunkCache) (Object) this, position.x(), position.z())) {
            original.call(chunk, buffer);
            if (publication != null) {
                publication.complete();
            }
        }
    }
}
