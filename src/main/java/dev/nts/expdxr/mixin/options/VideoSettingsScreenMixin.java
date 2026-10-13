package dev.nts.expdxr.mixin.options;

import dev.nts.expdxr.d3d12.render.backend.D3D12AntiAliasing;
import java.util.Arrays;

import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.OptionsList;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Puts DXRMC's anti-aliasing mode into the vanilla video-settings screen.
 *
 * <p>Vanilla assembles that screen from three static arrays -
 * {@code displayOptions}, {@code qualityOptions} and {@code preferenceOptions} -
 * so the new entry is appended to the quality array, which places it at the end
 * of the quality section, right after vanilla's own texture/mipmap/anisotropy
 * options. Appending to the array rather than injecting a widget into
 * {@code addOptions} keeps vanilla's two-per-row layout intact.
 *
 * <p>Like vanilla's anisotropy option, the button is greyed out whenever the
 * setting cannot do anything - here, whenever the game is not rendering through
 * the D3D12 backend.
 */
@Mixin(VideoSettingsScreen.class)
public abstract class VideoSettingsScreenMixin {
    @Inject(method = "qualityOptions", at = @At("RETURN"), cancellable = true)
    private static void dxr_mc$addAntiAliasing(Options options, CallbackInfoReturnable<OptionInstance<?>[]> cir) {
        OptionInstance<?>[] vanilla = cir.getReturnValue();
        OptionInstance<?>[] extended = Arrays.copyOf(vanilla, vanilla.length + 1);
        extended[vanilla.length] = D3D12AntiAliasing.option();
        cir.setReturnValue(extended);
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void dxr_mc$updateAntiAliasingButton(CallbackInfo ci) {
        OptionsList list = ((OptionsSubScreenAccessor) (Object) this).dxr_mc$getOptionsList();
        if (list == null) {
            return;
        }
        AbstractWidget button = list.findOption(D3D12AntiAliasing.option());
        if (button != null) {
            button.active = D3D12AntiAliasing.isAvailable();
        }
    }
}