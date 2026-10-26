package dev.nts.expdxr.mixin.core;

import dev.nts.expdxr.misc.EarlyWindowSurface;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * P273: clear the adopted early window before the game tears its surface down.
 *
 * <p>{@code Minecraft.close()} closes the window surface (our D3D12 swapchain) first and only destroys the SDL
 * window in the {@code finally} block. In that window SDL repaints whatever it presented last - our loading
 * screen - which is the flash seen on quit. Painting the window black at the very start of {@code close()}
 * makes that gap invisible.</p>
 */
@Mixin(Minecraft.class)
public class MinecraftCloseEarlyWindowMixin {
    @Inject(method = "close", at = @At("HEAD"), remap = false)
    private void dxr_mc$clearEarlyWindow(CallbackInfo ci) {
        EarlyWindowSurface.paintBlack();
    }
}