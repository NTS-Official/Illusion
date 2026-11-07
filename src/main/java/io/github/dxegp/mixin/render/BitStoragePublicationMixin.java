package io.github.dxegp.mixin.render;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.dxegp.d3d12.render.advanced.mesh.D3D12SectionGeometry;
import io.github.dxegp.experimental.raytracing.D3D12RtPaletteArrays;
import net.minecraft.util.BitStorage;
import net.minecraft.util.SimpleBitStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SimpleBitStorage.class)
public abstract class BitStoragePublicationMixin {
    @Inject(method = "<init>(II[J)V", at = @At("RETURN"))
    private void dxr_mc$constructed(int bits, int size, long[] supplied, CallbackInfo callback) {
        D3D12RtPaletteArrays.constructed((BitStorage) (Object) this, supplied);
    }

    @WrapMethod(method = "getRaw")
    private long[] dxr_mc$exposeArray(Operation<long[]> original) {
        D3D12RtPaletteArrays.expose((BitStorage) (Object) this);
        return original.call();
    }

    @WrapMethod(method = "set")
    private void dxr_mc$publishSet(int index, int value, Operation<Void> original) {
        try (var publication = D3D12SectionGeometry.beginPaletteStorageWrite((BitStorage) (Object) this, false)) {
            original.call(index, value);
            publication.complete();
        }
    }

    @WrapMethod(method = "getAndSet")
    private int dxr_mc$publishExchange(int index, int value, Operation<Integer> original) {
        try (var publication = D3D12SectionGeometry.beginPaletteStorageWrite((BitStorage) (Object) this, false)) {
            int previous = original.call(index, value);
            publication.complete();
            return previous;
        }
    }

    @WrapMethod(method = "fill")
    private void dxr_mc$publishFill(int value, Operation<Void> original) {
        try (var publication = D3D12SectionGeometry.beginPaletteStorageWrite((BitStorage) (Object) this, true)) {
            original.call(value);
            publication.complete();
        }
    }

    @WrapMethod(method = "copy")
    private BitStorage dxr_mc$copyContent(Operation<BitStorage> original) {
        var copy = D3D12SectionGeometry.beginPaletteStorageCopy((BitStorage) (Object) this);
        BitStorage result = original.call();
        D3D12SectionGeometry.finishPaletteStorageCopy(copy, result);
        return result;
    }
}
