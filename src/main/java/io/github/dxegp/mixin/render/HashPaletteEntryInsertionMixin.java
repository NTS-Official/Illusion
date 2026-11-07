package io.github.dxegp.mixin.render;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.dxegp.d3d12.render.advanced.mesh.D3D12SectionGeometry;
import net.minecraft.util.CrudeIncrementalIntIdentityHashBiMap;
import net.minecraft.world.level.chunk.HashMapPalette;
import net.minecraft.world.level.chunk.Palette;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(HashMapPalette.class)
public abstract class HashPaletteEntryInsertionMixin<Value> {
    @WrapOperation(method = "idFor", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/util/CrudeIncrementalIntIdentityHashBiMap;add:(Ljava/lang/Object;)I"))
    private int dxr_mc$append(CrudeIncrementalIntIdentityHashBiMap<Value> values, Value value, Operation<Integer> original) {
        try (var publication = D3D12SectionGeometry.beginPaletteEntryAppend((Palette<?>) (Object) this)) {
            int result = original.call(values, value);
            publication.complete();
            return result;
        }
    }
}
