package dev.nts.expdxr.mixin.options;

import dev.nts.expdxr.dxrmc.framegen.FrameGeneration;
import dev.nts.expdxr.misc.settings.D3D12HdrPresentation;
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
 * 把不挂在 {@code Options} 字段上的设置项写进原版 {@code options.txt}。
 *
 * <p>这些值都是真的 {@code OptionInstance}，但不是 {@code Options} 的字段，原版
 * {@code processDumpedOptions} 看不到它们，所以只能在 {@code load} / {@code save} 的 TAIL 用同一份
 * {@code key:jsonValue} 格式自己读写。与 {@link OptionsAntiAliasingMixin} 注入同样两个方法是安全的：
 * 各自只重写自己的键。
 * 见 docs/misc/mixins-and-earlywindow.md §3.7、docs/misc/settingscreen/README.md</p>
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

    /** HDR 改了但没重启时，把「需要重启」提示变成 true（位置/文案全由原版负责）。 */
    @Inject(method = "isRestartRequiredToApplyVideoSettings", at = @At("RETURN"), cancellable = true)
    private void dxr_mc$restartRequiredForHdr(CallbackInfoReturnable<Boolean> cir) {
        if (Boolean.TRUE.equals(cir.getReturnValue()) || !D3D12HdrPresentation.isRestartPending()) {
            return;
        }
        cir.setReturnValue(true);
    }
}