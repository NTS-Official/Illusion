package io.github.dxegp.mixin.render;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import io.github.dxegp.d3d12.render.advanced.mesh.D3D12SectionGeometry;
import net.minecraft.client.color.block.BlockTintCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ColorResolver;
import net.neoforged.neoforge.client.ColorResolverManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import java.util.function.ToIntFunction;

@Mixin(value = ColorResolverManager.class, remap = false)
public abstract class ColorResolverCacheSourcesMixin {
    @WrapOperation(method = "registerBlockTintCaches", at = @At(value = "NEW",
            target = "(Ljava/util/function/ToIntFunction;)Lnet/minecraft/client/color/block/BlockTintCache;"))
    private static BlockTintCache dxr_mc$ownSource(ToIntFunction<BlockPos> source, Operation<BlockTintCache> original,
            @Local(argsOnly = true) ClientLevel level, @Local ColorResolver resolver) {
        var cache = original.call(source);
        D3D12SectionGeometry.ownTintCacheSource(level, cache, source, resolver);
        return cache;
    }
}
