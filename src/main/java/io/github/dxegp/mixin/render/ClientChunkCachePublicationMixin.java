package io.github.dxegp.mixin.render;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.dxegp.d3d12.render.advanced.mesh.D3D12SectionGeometry;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.world.level.chunk.LevelChunk;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;

@Mixin(ClientChunkCache.class)
public abstract class ClientChunkCachePublicationMixin {
    @Shadow
    @Final
    private ClientLevel level;

    @WrapMethod(method = "replaceWithPacketData")
    private LevelChunk dxr_mc$publishChunk(int chunkX, int chunkZ, ClientboundLevelChunkPacketData packet,
                                          Operation<LevelChunk> original) {
        try (var publication = D3D12SectionGeometry.beginChunkPublication(level,
                (ClientChunkCache) (Object) this, chunkX, chunkZ)) {
            LevelChunk chunk = original.call(chunkX, chunkZ, packet);
            if (publication != null) {
                publication.complete();
            }
            return chunk;
        }
    }

    @WrapMethod(method = "updateViewCenter")
    private void dxr_mc$publishViewCenter(int chunkX, int chunkZ, Operation<Void> original) {
        D3D12SectionGeometry.publishChunkViewCenter((ClientChunkCache) (Object) this, chunkX, chunkZ,
                () -> original.call(chunkX, chunkZ));
    }

    @WrapOperation(method = "updateViewRadius", at = @At(value = "FIELD", opcode = Opcodes.PUTFIELD,
            target = "Lnet/minecraft/client/multiplayer/ClientChunkCache;storage:Lnet/minecraft/client/multiplayer/ClientChunkCache$Storage;"))
    private void dxr_mc$publishStorage(ClientChunkCache source, @Coerce Object storage, Operation<Void> original) {
        D3D12SectionGeometry.publishChunkStorage(source, storage, () -> original.call(source, storage));
    }
}
