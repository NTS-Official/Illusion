package io.github.dxegp.mixin.render;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.dxegp.d3d12.render.advanced.mesh.D3D12SectionGeometry;
import net.minecraft.world.level.lighting.DataLayerStorageMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Coerce;

@Mixin(targets = "net.minecraft.world.level.lighting.SkyLightSectionStorage$SkyDataLayerStorageMap")
public abstract class SkyLightMapCopyMixin {
    @WrapMethod(method = "copy()Lnet/minecraft/world/level/lighting/SkyLightSectionStorage$SkyDataLayerStorageMap;")
    @Coerce
    private DataLayerStorageMap<?> dxr_mc$copyMap(Operation<DataLayerStorageMap<?>> original) {
        var copy = D3D12SectionGeometry.beginLightMapCopy((DataLayerStorageMap<?>) (Object) this);
        var result = original.call();
        D3D12SectionGeometry.finishLightMapCopy(copy, result);
        return result;
    }
}
