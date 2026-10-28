package dev.nts.expdxr.mixin.core;

import dev.nts.expdxr.misc.fmlelw_control.EarlyWindowSurface;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 退出前把接管来的 SDL 窗口刷黑。
 *
 * <p>必须在 HEAD：{@code close()} 先销毁交换链、{@code finally} 才销毁窗口，中间 SDL 会重铺最后
 * present 的那一帧（加载屏）。见 docs/misc/mixins-and-earlywindow.md §5.7</p>
 */
@Mixin(Minecraft.class)
public class MinecraftCloseEarlyWindowMixin {
    @Inject(method = "close", at = @At("HEAD"), remap = false)
    private void dxr_mc$clearEarlyWindow(CallbackInfo ci) {
        EarlyWindowSurface.paintBlack();
    }
}