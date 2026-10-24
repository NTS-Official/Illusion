package dev.nts.expdxr.mixin.options;

import java.util.function.Function;

import net.minecraft.client.OptionInstance;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 读 {@link OptionInstance} 的两个私有字段，供模组自绘的选项行使用。
 *
 * <p>26.3 的选项控件（滑条 / 循环按钮）由 {@code OptionInstance.createButton} 生成，标题与取值的
 * 换算函数都是私有的：没有它们，自绘行只能退回 {@code toString()}，把 “Caption: Value” 拆开显示，
 * 既不能本地化，也不便于左右分栏排版。这里只取不改。
 */
@Mixin(OptionInstance.class)
public interface OptionInstanceAccessor {
    /** 选项标题（已本地化的 {@link Component}）。 */
    @Accessor("caption")
    Component dxr_mc$caption();

    /** 取值 → 显示文本的换算函数（原版 {@code CaptionBasedToString} 的封装）。 */
    @Accessor("toString")
    Function<Object, Component> dxr_mc$valueToComponent();

    /**
     * 提示文字的供给函数：传当前取值进去才能拿到 {@code Tooltip}（有些选项的提示随取值变化）。
     *
     * <p>取到后由屏幕按自己的版式绘制（右侧提示块 / 悬浮提示），不用原版那套。
     */
    @Accessor("tooltip")
    OptionInstance.TooltipSupplier<Object> dxr_mc$tooltipSupplier();
}
