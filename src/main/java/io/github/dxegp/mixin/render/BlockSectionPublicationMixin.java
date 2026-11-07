package io.github.dxegp.mixin.render;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.dxegp.d3d12.render.advanced.mesh.D3D12SectionGeometry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(LevelChunkSection.class)
public abstract class BlockSectionPublicationMixin {
    @WrapMethod(method = "setBlockState(IIILnet/minecraft/world/level/block/state/BlockState;Z)Lnet/minecraft/world/level/block/state/BlockState;")
    private BlockState dxr_mc$publishEdit(int localX, int localY, int localZ, BlockState state, boolean lock,
                                         Operation<BlockState> original) {
        try (var publication = D3D12SectionGeometry.beginBlockSectionPublication((LevelChunkSection) (Object) this, false)) {
            BlockState result = original.call(localX, localY, localZ, state, lock);
            if (publication != null) {
                publication.complete();
            }
            return result;
        }
    }

    @WrapMethod(method = "read")
    private void dxr_mc$publishRead(FriendlyByteBuf buffer, Operation<Void> original) {
        try (var publication = D3D12SectionGeometry.beginBlockSectionPublication((LevelChunkSection) (Object) this, true)) {
            original.call(buffer);
            if (publication != null) {
                publication.complete();
            }
        }
    }

    @WrapMethod(method = "recalcBlockCounts")
    private void dxr_mc$publishCounts(Operation<Void> original) {
        try (var publication = D3D12SectionGeometry.beginBlockSectionPublication((LevelChunkSection) (Object) this, false)) {
            original.call();
            if (publication != null) {
                publication.complete();
            }
        }
    }
}
