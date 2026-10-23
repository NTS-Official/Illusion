package dev.nts.expdxr.mixin.render;

import com.mojang.renderpearl.backend.opengl.GlSurface;
import dev.nts.expdxr.d3d12.D3D12Settings;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * D3D12 后端下吞掉 GL 多余的 SwapBuffers（P68）。
 *
 * 背景（探针矩阵见 _port/osdprobe.cpp）：
 * - D3D12 顶层/子窗口 + FLIP_DISCARD+TEARING + 异步呈现线程 + 独显 —— 都能出 OSD；
 * - 父窗口上只要有一条"真的 SwapBuffers 过"的 GL 交换链（哪怕只有第 0 帧一次）—— OSD 变 0 像素；
 * - 建了 GL 上下文但从不 SwapBuffers —— OSD 正常。
 * 即 RTSS 会把 OSD 绑到"真正呈现过"的那条链上，而 GL 的内容被我们的 D3D12 子窗口整块盖住，
 * 于是 OSD 一直在画、用户却永远看不到。
 *
 * 我们的后端已经负责这一帧的呈现，GL 那次是多余的，按配置 `[debug] skipGlSwap`（默认 false）吞掉。
 */
@Mixin(GlSurface.class)
public class GlSurfacePresentMixin {
    @Inject(method = "present", at = @At("HEAD"), cancellable = true)
    private void dxr_mc$onPresent(CallbackInfo ci) {
        if (D3D12Settings.INSTANCE.getSkipGlSwap()) ci.cancel();
    }
}
