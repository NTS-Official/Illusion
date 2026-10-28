package dev.nts.expdxr.mixin.options;

import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 取未换行的原始文本：原版 {@code toCharSequence} 已按固定宽度折行，自绘版式要自己 {@code Font.split}。 */
@Mixin(Tooltip.class)
public interface TooltipAccessor {
    @Accessor("message")
    Component dxr_mc$message();
}
