package io.github.dxegp.mixin.render;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.dxegp.d3d12.render.advanced.mesh.D3D12SectionGeometry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.chunk.PalettedContainer;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(PalettedContainer.class)
public abstract class BiomePalettePublicationMixin<Value> {
    @WrapMethod(method = "getAndSet(IIILjava/lang/Object;)Ljava/lang/Object;")
    private Value dxr_mc$publishBiomeGetAndSet(int localX, int localY, int localZ, Value value, Operation<Value> original) {
        try (var publication = D3D12SectionGeometry.beginBiomePalettePublication((PalettedContainer<?>) (Object) this, false)) {
            Value result = original.call(localX, localY, localZ, value);
            if (publication != null) {
                publication.complete();
            }
            return result;
        }
    }

    @WrapMethod(method = "getAndSetUnchecked")
    private Value dxr_mc$publishBiomeUnchecked(int localX, int localY, int localZ, Value value, Operation<Value> original) {
        try (var publication = D3D12SectionGeometry.beginBiomePalettePublication((PalettedContainer<?>) (Object) this, false)) {
            Value result = original.call(localX, localY, localZ, value);
            if (publication != null) {
                publication.complete();
            }
            return result;
        }
    }

    @WrapMethod(method = "set(IIILjava/lang/Object;)V")
    private void dxr_mc$publishBiomeSet(int localX, int localY, int localZ, Value value, Operation<Void> original) {
        try (var publication = D3D12SectionGeometry.beginBiomePalettePublication((PalettedContainer<?>) (Object) this, false)) {
            original.call(localX, localY, localZ, value);
            if (publication != null) {
                publication.complete();
            }
        }
    }

    @WrapMethod(method = "onResize")
    private int dxr_mc$publishBiomeResize(int bits, Value value, Operation<Integer> original) {
        try (var publication = D3D12SectionGeometry.beginBiomePalettePublication((PalettedContainer<?>) (Object) this, false)) {
            int result = original.call(bits, value);
            if (publication != null) {
                publication.complete();
            }
            return result;
        }
    }

    @WrapMethod(method = "read")
    private void dxr_mc$publishBiomeRead(FriendlyByteBuf buffer, Operation<Void> original) {
        try (var publication = D3D12SectionGeometry.beginBiomePalettePublication((PalettedContainer<?>) (Object) this, true)) {
            original.call(buffer);
            if (publication != null) {
                publication.complete();
            }
        }
    }
}
