package dev.nts.expdxr.misc.gui

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.network.chat.Component
import net.minecraft.sounds.SoundEvents
import net.minecraft.util.ARGB
import net.minecraft.util.FormattedCharSequence

/** [GuiGraphicsExtractor] 没有画笔状态也没有 alpha 栈：所有绘制都经这里转一手，颜色 alpha 乘上当前栈值。 */
class D3D12WidgetRenderer(private val extractor: GuiGraphicsExtractor) {
    private val alphas: ArrayDeque<Double> = ArrayDeque()

    private val currentAlpha: Double
        get() = alphas.lastOrNull() ?: 1.0

    fun pushAlpha(alpha: Double) {
        alphas.addLast(currentAlpha * alpha)
    }

    fun popAlpha() {
        if (alphas.isNotEmpty()) {
            alphas.removeLast()
        }
    }

    fun rect(x1: Int, y1: Int, x2: Int, y2: Int, color: Int) {
        extractor.fill(x1, y1, x2, y2, withAlpha(color))
    }

    fun text(text: Component, x: Int, y: Int, color: Int) {
        extractor.text(FONT, text, x, y, withAlpha(color))
    }

    fun text(text: String, x: Int, y: Int, color: Int) {
        extractor.text(FONT, text, x, y, withAlpha(color))
    }

    fun text(text: FormattedCharSequence, x: Int, y: Int, color: Int) {
        extractor.text(FONT, text, x, y, withAlpha(color))
    }

    fun centeredText(text: Component, x: Int, y: Int, color: Int) {
        extractor.centeredText(FONT, text, x, y, withAlpha(color))
    }

    fun textWidth(text: Component): Int = FONT.width(text)

    fun textWidth(text: String): Int = FONT.width(text)

    fun textHeight(): Int = FONT.lineHeight

    fun enableScissor(x1: Int, y1: Int, x2: Int, y2: Int) {
        extractor.enableScissor(x1, y1, x2, y2)
    }

    fun disableScissor() {
        extractor.disableScissor()
    }

    /** 颜色是 ARGB，当前栈值乘在 alpha 通道上。 */
    private fun withAlpha(color: Int): Int {
        val alpha = currentAlpha
        if (alpha >= 1.0) {
            return color
        }

        val base = (color ushr 24) and 0xFF
        val scaled = (base * alpha).toInt().coerceIn(0, 255)
        return (scaled shl 24) or (color and 0xFFFFFF)
    }

    private companion object {
        val FONT: Font
            get() = Minecraft.getInstance().font
    }
}

/**
 * 自绘控件基类：位置、尺寸与鼠标回调都在这里，绘制统一走 [render]。
 *
 * 不注册到 `Screen` 的 `renderables`：`Screen.extractRenderState` 直接遍历自己的控件列表，顺序与裁剪由屏幕控制。
 */
abstract class D3D12Widget(x: Int, y: Int, width: Int, height: Int) {
    var x: Int = x
        protected set

    var y: Int = y
        protected set

    var width: Int = width
        protected set

    var height: Int = height
        protected set

    fun isMouseOver(mouseX: Double, mouseY: Double): Boolean =
        mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height

    fun isMouseOver(mouseX: Int, mouseY: Int): Boolean = isMouseOver(mouseX.toDouble(), mouseY.toDouble())

    fun setPosition(x: Int, y: Int) {
        this.x = x
        this.y = y
    }

    open fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean = false

    open fun mouseReleased(mouseX: Double, mouseY: Double, button: Int): Boolean = false

    open fun mouseDragged(mouseX: Double, mouseY: Double, button: Int, dragX: Double, dragY: Double): Boolean = false

    open fun mouseMoved(mouseX: Double, mouseY: Double) {
    }

    abstract fun render(renderer: D3D12WidgetRenderer, mouseX: Int, mouseY: Int, delta: Float)

    protected fun centerY(h: Int): Int = y + (height - h) / 2

    protected fun centerTextY(renderer: D3D12WidgetRenderer): Int = centerY(renderer.textHeight()) + 1

    protected fun centerX(w: Int): Int = x + (width - w) / 2

    protected fun alignRight(w: Int, totalWidth: Int): Int = x + totalWidth - w - 6

    protected fun alignRight(w: Int): Int = alignRight(w, width)

    protected fun playClickSound() {
        Minecraft.getInstance().soundManager.play(
            SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0f),
        )
    }

    companion object {
        const val DEFAULT_HEIGHT: Int = 21

        val BG: Int = ARGB.color(125, 0, 0, 0)
        val BG_HOVERED: Int = ARGB.color(175, 0, 0, 0)
        const val WHITE: Int = -1
        val DISABLED: Int = ARGB.color(160, 160, 160, 255)

        /** 分组标题条：必须比行底更黑一档，否则"组"和"行"分不开。 */
        val HEADING_BG: Int = ARGB.color(205, 0, 0, 0)

        val ACCENT: Int = ARGB.color(225, 176, 124, 255)
        /** 警示色。参数序是 a,r,g,b —— 顺序写错会得到蓝紫而不是红。 */
        val RED: Int = ARGB.color(255, 255, 85, 85)

        /** 锁定提示的表头色。 */
        val WARNING: Int = ARGB.color(255, 255, 214, 64)

        val TOOLTIP_BG: Int = ARGB.color(232, 0, 0, 0)

        val TOOLTIP_BORDER: Int = ARGB.color(120, 255, 255, 255)

        /** 逐通道线性插值两个 ARGB 颜色。 */
        fun blend(from: Int, to: Int, amount: Double): Int {
            val t = amount.coerceIn(0.0, 1.0)

            fun channel(shift: Int): Int {
                val a = (from ushr shift) and 0xFF
                val b = (to ushr shift) and 0xFF
                return (a + (b - a) * t).toInt().coerceIn(0, 255)
            }

            return (channel(24) shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
        }
    }
}
