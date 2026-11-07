package io.github.dxegp.mixin.render;

import io.github.dxegp.d3d12.render.advanced.mesh.D3D12SectionGeometry;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientLevel.class)
public abstract class ClientLevelGeometryStateMixin {
    @Inject(method = "onChunkLoaded", at = @At("TAIL"))
    private void dxr_mc$observeChunkLoaded(ChunkPos position, CallbackInfo callback) {
        ClientLevel level = (ClientLevel) (Object) this;
        LevelChunk chunk = level.getChunkSource().getChunk(position.x(), position.z(), ChunkStatus.FULL, false);
        if (chunk != null) {
            D3D12SectionGeometry.observeChunkLoaded(position.x(), position.z(), level.getMinSectionY(),
                    level.getSectionsCount(), level, chunk);
        }
    }

    @Inject(method = "unload", at = @At("HEAD"))
    private void dxr_mc$observeChunkUnloaded(LevelChunk chunk, CallbackInfo callback) {
        ChunkPos position = chunk.getPos();
        D3D12SectionGeometry.observeChunkUnloaded(position.x(), position.z(), this, chunk);
    }
}
