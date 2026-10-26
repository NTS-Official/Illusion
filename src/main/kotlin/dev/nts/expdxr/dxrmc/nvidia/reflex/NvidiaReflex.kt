package dev.nts.expdxr.dxrmc.nvidia.reflex

import com.mojang.serialization.Codec
import dev.nts.expdxr.Illusion
import dev.nts.expdxr.dxrmc.sr.dlss.DLSSFrameGeneration
import dev.nts.expdxr.d3d12.extern.MCDR_ABI
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Optional
import net.minecraft.client.OptionInstance
import net.minecraft.client.gui.components.CycleButton
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.network.chat.Component

/**
 * P122：NVIDIA Reflex —— "Illusion 设置" 栏里的低延迟选项。
 *
 * <p>三条取值对应 Streamline 的 `sl::ReflexMode`：`off` / `on`（`eLowLatency`）/ `boost`
 * （`eLowLatencyWithBoost`）；**下标就是下发给 native 的编号**，不要重排。
 *
 * <p>Reflex 来自 Streamline（`sl.reflex` 插件），所以只有运行库在场、插件加载成功时按钮才可用
 * （[isAvailable] 问 native 的 `mcdr_dx12ReflexAvailable`）。关闭 Reflex 只是把 `mode` 置成
 * `eOff`，`slReflexSleep` 与 PCL 标记**仍然每帧照发**（`ProgrammingGuideReflex.md` §4 NOTE：
 * 即使模式为 Off 也必须调用，否则 PCL 统计断链）。
 *
 * <p>持久化走 `options.txt` 的 `dxrMcReflex:<off|on|boost>`，与 DLSS 选项同一套 [load]/[save]
 * 管道（挂在 `OptionsRenderOptionsMixin` 的 TAIL 上）。
 */
object NvidiaReflex {

    /** `options.txt` 里的键。 */
    const val OPTIONS_KEY: String = "dxrMcReflex"

    /** 取值名，下标即下发给 native 的模式编号。 */
    private val MODE_NAMES: List<String> = listOf("off", "on", "boost")

    /** 默认低延迟：与 P113 接入 Reflex 时的行为一致。 */
    private const val DEFAULT_MODE: Int = 1

    private object ModeValues : OptionInstance.CycleableValueSet<Int> {
        override fun validateValue(value: Int): Optional<Int> =
            if (value in MODE_NAMES.indices) Optional.of(value) else Optional.empty()

        override fun valueListSupplier(): CycleButton.ValueListSupplier<Int> =
            CycleButton.ValueListSupplier.create(MODE_NAMES.indices.toList())

        override fun codec(): Codec<Int> = Codec.intRange(0, MODE_NAMES.size - 1)
    }

    private val OPTION: OptionInstance<Int> = OptionInstance(
        "options.reflex",
        { Tooltip.create(Component.translatable("options.reflex.tooltip")) },
        { _, value -> Component.translatable("options.reflex.${MODE_NAMES[value]}") },
        ModeValues,
        DEFAULT_MODE,
        { value -> push(value) },
    )

    @JvmStatic
    fun option(): OptionInstance<Int> = OPTION

    /** 界面里的当前模式（0=off 1=on 2=boost），也是写回 `options.txt` 的值。 */
    @JvmStatic
    fun mode(): Int = currentMode

    /** Reflex 是否可用（native 三个入口都解析到才算）。 */
    @JvmStatic
    fun isAvailable(): Boolean = MCDR_ABI.D3D12ReflexAvailable() == 1

    /** 最后一次真正推给 native 的模式；`save` 以它为准（见 [save] 的说明）。 */
    private var currentMode: Int = DEFAULT_MODE

    /** 帧生成强制开启前玩家自己的模式（关掉帧生成时还原）。 */
    private var modeBeforeFrameGeneration: Int = 0
    private var forcedByFrameGeneration: Boolean = false

    /** 把模式推给 native；下一帧 Present 前生效。 */
    private fun push(mode: Int) {
        // P213 联动：帧生成开着时 Reflex 必须为 On。用户（或旧配置）想关掉也按 On 处理，
        // 这样“设置里显示关 / 实际强制开”这种错位不会出现；关掉帧生成后即可自由切换。
        val effective = if (mode == 0 && DLSSFrameGeneration.isEnabled()) 1 else mode
        currentMode = effective
        MCDR_ABI.D3D12SetReflexMode(effective)
    }

    /**
     * 联动入口：帧生成打开时由 [DLSSFrameGeneration] 调用，
     * 把 Reflex 抬到 On。
     *
     * 不能只 `OPTION.set(1)`：`Options.load` 期间 `OptionInstance.set` 不触发回调，
     * native 那边的 user 模式会停在旧值，所以这里把选项与 native 一起写。
     */
    @JvmStatic
    fun forceOn() {
        if (currentMode != 0) {
            return
        }
        modeBeforeFrameGeneration = currentMode
        forcedByFrameGeneration = true
        OPTION.set(1)
        push(1)
    }

    /** 帧生成关掉时把 Reflex 还原成玩家原本的选择（只还原被我们强制过的那次）。 */
    @JvmStatic
    fun releaseFrameGenerationForce() {
        if (!forcedByFrameGeneration) {
            return
        }
        forcedByFrameGeneration = false
        OPTION.set(modeBeforeFrameGeneration)
        push(modeBeforeFrameGeneration)
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
            Illusion.LOGGER.warn("[dxrmc] could not read $OPTIONS_KEY from $file", t)
        }
        // `Options.load` 跑在 Minecraft 构造函数里，`OptionInstance.set` 那时不触发回调，
        // 所以这里补一次 push（与 DLSS 选项同一处理）。
        push(OPTION.get())
        // P213 联动：帧生成开着时把 UI 值也纠正过来——只改 native 不改选项，
        // 设置界面会显示“Reflex 关着”却又被强制开启。
        if (OPTION.get() != currentMode) {
            OPTION.set(currentMode)
        }
    }

    /** 把模式写回 `options.txt`（先删同键旧行再追加，与 DLSS 的写法一致）。 */
    @JvmStatic
    fun save(file: File) {
        if (!file.isFile) {
            return
        }
        try {
            val eol = System.lineSeparator()
            val kept = file.readLines(StandardCharsets.UTF_8)
                .filterNot { line -> line.startsWith("$OPTIONS_KEY:") }
            // 用 currentMode 而不是 OPTION.get()：vanilla 在某些路径上会把选项重置回初始值，
            // 那时写文件的是"默认值"，玩家选的值就丢了（实测：选 boost 退出后被写成 off）。
            val text = (kept + "${OPTIONS_KEY}:${MODE_NAMES[currentMode]}").joinToString(eol) + eol
            file.writeText(text, StandardCharsets.UTF_8)
        } catch (t: Throwable) {
            Illusion.LOGGER.warn("[dxrmc] could not write $OPTIONS_KEY to $file", t)
        }
    }
}