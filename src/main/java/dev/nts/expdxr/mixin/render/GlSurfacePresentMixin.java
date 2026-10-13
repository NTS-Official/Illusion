package dev.nts.expdxr.mixin.render;

import com.mojang.blaze3d.opengl.GlSurface;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * RTSS/OSD 诊断 + 修复（P68）。
 *
 * 背景（探针矩阵见 _port/osdprobe.cpp）：
 * - D3D12 顶层/子窗口 + FLIP_DISCARD+TEARING + 异步呈现线程 + 独显 —— 都能出 OSD；
 * - 父窗口上只要有一条"真的 SwapBuffers 过"的 GL 交换链（哪怕只有第 0 帧一次）—— OSD 变 0 像素；
 * - 建了 GL 上下文但从不 SwapBuffers —— OSD 正常。
 * 即 RTSS 会把 OSD 绑到"真正呈现过"的那条链上，而 GL 的内容被我们的 D3D12 子窗口整块盖住，
 * 于是 OSD 一直在画、用户却永远看不到。
 *
 * 本 mixin 先做取证（打印前 5 次调用的栈），再按开关吞掉 GL 的 SwapBuffers：
 * 我们的后端已经负责这一帧的呈现，GL 那次是多余的。
 */
@Mixin(GlSurface.class)
public class GlSurfacePresentMixin {

    private static int dxr_mc$presentCount = 0;

    @Inject(method = "present", at = @At("HEAD"), cancellable = true)
    private void dxr_mc$onPresent(CallbackInfo ci) {
        int n = ++dxr_mc$presentCount;
        if (n <= 5) {
            new Throwable("dxr_mc: GlSurface.present() call #" + n).printStackTrace();
        }
        if (System.getenv("DXR_MC_SKIP_GL_SWAP") != null) {
            ci.cancel();
        }
    }
}