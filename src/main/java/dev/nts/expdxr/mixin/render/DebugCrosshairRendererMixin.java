package dev.nts.expdxr.mixin.render;

import net.minecraft.client.renderer.DebugCrosshairRenderer;
import org.spongepowered.asm.mixin.Mixin;

/**
 * 3D 准星接管点 —— **已停用，该类目前是一个空标记**（删掉不改变行为）。
 *
 * <p>原设计：gizmo 没有运动矢量，会被 DLSS/DLAA 当成静止内容，转视角时拖鬼影，所以在 HEAD
 * `ci.cancel()` 把这次绘制推迟到世界层搬进 GUI 目标之后补画。实测补画链一笔都提交不上去（补画期间的
 * 管线绑定点 0 命中），故整条接管停掉。恢复条件：查清 `CommandEncoder.createRenderPass` /
 * `RenderPass.setPipeline` 为何没落到 `D3D12RenderPassBackend`。见 docs/misc/mixins-and-earlywindow.md §3.8
 */
@Mixin(DebugCrosshairRenderer.class)
public class DebugCrosshairRendererMixin {
    // 故意为空：不留 @Inject，保证我们既不改写也不取消原版的绘制。
}
