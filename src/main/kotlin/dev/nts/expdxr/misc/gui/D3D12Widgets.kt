package dev.nts.expdxr.misc.gui

import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.Minecraft
import dev.nts.expdxr.mixin.options.OptionInstanceAccessor
import dev.nts.expdxr.mixin.options.TooltipAccessor
import net.minecraft.client.OptionInstance
import net.minecraft.network.chat.Component
import net.minecraft.util.ARGB
import net.minecraft.util.Mth
import java.util.function.Function
import kotlin.math.roundToInt

/** 一整行的标题（深色底 + 居中白字）。 */
class D3D12TitleWidget(x: Int, y: Int, width: Int, private val text: Component) :
    D3D12Widget(x, y, width, DEFAULT_HEIGHT) {

    override fun render(renderer: D3D12WidgetRenderer, mouseX: Int, mouseY: Int, delta: Float) {
        renderer.rect(x, y, x + width, y + height, BG)
        renderer.text(text, centerX(renderer.textWidth(text)), centerTextY(renderer), WHITE)
    }
}

/** 分组标题：只有主题色的字，不画底、不参与悬停；空文本就是一行留白。 */
class D3D12HeadingWidget(x: Int, y: Int, width: Int, private val text: Component) :
    D3D12Widget(x, y, width, DEFAULT_HEIGHT) {

    override fun render(renderer: D3D12WidgetRenderer, mouseX: Int, mouseY: Int, delta: Float) {
        if (text.string.isEmpty()) {
            return
        }

        renderer.text(text, x + 6, centerTextY(renderer), ACCENT)
    }
}
/** 只读说明行：灰色小字，不可点、不参与悬停 —— 必须看起来不可交互，否则会有人去点。 */
class D3D12NoteWidget(x: Int, y: Int, width: Int, private val text: Component, private val warning: Boolean) :
    D3D12Widget(x, y, width, DEFAULT_HEIGHT) {

    override fun render(renderer: D3D12WidgetRenderer, mouseX: Int, mouseY: Int, delta: Float) {
        if (text.string.isEmpty()) {
            return
        }

        renderer.text(text, x + 6, centerTextY(renderer), if (warning) RED else DISABLED)
    }
}

/** 左侧页签：选中时左边一条 1px 主题色竖条，选中/取消带淡入淡出。 */
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
        // 页签宽度固定：超长的译名必须截断，否则压到内容区。
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

/** 内容区顶部的二级页签：横排，宽度由屏幕按标题文本算好传入；选中时下缘一条紫线。 */
class D3D12SubTabWidget(
    x: Int,
    y: Int,
    width: Int,
    private val label: Component,
    private val selected: () -> Boolean,
    private val onSelect: () -> Unit,
) : D3D12Widget(x, y, width, HEIGHT) {

    private var animation: Double = if (selected()) 1.0 else 0.0

    override fun render(renderer: D3D12WidgetRenderer, mouseX: Int, mouseY: Int, delta: Float) {
        animation = if (selected()) {
            Mth.clamp(animation + delta * 6.0, 0.0, 1.0)
        } else {
            Mth.clamp(animation - delta * 6.0, 0.0, 1.0)
        }

        renderer.rect(x, y, x + width, y + height, if (isMouseOver(mouseX, mouseY)) BG_HOVERED else BG)
        val available = width - TEXT_INSET
        val flat = label.string
        val shown = if (renderer.textWidth(label) > available) {
            Minecraft.getInstance().font.plainSubstrByWidth(flat, available - renderer.textWidth("...")) + "..."
        } else {
            flat
        }
        renderer.text(shown, x + (width - renderer.textWidth(shown)) / 2, centerTextY(renderer), if (selected()) WHITE else DISABLED)

        if (animation > 0.0) {
            renderer.pushAlpha(animation)
            renderer.rect(x, y + height - 1, x + width, y + height, ACCENT)
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
        const val HEIGHT: Int = 18
        private const val TEXT_INSET: Int = 4
    }
}

/**
 * 一行设置：滑条 / 开关 / 枚举，三种外观都在这里。
 *
 * <p>滑条悬停时标题最多占 1/3 行宽，取值只能填在"标题右缘 → 行中线"之间，放不下就整体省略。
 * 见 docs/misc/settingscreen/README.md §6.4。
 */
class D3D12OptionWidget<T : Any>(
    x: Int,
    y: Int,
    width: Int,
    private val option: OptionInstance<T>,
    /** 窄窗下退化成折叠控件：行尾 ▸，点开在下一行露出滑轨（见 [renderCollapsible]）。 */
    private val collapsible: Boolean = false,
    /** 已展开：本行多占 [SLIDER_LINE] 的高度，后续行整体下移。 */
    private val expanded: Boolean = false,
    private val onToggleExpand: () -> Unit = {},
    /** 只读行：值由别处决定（如 DLSS 开着时渲染缩放绑到 DLSS 输入比例），只显示取值、文字转灰。 */
    private val readOnlyProvider: () -> Boolean = { false },
    /** 允许取值白名单（每次点击时求值）：帧生成生效时 Reflex 不允许被切回 Off，否则 SDK 静默禁用插帧。 */
    private val allowedValues: (() -> Set<T>)? = null,
    /** 只读原因：非空时悬停这一行会额外显示一条说明。 */
    private val lockReasonProvider: () -> Component? = { null },
) : D3D12Widget(x, y, width, DEFAULT_HEIGHT + if (expanded) SLIDER_LINE else 0) {

    // caption 与 toString 在 OptionInstance 上是 public 字段；只有 tooltip 是 private，走 accessor。
    private val caption: Component = option.caption
    private val stringifier: Function<T, Component> = option.toString
    private val slider: OptionInstance.SliderableValueSet<T>? =
        option.values() as? OptionInstance.SliderableValueSet<T>
    private val cycleValues: List<T>? = run {
        // 26.3 的枚举行有两种来源：Enum（内建枚举）与 CycleableValueSet（HDR 呈现、原版 int 循环按钮）。
        // 只认前者会让后者点了没反应。
        @Suppress("UNCHECKED_CAST")
        (option.values() as? OptionInstance.Enum<T>)?.values()
            ?: (option.values() as? OptionInstance.CycleableValueSet<T>)
                ?.valueListSupplier()?.defaultList
    }
    private val isSwitch: Boolean = option.get() is Boolean

    /** 暂存值：改动只记在这里，点"应用"才写回 [OptionInstance]（即时生效会立刻重建渲染目标、开关 DLSS）。 */
    private var pendingValue: T? = null
    private var hasPending: Boolean = false

    /** 显示用的取值：有暂存就显示暂存，否则显示已生效的值。 */
    @Suppress("UNCHECKED_CAST")
    private fun displayValue(): T = if (hasPending) pendingValue as T else option.get()

    private fun stage(value: T) {
        pendingValue = value
        hasPending = true
    }

    fun hasPendingChange(): Boolean = hasPending && pendingValue != option.get()

    fun commitPending() {
        if (hasPending) {
            @Suppress("UNCHECKED_CAST")
            option.set(pendingValue as T)
            pendingValue = null
            hasPending = false
        }
    }

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
     * 悬停（含拖拽中）显示在左半边的"实时取值"：一律用与未悬停时同一份格式化文本 —— 原始值对玩家
     * 没有意义（模式下标、枚举名），拖拽中还必须与松手后显示的那行字一致。
     */
    private fun sliderHoverText(): String = formattedValue().string

    fun currentValue(): Any? = option.get()

    fun restore(value: Any?) {
        @Suppress("UNCHECKED_CAST")
        stage(value as T)
    }

    override fun render(renderer: D3D12WidgetRenderer, mouseX: Int, mouseY: Int, delta: Float) {
        if (readOnlyProvider()) {
            // 只读：不响应悬停、不高亮、不画滑轨/手柄，只把标题与取值转灰。
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

    fun canCollapse(): Boolean = !isSwitch

    fun hasSlider(): Boolean = slider != null

    fun hoverHintWidth(): Int = Minecraft.getInstance().font.width(formattedValue().string)

    /** 标题未截断的像素宽度：屏幕据此判断真实重叠，而不是只看取值占多宽。 */
    fun captionWidth(): Int = Minecraft.getInstance().font.width(caption)

    fun captionComponent(): Component = caption

    /** 只读原因；为空表示这一行没有额外解释。 */
    fun lockReasonText(): Component? = if (readOnlyProvider()) lockReasonProvider() else null

    // tooltip 是 OptionInstance 里唯一还是 private 的字段，只能走 @Accessor；mixin 在加载期织入，
    // Kotlin 看不到那个接口，所以转换被判成"永远不可能成功"（运行时是成功的）。
    @Suppress("CAST_NEVER_SUCCEEDS")
    fun tooltipText(): Component? {
        val supplier = (option as OptionInstanceAccessor).`dxr_mc$tooltipSupplier`() ?: return null
        val tooltip = supplier.apply(displayValue()) ?: return null
        return (tooltip as TooltipAccessor).`dxr_mc$message`()
    }

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
        // 纯取值行（枚举/文本）也要给右对齐的取值留位置，否则长取值会直接盖住标题；滑条/开关按悬停收窄。
        val font = Minecraft.getInstance().font
        // 悬停中的滑条行把取值挪到左半边（右半边给滑轨），此时不需要再预留整段取值宽度；
        // 其余情况（含未悬停的滑条行）都必须给右对齐的取值/开关留位置，否则标题会被取值直接盖住。
        val valueReserve = when {
            hovered && slider != null -> 0
            isSwitch -> renderer.textHeight() + 2 + 8
            else -> minOf(renderer.textWidth(formattedValue()), (width - width / 4 - 12).coerceAtLeast(16))
        }
        val limit = if (valueReserve > 0) (width - valueReserve - 12).coerceAtLeast(width / 4) else width / 3
        val captionWidth = renderer.textWidth(caption)

        if (valueReserve > 0 && captionWidth > limit) {
            renderer.text(ellipsizeRight(font, caption.string, limit), x + 6, centerTextY(renderer), WHITE)
        } else if (hovered && captionWidth > limit) {
            val ellipsis = "..."
            val available = (limit - renderer.textWidth(ellipsis)).coerceAtLeast(0)
            val trimmed = font.plainSubstrByWidth(caption.string, available)
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
        val font = Minecraft.getInstance().font
        // 展开态的滑条在第二行，第一行不再受"中线左侧"限制，整行都可用。
        if (!hovered || expanded) {
            // 未悬停时取值右对齐；限宽给标题留出位置（长取值如 2560x1440@165Hz）。
            val text = formattedValue()
            val limit = if (expanded) (width - 12).coerceAtLeast(16) else (width - width / 4 - 12).coerceAtLeast(16)
            val capped = ellipsizeLeft(font, text.string, limit)
            renderer.text(capped, alignRight(renderer.textWidth(capped)), centerTextY(renderer), WHITE)
            return
        }

        // 悬停：取值只能填在"标题右侧 → 行中线"这段；只按 width/2 限宽、右对齐到中线会让长取值越过标题。
        val hoverText = sliderHoverText()
        val captionBudget = minOf(renderer.textWidth(caption), width / 3)
        val valueLeft = x + 6 + captionBudget + 8
        val valueRight = x + width / 2
        val available = (valueRight - valueLeft).coerceAtLeast(0)
        if (available > 0) {
            val shown = ellipsizeLeft(font, hoverText, available)
            renderer.text(shown, alignRight(renderer.textWidth(shown), width / 2), centerTextY(renderer), WHITE)
        }

        val trackX1 = x + width / 2
        val trackX2 = x + width - 6
        val midY = centerY(1)
        renderer.rect(trackX1, midY, trackX2, midY + 1, WHITE)

        val progress = valueRenderer.toSliderValue(displayValue()).coerceIn(0.0, 1.0)
        val handleWidth = 3
        val handleHeight = renderer.textHeight() + 2
        // 手柄必须落在滑轨上：轨道从 x + width/2 到 x + width - 6，所以按轨道长度换算（按 progress * width
        // 算的话，进度为 0 时手柄会飞到行首、压在标题上）。
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

    /** 折叠态滑条：行尾 ▸，点开后在**下一行**铺开整条滑轨（把后面的行往下挤，不覆盖）。 */
    private fun renderCollapsible(renderer: D3D12WidgetRenderer) {
        val marker = if (expanded) "▾" else "▸"
        val markerWidth = renderer.textWidth(marker)
        val font = Minecraft.getInstance().font

        // 折叠态排版按本行真实可用宽度算：内边距 6+6、▸ 与它的间距、标题与取值之间再留 6。
        val inner = (width - 12 - markerWidth - 4).coerceAtLeast(24)
        val valueText = formattedValue().string
        val captionText = caption.string
        val valueFull = font.width(valueText)
        val captionFull = font.width(captionText)

        // 取值先按它自己需要的宽度拿；只有标题与取值确实放不下时才压缩取值，此时标题至少保留 1/4 行宽。
        val minCaption = (inner / 4).coerceAtLeast(24)
        val valueBudget = if (captionFull + valueFull + 6 <= inner) {
            valueFull
        } else {
            (inner - minCaption - 6).coerceIn(16, inner)
        }

        // 纯取值行展开后，取值挪到第二行，第一行整行都留给标题（这样"有省略"时展开才真的能看全）。
        val valueInline = !(expanded && slider == null)

        // 取值从左边省略：数值/分辨率这类文本有意义的部分在尾部（“…0@165”），砍尾巴会只剩前缀。
        val value = if (valueInline) ellipsizeLeft(font, valueText, valueBudget) else ""
        val valueWidth = renderer.textWidth(value)
        val valueX = alignRight(valueWidth + markerWidth + 4)
        if (valueInline) {
            renderer.text(value, valueX, centerTextY(renderer), WHITE)
        }
        renderer.text(marker, x + width - 6 - markerWidth, centerTextY(renderer), ACCENT)

        val captionBudget = if (valueInline) (valueX - (x + 6) - 6) else (width - 12 - markerWidth - 4)
        val captionShown = ellipsizeRight(font, captionText, captionBudget.coerceAtLeast(16))
        renderer.text(captionShown, x + 6, centerTextY(renderer), WHITE)

        if (!expanded) {
            return
        }

        val valueRenderer = slider
        if (valueRenderer == null) {
            // 纯取值行没有滑轨可露，展开就是把完整取值放到第二行；只有第二行也放不下才省略。
            val full = ellipsizeRight(font, formattedValue().string, width - 12)
            renderer.text(full, x + 6, y + DEFAULT_HEIGHT + 1, WHITE)
            return
        }

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
        val capped = ellipsizeLeft(Minecraft.getInstance().font, shownValue.string, (width - width / 4 - 12).coerceAtLeast(16))
        renderer.text(capped, alignRight(renderer.textWidth(capped)), centerTextY(renderer), WHITE)
        renderer.popAlpha()
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (readOnlyProvider() || !isMouseOver(mouseX, mouseY)) {
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
            // 右半边 = 滑轨：按比例设值并进入拖拽；左半边 = 名称区：按原版语义退一档（左边也按比例
            // 设值的话，点在标题上就会把取值拉到 0）。
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
            val step = if (button == InputConstants.MOUSE_BUTTON_RIGHT) -1 else 1
            val next = values[(current + step + values.size) % values.size]
            stage(next)
            playClickSound()
            return true
        }

        return false
    }

    override fun mouseDragged(mouseX: Double, mouseY: Double, button: Int, dragX: Double, dragY: Double): Boolean {
        val valueRenderer = slider ?: return false
        if (readOnlyProvider() || !dragging) {
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
        // 开关也必须走暂存，否则一拨就生效、却不显示"应用"。
        @Suppress("UNCHECKED_CAST")
        stage((!boolean) as T)
    }

    /**
     * 原版换算函数返回"标题: 值"，去掉前缀；分隔符必须中英两种都认 —— 中文语言键是 `%s：%s`，
     * 只按 `": "` 匹配会把标题原样留在取值里。
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

/** 底部动作按钮：有改动时显示"应用"、无改动时显示"完成"，文字切换带淡入淡出。 */
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

/**
 * 右侧滚动条：轨道铺满滚动区的可见高度，滑块长度按「可见高度 / 内容总高」算 —— 内容只溢出一点时滑块
 * 很长，翻几屏才到底时滑块很短。按住滑块拖动或点击轨道就能在不碰滚轮的情况下移动页面。
 *
 * <p>条只有 [WIDTH] 像素宽，命中区左右各放宽 [HIT_PADDING]：按 4 像素去抓一条线太费劲。
 *
 * <p>自动隐藏：用过滚轮之后，滚轮停下 1 秒淡出，再滚一下又淡入；指针停在条上或正在拖动时不隐藏。
 * 从未用过滚轮时一直显示，否则鼠标玩家找不到这个入口。淡入淡出 0.3 秒。
 *
 * <p>计时用 [System.nanoTime] 而不是 `partialTick`：后者在一帧里会重复取值，停顿菜单里也不推进，
 * 动画会抖或卡在半透明。见 docs/misc/settingscreen/README.md §6.9。
 */
class D3D12ScrollBarWidget(
    /** 屏幕持有的滚动量（像素）。 */
    private val scrollProvider: () -> Double,
    /** 回写滚动量：屏幕负责夹到 `[0, maxScroll]` 并重排行的位置。 */
    private val onScroll: (Double) -> Unit,
) : D3D12Widget(0, 0, WIDTH, 0) {

    /** 内容总高（可见高度 + 可滚动距离），每次重排时由屏幕传来。 */
    private var contentHeight: Int = 0

    /** 0..1 的淡入淡出进度；到 0 就不画、也不接受点击。 */
    private var alpha: Double = 1.0
    private var hoverAnimation: Double = 0.0
    private var lastRenderNanos: Long = 0L
    private var wheelUsed: Boolean = false
    private var lastWheelNanos: Long = 0L
    private var dragging: Boolean = false

    /** 按下时指针相对滑块顶端的距离：拖动时保持它不变，滑块才不会跳到指针中心。 */
    private var grabOffset: Int = 0

    private val maxScroll: Double
        get() = (contentHeight - height).toDouble().coerceAtLeast(0.0)

    /** 屏幕在重排之后调用：决定横坐标、轨道范围与内容总高。 */
    fun layout(left: Int, trackTop: Int, trackHeight: Int, totalContentHeight: Int) {
        setPosition(left, trackTop)
        height = trackHeight.coerceAtLeast(0)
        contentHeight = totalContentHeight.coerceAtLeast(height)
    }

    /** 滚轮刚滚过页面：从这一刻起 1 秒后淡出。 */
    fun notifyWheel() {
        wheelUsed = true
        lastWheelNanos = System.nanoTime()
    }

    /** 内容放得下就没有可滚的距离：整条不画、也不接受点击。 */
    fun isActive(): Boolean = maxScroll > 0.0 && height > 0

    /** 指针是否落在命中区（比可见的条宽，见 [HIT_PADDING]）。 */
    fun isOverBar(mouseX: Double, mouseY: Double): Boolean =
        mouseY >= y && mouseY < y + height &&
            mouseX >= x - HIT_PADDING && mouseX < x + width + HIT_PADDING

    override fun render(renderer: D3D12WidgetRenderer, mouseX: Int, mouseY: Int, delta: Float) {
        val now = System.nanoTime()
        // 首帧没有上一帧，步长为 0；夹到 0.25 秒，避免切窗口/卡顿后一次性跳完整段动画。
        val seconds = if (lastRenderNanos == 0L) {
            0.0
        } else {
            ((now - lastRenderNanos) / 1e9).coerceIn(0.0, 0.25)
        }
        lastRenderNanos = now

        val hovered = isActive() && isOverBar(mouseX.toDouble(), mouseY.toDouble())
        val kept = !wheelUsed || dragging || hovered || now - lastWheelNanos < AUTO_HIDE_NANOS
        alpha = approach(alpha, if (kept) 1.0 else 0.0, seconds / FADE_SECONDS)
        hoverAnimation = approach(hoverAnimation, if (hovered || dragging) 1.0 else 0.0, seconds / HOVER_SECONDS)

        if (alpha < 0.001 || !isActive()) {
            return
        }

        renderer.pushAlpha(alpha)
        renderer.rect(x, y, x + width, y + height, TRACK)
        val top = thumbTop()
        renderer.rect(x, top, x + width, top + thumbLength(), blend(ACCENT, WHITE, hoverAnimation))
        renderer.popAlpha()
    }

    /** 滑块长度按可见高度占内容总高的比例算，并留一个最短长度，否则内容很长时只剩几个像素、抓不住。 */
    private fun thumbLength(): Int {
        if (maxScroll <= 0.0) {
            return height
        }

        val raw = (height.toDouble() * height / contentHeight).roundToInt()
        return raw.coerceIn(MIN_THUMB.coerceAtMost(height), height)
    }

    private fun thumbTop(): Int {
        val travel = height - thumbLength()
        if (travel <= 0) {
            return y
        }

        return y + (travel * (scrollProvider() / maxScroll).coerceIn(0.0, 1.0)).roundToInt()
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (!isActive() || alpha < 0.001 || button != InputConstants.MOUSE_BUTTON_LEFT || !isOverBar(mouseX, mouseY)) {
            return false
        }

        val top = thumbTop()
        val length = thumbLength()
        val onThumb = mouseY >= top && mouseY < top + length
        grabOffset = if (onThumb) (mouseY - top).toInt() else length / 2
        dragging = true
        if (!onThumb) {
            // 点在轨道上：滑块中心直接跳到指针处，而不是翻一页 —— 拖一下就到的距离，翻页反而要点多次。
            applyDrag(mouseY)
        }
        return true
    }

    override fun mouseDragged(mouseX: Double, mouseY: Double, button: Int, dragX: Double, dragY: Double): Boolean {
        if (!dragging) {
            return false
        }

        applyDrag(mouseY)
        return true
    }

    override fun mouseReleased(mouseX: Double, mouseY: Double, button: Int): Boolean {
        dragging = false
        return false
    }

    private fun applyDrag(mouseY: Double) {
        val travel = height - thumbLength()
        if (travel <= 0) {
            return
        }

        val fraction = ((mouseY - grabOffset - y) / travel.toDouble()).coerceIn(0.0, 1.0)
        onScroll(fraction * maxScroll)
    }

    companion object {
        const val WIDTH: Int = 4

        /** 命中区在条两侧各放宽的像素：条本身太细，按可见宽度抓不住。 */
        const val HIT_PADDING: Int = 2

        /** 滑块最短长度：内容很长时仍然抓得住。 */
        private const val MIN_THUMB: Int = 18

        private const val AUTO_HIDE_NANOS: Long = 1_000_000_000L
        private const val FADE_SECONDS: Double = 0.3
        private const val HOVER_SECONDS: Double = 0.15

        /** 轨道：半透明浅色，压在深色背景与黑底行之间都看得出来；条只有 4 像素宽，太淡就整条消失。 */
        private val TRACK: Int = ARGB.color(72, 255, 255, 255)

        /** 每帧朝目标推进固定步长：掉帧时不会一口气跳完整段动画。 */
        private fun approach(value: Double, target: Double, step: Double): Double =
            if (value < target) (value + step).coerceAtMost(target) else (value - step).coerceAtLeast(target)
    }
}

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

/** 从左边省略：保留后缀（分辨率、数值这类文本有意义的部分在末尾）。Kotlin 没有按像素裁左的现成函数，从尾部逐字符往前扩。 */
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
