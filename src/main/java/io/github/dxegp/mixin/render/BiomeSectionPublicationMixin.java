package io.github.dxegp.mixin.render;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.dxegp.d3d12.render.advanced.mesh.D3D12SectionGeometry;
import io.github.dxegp.experimental.raytracing.D3D12RtModelSupport;
import net.minecraft.core.Holder;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelChunkSection.class)
public abstract class BiomeSectionPublicationMixin {
    @Inject(method = "getNoiseBiome", at = @At("HEAD"))
    private void dxr_mc$recordBiomeRead(int quartX, int quartY, int quartZ,
                                      CallbackInfoReturnable<Holder<Biome>> callback) {
        D3D12RtModelSupport.recordBiomeRead((LevelChunkSection) (Object) this);
    }

    @WrapMethod(method = "read")
    private void dxr_mc$publishSectionBiomes(FriendlyByteBuf buffer, Operation<Void> original) {
        try (var publication = D3D12SectionGeometry.beginBiomeSectionPublication((LevelChunkSection) (Object) this, true)) {
            original.call(buffer);
            if (publication != null) {
                publication.complete();
            }
        }
    }

    @WrapMethod(method = "readBiomes")
    private void dxr_mc$publishBiomeSectionRead(FriendlyByteBuf buffer, Operation<Void> original) {
        try (var publication = D3D12SectionGeometry.beginBiomeSectionPublication((LevelChunkSection) (Object) this, true)) {
            original.call(buffer);
            if (publication != null) {
                publication.complete();
            }
        }
    }

    @WrapMethod(method = "fillBiomesFromNoise")
    private void dxr_mc$publishBiomeFill(BiomeResolver resolver, int quartX, int quartY, int quartZ, Operation<Void> original) {
        try (var publication = D3D12SectionGeometry.beginBiomeSectionPublication((LevelChunkSection) (Object) this, true)) {
            original.call(resolver, quartX, quartY, quartZ);
            if (publication != null) {
                publication.complete();
            }
        }
    }

    @WrapOperation(method = {"read", "readBiomes", "fillBiomesFromNoise"}, at = @At(value = "FIELD",
            target = "Lnet/minecraft/world/level/chunk/LevelChunkSection;biomes:Lnet/minecraft/world/level/chunk/PalettedContainerRO;",
            opcode = Opcodes.PUTFIELD))
    private void dxr_mc$publishBiomeIdentity(LevelChunkSection section, PalettedContainerRO<Holder<Biome>> palette,
                                            Operation<Void> original) {
        D3D12SectionGeometry.publishBiomePalette(section, () -> original.call(section, palette));
    }
}
