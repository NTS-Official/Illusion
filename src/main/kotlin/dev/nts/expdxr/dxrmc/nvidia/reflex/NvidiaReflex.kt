package dev.nts.expdxr.dxrmc.nvidia.reflex

import com.mojang.serialization.Codec
import dev.nts.expdxr.Illusion
import dev.nts.expdxr.dxrmc.framegen.FrameGeneration
import dev.nts.expdxr.d3d12.extern.MCDR_ABI
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Optional
import net.minecraft.client.OptionInstance
import net.minecraft.client.gui.components.CycleButton
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.network.chat.Component

/**
 * Reflex（Streamline `sl.reflex`）：`off` / `on` / `boost`，下标即下发给 native 的模式值，不得重排。
 * 关闭时仍每帧发 sleep 与 PCL 标记（mode = `eOff`），不发会让 PCL 统计断链。
 * 模式与持久化见 docs/framegen/README.md §3。
 */
object NvidiaReflex {

    /** `options.txt` 里的键（不改名）。 */
    const val OPTIONS_KEY: String = "dxrMcReflex"

    /** 下标即 native 模式值，也是 options.txt 字面量，不得重排。 */
    private val MODE_NAMES: List<String> = listOf("off", "on", "boost")

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

    /** 当前模式（0=off 1=on 2=boost），[save] 以它为准。 */
    @JvmStatic
    fun mode(): Int = currentMode

    /** native 三个入口都解析到才算可用。 */
    @JvmStatic
    fun isAvailable(): Boolean = MCDR_ABI.D3D12ReflexAvailable() == 1

    private var currentMode: Int = DEFAULT_MODE

    private var modeBeforeFrameGeneration: Int = 0
    private var forcedByFrameGeneration: Boolean = false

    private fun push(mode: Int) {
        // 帧生成开着时 Reflex 必须为 on：模式 0 在这里被提升成 1。
        val effective = if (mode == 0 && FrameGeneration.isEnabled()) 1 else mode
        currentMode = effective
        MCDR_ABI.D3D12SetReflexMode(effective)
    }

    /** 帧生成强制开启入口：必须同时写选项与 native —— 只写选项在 `Options.load` 期间不触发回调。 */
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

    /** 帧生成关掉时还原被强制前的选择，只还原强制过的那次。 */
    @JvmStatic
    fun releaseFrameGenerationForce() {
        if (!forcedByFrameGeneration) {
            return
        }
        forcedByFrameGeneration = false
        OPTION.set(modeBeforeFrameGeneration)
        push(modeBeforeFrameGeneration)
    }

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
        // Options.load 跑在 Minecraft 构造期，set 不触发回调，这里补一次 push。
        push(OPTION.get())
        // 帧生成强制开着时也纠正选项值，免得界面显示「关」而 native 是「开」。
        if (OPTION.get() != currentMode) {
            OPTION.set(currentMode)
        }
    }

    /** 写回 `options.txt`：先删同键旧行再追加，避免两行。 */
    @JvmStatic
    fun save(file: File) {
        if (!file.isFile) {
            return
        }
        try {
            val eol = System.lineSeparator()
            val kept = file.readLines(StandardCharsets.UTF_8)
                .filterNot { line -> line.startsWith("$OPTIONS_KEY:") }
            // 用 currentMode 而非 OPTION.get()：vanilla 会把选项重置回默认值，写文件就成了默认值。
            val text = (kept + "${OPTIONS_KEY}:${MODE_NAMES[currentMode]}").joinToString(eol) + eol
            file.writeText(text, StandardCharsets.UTF_8)
        } catch (t: Throwable) {
            Illusion.LOGGER.warn("could not write $OPTIONS_KEY to $file", t)
        }
    }
}