package dev.nts.expdxr.mixin.client;

import dev.nts.expdxr.compat.FpsLimitCompat;
import com.mojang.blaze3d.platform.FramerateLimitTracker;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 限帧档为 "Never" 时取消失焦降帧，直接返回玩家设定的 framerateLimit。 */
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
