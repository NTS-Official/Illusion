package io.github.dxegp.mixin.render;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.dxegp.d3d12.render.advanced.mesh.D3D12SectionGeometry;
import io.github.dxegp.experimental.raytracing.D3D12RtModelSupport;
import net.minecraft.client.color.block.BlockTintCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ClientLevel.class)
public abstract class ClientLevelTintReadMixin {
    @Inject(method = "calculateBlockTint(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/ColorResolver;)I",
            at = @At("HEAD"))
    private void dxr_mc$recordTintCalculator(BlockPos position, ColorResolver resolver,
            CallbackInfoReturnable<Integer> callback) {
        D3D12SectionGeometry.recordTintCalculatorRead((ClientLevel) (Object) this, resolver);
    }

    @Inject(method = "getUncachedNoiseBiome(III)Lnet/minecraft/core/Holder;", at = @At("HEAD"))
    private void dxr_mc$recordBiomeFallback(int quartX, int quartY, int quartZ,
            CallbackInfoReturnable<Holder<Biome>> callback) {
        D3D12SectionGeometry.recordBiomeFallback();
    }

    @WrapOperation(method = "getBlockTint", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/color/block/BlockTintCache;getColor(Lnet/minecraft/core/BlockPos;)I"))
    private int dxr_mc$recordTintRead(BlockTintCache cache, BlockPos position, Operation<Integer> original,
            BlockPos requestedPosition, ColorResolver resolver) {
        D3D12SectionGeometry.recordTintCacheLookup((ClientLevel) (Object) this, cache, resolver);
        D3D12RtModelSupport.recordTintRead(cache, position);
        return original.call(cache, position);
    }
}
