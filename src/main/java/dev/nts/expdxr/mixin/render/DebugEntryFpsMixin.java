package dev.nts.expdxr.mixin.render;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.nts.expdxr.d3d12.debug.D3D12FrameStats;
import net.minecraft.client.gui.components.debug.DebugEntryFps;
import net.minecraft.client.gui.components.debug.DebugScreenDisplayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * F3 的帧率行改成「rendered/output」两个数值。
 *
 * <p>在「交给显示器」这一层 wrap 而不是追加一行：帧率行只有 `addPriorityLine` 这一个出口，替换它能
 * 保住原版 `T:` 后缀与优先级行顺序。数值替换与非 D3D12 后端原样返回都在 `D3D12FrameStats.fpsLine()`。
 */
@Mixin(DebugEntryFps.class)
public abstract class DebugEntryFpsMixin {
    @WrapOperation(method = "display",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/components/debug/"
                            + "DebugScreenDisplayer;addPriorityLine(Ljava/lang/String;)V"),
            remap = false)
    private void dxr_mc$fpsAsPair(DebugScreenDisplayer displayer, String line, Operation<Void> original) {
        original.call(displayer, D3D12FrameStats.fpsLine(line));
    }
}