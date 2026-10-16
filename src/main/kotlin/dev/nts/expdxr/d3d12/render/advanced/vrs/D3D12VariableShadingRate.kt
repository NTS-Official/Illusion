package dev.nts.expdxr.d3d12.render.advanced.vrs

import com.mojang.serialization.Codec
import dev.nts.expdxr.Illusion
import dev.nts.expdxr.options.D3D12VRSEnumSettings
import dev.nts.expdxr.d3d12.extern.MCDR_ABI
import dev.nts.expdxr.d3d12.render.adapter.D3D12AdapterInfo
import java.io.File
import java.nio.charset.StandardCharsets
import net.minecraft.client.OptionInstance
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.network.chat.Component

/**
 * P97：可变速率着色（VRS）的着色粒度，作为一项**普通视频设置**。
 *
 * 迁移前它是 NeoForge 模组配置的 `[render] shadingRate`（`IllusionConfig.SHADING_RATE`），与
 * `[render] renderScale` 一起摆在模组自己的配置界面里。用户 m10294 要求把这一组搬进原版视频设置界面
 * 底部新增的第四组"Illusion 设置"（见 [dev.nts.expdxr.mixin.options.VideoSettingsScreenMixin]），
 * 于是它和抗锯齿走同一条管道：选项挂在视频设置界面上，值存在 vanilla 自己的 `options.txt`
 * （键 [OPTIONS_KEY]，由 [dev.nts.expdxr.mixin.options.OptionsRenderOptionsMixin] 在
 * `Options.load`/`save` 尾部接管），改动即时推给 native（[push]）。
 *
 * 档位本身仍由 [D3D12VRSEnumSettings] 定义（取值就是 D3D12 的 `D3D12_SHADING_RATE` 原始值，
 * `OFF` = -1 一个 `RSSetShadingRate` 都不发），lang 键是
 * `options.illusion.render.shadingRate.<NAME>`（见 [D3D12VRSEnumSettings.getTranslatedName]；
 * 带 `configuration` 前缀的 `dxr_mc.configuration.render.shadingRate.<NAME>` 是它还在模组配置里的旧名，
 * 已经废弃 —— 2026-10-16 核对时 en_us.json 里那 8 条旧键就是死的，没有任何代码读它们）。
 *
 * 能力判据全在 native 侧**且分两拍**（选项常在设备创建之前读到，那一刻问不到 tier 与
 * `AdditionalShadingRatesSupported`）：设备不存在就先收下，等第一条图形命令列表上再判并 WARN。
 * 这里只把状态码翻译成人话，不重复判一遍，免得两边的能力表漂移。
 */
object D3D12VariableShadingRate {
    /** 循环按钮里的档位顺序：与枚举声明顺序一致（OFF 在最前）。 */
    @JvmField
    val MODES: List<D3D12VRSEnumSettings> = D3D12VRSEnumSettings.entries.toList()

    /** `options.txt` 里的键名；与 `dxrMcAaMode`/`dxrMcRenderScale` 同一套管道，写的是枚举名。 */
    const val OPTIONS_KEY: String = "dxrMcShadingRate"

    /**
     * 循环按钮的选项。标题由 CycleButton 自己加（`CommonComponents.optionNameValue`），所以
     * `toString` 只返回取值名 —— 与 [dev.nts.expdxr.d3d12.render.advanced.D3D12AntiAliasing] 同理，
     * 带上标题会渲染两遍。
     */
    private val OPTION: OptionInstance<D3D12VRSEnumSettings> = OptionInstance(
        "options.shadingRate",
        { Tooltip.create(Component.translatable("options.shadingRate.tooltip")) },
        { _, value -> value.getTranslatedName() },
        OptionInstance.Enum<D3D12VRSEnumSettings>(MODES, modeCodec()),
        D3D12VRSEnumSettings.OFF,
        { value -> push(value) },
    )

    /** 存进 `options.txt` 的文本 ↔ 档位。选项不是 `Options` 的字段，codec 只用来把名字对上号。 */
    private fun modeCodec(): Codec<D3D12VRSEnumSettings> =
        Codec.STRING.xmap({ name -> modeFromName(name) }, { mode -> mode.name })

    /** 名字 → 档位；认不出来就退回 [D3D12VRSEnumSettings.OFF]（`options.txt` 可能被手改过）。 */
    private fun modeFromName(name: String): D3D12VRSEnumSettings =
        MODES.firstOrNull { it.name == name } ?: D3D12VRSEnumSettings.OFF

    @JvmStatic
    fun option(): OptionInstance<D3D12VRSEnumSettings> = OPTION

    /** 当前档位；交给 native 的是 [D3D12VRSEnumSettings.nativeRate]。 */
    @JvmStatic
    fun current(): D3D12VRSEnumSettings = OPTION.get()

    /** True while the game really renders through DXRMC's D3D12 backend（非 D3D12 时按钮置灰）。 */
    @JvmStatic
    fun isAvailable(): Boolean = D3D12AdapterInfo.current != null

    /**
     * 档位 → native。日志文案与返回码分支逐字搬自迁移前的 `Illusion.applyShadingRateConfig()`：
     * 这一项**不需要重启**，native 只把档位记进设备上下文，并在它启动的每条图形命令列表 `Reset`
     * 之后重放（`Reset` 清列表状态，所以不能在建列表时设一次），⇒ 改完下一次命令列表即生效。
     */
    private fun push(rate: D3D12VRSEnumSettings) {
        when (val status = MCDR_ABI.D3D12SetShadingRate(rate.nativeRate)) {
            1 -> if (rate == D3D12VRSEnumSettings.OFF) {
                Illusion.LOGGER.info("[${Illusion.MODID}] VRS shading rate control off; the device keeps its default 1X1.")
            } else {
                Illusion.LOGGER.info("[${Illusion.MODID}] VRS shading rate = ${rate.name} (0x${rate.nativeRate.toString(16)}).")
            }

            0 -> Illusion.LOGGER.warn(
                "[${Illusion.MODID}] VRS shading rate ${rate.name} refused by the native layer: this device " +
                    "cannot honour it (see the [MCDR] WARN for the reason); staying at 1X1.",
            )
            Int.MIN_VALUE -> Illusion.LOGGER.info(
                "[${Illusion.MODID}] VRS shading rate unavailable: the loaded MCDR.dll has no mcdrSetShadingRate.",
            )
            else -> Illusion.LOGGER.error(
                "[${Illusion.MODID}] VRS shading rate ${rate.name} rejected by the native layer (status $status); " +
                    "see the [MCDR] ERROR line for what this device accepts.",
            )
        }
    }

    /**
     * Restores the rate from vanilla's `options.txt`（行格式 `key:value`，值是枚举名）。
     *
     * 与 [dev.nts.expdxr.d3d12.render.advanced.D3D12AntiAliasing.load] 同理：`Options.load` 时游戏还没
     * `isRunning()`，`OptionInstance.set` 不会触发回调，所以末尾要自己推一次 native —— 这时设备通常还
     * 没建，走的正是 native 那条"先收下、第一条命令列表再判"的路径。
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
                val name = line.substring(OPTIONS_KEY.length + 1).trim().trim('"')
                OPTION.set(modeFromName(name))
                break
            }
        } catch (t: Throwable) {
            Illusion.LOGGER.warn("[${Illusion.MODID}] could not read $OPTIONS_KEY from $file", t)
            return
        }
        // 文件里没有这一行时（首次运行 / 用户删过 / 刚从模组配置迁移过来）也要推一次，
        // 让 native 与界面取值一致。
        push(OPTION.get())
    }

    /**
     * Writes the rate back into vanilla's `options.txt`（vanilla 每次保存都整份重写，所以要在
     * `Options.save` 尾部补一行；先丢掉自己的旧行，重复保存不会堆叠）。
     */
    @JvmStatic
    fun save(file: File) {
        if (!file.isFile) {
            return
        }
        try {
            val eol = System.lineSeparator()
            val kept = file.readLines(StandardCharsets.UTF_8).filterNot { it.startsWith("$OPTIONS_KEY:") }
            val text = (kept + ("$OPTIONS_KEY:${OPTION.get().name}")).joinToString(eol) + eol
            file.writeText(text, StandardCharsets.UTF_8)
        } catch (t: Throwable) {
            Illusion.LOGGER.warn("[${Illusion.MODID}] could not write $OPTIONS_KEY to $file", t)
        }
    }
}