package dev.nts.expdxr.misc.settings

import com.mojang.serialization.Codec
import dev.nts.expdxr.Illusion
import dev.nts.expdxr.d3d12.extern.MCDR_ABI
import dev.nts.expdxr.d3d12.render.adapter.D3D12AdapterInfo
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Optional
import net.minecraft.client.OptionInstance
import net.minecraft.client.gui.components.CycleButton
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.network.chat.Component

/**
 * 原生 HDR 呈现（scRGB 交换链）的视频设置项，位于原版「显示」子页。
 *
 * <p>三档下标就是下发给 native 的模式编号（0=AUTO / 1=FORCE_ON / 2=FORCE_OFF），不要重排；
 * 交换链格式与色彩空间是建链参数，改动只在下一次创建交换链时生效。
 * 见 docs/misc/settingscreen/README.md §8.5。
 */
object D3D12HdrPresentation {

    /** `options.txt` 里的键。 */
    const val OPTIONS_KEY: String = "dxrMcHdr"

    /** 取值名，下标即 native 模式编号。 */
    private val MODE_NAMES: List<String> = listOf("auto", "on", "off")

    private const val DEFAULT_MODE: Int = 0

    private object ModeValues : OptionInstance.CycleableValueSet<Int> {
        override fun validateValue(value: Int): Optional<Int> =
            if (value in MODE_NAMES.indices) Optional.of(value) else Optional.empty()

        override fun valueListSupplier(): CycleButton.ValueListSupplier<Int> =
            CycleButton.ValueListSupplier.create(MODE_NAMES.indices.toList())

        override fun codec(): Codec<Int> = Codec.intRange(0, MODE_NAMES.size - 1)
    }

    private val OPTION: OptionInstance<Int> = OptionInstance(
        "options.hdr",
        { Tooltip.create(Component.translatable("options.hdr.tooltip")) },
        { _, value -> Component.translatable("options.hdr.${MODE_NAMES[value]}") },
        ModeValues,
        DEFAULT_MODE,
        { value -> push(value) },
    )

    @JvmStatic
    fun option(): OptionInstance<Int> = OPTION

    /** 判据是 [D3D12AdapterInfo.current]（设备真的建起来），不是配置里的首选后端。 */
    @JvmStatic
    fun isAvailable(): Boolean = D3D12AdapterInfo.current != null

    /** 当前模式（下标 0/1/2），也是写回 `options.txt` 的值。 */
    @JvmStatic
    fun mode(): Int = currentMode

    /** 基准是 native 记的**建链时**模式（`D3D12HdrAppliedMode`），`-1`（还没建过链）时一律 false。 */
    @JvmStatic
    fun isRestartPending(): Boolean {
        if (!isAvailable()) {
            return false
        }
        val applied = MCDR_ABI.D3D12HdrAppliedMode()
        return applied >= 0 && applied != currentMode
    }

    private var currentMode: Int = DEFAULT_MODE

    private fun push(mode: Int) {
        currentMode = mode
        MCDR_ABI.D3D12SetHdrMode(mode)
    }

    /** 从 `options.txt` 读回模式（认名字与数字两种写法），并立刻推给 native。 */
    @JvmStatic
    fun load(file: File) {
        if (!file.isFile) {
            return
        }
        try {
            for (line in file.readLines(StandardCharsets.UTF_8)) {
                if (!line.startsWith("$OPTIONS_KEY:")) {
                    continue
                }
                val raw = line.substring(OPTIONS_KEY.length + 1).trim().lowercase()
                val byName = MODE_NAMES.indexOf(raw).takeIf { it >= 0 }
                val value = byName ?: raw.toIntOrNull()?.takeIf { it in MODE_NAMES.indices }
                if (value != null) {
                    OPTION.set(value)
                }
                break
            }
        } catch (t: Throwable) {
            Illusion.LOGGER.warn("could not read $OPTIONS_KEY from $file", t)
        }
        // `Options.load` 期间 `OptionInstance.set` 不触发回调，所以这里补一次 push。
        push(OPTION.get())
    }

    /** 把模式写回 `options.txt`（先删同键旧行再追加）。 */
    @JvmStatic
    fun save(file: File) {
        if (!file.isFile) {
            return
        }
        try {
            val eol = System.lineSeparator()
            val kept = file.readLines(StandardCharsets.UTF_8)
                .filterNot { line -> line.startsWith("$OPTIONS_KEY:") }
            val text = (kept + "${OPTIONS_KEY}:${MODE_NAMES[currentMode]}").joinToString(eol) + eol
            file.writeText(text, StandardCharsets.UTF_8)
        } catch (t: Throwable) {
            Illusion.LOGGER.warn("could not write $OPTIONS_KEY to $file", t)
        }
    }
}
