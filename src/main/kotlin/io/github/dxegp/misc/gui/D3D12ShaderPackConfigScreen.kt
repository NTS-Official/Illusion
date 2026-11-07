package io.github.dxegp.misc.gui

import com.mojang.blaze3d.platform.InputConstants
import io.github.dxegp.experimental.raytracing.D3D12PackConfig
import io.github.dxegp.experimental.raytracing.D3D12ShaderPacks
import io.github.dxegp.experimental.raytracing.PackAttribute
import net.minecraft.ChatFormatting
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import net.minecraft.util.ARGB
import java.util.Locale

/**
 * 光影包设置：渲染包里 `shaders/configs.json` 声明的 `attributes`。
 *
 * 光追相关的开关是包的一部分（同一个键在不同包里含义可以不同），所以它们只在这里出现，
 * 不放进全局配置页。见 docs/rt/README.md 11.6 节。
 *
 * 改动一落盘就生效：开关/滑块直接写包的 `settings.json`，带 `key` 的再写回 TOML。
 */
class D3D12ShaderPackConfigScreen(
    private val parent: Screen,
    private val pack: String = D3D12ShaderPacks.activeName(),
) : Screen(Component.translatable("dxr_mc.shaderpack.config.title")) {

    private val groups: List<Pair<String, List<PackAttribute>>> = D3D12PackConfig.groups(pack)
    private val rows: List<Row> = buildRows()

    private var scroll: Double = 0.0
    private var dragging: PackAttribute? = null
    private val buttons: MutableList<D3D12ButtonWidget> = ArrayList()

    /** 一行要么是分组标题（[attribute] 为空），要么是一个设置项。 */
    private class Row(val attribute: PackAttribute?, val group: String, val height: Int)

    private fun buildRows(): List<Row> {
        val list = ArrayList<Row>()
        for ([group, attributes] in groups) {
            if (attributes.isEmpty()) continue
            list += Row(null, group, GROUP_HEIGHT)
            for (attribute in attributes) {
                list += Row(attribute, group, ROW_HEIGHT)
            }
        }
        return list
    }

    override fun init() {
        buttons.clear()
        buttons += D3D12ButtonWidget(
            width / 2 - 50,
            height - 27,
            100,
            20,
            { Component.translatable("dxr_mc.shaderpack.done") },
            { true },
        ) { onClose() }
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
            Component.translatable("dxr_mc.shaderpack.config.subtitle", pack).withStyle(ChatFormatting.ITALIC),
            width / 2,
            SUBTITLE_Y,
            ARGB.color(255, 160, 160, 160),
        )

        if (rows.isEmpty()) {
            renderer.centeredText(
                Component.translatable("dxr_mc.shaderpack.config.empty"),
                width / 2,
                listTop() + 8,
                D3D12Widget.DISABLED,
            )
        }

        renderRows(renderer, mouseX, mouseY)
        buttons.forEach { it.render(renderer, mouseX, mouseY, partialTick) }
    }

    private fun renderRows(renderer: D3D12WidgetRenderer, mouseX: Int, mouseY: Int) {
        val left = listLeft()
        val rowWidth = rowWidth()
        renderer.rect(left, listTop() - 2, left + rowWidth, listTop(), D3D12Widget.ACCENT)
        val scissored = maxScroll() > 0
        if (scissored) {
            renderer.enableScissor(left, listTop(), left + rowWidth, listBottom())
        }

        var y = rowTop()
        for (row in rows) {
            if (y + row.height > listTop() && y < listBottom()) {
                drawRow(renderer, row, left, y, rowWidth, mouseY)
            }
            y += row.height
        }

        if (scissored) {
            renderer.disableScissor()
        }
    }

    private fun drawRow(
        renderer: D3D12WidgetRenderer,
        row: Row,
        left: Int,
        y: Int,
        rowWidth: Int,
        mouseY: Int,
    ) {
        val attribute = row.attribute
        if (attribute == null) {
            renderer.text(groupTitle(row.group), left + 2, y + 4, D3D12Widget.ACCENT)
            return
        }

        if (mouseY >= y && mouseY < y + ROW_HEIGHT) {
            renderer.rect(left, y, left + rowWidth, y + ROW_HEIGHT - 1, D3D12Widget.BG_HOVERED)
        }

        val labelY = y + 6
        renderer.text(describe(attribute), left + 4, labelY, D3D12Widget.WHITE)

        val value = if (attribute.kind == PackAttribute.Kind.BOOL) {
            // 布尔行显示「开/关」而不是 true/false：这一列是给人看的状态，不是存储值。
            Component.translatable(
                if (D3D12PackConfig.bool(attribute)) "dxr_mc.shaderpack.value.on"
                else "dxr_mc.shaderpack.value.off",
            ).string
        } else {
            D3D12PackConfig.value(attribute, pack)
        }
        val impact = impactSuffix(attribute)
        val text = if (impact.isEmpty()) value else "$value  $impact"
        val textWidth = renderer.textWidth(text)
        renderer.text(text, left + rowWidth - textWidth - 4, labelY, D3D12Widget.WHITE)

        if (attribute.isSlider) {
            val trackLeft = left + rowWidth / 2
            val trackRight = left + rowWidth - 4
            val span = (attribute.max - attribute.min).takeIf { it > 0.0 } ?: 1.0
            val fraction = ((D3D12PackConfig.number(attribute) - attribute.min) / span).toFloat().coerceIn(0f, 1f)
            val trackY = y + ROW_HEIGHT - 5
            renderer.rect(trackLeft, trackY, trackRight, trackY + 2, ARGB.color(255, 80, 80, 80))
            val filled = trackLeft + ((trackRight - trackLeft) * fraction).toInt()
            renderer.rect(trackLeft, trackY, filled, trackY + 2, D3D12Widget.ACCENT)
        }
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        for (button in buttons) {
            if (button.mouseClicked(event.x, event.y, event.button())) {
                return true
            }
        }

        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            val attribute = rowAt(event.y)
            if (attribute != null) {
                activate(attribute, event.x)
                return true
            }
        }

        return super.mouseClicked(event, doubleClick)
    }

    override fun mouseDragged(
        event: MouseButtonEvent,
        dragX: Double,
        dragY: Double,
    ): Boolean {
        val attribute = dragging ?: return super.mouseDragged(event, dragX, dragY)
        setFromPosition(attribute, event.x)
        return true
    }

    override fun mouseReleased(event: MouseButtonEvent): Boolean {
        dragging = null
        return super.mouseReleased(event)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        if (maxScroll() <= 0 || !inList(mouseX, mouseY)) {
            return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)
        }
        scroll = (scroll - scrollY * ROW_HEIGHT).coerceIn(0.0, maxScroll().toDouble())
        return true
    }

    override fun onClose() {
        minecraft.setScreenAndShow(parent)
    }

    // ---- 命中与取值 ----

    private fun activate(attribute: PackAttribute, mouseX: Double) {
        when (attribute.kind) {
            PackAttribute.Kind.BOOL ->
                D3D12PackConfig.set(attribute, if (D3D12PackConfig.bool(attribute)) "false" else "true", pack)

            PackAttribute.Kind.ENUM -> {
                if (attribute.choices.isEmpty()) return
                val next = (D3D12PackConfig.enumIndex(attribute) + 1) % attribute.choices.size
                D3D12PackConfig.set(attribute, attribute.choices[next], pack)
            }

            PackAttribute.Kind.INT_RANGE, PackAttribute.Kind.FLOAT_RANGE -> {
                dragging = attribute
                setFromPosition(attribute, mouseX)
            }
        }
    }

    private fun setFromPosition(attribute: PackAttribute, mouseX: Double) {
        val left = listLeft().toDouble()
        val trackLeft = left + rowWidth() / 2.0
        val trackRight = left + rowWidth() - 4.0
        val fraction = ((mouseX - trackLeft) / (trackRight - trackLeft)).coerceIn(0.0, 1.0)
        val raw = attribute.min + fraction * (attribute.max - attribute.min)
        val snapped = if (attribute.kind == PackAttribute.Kind.INT_RANGE) {
            Math.round(raw).toDouble()
        } else {
            Math.round(raw * 100.0) / 100.0
        }
        val clamped = snapped.coerceIn(attribute.min, attribute.max)
        val text = if (attribute.kind == PackAttribute.Kind.INT_RANGE) {
            clamped.toInt().toString()
        } else {
            String.format(Locale.ROOT, "%.2f", clamped)
        }
        D3D12PackConfig.set(attribute, text, pack)
    }

    private fun rowAt(mouseY: Double): PackAttribute? {
        if (!inList(listLeft().toDouble(), mouseY)) return null
        var y = rowTop().toDouble()
        for (row in rows) {
            if (mouseY >= y && mouseY < y + row.height) {
                return row.attribute
            }
            y += row.height
        }
        return null
    }

    // ---- 布局 ----

    private fun rowWidth(): Int = minOf(320, width - 50)

    private fun listLeft(): Int = width / 2 - rowWidth() / 2

    private fun listTop(): Int = 32

    private fun listBottom(): Int = height - 27 - 8

    private fun rowTop(): Int = listTop() - scroll.toInt()

    private fun contentHeight(): Int = rows.sumOf { it.height }

    private fun maxScroll(): Int = (contentHeight() - (listBottom() - listTop())).coerceAtLeast(0)

    private fun inList(mouseX: Double, mouseY: Double): Boolean {
        val left = listLeft()
        return mouseX >= left && mouseX <= left + rowWidth() && mouseY >= listTop() && mouseY <= listBottom()
    }

    private fun describe(attribute: PackAttribute): Component =
        if (attribute.description.contains('.')) {
            Component.translatable(attribute.description)
        } else {
            Component.literal(attribute.description)
        }

    private fun groupTitle(group: String): Component = when {
        group.isEmpty() -> Component.empty()
        group.contains('.') -> Component.translatable(group)
        else -> Component.literal(group)
    }

    private fun impactSuffix(attribute: PackAttribute): String = when (attribute.impact) {
        "high" -> Component.translatable("dxr_mc.shaderpack.config.impact.high").string
        "low" -> Component.translatable("dxr_mc.shaderpack.config.impact.low").string
        else -> ""
    }

    private companion object {
        private const val TITLE_Y = 8
        private const val SUBTITLE_Y = 21
        private const val ROW_HEIGHT = 20
        private const val GROUP_HEIGHT = 16
    }
}
