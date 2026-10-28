package dev.nts.expdxr.mixin.render;

import com.mojang.renderpearl.backend.opengl.GlSurface;
import dev.nts.expdxr.misc.settings.D3D12Settings;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * D3D12 后端下吞掉多余的 GL SwapBuffers。
 *
 * <p>父窗口上只要有一条真的 SwapBuffers 过的 GL 交换链（哪怕只有第 0 帧一次），RTSS 就把 OSD 绑到那条
 * 链上、画进被 D3D12 子窗口整块盖住的内容里 —— OSD 一直在画，用户永远看不到。开关是
 * `[debug] skipGlSwap`（默认 false）。见 docs/misc/mixins-and-earlywindow.md §3.2
 */
@Mixin(GlSurface.class)
public class GlSurfacePresentMixin {
    @Inject(method = "present", at = @At("HEAD"), cancellable = true)
    private void dxr_mc$onPresent(CallbackInfo ci) {
        if (D3D12Settings.INSTANCE.getSkipGlSwap()) ci.cancel();
    }
}
