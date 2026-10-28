package dev.nts.expdxr.mixin.render;

import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import dev.nts.expdxr.d3d12.render.adapter.D3D12RenderProf;
import dev.nts.expdxr.d3d12.render.advanced.vrs.D3D12ShadingRateImage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 整帧帧图执行（所有 pass 体：区块 / 实体 / 天空 / 云 / 天气）。与区块渲染相减得到非区块部分。 */
@Mixin(FrameGraphBuilder.class)
public class FrameGraphBuilderProfMixin {

    /** 必须写明完整描述符：FrameGraphBuilder 有两个 execute 重载，仅写方法名会产生歧义。 */
    @Inject(method = "execute(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;" +
            "Lcom/mojang/blaze3d/framegraph/FrameGraphBuilder$Inspector;)V",
            at = @At("HEAD"), remap = false)
    private void D3D12ProfExecFrameGraphHead(GraphicsResourceAllocator resourceAllocator, FrameGraphBuilder.Inspector inspector, CallbackInfo ci) {
        // 整帧开画前生成 Tier 2 着色率图像：此刻本帧的 clear 还没跑，主深度里仍是上一帧最后一笔，
        // 正好是 SRI 想要的输入（见 D3D12ShadingRateImage 类注释）。
        D3D12ShadingRateImage.prepareFrame();
        D3D12RenderProf.begin(D3D12RenderProf.EXECUTE_FRAMEGRAPH);
    }

    @Inject(method = "execute(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;" +
            "Lcom/mojang/blaze3d/framegraph/FrameGraphBuilder$Inspector;)V",
            at = @At("RETURN"), remap = false)
    private void D3D12ProfExecFrameGraphReturn(GraphicsResourceAllocator resourceAllocator, FrameGraphBuilder.Inspector inspector, CallbackInfo ci) {
        D3D12RenderProf.end(D3D12RenderProf.EXECUTE_FRAMEGRAPH);
    }
}
