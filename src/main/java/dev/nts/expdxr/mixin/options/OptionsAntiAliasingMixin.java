package dev.nts.expdxr.mixin.options;

import dev.nts.expdxr.d3d12.render.advanced.D3D12AntiAliasing;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Persists DXRMC's antialiasing mode in vanilla's own {@code options.txt}.
 *
 * <p>The options is a real {@code OptionInstance} (see {@link D3D12AntiAliasing})
 * but it is not a field of {@code Options}, so vanilla's
 * {@code processDumpedOptions} pass cannot see it. Rather than reach into that
 * pass - its argument type, {@code Options.OptionAccess}, is a private nested
 * interface - the value is written next to vanilla's own options at the tail of
 * {@code save} and read back at the tail of {@code load}, using the same
 * {@code key:jsonValue} line format and the same file.
 *
 * <p>{@code load} returns early when the file does not exist; the TAIL
 * injection therefore only runs on the normal path, and {@code load} itself
 * tolerates a missing file.
 */
@Mixin(Options.class)
public abstract class OptionsAntiAliasingMixin {
    @Inject(method = "load", at = @At("TAIL"))
    private void dxr_mc$loadAntiAliasing(CallbackInfo ci) {
        D3D12AntiAliasing.load(((Options) (Object) this).getFile());
    }

    @Inject(method = "save", at = @At("TAIL"))
    private void dxr_mc$saveAntiAliasing(CallbackInfo ci) {
        D3D12AntiAliasing.save(((Options) (Object) this).getFile());
    }
}