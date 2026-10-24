package dev.nts.expdxr.mixin.render;

import net.minecraft.client.renderer.DebugCrosshairRenderer;
import org.spongepowered.asm.mixin.Mixin;

/**
 * P109 的 3D 准星接管点 —— <b>P223 起停用，该类目前是一个空标记</b>。
 *
 * <p>原设计：26.3 的 `DebugCrosshairRenderer.render(CameraRenderState, int, GpuTextureView,
 * GpuTextureView)` 在世界层里画那个世界对齐的小 gizmo，它没有运动矢量，会被 DLSS/DLAA 的时间
 * 累积当成静止内容 → 转视角时拖鬼影。原来的做法是在 HEAD 处 `ci.cancel()` 把这次绘制推迟到
 * 世界层搬进 GUI 目标之后再补画。
 *
 * <p>为什么停用：补画链实测**一笔都提交不上去** —— 同一次运行里 `P219` 每帧 pending=true、
 * `P221` 补画目标与尺寸都对（`D3D12GUIRenderTarget` 854x480）、`P222` 无异常，但 `P220`
 * （补画期间的管线绑定点）0 命中；同时因为不再取消原版绘制，3D 准星关掉后仍会被 replay
 * 重复画出来。现在把整条接管停掉：3D 准星开 → 原版就地画；关 → 原版画 2D 十字，与原版一致。
 *
 * <p>恢复条件：查清前端 `CommandEncoder.createRenderPass(...)` / `RenderPass.setPipeline(...)`
 * 在 replay 期间为什么没有落到 `D3D12RenderPassBackend`，修好后再把 defer + cancel 收回来，
 * 并顺带解决 gizmo 的 DLSS 拖影。
 */
@Mixin(DebugCrosshairRenderer.class)
public class DebugCrosshairRendererMixin {
    // 故意为空：不留 @Inject，保证我们既不改写也不取消原版的绘制。
}
