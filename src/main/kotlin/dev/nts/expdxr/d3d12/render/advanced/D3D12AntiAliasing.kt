package dev.nts.expdxr.d3d12.render.advanced

import com.mojang.serialization.Codec
import dev.nts.expdxr.Illusion
import dev.nts.expdxr.d3d12.extern.MCDR_ABI
import dev.nts.expdxr.d3d12.render.adapter.D3D12AdapterInfo
import java.io.File
import java.nio.charset.StandardCharsets
import net.minecraft.client.OptionInstance
import net.minecraft.client.Minecraft
import net.minecraft.client.Options
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.network.chat.Component

/**
 * The Direct3D 12 backend's antialiasing mode, exposed as an ordinary vanilla
 * video setting instead of a mod-config entry.
 *
 * The Direct3D 12 renderer reads the selection through [current] and hands it to the native backend
 * through [MCDR_ABI.D3D12SetAntiAliasing] ([push]). SMAA is implemented natively (three
 * full-screen passes right before the final blit); TAA and DLAA are still placeholders and are
 * downgraded to OFF by the native side. No restart is needed — the mode takes effect on the next
 * blit.
 */
object D3D12AntiAliasing {
    // Antialiasing mode. Note that TAA and DLAA are not yet implemented.
    // 公开（不是 private）：DLSS 那边（dev.nts.expdxr.dxrmc.dlss）在"旧 DLL 兼容桥"里要把抗锯齿
    // 还原成 SMAA/OFF，而且这几个值本就是这套选项的公开取值域。
    const val OFF = 0
    const val SMAA = 1
    const val TAA = 2
    const val DLAA = 3

    /**
     * Cycle order of the video-settings button.
     *
     * <p>P108b（用户 m10920）：DLSS **不再占第 4 档** —— 它是自己那一项设置（`options.dlss` /
     * `dxrMcDlss`，见 `dev.nts.expdxr.dxrmc.dlss.D3D12Dlss`），这一栏回到 0..3。两者互相独立：
     * 都开时 native 先对低分辨率世界层做 SMAA，再让 DLSS 升采样。
     */
    @JvmField
    val MODES: List<Int> = listOf(OFF, SMAA, TAA, DLAA)

    /** Key used inside `options.txt`; must stay stable, it is user-visible state. */
    const val OPTIONS_KEY: String = "dxrMcAaMode"

    private val OPTION: OptionInstance<Int> = OptionInstance(
        "options.antiAliasing",
        {
            Tooltip.create(Component.translatable("options.antiAliasing.tooltip"))
        },
        // Only the value is returned here: the options is a cycle button, and
        // vanilla's CycleButton already prefixes the caption itself
        // (CommonComponents.optionNameValue in CycleButton.Builder.create),
        // so including it here renders the label twice.
        { _, value ->
            Component.translatable(modeKey(value))
        },
        OptionInstance.Enum<Int>(MODES, Codec.INT),
        OFF,
        // P104: every change (a click on the video-settings button, or the value restored from
        // options.txt) is pushed straight to the native renderer. The native side only records
        // the mode and applies it on the next blit, so no restart is involved.
        { value -> push(value); markPresetCustom() }
    )

    @JvmStatic
    fun option(): OptionInstance<Int> = OPTION

    /** The selected mode, for the renderer. */
    @JvmStatic
    fun current(): Int = OPTION.get()

    /** True while the game really renders through DXRMC's D3D12 backend. */
    @JvmStatic
    fun isAvailable(): Boolean = D3D12AdapterInfo.current != null

    private fun modeKey(mode: Int): String = "options.antiAliasing.mode.$mode"

    /** 预设正在写这个选项（或正在从 options.txt 恢复）：此时不要把画质预设打成"自定义"。 */
    private var suppressPresetCustom = false

    // ------------------------------------------------------------------
    // DLSS 开启期间的锁定
    // ------------------------------------------------------------------

    /**
     * DLSS 是否正占着这个选项（由 [dev.nts.expdxr.dxrmc.dlss.DeepLearningSuperSampling] 推过来）。
     *
     * 这里**故意不 import** DLSS：依赖只能单向。DLSS 打开时把 AA 锁成 [OFF]，本类据此
     * ①拒掉预设/回推对取值的改写、②让界面把按钮置灰。
     *
     * **为什么不写 KDoc 链接**：`[DeepLearningSuperSampling]` 算不出 KDoc 链接会直接**编译失败**
     * （K2 的 `Unresolved reference in KDoc` 是 error），所以这些注释一律用全限定名。
     */
    @Volatile
    private var dlssLock = false

    /** 加锁的是不是"本类自己"：是才说明有快照可还原。 */
    private var dlssLockApplied = false

    /** 加锁前玩家自己选的模式，解冻时还回去；[OFF] 表示当初没必要存快照。 */
    private var dlssModeSnapshot = OFF

    /**
     * DLSS 开关变化时由 DLSS 侧调用：开 => 本选项锁成 [OFF]，关 => 还原玩家原先的选择。
     *
     * 幂等，且每次开关切换只打一行日志（不会每帧刷屏）。
     */
    @JvmStatic
    fun setDlssLock(locked: Boolean) {
        dlssLock = locked
        if (locked) {
            if (!dlssLockApplied) {
                // 玩家本来就停在 OFF 上时不留快照：解冻时"还回 OFF"与"没动过"是同一结果。
                dlssModeSnapshot = if (OPTION.get() == OFF) OFF else OPTION.get()
                if (OPTION.get() != OFF) {
                    setModeQuietly(OFF)
                }
                dlssLockApplied = true
                Illusion.LOGGER.info("[dxrmc] DLSS 已开启：抗锯齿锁为 OFF，本项在视频设置里不可调整")
            }
            return
        }
        if (dlssLockApplied) {
            dlssLockApplied = false
            val restored = dlssModeSnapshot
            dlssModeSnapshot = OFF
            if (restored != OFF && OPTION.get() != restored) {
                setModeQuietly(restored)
            }
            Illusion.LOGGER.info("[dxrmc] DLSS 已关闭：抗锯齿重新可调（还原为 {}）", modeName(restored))
        }
    }

    /** 界面据此把按钮置灰。 */
    @JvmStatic
    fun isLockedByDlss(): Boolean = dlssLock

    /** 写取值但不惊动画质预设、也不回推 native（[push] 在写完后再显式调一次）。 */
    private fun setModeQuietly(mode: Int) {
        suppressPresetCustom = true
        try {
            OPTION.set(mode)
        } finally {
            suppressPresetCustom = false
        }
    }

    /**
     * 画质预设 -> 抗锯齿：流畅(fast)=关、高品质(fancy)=SMAA、极佳(fabulous)=TAA，自定义不动。
     * 由 [dev.nts.expdxr.mixin.options.OptionsGraphicsPresetMixin] 在 GraphicsPreset.apply 之后调用。
     *
     * DLSS 锁定期内整个跳过：切画质预设不能把被锁住的选项改回去。
     */
    @JvmStatic
    fun applyFromPreset(serializedName: String) {
        if (dlssLock) {
            return
        }
        val mode = when (serializedName) {
            "fast" -> OFF
            "fancy" -> SMAA
            "fabulous" -> TAA
            else -> return
        }
        suppressPresetCustom = true
        try {
            OPTION.set(mode)
        } finally {
            suppressPresetCustom = false
        }
    }

    /** 玩家自己改了抗锯齿 -> 画质预设变"自定义"，与 vanilla 选项同一行为。 */
    private fun markPresetCustom() {
        if (suppressPresetCustom) {
            return
        }
        runCatching {
            val options = Minecraft.getInstance().options
            options.setGraphicsPresetToCustom()   // 可见性由 META-INF/accesstransformer.cfg 放宽
        }
    }

    /** Short name used in the log lines (the lang keys are for the UI). */
    private fun modeName(mode: Int): String = when (mode) {
        OFF -> "OFF"
        SMAA -> "SMAA"
        TAA -> "TAA"
        DLAA -> "DLAA"
        else -> "UNKNOWN($mode)"
    }

    /**
     * P104: hands the selected mode to the native renderer.
     *
     * The native layer keeps the mode in its device context and re-issues the SMAA passes on every
     * blit, so this needs neither a device nor a restart. A missing export means the loaded
     * `MCDR.dll` predates this feature; that is reported once per call and otherwise ignored.
     */
    private fun push(mode: Int) {
        // DLSS 锁定期内这是**回推 native** 的路径，不是"玩家改了设置"的路径：必须仍然下发，
        // 否则 native 的设备上下文会停在旧模式上（尤其关掉 DLSS、还原玩家选择的这一次）。
        // 玩家能不能改由界面置灰 + [validateLocked] 负责。
        when (val status = MCDR_ABI.D3D12SetAntiAliasing(mode)) {
            1 -> Illusion.LOGGER.info("[dxrmc] anti-aliasing = {}", modeName(mode))
            0 -> Illusion.LOGGER.warn(
                "[dxrmc] anti-aliasing {} is not implemented yet; the renderer falls back to OFF",
                modeName(mode),
            )
            Int.MIN_VALUE -> Illusion.LOGGER.info(
                "[dxrmc] the loaded MCDR.dll has no mcdrSetAntiAliasing; anti-aliasing stays off",
            )
            else -> Illusion.LOGGER.warn("[dxrmc] mcdrSetAntiAliasing({}) returned {}", mode, status)
        }
    }

    /** Re-pushes the stored selection; kept for callers that need to force a sync. */
    @JvmStatic
    fun applyCurrent() {
        push(OPTION.get())
    }

    /**
     * Restores the mode from vanilla's `options.txt`. Called at the tail of
     * [Options.load]; the line format is vanilla's own `key:jsonValue`.
     */
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
                val mode = line.substring(OPTIONS_KEY.length + 1).trim().toIntOrNull()
                if (mode != null && MODES.contains(mode)) {
                    // 从 options.txt 恢复不算"玩家手改"，否则会把刚载入的画质预设立刻打成自定义。
                    // DLSS 锁定期内则整个忽略：这条注入与 DLSS 自己那条同在 `Options.load` 的 TAIL
                    // 上，先后顺序不由我们决定，照单全收就会把"DLSS 开着 => 抗锯齿 OFF"的约定破坏掉。
                    if (dlssLock) {
                        Illusion.LOGGER.info(
                            "[dxrmc] DLSS is on; ignoring the anti-aliasing mode {} restored from {}",
                            modeName(mode), OPTIONS_KEY,
                        )
                    } else {
                        suppressPresetCustom = true
                        try {
                            OPTION.set(mode)
                        } finally {
                            suppressPresetCustom = false
                        }
                    }
                }
                break
            }
        } catch (t: Throwable) {
            Illusion.LOGGER.warn("[dx12] could not read {} from {}", OPTIONS_KEY, file, t)
            return
        }
        // 文件里没有这一行时（首次运行 / 用户删过）也要推一次，让 native 与界面取值一致。
        push(OPTION.get())
    }

    /**
     * Writes the mode back into vanilla's `options.txt`.
     *
     * Vanilla rewrites that file from scratch on every save, so the line has to
     * be re-added afterward; a stale copy is dropped first so repeated saves
     * cannot pile up duplicates.
     */
    @JvmStatic
    fun save(file: File) {
        if (!file.isFile) {
            return
        }
        try {
            val eol = System.lineSeparator()
            val kept = file.readLines(StandardCharsets.UTF_8)
                .filterNot { it.startsWith("$OPTIONS_KEY:") }
            val text = (kept + ("${OPTIONS_KEY}:${OPTION.get()}")).joinToString(eol) + eol
            file.writeText(text, StandardCharsets.UTF_8)
        } catch (t: Throwable) {
            Illusion.LOGGER.warn("[dx12] could not write {} to {}", OPTIONS_KEY, file, t)
        }
    }
}