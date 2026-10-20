package dev.nts.expdxr.mixin;

import dev.nts.expdxr.compat.FpsLimitCompat;
import com.mojang.blaze3d.platform.FramerateLimitTracker;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 选了 "Never" 时，AFK / 最小化都不再降低帧率上限（直接用玩家设定的 framerateLimit）。
 *
 * <p>Java 版移植自 whynotkeepyourfpsup 的同名 Kotlin mixin。
 */
@Mixin(FramerateLimitTracker.class)
public class FpsLimitTrackerMixin {
    @Shadow
    @Final
    private Options options;

    @Inject(method = "getFramerateLimit", at = @At("HEAD"), cancellable = true)
    private void dxrmc$keepFramerate(CallbackInfoReturnable<Integer> cir) {
        if (FpsLimitCompat.isNever(this.options.inactivityFpsLimit().get())) {
            cir.setReturnValue(this.options.framerateLimit().get());
        }
    }
}
