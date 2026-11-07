package io.github.dxegp.misc.gui

import com.mojang.blaze3d.platform.InputConstants
import io.github.dxegp.experimental.raytracing.D3D12ShaderPacks
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.util.ARGB
import net.minecraft.util.FormattedCharSequence
import kotlin.math.max
import kotlin.math.min

/**
 * 光影包选择界面。
 *
 * 布局照 Iris / OptiFine 那一版：标题、包列表、底部两行按钮（打开文件夹 / 重新扫描、撤销 / 应用 / 完成），
 * 指针在包上停留一会儿再在底部弹出说明面板。
 *
 * 「完成」是应用并关闭，「撤销」丢弃这次选择，Esc 等同撤销。应用只写配置，管线要下次进世界才重建。
 * 见 docs/misc/settingscreen/README.md §7
 */
class D3D12ShaderPackScreen(private val parent: Screen) :
    Screen(Component.translatable("dxr_mc.shaderpack.title")) {

    private var packs: List<D3D12ShaderPacks.Pack> = D3D12ShaderPacks.all()

    /** 当前高亮行：0 = 无光影，n = `packs[n - 1]`。 */
    private var selection: Int = rowOf(D3D12ShaderPacks.selected())

    /** 已落盘的那一行（黄色高亮的就是它）。 */
    private var applied: Int = selection

    private var scroll: Double = 0.0
    private var hoveredRow: Int = -1
    private var hoveredFrames: Int = 0
    private var note: Component? = null

    private val buttons: MutableList<D3D12ButtonWidget> = ArrayList()

    override fun init() {
        buttons.clear()
        val centre = width / 2
        val firstRow = height - 51
        val secondRow = height - 27
        buttons += D3D12ButtonWidget(
            centre - 76 - 78,
            firstRow,
            152,
            20,
            { Component.translatable("dxr_mc.shaderpack.open_folder") },
            { true },
            ::openFolder,
        )
        buttons += D3D12ButtonWidget(
            centre - 76 + 78,
            firstRow,
            152,
            20,
            { Component.translatable("dxr_mc.shaderpack.rescan") },
            { true },
            ::rescan,
        )
        buttons += D3D12ButtonWidget(
            centre - 50 - 104,
            secondRow,
            100,
            20,
            { Component.translatable("dxr_mc.shaderpack.cancel") },
            { true },
            ::cancel,
        )
        buttons += D3D12ButtonWidget(
            centre - 50,
            secondRow,
            100,
            20,
            { Component.translatable("dxr_mc.shaderpack.apply") },
            { true },
        ) { applySelection() }
        buttons += D3D12ButtonWidget(
            centre - 50 + 104,
            secondRow,
            100,
            20,
            { Component.translatable("dxr_mc.shaderpack.done") },
            { true },
            ::applyAndClose,
        )
    }

    override fun extractRenderState(
        extractor: GuiGraphicsExtractor,
        mouseX: Int,
        mouseY: Int,
        partialTick: Float,
    ) {
        super.extractRenderState(extractor, mouseX, mouseY, partialTick)
        val renderer = D3D12WidgetRenderer(extractor)

        renderer.centeredText(title, width / 2, TITLE_Y, D3D12Widget.WHITE)
        renderer.centeredText(
            Component.translatable("dxr_mc.shaderpack.subtitle").withStyle(ChatFormatting.ITALIC),
            width / 2,
            SUBTITLE_Y,
            ARGB.color(255, 160, 160, 160),
        )

        trackHover(mouseX, mouseY)
        renderRows(renderer)
        buttons.forEach { it.render(renderer, mouseX, mouseY, partialTick) }
        renderComment(renderer)

        note?.let { renderer.centeredText(it, width / 2, height - 73, D3D12Widget.WARNING) }
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        for (button in buttons) {
            if (button.mouseClicked(event.x, event.y, event.button())) {
                return true
            }
        }

        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            val row = rowAt(event.x, event.y)
            if (row >= 0) {
                select(row)
                return true
            }
        }

        return super.mouseClicked(event, doubleClick)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        if (maxScroll() <= 0 || !inList(mouseX, mouseY)) {
            return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)
        }

        scroll = (scroll - scrollY * ROW_HEIGHT).coerceIn(0.0, maxScroll().toDouble())
        return true
    }

    /** 关界面不应用：没点「应用」的选择一律丢掉。 */
    override fun onClose() {
        minecraft.setScreenAndShow(parent)
    }

    private fun applyAndClose() {
        if (applySelection()) {
            onClose()
        }
    }

    /** 应用当前选择；成功返回 true（「应用」据此打提示、「完成」据此关界面）。 */
    private fun applySelection(): Boolean {
        if (selection > 0 && !packs[selection - 1].compatible) {
            note = Component.translatable("dxr_mc.shaderpack.incompatible")
            return false
        }

        val name = if (selection == 0) D3D12ShaderPacks.NONE else packs[selection - 1].name
        D3D12ShaderPacks.apply(name)
        applied = selection
        note = Component.translatable("dxr_mc.shaderpack.applied", rowName(selection))
        return true
    }

    /** 取消 = 丢掉没应用的选择并关界面（只丢选择不关，玩家按了没有反应）。 */
    private fun cancel() {
        selection = applied
        note = null
        onClose()
    }

    private fun rescan() {
        packs = D3D12ShaderPacks.reload()
        selection = rowOf(D3D12ShaderPacks.selected())
        applied = selection
        scroll = 0.0
        hoveredRow = -1
        note = null
    }

    private fun openFolder() {
        note = if (D3D12ShaderPacks.openDirectory()) {
            null
        } else {
            Component.translatable("dxr_mc.shaderpack.open_failed", D3D12ShaderPacks.packsDirectory().absolutePath)
        }
    }

    private fun select(row: Int) {
        if (row == selection) return
        selection = row
        note = null
    }

    private fun trackHover(mouseX: Int, mouseY: Int) {
        val row = rowAt(mouseX.toDouble(), mouseY.toDouble())
        if (row == hoveredRow) {
            hoveredFrames++
            return
        }
        hoveredRow = row
        hoveredFrames = 0
    }

    private fun renderRows(renderer: D3D12WidgetRenderer) {
        val left = listLeft()
        val width = rowWidth()
        renderer.rect(left, listTop() - 2, left + width, listTop(), D3D12Widget.ACCENT)
        val scissored = maxScroll() > 0
        if (scissored) {
            renderer.enableScissor(left, listTop(), left + width, listBottom())
        }

        for (row in 0 until rowCount()) {
            val y = rowTop(row)
            if (y + ROW_HEIGHT <= listTop() || y >= listBottom()) {
                continue
            }

            val hovered = row == hoveredRow
            val colour = when {
                row > 0 && !packs[row - 1].compatible -> D3D12Widget.DISABLED
                row == applied -> APPLIED_COLOR
                else -> D3D12Widget.WHITE
            }

            if (hovered) {
                renderer.rect(left, y, left + width, y + ROW_HEIGHT - 1, D3D12Widget.BG_HOVERED)
            } else if (row == selection) {
                renderer.rect(left, y, left + width, y + ROW_HEIGHT - 1, D3D12Widget.BG)
            }

            val label = rowLabel(row, hovered)
            renderer.text(label, left + width / 2 - renderer.textWidth(label) / 2, y + 6, colour)
        }

        if (scissored) {
            renderer.disableScissor()
        }
        renderer.rect(left, listBottom(), left + width, listBottom() + 2, D3D12Widget.ACCENT)
    }

    private fun renderComment(renderer: D3D12WidgetRenderer) {
        val row = hoveredRow
        if (row < 0 || hoveredFrames < COMMENT_DELAY_FRAMES) {
            return
        }

        val lines = commentLines(row)
        val panelHeight = max(50, 18 + lines.size * 10)
        val left = width / 2 - COMMENT_WIDTH / 2
        val top = height - (panelHeight + 4)

        renderer.pushAlpha(min(1.0, hoveredFrames / (COMMENT_DELAY_FRAMES * 2.0)))
        renderer.rect(left - 1, top - 1, left + COMMENT_WIDTH + 1, top + panelHeight + 1, D3D12Widget.TOOLTIP_BORDER)
        renderer.rect(left, top, left + COMMENT_WIDTH, top + panelHeight, D3D12Widget.TOOLTIP_BG)
        renderer.text(commentTitle(row), left + 4, top + 4, D3D12Widget.WHITE)
        lines.forEachIndexed { index, line ->
            renderer.text(line, left + 4, top + 16 + index * 10, D3D12Widget.DISABLED)
        }
        renderer.popAlpha()
    }

    private fun commentTitle(row: Int): Component = if (row == 0) {
        Component.translatable("dxr_mc.shaderpack.none")
    } else {
        Component.literal(packs[row - 1].displayName)
    }

    private fun commentLines(row: Int): List<FormattedCharSequence> {
        val font = Minecraft.getInstance().font
        if (row == 0) {
            return font.split(Component.translatable("dxr_mc.shaderpack.none.description"), COMMENT_WIDTH - 8)
        }

        val pack = packs[row - 1]
        val body = pack.description.ifEmpty {
            Component.translatable("dxr_mc.shaderpack.empty_description").string
        }
        val lines = ArrayList<FormattedCharSequence>()
        lines += font.split(Component.literal(body), COMMENT_WIDTH - 8)
        if (pack.author.isNotEmpty()) {
            lines += font.split(Component.translatable("dxr_mc.shaderpack.author", pack.author), COMMENT_WIDTH - 8)
        }
        if (pack.version.isNotEmpty()) {
            lines += font.split(Component.translatable("dxr_mc.shaderpack.version", pack.version), COMMENT_WIDTH - 8)
        }
        val status = if (pack.compatible) {
            Component.translatable("dxr_mc.shaderpack.status.ready")
        } else {
            Component.translatable("dxr_mc.shaderpack.status.missing", pack.missing.joinToString())
        }
        lines += font.split(status, COMMENT_WIDTH - 8)
        return lines
    }

    private fun rowLabel(row: Int, hovered: Boolean): Component =
        if (hovered) rowName(row).withStyle(ChatFormatting.BOLD) else rowName(row)

    private fun rowName(row: Int): MutableComponent = if (row == 0) {
        Component.translatable("dxr_mc.shaderpack.none")
    } else {
        Component.literal(packs[row - 1].displayName)
    }

    private fun rowOf(name: String): Int {
        if (name.isEmpty()) return 0
        val index = packs.indexOfFirst { it.name == name }
        return if (index < 0) 0 else index + 1
    }

    private fun rowCount(): Int = packs.size + 1

    private fun rowTop(row: Int): Int = listTop() + row * ROW_HEIGHT - scroll.toInt()

    private fun rowAt(mouseX: Double, mouseY: Double): Int {
        if (!inList(mouseX, mouseY)) return -1
        val index = ((mouseY - listTop() + scroll) / ROW_HEIGHT).toInt()
        return if (index in 0 until rowCount()) index else -1
    }

    private fun inList(mouseX: Double, mouseY: Double): Boolean {
        val left = listLeft().toDouble()
        return mouseY >= listTop() && mouseY < listBottom() && mouseX >= left && mouseX < left + rowWidth()
    }

    private fun listLeft(): Int = width / 2 - rowWidth() / 2

    private fun rowWidth(): Int = min(ROW_WIDTH_MAX, width - 50)

    private fun listTop(): Int = LIST_TOP

    private fun listBottom(): Int = height - LIST_BOTTOM_MARGIN

    private fun maxScroll(): Int = max(0, rowCount() * ROW_HEIGHT - (listBottom() - listTop()))

    private companion object {
        /** 行宽上限：Iris 用 308，窄窗口再收窄。 */
        const val ROW_WIDTH_MAX = 308
        const val ROW_HEIGHT = 20
        const val TITLE_Y = 8
        const val SUBTITLE_Y = 21
        const val LIST_TOP = 32

        /** 列表底边离窗口底多少：给两行按钮留位置（Iris 的 `height - 94`）。 */
        const val LIST_BOTTOM_MARGIN = 94
        const val COMMENT_WIDTH = 314

        /** 指针在包上停多少帧才弹说明（Iris 是 10 tick）。 */
        const val COMMENT_DELAY_FRAMES = 10

        /** 已应用包的高亮色，与 Iris 的 `0xFFFFF263` 同一个黄。 */
        val APPLIED_COLOR: Int = ARGB.color(255, 255, 242, 99)
    }
}




