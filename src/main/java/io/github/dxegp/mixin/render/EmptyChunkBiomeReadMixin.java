package io.github.dxegp.mixin.render;

import io.github.dxegp.d3d12.render.advanced.mesh.D3D12SectionGeometry;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.EmptyLevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(EmptyLevelChunk.class)
public abstract class EmptyChunkBiomeReadMixin {
    @Inject(method = "getNoiseBiome(III)Lnet/minecraft/core/Holder;", at = @At("HEAD"))
    private void dxr_mc$recordBiomeFallback(int quartX, int quartY, int quartZ,
            CallbackInfoReturnable<Holder<Biome>> callback) {
        D3D12SectionGeometry.recordBiomeFallback();
    }
}
