package dev.nts.expdxr.mixin.render;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.nts.expdxr.d3d12.debug.D3D12DebugInfo;
import net.minecraft.client.gui.components.debug.DebugEntrySimplePerformanceImpactors;
import net.minecraft.client.gui.components.debug.DebugScreenDisplayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 往原版 F3 的 "Terrain Rendering: ..." 行追加本模组的 mesh 标记。
 *
 * <p>wrap 这一行的出口而不是追加新行，行数与顺序才与原版一致；本模组是否真的是 mesh 渲染器在
 * `D3D12DebugInfo.terrainLine(String)` 里判断。
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
