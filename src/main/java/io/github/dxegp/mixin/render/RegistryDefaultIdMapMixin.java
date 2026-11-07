package io.github.dxegp.mixin.render;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.dxegp.experimental.raytracing.D3D12RtIdMaps;
import net.minecraft.core.DefaultedMappedRegistry;
import net.minecraft.core.Holder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(DefaultedMappedRegistry.class)
public abstract class RegistryDefaultIdMapMixin {
    @WrapOperation(method = "register", at = @At(value = "FIELD",
            target = "Lnet/minecraft/core/DefaultedMappedRegistry;defaultValue:Lnet/minecraft/core/Holder$Reference;", opcode = 181))
    private void dxr_mc$default(DefaultedMappedRegistry<?> registry, Holder.Reference<?> value, Operation<Void> original) {
        D3D12RtIdMaps.writeDefault(registry, value, () -> original.call(registry, value));
    }
}
