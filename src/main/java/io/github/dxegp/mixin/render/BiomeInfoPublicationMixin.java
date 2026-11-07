package io.github.dxegp.mixin.render;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.dxegp.experimental.raytracing.D3D12RtBiomeInputs;
import io.github.dxegp.experimental.raytracing.D3D12RtModelSupport;
import net.neoforged.neoforge.common.world.ModifiableBiomeInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = ModifiableBiomeInfo.class, remap = false)
public abstract class BiomeInfoPublicationMixin {
    @WrapOperation(method = "applyBiomeModifiers", at = @At(value = "FIELD",
            target = "Lnet/neoforged/neoforge/common/world/ModifiableBiomeInfo;modifiedBiomeInfo:Lnet/neoforged/neoforge/common/world/ModifiableBiomeInfo$BiomeInfo;",
            opcode = 181))
    private void dxr_mc$info(ModifiableBiomeInfo owner, ModifiableBiomeInfo.BiomeInfo value, Operation<Void> original) {
        D3D12RtBiomeInputs.write(owner, value, () -> original.call(owner, value));
    }

    @Inject(method = {"get", "getModifiedBiomeInfo"}, at = @At("HEAD"))
    private void dxr_mc$read(CallbackInfoReturnable<ModifiableBiomeInfo.BiomeInfo> callback) {
        D3D12RtModelSupport.recordBiomeInfoRead((ModifiableBiomeInfo) (Object) this);
    }
}
