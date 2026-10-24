package dev.nts.expdxr.dxrmc.nvidia.dlss

import com.mojang.serialization.Codec
import dev.nts.expdxr.d3d12.extern.MCDR_ABI
import dev.nts.expdxr.dxrmc.nvidia.reflex.NvidiaReflex
import dev.nts.expdxr.d3d12.render.adapter.D3D12AdapterInfo
import java.io.File
import java.util.Optional
import net.minecraft.client.gui.components.CycleButton
import net.minecraft.client.OptionInstance
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.network.chat.Component

/**
 * P213：DLSS 帧生成（DLSS-G）的两个玩家选项。
 *
 * <p>和 [D3D12HdrPresentation] 一样挂在 `options.txt` 上（键 `dxrMcFrameGeneration` /
 * `dxrMcFrameGenerationFrames`）。真正干活的是 native：`mcdr_dx12SetDlssgMode(mode, frames)`
 * 写进 `gDlssgMode` / `gDlssgFrames`，渲染线程每帧在 `streamlineEvaluate()` 里下发给 Streamline。
 *
 * <p>模式只有 关/开 两档；“自动”（Dynamic MFG）被搬到了倍率滑条的最低档（0 = 自动），
 * 由 [nativeMode] 映射成 native 的 2。
 */
object DLSSFrameGeneration {

    /** `options.txt` 里的键。 */
    const val OPTIONS_KEY: String = "dxrMcFrameGeneration"

    /** 生成帧数的键。 */
    const val FRAMES_KEY: String = "dxrMcFrameGenerationFrames"

    /** 取值名，下标即下发给 native 的模式编号。 */
    private val MODE_NAMES: List<String> = listOf("off", "on")

    private const val DEFAULT_MODE: Int = 0

    /** 每渲染帧生成多少帧：1..5（上限由 `DLSSGState::numFramesToGenerateMax` 决定，本机是 5）。 */
    /** 倍率滑条的下限是“自动”（Dynamic MFG），不是 1× —— 自动只在开帧生成时有意义。 */
    private const val MIN_FRAMES: Int = 0
    private const val MAX_FRAMES: Int = 5
    private const val DEFAULT_FRAMES: Int = 1

    private object ModeValues : OptionInstance.CycleableValueSet<Int> {
        override fun validateValue(value: Int): Optional<Int> =
            if (value in MODE_NAMES.indices) Optional.of(value) else Optional.empty()

        override fun valueListSupplier(): CycleButton.ValueListSupplier<Int> =
            CycleButton.ValueListSupplier.create(MODE_NAMES.indices.toList())

        override fun codec(): Codec<Int> = Codec.intRange(0, MODE_NAMES.size - 1)
    }

    private object FrameValues : OptionInstance.SliderableValueSet<Int> {
        override fun validateValue(value: Int): Optional<Int> =
            if (value in MIN_FRAMES..MAX_FRAMES) Optional.of(value) else Optional.empty()

        override fun toSliderValue(value: Int): Double =
            (value - MIN_FRAMES).toDouble() / (MAX_FRAMES - MIN_FRAMES).toDouble()

        override fun fromSliderValue(slider: Double): Int =
            (MIN_FRAMES + Math.round(slider * (MAX_FRAMES - MIN_FRAMES)).toInt())
                .coerceIn(MIN_FRAMES, MAX_FRAMES)

        override fun codec(): Codec<Int> = Codec.intRange(MIN_FRAMES, MAX_FRAMES)

        override fun next(value: Int): Optional<Int> = Optional.of(if (value >= MAX_FRAMES) MIN_FRAMES else value + 1)

        override fun previous(value: Int): Optional<Int> = Optional.of(if (value <= MIN_FRAMES) MAX_FRAMES else value - 1)
    }

    private val OPTION: OptionInstance<Int> = OptionInstance(
        "options.frameGeneration",
        { Tooltip.create(Component.translatable("options.frameGeneration.tooltip")) },
        { _, value -> Component.translatable("options.frameGeneration.${MODE_NAMES[value]}") },
        ModeValues,
        DEFAULT_MODE,
        { value -> currentMode = value; push() },
    )

    private val FRAMES_OPTION: OptionInstance<Int> = OptionInstance(
        "options.frameGeneration.frames",
        { Tooltip.create(Component.translatable("options.frameGeneration.frames.tooltip")) },
        { _, value ->
            if (value <= MIN_FRAMES) {
                Component.translatable("options.frameGeneration.auto")
            } else {
                Component.literal("${value + 1}×")
            }
        },
        FrameValues,
        DEFAULT_FRAMES,
        { value -> currentFrames = value; push() },
    )

    /** 最后一次真正推给 native 的模式。 */
    private var currentMode: Int = DEFAULT_MODE

    /** 最后一次真正推给 native 的生成帧数。 */
    private var currentFrames: Int = DEFAULT_FRAMES

    @JvmStatic
    fun option(): OptionInstance<Int> = OPTION

    @JvmStatic
    fun framesOption(): OptionInstance<Int> = FRAMES_OPTION

    /**
     * 是否在视频设置里显示这两行。
     *
     * <p>帧生成只在 DLSS 真正跑起来时才有意义（Streamline 要求 Reflex + DLSS 都在），所以
     * 这里比 HDR 多一个 DLSS 条件：后端是 D3D12 且 DLSS 已启用。
     */
    @JvmStatic
    fun isAvailable(): Boolean = D3D12AdapterInfo.current != null

    /** 当前模式（0 关 / 1 开）；“自动”由倍率档位 0 表达，见 [nativeMode]。 */
    @JvmStatic
    fun mode(): Int = currentMode

    /** 当前倍率档位：0 = 自动，1..5 = 2×..6×。 */
    @JvmStatic
    fun frames(): Int = currentFrames

    /** 帧生成是否开启（固定倍率与自动都算）。 */
    @JvmStatic
    fun isEnabled(): Boolean = currentMode != 0

    /** 下发给 native 的模式：0 关 / 1 固定倍率 / 2 动态（自动）。 */
    private fun nativeMode(): Int = when {
        currentMode == 0 -> 0
        currentFrames <= MIN_FRAMES -> 2
        else -> 1
    }

    /** 下发给 native 的额外帧数；自动档固定 1（具体张数由驱动决定）。 */
    private fun nativeFrames(): Int = if (currentFrames <= MIN_FRAMES) 1 else currentFrames

    private fun push() {
        // P213 联动：帧生成依赖 Reflex。用户把帧生成打开时必须让 Reflex 跟着开，
        // 否则 SDK 会在 shouldInterpolate 里静默禁用插帧（native 侧也会把 effective
        // mode 抬到 On，这里同步 UI/配置状态，免得设置里显示“Reflex 关着”却实际被强制开启）。
        if (isEnabled() && NvidiaReflex.mode() == 0) {
            NvidiaReflex.forceOn()
        }
        // 帧生成的输入就是 DLSS 的输出：开着 FG 却关着 DLSS 没有意义（native 也会拒绝）。
        // 顺手打开 DLSS，配合“FG 开启时 DLSS 只读”，避免出现自相矛盾的状态。
        if (isEnabled() && !DeepLearningSuperSampling.isEnabled()) {
            DeepLearningSuperSampling.option().set(true)
        }
        MCDR_ABI.D3D12SetDlssgMode(nativeMode(), nativeFrames())
    }

    /** 从 `options.txt` 读回两个值（认名字与数字两种写法），并立刻推给 native。 */
    @JvmStatic
    fun load(file: File) {
        val entries = readOptions(file)
        val rawMode = entries[OPTIONS_KEY]?.trim()?.removeSurrounding("\"")?.lowercase()
        currentMode = when {
            rawMode == null -> DEFAULT_MODE
            rawMode in MODE_NAMES -> MODE_NAMES.indexOf(rawMode)
            else -> rawMode.toIntOrNull()?.coerceIn(0, MODE_NAMES.size - 1) ?: DEFAULT_MODE
        }
        currentFrames = entries[FRAMES_KEY]?.trim()?.toIntOrNull()?.coerceIn(MIN_FRAMES, MAX_FRAMES)
            ?: DEFAULT_FRAMES
        // 旧配置里的 "dynamic" 现在等价于“开 + 倍率最低档（自动）”，读回来时做一次迁移。
        if (rawMode == "dynamic") {
            currentMode = 1
            currentFrames = MIN_FRAMES
        }
        // 绕开 OptionInstance 的回调（`Options.load` 期间回调不触发），直接把值塞回选项对象。
        OPTION.set(currentMode)
        FRAMES_OPTION.set(currentFrames)
        push()
    }

    /** 写回 `options.txt`。 */
    @JvmStatic
    fun save(file: File) {
        writeOption(file, OPTIONS_KEY, currentMode.toString())
        writeOption(file, FRAMES_KEY, currentFrames.toString())
    }

    private fun readOptions(file: File): Map<String, String> {
        if (!file.isFile) {
            return emptyMap()
        }
        return file.readLines()
            .mapNotNull { line ->
                val idx = line.indexOf(':')
                if (idx <= 0) null else line.substring(0, idx).trim() to line.substring(idx + 1)
            }
            .toMap()
    }

    private fun writeOption(file: File, key: String, value: String) {
        if (!file.isFile) {
            return
        }
        val lines = file.readLines().toMutableList()
        val idx = lines.indexOfFirst { it.substringBefore(':').trim() == key }
        if (idx >= 0) {
            lines[idx] = "$key:$value"
        } else {
            lines.add("$key:$value")
        }
        file.writeText(lines.joinToString("\n", postfix = "\n"))
    }
}
