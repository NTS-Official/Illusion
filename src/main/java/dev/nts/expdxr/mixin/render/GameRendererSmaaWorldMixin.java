package dev.nts.expdxr.mixin.render;

import dev.nts.expdxr.d3d12.render.advanced.smaa.D3D12GUISplit;
import dev.nts.expdxr.d3d12.render.advanced.smaa.D3D12SMAAWorld;
import dev.nts.expdxr.dxrmc.dlss.DeepLearningSuperSampling;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * P104b：SMAA 的挂点 —— 世界层画完、GUI 还没画的那一刻。
 *
 * 不加这一处，SMAA 就只能在最终 blit 上做，而那一刻「世界 + GUI」已经合成为整帧，
 * 界面会被一起柔化（用户报告的 GUI 发虚）。
 *
 * 注入点选 `GuiRenderer.render()` 这个 INVOKE：它前面是 fogRenderer.endFrame()（世界渲染
 * 与后处理都已结束），后面才是 GUI 的绘制。逻辑全在 Kotlin 的
 * `D3D12SMAAWorld.applyIfEnabled()`，这里只留一行。
 */
@Mixin(GameRenderer.class)
public class GameRendererSmaaWorldMixin {

    @Inject(method = "render",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/gui/render/GuiRenderer;render()V"),
            remap = false)
    private void dx12_smaaWorldOnly(DeltaTracker deltaTracker, boolean advanceGameTime, CallbackInfo ci) {
        D3D12SMAAWorld.applyIfEnabled();
        // P108d：DLSS 的 Evaluate 挂在这里 —— 只有这一刻主目标的**颜色**里才有本帧的世界层。
        // 排在 SMAA 之后：先在世界分辨率上做 AA，再让 DLSS 把结果升采样进 GUI 目标。
        DeepLearningSuperSampling.applyIfEnabled();
        // P106：世界层（含 SMAA / DLSS 结果）随后被搬进 GUI 自己的窗口尺寸目标 —— 顺序不能反。
        D3D12GUISplit.prepareForGui();
    }

    /**
     * P108：DLSS 的**数据面**挂点 —— 比上面那处更早，因为 vanilla 在调 `GuiRenderer.render()` 之前
     * 就把主渲染目标的深度清掉了（26.2 的 `GameRenderer.render`：:443 `clearDepthTexture` 早于
     * :447 `GuiRenderer.render`），而运动矢量要吃本帧深度。
     *
     * <p>P108d（用户 m11364）：这里**只录运动矢量**。实测（native `DXRMC_P108X_COPY_COLOR=1`）
     * 证明此刻主目标的颜色还是清屏色（常量 190,179,167），DLSS 拿它升采样只会得到常量输出 ——
     * 所以真正的 Evaluate 移到上面 SMAA 那处（世界层颜色完整）。逻辑全在 Kotlin 的
     * {@code DeepLearningSuperSampling.captureMotionVectors()}，这里只留一行。
     *
     * <p>P108b（用户 m10920）：SMAA 与 DLSS **互不排斥**，两处注入各判各的开关（DLSS 是自己的
     * 开关，SMAA 那处是抗锯齿模式）。
     */
    @Inject(method = "render",
            at = @At(value = "INVOKE",
                     target = "Lcom/mojang/blaze3d/systems/CommandEncoder;clearDepthTexture(Lcom/mojang/blaze3d/textures/GpuTexture;D)V",
                     shift = At.Shift.BEFORE),
            remap = false)
    private void dxr_mc$dlssBeforeDepthClear(DeltaTracker deltaTracker, boolean advanceGameTime, CallbackInfo ci) {
        // 用户 m05600：DLSS 开启期间抗锯齿锁为 OFF 且界面不可调。每帧兜底重算一次，挡住两个
        // 旁路 —— `Options.load` 上那两条 TAIL 注入（`OptionsAntiAliasingMixin` 与
        // `OptionsRenderOptionsMixin`）先后顺序不由我们决定；`D3D12AntiAliasing.applyFromPreset()`
        // 也会在切画质预设时写这个选项。稳态下只是一次 volatile 读。
        //
        // 必须挂在**这里**（无论 DLSS 开还是关都会跑到），不能挂 `applyIfEnabled()`：
        // "DLSS 关掉 => 把抗锯齿还回去"恰恰发生在开关为 false 的时候。
        DeepLearningSuperSampling.reconcileAntiAliasingEveryFrame();
        DeepLearningSuperSampling.captureMotionVectors();
    }
}