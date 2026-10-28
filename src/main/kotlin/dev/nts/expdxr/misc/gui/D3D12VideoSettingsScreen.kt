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
 *
 * <p>页可以再分二级子页（[D3D12VideoSettingsPage.subPages]）：标题横排在内容区顶部，宽度按文本自适应。
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

    /** 右侧滚动条：位置与长度每次重排时由 [layoutScrollBar] 重算，滚动量取自 [scroll]。 */
    private val scrollBar = D3D12ScrollBarWidget({ scroll }, { moveTo(it) })

    /** 二级子页下标，按页记。 */
    private val subPageByPage: MutableMap<Int, Int> = mutableMapOf()

    /** 二级页签：固定不滚动。 */
    private val subTabs: MutableList<D3D12Widget> = ArrayList()

    /** 有子页时，页自己的行固定在页签上方，不参与滚动。 */
    private val headerRows: MutableList<Pair<D3D12Widget, Int>> = ArrayList()

    /** `headerRows` 里的控件快照：区分"固定在页签上方"与"会滚动的行"两块区域用。 */
    private val headerWidgets: MutableSet<D3D12Widget> = HashSet()

    /** 二级页签行的 y，由上方固定行的高度决定。 */
    private var tabY = 0

    /** 二级页签控件属于哪一页；换页才重建。 */
    private var subTabsPage = -1

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

    // ------------------------------------------------------------ 嵌套子页

    private fun subPages(): List<D3D12VideoSettingsSubPage> = pages[page].subPages

    /** 当前页选中的二级子页；没有子页时恒为 0。 */
    private fun activeSubPage(): Int {
        val count = subPages().size
        if (count == 0) {
            return 0
        }

        return (subPageByPage[page] ?: 0).coerceIn(0, count - 1)
    }

    /** 本页要铺的行：子页内容（或整页行）+ 页尾行。 */
    private fun visibleEntries(): List<D3D12VideoSettingsEntry> {
        val group = subPages()
        val pageData = pages[page]
        return if (group.isEmpty()) {
            pageData.entries + pageData.trailingEntries
        } else {
            group[activeSubPage()].entries + pageData.trailingEntries
        }
    }

    /** 内容区首行的 y（有二级页签时让出页签那一行）。 */
    private fun rowsTop(): Int = tabY + if (subPages().isEmpty()) 0 else D3D12SubTabWidget.HEIGHT + GAP

    /** 清空页签并强制下次重建。离开有子页的页面时必须调用，否则残留的页签会叠在新页面的行上。 */
    private fun clearSubTabs() {
        subTabs.clear()
        subTabsPage = -1
    }

    /** 按"标题像素宽 + 内边距"横排二级页签；排不下先压内边距，仍排不下按内容区截断。
     * 同一页内只挪位置不重建，否则选中动画会被打回起点。 */
    private fun buildSubTabs() {
        val group = subPages()
        if (group.isEmpty()) {
            clearSubTabs()
            return
        }

        val rebuild = subTabsPage != page || subTabs.size != group.size

        val font = Minecraft.getInstance().font
        val textWidths = group.map { font.width(it.name) }
        val gaps = SUB_TAB_GAP * (group.size - 1)
        var pad = SUB_TAB_PAD
        while (pad > SUB_TAB_MIN_PAD && textWidths.sum() + gaps + pad * 2 * group.size > contentWidth) {
            pad--
        }

        if (rebuild) {
            subTabs.clear()
        }

        var x = contentLeft
        group.forEachIndexed { index, subPage ->
            val width = (textWidths[index] + pad * 2)
                .coerceIn(SUB_TAB_MIN_WIDTH.coerceAtMost(contentWidth), contentWidth)
            if (rebuild) {
                subTabs += D3D12SubTabWidget(
                    x, tabY, width, subPage.name,
                    { activeSubPage() == index },
                    { selectSubPage(index) },
                )
            } else {
                subTabs[index].setPosition(x, tabY)
            }
            x += width + SUB_TAB_GAP
        }
        subTabsPage = page
    }

    private fun selectSubPage(index: Int) {
        if (subPages().isEmpty() || activeSubPage() == index) {
            return
        }

        subPageByPage[page] = index
        scroll = 0.0
        // 页签不重建：选中状态是 lambda 现算的，重建会把淡入淡出打断。
        buildRows()
    }

    private fun buildRows() {
        // 切页签/重新布局会重建行，未点"应用"的暂存值必须跟着走。
        val carried = optionRows.mapNotNull { [option, widget] ->
            widget.pendingOrNull()?.let { option to it }
        }.toMap()

        rows.clear()
        optionRows.clear()
        headerRows.clear()
        headerWidgets.clear()

        val pageData = pages[page]
        var y = contentTop
        if (pageData.subPages.isEmpty()) {
            tabY = contentTop
            clearSubTabs()
            y = layoutEntries(visibleEntries(), contentTop, carried, rows)
        } else {
            // 页自己的行固定在页签上方：行区从页签下面开始，滚动不带走它们。
            y = layoutEntries(pageData.entries, contentTop, carried, headerRows)
            headerRows.forEach { headerWidgets += it.first }
            tabY = y + GAP
            buildSubTabs()
            y = layoutEntries(visibleEntries(), rowsTop(), carried, rows)
        }

        val top = rowsTop()
        maxScroll = (y - top - (contentBottom - top)).coerceAtLeast(0)
        scroll = scroll.coerceIn(0.0, maxScroll.toDouble())
        applyScroll()
        layoutScrollBar()
    }

    /** 把一组条目铺进 `target`，返回下一个可用 y。 */
    private fun layoutEntries(
        entries: List<D3D12VideoSettingsEntry>,
        start: Int,
        carried: Map<OptionInstance<*>, Any?>,
        target: MutableList<Pair<D3D12Widget, Int>>,
    ): Int {
        var y = start
        for (entry in entries) {
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

            target += widget to y
            // 折叠控件展开后会多占一行，后面的行整体下移（不叠加、不覆盖）。
            y += D3D12Widget.DEFAULT_HEIGHT +
                if (entry is D3D12VideoSettingsEntry.Setting && expandedOptions.contains(entry.option)) D3D12OptionWidget.SLIDER_LINE else 0
        }

        return y
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
        for (page in pages) {
            val entries = page.entries + page.trailingEntries + page.subPages.flatMap { it.entries }
            for (entry in entries) {
                if (entry is D3D12VideoSettingsEntry.Setting) baseline[entry.option] = entry.option.get()
            }
        }
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
        for ([_, widget] in optionRows) {
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

        // 页签上方那一块固定不动。
        for ([widget, _] in headerRows) {
            widget.render(renderer, mouseX, mouseY, partialTick)
        }

        val top = rowsTop()
        val scissored = maxScroll > 0 && contentBottom > top
        if (scissored) {
            renderer.enableScissor(contentLeft, top, contentRight, contentBottom)
        }

        for ([widget, _] in rows) {
            widget.render(renderer, mouseX, mouseY, partialTick)
        }

        if (scissored) {
            renderer.disableScissor()
        }

        // 二级页签固定不滚动。
        subTabs.forEach { it.render(renderer, mouseX, mouseY, partialTick) }

        scrollBar.render(renderer, mouseX, mouseY, partialTick)

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

        // 内容区外的行（被下半部分按钮挡住 / 滚出边界）不能弹提示：滚动区里的行还要额外越过页签，
        // 否则滚到页签底下、已经裁掉的行仍会压着指针弹提示。
        if (mouseY !in contentTop..contentBottom) return
        val scrollTop = rowsTop()
        val hovered = optionRows.lastOrNull { [_, widget] ->
            val regionTop = if (headerWidgets.contains(widget)) contentTop else scrollTop
            widget.y >= regionTop && widget.y + widget.height <= contentBottom &&
                widget.isMouseOver(mouseX.toDouble(), mouseY.toDouble())
        }
            ?.second ?: return
        val tooltipText = hovered.tooltipText() ?: return

        val font = Minecraft.getInstance().font
        val caption = hovered.captionComponent()
        val lineHeight = renderer.textHeight()
        val pad = HINT_PAD
        // 内容区右边这一列留给滚动条，提示块必须整列让开，否则会压在滚动条上。
        val freeRight = width - (contentRight + HINT_COLUMN_OFFSET) - OUTER_MARGIN
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
            boxX = contentRight + HINT_COLUMN_OFFSET
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

        // 二级页签在行之上，先判。
        for (widget in subTabs) {
            if (widget.mouseClicked(mouseX, mouseY, event.button())) {
                return true
            }
        }

        // 页签上方的固定行（渲染缩放、VRS）也带滑条。
        for ([widget, _] in headerRows) {
            if (widget.mouseClicked(mouseX, mouseY, event.button())) {
                dragging = widget
                return true
            }
        }

        // 滚动区只认页签下面那块：指针在固定的行或页签上时，不该滚走子页内容。
        if (mouseY >= rowsTop() && mouseY <= contentBottom && mouseX >= contentLeft && mouseX <= contentRight) {
            for ([widget, _] in rows) {
                if (widget.mouseClicked(mouseX, mouseY, event.button())) {
                    dragging = widget
                    return true
                }
            }
        }

        // 滚动条在内容区之外，点它不经过任何行。
        if (scrollBar.mouseClicked(mouseX, mouseY, event.button())) {
            dragging = scrollBar
            return true
        }

        return super.mouseClicked(event, doubleClick)
    }

    override fun mouseReleased(event: MouseButtonEvent): Boolean {
        val widget = dragging
        dragging = null
        // 必须转发：滑条与滚动条靠这一下结束拖拽状态，否则松手后它们仍以为自己还在被拖。
        widget?.mouseReleased(event.x, event.y, event.button())
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
        // 滚轮只作用于指针所在的区域：页签上方的固定行与页签本身都不滚；指针压在右侧滚动条上照滚。
        val overBar = scrollBar.isActive() && scrollBar.isOverBar(mouseX, mouseY)
        val inScrollRegion = maxScroll > 0 &&
            (overBar ||
                (mouseY >= rowsTop() && mouseY <= contentBottom && mouseX >= contentLeft && mouseX <= contentRight))
        if (inScrollRegion) {
            moveTo(scroll - scrollY * SCROLL_STEP)
            // 滚轮滚过才启用自动隐藏：没滚过就淡出的话，鼠标玩家会连入口都看不到。
            scrollBar.notifyWheel()
            return true
        }

        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)
    }

    private fun applyScroll() {
        val offset = scroll.roundToInt()
        for ([widget, baseY] in rows) {
            widget.setPosition(contentLeft, baseY - offset)
        }
    }

    /**
     * 滚动条的横坐标跟着内容区右边界走：平时在边界之外，有二级子页时整条向内收缩一点 ——
     * 这时它滚的是子页内容、不是整页，位置要能看出这一层。
     */
    private fun layoutScrollBar() {
        val inset = if (pages[page].subPages.isEmpty()) SCROLLBAR_OFFSET else SCROLLBAR_NESTED_OFFSET
        // 窗口窄到内容区右边界贴着画面时把条夹回窗口内：留在外面就是整条不可见、也点不到。
        val left = minOf(contentRight + inset, width - D3D12ScrollBarWidget.WIDTH)
        val top = rowsTop()
        val viewport = (contentBottom - top).coerceAtLeast(0)
        scrollBar.layout(left, top, viewport, viewport + maxScroll)
    }

    /** 滚动量唯一的写入点：夹到可用范围后立刻重排行位置。 */
    private fun moveTo(value: Double) {
        scroll = value.coerceIn(0.0, maxScroll.toDouble())
        applyScroll()
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
        for ([_, widget] in optionRows) {
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

        /** 二级页签：内边距（排不下时先压缩）、间隙与最小宽度。 */
        const val SUB_TAB_PAD: Int = 12
        const val SUB_TAB_MIN_PAD: Int = 4
        const val SUB_TAB_GAP: Int = 4
        const val SUB_TAB_MIN_WIDTH: Int = 36

        /** "需要重启"提示行的高度：常驻预留，避免提示出现时压到动作按钮。 */
        const val HINT_LINE: Int = 12

        const val HINT_PAD: Int = 6

        /** 右侧滚动条：距内容区右边界的外扩距离，以及有二级子页时向内收缩后的距离。 */
        const val SCROLLBAR_OFFSET: Int = 6
        const val SCROLLBAR_NESTED_OFFSET: Int = 2

        /** 右侧提示块给滚动条让出的整列宽度（滚动条 + 外扩距离 + 一点间隙）。 */
        const val HINT_COLUMN_OFFSET: Int = SCROLLBAR_OFFSET + D3D12ScrollBarWidget.WIDTH + 4

        /** 右侧至少要留这么宽才把提示画成固定块，否则整体退化成悬浮提示。 */
        const val MIN_HINT_WIDTH: Int = 96

        const val MAX_HINT_WIDTH: Int = 240

        const val FLOAT_HINT_WIDTH: Int = 200

        fun translatable(key: String): Component = Component.translatable(key)
    }
}
