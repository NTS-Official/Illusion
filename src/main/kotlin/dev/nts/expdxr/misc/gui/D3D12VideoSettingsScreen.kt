package dev.nts.expdxr.misc.gui

import dev.nts.expdxr.misc.settings.D3D12HdrPresentation
import net.minecraft.client.Minecraft
import net.minecraft.client.OptionInstance
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import java.util.IdentityHashMap
import kotlin.math.roundToInt

/**
 * D3D12 专属视频设置界面：左侧页签、右侧自绘设置行、底部"应用 / 撤销 / 完成"。
 *
 * <p>本类只负责布局尺寸、滚动、绘制与输入；内容（有哪几页、哪几行、为什么被锁）在
 * [D3D12VideoSettingsContent]。改动先暂存，"应用"才落盘。见 docs/misc/settingscreen/README.md §6。
 */
class D3D12VideoSettingsScreen @JvmOverloads constructor(
    private val parent: Screen,
    private val content: D3D12VideoSettingsContent = D3D12VideoSettingsContent(),
) : Screen(Component.translatable("dxr_mc.video.title")) {

    private val pages = content.pages()

    private val widgets: MutableList<D3D12Widget> = ArrayList()

    /** 当前页的行控件，以及它们在"未滚动"时的 y。 */
    private val rows: MutableList<Pair<D3D12Widget, Int>> = ArrayList()

    /** 行控件 ↔ 它承载的选项，撤销与"是否有改动"都用这份对应关系。 */
    private val optionRows: MutableList<Pair<OptionInstance<*>, D3D12OptionWidget<*>>> = ArrayList()

    /** 打开界面时的取值，撤销写回用。 */
    private val baseline: IdentityHashMap<OptionInstance<*>, Any?> = IdentityHashMap()

    private var page = 0
    private var scroll = 0.0
    private var maxScroll = 0
    private var dragging: D3D12Widget? = null

    private var contentLeft = 0
    private var contentTop = 0
    private var contentRight = 0
    private var contentBottom = 0
    private var contentWidth = 0

    private val expandedOptions: MutableSet<OptionInstance<*>> = mutableSetOf()

    init {
        snapshotBaseline()
    }

    override fun init() {
        widgets.clear()
        rows.clear()
        optionRows.clear()

        val optimalWidth = optimalWidth()
        contentLeft = SIDEBAR_X + D3D12TabWidget.WIDTH + GAP
        contentTop = TITLE_Y + D3D12Widget.DEFAULT_HEIGHT + GAP
        contentRight = optimalWidth - OUTER_MARGIN
        // 底部固定留三行：提示行（常驻预留，否则出现提示时会压到动作按钮）+ 间隔 + 按钮行。
        contentBottom = height - OUTER_MARGIN - D3D12Widget.DEFAULT_HEIGHT - GAP - HINT_LINE
        contentWidth = (contentRight - contentLeft).coerceAtLeast(MIN_CONTENT_WIDTH)

        widgets += D3D12TitleWidget(TITLE_X, TITLE_Y, optimalWidth - TITLE_X - OUTER_MARGIN, title)

        var tabY = contentTop
        pages.forEachIndexed { index, page ->
            widgets += D3D12TabWidget(SIDEBAR_X, tabY, page.name,
                { this.page == index },
                { switchPage(index) },
            )
            tabY += D3D12TabWidget.HEIGHT + TAB_GAP
        }

        buildRows()
        buildActionButtons(optimalWidth)
    }

    private fun buildRows() {
        // 切页签/重新布局会重建行，未点"应用"的暂存值必须跟着走。
        val carried = optionRows.mapNotNull { (option, widget) ->
            widget.pendingOrNull()?.let { option to it }
        }.toMap()

        rows.clear()
        optionRows.clear()

        var y = contentTop
        for (entry in pages[page].entries) {
            val widget: D3D12Widget = when (entry) {
                is D3D12VideoSettingsEntry.Heading -> D3D12HeadingWidget(contentLeft, y, contentWidth, entry.text)
                is D3D12VideoSettingsEntry.Note -> D3D12NoteWidget(contentLeft, y, contentWidth, entry.text, entry.warning)
                is D3D12VideoSettingsEntry.Setting -> {
                    val row = optionWidgetFor(entry.option, contentLeft, y, contentWidth)
                    carried[entry.option]?.let { row.stageExternal(it) }
                    optionRows += entry.option to row
                    row
                }
            }

            rows += widget to y
            // 折叠控件展开后会多占一行，后面的行整体下移（不叠加、不覆盖）。
            y += D3D12Widget.DEFAULT_HEIGHT +
                if (entry is D3D12VideoSettingsEntry.Setting && expandedOptions.contains(entry.option)) D3D12OptionWidget.SLIDER_LINE else 0
        }

        maxScroll = (y - contentTop - (contentBottom - contentTop)).coerceAtLeast(0)
        scroll = scroll.coerceIn(0.0, maxScroll.toDouble())
        applyScroll()
    }

    @Suppress("UNCHECKED_CAST")
    private fun optionWidgetFor(option: OptionInstance<*>, x: Int, y: Int, width: Int): D3D12OptionWidget<*> {
        val typed = option as OptionInstance<Any>
        val probe = D3D12OptionWidget(x, y, width, typed)
        // 重叠判定按真实像素算：标题（悬停时最多占行宽 1/3）+ 取值 + 内边距 > 行宽，或内容区本来就窄。
        val captionWidth = probe.captionWidth()
        val valueWidth = probe.hoverHintWidth()
        // 折叠只在"真的放不下"时触发，否则标题很短、取值放得下的行会被无谓折叠又省略。
        val wanted = if (probe.hasSlider()) {
            maxOf(captionWidth, width / 3) + valueWidth + 18
        } else {
            captionWidth + valueWidth + 18
        }
        // 滑条行内联时取值只能填在"标题右缘 → 行中线"这段，比它宽就一定会被省略，直接折叠成可展开控件。
        val inlineCaption = minOf(captionWidth, width / 3)
        val inlineValueSpace = (width / 2 - 14 - inlineCaption).coerceAtLeast(0)
        val valueOverflowsInlineSlider = probe.hasSlider() && valueWidth > inlineValueSpace
        val collapsible = probe.canCollapse() &&
            (wanted > width || valueOverflowsInlineSlider || (probe.hasSlider() && width < MIN_EXPANDED_WIDTH))
        if (!collapsible) {
            // 窗口拉宽后不再折叠：清掉残留的展开状态，否则行高会一直停在一行半。
            expandedOptions.remove(option)
        }
        val expanded = collapsible && expandedOptions.contains(option)
        return D3D12OptionWidget(
            x, y, width, typed, collapsible, expanded, { toggleExpand(option) },
            { content.isReadOnly(option) }, content.allowedValuesProvider(option), { content.lockReasonFor(option) },
        )
    }


    /** 折叠/展开一行；只重建行，保留滚动位置（不重进 `init()`，否则页签与标题也会被重建）。 */
    private fun toggleExpand(option: OptionInstance<*>) {
        if (!expandedOptions.remove(option)) {
            expandedOptions += option
        }
        buildRows()
    }

    private fun buildActionButtons(optimalWidth: Int) {
        val buttonWidth = 100
        val buttonY = height - OUTER_MARGIN - D3D12Widget.DEFAULT_HEIGHT
        val applyX = optimalWidth - OUTER_MARGIN - buttonWidth
        val undoX = applyX - buttonWidth - 4

        widgets += D3D12ButtonWidget(
            undoX,
            buttonY,
            buttonWidth,
            D3D12Widget.DEFAULT_HEIGHT,
            { translatable("dxr_mc.video.undo") },
            { hasChanges() },
            { undo() },
        )
        widgets += D3D12ButtonWidget(
            applyX,
            buttonY,
            buttonWidth,
            D3D12Widget.DEFAULT_HEIGHT,
            { translatable(if (hasChanges()) "dxr_mc.video.apply" else "dxr_mc.video.done") },
            { true },
            {
                if (hasChanges()) {
                    apply()
                } else {
                    onClose()
                }
            },
        )
    }

    private fun switchPage(index: Int) {
        if (index == page) {
            return
        }

        page = index
        scroll = 0.0
        buildRows()
    }

    private fun snapshotBaseline() {
        baseline.clear()
        for (page in pages) for (entry in page.entries) if (entry is D3D12VideoSettingsEntry.Setting) baseline[entry.option] = entry.option.get()
    }

    private fun hasChanges(): Boolean = optionRows.any { [_, widget] -> widget.hasPendingChange() }

    /** 应用：暂存值一次性写回 [OptionInstance]（到这一步才真正重建渲染目标、开关 DLSS），然后落盘。 */
    private fun apply() {
        val opts = Minecraft.getInstance().options
        val presetRow = optionRows.firstOrNull { it.first === opts.graphicsPreset() }?.second
        val presetChanged = presetRow?.hasPendingChange() == true
        for ([_, widget] in optionRows) {
            widget.commitPending()
        }
        // 画质预设与抗锯齿的联动必须在"应用"之后显式补一次：`OptionInstance.set` 只写取值，原版是靠
        // `Options.applyGraphicsPreset(...)` 触发 `GraphicsPreset.apply`，我们的自绘界面绕过了那段。
        if (presetChanged) {
            opts.applyGraphicsPreset(opts.graphicsPreset().get())
        }

        minecraft.options.save()
        snapshotBaseline()
    }

    private fun undo() {
        for ((_, widget) in optionRows) {
            widget.clearPending()
        }
    }

    override fun extractRenderState(
        extractor: GuiGraphicsExtractor,
        mouseX: Int,
        mouseY: Int,
        partialTick: Float,
    ) {
        super.extractRenderState(extractor, mouseX, mouseY, partialTick)

        val renderer = D3D12WidgetRenderer(extractor)
        val scissored = maxScroll > 0 && contentBottom > contentTop
        if (scissored) {
            renderer.enableScissor(contentLeft, contentTop, contentRight, contentBottom)
        }

        for ((widget, _) in rows) {
            widget.render(renderer, mouseX, mouseY, partialTick)
        }

        if (scissored) {
            renderer.disableScissor()
        }

        widgets.forEach { it.render(renderer, mouseX, mouseY, partialTick) }
        drawTooltip(renderer, mouseX, mouseY)

        if (restartPending()) {
            // 提示行是常驻预留的（见 init 里的 contentBottom）；再夹一道横向裁剪，小窗 + 中文长句时宁可截断。
            val hintY = contentBottom + (HINT_LINE - 8) / 2
            renderer.enableScissor(contentLeft, contentBottom, contentRight, contentBottom + HINT_LINE)
            renderer.text(
                translatable("options.restartRequired"),
                contentLeft + 6,
                hintY,
                D3D12Widget.RED,
            )
            renderer.disableScissor()
        }
    }

    /**
     * 悬停行的提示：右侧有余量用固定提示块（模板 标题 / 空行 / 描述），窄到没有余量才退回跟随鼠标的悬浮提示。
     */
    private fun drawTooltip(renderer: D3D12WidgetRenderer, mouseX: Int, mouseY: Int) {
        if (dragging != null) {
            return
        }

        // 内容区外的行（被下半部分按钮挡住 / 滚出边界）不能弹提示。
        if (mouseY < contentTop || mouseY > contentBottom) return
        val hovered = optionRows.lastOrNull { (_, widget) ->
            widget.y >= contentTop && widget.y + widget.height <= contentBottom &&
                widget.isMouseOver(mouseX.toDouble(), mouseY.toDouble())
        }
            ?.second ?: return
        val tooltipText = hovered.tooltipText() ?: return

        val font = Minecraft.getInstance().font
        val caption = hovered.captionComponent()
        val lineHeight = renderer.textHeight()
        val pad = HINT_PAD
        val freeRight = width - (contentRight + GAP) - OUTER_MARGIN
        val anchored = freeRight >= MIN_HINT_WIDTH

        // 换行宽度：固定块用右侧实际余量（上限便于阅读），悬浮提示用固定宽度。
        val textZone = if (anchored) {
            (freeRight - GAP).coerceIn(MIN_HINT_WIDTH, MAX_HINT_WIDTH) - pad * 2
        } else {
            FLOAT_HINT_WIDTH - pad * 2
        }
        val lines = font.split(tooltipText, textZone)
        // 固定块里把锁定原因并进同一个框：两块半透明背景叠在一起会显得更黑。
        val lockReason = hovered.lockReasonText()
        val reasonLines = if (anchored && lockReason != null) font.split(lockReason, textZone) else emptyList()
        val reasonExtra = if (reasonLines.isEmpty()) 0 else 2 + reasonLines.size  // 空行 + 注意 + 原因
        // 悬浮提示不显示标题行；固定块仍然是"标题 / 空行 / 描述"。
        val headerLines = if (anchored) 2 else 0
        val boxWidth = maxOf(
            lines.maxOf { font.width(it) },
            reasonLines.maxOfOrNull { font.width(it) } ?: 0,
            if (anchored) renderer.textWidth(caption) else 0,
        ) + pad * 2
        val boxHeight = pad * 2 + lineHeight * (lines.size + headerLines + reasonExtra)

        val boxX: Int
        val boxY: Int
        if (anchored) {
            boxX = contentRight + GAP
            boxY = TITLE_Y
        } else {
            boxX = (mouseX + 12).coerceIn(4, (width - boxWidth - 4).coerceAtLeast(4))
            boxY = (mouseY + 12).coerceIn(4, (height - boxHeight - 4).coerceAtLeast(4))
        }
        val reasonHeader = if (reasonLines.isEmpty()) null else Component.translatable("dxr_mc.video.lock.notice")
        drawTooltipBox(renderer, caption, lines, reasonLines, reasonHeader, boxX, boxY, boxWidth, boxHeight, pad, lineHeight, anchored)

        if (!anchored && lockReason != null) {
            val floatLines = font.split(lockReason, textZone)
            val floatWidth = floatLines.maxOf { font.width(it) } + pad * 2
            val floatHeight = pad * 2 + lineHeight * (floatLines.size + 1)
            val floatY = (mouseY - floatHeight - 12).coerceAtLeast(4)
            renderer.rect(boxX, floatY, boxX + floatWidth, floatY + floatHeight, D3D12Widget.TOOLTIP_BG)
            var floatRow = floatY + pad
            renderer.text(Component.translatable("dxr_mc.video.lock.notice"), boxX + pad, floatRow, D3D12Widget.WARNING)
            floatRow += lineHeight
            for (floatLine in floatLines) {
                renderer.text(floatLine, boxX + pad, floatRow, D3D12Widget.DISABLED)
                floatRow += lineHeight
            }
        }
    }

    private fun drawTooltipBox(
        renderer: D3D12WidgetRenderer,
        caption: Component,
        lines: List<net.minecraft.util.FormattedCharSequence>,
        reasonLines: List<net.minecraft.util.FormattedCharSequence>,
        reasonHeader: Component?,
        boxX: Int,
        boxY: Int,
        boxWidth: Int,
        boxHeight: Int,
        pad: Int,
        lineHeight: Int,
        anchored: Boolean,
    ) {
        renderer.rect(boxX, boxY, boxX + boxWidth, boxY + boxHeight, D3D12Widget.TOOLTIP_BG)
        if (anchored) {
            renderer.rect(boxX, boxY, boxX + boxWidth, boxY + 1, D3D12Widget.ACCENT)
        } else {
            renderer.rect(boxX, boxY, boxX + boxWidth, boxY + 1, D3D12Widget.TOOLTIP_BORDER)
            renderer.rect(boxX, boxY + boxHeight - 1, boxX + boxWidth, boxY + boxHeight, D3D12Widget.TOOLTIP_BORDER)
            renderer.rect(boxX, boxY, boxX + 1, boxY + boxHeight, D3D12Widget.TOOLTIP_BORDER)
            renderer.rect(boxX + boxWidth - 1, boxY, boxX + boxWidth, boxY + boxHeight, D3D12Widget.TOOLTIP_BORDER)
        }

        var textY = boxY + pad
        if (anchored) {
            renderer.text(caption, boxX + pad, textY, D3D12Widget.ACCENT)
            textY += lineHeight * 2
        }
        for (line in lines) {
            renderer.text(line, boxX + pad, textY, D3D12Widget.WHITE)
            textY += lineHeight
        }
        if (reasonLines.isNotEmpty()) {
            // 空一行，先写亮黄色的"注意："，再接锁定原因（颜色更暗）。
            textY += lineHeight
            if (reasonHeader != null) {
                renderer.text(reasonHeader, boxX + pad, textY, D3D12Widget.WARNING)
                textY += lineHeight
            }
            for (reasonLine in reasonLines) {
                renderer.text(reasonLine, boxX + pad, textY, D3D12Widget.DISABLED)
                textY += lineHeight
            }
        }
    }
    private fun restartPending(): Boolean =
        D3D12HdrPresentation.isRestartPending() || minecraft.options.isRestartRequiredToApplyVideoSettings()

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        val mouseX = event.x
        val mouseY = event.y

        for (widget in widgets) {
            if (widget.mouseClicked(mouseX, mouseY, event.button())) {
                return true
            }
        }

        if (mouseY >= contentTop && mouseY <= contentBottom) {
            for ((widget, _) in rows) {
                if (widget.mouseClicked(mouseX, mouseY, event.button())) {
                    dragging = widget
                    return true
                }
            }
        }

        return super.mouseClicked(event, doubleClick)
    }

    override fun mouseReleased(event: MouseButtonEvent): Boolean {
        dragging = null
        return super.mouseReleased(event)
    }

    override fun mouseDragged(event: MouseButtonEvent, dragX: Double, dragY: Double): Boolean {
        val widget = dragging
        if (widget != null) {
            widget.mouseDragged(event.x, event.y, event.button(), dragX, dragY)
            return true
        }

        return super.mouseDragged(event, dragX, dragY)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        if (maxScroll > 0) {
            scroll = (scroll - scrollY * SCROLL_STEP).coerceIn(0.0, maxScroll.toDouble())
            applyScroll()
            return true
        }

        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)
    }

    private fun applyScroll() {
        val offset = scroll.roundToInt()
        for ((widget, baseY) in rows) {
            widget.setPosition(contentLeft, baseY - offset)
        }
    }

    private fun optimalWidth(): Int {
        val window = minecraft.window
        val monitorWidth = window.findBestMonitor()?.let { monitor ->
            monitor.currentMode.width.takeIf { it > 0 } ?: monitor.videoModes.firstOrNull()?.width
        } ?: FALLBACK_MAX_WIDTH

        val scale = window.guiScale.coerceAtLeast(1)
        // 窗口比 MIN_WIDTH 还窄时下限跟着窗口走，否则 coerceIn 会因为下界大于上界抛异常。
        val minWidth = MIN_WIDTH.coerceAtMost(width)
        return (monitorWidth * WIDTH_SHARE_OF_MONITOR / scale).toInt().coerceIn(minWidth, width)
    }

    override fun onClose() {
        // 关界面不等于应用：没点"应用"的改动一律丢弃。
        for ((_, widget) in optionRows) {
            widget.clearPending()
        }

        minecraft.setScreenAndShow(parent)
    }

    override fun removed() {
        minecraft.options.save()
    }

    private companion object {
        const val WIDTH_SHARE_OF_MONITOR: Double = 0.809

        const val FALLBACK_MAX_WIDTH: Int = 2071

        const val SCROLL_STEP: Int = 12

        const val MIN_WIDTH: Int = 360
        const val MIN_CONTENT_WIDTH: Int = 220

        /** 内容区窄于这个宽度时，滑条一律退化成折叠控件（内联展开必重叠）。 */
        const val MIN_EXPANDED_WIDTH: Int = 330
        const val SIDEBAR_X: Int = 8
        const val TITLE_X: Int = 8
        const val TITLE_Y: Int = 8
        const val OUTER_MARGIN: Int = 8
        const val GAP: Int = 8
        const val TAB_GAP: Int = 4

        /** "需要重启"提示行的高度：常驻预留，避免提示出现时压到动作按钮。 */
        const val HINT_LINE: Int = 12

        const val HINT_PAD: Int = 6

        /** 右侧至少要留这么宽才把提示画成固定块，否则整体退化成悬浮提示。 */
        const val MIN_HINT_WIDTH: Int = 96

        const val MAX_HINT_WIDTH: Int = 240

        const val FLOAT_HINT_WIDTH: Int = 200

        fun translatable(key: String): Component = Component.translatable(key)
    }
}
