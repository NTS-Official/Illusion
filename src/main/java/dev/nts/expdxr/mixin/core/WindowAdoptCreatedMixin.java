package dev.nts.expdxr.mixin.core;

import com.mojang.blaze3d.platform.Window;
import com.mojang.renderpearl.api.device.GpuBackend;
import dev.nts.expdxr.d3d12.backend.D3D12Backend;
import dev.nts.expdxr.misc.fmlelw_control.EarlyWindowSurface;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 接管 provider 已建好的 SDL 窗口，否则加载窗与游戏窗会是两个窗口。
 *
 * <p>句柄只能走系统属性（provider 在 early-service 类加载器里，与游戏不共享静态），读后必须立刻清除；
 * 非 D3D12 后端必须保留不清，因为每个后端尝试都会建一次窗口。
 * 见 docs/misc/mixins-and-earlywindow.md §5.4</p>
 */
@Mixin(Window.class)
public abstract class WindowAdoptCreatedMixin {
    /** provider 写入；被接管时清除。 */
    private static final String HANDLE_PROPERTY = "dxrmc.earlywindow.handle";

    /** 告知 provider 窗口已被接管而非丢弃。 */
    private static final String ADOPTED_PROPERTY = "dxrmc.earlywindow.adopted";

    @Inject(method = "createWindow", at = @At("HEAD"), cancellable = true)
    private void dxr_mc$adoptEarlyWindow(GpuBackend backend, int width, int height, String title,
            CallbackInfoReturnable<Long> cir) {
        String pending = System.getProperty(HANDLE_PROPERTY);
        if (pending == null) {
            return;
        }
        if (!(backend instanceof D3D12Backend)) {
            // 不能清属性：每个后端尝试都会建一次窗口，我们的后端可能第二次才起来。
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
