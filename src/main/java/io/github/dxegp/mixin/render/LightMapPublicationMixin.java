package io.github.dxegp.mixin.render;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.dxegp.d3d12.render.advanced.mesh.D3D12SectionGeometry;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.lighting.DataLayerStorageMap;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(DataLayerStorageMap.class)
public abstract class LightMapPublicationMixin {
    @WrapMethod(method = "setLayer")
    private void dxr_mc$publishSet(long node, DataLayer layer, Operation<Void> original) {
        try (var publication = D3D12SectionGeometry.beginLightMapMutation((DataLayerStorageMap<?>) (Object) this, node, true)) {
            original.call(node, layer);
            publication.complete();
        }
    }

    @WrapMethod(method = "removeLayer")
    private DataLayer dxr_mc$publishRemove(long node, Operation<DataLayer> original) {
        try (var publication = D3D12SectionGeometry.beginLightMapMutation((DataLayerStorageMap<?>) (Object) this, node, true)) {
            DataLayer result = original.call(node);
            publication.complete();
            return result;
        }
    }

    @WrapMethod(method = "copyDataLayer")
    private DataLayer dxr_mc$publishCopy(long node, Operation<DataLayer> original) {
        try (var publication = D3D12SectionGeometry.beginLightMapMutation((DataLayerStorageMap<?>) (Object) this, node, false)) {
            DataLayer result = original.call(node);
            publication.complete();
            return result;
        }
    }
}
