package io.github.dxegp.mixin.render;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import io.github.dxegp.d3d12.render.advanced.mesh.D3D12SectionGeometry;
import io.github.dxegp.experimental.raytracing.D3D12RtPaletteArrays;
import net.minecraft.util.BitStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;

@Mixin(targets = "net.minecraft.world.level.chunk.PalettedContainer$Data")
public abstract class PaletteDataArrayMixin {
    @WrapMethod(method = "copy")
    @Coerce
    private Object dxr_mc$copyData(Operation<Object> original) {
        var copy = D3D12SectionGeometry.beginPaletteDataSnapshot(this);
        Object result = original.call();
        D3D12SectionGeometry.finishPaletteDataCopy(copy, result);
        return result;
    }

    @WrapOperation(method = {"getSerializedSize", "write"}, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/util/BitStorage;getRaw:()[J"))
    private long[] dxr_mc$borrowSerialization(BitStorage storage, Operation<long[]> original) {
        try (var access = D3D12RtPaletteArrays.borrow(storage)) {
            return original.call(storage);
        }
    }
}
