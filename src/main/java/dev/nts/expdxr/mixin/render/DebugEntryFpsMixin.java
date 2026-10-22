package dev.nts.expdxr.mixin.render;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.nts.expdxr.d3d12.render.D3D12FrameStats;
import net.minecraft.client.gui.components.debug.DebugEntryFps;
import net.minecraft.client.gui.components.debug.DebugScreenDisplayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * F3 的帧率行改成「rendered/output」两个数值（当前是帧生成的占位）。
 *
 * 原版 DebugEntryFps.display() 只把一行 `%d fps T: %s%s` 交给
 * DebugScreenDisplayer.addPriorityLine()，数字来自 Minecraft.getFps()。
 * 在「交给显示器」这一层做 {@code @WrapOperation}（而不是改 String.format 或
 * 事后追加一行），是因为帧率行只有这一处出口：
 * - 只替换这一行时，原版的 T: 限帧/呈现模式后缀原样保留；
 * - 追加一行会多出一行同义文本，并打乱 F3 的优先级行顺序。
 *
 * 真正的数值替换与「非 D3D12 后端原样返回」都在 D3D12FrameStats.fpsLine() 里，
 * 这个 mixin 只负责把那一行接过去。
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