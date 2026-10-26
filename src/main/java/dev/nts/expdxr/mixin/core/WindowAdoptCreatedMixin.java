package dev.nts.expdxr.mixin.core;

import com.mojang.blaze3d.platform.Window;
import com.mojang.renderpearl.api.device.GpuBackend;
import dev.nts.expdxr.d3d12.backend.D3D12Backend;
import dev.nts.expdxr.misc.EarlyWindowSurface;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * P268: let Minecraft adopt the SDL window that our early window provider already created instead of spawning a
 * second one. Without this the early loading window and the game window are two different windows.
 *
 * <p>The provider runs in the loader's early-service class loader, so it cannot share classes with the game.
 * The window handle therefore travels through a JVM wide system property (removed again as soon as it is
 * consumed, so a later window cannot adopt a stale handle).</p>
 *
 * <p>P269: only for our Direct3D 12 backend. On OpenGL / Vulkan the vanilla path must create the window itself
 * (it needs its own GL attributes / surface flags), so the handle is dropped and the provider hides its window.</p>
 */
@Mixin(Window.class)
public abstract class WindowAdoptCreatedMixin {
    /** Written by {@code DxrmcEarlyWindowProvider.initialize()} (early-service jar). */
    private static final String HANDLE_PROPERTY = "dxrmc.earlywindow.handle";

    /** Set here so the provider knows its window was taken over instead of being abandoned. */
    private static final String ADOPTED_PROPERTY = "dxrmc.earlywindow.adopted";

    @Inject(method = "createWindow", at = @At("HEAD"), cancellable = true)
    private void dxr_mc$adoptEarlyWindow(GpuBackend backend, int width, int height, String title,
            CallbackInfoReturnable<Long> cir) {
        String pending = System.getProperty(HANDLE_PROPERTY);
        if (pending == null) {
            return;
        }
        if (!(backend instanceof D3D12Backend)) {
            // P269: vanilla creates (and shows) its own window; the provider hides ours on hand-over.
            // Keep the property: the game creates the window once per backend attempt, so our backend may
            // still come up second. The provider clears the property on hand-over either way.
            return;
        }
        System.clearProperty(HANDLE_PROPERTY);
        long handle;
        try {
            handle = Long.parseLong(pending);
        } catch (NumberFormatException ignored) {
            return;
        }
        if (handle != 0L) {
            System.setProperty(ADOPTED_PROPERTY, "1");
            EarlyWindowSurface.remember(handle);
            cir.setReturnValue(handle);
        }
    }
}
