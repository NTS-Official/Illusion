package dev.nts.expdxr.misc.gui

import dev.nts.expdxr.Illusion.Companion.MODID
import dev.nts.expdxr.d3d12.extern.MCDR_ABI
import dev.nts.expdxr.dxrmc.sr.dlss.DLSSFrameGeneration
import dev.nts.expdxr.SrBackend
import dev.nts.expdxr.dxrmc.sr.SuperResolution
import dev.nts.expdxr.misc.D3D12HdrPresentation
import dev.nts.expdxr.d3d12.render.advanced.D3D12AntiAliasing
import dev.nts.expdxr.d3d12.render.advanced.vrs.D3D12RenderScale
import dev.nts.expdxr.d3d12.render.advanced.vrs.D3D12VariableShadingRate
import dev.nts.expdxr.dxrmc.sr.dlss.DeepLearningSuperSampling
import dev.nts.expdxr.dxrmc.nvidia.reflex.NvidiaReflex
import dev.nts.expdxr.mixin.options.VideoSettingsScreenOptionsAccessor
import net.minecraft.client.Minecraft
import net.minecraft.client.OptionInstance
import net.minecraft.client.Options
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import java.util.IdentityHashMap
import java.util.Optional
import kotlin.math.roundToInt

/**
 * D3D12 专属的视频设置界面：左侧四个页签、右侧自绘的设置行、底部“应用 / 撤销 / 完成”。
 *
 * <p>整页照 wgpu-mc（Neolectrum）的 `OptionPageScreen` 逐行移植，只把它的 `Option` 抽象换成原版的
 * [OptionInstance]，把渲染换成 26.3 的 `GuiGraphicsExtractor`：
 * <ul>
 *   <li>页宽按“显示器宽 × [WIDTH_SHARE_OF_MONITOR] ÷ GUI 缩放”算，并夹在窗口内——缩到很小也能用。</li>
 *   <li>内容超出可视高度时整页滚动（滚轮），绘制用 scissor 裁掉视口外的行。</li>
 *   <li>改动通过 [OptionInstance.set] 立即生效；“应用”落盘，“撤销”写回打开界面时的基线。</li>
 * </ul>
 *
 * @author DXRMC
 */
class D3D12VideoSettingsScreen(private val parent: Screen) :
    Screen(Component.translatable("dxr_mc.video.title")) {

    private val pages: List<Page> = buildPages()

    /** 与滚动无关的控件：标题、页签、底部按钮。 */
    private val widgets: MutableList<D3D12Widget> = ArrayList()

    /** 当前页的行控件，以及它们在“未滚动”时的 y。 */
    private val rows: MutableList<Pair<D3D12Widget, Int>> = ArrayList()

    /** 行控件 ↔ 它承载的选项，撤销与“是否有改动”都用这份对应关系。 */
    private val optionRows: MutableList<Pair<OptionInstance<*>, D3D12OptionWidget<*>>> = ArrayList()

    /** 打开界面时的取值，撤销写回用。 */
    private val baseline: IdentityHashMap<OptionInstance<*>, Any?> = IdentityHashMap()

    private var page: Int = 0
    private var scroll: Double = 0.0
    private var maxScroll: Int = 0
    private var dragging: D3D12Widget? = null

    private var contentLeft: Int = 0
    private var contentTop: Int = 0
    private var contentRight: Int = 0
    private var contentBottom: Int = 0
    private var contentWidth: Int = 0

    /** 已展开的折叠控件，按选项实例记（同一选项在页与页之间是同一个实例）。 */
    private val expandedOptions: MutableSet<OptionInstance<*>> = mutableSetOf()

    init {
        snapshotBaseline()
    }

    override fun init() {
        widgets.clear()
        rows.clear()
        optionRows.clear()

        val optimalWidth = optimalWidth()
        contentLeft = SIDEBAR_X + D3D12TabWidget.WIDTH + GAP
        contentTop = TITLE_Y + D3D12Widget.DEFAULT_HEIGHT + GAP
        contentRight = optimalWidth - OUTER_MARGIN
        // 底部留三行：提示行（常驻预留，避免出现提示时压到动作按钮上，用户 m06437）+ 间隔 + 按钮行。
        contentBottom = height - OUTER_MARGIN - D3D12Widget.DEFAULT_HEIGHT - GAP - HINT_LINE
        contentWidth = (contentRight - contentLeft).coerceAtLeast(MIN_CONTENT_WIDTH)

        widgets += D3D12TitleWidget(TITLE_X, TITLE_Y, optimalWidth - TITLE_X - OUTER_MARGIN, title)

        var tabY = contentTop
        pages.forEachIndexed { index, page ->
            widgets += D3D12TabWidget(
                SIDEBAR_X,
                tabY,
                page.name,
                { this.page == index },
                { switchPage(index) },
            )
            tabY += D3D12TabWidget.HEIGHT + TAB_GAP
        }

        buildRows()
        buildActionButtons(optimalWidth)
    }

    // ------------------------------------------------------------ 页面内容

    /** 一页 = 一个页签名 + 一串“标题行 / 设置行”。 */
    private class Page(val name: Component, val entries: List<Entry>)

    private sealed interface Entry {
        class Heading(val text: Component) : Entry

        /**
         * 只读说明行：写"这项为什么现在不可用"这类**状态**（例如运行库缺失），不参与交互。
         *
         * <p>它存在的理由：SR 的后端能力差别很大，光一个"打了勾但每帧没生效"看不出原因，
         * 而原因只有 native 问得出来（caps.note）。
         */
        class Note(val text: Component, val warning: Boolean = false) : Entry

        class Setting(val option: OptionInstance<*>) : Entry
    }

    private fun buildPages(): List<Page> {
        val options = Minecraft.getInstance().options
        val display = VideoSettingsScreenOptionsAccessor.dxrmcDisplayOptions(options)
        val quality = VideoSettingsScreenOptionsAccessor.dxrmcQualityOptions(options)
        val preference = VideoSettingsScreenOptionsAccessor.dxrmcPreferenceOptions(options)

        return listOf(
            Page(
                translatable("dxr_mc.video.tab.display"),
                listOfNotNull(
                    Entry.Heading(translatable("dxr_mc.video.group.display")),
                    // 原版把分辨率滑条内联在 addOptions 里，没有暴露成 Options 字段，这里照原版语义重建。
                    Entry.Setting(exclusiveFullscreenModeOption()),
                    Entry.Setting(options.fullscreen()),
                    Entry.Setting(options.exclusiveFullscreen()),
                    Entry.Setting(options.enableVsync()),
                    Entry.Setting(D3D12HdrPresentation.option()),
                    Entry.Heading(Component.empty()),
                    Entry.Heading(translatable("dxr_mc.video.group.game")),
                    // displayOptions 顺序：0 最大帧率 / 1 垂直同步 / 2 降低帧率 / 3 界面尺寸 / 4 全屏 /
                    // 5 独占全屏 / 6 亮度 / 7 图形 API。垂直同步与全屏已挪到上面，这里取剩下的。
                    display.getOrNull(3)?.let { Entry.Setting(it) },
                    display.getOrNull(0)?.let { Entry.Setting(it) },
                    display.getOrNull(2)?.let { Entry.Setting(it) },
                    display.getOrNull(6)?.let { Entry.Setting(it) },
                    Entry.Heading(Component.empty()),
                    Entry.Heading(translatable("dxr_mc.video.group.advanced")),
                    display.getOrNull(7)?.let { Entry.Setting(it) },
                ),
            ),
            Page(
                translatable("dxr_mc.video.tab.quality"),
                listOfNotNull(
                    Entry.Heading(translatable("dxr_mc.video.group.preset")),
                    Entry.Setting(options.graphicsPreset()),
                    Entry.Heading(Component.empty()),
                    Entry.Heading(Component.translatable("$MODID.video.group.quality")),
                ) + quality.map { Entry.Setting(it) } + listOfNotNull(
                    Entry.Heading(Component.empty()),
                    Entry.Heading(translatable("dxr_mc.video.group.extra")),
                    Entry.Setting(D3D12AntiAliasing.option()),
                ),
            ),
            Page(
                translatable("dxr_mc.video.tab.preferences"),
                preference.map { Entry.Setting(it) },
            ),
            Page(
                translatable("dxr_mc.video.tab.illusion"),
                listOfNotNull(
                    Entry.Setting(D3D12RenderScale.option()),
                    Entry.Heading(Component.empty()),
                    Entry.Heading(translatable("dxr_mc.video.group.vrs")),
                    Entry.Setting(D3D12VariableShadingRate.option()),
                    Entry.Heading(Component.empty()),
                    Entry.Heading(translatable("dxr_mc.video.group.nvidia")),
                    Entry.Note(DeepLearningSuperSampling.statusLine()),
                    Entry.Setting(DeepLearningSuperSampling.option()),
                    Entry.Setting(DeepLearningSuperSampling.presetOption()),
                    Entry.Setting(DeepLearningSuperSampling.qualityOption()),
                    Entry.Setting(NvidiaReflex.option()),
                    // P213：帧生成只在 DLSS 启用时才有意义，这里的 listOfNotNull 会把它藏掉。
                    if (DLSSFrameGeneration.isAvailable()) Entry.Setting(DLSSFrameGeneration.option()) else null,
                    if (DLSSFrameGeneration.isAvailable()) Entry.Setting(DLSSFrameGeneration.framesOption()) else null,
                    // 用户 m01923：SR 的两个非 NVIDIA 后端接在这两段里。开关共用门面里的
                    // 一个"当前后端"，点开一家另一家自动关（见 SuperResolution.requestBackend）。
                    // 每段的形状与上面 DLSS 段对齐：状态行 -> 开关 -> 质量档；下面再接三行
                    // 两家共用的项（遮罩 / 锐化 / 帧生成说明）。
                    Entry.Heading(Component.empty()),
                    Entry.Heading(translatable("dxr_mc.video.group.amd")),
                    Entry.Note(
                        SuperResolution.statusLine(MCDR_ABI.SR_BACKEND_FSR),
                        SuperResolution.isBackendMissing(MCDR_ABI.SR_BACKEND_FSR),
                    ),
                    Entry.Setting(SuperResolution.fsrOption()),
                    Entry.Setting(SuperResolution.fsrQualityOption()),
        Entry.Note(SuperResolution.runtimeLine(MCDR_ABI.SR_BACKEND_FSR)),
                    Entry.Heading(Component.empty()),
                    Entry.Heading(translatable("dxr_mc.video.group.intel")),
                    Entry.Note(
                        SuperResolution.statusLine(MCDR_ABI.SR_BACKEND_XESS),
                        SuperResolution.isBackendMissing(MCDR_ABI.SR_BACKEND_XESS),
                    ),
                    Entry.Setting(SuperResolution.xessOption()),
                    Entry.Setting(SuperResolution.xessQualityOption()),
        Entry.Note(SuperResolution.runtimeLine(MCDR_ABI.SR_BACKEND_XESS)),
                    Entry.Heading(Component.empty()),
                    Entry.Heading(translatable("dxr_mc.video.group.sr_shared")),
                    Entry.Setting(SuperResolution.maskOption()),
                    Entry.Setting(SuperResolution.sharpnessOption()),
                    Entry.Note(translatable("dxr_mc.video.sr.framegen_absent")),
        Entry.Note(translatable("dxr_mc.video.sr.latency_absent")),
                ),
            ),
        )
    }

    private fun buildRows() {
        // 切页签/重新布局会重建行，未点“应用”的暂存值要跟着走（用户 m06864 的暂存模型）。
        val carried = optionRows.mapNotNull { (option, widget) ->
            widget.pendingOrNull()?.let { option to it }
        }.toMap()

        rows.clear()
        optionRows.clear()

        var y = contentTop
        for (entry in pages[page].entries) {
            val widget: D3D12Widget = when (entry) {
                is Entry.Heading -> D3D12HeadingWidget(contentLeft, y, contentWidth, entry.text)
                is Entry.Note -> D3D12NoteWidget(contentLeft, y, contentWidth, entry.text, entry.warning)
                is Entry.Setting -> {
                    val row = optionWidgetFor(entry.option, contentLeft, y, contentWidth)
                    carried[entry.option]?.let { row.stageExternal(it) }
                    optionRows += entry.option to row
                    row
                }
            }

            rows += widget to y
            // 折叠控件展开后会多占一行，后面的行整体下移（不叠加、不覆盖）。
            y += D3D12Widget.DEFAULT_HEIGHT +
                if (entry is Entry.Setting && expandedOptions.contains(entry.option)) D3D12OptionWidget.SLIDER_LINE else 0
        }

        maxScroll = (y - contentTop - (contentBottom - contentTop)).coerceAtLeast(0)
        scroll = scroll.coerceIn(0.0, maxScroll.toDouble())
        applyScroll()
    }

    @Suppress("UNCHECKED_CAST")
    private fun optionWidgetFor(option: OptionInstance<*>, x: Int, y: Int, width: Int): D3D12OptionWidget<*> {
        val typed = option as OptionInstance<Any>
        // 只对**滑条行**生效（开关/枚举是点击循环，没有展开态）。
        val probe = D3D12OptionWidget(x, y, width, typed)
        // 重叠判定按真实像素算（用户 m06567）：标题（悬停时最多占行宽 1/3）+ 取值 + 内边距 > 行宽，
        // 或者内容区本来就窄，就退化成折叠控件。之前只比“取值 > 半行宽”，标题一长就漏判。
        val captionWidth = probe.captionWidth()
        val valueWidth = probe.hoverHintWidth()
        // 折叠只在“真的放不下”时触发（用户 m10344：标题很短、取值放得下的行被无谓折叠又省略了）。
        // 滑条行额外要求整行宽度够放滑轨（点得准），所以它的标题按 1/3 行宽估算。
        val wanted = if (probe.hasSlider()) {
            maxOf(captionWidth, width / 3) + valueWidth + 18
        } else {
            captionWidth + valueWidth + 18
        }
        // 优先“变成可展开控件”，展开后仍放不下才省略（用户 m10319）：纯取值行只在真的放不下时折叠，
        // 滑条行在窄窗也折叠（滑轨需要整行宽度才点得准）。
        // 滑条行内联时：标题最多占 1/3 行宽，取值只能填在“标题右缘 → 行中线”这一段
        // （`x + width/2 - (x + 6 + min(captionW, width/3) + 8)`）。取值比这段宽就一定会被
        // 省略，按用户要求直接折叠成可展开控件（m10319/m10358/m10394）。
        val inlineCaption = minOf(captionWidth, width / 3)
        val inlineValueSpace = (width / 2 - 14 - inlineCaption).coerceAtLeast(0)
        val valueOverflowsInlineSlider = probe.hasSlider() && valueWidth > inlineValueSpace
        val collapsible = probe.canCollapse() &&
            (wanted > width || valueOverflowsInlineSlider || (probe.hasSlider() && width < MIN_EXPANDED_WIDTH))
        if (!collapsible) {
            // 窗口拉宽后不再折叠：把残留的展开状态清掉，否则行高会一直停在一行半（用户 m06567）。
            expandedOptions.remove(option)
        }
        val expanded = collapsible && expandedOptions.contains(option)
        // P213 联动：帧生成生效时 Reflex 只允许 On(1) / On+Boost(2)，不许回到 Off(0)。
        val allowed: (() -> Set<Any>)? =
            if (option === NvidiaReflex.option()) {
                { if (DLSSFrameGeneration.isEnabled()) setOf<Any>(1, 2) else setOf<Any>(0, 1, 2) }
            } else {
                null
            }
        return D3D12OptionWidget(
            x, y, width, typed, collapsible, expanded, { toggleExpand(option) }, { isReadOnly(option) }, allowed, { lockReasonFor(option) },
        )
    }

    /**
     * 这一行是否只读（用户 m07432）。
     *
     * <p>目前只有一种情况：DLSS 上采样开着时，渲染缩放被绑到 DLSS 的输入比例
     * （见 `DeepLearningSuperSampling.bindRenderScaleToDlss`），滑条再可拖就是自相矛盾的。
     * DLAA 不算上采样（比例锁 100%），仍然可拖。
     */
    /**
     * 只读原因（用户 m10067 / m10163）：返回 null 表示这一行可改；非 null 时整行只读，
     * 悬停会额外显示这条原因（固定提示块放在说明框下方，悬浮提示放到鼠标上方）。
     *
     * <p>四种锁定都来自“两套时域/超分机制不能同时说话”：
     * DLSS 上采样接管了分辨率与时域消解，DLAA 接管了 DLSS 的开关与挡位，帧生成又把 DLSS 的输出当输入。
     */
    private fun lockReasonFor(option: OptionInstance<*>): Component? {
        val upscaling = DeepLearningSuperSampling.isEnabled() && !DeepLearningSuperSampling.isDlaaActive()
        val dlaa = DeepLearningSuperSampling.isDlaaActive()
        // 超分器换成 FSR / XeSS 时 DLSS 那条链路并没有在跑，这两把锁的理由都不成立（比例是三家共用的
        // "世界层分辨率"，抗锯齿也轮不到没在跑的 DLSS 说话），所以只在 DLSS 档才锁。
        val dlssIsTheUpscaler = SuperResolution.backend() == SrBackend.DLSS
        // 渲染缩放：DLSS 上采样时比例由挡位决定（bindRenderScaleToDlss），手拖会自相矛盾。
        if (option === D3D12RenderScale.option() && upscaling && dlssIsTheUpscaler) {
            return Component.translatable("dxr_mc.video.lock.renderscale_dlss")
        }
        // 抗锯齿：DLSS 上采样自己就是时域消解，再叠加 TAA/SMAA 只会双重处理（DLAA 时反而不锁）。
        if (option === D3D12AntiAliasing.option() && upscaling && dlssIsTheUpscaler) {
            return Component.translatable("dxr_mc.video.lock.aa_dlss")
        }
        if (option === DeepLearningSuperSampling.option()) {
            // 帧生成的输入就是 DLSS 输出：关掉 DLSS 等于关掉帧生成。
            if (DLSSFrameGeneration.isEnabled()) {
                return Component.translatable("dxr_mc.video.lock.dlss_framegen")
            }
            // DLAA 走的就是 DLSS 特性链路，开关与挡位由 DLAA 决定。
            if (dlaa) {
                return Component.translatable("dxr_mc.video.lock.dlss_dlaa")
            }
            // 另一家超分（FSR / XeSS）已经占了后端时，再动 DLSS 这一行不会有效果（门面按当前后端
            // 分派），置灰比让人以为"打开了 DLSS"更诚实。
            //
            // P239 修：`backend() == NONE`（还没选过后端）**不能**锁 —— 否则"没选后端 → DLSS 置灰 →
            // 选不了后端"就是死锁（用户 m03463：「超分被锁了开不起来」）。NONE 时打开 DLSS 会由
            // `SuperResolution.onDlssToggle` 顺手认领后端。
            val owner = SuperResolution.backend()
            if (owner == SrBackend.FSR || owner == SrBackend.XESS) {
                return Component.translatable("dxr_mc.video.lock.sr_other_backend")
            }
        }
        // 用户 m01923：两家非 NVIDIA 后端共用一个"当前后端"，所以另一家置灰；
        // 帧生成插值的是 DLSS 输出，开着它的时候也不能把超分器换成别人。
        if (option === SuperResolution.fsrOption() || option === SuperResolution.xessOption()) {
            if (DLSSFrameGeneration.isEnabled()) {
                return Component.translatable("dxr_mc.video.lock.sr_framegen")
            }
            val other = if (option === SuperResolution.fsrOption()) SuperResolution.xessOption() else SuperResolution.fsrOption()
            if (other.get()) {
                return Component.translatable("dxr_mc.video.lock.sr_exclusive")
            }
        }
        // 新加的两行质量档只在"那一家正在跑"时才有效（applyQuality 对非当前后端直接返回）；
        // 置灰比让人拨了半天没反应更诚实。帧生成开着时同样锁（插值的是 DLSS 输出，不能换源）。
        val fsrQuality = option === SuperResolution.fsrQualityOption()
        val xessQuality = option === SuperResolution.xessQualityOption()
        if (fsrQuality || xessQuality) {
            if (DLSSFrameGeneration.isEnabled()) {
                return Component.translatable("dxr_mc.video.lock.sr_framegen")
            }
            val wanted = if (fsrQuality) SrBackend.FSR else SrBackend.XESS
            if (SuperResolution.backend() != wanted) {
                return Component.translatable("dxr_mc.video.lock.sr_not_active")
            }
        }
        // 遮罩是"谁吃谁用"的：DLSS 走 NGX，没有遮罩输入，拨了不会有效果。
        if (option === SuperResolution.maskOption() && !SuperResolution.maskSupported()) {
            return Component.translatable("dxr_mc.video.lock.sr_mask_unsupported")
        }
        if (option === DeepLearningSuperSampling.qualityOption() && dlaa) {
            return Component.translatable("dxr_mc.video.lock.dlss_dlaa")
        }
        // 帧生成开着时 Reflex 不能回到 Off（那会让 SDK 静默禁用插帧），但 On / On+Boost
        // 都合法，所以这一行不锁，只把 Off 从循环里摘掉（见 optionWidgetFor 的 allowedValues）。
        return null
    }

    /** 这一行是否只读：唯一判据就是“有没有锁定原因”。 */
    private fun isReadOnly(option: OptionInstance<*>): Boolean = lockReasonFor(option) != null

    /** 折叠/展开一行；只重建行，保留滚动位置（不重进 `init()`，否则页签与标题也会被重建）。 */
    private fun toggleExpand(option: OptionInstance<*>) {
        if (!expandedOptions.remove(option)) {
            expandedOptions += option
        }
        buildRows()
    }

    private fun buildActionButtons(optimalWidth: Int) {
        val buttonWidth = 100
        val buttonY = height - OUTER_MARGIN - D3D12Widget.DEFAULT_HEIGHT
        val applyX = optimalWidth - OUTER_MARGIN - buttonWidth
        val undoX = applyX - buttonWidth - 4

        widgets += D3D12ButtonWidget(
            undoX,
            buttonY,
            buttonWidth,
            D3D12Widget.DEFAULT_HEIGHT,
            { translatable("dxr_mc.video.undo") },
            { hasChanges() },
            { undo() },
        )
        widgets += D3D12ButtonWidget(
            applyX,
            buttonY,
            buttonWidth,
            D3D12Widget.DEFAULT_HEIGHT,
            { translatable(if (hasChanges()) "dxr_mc.video.apply" else "dxr_mc.video.done") },
            { true },
            {
                if (hasChanges()) {
                    apply()
                } else {
                    onClose()
                }
            },
        )
    }

    private fun switchPage(index: Int) {
        if (index == page) {
            return
        }

        page = index
        scroll = 0.0
        buildRows()
    }

    // ------------------------------------------------------------ 改动/落盘

    private fun snapshotBaseline() {
        baseline.clear()
        for (page in pages) {
            for (entry in page.entries) {
                if (entry is Entry.Setting) {
                    baseline[entry.option] = entry.option.get()
                }
            }
        }
    }

    private fun hasChanges(): Boolean = optionRows.any { (_, widget) -> widget.hasPendingChange() }

    /**
     * 应用：把各行的暂存值一次性写回 [OptionInstance]（到这一步才真正重建渲染目标、开关 DLSS），
     * 然后落盘。用户 m06864 要求“点了应用才生效”。
     */
    private fun apply() {
        val opts = Minecraft.getInstance().options
        val presetRow = optionRows.firstOrNull { it.first === opts.graphicsPreset() }?.second
        val presetChanged = presetRow?.hasPendingChange() == true
        for ((_, widget) in optionRows) {
            widget.commitPending()
        }
        // P211j（用户 m08262）：画质预设与抗锯齿的联动必须在“应用”之后显式补一次。
        // `OptionInstance.set` 只写取值，原版界面是靠 `Options.applyGraphicsPreset(...)` 触发
        // `GraphicsPreset.apply` 的；我们的自绘界面绕过了那段，于是预设变了、抗锯齿不动
        // （fast→OFF / fancy→SMAA / fabulous→TAA 的映射在 `D3D12AntiAliasing.applyFromPreset`）。
        if (presetChanged) {
            opts.applyGraphicsPreset(opts.graphicsPreset().get())
        }

        minecraft.options.save()
        snapshotBaseline()
    }

    /** 撤销：丢掉暂存值，界面回落到“已生效”的取值。 */
    private fun undo() {
        for ((_, widget) in optionRows) {
            widget.clearPending()
        }
    }

    // ------------------------------------------------------------ 绘制/输入

    override fun extractRenderState(
        extractor: GuiGraphicsExtractor,
        mouseX: Int,
        mouseY: Int,
        partialTick: Float,
    ) {
        super.extractRenderState(extractor, mouseX, mouseY, partialTick)

        val renderer = D3D12WidgetRenderer(extractor)
        val scissored = maxScroll > 0 && contentBottom > contentTop
        if (scissored) {
            renderer.enableScissor(contentLeft, contentTop, contentRight, contentBottom)
        }

        for ((widget, _) in rows) {
            widget.render(renderer, mouseX, mouseY, partialTick)
        }

        if (scissored) {
            renderer.disableScissor()
        }

        widgets.forEach { it.render(renderer, mouseX, mouseY, partialTick) }
        drawTooltip(renderer, mouseX, mouseY)

        if (restartPending()) {
            // 提示行是常驻预留的（见 init 里的 contentBottom），并再夹一道横向裁剪：
            // 小窗 + 中文长句时宁可截断，也不能画到 撤销/应用 按钮上（用户 m06437）。
            val hintY = contentBottom + (HINT_LINE - 8) / 2
            renderer.enableScissor(contentLeft, contentBottom, contentRight, contentBottom + HINT_LINE)
            renderer.text(
                translatable("options.restartRequired"),
                contentLeft + 6,
                hintY,
                D3D12Widget.RED,
            )
            renderer.disableScissor()
        }
    }

    /**
     * 悬停行的提示。
     *
     * <p>版式是**布局级**决策（用户 m06665）：只要右侧还有可用空间，所有提示都用固定提示块，
     * 长描述在块内自己换行；只有当窗口窄到右侧完全没有余量时，才整体退回跟随鼠标的悬浮提示。
     * 内容模板固定为：标题 / 空行 / 描述。
     */
    private fun drawTooltip(renderer: D3D12WidgetRenderer, mouseX: Int, mouseY: Int) {
        if (dragging != null) {
            return
        }

        // 用户 m10226：内容区外的行（被下半部分按钮挡住/滚出边界）不能再弹提示。
        if (mouseY < contentTop || mouseY > contentBottom) return
        val hovered = optionRows.lastOrNull { (_, widget) ->
            widget.y >= contentTop && widget.y + widget.height <= contentBottom &&
                widget.isMouseOver(mouseX.toDouble(), mouseY.toDouble())
        }
            ?.second ?: return
        val tooltipText = hovered.tooltipText() ?: return

        val font = Minecraft.getInstance().font
        val caption = hovered.captionComponent()
        val lineHeight = renderer.textHeight()
        val pad = HINT_PAD
        val freeRight = width - (contentRight + GAP) - OUTER_MARGIN
        val anchored = freeRight >= MIN_HINT_WIDTH

        // 换行宽度：固定块用右侧实际余量（上限便于阅读），悬浮提示用固定宽度。
        val textZone = if (anchored) {
            (freeRight - GAP).coerceIn(MIN_HINT_WIDTH, MAX_HINT_WIDTH) - pad * 2
        } else {
            FLOAT_HINT_WIDTH - pad * 2
        }
        val lines = font.split(tooltipText, textZone)
        // 固定块里把锁定原因并进同一个框（用户 m10189：两块半透明背景叠在一起会显得更黑）。
        val lockReason = hovered.lockReasonText()
        val reasonLines = if (anchored && lockReason != null) font.split(lockReason, textZone) else emptyList()
        val reasonExtra = if (reasonLines.isEmpty()) 0 else 2 + reasonLines.size  // 空行 + 注意 + 原因
        // 悬浮提示不显示标题行（用户 m06708），固定块仍然“标题 / 空行 / 描述”。
        val headerLines = if (anchored) 2 else 0
        val boxWidth = maxOf(
            lines.maxOf { font.width(it) },
            reasonLines.maxOfOrNull { font.width(it) } ?: 0,
            if (anchored) renderer.textWidth(caption) else 0,
        ) + pad * 2
        val boxHeight = pad * 2 + lineHeight * (lines.size + headerLines + reasonExtra)

        val boxX: Int
        val boxY: Int
        if (anchored) {
            boxX = contentRight + GAP
            boxY = TITLE_Y
        } else {
            boxX = (mouseX + 12).coerceIn(4, (width - boxWidth - 4).coerceAtLeast(4))
            boxY = (mouseY + 12).coerceIn(4, (height - boxHeight - 4).coerceAtLeast(4))
        }
        val reasonHeader = if (reasonLines.isEmpty()) null else Component.translatable("dxr_mc.video.lock.notice")
        drawTooltipBox(renderer, caption, lines, reasonLines, reasonHeader, boxX, boxY, boxWidth, boxHeight, pad, lineHeight, anchored)

        // 悬浮式提示（窄窗）：锁定原因仍然单独画在鼠标上方；固定块里已经并进同一个框（见上）。
        if (!anchored && lockReason != null) {
            val floatLines = font.split(lockReason, textZone)
            val floatWidth = floatLines.maxOf { font.width(it) } + pad * 2
            val floatHeight = pad * 2 + lineHeight * (floatLines.size + 1)
            val floatY = (mouseY - floatHeight - 12).coerceAtLeast(4)
            renderer.rect(boxX, floatY, boxX + floatWidth, floatY + floatHeight, D3D12Widget.TOOLTIP_BG)
            var floatRow = floatY + pad
            renderer.text(Component.translatable("dxr_mc.video.lock.notice"), boxX + pad, floatRow, D3D12Widget.WARNING)
            floatRow += lineHeight
            for (floatLine in floatLines) {
                renderer.text(floatLine, boxX + pad, floatRow, D3D12Widget.DISABLED)
                floatRow += lineHeight
            }
        }
    }

    private fun drawTooltipBox(
        renderer: D3D12WidgetRenderer,
        caption: Component,
        lines: List<net.minecraft.util.FormattedCharSequence>,
        reasonLines: List<net.minecraft.util.FormattedCharSequence>,
        reasonHeader: Component?,
        boxX: Int,
        boxY: Int,
        boxWidth: Int,
        boxHeight: Int,
        pad: Int,
        lineHeight: Int,
        anchored: Boolean,
    ) {
        renderer.rect(boxX, boxY, boxX + boxWidth, boxY + boxHeight, D3D12Widget.TOOLTIP_BG)
        if (anchored) {
            // 固定块用顶部强调线，和标题条呼应。
            renderer.rect(boxX, boxY, boxX + boxWidth, boxY + 1, D3D12Widget.ACCENT)
        } else {
            renderer.rect(boxX, boxY, boxX + boxWidth, boxY + 1, D3D12Widget.TOOLTIP_BORDER)
            renderer.rect(boxX, boxY + boxHeight - 1, boxX + boxWidth, boxY + boxHeight, D3D12Widget.TOOLTIP_BORDER)
            renderer.rect(boxX, boxY, boxX + 1, boxY + boxHeight, D3D12Widget.TOOLTIP_BORDER)
            renderer.rect(boxX + boxWidth - 1, boxY, boxX + boxWidth, boxY + boxHeight, D3D12Widget.TOOLTIP_BORDER)
        }

        var textY = boxY + pad
        if (anchored) {
            renderer.text(caption, boxX + pad, textY, D3D12Widget.ACCENT)
            textY += lineHeight * 2
        }
        for (line in lines) {
            renderer.text(line, boxX + pad, textY, D3D12Widget.WHITE)
            textY += lineHeight
        }
        if (reasonLines.isNotEmpty()) {
            // 空一行，先写亮黄色的“注意：”，再接锁定原因（同一框内，颜色更暗）。
            textY += lineHeight
            if (reasonHeader != null) {
                renderer.text(reasonHeader, boxX + pad, textY, D3D12Widget.WARNING)
                textY += lineHeight
            }
            for (reasonLine in reasonLines) {
                renderer.text(reasonLine, boxX + pad, textY, D3D12Widget.DISABLED)
                textY += lineHeight
            }
        }
    }
    private fun restartPending(): Boolean =
        D3D12HdrPresentation.isRestartPending() || minecraft.options.isRestartRequiredToApplyVideoSettings()

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        val mouseX = event.x
        val mouseY = event.y

        for (widget in widgets) {
            if (widget.mouseClicked(mouseX, mouseY, event.button())) {
                return true
            }
        }

        if (mouseY >= contentTop && mouseY <= contentBottom) {
            for ((widget, _) in rows) {
                if (widget.mouseClicked(mouseX, mouseY, event.button())) {
                    dragging = widget
                    return true
                }
            }
        }

        return super.mouseClicked(event, doubleClick)
    }

    override fun mouseReleased(event: MouseButtonEvent): Boolean {
        dragging = null
        return super.mouseReleased(event)
    }

    override fun mouseDragged(event: MouseButtonEvent, dragX: Double, dragY: Double): Boolean {
        val widget = dragging
        if (widget != null) {
            widget.mouseDragged(event.x, event.y, event.button(), dragX, dragY)
            return true
        }

        return super.mouseDragged(event, dragX, dragY)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        if (maxScroll > 0) {
            scroll = (scroll - scrollY * SCROLL_STEP).coerceIn(0.0, maxScroll.toDouble())
            applyScroll()
            return true
        }

        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)
    }

    private fun applyScroll() {
        val offset = scroll.roundToInt()
        for ((widget, baseY) in rows) {
            widget.setPosition(contentLeft, baseY - offset)
        }
    }

    // ------------------------------------------------------------ 布局尺寸

    /**
     * 页宽：显示器宽 × [WIDTH_SHARE_OF_MONITOR] ÷ GUI 缩放，并夹在窗口内。
     *
     * <p>取不到显示器时退回 [FALLBACK_MAX_WIDTH]，再与窗口宽取小——这条兜底保证缩窗口不会把页面撑出屏幕。
     */
    private fun optimalWidth(): Int {
        val window = minecraft.window
        val monitorWidth = window.findBestMonitor()?.let { monitor ->
            monitor.currentMode.width.takeIf { it > 0 } ?: monitor.videoModes.firstOrNull()?.width
        } ?: FALLBACK_MAX_WIDTH

        val scale = window.guiScale.coerceAtLeast(1)
        // 窗口比 MIN_WIDTH 还窄时下限跟着窗口走，否则 coerceIn 会因为下界大于上界抛异常。
        val minWidth = MIN_WIDTH.coerceAtMost(width)
        return (monitorWidth * WIDTH_SHARE_OF_MONITOR / scale).toInt().coerceIn(minWidth, width)
    }

    // ------------------------------------------------------------ 生命周期

    override fun onClose() {
        // 关界面不等于应用（用户 m06864）：没点“应用”的改动一律丢弃。
        for ((_, widget) in optionRows) {
            widget.clearPending()
        }

        minecraft.setScreenAndShow(parent)
    }

    override fun removed() {
        minecraft.options.save()
    }

    /**
     * 分辨率滑条（原版 `options.fullscreen.exclusive.mode`）。
     *
     * <p>值 -1 表示“跟随当前模式”，其余是 Monitor 里的模式下标；写回的是
     * `Window.setPreferredFullscreenVideoMode`，与原版那个内联滑条完全一致。
     */
    private fun exclusiveFullscreenModeOption(): OptionInstance<Int> {
        val window = minecraft.window
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

    private companion object {
        /** 页面宽度占显示器宽度的比例（照 wgpu-mc）。 */
        const val WIDTH_SHARE_OF_MONITOR: Double = 0.809

        /** 取不到显示器时的兜底宽度（照 wgpu-mc 的 2071 逻辑宽）。 */
        const val FALLBACK_MAX_WIDTH: Int = 2071

        /** 滚轮一格滚几像素（照 wgpu-mc）。 */
        const val SCROLL_STEP: Int = 12

        const val MIN_WIDTH: Int = 360
        const val MIN_CONTENT_WIDTH: Int = 220

        /** 内容区窄于这个宽度时，滑条一律退化成折叠控件（内联展开必重叠）。 */
        const val MIN_EXPANDED_WIDTH: Int = 330
        const val SIDEBAR_X: Int = 8
        const val TITLE_X: Int = 8
        const val TITLE_Y: Int = 8
        const val OUTER_MARGIN: Int = 8
        const val GAP: Int = 8
        const val TAB_GAP: Int = 4

        /** “需要重启”提示行的高度：常驻预留，避免提示出现时压到动作按钮（用户 m06437）。 */
        const val HINT_LINE: Int = 12

        /** 右侧提示块的内边距。 */
        const val HINT_PAD: Int = 6

        /** 右侧至少要留这么宽，才把提示画成固定块；否则整体退化成悬浮提示（用户 m06665）。 */
        const val MIN_HINT_WIDTH: Int = 96

        /** 固定提示块的正文宽度上限：太宽反而不便阅读。 */
        const val MAX_HINT_WIDTH: Int = 240

        /** 悬浮提示的固定正文宽度（照 wgpu-mc 的观感）。 */
        const val FLOAT_HINT_WIDTH: Int = 200

        fun translatable(key: String): Component = Component.translatable(key)
    }
}
