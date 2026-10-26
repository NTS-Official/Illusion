package dev.nts.expdxr.mixin.options;

import net.minecraft.client.OptionInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 读 {@link OptionInstance} 的私有字段，供模组自绘的选项行使用。
 *
 * <p>26.3 的选项控件（滑条 / 循环按钮）由 {@code OptionInstance.createButton} 生成。其中标题
 * {@code caption} 与取值换算函数 {@code toString} 都是 public 字段，直接用即可（以前这里也有对应
 * 的 accessor，纯属多余）；只有随取值变化的提示供给函数 {@code tooltip} 仍是 private，才需要这里。
 */
@Mixin(OptionInstance.class)
public interface OptionInstanceAccessor {
    /**
     * 提示文字的供给函数：传当前取值进去才能拿到 {@code Tooltip}（有些选项的提示随取值变化）。
     *
     * <p>取到后由屏幕按自己的版式绘制（右侧提示块 / 悬浮提示），不用原版那套。
     */
    @Accessor("tooltip")
    OptionInstance.TooltipSupplier<Object> dxr_mc$tooltipSupplier();
}
