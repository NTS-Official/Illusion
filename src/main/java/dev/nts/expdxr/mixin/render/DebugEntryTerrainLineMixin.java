package dev.nts.expdxr.mixin.render;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.nts.expdxr.d3d12.debug.D3D12DebugInfo;
import net.minecraft.client.gui.components.debug.DebugEntrySimplePerformanceImpactors;
import net.minecraft.client.gui.components.debug.DebugScreenDisplayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Appends DXRMC's mesh badge to vanilla's F3 "Terrain Rendering: ..." row.
 *
 * <p>Vanilla builds that row as {@code "Terrain Rendering: " + (multidrawindirect|naive)}
 * and hands it to {@link DebugScreenDisplayer#addLine}. Wrapping the hand-off (instead of
 * adding a second row) keeps the line count and ordering identical; the decision whether
 * DXRMC is actually the live mesh renderer lives in
 * {@link D3D12DebugInfo#terrainLine(String)}.
 */
@Mixin(DebugEntrySimplePerformanceImpactors.class)
public abstract class DebugEntryTerrainLineMixin {
    @WrapOperation(method = "display",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/components/debug/"
                            + "DebugScreenDisplayer;addLine(Ljava/lang/String;)V"),
            remap = false)
    private void dxr_mc$appendMeshTag(DebugScreenDisplayer displayer, String line, Operation<Void> original) {
        original.call(displayer, D3D12DebugInfo.terrainLine(line));
    }
}
