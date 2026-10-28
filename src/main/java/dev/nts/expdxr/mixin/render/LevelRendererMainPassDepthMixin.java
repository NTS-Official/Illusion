package dev.nts.expdxr.mixin.render;

import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import dev.nts.expdxr.d3d12.render.advanced.taa.D3D12TAA;
import dev.nts.expdxr.dxrmc.sr.dlss.DeepLearningSuperSampling;
import dev.nts.expdxr.d3d12.render.core.D3D12GpuTextureView;
import dev.nts.expdxr.dxrmc.sr.D3D12SrMask;
import net.minecraft.client.renderer.LevelTargetBundle;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * DLSS 深度快照与运动矢量速度 pass 的挂点：本帧世界几何刚画完、任何一次 `clearDepthTexture` 之前。
 *
 * <p>帧图里有三处清主目标深度，`GameRenderer.render` 那两处（`GameRendererSmaaWorldMixin` 的挂点）
 * 排在全部三次之后，实测深度四点采样恒为 `mean=0.0`；`always_on_top` pass 又因
 * `hasAnyAlwaysOnTop()` 为假从没跑到过。只有 main pass 的 RETURN 同时满足「深度有效」与「无条件进入帧图」。
 * `require = 1` 保证 lambda 名字变了就启动失败，不会静默失效。
 * 见 docs/misc/mixins-and-earlywindow.md §3.6
 */
@Mixin(LevelRenderer.class)
public class LevelRendererMainPassDepthMixin {
    /** OIT 的透射率缓冲（`LevelTargetBundle.transmittance`，RGBA16F），掩码的来源。 */
    @Shadow @Final private LevelTargetBundle targets;

    /** main pass 体（javac 合成的 `lambda$addMainPass$0`）的 RETURN：先录速度 pass 再做深度快照，两者读同一份资源。 */
    @Inject(method = "lambda$addMainPass$0", at = @At("RETURN"), remap = false)
    private void dxr_mc$dlssDepthSnapshotAfterMainPass(GpuBufferSlice slice, boolean bl, ChunkSectionsToRender sections,
                                                       FeatureRenderDispatcher.PreparedFrame frame, boolean bl2,
                                                       boolean bl3, CallbackInfo ci) {
        // captureMotionVectors 在 DLSS 关闭时第一行就返回，所以只开 TAA 时运动矢量要靠这里补录。
        D3D12TAA.captureTemporalData();
        DeepLearningSuperSampling.captureMotionVectors();
        DeepLearningSuperSampling.captureDepthSnapshot();
        // 这一刻 OIT 已合成完，透射率缓冲正是本帧的值；取不到就传 null（掩码这条路会自己跳过）。
        dxr_mc$captureTransmittance();
    }

    private void dxr_mc$captureTransmittance() {
        // 列表里装的是吸收率小波系数（2 张 RGBA16F），由 HLSL 里 sampleAbsorbance 的移植还原整列透射率。
        LevelTargetBundle bundle = this.targets;
        if (bundle == null) {
            return;
        }
        var handles = bundle.transmittance;
        if (handles == null || handles.isEmpty()) {
            return;
        }
        var views = new java.util.ArrayList<D3D12GpuTextureView>(handles.size());
        for (var handle : handles) {
            try {
                RenderTarget target = handle.get();
                if (target != null && target.getColorTextureView() instanceof D3D12GpuTextureView view) {
                    views.add(view);
                }
            } catch (Throwable ignored) {
                // 单个目标取不到不影响其余附件。
            }
        }
        if (views.isEmpty()) {
            return;
        }
        D3D12SrMask.captureTransmittance(views.toArray(new D3D12GpuTextureView[0]));
    }
}
