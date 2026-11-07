package io.github.dxegp.mixin.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import io.github.dxegp.RtDebugView;
import io.github.dxegp.d3d12.backend.D3D12CommandEncoderBackend;
import io.github.dxegp.d3d12.render.adapter.D3D12Device;
import io.github.dxegp.experimental.raytracing.D3D12RtFrame;
import io.github.dxegp.experimental.raytracing.D3D12RtGBuffer;
import io.github.dxegp.experimental.raytracing.D3D12RtStats;
import io.github.dxegp.d3d12.render.core.D3D12GpuTexture;
import io.github.dxegp.d3d12.render.core.D3D12GpuTextureView;
import io.github.dxegp.misc.IllusionLogger;
import io.github.dxegp.misc.settings.D3D12Settings;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LevelTargetBundle;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 光追出图点：main pass 体 RETURN 时把光追结果（或一张 G-Buffer 调试图）铺到主目标上。
 *
 * <p>此时 OIT 与 outline 已合成完，深度仍然有效，是「世界画完、GUI 未画」的唯一时点。
 * 优先级取 1100（比默认 1000 晚应用），保证在 TAA/DLSS 的历史与深度捕获之后才覆盖主目标，
 * 否则超分历史会吃到光追图像。见 docs/rt/README.md §8、§9
 */
@Mixin(value = LevelRenderer.class, priority = 1100)
public class LevelRendererRtDebugViewMixin {
    @Shadow @Final private LevelTargetBundle targets;

    @Inject(method = "lambda$addMainPass$0", at = @At("RETURN"), remap = false)
    private void dxr_mc$blitDebugView(GpuBufferSlice slice, boolean bl, ChunkSectionsToRender sections,
                                      FeatureRenderDispatcher.PreparedFrame frame, boolean bl2,
                                      boolean bl3, CallbackInfo ci) {
        if (!D3D12Settings.INSTANCE.getRtEnabled()) {
            return;
        }
        // 光追链是开发档功能：异常只记日志，绝不冒泡打断整帧。
        try {
            dxr_mc$blit();
        } catch (Throwable t) {
            D3D12RtFrame.reportFailure(t);
        }
    }

    /**
     * 上屏前必须同尺寸：窗口刚改过尺寸的那一帧，源纹理还是旧尺寸，1:1 搬过去会只在左上角
     * 留下一块旧画面并看起来像被裁掉。等下一帧两边重建完再搬。
     */
    @Unique
    private static boolean dxr_mc$sameSize(D3D12GpuTexture a, D3D12GpuTexture b) {
        return a.getWidth(0) == b.getWidth(0) && a.getHeight(0) == b.getHeight(0);
    }

    @Unique
    private static boolean dxr_mc$sameSize(D3D12RtFrame.CompositeImage source, D3D12GpuTexture destination) {
        return source.getWidth() == destination.getWidth(0) && source.getHeight() == destination.getHeight(0);
    }

    @Unique
    private void dxr_mc$blit() {
        LevelTargetBundle bundle = this.targets;
        if (bundle == null) {
            return;
        }
        D3D12CommandEncoderBackend encoder = D3D12Device.sharedCommandEncoder();
        RenderTarget main;
        try {
            main = bundle.main.get();
        } catch (Throwable ignored) {
            return;
        }
        D3D12GpuTexture destination = dxr_mc$texture(main.getColorTextureView());
        if (destination == null) {
            return;
        }
        int view = D3D12Settings.INSTANCE.getRtDebugView();
        int validityMode = view == RtDebugView.PRIMARY_VALIDITY.ordinal() ? 7
                : view == RtDebugView.DIRECT_VALIDITY.ordinal() ? 8
                : view == RtDebugView.DIFFUSE_VALIDITY.ordinal() ? 9
                : view == RtDebugView.SPECULAR_VALIDITY.ordinal() ? 10 : 0;
        if (validityMode != 0) {
            D3D12RtFrame.CompositeImage validity = D3D12RtFrame.INSTANCE.composite(encoder,
                    destination, validityMode);
            if (validity != null && dxr_mc$sameSize(validity, destination)) {
                D3D12RtFrame.INSTANCE.blitComposite(encoder, validity, destination);
                D3D12RtStats.INSTANCE.mark(encoder, D3D12RtStats.SLOT_BLIT);
            }
            return;
        }
        if (view == RtDebugView.HIT_DISTANCE.ordinal()) {
            // 命中距离灰度：判断射线到底有没有打到几何，比辐亮度更直接。
            D3D12RtFrame.CompositeImage hit = D3D12RtFrame.INSTANCE.composite(encoder,
                    destination, 2);
            if (hit != null && dxr_mc$sameSize(hit, destination)) {
                D3D12RtFrame.INSTANCE.blitComposite(encoder, hit, destination);
                D3D12RtStats.INSTANCE.mark(encoder, D3D12RtStats.SLOT_BLIT);
            }
            return;
        }
        if (view == RtDebugView.COVERAGE.ordinal()) {
            // 覆盖率：白 = G-Buffer 写过，黑 = 没写过。追踪没东西时先看这张。
            D3D12RtFrame.CompositeImage covered = D3D12RtFrame.INSTANCE.composite(encoder,
                    destination, 3);
            if (covered != null && dxr_mc$sameSize(covered, destination)) {
                D3D12RtFrame.INSTANCE.blitComposite(encoder, covered, destination);
                D3D12RtStats.INSTANCE.mark(encoder, D3D12RtStats.SLOT_BLIT);
            }
            return;
        }
        if (view == RtDebugView.DIRECT.ordinal()) {
            // 只显示追踪出来的辐亮度：分辨「追踪没出东西」与「追踪有东西但画面接近原版」。
            D3D12RtFrame.CompositeImage rawView = D3D12RtFrame.INSTANCE.composite(encoder,
                    destination, 1);
            if (rawView != null && dxr_mc$sameSize(rawView, destination)) {
                D3D12RtFrame.INSTANCE.blitComposite(encoder, rawView, destination);
                D3D12RtStats.INSTANCE.mark(encoder, D3D12RtStats.SLOT_BLIT);
            }
            return;
        }
        if (view == RtDebugView.SHADOW.ordinal()) {
            // 阴影掩码：这一张有没有光追，一眼就能看出来。
            D3D12RtFrame.CompositeImage mask = D3D12RtFrame.INSTANCE.composite(encoder,
                    destination, 5);
            if (mask != null && dxr_mc$sameSize(mask, destination)) {
                D3D12RtFrame.INSTANCE.blitComposite(encoder, mask, destination);
                D3D12RtStats.INSTANCE.mark(encoder, D3D12RtStats.SLOT_BLIT);
            }
            return;
        }
        if (view == RtDebugView.INDIRECT.ordinal()) {
            // 只显示间接项：直射正常但间接缺，追踪就可能「看着像原版」。
            D3D12RtFrame.CompositeImage indirectView = D3D12RtFrame.INSTANCE.composite(encoder,
                    destination, 4);
            if (indirectView != null && dxr_mc$sameSize(indirectView, destination)) {
                D3D12RtFrame.INSTANCE.blitComposite(encoder, indirectView, destination);
                D3D12RtStats.INSTANCE.mark(encoder, D3D12RtStats.SLOT_BLIT);
            }
            return;
        }
        if (view == RtDebugView.COMPOSITE_MARK.ordinal()) {
            // 整屏品红：只看这条通路通不通（合光 -> 上屏），不看辐亮度内容。
            D3D12RtFrame.CompositeImage mark = D3D12RtFrame.INSTANCE.composite(encoder,
                    destination, 6);
            if (mark != null && dxr_mc$sameSize(mark, destination)) {
                boolean ok = D3D12RtFrame.INSTANCE.blitComposite(encoder, mark, destination);
                if (ok) {
                    D3D12RtStats.INSTANCE.mark(encoder, D3D12RtStats.SLOT_BLIT);
                }
                dxr_mc$noteBlit(ok, mark);
            }
            return;
        }
        if (view == RtDebugView.NONE.ordinal()) {
            // 默认视图：albedo * (direct + indirect) + emissive，合光后再上屏。
            D3D12RtFrame.CompositeImage composite = D3D12RtFrame.INSTANCE.composite(encoder,
                    destination);
            if (composite != null && dxr_mc$sameSize(composite, destination)) {
                boolean ok = D3D12RtFrame.INSTANCE.blitComposite(encoder, composite, destination);
                if (ok) {
                    D3D12RtStats.INSTANCE.mark(encoder, D3D12RtStats.SLOT_BLIT);
                }
                dxr_mc$noteBlit(ok, composite);
            } else {
                dxr_mc$noteBlit(false, composite);
            }
            return;
        }
        if (view == RtDebugView.ROUGHNESS.ordinal()) {
            // 粗糙度在 g_NormalRoughness.w，单通道得先拉成灰度再铺（blit 只采三通道）。
            D3D12GpuTexture packed = D3D12RtFrame.INSTANCE.debugChannel(encoder,
                    D3D12RtGBuffer.INSTANCE.texture(D3D12RtGBuffer.NORMAL_ROUGHNESS), 3,
                    destination.getWidth(0), destination.getHeight(0));
            if (packed != null && dxr_mc$sameSize(packed, destination) && encoder.recordTextureBlit(packed, destination)) {
                D3D12RtStats.INSTANCE.mark(encoder, D3D12RtStats.SLOT_BLIT);
            }
            return;
        }
        if (view == RtDebugView.NORMAL.ordinal()) {
            // 世界空间法线在 -1..1，直接 blit 会被目标格式截成 0/1（地面整片黑），先编到 0..1。
            D3D12GpuTexture packed = D3D12RtFrame.INSTANCE.debugChannel(encoder,
                    D3D12RtGBuffer.INSTANCE.texture(D3D12RtGBuffer.NORMAL_ROUGHNESS), 4,
                    destination.getWidth(0), destination.getHeight(0));
            if (packed != null && dxr_mc$sameSize(packed, destination) && encoder.recordTextureBlit(packed, destination)) {
                D3D12RtStats.INSTANCE.mark(encoder, D3D12RtStats.SLOT_BLIT);
            }
            return;
        }
        if (view == RtDebugView.BLAS.ordinal()) {
            // 先合光出一张正常画面，再把加速结构的实例盒线框叠在上面：线框只写自己的像素，
            // 其余像素保持合成结果。见 docs/rt/README.md §14.6
            D3D12RtFrame.CompositeImage image = D3D12RtFrame.INSTANCE.composite(encoder,
                    destination);
            if (image == null) {
                return;
            }
            D3D12RtFrame.CompositeImage drawn = D3D12RtFrame.INSTANCE.debugBlas(encoder, image);
            if (drawn == null) {
                // 还没有实例（刚打开光追、或本帧没建起 TLAS）：至少把合成图铺上去，不要留原版画面。
                drawn = image;
            }
            if (D3D12RtFrame.INSTANCE.blitComposite(encoder, drawn, destination)) {
                D3D12RtStats.INSTANCE.mark(encoder, D3D12RtStats.SLOT_BLIT);
            }
            return;
        }
        // 深度不能直接 blit：native 收尾把源切回 RENDER_TARGET，而深度资源不允许那个状态（会移除设备），
        // 所以先由 compute 摊成一张颜色纹理；其余视图直接从 G-Buffer 取。
        D3D12RtGBuffer.Image source = D3D12RtGBuffer.INSTANCE.debugTexture();
        if (source == null) {
            return;
        }
        if (D3D12RtGBuffer.INSTANCE.blit(encoder, source, destination)) {
            D3D12RtStats.INSTANCE.mark(encoder, D3D12RtStats.SLOT_BLIT);
        }
    }

    /** 上屏失败的头几次各打一行：次数本身不刷屏，能区分「合光没产出」与「产出但没搬上屏」。 */
    @Unique private static int dxr_mc$blitTries;

    @Unique private static int dxr_mc$blitFails;

    @Unique
    private static void dxr_mc$noteBlit(boolean ok, D3D12RtFrame.CompositeImage source) {
        dxr_mc$blitTries++;
        if (ok) {
            return;
        }
        dxr_mc$blitFails++;
        if (dxr_mc$blitFails > 3) {
            return;
        }
        String size = source == null ? "none" : source.getWidth() + "x" + source.getHeight();
        IllusionLogger.warnMessage(
                "rt blit: " + dxr_mc$blitTries + " tries, " + dxr_mc$blitFails + " fail, last " + size);
    }

    @Unique
    private static D3D12GpuTexture dxr_mc$texture(GpuTextureView view) {
        return view instanceof D3D12GpuTextureView dxr ? (D3D12GpuTexture) dxr.texture() : null;
    }
}
