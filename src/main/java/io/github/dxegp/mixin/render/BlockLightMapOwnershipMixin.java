package io.github.dxegp.mixin.render;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.dxegp.d3d12.render.advanced.mesh.D3D12SectionGeometry;
import io.github.dxegp.experimental.raytracing.D3D12RtModelSupport;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.lighting.BlockLightSectionStorage;
import net.minecraft.world.level.lighting.DataLayerStorageMap;
import net.minecraft.world.level.lighting.LayerLightSectionStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BlockLightSectionStorage.class)
public abstract class BlockLightMapOwnershipMixin {
    @Inject(method = "getLightValue(J)I", at = @At("HEAD"))
    private void dxr_mc$recordRead(long position, CallbackInfoReturnable<Integer> callback) {
        D3D12RtModelSupport.recordLightRead((LayerLightSectionStorage<?>) (Object) this, position, false);
    }

    @WrapOperation(method = "<init>", at = @At(value = "NEW",
            target = "(Lit/unimi/dsi/fastutil/longs/Long2ObjectOpenHashMap;)Lnet/minecraft/world/level/lighting/BlockLightSectionStorage$BlockDataLayerStorageMap;"))
    @Coerce
    private static DataLayerStorageMap<?> dxr_mc$ownInitialMap(Long2ObjectOpenHashMap<DataLayer> layers, Operation<DataLayerStorageMap<?>> original) {
        var result = original.call(layers);
        D3D12SectionGeometry.ownLightMap(result, layers, null);
        return result;
    }
}
