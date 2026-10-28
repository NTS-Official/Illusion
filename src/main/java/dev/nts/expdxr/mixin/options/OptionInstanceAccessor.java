package dev.nts.expdxr.mixin.options;

import net.minecraft.client.OptionInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 读 {@code OptionInstance.tooltip}：26.3 只有它还私有（{@code caption} / {@code toString} 已是 public）。 */
@Mixin(OptionInstance.class)
public interface OptionInstanceAccessor {
    /** 提示随取值变化：必须把当前取值传进去才能拿到 {@code Tooltip}。 */
    @Accessor("tooltip")
    OptionInstance.TooltipSupplier<Object> dxr_mc$tooltipSupplier();
}
