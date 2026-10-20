package dev.nts.expdxr.mixin.options;

import dev.nts.expdxr.d3d12.render.advanced.vrs.D3D12RenderScale;
import dev.nts.expdxr.d3d12.render.advanced.vrs.D3D12VariableShadingRate;
import dev.nts.expdxr.dxrmc.nvidia.dlss.DeepLearningSuperSampling;
import dev.nts.expdxr.dxrmc.nvidia.reflex.NvidiaReflex;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Persists DXRMC's render scale, VRS shading rate and DLSS switch (P108b) in vanilla's own
 * {@code options.txt}.
 *
 * <p>All three are real {@code OptionInstance}s (see {@link D3D12RenderScale},
 * {@link D3D12VariableShadingRate} and {@link DeepLearningSuperSampling}) but none of them is a field of
 * {@code Options}, so vanilla's
 * {@code processDumpedOptions} pass cannot see them - exactly the problem
 * {@link OptionsAntiAliasingMixin} works around for the anti-aliasing mode, with the same
 * {@code key:jsonValue} line format and the same file. The two mixins do inject into the same
 * two methods, which is harmless: each one re-reads the file and only rewrites <em>its own</em>
 * key, so whichever runs first keeps the other's line intact. (For DLSS on a DLL that predates
 * P108b the load order does matter once - see the temporary bridge in
 * {@link DeepLearningSuperSampling}, which re-asserts the legacy gate on the first frame instead of relying on
 * which TAIL injector ran last.)
 *
 * <p>{@code load} returns early when the file does not exist; the TAIL injection therefore only
 * runs on the normal path, and every {@code load} implementation tolerates a missing file.
 * {@code Options.load} itself runs inside {@code Minecraft}'s constructor, long before the main
 * render target exists, which is why the render scale only records its value there and leaves
 * the render-target rebuild to the options's own callback.
 */
@Mixin(Options.class)
public abstract class OptionsRenderOptionsMixin {

    @Inject(method = "load", at = @At("TAIL"))
    private void dxr_mc$loadRenderOptions(CallbackInfo ci) {
        Options options = (Options) (Object) this;
        D3D12RenderScale.load(options.getFile());
        D3D12VariableShadingRate.load(options.getFile());
        DeepLearningSuperSampling.load(options.getFile());
        NvidiaReflex.load(options.getFile());
    }

    @Inject(method = "save", at = @At("TAIL"))
    private void dxr_mc$saveRenderOptions(CallbackInfo ci) {
        Options options = (Options) (Object) this;
        D3D12RenderScale.save(options.getFile());
        D3D12VariableShadingRate.save(options.getFile());
        DeepLearningSuperSampling.save(options.getFile());
        NvidiaReflex.save(options.getFile());
    }
}