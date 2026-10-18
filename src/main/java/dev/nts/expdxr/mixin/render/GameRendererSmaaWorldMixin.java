package dev.nts.expdxr.mixin.render;

import dev.nts.expdxr.d3d12.render.advanced.smaa.D3D12GUISplit;
import dev.nts.expdxr.d3d12.render.advanced.smaa.D3D12SMAAWorld;
import dev.nts.expdxr.d3d12.render.advanced.taa.D3D12TAA;
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
        // P77：TAA 与 SMAA 是同一个选项的两个取值，互斥；排在 SMAA 旁边，谁被选中谁动手。
        D3D12TAA.applyIfEnabled();
        D3D12SMAAWorld.applyIfEnabled();
        // P108d：DLSS 的 Evaluate 挂在这里 —— 只有这一刻主目标的**颜色**里才有本帧的世界层。
        // 排在 SMAA 之后：先在世界分辨率上做 AA，再让 DLSS 把结果升采样进 GUI 目标。
        DeepLearningSuperSampling.applyIfEnabled();
        // P117：世界层已画完 —— eSimulationEnd + eRenderSubmitStart。
        DeepLearningSuperSampling.pclRenderSubmitStart();
        // P109：F3 的 3D 十字准星推迟到这里才画 —— 上面那一笔已经把本帧世界层（不含它）喂给
        // DLSS/DLAA 评价了，所以它进不了 NGX 的时间性输入（不会拖影），但仍然画在同一个主目标上。
        // 必须留在**调用点**、不能塞进 applyIfEnabled()：那条路径有多条提前 return，放进去就会在
        // "本帧不评价"的时候把十字准星整个丢掉。
        DeepLearningSuperSampling.replayCrosshair();
        // P106：世界层（含 SMAA / DLSS 结果）随后被搬进 GUI 自己的窗口尺寸目标 —— 顺序不能反。
        D3D12GUISplit.prepareForGui();
    }

    /**
     * 每帧兜底（抗锯齿锁 + 进出 DLSS 模式的状态机）。
     *
     * <p>P76c（用户 m07942）：运动矢量的**速度 pass 已经从这里移走**，改由
     * `LevelRendererMainPassDepthMixin` 挂在帧图 main pass 的 RETURN 上录。原因：这处注入按
     * `LevelRendererMainPassDepthMixin` 类注释里的执行序排在三次清深度中的 (1)(2) **之后**，
     * 实测此刻主目标深度是 `mean=0.0/0.0/0.0/0.0` —— 速度 pass 拿到全 0 深度会把运动矢量写成一整张 0，
     * DLSS 于是把移动的画面当静止画面累积，表现为"只有开 DLSS 才有、只随运动出现的拖影"。
     * 这里只保留与 DLSS 开关状态有关的记账，不再碰深度/速度通道。
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
        // P117：PCL stats 的帧开始标记（必须在任何 evaluate 之前）。
        DeepLearningSuperSampling.pclFrameBegin();
    }
}