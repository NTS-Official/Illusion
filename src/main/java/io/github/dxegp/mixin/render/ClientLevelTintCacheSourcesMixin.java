package io.github.dxegp.mixin.render;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.dxegp.d3d12.render.advanced.mesh.D3D12SectionGeometry;
import net.minecraft.client.color.block.BlockTintCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ColorResolver;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import java.util.function.ToIntFunction;

@Mixin(ClientLevel.class)
public abstract class ClientLevelTintCacheSourcesMixin {
    @WrapOperation(method = "lambda$new$0", at = @At(value = "NEW",
            target = "(Ljava/util/function/ToIntFunction;)Lnet/minecraft/client/color/block/BlockTintCache;", ordinal = 0))
    private BlockTintCache dxr_mc$ownGrass(ToIntFunction<BlockPos> source, Operation<BlockTintCache> original) {
        return dxr_mc$own(source, BiomeColors.GRASS_COLOR_RESOLVER, original);
    }

    @WrapOperation(method = "lambda$new$0", at = @At(value = "NEW",
            target = "(Ljava/util/function/ToIntFunction;)Lnet/minecraft/client/color/block/BlockTintCache;", ordinal = 1))
    private BlockTintCache dxr_mc$ownFoliage(ToIntFunction<BlockPos> source, Operation<BlockTintCache> original) {
        return dxr_mc$own(source, BiomeColors.FOLIAGE_COLOR_RESOLVER, original);
    }

    @WrapOperation(method = "lambda$new$0", at = @At(value = "NEW",
            target = "(Ljava/util/function/ToIntFunction;)Lnet/minecraft/client/color/block/BlockTintCache;", ordinal = 2))
    private BlockTintCache dxr_mc$ownDryFoliage(ToIntFunction<BlockPos> source, Operation<BlockTintCache> original) {
        return dxr_mc$own(source, BiomeColors.DRY_FOLIAGE_COLOR_RESOLVER, original);
    }

    @WrapOperation(method = "lambda$new$0", at = @At(value = "NEW",
            target = "(Ljava/util/function/ToIntFunction;)Lnet/minecraft/client/color/block/BlockTintCache;", ordinal = 3))
    private BlockTintCache dxr_mc$ownWater(ToIntFunction<BlockPos> source, Operation<BlockTintCache> original) {
        return dxr_mc$own(source, BiomeColors.WATER_COLOR_RESOLVER, original);
    }

    @Unique
    private BlockTintCache dxr_mc$own(ToIntFunction<BlockPos> source, ColorResolver resolver,
            Operation<BlockTintCache> original) {
        var cache = original.call(source);
        D3D12SectionGeometry.ownTintCacheSource((ClientLevel) (Object) this, cache, source, resolver);
        return cache;
    }
}
