package dev.nts.expdxr.mixin.render;

import dev.nts.expdxr.d3d12.render.advanced.vrs.D3D12RenderScale;
import dev.nts.expdxr.dxrmc.dlss.DeepLearningSuperSampling;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * TEMPORARY harness for the P108z crash (user m11203: with DLSS on, dragging the render-scale
 * slider DOWN by more than 10% crashed the frame with "dx12MapBuffer returned null").
 * It drives exactly what the slider drives - OptionInstance.set on D3D12RenderScale - one step
 * at a time with a delay between steps, so each step gets its own frame and its own main-target
 * rebuild. Delete once the evidence is captured.
 */
@Mixin(GameRenderer.class)
public class P108zScaleDragProbeMixin {

    @Unique
    private static final Logger DXRMC_DRAG_LOG = LoggerFactory.getLogger("dxrmc-p108z-drag");

    @Unique
    private static final int[] DXRMC_DRAG_SEQUENCE = new int[] {60, 50, 40, 50, 60, 70, 80, 90, 80, 70};

    @Unique
    private static int dxr_mc$step = 0;

    @Unique
    private static long dxr_mc$nextStepNanos = 0L;

    @Inject(method = "render", at = @At("HEAD"), remap = false)
    private void dxr_mc$scaleDragProbe(CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        long now = System.nanoTime();
        if (dxr_mc$nextStepNanos == 0L) {
            dxr_mc$nextStepNanos = now + 3_000_000_000L;
            DXRMC_DRAG_LOG.info("DRAG start: dlss={} scale={} dlssMode={}",
                    DeepLearningSuperSampling.isEnabled(), D3D12RenderScale.current(),
                    D3D12RenderScale.isDlssMode());
            return;
        }
        if (now < dxr_mc$nextStepNanos) {
            return;
        }
        if (dxr_mc$step >= DXRMC_DRAG_SEQUENCE.length) {
            DXRMC_DRAG_LOG.info("DRAG done: scale={}", D3D12RenderScale.current());
            return;
        }
        int target = DXRMC_DRAG_SEQUENCE[dxr_mc$step];
        dxr_mc$step++;
        DXRMC_DRAG_LOG.info("DRAG step {} -> {} (was {})", dxr_mc$step, target,
                D3D12RenderScale.current());
        D3D12RenderScale.option().set(target);
        DXRMC_DRAG_LOG.info("DRAG step {} applied: scale={} dlssMode={}",
                dxr_mc$step, D3D12RenderScale.current(), D3D12RenderScale.isDlssMode());
        dxr_mc$nextStepNanos = now + 2_000_000_000L;
    }
}