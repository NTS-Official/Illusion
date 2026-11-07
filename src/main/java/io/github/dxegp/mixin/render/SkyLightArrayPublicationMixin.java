package io.github.dxegp.mixin.render;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.dxegp.d3d12.render.advanced.mesh.D3D12SectionGeometry;
import io.github.dxegp.experimental.raytracing.D3D12RtLightArrays;
import io.github.dxegp.experimental.raytracing.D3D12RtModelSupport;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.lighting.SkyLightSectionStorage;
import net.minecraft.world.level.lighting.DataLayerStorageMap;
import net.minecraft.world.level.lighting.LayerLightSectionStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SkyLightSectionStorage.class)
public abstract class SkyLightArrayPublicationMixin {
    @Inject(method = "getLightValue(JZ)I", at = @At("HEAD"))
    private void dxr_mc$recordRead(long position, boolean updating, CallbackInfoReturnable<Integer> callback) {
        D3D12RtModelSupport.recordLightRead((LayerLightSectionStorage<?>) (Object) this, position, updating);
    }

    @WrapOperation(method = "<init>", at = @At(value = "NEW",
            target = "(Lit/unimi/dsi/fastutil/longs/Long2ObjectOpenHashMap;Lit/unimi/dsi/fastutil/longs/Long2IntOpenHashMap;I)Lnet/minecraft/world/level/lighting/SkyLightSectionStorage$SkyDataLayerStorageMap;"))
    @Coerce
    private static DataLayerStorageMap<?> dxr_mc$ownInitialMap(Long2ObjectOpenHashMap<DataLayer> layers, Long2IntOpenHashMap tops,
                                                             int lowest, Operation<DataLayerStorageMap<?>> original) {
        var result = original.call(layers, tops, lowest);
        D3D12SectionGeometry.ownLightMap(result, layers, tops);
        return result;
    }

    @WrapMethod(method = "onNodeAdded")
    private void dxr_mc$publishAdded(long node, Operation<Void> original) {
        try (var publication = D3D12SectionGeometry.beginLightSkyMetadata((SkyLightSectionStorage) (Object) this, node)) {
            original.call(node);
            publication.complete();
        }
    }

    @WrapMethod(method = "onNodeRemoved")
    private void dxr_mc$publishRemoved(long node, Operation<Void> original) {
        try (var publication = D3D12SectionGeometry.beginLightSkyMetadata((SkyLightSectionStorage) (Object) this, node)) {
            original.call(node);
            publication.complete();
        }
    }

    @WrapMethod(method = "repeatFirstLayer")
    private static DataLayer dxr_mc$repeatFirstLayer(DataLayer source, Operation<DataLayer> original) {
        var copy = D3D12SectionGeometry.beginLightLayerCopy(source);
        try (var access = D3D12RtLightArrays.borrow(source)) {
            DataLayer result = original.call(source);
            D3D12SectionGeometry.finishLightLayerCopy(copy, result);
            return result;
        }
    }

    @WrapOperation(method = "repeatFirstLayer", at = @At(value = "NEW",
            target = "([B)Lnet/minecraft/world/level/chunk/DataLayer;"))
    private static DataLayer dxr_mc$ownRepeat(byte[] bytes, Operation<DataLayer> original) {
        DataLayer result = original.call(bytes);
        D3D12RtLightArrays.own(result, bytes);
        return result;
    }
}
