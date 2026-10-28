package dev.nts.expdxr.mixin.options;

import dev.nts.expdxr.d3d12.render.advanced.D3D12AntiAliasing;
import net.minecraft.client.GraphicsPreset;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 画质预设联动抗锯齿（fast=off、fancy=SMAA、fabulous=TAA、custom 不动）。
 *
 * <p>必须在 {@code GraphicsPreset.apply} 之后、{@code isApplyingGraphicsPreset} 清空之前：否则写抗锯齿会被
 * 当成手动改动，把刚选的预设弹回 custom。是否该写的判据全在 {@code D3D12AntiAliasing.applyFromPreset}。
 * 见 docs/misc/mixins-and-earlywindow.md §3.7</p>
 */
@Mixin(Options.class)
public abstract class OptionsGraphicsPresetMixin {
    @Inject(method = "applyGraphicsPreset", at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/GraphicsPreset;" +
                             "apply(Lnet/minecraft/client/Minecraft;)V",
                     shift = At.Shift.AFTER), remap = false)
    private void dxr_mc$applyPresetToAntiAliasing(GraphicsPreset preset, CallbackInfo ci) {
        D3D12AntiAliasing.applyFromPreset(preset.getSerializedName());
    }
}