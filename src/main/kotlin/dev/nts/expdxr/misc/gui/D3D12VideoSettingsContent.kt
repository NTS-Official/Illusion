package dev.nts.expdxr.misc.gui

import dev.nts.expdxr.Illusion.Companion.MODID
import dev.nts.expdxr.SrBackend
import dev.nts.expdxr.d3d12.extern.MCDR_ABI
import dev.nts.expdxr.d3d12.render.advanced.D3D12AntiAliasing
import dev.nts.expdxr.d3d12.render.advanced.vrs.D3D12RenderScale
import dev.nts.expdxr.d3d12.render.advanced.vrs.D3D12VariableShadingRate
import dev.nts.expdxr.dxrmc.framegen.FrameGeneration
import dev.nts.expdxr.dxrmc.nvidia.reflex.NvidiaReflex
import dev.nts.expdxr.dxrmc.sr.SuperResolution
import dev.nts.expdxr.dxrmc.sr.dlss.DeepLearningSuperSampling
import dev.nts.expdxr.misc.settings.D3D12HdrPresentation
import dev.nts.expdxr.mixin.options.VideoSettingsScreenOptionsAccessor
import net.minecraft.client.Minecraft
import net.minecraft.client.OptionInstance
import net.minecraft.client.Options
import net.minecraft.network.chat.Component
import java.util.Optional
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 一页设置。有 `subPages` 时：`entries` 固定在子页签上方，`trailingEntries` 接在子页内容下方；
 * 没有子页时两者按顺序铺在一起。
 */
class D3D12VideoSettingsPage(
    val name: Component,
    val entries: List<D3D12VideoSettingsEntry> = emptyList(),
    val subPages: List<D3D12VideoSettingsSubPage> = emptyList(),
    val trailingEntries: List<D3D12VideoSettingsEntry> = emptyList(),
)

/** 二级子页：标题与行。 */
class D3D12VideoSettingsSubPage(val name: Component, val entries: List<D3D12VideoSettingsEntry>)

sealed interface D3D12VideoSettingsEntry {
    class Heading(val text: Component) : D3D12VideoSettingsEntry

    /** 只读说明行：写"这项为什么现在不可用"这类状态（原因只有 native 的 caps.note 问得出来），不参与交互。 */
    class Note(val text: Component, val warning: Boolean = false) : D3D12VideoSettingsEntry

    class Setting(val option: OptionInstance<*>) : D3D12VideoSettingsEntry
}

/**
 * 视频设置页的**内容**：只回答"有哪几页、每页有哪几行、哪一行为什么被锁"，不碰布局、滚动、绘制与输入。
 *
 * <p>拆出来是给附属模组留出口：追加页签用 [addPageProvider]，改核心页则继承本类覆写 [pages]。
 * 见 docs/misc/settingscreen/README.md §6.3 / §7。
 */
open class D3D12VideoSettingsContent {

    /** 当前要显示的页：四个核心页 + 已注册的扩展页。每次打开界面重算一遍（行上的条件依赖实时状态）。 */
    open fun pages(): List<D3D12VideoSettingsPage> {
        val pages = ArrayList<D3D12VideoSettingsPage>(4 + providers.size)
        pages += displayPage()
        pages += qualityPage()
        pages += preferencesPage()
        pages += illusionPage()
        for (provider in providers) {
            pages += provider.pages()
        }
        return pages
    }

    // ------------------------------------------------------------ 核心页

    protected open fun displayPage(): D3D12VideoSettingsPage {
        val options = Minecraft.getInstance().options
        val display = VideoSettingsScreenOptionsAccessor.dxrmcDisplayOptions(options)

        return D3D12VideoSettingsPage(
            translatable("dxr_mc.video.tab.display"),
            listOfNotNull(
                D3D12VideoSettingsEntry.Heading(translatable("dxr_mc.video.group.display")),
                // 原版把分辨率滑条内联在 addOptions 里，没有暴露成 Options 字段，这里照原版语义重建。
                D3D12VideoSettingsEntry.Setting(exclusiveFullscreenModeOption()),
                D3D12VideoSettingsEntry.Setting(options.fullscreen()),
                D3D12VideoSettingsEntry.Setting(options.exclusiveFullscreen()),
                D3D12VideoSettingsEntry.Setting(options.enableVsync()),
                D3D12VideoSettingsEntry.Setting(D3D12HdrPresentation.option()),
                D3D12VideoSettingsEntry.Heading(Component.empty()),
                D3D12VideoSettingsEntry.Heading(translatable("dxr_mc.video.group.game")),
                // displayOptions 顺序：0 最大帧率 / 1 垂直同步 / 2 降低帧率 / 3 界面尺寸 / 4 全屏 /
                // 5 独占全屏 / 6 亮度 / 7 图形 API。垂直同步与全屏已挪到上面，这里取剩下的。
                display.getOrNull(3)?.let { D3D12VideoSettingsEntry.Setting(it) },
                display.getOrNull(0)?.let { D3D12VideoSettingsEntry.Setting(it) },
                display.getOrNull(2)?.let { D3D12VideoSettingsEntry.Setting(it) },
                display.getOrNull(6)?.let { D3D12VideoSettingsEntry.Setting(it) },
                D3D12VideoSettingsEntry.Heading(Component.empty()),
                D3D12VideoSettingsEntry.Heading(translatable("dxr_mc.video.group.advanced")),
                display.getOrNull(7)?.let { D3D12VideoSettingsEntry.Setting(it) },
            ),
        )
    }

    protected open fun qualityPage(): D3D12VideoSettingsPage {
        val options = Minecraft.getInstance().options
        val quality = VideoSettingsScreenOptionsAccessor.dxrmcQualityOptions(options)

        return D3D12VideoSettingsPage(
            translatable("dxr_mc.video.tab.quality"),
            listOfNotNull(
                D3D12VideoSettingsEntry.Heading(translatable("dxr_mc.video.group.preset")),
                D3D12VideoSettingsEntry.Setting(options.graphicsPreset()),
                D3D12VideoSettingsEntry.Heading(Component.empty()),
                D3D12VideoSettingsEntry.Heading(Component.translatable("$MODID.video.group.quality")),
            ) + quality.map { D3D12VideoSettingsEntry.Setting(it) } + listOfNotNull(
                D3D12VideoSettingsEntry.Heading(Component.empty()),
                D3D12VideoSettingsEntry.Heading(translatable("dxr_mc.video.group.extra")),
                D3D12VideoSettingsEntry.Setting(D3D12AntiAliasing.option()),
            ),
        )
    }

    protected open fun preferencesPage(): D3D12VideoSettingsPage {
        val preference = VideoSettingsScreenOptionsAccessor.dxrmcPreferenceOptions(Minecraft.getInstance().options)
        return D3D12VideoSettingsPage(
            translatable("dxr_mc.video.tab.preferences"),
            preference.map { D3D12VideoSettingsEntry.Setting(it) },
        )
    }

    protected open fun illusionPage(): D3D12VideoSettingsPage {
        val nvidia = vendorPage(
            Component.literal("NVIDIA"), MCDR_ABI.SR_BACKEND_DLSS, MCDR_ABI.FG_BACKEND_DLSSG,
            DeepLearningSuperSampling.statusLine(), false,
            listOf(
                D3D12VideoSettingsEntry.Setting(DeepLearningSuperSampling.option()),
                D3D12VideoSettingsEntry.Setting(DeepLearningSuperSampling.presetOption()),
                D3D12VideoSettingsEntry.Setting(DeepLearningSuperSampling.qualityOption()),
                D3D12VideoSettingsEntry.Setting(NvidiaReflex.option()),
            ),
        )
        val amd = vendorPage(
            Component.literal("AMD"), MCDR_ABI.SR_BACKEND_FSR, MCDR_ABI.FG_BACKEND_FSRFG,
            SuperResolution.statusLine(MCDR_ABI.SR_BACKEND_FSR),
            SuperResolution.isBackendMissing(MCDR_ABI.SR_BACKEND_FSR),
            listOf(
                D3D12VideoSettingsEntry.Setting(SuperResolution.fsrOption()),
                D3D12VideoSettingsEntry.Setting(SuperResolution.fsrQualityOption()),
            ),
        )
        val intel = vendorPage(
            Component.literal("Intel"), MCDR_ABI.SR_BACKEND_XESS, MCDR_ABI.FG_BACKEND_XESSFG,
            SuperResolution.statusLine(MCDR_ABI.SR_BACKEND_XESS),
            SuperResolution.isBackendMissing(MCDR_ABI.SR_BACKEND_XESS),
            listOf(
                D3D12VideoSettingsEntry.Setting(SuperResolution.xessOption()),
                D3D12VideoSettingsEntry.Setting(SuperResolution.xessQualityOption()),
            ),
        )

        return D3D12VideoSettingsPage(
            translatable("dxr_mc.video.tab.illusion"),
            // 与厂商无关的行固定在子页签上方，切子页时不动。
            entries = listOf(
                D3D12VideoSettingsEntry.Heading(translatable("dxr_mc.video.group.scale")),
                D3D12VideoSettingsEntry.Setting(D3D12RenderScale.option()),
                D3D12VideoSettingsEntry.Heading(Component.empty()),
                D3D12VideoSettingsEntry.Heading(translatable("dxr_mc.video.group.vrs")),
                D3D12VideoSettingsEntry.Setting(D3D12VariableShadingRate.option()),
                D3D12VideoSettingsEntry.Heading(Component.empty()),
                D3D12VideoSettingsEntry.Heading(translatable("dxr_mc.video.group.sr_and_fg"))
            ),
            subPages = listOf(nvidia, amd, intel),
            trailingEntries = listOf(
                D3D12VideoSettingsEntry.Heading(Component.empty()),
                D3D12VideoSettingsEntry.Heading(translatable("dxr_mc.video.group.sr_shared")),
                D3D12VideoSettingsEntry.Setting(SuperResolution.maskOption()),
                D3D12VideoSettingsEntry.Setting(SuperResolution.sharpnessOption()),
                D3D12VideoSettingsEntry.Note(translatable("dxr_mc.video.sr.framegen_absent")),
                D3D12VideoSettingsEntry.Note(translatable("dxr_mc.video.sr.latency_absent")),
            ),
        )
    }

    /** 厂商子页：空行 + 可用性/运行状态，再空一行接该厂商的设置，帧生成开关排在设置最后。 */
    private fun vendorPage(
        label: Component,
        srBackend: Int,
        fgBackend: Int,
        srStatus: Component,
        srMissing: Boolean,
        vendorSettings: List<D3D12VideoSettingsEntry>,
    ): D3D12VideoSettingsSubPage {
        val entries = arrayListOf<D3D12VideoSettingsEntry>()
        entries += D3D12VideoSettingsEntry.Heading(Component.empty())
        entries += D3D12VideoSettingsEntry.Note(srStatus, srMissing)
        val srRuntime = SuperResolution.runtimeLine(srBackend)
        if (srRuntime.string.isNotEmpty()) {
            entries += D3D12VideoSettingsEntry.Note(srRuntime)
        }

        entries += D3D12VideoSettingsEntry.Note(
            FrameGeneration.statusLine(fgBackend),
            FrameGeneration.isBackendUnavailable(fgBackend),
        )
        if (FrameGeneration.isEnabled() && FrameGeneration.activeBackend() == fgBackend) {
            val fgRuntime = FrameGeneration.runtimeLine()
            if (fgRuntime.string.isNotEmpty()) {
                entries += D3D12VideoSettingsEntry.Note(fgRuntime)
            }
            FrameGeneration.rollbackLine()?.let { entries += D3D12VideoSettingsEntry.Note(it, warning = true) }
        }

        entries += D3D12VideoSettingsEntry.Heading(Component.empty())
        entries += vendorSettings
        entries += frameGenerationSettings(fgBackend)
        return D3D12VideoSettingsSubPage(label, entries)
    }

    /** 帧生成开关 + 倍率行；FSR-FG 固定只插 1 张，用一行说明代替倍率。 */
    private fun frameGenerationSettings(fgBackend: Int): List<D3D12VideoSettingsEntry> = listOfNotNull(
        FrameGeneration.backendOption(fgBackend)?.let { D3D12VideoSettingsEntry.Setting(it) },
        if (FrameGeneration.isEnabled() && FrameGeneration.activeBackend() == fgBackend) {
            if (fgBackend == MCDR_ABI.FG_BACKEND_FSRFG) {
                D3D12VideoSettingsEntry.Note(translatable("dxr_mc.video.fg.fsr.fixed2x"))
            } else {
                D3D12VideoSettingsEntry.Setting(FrameGeneration.framesOption())
            }
        } else {
            null
        },
    )

    /** 分辨率滑条（原版 `options.fullscreen.exclusive.mode`）：-1 = 跟随当前模式，其余是 Monitor 的模式下标。 */
    fun exclusiveFullscreenModeOption(): OptionInstance<Int> {
        val window = Minecraft.getInstance().window
        val monitor = window.findBestMonitor()
        val modes = monitor?.videoModes ?: emptyList()

        val initial = if (monitor == null) {
            -1
        } else {
            modes.indexOfFirst { it == monitor.currentMode }
        }

        return OptionInstance(
            "options.fullscreen.exclusive.mode",
            OptionInstance.cachedConstantTooltip(translatable("options.fullscreen.exclusive.mode.tooltip")),
            { caption: Component, value: Int ->
                when {
                    monitor == null -> translatable("options.fullscreen.unavailable")
                    value < 0 || value >= modes.size ->
                        Options.genericValueLabel(caption, translatable("options.fullscreen.current"))
                    else -> {
                        val mode = modes[value]
                        Options.genericValueLabel(
                            caption,
                            Component.translatable(
                                "options.fullscreen.entry",
                                mode.width,
                                mode.height,
                                mode.refreshRateLabel(),
                                mode.redBits + mode.greenBits + mode.blueBits,
                            ),
                        )
                    }
                }
            },
            OptionInstance.IntRange(-1, if (modes.isEmpty()) -1 else modes.size - 1),
            initial,
            { value: Int ->
                if (monitor != null) {
                    window.setPreferredFullscreenVideoMode(
                        if (value < 0 || value >= modes.size) Optional.empty() else Optional.of(modes[value]),
                    )
                }
            },
        )
    }

    // ------------------------------------------------------------ 每行的策略

    /**
     * 只读原因：null = 这一行可改；非 null = 整行只读，且悬停会额外显示这条原因。
     *
     * <p>所有锁都来自"两套时域/超分机制不能同时说话"。灰化矩阵见 docs/misc/settingscreen/README.md §6.6。
     */
    open fun lockReasonFor(option: OptionInstance<*>): Component? {
        val upscaling = DeepLearningSuperSampling.isEnabled() && !DeepLearningSuperSampling.isDlaaActive()
        val dlaa = DeepLearningSuperSampling.isDlaaActive()
        // FSR/XeSS 在跑时 DLSS 那条链路并没有在跑，比例与 AA 的锁都不成立，所以只在 DLSS 档才锁。
        val dlssIsTheUpscaler = SuperResolution.backend() == SrBackend.DLSS
        if (option === D3D12RenderScale.option() && upscaling && dlssIsTheUpscaler) {
            return Component.translatable("dxr_mc.video.lock.renderscale_dlss")
        }
        if (option === D3D12AntiAliasing.option() && upscaling && dlssIsTheUpscaler) {
            return Component.translatable("dxr_mc.video.lock.aa_dlss")
        }
        if (option === DeepLearningSuperSampling.option()) {
            if (FrameGeneration.isEnabled()) {
                return Component.translatable("dxr_mc.video.lock.dlss_framegen")
            }
            if (dlaa) {
                return Component.translatable("dxr_mc.video.lock.dlss_dlaa")
            }
            // 另一家超分已占后端时动 DLSS 不会有效果（门面按当前后端分派），置灰比让人以为"打开了"更诚实。
            // 但 `backend() == NONE`（还没选过后端）**不能**锁：否则"没选后端 → DLSS 置灰 → 选不了后端"是死锁。
            val owner = SuperResolution.backend()
            if (owner == SrBackend.FSR || owner == SrBackend.XESS) {
                return Component.translatable("dxr_mc.video.lock.sr_other_backend")
            }
        }
        if (option === SuperResolution.fsrOption() || option === SuperResolution.xessOption()) {
            if (FrameGeneration.isEnabled()) {
                return Component.translatable("dxr_mc.video.lock.sr_framegen")
            }
            val other = if (option === SuperResolution.fsrOption()) SuperResolution.xessOption() else SuperResolution.fsrOption()
            if (other.get()) {
                return Component.translatable("dxr_mc.video.lock.sr_exclusive")
            }
        }
        val fsrQuality = option === SuperResolution.fsrQualityOption()
        val xessQuality = option === SuperResolution.xessQualityOption()
        if (fsrQuality || xessQuality) {
            if (FrameGeneration.isEnabled()) {
                return Component.translatable("dxr_mc.video.lock.sr_framegen")
            }
            val wanted = if (fsrQuality) SrBackend.FSR else SrBackend.XESS
            if (SuperResolution.backend() != wanted) {
                return Component.translatable("dxr_mc.video.lock.sr_not_active")
            }
        }
        if (option === SuperResolution.maskOption() && !SuperResolution.maskSupported()) {
            return Component.translatable("dxr_mc.video.lock.sr_mask_unsupported")
        }
        // 帧生成的厂商开关一律不锁：能力表 available = 0 的后端正是要能点开试的，跑不起来由 native 的
        // note 与状态行解释；倍率行只在某家亮着时出现。
        if (option === DeepLearningSuperSampling.qualityOption() && dlaa) {
            return Component.translatable("dxr_mc.video.lock.dlss_dlaa")
        }
        // 帧生成开着时 Reflex 不能回到 Off（SDK 会静默禁用插帧），但 On / Boost 合法：只把 Off 从循环里摘掉。
        return null
    }

    /** 这一行是否只读：唯一判据就是"有没有锁定原因"。 */
    fun isReadOnly(option: OptionInstance<*>): Boolean = lockReasonFor(option) != null

    /**
     * 这一行允许的取值集合（null = 不限制）。返回的是**每次询问时重算**的 lambda：界面开着时
     * 帧生成可能被点开/关掉，Reflex 的允许集合随之变化。
     */
    open fun allowedValuesProvider(option: OptionInstance<*>): (() -> Set<Any>)? =
        if (option === NvidiaReflex.option()) {
            { if (FrameGeneration.isEnabled()) setOf<Any>(1, 2) else setOf<Any>(0, 1, 2) }
        } else {
            null
        }

    // ------------------------------------------------------------ 扩展点

    /** 往页签列表尾部追加内容的一方。注册一次，之后每次打开界面都会调用。 */
    fun interface PageProvider {
        fun pages(): List<D3D12VideoSettingsPage>
    }

    companion object {
        private val providers = CopyOnWriteArrayList<PageProvider>()

        /** 注册一个页提供者（线程安全）。调用方保证只注册一次；要撤销用 [removePageProvider]。 */
        @JvmStatic
        fun addPageProvider(provider: PageProvider) {
            providers += provider
        }

        /** 撤销注册（热重载与测试用）。返回是否真的移除了一条。 */
        @JvmStatic
        fun removePageProvider(provider: PageProvider): Boolean = providers.remove(provider)

        private fun translatable(key: String): Component = Component.translatable(key)
    }
}
