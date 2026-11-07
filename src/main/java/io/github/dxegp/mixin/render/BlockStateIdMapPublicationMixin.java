package io.github.dxegp.mixin.render;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.dxegp.experimental.raytracing.D3D12RtIdMaps;
import it.unimi.dsi.fastutil.objects.Reference2IntMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;

@Mixin(targets = "net.neoforged.neoforge.registries.NeoForgeRegistryCallbacks$BlockCallbacks$ClearableObjectIntIdentityMap", remap = false)
public abstract class BlockStateIdMapPublicationMixin {
    @WrapMethod(method = "clear", remap = false)
    private void dxr_mc$clear(Operation<Void> original) {
        try (var write = D3D12RtIdMaps.writing(this, true)) {
            original.call();
            write.complete();
        }
    }

    @WrapOperation(method = "clear", remap = false, at = @At(value = "INVOKE",
            target = "Lit/unimi/dsi/fastutil/objects/Reference2IntMap;clear:()V", remap = false))
    private void dxr_mc$reverse(Reference2IntMap<?> ids, Operation<Void> original) {
        D3D12RtIdMaps.clear(this, () -> original.call(ids));
    }

    @WrapOperation(method = "clear", remap = false, at = @At(value = "INVOKE",
            target = "Ljava/util/List;clear:()V", remap = false))
    private void dxr_mc$values(List<?> values, Operation<Void> original) {
        D3D12RtIdMaps.clear(this, () -> original.call(values));
    }
}
