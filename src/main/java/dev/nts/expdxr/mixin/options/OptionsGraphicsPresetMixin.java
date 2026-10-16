package dev.nts.expdxr.mixin.options;

import dev.nts.expdxr.d3d12.render.advanced.D3D12AntiAliasing;
import net.minecraft.client.GraphicsPreset;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * P104: folds anti-aliasing into the vanilla graphics presets.
 *
 * <p>Vanilla's {@code GraphicsPreset.apply} only touches the options it knows about; anti-aliasing
 * is DXRMC's own video setting, so it is mapped here right after the preset has done its work:
 * fast = off, fancy = SMAA, fabulous = TAA, custom = left alone (that is the player's own mix).
 *
 * <p>The injection sits after the {@code GraphicsPreset.apply} call and before
 * {@code isApplyingGraphicsPreset} is cleared, so writing the anti-aliasing options is not mistaken
 * for a manual change that would immediately bounce the freshly picked preset back to "custom".
 */
@Mixin(Options.class)
public abstract class OptionsGraphicsPresetMixin {

    @Inject(method = "applyGraphicsPreset",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/GraphicsPreset;apply(Lnet/minecraft/client/Minecraft;)V",
                     shift = At.Shift.AFTER),
            remap = false)
    private void dxr_mc$applyPresetToAntiAliasing(GraphicsPreset preset, CallbackInfo ci) {
        D3D12AntiAliasing.applyFromPreset(preset.getSerializedName());
    }
}