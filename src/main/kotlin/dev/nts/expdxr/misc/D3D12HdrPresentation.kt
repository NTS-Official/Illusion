package dev.nts.expdxr.misc

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
 * P198：原生 HDR 呈现（P197 的 scRGB 交换链）的**视频设置项**。
 *
 * <p>用户要求：HDR 不放在模组自己的 "Illusion 设置" 栏里，而是进原版的
 * **显示**子页面（`VideoSettingsScreen.displayOptions`），与分辨率/全屏/亮度并列；后端不是
 * D3D12 时整块设置（含本项）都不出现。
 *
 * <p>三档语义与 native 侧 `mcdr_dx12SetHdrMode` 完全一致：0=AUTO（跟随系统，Windows 的
 * 高级颜色开着才走 scRGB）、1=ON（强制 scRGB，SDR 显示器上会过曝，用于验证管线）、
 * 2=OFF（强制 SDR）。**下标就是下发给 native 的编号**，不要重排。
 *
 * <p>生效时机：交换链格式与色彩空间是建链参数，所以改动在下一次创建交换链时才生效
 * （正常玩法里等于重启客户端）。tooltip 里写明了这一点，避免玩家以为没反应。
 */
object D3D12HdrPresentation {

    /** `options.txt` 里的键。 */
    const val OPTIONS_KEY: String = "dxrMcHdr"

    /** 取值名，下标即下发给 native 的模式编号（与 `HdrMode` 枚举的 ordinal 一致）。 */
    private val MODE_NAMES: List<String> = listOf("auto", "on", "off")

    /** 默认跟随系统。 */
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

    /**
     * 是否显示这一项（以及其它所有模组视频设置）。
     *
     * <p>用 [D3D12AdapterInfo.current] 而不是配置里的 `preferredGraphicsBackend`：它只在
     * D3D12 设备真的建起来之后才有值，玩家选了 D3D12 但后来回退到原版后端时也不会误显示。
     */
    @JvmStatic
    fun isAvailable(): Boolean = D3D12AdapterInfo.current != null

    /** 当前模式（下标 0/1/2），也是写回 `options.txt` 的值。 */
    @JvmStatic
    fun mode(): Int = currentMode

    /**
     * P198：当前选项和“建链时用掉的模式”不一致 —— 需要重启客户端才生效。
     *
     * <p>判定基准取 native 记的 applied mode，而不是我们自己记的状态：只有建链那一刻是权威，
     * 中途 `D3D12SetHdrMode` 推过去的值并不会换掉正在用的交换链格式。反着也成立 —— 玩家改完又
     * 改回原值，提示会自己消失。`-1`（还没建过链）时一律 false。
     */
    @JvmStatic
    fun isRestartPending(): Boolean {
        if (!isAvailable()) {
            return false
        }
        val applied = MCDR_ABI.D3D12HdrAppliedMode()
        return applied >= 0 && applied != currentMode
    }

    /** 最后一次真正推给 native 的模式；`save` 以它为准。 */
    private var currentMode: Int = DEFAULT_MODE

    /** 把模式推给 native；下一次创建交换链时生效。 */
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
        // `Options.load` 跑在 Minecraft 构造函数里，`OptionInstance.set` 那时不触发回调，
        // 所以这里补一次 push（与 Reflex / DLSS 同一处理）。native 侧只记状态，
        // 真正建交换链要等设备创建，那时会读这个值。
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
