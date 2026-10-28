package dev.nts.expdxr.d3d12.render.advanced.vrs

import com.mojang.serialization.Codec
import dev.nts.expdxr.Illusion
import dev.nts.expdxr.IllusionConfig
import dev.nts.expdxr.misc.settings.D3D12VRSSettings
import dev.nts.expdxr.d3d12.extern.MCDR_ABI
import dev.nts.expdxr.d3d12.render.adapter.D3D12AdapterInfo
import dev.nts.expdxr.dxrmc.sr.SuperResolution
import java.io.File
import java.nio.charset.StandardCharsets
import net.minecraft.client.OptionInstance
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.network.chat.Component
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent
import net.neoforged.neoforge.common.ModConfigSpec
import net.neoforged.neoforge.common.NeoForge

/**
 * VRS 着色粒度：只有 OFF / AUTO 两档，作为普通视频设置挂在原版视频设置界面，值存 `options.txt`
 * （键 [OPTIONS_KEY]，改动经 [push] 即时推给 native）。能力判据全在 native 侧且分两拍。
 * 见 docs/mesh&vrs/README.md §3.1。
 */
object D3D12VariableShadingRate {
    /** 循环按钮取值顺序 = 枚举声明顺序（OFF 在最前）。 */
    @JvmField
    val MODES: List<D3D12VRSSettings> = D3D12VRSSettings.entries.toList()

    /** `options.txt` 键名（与抗锯齿 / 渲染比例同一套管道，写的是枚举名）。 */
    const val OPTIONS_KEY: String = "dxrMcShadingRate"

    /** 标题由 CycleButton 自己加，`toString` 只能返回取值名，否则标题渲染两遍。 */
    private val OPTION: OptionInstance<D3D12VRSSettings> = OptionInstance(
        "options.shadingRate",
        { Tooltip.create(Component.translatable("options.shadingRate.tooltip")) },
        { _, value -> value.getTranslatedName() },
        OptionInstance.Enum<D3D12VRSSettings>(MODES, modeCodec()),
        D3D12VRSSettings.OFF,
        { value -> push(value) },
    )

    /** `options.txt` 文本 ↔ 取值；选项不是 `Options` 的字段，codec 只做名字映射。 */
    private fun modeCodec(): Codec<D3D12VRSSettings> =
        Codec.STRING.xmap({ name -> modeFromName(name) }, { mode -> mode.name })

    /** 旧 `options.txt` 遗留的 `RATE_*` 手动档一律当 AUTO，不能静默退回 OFF。 */
    private fun modeFromName(name: String): D3D12VRSSettings {
        MODES.firstOrNull { it.name == name }?.let { return it }
        return if (name.startsWith("RATE_")) D3D12VRSSettings.AUTO else D3D12VRSSettings.OFF
    }

    @JvmStatic
    fun option(): OptionInstance<D3D12VRSSettings> = OPTION

    @JvmStatic
    fun current(): D3D12VRSSettings = OPTION.get()

    /** True when AUTO is selected **and** native did not refuse the request. */
    @JvmStatic
    fun isEnabled(): Boolean = current() == D3D12VRSSettings.AUTO && !nativeRefused

    /** True while the game really renders through DXRMC's D3D12 backend（非 D3D12 时按钮置灰）。 */
    @JvmStatic
    fun isAvailable(): Boolean = D3D12AdapterInfo.current != null

    /**
     * 取值 → native：AUTO 推 [MAX_TERRAIN_RATE] 只为让 native 做一次能力预检（tier 0 / 不支持返回 0），
     * 失败记入 [nativeRefused]。速率只是地形粗粒度的上限，真正下发由 [scope] 逐批次完成（§3.1）。
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

    /** 地形最多粗到 2X2（枚举值按面积递增，越小越细）。 */
    const val MAX_TERRAIN_RATE: Int = 0x5

    /**
     * 本帧地形允许的粗粒度上限：超分在跑时压到 1X2（58% 尺度下 2X2 只剩 1/4 样本，超分抖动会放大成
     * 整屏跳动），100% 尺度仍允许 2X2。见 docs/mesh&vrs/README.md §3.2。
     */
    @JvmStatic
    fun terrainRateCap(): Int = if (SuperResolution.isEnabled()) RATE_1X2 else RATE_2X2

    /** 已下发的粗粒度批次 / 已恢复 1X1 的次数（诊断用，进 F3 行）。 */
    @JvmStatic
    var scopedBatches: Long = 0L
        private set

    @JvmStatic
    var scopedRestores: Long = 0L
        private set

    /** 真正绑上过着色率图像的批次（恒 0 = SRI 没生效，看 `dxrmc-vrs-sri` 日志）。 */
    @JvmStatic
    var scopedImages: Long = 0L
        private set

    /** 上一批的组合器（`d3d12.h` 取值），避免每批次白调一次 native。 */
    private var lastCombiner0: Int = COMBINER_MIN
    private var lastCombiner1: Int = COMBINER_PASSTHROUGH

    /** 清零三个诊断计数 —— **只在离开存档时**调用；只写字段，不碰 GPU 资源（回调不在渲染线程上）。 */
    @JvmStatic
    fun resetCounters() {
        scopedBatches = 0L
        scopedRestores = 0L
        scopedImages = 0L
        D3D12ShadingRateImage.resetCounters()
    }

    /** 挂"退出 / 进入存档"监听：清计数并请求强制 1X1；回调不在渲染线程上，只置标志由下一帧消费。 */
    fun register() {
        NeoForge.EVENT_BUS.addListener<ClientPlayerNetworkEvent.LoggingOut> {
            D3D12ShadingRateImage.requestForceFine("left the world")
            resetCounters()
        }
        NeoForge.EVENT_BUS.addListener<ClientPlayerNetworkEvent.LoggingIn> {
            D3D12ShadingRateImage.requestForceFine("joined a world")
        }
    }

    /** native 说过"这台设备做不到"（tier 0 / 速率不支持 / 导出缺失）。 */
    @JvmStatic
    var nativeRefused: Boolean = false
        private set

    /**
     * 这条地形批次允许的着色率，`-1` = 一个调用都不发（保持 1X1）。白名单：只有 `solid_terrain`
     * （不含 shadow）能变粗；cutout / translucent / oit 一律 1X1，否则叶子边缘会破洞（§3.2）。
     */
    @JvmStatic
    fun terrainRateFor(location: String): Int {
        if (!isEnabled()) {
            return -1
        }
        if (location.contains("shadow") || !location.contains("solid_terrain")) {
            return -1
        }
        return terrainRateCap()
    }

    /**
     * 把 `body` 包成一次"允许变粗"的作用域：进入时下发 `RSSetShadingRate(rate)`，`finally` 恢复 1X1
     * （异常也不会把粗粒度漏给后面的绘制）。`ctx` 是已开始录制的主命令列表；`rate <= 0` 时零调用。
     */
    @JvmStatic
    fun scope(ctx: Long, location: String, body: () -> Unit) {
        val rate = terrainRateFor(location)
        if (rate <= 0) {
            body()
            return
        }
        // 组合器按"图像是否在场"切换：图像在场时 stage0 用 PASSTHROUGH（否则远处那圈基率会压在
        // 图像之上，实测表现为地平线闪烁），stage1 用 MIN 让图像只能保住更细的 tile、不能反向变粗；
        // 图像不在场则回到 MIN / PASSTHROUGH。
        val sriReady = D3D12ShadingRateImage.isReady()
        setCombiners(if (sriReady) COMBINER_PASSTHROUGH else COMBINER_MIN, COMBINER_MIN)
        val issued = MCDR_ABI.D3D12SetShadingRateNow(ctx, rate) == 1
        if (issued) {
            scopedBatches += 1
        }
        // `RSSetShadingRateImage` 是每条命令列表的状态，必须与 `RSSetShadingRate` 同列表。
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
     * 把两个组合器推给 native（幂等）。stage0 取 `MIN`，mesh shader 逐图元写的 `SV_ShadingRate` 才能
     * "近处更细"生效（`PASSTHROUGH` 会忽略它、`OVERRIDE` 会被最远档盖掉）；逐图元速率缺省 1X1，故失败即回到"VRS 未生效"（§3.4）。
     */
    @JvmStatic
    /** 只改 native 存储值、下次 `SetShadingRateNow` 生效 —— 所以本调用**必须**排在它之前。 */
    private fun setCombiners(first: Int, second: Int) {
        if (first == lastCombiner0 && second == lastCombiner1) {
            return
        }
        if (MCDR_ABI.D3D12SetShadingRateCombiners(first, second) == 1) {
            lastCombiner0 = first
            lastCombiner1 = second
        }
    }

    /** 防御性复位：GUI 之前把速率与图像一起清掉（`finally` 已恢复过，这里防异常路径把文字也粗着色）。 */
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
     * `b8` 的 `gVrsBand` = (近带外沿, 中带外沿, 远带速率, 启用位)，单位方块、相机相对；外沿每批次现读
     * `[vrs]` 配置，改 TOML 下一帧生效。近带 1X1 / 中带 1X2 / 远带取 [terrainRateCap]（§3.2）。
     */
    @JvmStatic
    fun msBand(): FloatArray {
        val enabled = isEnabled()
        val near = configFloat(IllusionConfig.VRS_TERRAIN_NEAR_BLOCKS, MS_NEAR_END)
        val far = configFloat(IllusionConfig.VRS_TERRAIN_FAR_BLOCKS, MS_FAR_START)
        return floatArrayOf(near, far, terrainRateCap().toFloat(), if (enabled) 1.0f else 0.0f)
    }

    /** 配置未加载（早期帧、单测）时退回常量，不让 `get()` 抛异常打断整帧。 */
    private fun configFloat(value: ModConfigSpec.DoubleValue, fallback: Float): Float {
        if (!IllusionConfig.SPEC.isLoaded) return fallback
        return value.get().toFloat()
    }

    /** 近带外沿兜底值（方块）。 */
    private const val MS_NEAR_END: Float = 32.0f

    /** 中带外沿兜底值（方块）。 */
    private const val MS_FAR_START: Float = 96.0f

    /** `D3D12_SHADING_RATE_1X1`：全质量，也是 `scope()` 结束恢复的那一档。 */
    private const val RATE_1X1: Int = 0x0

    /** `D3D12_SHADING_RATE_1X2`：竖直方向减半。 */
    private const val RATE_1X2: Int = 0x1

    /** `D3D12_SHADING_RATE_2X2`：四分之一的着色像素。 */
    private const val RATE_2X2: Int = 0x5

    /** `D3D12_SHADING_RATE_COMBINER_PASSTHROUGH`（取值与 `d3d12.h` 一致）。 */
    private const val COMBINER_PASSTHROUGH: Int = 0

    /** `D3D12_SHADING_RATE_COMBINER_MIN`。 */
    private const val COMBINER_MIN: Int = 2

    /** F3 上的 VRS 行：档位开着但 `batches` 恒 0 ⇒ 作用域没走到，或 native 导出缺失。 */
    @JvmStatic
    fun debugLine(): String {
        // 一行给出能力（tier/tile/extra）、判决者（图像 or 距离带）与计数，便于分辨"设了没效果"。
        val info = D3D12AdapterInfo.current
        val caps = "tier=${info?.shadingRateTier ?: 0} tile=${info?.shadingRateTileSize ?: 0}" +
            " extra=${if (info?.additionalShadingRates == true) 1 else 0}"
        if (current() == D3D12VRSSettings.OFF) {
            return "VRS: OFF (no RSSetShadingRate) | $caps"
        }
        if (nativeRefused) {
            return "VRS: AUTO unsupported by this device (staying 1X1) | $caps"
        }
        // cap 单列一段：它决定"允许粗到哪一档"，与"谁是判决者（图像 / 距离带）"是两件事。
        val cap = "cap=${rateName(terrainRateCap())}${if (SuperResolution.isEnabled()) "(sr)" else ""}"
        val judge = if (D3D12ShadingRateImage.isReady()) {
            "sri ${D3D12ShadingRateImage.tileSize()} imgs=$scopedImages" +
                " clamp=${if (D3D12ShadingRateImage.temporalClampActive()) "on" else "off"}" +
                " $cap"
        } else {
            "$cap near=${IllusionConfig.VRS_TERRAIN_NEAR_BLOCKS.get().toInt()}" +
                " far=${IllusionConfig.VRS_TERRAIN_FAR_BLOCKS.get().toInt()}"
        }
        return "VRS: AUTO $judge | $caps batches=$scopedBatches restores=$scopedRestores"
    }

    /**
     * 从 `options.txt` 读回取值（行格式 `key:value`，值是枚举名）。`Options.load` 时游戏还没
     * `isRunning()`，`OPTION.set` 不触发回调，所以末尾自己推一次 native。
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
        // 文件里没有这一行（首跑 / 用户删过 / 刚从模组配置迁移过来）也要推一次，保持界面与 native 一致。
        push(OPTION.get())
        pushCombiners()
    }

    /** 写回 `options.txt`：先丢掉自己的旧行，重复保存不会堆叠。 */
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

