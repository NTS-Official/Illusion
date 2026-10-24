package dev.nts.expdxr.mixin.options;

import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 读 {@link Tooltip} 的原始提示文本。
 *
 * <p>原版只给 {@code toCharSequence(Minecraft)}（已按原版固定宽度换行）。我们自己的提示版式要按
 * “这一版式实际可用的宽度”重新换行，所以需要未换行的组件再自己调 {@code Font.split}。
 */
@Mixin(Tooltip.class)
public interface TooltipAccessor {
    @Accessor("message")
    Component dxr_mc$message();
}
