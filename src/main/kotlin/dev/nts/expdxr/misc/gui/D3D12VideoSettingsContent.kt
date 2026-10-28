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

class D3D12VideoSettingsPage(val name: Component, val entries: List<D3D12VideoSettingsEntry>)

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

    protected open fun illusionPage(): D3D12VideoSettingsPage = D3D12VideoSettingsPage(
        translatable("dxr_mc.video.tab.illusion"),
        listOfNotNull(
            D3D12VideoSettingsEntry.Setting(D3D12RenderScale.option()),
            D3D12VideoSettingsEntry.Heading(Component.empty()),
            D3D12VideoSettingsEntry.Heading(translatable("dxr_mc.video.group.vrs")),
            D3D12VideoSettingsEntry.Setting(D3D12VariableShadingRate.option()),
            D3D12VideoSettingsEntry.Heading(Component.empty()),
            D3D12VideoSettingsEntry.Heading(translatable("dxr_mc.video.group.nvidia")),
            D3D12VideoSettingsEntry.Note(DeepLearningSuperSampling.statusLine()),
            D3D12VideoSettingsEntry.Setting(DeepLearningSuperSampling.option()),
            D3D12VideoSettingsEntry.Setting(DeepLearningSuperSampling.presetOption()),
            D3D12VideoSettingsEntry.Setting(DeepLearningSuperSampling.qualityOption()),
            D3D12VideoSettingsEntry.Setting(NvidiaReflex.option()),
            // 帧生成按厂商分段各一个开关，点开一家另外两家自动关（互锁在 FrameGeneration.requestBackend），
            // 全灭就是不启用；倍率滑条只跟着亮着的那家出现。
            FrameGeneration.backendOption(MCDR_ABI.FG_BACKEND_DLSSG)?.let { D3D12VideoSettingsEntry.Setting(it) },
            if (FrameGeneration.isEnabled() && FrameGeneration.activeBackend() == MCDR_ABI.FG_BACKEND_DLSSG) {
                D3D12VideoSettingsEntry.Setting(FrameGeneration.framesOption())
            } else {
                null
            },
            // 状态行只说"为什么不能开 / 已就绪"；运行状态行摊 native 的 `[fg] picked=… rebuilds=…`，省得翻日志。
            if (FrameGeneration.isAvailable() || FrameGeneration.isEnabled()) {
                D3D12VideoSettingsEntry.Note(FrameGeneration.statusLine(), !FrameGeneration.isAvailable())
            } else {
                null
            },
            if (FrameGeneration.isEnabled()) D3D12VideoSettingsEntry.Note(FrameGeneration.runtimeLine()) else null,
            // native 侧回滚了（例如开着帧生成把 SR 后端换成 FSR/XeSS），而行上还写着「开」时
            // 必须说出来，否则就是界面在说假话。返 null 时不占高度。
            FrameGeneration.rollbackLine()?.let { D3D12VideoSettingsEntry.Note(it, warning = true) },
            // 两家非 NVIDIA 后端共用门面里的一个"当前后端"，点开一家另一家自动关（见 SuperResolution.requestBackend）；
            // 每段形状与 DLSS 段对齐：状态行 -> 开关 -> 质量档，下面再接两家共用的项。
            D3D12VideoSettingsEntry.Heading(Component.empty()),
            D3D12VideoSettingsEntry.Heading(translatable("dxr_mc.video.group.amd")),
            D3D12VideoSettingsEntry.Note(
                SuperResolution.statusLine(MCDR_ABI.SR_BACKEND_FSR),
                SuperResolution.isBackendMissing(MCDR_ABI.SR_BACKEND_FSR),
            ),
            D3D12VideoSettingsEntry.Setting(SuperResolution.fsrOption()),
            D3D12VideoSettingsEntry.Setting(SuperResolution.fsrQualityOption()),
            D3D12VideoSettingsEntry.Note(SuperResolution.runtimeLine(MCDR_ABI.SR_BACKEND_FSR)),
            // AMD 段的帧生成开关（FSR-FG）。互锁见 FrameGeneration.requestBackend。
            FrameGeneration.backendOption(MCDR_ABI.FG_BACKEND_FSRFG)?.let { D3D12VideoSettingsEntry.Setting(it) },
            // FSR-FG 的 SDK 只插 1 张（2×），倍率行对它没有意义：给一行说明而不是一个永远只出 2× 的选项。
            if (FrameGeneration.isEnabled() && FrameGeneration.activeBackend() == MCDR_ABI.FG_BACKEND_FSRFG) {
                D3D12VideoSettingsEntry.Note(translatable("dxr_mc.video.fg.fsr.fixed2x"))
            } else {
                null
            },
            D3D12VideoSettingsEntry.Heading(Component.empty()),
            D3D12VideoSettingsEntry.Heading(translatable("dxr_mc.video.group.intel")),
            D3D12VideoSettingsEntry.Note(
                SuperResolution.statusLine(MCDR_ABI.SR_BACKEND_XESS),
                SuperResolution.isBackendMissing(MCDR_ABI.SR_BACKEND_XESS),
            ),
            D3D12VideoSettingsEntry.Setting(SuperResolution.xessOption()),
            D3D12VideoSettingsEntry.Setting(SuperResolution.xessQualityOption()),
            D3D12VideoSettingsEntry.Note(SuperResolution.runtimeLine(MCDR_ABI.SR_BACKEND_XESS)),
            // Intel 段的帧生成开关（XeSS-FG）。
            FrameGeneration.backendOption(MCDR_ABI.FG_BACKEND_XESSFG)?.let { D3D12VideoSettingsEntry.Setting(it) },
            if (FrameGeneration.isEnabled() && FrameGeneration.activeBackend() == MCDR_ABI.FG_BACKEND_XESSFG) {
                D3D12VideoSettingsEntry.Setting(FrameGeneration.framesOption())
            } else {
                null
            },
            D3D12VideoSettingsEntry.Heading(Component.empty()),
            D3D12VideoSettingsEntry.Heading(translatable("dxr_mc.video.group.sr_shared")),
            D3D12VideoSettingsEntry.Setting(SuperResolution.maskOption()),
            D3D12VideoSettingsEntry.Setting(SuperResolution.sharpnessOption()),
            D3D12VideoSettingsEntry.Note(translatable("dxr_mc.video.sr.framegen_absent")),
            D3D12VideoSettingsEntry.Note(translatable("dxr_mc.video.sr.latency_absent")),
        ),
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
