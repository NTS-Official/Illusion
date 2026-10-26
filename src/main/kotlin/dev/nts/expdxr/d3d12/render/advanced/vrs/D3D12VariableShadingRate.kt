package dev.nts.expdxr.d3d12.render.advanced.vrs

import com.mojang.serialization.Codec
import dev.nts.expdxr.Illusion
import dev.nts.expdxr.IllusionConfig
import dev.nts.expdxr.misc.D3D12VRSSettings
import dev.nts.expdxr.d3d12.extern.MCDR_ABI
import dev.nts.expdxr.d3d12.render.adapter.D3D12AdapterInfo
import java.io.File
import java.nio.charset.StandardCharsets
import net.minecraft.client.OptionInstance
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.network.chat.Component
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent
import net.neoforged.neoforge.common.ModConfigSpec
import net.neoforged.neoforge.common.NeoForge

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
 * 档位只有 [D3D12VRSSettings.OFF] / [D3D12VRSSettings.AUTO] 两档（P238：手动档位在 P233 实测后
 * 删除，理由见枚举注释），lang 键是 `options.illusion.render.shadingRate.<NAME>`
 * （见 [D3D12VRSSettings.getTranslatedName]）。
 *
 * 能力判据全在 native 侧**且分两拍**（选项常在设备创建之前读到，那一刻问不到 tier 与
 * `AdditionalShadingRatesSupported`）：设备不存在就先收下，等第一条图形命令列表上再判并 WARN。
 * 这里只把状态码翻译成人话，不重复判一遍，免得两边的能力表漂移。
 */
object D3D12VariableShadingRate {
    /** 循环按钮里的取值顺序：与枚举声明顺序一致（OFF 在最前）。 */
    @JvmField
    val MODES: List<D3D12VRSSettings> = D3D12VRSSettings.entries.toList()

    /** `options.txt` 里的键名；与 `dxrMcAaMode`/`dxrMcRenderScale` 同一套管道，写的是枚举名。 */
    const val OPTIONS_KEY: String = "dxrMcShadingRate"

    /**
     * 循环按钮的选项。标题由 CycleButton 自己加（`CommonComponents.optionNameValue`），所以
     * `toString` 只返回取值名 —— 与 [dev.nts.expdxr.d3d12.render.advanced.D3D12AntiAliasing] 同理，
     * 带上标题会渲染两遍。
     */
    private val OPTION: OptionInstance<D3D12VRSSettings> = OptionInstance(
        "options.shadingRate",
        { Tooltip.create(Component.translatable("options.shadingRate.tooltip")) },
        { _, value -> value.getTranslatedName() },
        OptionInstance.Enum<D3D12VRSSettings>(MODES, modeCodec()),
        D3D12VRSSettings.OFF,
        { value -> push(value) },
    )

    /** 存进 `options.txt` 的文本 ↔ 取值。选项不是 `Options` 的字段，codec 只用来把名字对上号。 */
    private fun modeCodec(): Codec<D3D12VRSSettings> =
        Codec.STRING.xmap({ name -> modeFromName(name) }, { mode -> mode.name })

    /**
     * 名字 → 取值。P238 之前存的是 `RATE_1X1` / `RATE_2X2` 这类手动档位，现在一律当
     * [D3D12VRSSettings.AUTO]：档位删掉之后，旧 `options.txt` 里留着它们的人应当拿到"自动"，
     * 而不是静默退回 OFF（那等于"更新完 VRS 自己关了"）。
     */
    private fun modeFromName(name: String): D3D12VRSSettings {
        MODES.firstOrNull { it.name == name }?.let { return it }
        return if (name.startsWith("RATE_")) D3D12VRSSettings.AUTO else D3D12VRSSettings.OFF
    }

    @JvmStatic
    fun option(): OptionInstance<D3D12VRSSettings> = OPTION

    /** 当前取值。 */
    @JvmStatic
    fun current(): D3D12VRSSettings = OPTION.get()

    /** True when AUTO is selected **and** native did not refuse the request. */
    @JvmStatic
    fun isEnabled(): Boolean = current() == D3D12VRSSettings.AUTO && !nativeRefused

    /** True while the game really renders through DXRMC's D3D12 backend（非 D3D12 时按钮置灰）。 */
    @JvmStatic
    fun isAvailable(): Boolean = D3D12AdapterInfo.current != null

    /**
     * 取值 → native。日志文案与返回码分支搬自迁移前的 `Illusion.applyShadingRateConfig()`。
     *
     * <p>P231 起语义变了：native 只把速率记进设备上下文（= 地形粗粒度的**上限**），不再在每条
     * 图形命令列表 `Reset` 之后全局重放 —— 那种重放会把天空、粒子、GUI 一起变粗（P97 的行为）。
     * 真正下发由 [scope] 逐批次完成，所以改档位同样是"下一次地形批次即生效"，不需要重启。
     *
     * <p>P238：`AUTO` 推的是 [MAX_TERRAIN_RATE]（2X2）而不是玩家选的档位 —— 玩家已经没有"选哪档"
     * 的余地，这里推上限只是让 native 顺手做一次**能力预检**（tier 0 / 不支持该速率会返回 0）。
     * 预检失败就记进 [nativeRefused]，[isEnabled] 与 [terrainRateFor] 一起退回"不发速率"。
     */
    private fun push(settings: D3D12VRSSettings) {
        val rate = if (settings == D3D12VRSSettings.AUTO) MAX_TERRAIN_RATE else -1
        when (val status = MCDR_ABI.D3D12SetShadingRate(rate)) {
            1 -> {
                nativeRefused = false
                if (settings == D3D12VRSSettings.OFF) {
                    Illusion.LOGGER.info("[${Illusion.MODID}] VRS off; the device keeps its default 1X1.")
                } else {
                    Illusion.LOGGER.info(
                        "[${Illusion.MODID}] VRS auto: terrain ceiling ${rateName(MAX_TERRAIN_RATE)} (0x${MAX_TERRAIN_RATE.toString(16)}).",
                    )
                }
            }

            0 -> {
                nativeRefused = true
                Illusion.LOGGER.warn(
                    "[${Illusion.MODID}] VRS auto refused by the native layer: this device cannot honour " +
                        "1X1..${rateName(MAX_TERRAIN_RATE)} (see the [MCDR] WARN for the reason); staying at 1X1.",
                )
            }
            Int.MIN_VALUE -> {
                nativeRefused = true
                Illusion.LOGGER.info(
                    "[${Illusion.MODID}] VRS unavailable: the loaded MCDR.dll has no mcdrSetShadingRate.",
                )
            }
            else -> {
                nativeRefused = true
                Illusion.LOGGER.error(
                    "[${Illusion.MODID}] VRS rejected by the native layer (status $status); " +
                        "see the [MCDR] ERROR line for what this device accepts.",
                )
            }
        }
    }

    /** 速率值 → `RATE_2X2` 这样的名字，只用于日志。 */
    private fun rateName(rate: Int): String = when (rate) {
        0x0 -> "1X1"
        0x1 -> "1X2"
        0x4 -> "2X1"
        0x5 -> "2X2"
        0x6 -> "2X4"
        0x9 -> "4X2"
        0xA -> "4X4"
        else -> "rate 0x${rate.toString(16)}"
    }

    /** P232：地形最多粗到 2X2（枚举数值按面积单调递增，所以"更细"就是数值更小）。 */
    const val MAX_TERRAIN_RATE: Int = 0x5

    /** 已下发的粗粒度批次 / 已恢复 1X1 的次数（诊断用，P237 会把它送进 OSD）。 */
    @JvmStatic
    var scopedBatches: Long = 0L
        private set

    @JvmStatic
    var scopedRestores: Long = 0L
        private set

    /** 真正绑上过着色率图像的批次（诊断用；恒 0 = SRI 没生效，看 `dxrmc-vrs-sri` 的日志）。 */
    @JvmStatic
    var scopedImages: Long = 0L
        private set

    /** 上一批已下发的组合器（`d3d12.h` 的取值），避免每批次都白调一次 native。 */
    private var lastCombiner0: Int = COMBINER_MIN
    private var lastCombiner1: Int = COMBINER_PASSTHROUGH

    /**
     * 把三个诊断计数清零 —— **只在离开存档时**调用（见 [register]）。
     *
     * <p>它们是从开世界一路累加的 `Long`（不分配内存，也永远不会溢出），但 F3 那行本来就该
     * 描述"这一局/这张存档"，跨存档带着上一局的总数没有意义。只写字段，不碰 GPU 资源：
     * `ClientPlayerNetworkEvent.LoggingOut` 不一定在渲染线程上，动资源会是 D.10 那类悬空指针。
     */
    @JvmStatic
    fun resetCounters() {
        scopedBatches = 0L
        scopedRestores = 0L
        scopedImages = 0L
        D3D12ShadingRateImage.resetCounters()
    }

    /**
     * 挂上"退出存档就清零"与"世界切换强制 1X1"的监听（NeoForge 游戏总线）。
     *
     * <p>`LoggingOut` / `LoggingIn` 是玩家离开/进入世界（单人存档、服务器、被踢）都会走的那一处，
     * 也是 TAA 历史、实体缓存这类"属于一局"的状态的天然边界。两个回调都不在渲染线程上，
     * 所以只置标志，由下一帧的 `prepareFrame` 消费（§8.4 第 3 条）。
     */
    fun register() {
        NeoForge.EVENT_BUS.addListener<ClientPlayerNetworkEvent.LoggingOut> {
            D3D12ShadingRateImage.requestForceFine("left the world")
            resetCounters()
        }
        NeoForge.EVENT_BUS.addListener<ClientPlayerNetworkEvent.LoggingIn> {
            D3D12ShadingRateImage.requestForceFine("joined a world")
        }
    }

    /** native 说过"这台设备做不到"（tier 0 / 不支持该速率 / 导出缺失）；P238 起是唯一的能力缓存。 */
    @JvmStatic
    var nativeRefused: Boolean = false
        private set

    /**
     * P232：这条地形批次允许的着色率，`-1` = 一个调用都不发（保持 1X1）。
     *
     * <p>白名单而不是黑名单 —— 26.3 的 pass 表还在变，新加的 pass 默认必须是"不粗"。
     * 只有 `solid_terrain`（不含 shadow）能拿到 [MAX_TERRAIN_RATE]：再粗下去，法线/UV 的块状误差
     * 在 TAA/DLSS 之后仍然可见。`cutout_terrain`（叶子、栅栏的镂空）与 `translucent`/`oit_`
     * 一律 1X1 —— 粗像素会把相邻 alpha 混进同一个着色样本，表现为"叶子边缘破洞"（§8.3）。
     */
    @JvmStatic
    fun terrainRateFor(location: String): Int {
        if (!isEnabled()) {
            return -1
        }
        if (location.contains("shadow") || !location.contains("solid_terrain")) {
            return -1
        }
        return MAX_TERRAIN_RATE
    }

    /**
     * P232：把 `body` 包成一次"允许变粗"的作用域 —— 进入时下发 `RSSetShadingRate(rate)`，
     * 出作用域立刻恢复 1X1（`finally`，即使 `body` 抛异常也不会把粗粒度漏给后面的绘制）。
     *
     * <p>`ctx` 是 `D3D12RenderPassBackend` 的 native 命令上下文（主列表，已开始录制）。
     * `rate <= 0` 时零调用 —— 档位 OFF 与本来就必须 1X1 的批次都走这条，所以开启 VRS 之后
     * 每帧真正多出来的 native 调用只有"每个实心地形批次两次"。
     */
    @JvmStatic
    fun scope(ctx: Long, location: String, body: () -> Unit) {
        val rate = terrainRateFor(location)
        if (rate <= 0) {
            body()
            return
        }
        // P235：组合器按"图像是否在场"切换 —— 图像在场时 stage0 改 PASSTHROUGH，让逐图元
        // 距离带不再一票否决图像（否则 32–96 格那一圈 1X2 会一直压在图像之上，用户实测
        // 地平线闪烁正是这一圈）；stage1 取 MIN，图像只能把更细的 tile 保住、不能反向变粗。
        // 图像不在场则回到 P233 的两级 MIN/PASSTHROUGH。
        val sriReady = D3D12ShadingRateImage.isReady()
        setCombiners(if (sriReady) COMBINER_PASSTHROUGH else COMBINER_MIN, COMBINER_MIN)
        val issued = MCDR_ABI.D3D12SetShadingRateNow(ctx, rate) == 1
        if (issued) {
            scopedBatches += 1
        }
        // P235：本帧的着色率图像也在同一条列表上绑（Tier 2 的第三票，组合器取 MIN）。
        // 必须与 `RSSetShadingRate` 同列表 —— `RSSetShadingRateImage` 是每条命令列表的状态。
        val imageBound = issued && D3D12ShadingRateImage.bind(ctx)
        if (imageBound) {
            scopedImages += 1
        }
        try {
            body()
        } finally {
            if (imageBound) {
                D3D12ShadingRateImage.unbind(ctx)
            }
            if (issued && MCDR_ABI.D3D12SetShadingRateNow(ctx, 0x0) == 1) {
                scopedRestores += 1
            }
        }
    }

    /**
     * P231/P233：把两个组合器推给 native（幂等，启动/重载时调一次即可）。
     *
     * <p>P233 起 stage0 取 `MIN`：mesh shader 已经逐图元写 `SV_ShadingRate`（近处 1X1、中距
     * 1X2、远处 2X2），只有 `MIN` 才能让"近处更细"生效 —— `PASSTHROUGH` 会直接忽略逐图元
     * 速率，`OVERRIDE` 会让最远那一档把基率整个盖掉。stage1 仍是 `PASSTHROUGH`（SRI 还没上，
     * 见 P235）。
     *
     * <p>**失败方向**：如果 mesh shader 因为没有解析到相机 uniform 而**没有**写该语义，
     * 逐图元速率默认 1X1，`MIN(2X2, 1X1) = 1X1` ⇒ 画面回到"VRS 未生效"，不会把近处地形
     * 一起糊掉。
     */
    @JvmStatic
    /**
     * P235：每批次换组合器只改 native 的存储值，下次 `SetShadingRateNow` 生效 ——
     * 所以这个调用必须排在 `SetShadingRateNow` 之前（`RSSetShadingRate` 把基率与两个
     * 组合器一起写进列表状态，晚写就赶不上本次生效）。
     */
    private fun setCombiners(first: Int, second: Int) {
        if (first == lastCombiner0 && second == lastCombiner1) {
            return
        }
        if (MCDR_ABI.D3D12SetShadingRateCombiners(first, second) == 1) {
            lastCombiner0 = first
            lastCombiner1 = second
        }
    }

    /**
     * P236：防御性复位 —— 在 GUI 开始前把速率与图像一起清掉。
     *
     * <p>正常路径上 `scope()` 的 `finally` 已经恢复过 1X1，这里是"重复一次"的保险：GUI 与世界上半
     * 部分共用同一条命令列表（计划 §2.4），只要有一条异常路径跳过了 finally，文字就会被粗着色。
     * 代价是每帧两次 `now()` 调用（不计入 `scopedRestores`，避免污染档位统计）。
     */
    @JvmStatic
    fun hardReset(ctx: Long) {
        if (ctx == 0L || current() == D3D12VRSSettings.OFF) {
            return
        }
        MCDR_ABI.D3D12SetShadingRateImage(ctx, 0L)
        MCDR_ABI.D3D12SetShadingRateNow(ctx, RATE_1X1)
    }

    fun pushCombiners() {
        when (val status = MCDR_ABI.D3D12SetShadingRateCombiners(COMBINER_MIN, COMBINER_PASSTHROUGH)) {
            1 -> Illusion.LOGGER.info("[${Illusion.MODID}] VRS combiners = MIN / PASSTHROUGH.")
            Int.MIN_VALUE -> Illusion.LOGGER.info(
                "[${Illusion.MODID}] VRS combiners unavailable: the loaded MCDR.dll has no mcdr_dx12SetShadingRateCombiners.",
            )
            else -> Illusion.LOGGER.warn("[${Illusion.MODID}] VRS combiners rejected by the native layer (status $status).")
        }
    }

    /**
     * P233：`b8` 的 `gVrsBand` —— (近带外沿, 中带外沿, 远带速率, 启用位)，距离单位是方块、相机相对。
     *
     * <p>近带内 1X1（脚下的方块、手持物附近必须清楚），中带 1X2，远带 2X2。两个外沿每批次现读
     * `[vrs]` 配置，改 `dxr_mc-client.toml` 下一帧就生效 —— P233 的地平线闪烁正是靠这个在不重启的
     * 情况下定位的（结论与三次失败尝试见 `known_bugs.md` 的 P233 条目）。远带速率自 P238 起固定
     * 2X2：玩家档位已删，这里不再读配置。
     */
    @JvmStatic
    fun msBand(): FloatArray {
        val enabled = isEnabled()
        val near = configFloat(IllusionConfig.VRS_TERRAIN_NEAR_BLOCKS, MS_NEAR_END)
        val far = configFloat(IllusionConfig.VRS_TERRAIN_FAR_BLOCKS, MS_FAR_START)
        return floatArrayOf(near, far, RATE_2X2.toFloat(), if (enabled) 1.0f else 0.0f)
    }

    /** 配置没加载（早期帧、单元测试）时退回常量，不让 `get()` 抛异常打断整帧。 */
    private fun configFloat(value: ModConfigSpec.DoubleValue, fallback: Float): Float {
        if (!IllusionConfig.SPEC.isLoaded) return fallback
        return value.get().toFloat()
    }

    /** P233：近带外沿（方块），配置缺失时的兜底值。 */
    private const val MS_NEAR_END: Float = 32.0f

    /** P233：中带外沿（方块），配置缺失时的兜底值。 */
    private const val MS_FAR_START: Float = 96.0f

    /** `D3D12_SHADING_RATE_1X1` —— 全质量，也是 `scope()` 结束时恢复的那一档。 */
    private const val RATE_1X1: Int = 0x0

    /** `D3D12_SHADING_RATE_1X2` —— 竖直方向减半。 */
    private const val RATE_1X2: Int = 0x1

    /** `D3D12_SHADING_RATE_2X2` —— 四分之一的着色像素。 */
    private const val RATE_2X2: Int = 0x5

    /** `D3D12_SHADING_RATE_COMBINER_PASSTHROUGH`；只在本文件用，取值与 d3d12.h 一致。 */
    private const val COMBINER_PASSTHROUGH: Int = 0

    /** `D3D12_SHADING_RATE_COMBINER_MIN`。 */
    private const val COMBINER_MIN: Int = 2

    /**
     * P232：F3 上的 VRS 行。档位开着却 `batches` 一直是 0，说明作用域没被走到（地形批次没经过
     * [scope]）或者 native 那条导出缺失 —— 两种都会表现为"设了但看不出来"，所以必须可观测。
     */
    @JvmStatic
    fun debugLine(): String {
        // P237：一行里同时给出能力（tier/tile/extra）、当前判决者（图像 or 距离带）与计数，
        // 这样"设了看不见效果"时能一眼分辨是设备不支持、图像没生成、还是批次没走白名单。
        val info = D3D12AdapterInfo.current
        val caps = "tier=${info?.shadingRateTier ?: 0} tile=${info?.shadingRateTileSize ?: 0}" +
            " extra=${if (info?.additionalShadingRates == true) 1 else 0}"
        if (current() == D3D12VRSSettings.OFF) {
            return "VRS: OFF (no RSSetShadingRate) | $caps"
        }
        if (nativeRefused) {
            return "VRS: AUTO unsupported by this device (staying 1X1) | $caps"
        }
        val judge = if (D3D12ShadingRateImage.isReady()) {
            "sri ${D3D12ShadingRateImage.tileSize()} imgs=$scopedImages" +
                " clamp=${if (D3D12ShadingRateImage.temporalClampActive()) "on" else "off"}"
        } else {
            "band<=${rateName(MAX_TERRAIN_RATE)} near=${IllusionConfig.VRS_TERRAIN_NEAR_BLOCKS.get().toInt()}" +
                " far=${IllusionConfig.VRS_TERRAIN_FAR_BLOCKS.get().toInt()}"
        }
        return "VRS: AUTO $judge | $caps batches=$scopedBatches restores=$scopedRestores"
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
        // P231：组合器是设备级状态，启动时推一次即可（幂等）。
        pushCombiners()
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

