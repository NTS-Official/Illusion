package io.github.dxegp.mixin.render;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.dxegp.d3d12.render.advanced.mesh.D3D12SectionGeometry;
import io.github.dxegp.experimental.raytracing.D3D12RtLightArrays;
import net.minecraft.world.level.chunk.DataLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(DataLayer.class)
public abstract class LightLayerPublicationMixin {
    @Inject(method = "<init>([B)V", at = @At("RETURN"))
    private void dxr_mc$constructed(byte[] bytes, CallbackInfo callback) {
        D3D12RtLightArrays.constructed((DataLayer) (Object) this, bytes);
    }

    @WrapMethod(method = "getData")
    private byte[] dxr_mc$expose(Operation<byte[]> original) {
        return D3D12RtLightArrays.read((DataLayer) (Object) this, () -> original.call());
    }

    @WrapOperation(method = "set(II)V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/chunk/DataLayer;getData:()[B"))
    private byte[] dxr_mc$borrowSet(DataLayer layer, Operation<byte[]> original) {
        try (var access = D3D12RtLightArrays.borrow(layer)) {
            return original.call(layer);
        }
    }

    @WrapOperation(method = "copy", at = @At(value = "NEW",
            target = "([B)Lnet/minecraft/world/level/chunk/DataLayer;"))
    private DataLayer dxr_mc$ownCopy(byte[] bytes, Operation<DataLayer> original) {
        DataLayer result = original.call(bytes);
        D3D12RtLightArrays.own(result, bytes);
        return result;
    }

    @WrapMethod(method = "copy")
    private DataLayer dxr_mc$copy(Operation<DataLayer> original) {
        var copy = D3D12SectionGeometry.beginLightLayerCopy((DataLayer) (Object) this);
        DataLayer result = original.call();
        D3D12SectionGeometry.finishLightLayerCopy(copy, result);
        return result;
    }

    @WrapMethod(method = "set(IIII)V")
    private void dxr_mc$publishSet(int blockX, int blockY, int blockZ, int value, Operation<Void> original) {
        try (var publication = D3D12SectionGeometry.beginLightLayerPublication((DataLayer) (Object) this, false)) {
            original.call(blockX, blockY, blockZ, value);
            if (publication != null) {
                publication.complete();
            }
        }
    }

    @WrapMethod(method = "fill(I)V")
    private void dxr_mc$publishFill(int value, Operation<Void> original) {
        try (var publication = D3D12SectionGeometry.beginLightLayerPublication((DataLayer) (Object) this, true)) {
            original.call(value);
            if (publication != null) {
                publication.complete();
            }
        }
    }
}
