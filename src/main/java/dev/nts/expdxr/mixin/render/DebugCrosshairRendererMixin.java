package dev.nts.expdxr.mixin.render;

import dev.nts.expdxr.dxrmc.nvidia.dlss.DeepLearningSuperSampling;
import net.minecraft.client.renderer.DebugCrosshairRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * P109：F3 的「3D 十字准星」不进 DLSS/DLAA 的时间性输入。
 *
 * <p>症状：那个小 gizmo 是**世界对齐**的（转视角时跟着转），却画进 `mainRenderTarget`
 * （`DebugCrosshairRenderer.java:68`）而且没有任何运动矢量；NGX 于是把它当静止的世界内容做
 * 时间累积，转视角时拖出一条鬼影/涂抹。
 *
 * <p>为什么不用 reactive mask：本机这版 NGX 运行库没有这个能力 ——
 * `MCDR\build\_dl\x\DLSS-main\include\*` 里搜不到任何 `ReactiveMask`，
 * `nvsdk_ngx_helpers_d3d.h:225` 的评价参数结构体也没有该字段。所以只能**推迟画**：在
 * `render` 的 HEAD 把这一笔拦下来交给 `DeepLearningSuperSampling.deferCrosshair(...)` 存着，
 * 等 `GameRendererSmaaWorldMixin` 在 `applyIfEnabled()` 之后调 `replayCrosshair()` 再画。
 * 那一刻升采样已经消费完本帧输入，迟到的十字准星只落在最终画面上，不参与累积。
 *
 * <p>DLSS/DLAA 关闭时整段不生效（`ci.cancel()` 只在 `isEnabled()` 为真时发生），行为与
 * vanilla 逐字节一致。
 */
@Mixin(DebugCrosshairRenderer.class)
public class DebugCrosshairRendererMixin {
    @Inject(method = "render", at = @At("HEAD"), cancellable = true, remap = false)
    private void dxr_mc$deferForDlss(CameraRenderState cameraState, int guiScale, CallbackInfo ci) {
        if (DeepLearningSuperSampling.isEnabled()) {
            DeepLearningSuperSampling.deferCrosshair(
                    (DebugCrosshairRenderer) (Object) this, cameraState, guiScale);
            ci.cancel();
        }
    }
}
