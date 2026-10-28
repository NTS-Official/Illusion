package dev.nts.expdxr.mixin.options;

import dev.nts.expdxr.d3d12.render.advanced.D3D12AntiAliasing;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 把抗锯齿模式写进原版 {@code options.txt}。
 *
 * <p>它不是 {@code Options} 的字段，原版 {@code processDumpedOptions} 看不到；而那个 pass 的参数类型
 * {@code Options.OptionAccess} 是私有嵌套接口，所以只能在 {@code load} / {@code save} 的 TAIL 用同一份
 * {@code key:jsonValue} 格式自己读写。见 docs/misc/mixins-and-earlywindow.md §3.7</p>
 */
@Mixin(Options.class)
public abstract class OptionsAntiAliasingMixin {
    @Inject(method = "load", at = @At("TAIL"))
    private void dxr_mc$loadAntiAliasing(CallbackInfo ci) {
        D3D12AntiAliasing.load(
                ((Options) (Object) this).getFile());
    }

    @Inject(method = "save", at = @At("TAIL"))
    private void dxr_mc$saveAntiAliasing(CallbackInfo ci) {
        D3D12AntiAliasing.save(
                ((Options) (Object) this).getFile());
    }
}