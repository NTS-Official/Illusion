package dev.nts.expdxr.misc.gui

import net.minecraft.client.Minecraft
import dev.nts.expdxr.mixin.options.OptionInstanceAccessor
import dev.nts.expdxr.mixin.options.TooltipAccessor
import net.minecraft.client.OptionInstance
import net.minecraft.network.chat.Component
import net.minecraft.util.Mth
import java.util.function.Function

/**
 * 一整行的标题（深色底 + 居中白字）。
 *
 * <p>挪自 wgpu-mc（Neolectrum）的 `TextWidget`：屏幕顶部那条“视频设置”就是它。
 *
 * @author DXRMC
 */
class D3D12TitleWidget(x: Int, y: Int, width: Int, private val text: Component) :
    D3D12Widget(x, y, width, DEFAULT_HEIGHT) {

    override fun render(renderer: D3D12WidgetRenderer, mouseX: Int, mouseY: Int, delta: Float) {
        renderer.rect(x, y, x + width, y + height, BG)
        renderer.text(text, centerX(renderer.textWidth(text)), centerTextY(renderer), WHITE)
    }
}

/**
 * 分组标题：不画底、不参与悬停，只有主题色的字。
 *
 * <p>照 wgpu-mc 的 `HeadingWidget`：空文本就是一个纯留白行，用来隔开上一段设置。
 *
 * @author DXRMC
 */
class D3D12HeadingWidget(x: Int, y: Int, width: Int, private val text: Component) :
    D3D12Widget(x, y, width, DEFAULT_HEIGHT) {

    override fun render(renderer: D3D12WidgetRenderer, mouseX: Int, mouseY: Int, delta: Float) {
        if (text.string.isEmpty()) {
            return
        }

        // 分组标题不再铺深色底（用户 m06280）：只有一行主题色的字，和下面对齐即可。
        renderer.text(text, x + 6, centerTextY(renderer), ACCENT)
    }
}

/**
 * 左侧页签：深色底 + 白字，选中时左边一条 1px 主题色竖条，出现/消失带淡入淡出。
 *
 * @author DXRMC
 */
class D3D12TabWidget(
    x: Int,
    y: Int,
    private val label: Component,
    private val selected: () -> Boolean,
    private val onSelect: () -> Unit,
) : D3D12Widget(x, y, WIDTH, HEIGHT) {

    private var animation: Double = if (selected()) 1.0 else 0.0

    override fun render(renderer: D3D12WidgetRenderer, mouseX: Int, mouseY: Int, delta: Float) {
        animation = if (selected()) {
            Mth.clamp(animation + delta * 6.0, 0.0, 1.0)
        } else {
            Mth.clamp(animation - delta * 6.0, 0.0, 1.0)
        }

        renderer.rect(x, y, x + width, y + height, if (isMouseOver(mouseX, mouseY)) BG_HOVERED else BG)
        // 页签宽度固定，超长的译名要截断，否则会压到内容区（P201 实机所见）。
        val available = width - TEXT_INSET * 2
        val flat = label.string
        val shown = if (renderer.textWidth(label) > available) {
            Minecraft.getInstance().font.plainSubstrByWidth(flat, available - renderer.textWidth("...")) + "..."
        } else {
            flat
        }
        renderer.text(shown, x + TEXT_INSET, centerTextY(renderer), WHITE)

        if (animation > 0.0) {
            renderer.pushAlpha(animation)
            renderer.rect(x, y, x + 1, y + height, ACCENT)
            renderer.popAlpha()
        }
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (!isMouseOver(mouseX, mouseY)) {
            return false
        }

        playClickSound()
        onSelect()
        return true
    }

    companion object {
        const val WIDTH: Int = 120
        const val HEIGHT: Int = 25
        private const val TEXT_INSET: Int = 8
    }
}

/**
 * 一行设置：滑条 / 开关 / 枚举，三种外观都在这里。
 *
 * <p>照 wgpu-mc 的 `IntWidget` / `BoolWidget` / `EnumWidget`：
 * <ul>
 *   <li>滑条：未悬停时右侧显示格式化后的取值；悬停时名称限宽（超过 1/3 行宽就截断加 “...”），取值缩到
 *       左半边，右半边出现白色滑轨与手柄，点击/拖拽直接按比例设值。</li>
 *   <li>开关：右侧一个方框，打开时里面填主题色（带过渡）。</li>
 *   <li>枚举：右侧取值，取值变化时新旧文本交叉淡入淡出。</li>
 * </ul>
 *
 * @author DXRMC
 */
class D3D12OptionWidget<T : Any>(
    x: Int,
    y: Int,
    width: Int,
    private val option: OptionInstance<T>,
    /** 窄窗/大界面尺寸下退化成“折叠控件”：行尾显示 ▸，点开后在下一行露出滑条（见 [renderCollapsible]）。 */
    private val collapsible: Boolean = false,
    /** 折叠控件是否已展开；展开时本行多占 [SLIDER_LINE] 的高度，后续行整体下移。 */
    private val expanded: Boolean = false,
    private val onToggleExpand: () -> Unit = {},
    /**
     * 只读行（用户 m07432）：值由别处决定时（例如 DLSS 开着时渲染缩放被绑到 DLSS 输入比例），
     * 这一行只显示取值、不接受任何点击/拖拽，文字转灰。
     */
    private val readOnly: Boolean = false,
    /**
     * 允许取值白名单（每次点击时求值）：帧生成生效期间 Reflex 只允许在 On / Boost 之间切换，
     * 不允许被切回 Off（那会让 SDK 静默禁用插帧，用户却以为还开着）。
     */
    private val allowedValues: (() -> Set<T>)? = null,
) : D3D12Widget(x, y, width, DEFAULT_HEIGHT + if (expanded) SLIDER_LINE else 0) {

    private val caption: Component = (option as OptionInstanceAccessor).`dxr_mc$caption`()
    @Suppress("UNCHECKED_CAST")
    private val stringifier: Function<T, Component> =
        (option as OptionInstanceAccessor).`dxr_mc$valueToComponent`() as Function<T, Component>
    private val slider: OptionInstance.SliderableValueSet<T>? =
        option.values() as? OptionInstance.SliderableValueSet<T>
    private val cycleValues: List<T>? = run {
        // 26.3 里“枚举行”有两种来源：`Enum`（抗锯齿、画质预设这类内建枚举）与 `CycleableValueSet`
        // （我们自己的 HDR 呈现、以及原版若干 int 型循环按钮）。只认前者会让后者点了没反应（用户 m06370）。
        @Suppress("UNCHECKED_CAST")
        (option.values() as? OptionInstance.Enum<T>)?.values()
            ?: (option.values() as? OptionInstance.CycleableValueSet<T>)
                ?.valueListSupplier()?.defaultList
    }
    private val isSwitch: Boolean = option.get() is Boolean

    /**
     * 暂存值（用户 m06864）：改动只记在这里，点“应用”才真正写回 [OptionInstance]。
     *
     * <p>这样“改一下看看效果”不会再立刻重建渲染目标/开关 DLSS——以前那种即时生效正是
     * “关掉 DLSS 却没点应用也崩”的直接原因。
     */
    private var pendingValue: T? = null
    private var hasPending: Boolean = false

    /** 显示用的取值：有暂存就显示暂存，否则显示已生效的值。 */
    @Suppress("UNCHECKED_CAST")
    private fun displayValue(): T = if (hasPending) pendingValue as T else option.get()

    /** 记下暂存值（不写回 OptionInstance）。 */
    private fun stage(value: T) {
        pendingValue = value
        hasPending = true
    }

    /** 这一行是否有“尚未应用”的改动。 */
    fun hasPendingChange(): Boolean = hasPending && pendingValue != option.get()

    /** 把暂存值写回 OptionInstance（“应用”按钮与撤销基线都用它）。 */
    fun commitPending() {
        if (hasPending) {
            @Suppress("UNCHECKED_CAST")
            option.set(pendingValue as T)
            pendingValue = null
            hasPending = false
        }
    }

    /** 丢弃暂存值（“撤销”与关闭界面时用）。 */
    fun clearPending() {
        pendingValue = null
        hasPending = false
    }

    /** 供界面在重建行时携带暂存值（切页签/重新布局不该丢掉未应用的改动）。 */
    fun pendingOrNull(): Any? = if (hasPending) pendingValue else null

    /** 把外部带回来的暂存值重新挂上（与 [pendingOrNull] 配套）。 */
    @Suppress("UNCHECKED_CAST")
    fun stageExternal(value: Any?) {
        if (value == null) {
            return
        }

        stage(value as T)
    }

    private var switchAnimation: Double = if (displayValue() == true) 1.0 else 0.0
    /** 是否正从右半边的滑轨上拖拽；左边名称区按下不进入拖拽（见 [mouseClicked]）。 */
    private var dragging: Boolean = false
    private var shownValue: Component = formattedValue()
    private var previousValue: Component? = null
    private var valueAnimation: Double = 1.0

    /**
     * 悬停（含拖拽中）显示在左半边的“实时取值”。
     *
     * <p>一律用与未悬停时同一个格式化文本：原始值对玩家没有意义 —— 独占全屏模式是显示器模式列表的
     * 下标（“0/1/2”），画质预设是枚举名（“FAST/FANCY”），拖拽中还必须和松手后显示的那行字一致
     * （用户 m06236 / m06270）。
     */
    private fun sliderHoverText(): String = formattedValue().string

    /** 与屏幕的撤销基线对接：当前值与写回。 */
    fun currentValue(): Any? = option.get()

    fun restore(value: Any?) {
        @Suppress("UNCHECKED_CAST")
        stage(value as T)
    }

    override fun render(renderer: D3D12WidgetRenderer, mouseX: Int, mouseY: Int, delta: Float) {
        if (readOnly) {
            // 只读：不响应悬停、不高亮、不画滑轨/手柄，只把标题与取值转灰（用户 m07432）。
            renderer.rect(x, y, x + width, y + height, BG)
            renderDisabled(renderer)
            return
        }
        val hovered = isMouseOver(mouseX, mouseY)
        renderer.rect(x, y, x + width, y + height, if (hovered) BG_HOVERED else BG)

        if (isSwitch) {
            switchAnimation = Mth.clamp(
                switchAnimation + delta * 6.0 * (if (displayValue() == true) 1.0 else -1.0),
                0.0,
                1.0,
            )
        }

        // 折叠控件自己排版标题（要给行尾的 ▸ 和取值让位），不能再走默认标题绘制，否则会叠一遍。
        if (!collapsible) {
            renderTitle(renderer, hovered)
        }

        // 开关必须先判：26.3 的 BOOLEAN_VALUES 也满足 SliderableValueSet，先判滑条会把开关画成空取值。
        when {
            isSwitch -> renderSwitch(renderer)
            collapsible -> renderCollapsible(renderer)
            slider != null -> renderSliderValue(renderer, hovered, slider)
            else -> renderValue(renderer, delta)
        }
    }

    /** 屏幕用它判断这一行值不值得退化成折叠控件：只有滑条行才需要（开关/枚举没有展开态）。 */
    fun canCollapse(): Boolean = slider != null && !isSwitch

    /** 取值（展开态、悬停布局用）的像素宽度：屏幕据此判断标题加取值是否放得下。 */
    fun hoverHintWidth(): Int = Minecraft.getInstance().font.width(formattedValue().string)

    /** 标题（未截断）的像素宽度：屏幕用它判断真实重叠，而不是只看取值占多宽（用户 m06567）。 */
    fun captionWidth(): Int = Minecraft.getInstance().font.width(caption)

    /** 标题组件：屏幕的提示块首行显示它（用户 m06618 的版式：标题、空行、描述）。 */
    fun captionComponent(): Component = caption

    /**
     * 该行的提示文本（**未换行**）；没有提示时为 `null`。
     *
     * <p>原版只给“已按固定宽度换好行”的序列；版式宽度由屏幕决定，所以这里拿原始组件回去自己拆行。
     */
    fun tooltipText(): Component? {
        val supplier = (option as OptionInstanceAccessor).`dxr_mc$tooltipSupplier`() ?: return null
        val tooltip = supplier.apply(displayValue()) ?: return null
        return (tooltip as TooltipAccessor).`dxr_mc$message`()
    }

    /** 只读行的绘制：标题与取值都转灰，取值右对齐，标题按剩余宽度从右侧截断。 */
    private fun renderDisabled(renderer: D3D12WidgetRenderer) {
        val valueText = formattedValue()
        renderer.text(
            valueText,
            alignRight(renderer.textWidth(valueText)),
            centerTextY(renderer),
            DISABLED,
        )
        val available = (width - renderer.textWidth(valueText) - 18).coerceAtLeast(16)
        val shown = Minecraft.getInstance().font.plainSubstrByWidth(caption.string, available)
        renderer.text(shown, x + 6, centerTextY(renderer), DISABLED)
    }

    private fun renderTitle(renderer: D3D12WidgetRenderer, hovered: Boolean) {
        val limit = width / 3
        val captionWidth = renderer.textWidth(caption)

        if (hovered && captionWidth > limit) {
            val ellipsis = "..."
            val available = (limit - renderer.textWidth(ellipsis)).coerceAtLeast(0)
            val trimmed = Minecraft.getInstance().font.plainSubstrByWidth(caption.string, available)
            renderer.text(trimmed + ellipsis, x + 6, centerTextY(renderer), WHITE)
        } else {
            renderer.text(caption, x + 6, centerTextY(renderer), WHITE)
        }
    }

    private fun renderSliderValue(
        renderer: D3D12WidgetRenderer,
        hovered: Boolean,
        valueRenderer: OptionInstance.SliderableValueSet<T>,
    ) {
        if (!hovered) {
            val text = formattedValue()
            renderer.text(text, alignRight(renderer.textWidth(text)), centerTextY(renderer), WHITE)
            return
        }

        // 悬停时取值缩到左半边，右半边让给滑轨。取值同样从左边省略（数值有意义的部分在尾部）。
        val hoverText = sliderHoverText()
        val available = (width / 2 - 12).coerceAtLeast(16)
        val shown = ellipsizeLeft(Minecraft.getInstance().font, hoverText, available)
        renderer.text(shown, alignRight(renderer.textWidth(shown), width / 2), centerTextY(renderer), WHITE)

        val trackX1 = x + width / 2
        val trackX2 = x + width - 6
        val midY = centerY(1)
        renderer.rect(trackX1, midY, trackX2, midY + 1, WHITE)

        val progress = valueRenderer.toSliderValue(displayValue()).coerceIn(0.0, 1.0)
        val handleWidth = 3
        val handleHeight = renderer.textHeight() + 2
        // 手柄必须落在滑轨上：轨道从 x + width/2 开始、到 x + width - 6 结束，所以按轨道长度换算。
        // 之前用 x + progress * width 算，进度为 0 时手柄直接飞到行首、压在标题文字上（P201 实机所见）。
        val handleX = (trackX1 + (progress * (trackX2 - trackX1)).toInt())
            .coerceIn(trackX1, trackX2 - handleWidth)
        renderer.rect(
            handleX,
            centerY(handleHeight),
            handleX + handleWidth,
            centerY(handleHeight) + handleHeight,
            WHITE,
        )
    }

    /**
     * 折叠态滑条：行尾一个 ▸，点开后在**下一行**铺开整条滑轨（把后面的行往下挤，不覆盖）。
     *
     * <p>窄窗或大界面尺寸下，展开的滑条会把取值挤到标题上（用户 m06280），所以这种行不再内联展开，
     * 直接退化成折叠控件。
     */
    private fun renderCollapsible(renderer: D3D12WidgetRenderer) {
        val marker = if (expanded) "▾" else "▸"
        val markerWidth = renderer.textWidth(marker)
        val font = Minecraft.getInstance().font

        // 折叠态排版按“这一行真实可用的宽度”算，而不是套用带滑条的布局（用户 m06520）：
        // 内边距 6+6、行尾 ▸ 与它的间距、标题与取值之间再留 6。
        val inner = (width - 12 - markerWidth - 4).coerceAtLeast(24)
        val valueText = formattedValue().string
        val captionText = caption.string
        val valueFull = font.width(valueText)
        val captionFull = font.width(captionText)

        // 取值先按“它自己需要的宽度”拿（用户 m06597：固定按 45% 分给标题，会在两者本可以并排时
        // 把取值白白截掉）。只有标题与取值确实放不下时，才压缩取值；此时标题至少保留 1/4 行宽。
        val minCaption = (inner / 4).coerceAtLeast(24)
        val valueBudget = if (captionFull + valueFull + 6 <= inner) {
            valueFull
        } else {
            (inner - minCaption - 6).coerceIn(16, inner)
        }

        // 取值从左边省略：数值/分辨率这类文本有意义的部分在尾部（“…0@165”），砍尾巴会只剩前缀。
        val value = ellipsizeLeft(font, valueText, valueBudget)
        val valueWidth = renderer.textWidth(value)
        val valueX = alignRight(valueWidth + markerWidth + 4)
        renderer.text(value, valueX, centerTextY(renderer), WHITE)
        renderer.text(marker, x + width - 6 - markerWidth, centerTextY(renderer), ACCENT)

        val captionShown = ellipsizeRight(font, captionText, (valueX - (x + 6) - 6).coerceAtLeast(16))
        renderer.text(captionShown, x + 6, centerTextY(renderer), WHITE)

        if (!expanded) {
            return
        }

        val valueRenderer = slider ?: return
        val x1 = x + 6
        val x2 = x + width - 6
        val lineY = y + DEFAULT_HEIGHT + (SLIDER_LINE - 2) / 2
        renderer.rect(x1, lineY, x2, lineY + 1, WHITE)

        val progress = valueRenderer.toSliderValue(displayValue()).coerceIn(0.0, 1.0)
        val handleWidth = 3
        val handleHeight = renderer.textHeight() + 2
        val handleX = (x1 + (progress * (x2 - x1)).toInt()).coerceIn(x1, x2 - handleWidth)
        renderer.rect(
            handleX,
            lineY - handleHeight / 2,
            handleX + handleWidth,
            lineY - handleHeight / 2 + handleHeight,
            WHITE,
        )
    }

    private fun renderSwitch(renderer: D3D12WidgetRenderer) {
        val color = blend(ACCENT, WHITE, switchAnimation)
        val size = renderer.textHeight() + 2
        val boxX = alignRight(size)
        val boxY = centerY(size)

        renderer.rect(boxX, boxY, boxX + size, boxY + 1, color)
        renderer.rect(boxX, boxY + size - 1, boxX + size, boxY + size, color)
        renderer.rect(boxX, boxY + 1, boxX + 1, boxY + size - 1, color)
        renderer.rect(boxX + size - 1, boxY + 1, boxX + size, boxY + size - 1, color)

        if (switchAnimation > 0.0) {
            renderer.pushAlpha(switchAnimation)
            renderer.rect(boxX + 2, boxY + 2, boxX + size - 2, boxY + size - 2, ACCENT)
            renderer.popAlpha()
        }
    }

    private fun renderValue(renderer: D3D12WidgetRenderer, delta: Float) {
        val current = formattedValue()
        if (current != shownValue) {
            previousValue = shownValue
            shownValue = current
            valueAnimation = 0.0
        }

        valueAnimation = Mth.clamp(valueAnimation + delta * 6.0, 0.0, 1.0)

        if (valueAnimation < 1.0) {
            val previous = previousValue
            if (previous != null) {
                renderer.pushAlpha(1.0 - valueAnimation)
                renderer.text(previous, alignRight(renderer.textWidth(previous)), centerTextY(renderer), WHITE)
                renderer.popAlpha()
            }
        }

        renderer.pushAlpha(valueAnimation)
        renderer.text(shownValue, alignRight(renderer.textWidth(shownValue)), centerTextY(renderer), WHITE)
        renderer.popAlpha()
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (readOnly || !isMouseOver(mouseX, mouseY)) {
            return false
        }

        if (collapsible) {
            val valueRenderer = slider
            val onSliderLine = expanded && mouseY >= (y + DEFAULT_HEIGHT).toDouble()
            if (!onSliderLine || valueRenderer == null) {
                onToggleExpand()
                playClickSound()
                return true
            }
            dragging = true
            applyFractionExpanded(valueRenderer, mouseX)
            playClickSound()
            return true
        }

        val valueRenderer = slider
        if (valueRenderer != null) {
            // 右半边 = 滑轨：按比例设值并进入拖拽；左半边 = 名称区：按原版语义退一档。
            // 以前左边也按比例设值，鼠标点在标题上就会把取值拉到 0，看起来就像“手柄飞到行首”（用户 m06174）。
            if (mouseX >= (x + width / 2).toDouble()) {
                dragging = true
                applyFraction(valueRenderer, mouseX)
            } else {
                valueRenderer.previous(displayValue()).ifPresent { stage(it) }
            }
            playClickSound()
            return true
        }

        if (isSwitch) {
            toggleSwitch()
            playClickSound()
            return true
        }

        val allowed = allowedValues?.invoke()
        val values = cycleValues?.let { all -> if (allowed == null) all else all.filter { it in allowed } }
        if (values != null && values.isNotEmpty()) {
            val current = values.indexOf(displayValue())
            val step = if (button == 1) -1 else 1
            val next = values[(current + step + values.size) % values.size]
            stage(next)
            playClickSound()
            return true
        }

        return false
    }

    override fun mouseDragged(mouseX: Double, mouseY: Double, button: Int, dragX: Double, dragY: Double): Boolean {
        val valueRenderer = slider ?: return false
        if (readOnly || !dragging) {
            return false
        }

        if (collapsible) {
            applyFractionExpanded(valueRenderer, mouseX)
            return true
        }

        applyFraction(valueRenderer, mouseX)
        return true
    }

    override fun mouseReleased(mouseX: Double, mouseY: Double, button: Int): Boolean {
        dragging = false
        return false
    }

    private fun applyFraction(valueRenderer: OptionInstance.SliderableValueSet<T>, mouseX: Double) {
        val track = (width / 2 - 6).coerceAtLeast(1)
        val fraction = ((mouseX - x - width / 2) / track).coerceIn(0.0, 1.0)
        stage(valueRenderer.fromSliderValue(fraction))
    }

    /** 折叠控件展开后的滑轨铺满整行（x+6 .. x+width-6），命中换算必须跟着它走。 */
    private fun applyFractionExpanded(valueRenderer: OptionInstance.SliderableValueSet<T>, mouseX: Double) {
        val track = (width - 12).coerceAtLeast(1)
        val fraction = ((mouseX - x - 6) / track).coerceIn(0.0, 1.0)
        stage(valueRenderer.fromSliderValue(fraction))
    }

    @Suppress("UNCHECKED_CAST")
    private fun toggleSwitch() {
        val boolean = displayValue() as Boolean
        // 开关也必须走暂存（用户 m06937：DLSS 开关一拨就生效、却不显示“应用”）。
        @Suppress("UNCHECKED_CAST")
        stage((!boolean) as T)
    }

    /**
     * 原版的换算函数返回 “标题: 值”，直接显示会变成 “标题：标题：值”，所以这里把前缀去掉。
     *
     * <p>分隔符要两种都认：英文语言的 `options.generic_value` 是 `%s: %s`，中文是 `%s：%s`，
     * 只按 `": "` 匹配的话中文界面会把标题原样留在取值里（P201 实机所见）。
     */
    private fun formattedValue(): Component {
        val text = stringifier.apply(displayValue())
        val prefix = caption.string
        val flat = text.string

        if (!flat.startsWith(prefix)) {
            return text
        }

        val rest = flat.substring(prefix.length).trimStart()
        val stripped = when {
            rest.startsWith(":") || rest.startsWith("：") -> rest.substring(1).trimStart()
            else -> rest
        }
        // 个别选项的换算函数只回标题本身（自定义选项会这样），此时保留原文，别把整行取值抹掉。
        return if (stripped.isEmpty()) Component.literal(flat) else Component.literal(stripped)
    }

    companion object {
        /** 折叠控件展开后追加的一行高度（整条滑轨所在行）。 */
        const val SLIDER_LINE: Int = 12
    }
}

/**
 * 底部动作按钮：深色底 + 居中白字，出现/消失与文字切换都带淡入淡出。
 *
 * <p>照 wgpu-mc 的 `CustomButtonWidget`：同一个按钮在“有改动”时显示成“应用”，没改动时显示“完成”。
 *
 * @author DXRMC
 */
class D3D12ButtonWidget(
    x: Int,
    y: Int,
    width: Int,
    height: Int,
    private val label: () -> Component,
    private val visible: () -> Boolean,
    private val action: () -> Unit,
) : D3D12Widget(x, y, width, height) {

    private var animation: Double = if (visible()) 1.0 else 0.0
    private var shownLabel: Component = label()
    private var previousLabel: Component? = null
    private var labelAnimation: Double = 1.0

    override fun render(renderer: D3D12WidgetRenderer, mouseX: Int, mouseY: Int, delta: Float) {
        animation = if (visible()) {
            Mth.clamp(animation + delta * 6.0, 0.0, 1.0)
        } else {
            Mth.clamp(animation - delta * 6.0, 0.0, 1.0)
        }

        if (animation <= 0.001) {
            return
        }

        val current = label()
        if (current != shownLabel) {
            previousLabel = shownLabel
            shownLabel = current
            labelAnimation = 0.0
        }

        labelAnimation = Mth.clamp(labelAnimation + delta * 6.0, 0.0, 1.0)

        renderer.pushAlpha(animation)
        renderer.rect(x, y, x + width, y + height, if (isMouseOver(mouseX, mouseY)) BG_HOVERED else BG)

        val previous = previousLabel
        if (labelAnimation < 1.0 && previous != null) {
            renderer.pushAlpha(1.0 - labelAnimation)
            renderer.text(previous, centerX(renderer.textWidth(previous)), centerTextY(renderer), WHITE)
            renderer.popAlpha()
        }

        renderer.pushAlpha(labelAnimation)
        renderer.text(shownLabel, centerX(renderer.textWidth(shownLabel)), centerTextY(renderer), WHITE)
        renderer.popAlpha()
        renderer.popAlpha()
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (!isMouseOver(mouseX, mouseY) || !visible()) {
            return false
        }

        playClickSound()
        action()
        return true
    }
}
/** 从右边省略：保留前缀（标题这类文本有意义的部分在开头）。 */
private fun ellipsizeRight(font: net.minecraft.client.gui.Font, text: String, maxWidth: Int): String {
    if (maxWidth <= 0) {
        return ""
    }
    if (font.width(text) <= maxWidth) {
        return text
    }
    val dots = "..."
    val budget = (maxWidth - font.width(dots)).coerceAtLeast(0)
    return font.plainSubstrByWidth(text, budget) + dots
}

/**
 * 从左边省略：保留后缀（分辨率、数值这类文本有意义的部分在末尾）。
 *
 * <p>Kotlin 没有现成的“按像素裁左”，这里从尾部逐字符往前扩，直到再扩就超宽为止。
 */
private fun ellipsizeLeft(font: net.minecraft.client.gui.Font, text: String, maxWidth: Int): String {
    if (maxWidth <= 0) {
        return ""
    }
    if (font.width(text) <= maxWidth) {
        return text
    }
    val dots = "..."
    val budget = (maxWidth - font.width(dots)).coerceAtLeast(0)
    var start = text.length
    while (start > 0 && font.width(text.substring(start - 1)) <= budget) {
        start--
    }
    return dots + text.substring(start)
}
