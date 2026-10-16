package dev.nts.expdxr.mixin.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import dev.nts.expdxr.d3d12.render.advanced.vrs.D3D12RenderScale;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * P105b：让世界层的辅助渲染目标也按渲染尺寸分配 —— 把 P105 的「渲染尺寸与窗口解耦」补完整。
 *
 * <p>崩溃根因（2026-10-16 18:36:53 客户端崩溃报告，`Source texture (1920x1000) is not large
 * enough to read a rectangle of 2560x1334 at 0x0`）：原版
 * `RenderTarget.copyDepthFrom`（`_port\mcsrc\com\mojang\blaze3d\pipeline\RenderTarget.java:84`）
 * 拿**目标**的 `this.width/height` 当拷贝矩形，却从**源**的深度纹理读：
 * <pre>
 * copyTextureToTexture(source.depthTexture, this.depthTexture, 0,0,0,0,0, this.width, this.height);
 * </pre>
 * 调用点 `LevelRenderer.java:456 / :460 / :464` 分别是
 * `translucentTarget.get().copyDepthFrom(mainTarget.get())`（半透明）、`itemEntityTarget`（物品实体）、
 * `particleTarget`（粒子）。
 *
 * <p>原版在 `LevelRenderer.render` 里用 `gameRenderer.mainRenderTarget().width/height`
 * （`LevelRenderer.java:180-181`）同时给 `screenSizeTargetDescriptor`（`:182-184`，translucent /
 * item_entity / particles / weather / clouds 的分配尺寸）与两条世界后处理链的
 * `addToFrame(frame, screenWidth, screenHeight, targets)`（`:217` / `:237`）取尺寸。
 * P105 有意**不**改主目标的逻辑尺寸（`MainTargetRenderScaleMixin` 的 KDoc：让
 * `windowRenderState.width != mainRenderTarget.width` 仍为假，免得原版每帧重建主目标），
 * 于是这两个数停留在窗口尺寸，而主目标的**纹理**已经被缩到渲染尺寸 —— 辅助目标比源大 640x334，
 * 一进世界渲染色半透明阶段就抛越界。
 *
 * <p>这里只把 `render` 方法里那两处字段读取的**返回值**换成渲染尺寸（不动 `this.width/height`
 * 本身），于是：
 * <ul>
 *   <li>辅助目标与主目标附件同尺寸 ⇒ 拷贝矩形合法、`transparency.json` 那条链的逐像素深度比较
 *       仍在同一坐标系（这正是**不**采用「夹取拷贝矩形」的原因：目标比源大时夹取只会拷左上角一块，
 *       深度与颜色错位，半透明/粒子会穿帮）。</li>
 *   <li>{@code Projection.setSize} 收到的也是渲染尺寸，与真实渲染区域一致。</li>
 *   <li>主目标的逻辑尺寸、`windowRenderState` 比对、P106 的
 *       `mainTargetMatchesWindow` 判断依据（真实纹理尺寸）全都不变。</li>
 * </ul>
 *
 * <p>100%（默认）时 {@link D3D12RenderScale#worldLayerSize(int)} 的输入输出恒等，本 mixin 零副作用。
 * 这一条必须与 `MainTargetRenderScaleMixin` / `RenderTargetCreateBuffersScaleMixin` 成对存在：
 * 少了它，"只有主目标被缩、世界层其它目标没被缩" 就是一个必然崩溃的组合。
 */
@Mixin(LevelRenderer.class)
public class LevelRendererWorldTargetsScaleMixin {

    /** `mainRenderTarget().width`（`LevelRenderer.java:180`）。 */
    @Redirect(
        method = "render",
        at = @At(value = "FIELD", target = "Lcom/mojang/blaze3d/pipeline/RenderTarget;width:I"),
        remap = false
    )
    private int dxr_mc$worldTargetWidth(RenderTarget target) {
        return dxr_mc$worldTargetDimension(target.width);
    }

    /** `mainRenderTarget().height`（`LevelRenderer.java:181`）。 */
    @Redirect(
        method = "render",
        at = @At(value = "FIELD", target = "Lcom/mojang/blaze3d/pipeline/RenderTarget;height:I"),
        remap = false
    )
    private int dxr_mc$worldTargetHeight(RenderTarget target) {
        return dxr_mc$worldTargetDimension(target.height);
    }

    private static int dxr_mc$worldTargetDimension(int value) {
        return D3D12RenderScale.worldLayerSize(value);
    }
}