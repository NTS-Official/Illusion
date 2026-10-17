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
 * TEMPORARY reproduction harness for user m11757 ("with DLSS on, lowering the render scale still
 * crashes"). It drives exactly what the video-settings slider drives - OptionInstance.set on
 * D3D12RenderScale - first DOWN (70 -> 60 -> 50) then back UP (50 -> 60 -> 70 -> 80 -> 90), one
 * step every 2 s so every step gets its own frame and its own main-target rebuild.
 *
 * <p>Register before running (`_port\resize_run.ps1 -Enable`), delete the class + its entry once
 * the evidence is captured. The step sequence is deliberately hard-coded: env/system properties
 * do not survive Loom's runClient (see the P108z note), and the point is to reproduce reliably,
 * not to be configurable.
 */
@Mixin(GameRenderer.class)
public class P108zResizeProbeMixin {

    @Unique
    private static final Logger DXRMC_RESIZE_LOG = LoggerFactory.getLogger("dxrmc-p108z-resize");

    /** Down first (the user's action), then up. Values are DLSS-mode steps (50..90 step 10). */
    @Unique
    private static final int[] DXRMC_RESIZE_SEQUENCE = new int[] {60, 50, 60, 70, 80, 90, 80, 70};

    @Unique
    private static int dxr_mc$resizeStep = 0;

    @Unique
    private static long dxr_mc$resizeNextNanos = 0L;

    @Unique
    private static boolean dxr_mc$resizeLoggedStart = false;

    @Inject(method = "render", at = @At("HEAD"), remap = false)
    private void dxr_mc$resizeProbe(CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        long now = System.nanoTime();
        if (!dxr_mc$resizeLoggedStart) {
            dxr_mc$resizeLoggedStart = true;
            dxr_mc$resizeNextNanos = now + 5_000_000_000L;
            DXRMC_RESIZE_LOG.info("RESIZE start: dlss={} handover={} scale={} dlssMode={} quality={}",
                    DeepLearningSuperSampling.isEnabled(),
                    DeepLearningSuperSampling.isDlssOutputHandoverEnabled(),
                    D3D12RenderScale.current(), D3D12RenderScale.isDlssMode(),
                    DeepLearningSuperSampling.quality());
            return;
        }
        if (now < dxr_mc$resizeNextNanos || dxr_mc$resizeStep >= DXRMC_RESIZE_SEQUENCE.length) {
            return;
        }
        int target = DXRMC_RESIZE_SEQUENCE[dxr_mc$resizeStep];
        dxr_mc$resizeStep++;
        int before = D3D12RenderScale.current();
        // Exactly what the slider's widget does.
        D3D12RenderScale.option().set(target);
        DXRMC_RESIZE_LOG.info("RESIZE step {} -> {} (was {}): now={} dlssMode={}",
                dxr_mc$resizeStep, target, before, D3D12RenderScale.current(),
                D3D12RenderScale.isDlssMode());
        dxr_mc$resizeNextNanos = now + 2_000_000_000L;
        if (dxr_mc$resizeStep >= DXRMC_RESIZE_SEQUENCE.length) {
            DXRMC_RESIZE_LOG.info("RESIZE sequence done: scale={}", D3D12RenderScale.current());
        }
    }
}