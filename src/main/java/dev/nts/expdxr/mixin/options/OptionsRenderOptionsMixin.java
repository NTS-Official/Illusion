package dev.nts.expdxr.mixin.options;

import dev.nts.expdxr.dxrmc.framegen.FrameGeneration;
import dev.nts.expdxr.misc.D3D12HdrPresentation;
import dev.nts.expdxr.d3d12.render.advanced.vrs.D3D12RenderScale;
import dev.nts.expdxr.d3d12.render.advanced.vrs.D3D12VariableShadingRate;
import dev.nts.expdxr.dxrmc.sr.dlss.DeepLearningSuperSampling;
import dev.nts.expdxr.dxrmc.nvidia.reflex.NvidiaReflex;
import dev.nts.expdxr.dxrmc.sr.SuperResolution;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

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
        D3D12HdrPresentation.load(options.getFile());
        FrameGeneration.load(options.getFile());
        SuperResolution.load(options.getFile());
    }

    @Inject(method = "save", at = @At("TAIL"))
    private void dxr_mc$saveRenderOptions(CallbackInfo ci) {
        Options options = (Options) (Object) this;
        D3D12RenderScale.save(options.getFile());
        D3D12VariableShadingRate.save(options.getFile());
        DeepLearningSuperSampling.save(options.getFile());
        NvidiaReflex.save(options.getFile());
        D3D12HdrPresentation.save(options.getFile());
        FrameGeneration.save(options.getFile());
        SuperResolution.save(options.getFile());
    }

    /**
     * P198：HDR 模式改了但还没重启 —— 借用原版那条“需要重启”的红色提示。
     *
     * <p>原版就是这么提示图形后端切换的：{@code VideoSettingsScreen} 在
     * {@code isRestartRequiredToApplyVideoSettings()} 为真时加一个
     * {@code options.restartRequired} 的红色 StringWidget。我们只负责把“要不要提示”这件事
     * 补上一票，提示的位置、颜色、文案全部由原版负责。
     */
    @Inject(method = "isRestartRequiredToApplyVideoSettings", at = @At("RETURN"), cancellable = true)
    private void dxr_mc$restartRequiredForHdr(CallbackInfoReturnable<Boolean> cir) {
        if (Boolean.TRUE.equals(cir.getReturnValue()) || !D3D12HdrPresentation.isRestartPending()) {
            return;
        }
        cir.setReturnValue(true);
    }
}