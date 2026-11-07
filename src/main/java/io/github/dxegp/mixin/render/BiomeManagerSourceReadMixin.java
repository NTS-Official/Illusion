package io.github.dxegp.mixin.render;

import io.github.dxegp.d3d12.render.advanced.mesh.D3D12SectionGeometry;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeResolver;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BiomeManager.class)
public abstract class BiomeManagerSourceReadMixin {
    @Shadow @Final private BiomeResolver noiseBiomeSource;

    @Inject(method = {"getBiome(III)Lnet/minecraft/core/Holder;",
            "getNoiseBiomeAtQuart(III)Lnet/minecraft/core/Holder;"}, at = @At("HEAD"))
    private void dxr_mc$recordBiomeSource(int coordinateX, int coordinateY, int coordinateZ,
            CallbackInfoReturnable<Holder<Biome>> callback) {
        D3D12SectionGeometry.recordBiomeManagerRead((BiomeManager) (Object) this, noiseBiomeSource);
    }
}
