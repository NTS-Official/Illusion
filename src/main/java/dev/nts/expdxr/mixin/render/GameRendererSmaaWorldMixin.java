package dev.nts.expdxr.mixin.render;

import dev.nts.expdxr.d3d12.extern.MCDR_ABI;
import dev.nts.expdxr.d3d12.render.advanced.smaa.D3D12GUISplit;
import dev.nts.expdxr.d3d12.render.advanced.smaa.D3D12SMAAWorld;
import dev.nts.expdxr.d3d12.render.advanced.taa.D3D12TAA;
import dev.nts.expdxr.dxrmc.sr.dlss.DeepLearningSuperSampling;
import dev.nts.expdxr.dxrmc.sr.SuperResolution;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * SMAA / 超分的挂点：世界层画完、GUI 还没画的那一刻 —— 全帧唯一这样的时刻。
 *
 * <p>`GuiRenderer.render()` 这个 INVOKE 前面是 `fogRenderer.endFrame()`（世界渲染与后处理都已结束），
 * 后面才是 GUI 的绘制。退到最终 blit 上做会把界面一起柔化。逻辑都在 Kotlin 的 `applyIfEnabled()`。
 * 见 docs/misc/mixins-and-earlywindow.md §3.6
 */
@Mixin(GameRenderer.class)
public class GameRendererSmaaWorldMixin {

    @Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/render/GuiRenderer;render()V"), remap = false)
    private void dx12_smaaWorldOnly(CallbackInfo ci) {
        // TAA 与 SMAA 是同一个选项的两个取值，互斥；谁被选中谁动手。
        D3D12TAA.applyIfEnabled();
        D3D12SMAAWorld.applyIfEnabled();
        // Evaluate 只能在这里：此刻主目标颜色里才有本帧的世界层。排在 SMAA 之后 —— 先在世界分辨率上
        // 做 AA，再让后端把结果升采样进 GUI 目标。门面按 `[sr] backend` 分流。
        SuperResolution.applyIfEnabled();
        // 世界层已画完，记 eSimulationEnd + eRenderSubmitStart。
        DeepLearningSuperSampling.pclRenderSubmitStart();
        // 世界层（含 AA / 超分结果）随后搬进 GUI 自己的窗口尺寸目标 —— 顺序不能反。
        D3D12GUISplit.prepareForGui();
        // F3 的 3D 准星补画已停用（见 DebugCrosshairRendererMixin 类注释）。
        // DeepLearningSuperSampling.replayCrosshair();
    }

    /**
     * 每帧兜底：抗锯齿锁与进出 DLSS 模式的状态机。速度 pass 已移到 `LevelRendererMainPassDepthMixin`
     * —— 此处按执行序排在三次清深度之后，深度恒为 0，运动矢量会被写成整张 0。
     */
    @Inject(method = "render",
            at = @At(value = "INVOKE",
                     target = "Lcom/mojang/renderpearl/api/commands/CommandEncoder;" +
                             "clearDepthTexture(Lcom/mojang/renderpearl/api/textures/GpuTexture;D)V",
                     shift = At.Shift.BEFORE),
            remap = false)
    private void dxr_mc$dlssBeforeDepthClear(CallbackInfo ci) {
        // 这两件都是 DLSS 自家记账；选了 FSR / XeSS 时不该再让 DLSS 推自己的开关 —— 会每帧付一次
        // NGX 可用性探测，还与 `[sr] backend` 打架。
        if (SuperResolution.backendId() != MCDR_ABI.SR_BACKEND_DLSS) {
            return;
        }
        DeepLearningSuperSampling.reconcileAntiAliasingEveryFrame();
        // Reflex PCL stats
        DeepLearningSuperSampling.pclFrameBegin();
    }
}