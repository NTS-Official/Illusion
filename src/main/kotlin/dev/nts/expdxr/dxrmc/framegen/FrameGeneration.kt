package dev.nts.expdxr.dxrmc.framegen

import com.mojang.serialization.Codec
import dev.nts.expdxr.d3d12.extern.MCDRLayout
import dev.nts.expdxr.d3d12.extern.MCDR_ABI
import dev.nts.expdxr.d3d12.render.adapter.D3D12AdapterInfo
import dev.nts.expdxr.dxrmc.nvidia.reflex.NvidiaReflex
import dev.nts.expdxr.dxrmc.sr.dlss.DeepLearningSuperSampling
import dev.nts.expdxr.misc.D3D12Settings
import dev.nts.expdxr.misc.IllusionLogger
import java.io.File
import java.lang.foreign.Arena
import java.lang.foreign.ValueLayout.JAVA_INT
import java.util.Optional
import net.minecraft.client.Minecraft
import net.minecraft.client.OptionInstance
import net.minecraft.client.gui.components.CycleButton
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.network.chat.Component

/**
 * P300：帧生成的**后端无关门面**。
 *
 * <p>为什么从 `sr/dlss/DLSSFrameGeneration` 搬到这里：那个位置把「帧生成」写死成了 DLSS-G ——
 * 包名是 DLSS、选项名是 DLSS、能力判断查的是 D3D12 适配器。帧生成抽象层（native 侧
 * `IFrameGenProvider` + ABI v4 的 `mcdr_dx12Fg*`）已经把后端做成了可替换的东西，JVM 这一侧
 * 就该跟着：**玩家看到的是「帧生成」，不是「DLSS 帧生成」**。
 *
 * <p>职责边界：
 * - 这里只做「策略 + 持久化 + 文案 + 把意图交给 native」，一帧的活全在 native 侧
 *   （`fgBeforePresent` / `fgAfterPresent` 在 `presentSurface` 里，见 `fg_plan.md` §3.4）。
 * - 后端选择由能力表决定（[CANDIDATES] 按优先级取第一个 available 的）；v1 里真正能跑的
 *   只有 DLSS-G，FSR-FG / XeSS-FG 在 native 侧是 stub，能力表里 `available = 0`。
 * - 任何后端私有的前置条件（Reflex 必须开、DLSS 必须开）都收敛在 [ensurePrerequisites] 一个
 *   函数里 —— 那里是唯一知道「今天的低延迟方案是 Reflex」的地方。
 *
 * <p>持久化仍在 `options.txt`（键 `dxrMcFrameGeneration` / `dxrMcFrameGenerationFrames`，
 * 与 `D3D12HdrPresentation` 同一体例）。**键名不改**：改了就等于把老用户的设置扔掉。
 */
object FrameGeneration {

    /** `options.txt` 里的键（历史名字，换门面也不改）。 */
    const val OPTIONS_KEY: String = "dxrMcFrameGeneration"

    /** 生成帧数的键。 */
    const val FRAMES_KEY: String = "dxrMcFrameGenerationFrames"

    /** 选中的后端键（`""` = 一家都没选）。 */
    const val BACKEND_KEY: String = "dxrMcFrameGenBackend"

    /** 取值名，下标即玩家可见的模式编号。 */
    private val MODE_NAMES: List<String> = listOf("off", "on")

    private const val DEFAULT_MODE: Int = 0

    /**
     * 倍率档位的下限是「自动」（Dynamic MFG），不是 1× —— 自动只在开了帧生成时有意义。
     * 上限 5 对应 DLSS-G 的 `numFramesToGenerateMax`（`slDLSSGGetState` 报出来的值）。
     */
    private const val MIN_FRAMES: Int = 0
    private const val MAX_FRAMES: Int = 5
    private const val DEFAULT_FRAMES: Int = 1

    /** 能力表缓存的存活时间（纳秒）。 */
    private const val CAPS_TTL_NS: Long = 1_000_000_000L

    /**
     * 候选后端，按优先级排列。v1 只有第一个能跑；后两个在这里是**如实登记**，不是承诺 ——
     * native 侧它们的 `caps.available` 恒为 0，所以永远轮不到（见 `fg_plan.md` §3.7）。
     */
    private val CANDIDATES: IntArray = intArrayOf(
        MCDR_ABI.FG_BACKEND_DLSSG,
        MCDR_ABI.FG_BACKEND_FSRFG,
        MCDR_ABI.FG_BACKEND_XESSFG,
    )

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

        override fun next(value: Int): Optional<Int> =
            Optional.of(if (value >= MAX_FRAMES) MIN_FRAMES else value + 1)

        override fun previous(value: Int): Optional<Int> =
            Optional.of(if (value <= MIN_FRAMES) MAX_FRAMES else value - 1)
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

    /** 用户在视频设置里选中的后端；`0` = 一家都没选 ⇒ 帧生成关。 */
    private var selectedBackend: Int = 0

    /** 同步开关状态时抑制回调（与 `SuperResolution.syncOptions` 同体例）。 */
    private var syncingOptions: Boolean = false

    /** 后端号 ↔ `options.txt` 里的短名。 */
    private val BACKEND_NAMES: Map<Int, String> = linkedMapOf(
        MCDR_ABI.FG_BACKEND_DLSSG to "dlssg",
        MCDR_ABI.FG_BACKEND_FSRFG to "fsrfg",
        MCDR_ABI.FG_BACKEND_XESSFG to "xessfg",
    )
    private val BACKEND_BY_NAME: Map<String, Int> =
        BACKEND_NAMES.entries.associate { (backend, name) -> name to backend }

    /**
     * 每家的帧生成开关（视频设置里各厂商段内一行）。点开一家自动关掉其它 ——
     * 三家改的是同一条呈现链路，同时开没有意义。
     */
    private val BACKEND_OPTIONS: Map<Int, OptionInstance<Boolean>> =
        BACKEND_NAMES.keys.associateWith { backend ->
            val name = BACKEND_NAMES.getValue(backend)
            OptionInstance.createBoolean(
                "options.frameGeneration.$name",
                { Tooltip.create(Component.translatable("options.frameGeneration.$name.tooltip")) },
                false,
            ) { on -> requestBackend(backend, on) }
        }

    /** 最后一次真正推给 native 的生成帧数。 */
    private var currentFrames: Int = DEFAULT_FRAMES

    /** 上一次真正下发过的后端 —— 关闭时必须把它关掉，而不是关掉「现在看起来最合适的那个」。 */
    private var pushedBackend: Int = MCDR_ABI.FG_BACKEND_DLSSG

    /** 可用性原因只在一个会话里打一次，别在滑条上来回拖时刷屏。 */
    private var warnedUnavailable: Boolean = false

    /** 能力表的缓存（按秒过期；见 [capsFor]）。 */
    private var capsCache: Map<Int, MCDR_ABI.FgCaps?> = emptyMap()
    private var capsEpochNs: Long = Long.MIN_VALUE

    /** 某一家在视频设置里的开关；后端号不认识时返回 null。 */
    @JvmStatic
    fun backendOption(backend: Int): OptionInstance<Boolean>? = BACKEND_OPTIONS[backend]

    /**
     * 开关被点：开 = 选中这家（其它两家自动关），关 = 取消选中。
     *
     * <p>选中即代表「帧生成开」，所以这里同时把模式切到 1 —— 界面上没有第二个总开关，
     * 三个厂商开关里最多亮一个，全灭就是不启用（用户 m03594 的要求）。
     */
    @JvmStatic
    fun requestBackend(backend: Int, on: Boolean) {
        if (syncingOptions) {
            return
        }
        if (on) {
            selectedBackend = backend
            currentMode = 1
        } else if (selectedBackend == backend) {
            selectedBackend = 0
            currentMode = 0
        } else {
            // 点的是本来就没选中那一家：把它的显示按回去，状态没变。
            syncOptions()
            return
        }
        syncOptions()
        push()
    }

    /** 三个开关的显示同步成真实状态（`OptionInstance.set` 会回调，用 [syncingOptions] 挡掉）。 */
    private fun syncOptions() {
        // `OptionInstance.set` 内部会问 `Minecraft.getInstance()`，构造期还是 null（P291m 踩过）。
        if (Minecraft.getInstance() == null) {
            return
        }
        syncingOptions = true
        try {
            BACKEND_OPTIONS.forEach { (backend, option) ->
                option.set(selectedBackend == backend && currentMode != 0)
            }
        } finally {
            syncingOptions = false
        }
    }

    @JvmStatic
    fun framesOption(): OptionInstance<Int> = FRAMES_OPTION

    /**
     * 后端能力表；`null` = 查不到（老 DLL 没有 ABI v4 的导出，或调用失败）。
     *
     * <p>能力表是 native 侧的**唯一事实来源** —— JVM 不再自己拼「有没有适配器 ⇒ 能不能插帧」
     * 这种判断（P300 之前的原罪）。
     *
     * <p>**按秒缓存**（与 `SuperResolution` 的 `cachedCaps` 同一体例，P281b）：设置页构造条目时
     * 会连着问很多次（`isAvailable()` 每个控件一次），而能力表只在「用户改设置」时有意义地变化 ——
     * 那种时刻 [push] 会调 [invalidateCaps]。
     */
    private fun capsFor(backend: Int): MCDR_ABI.FgCaps? {
        val now = System.nanoTime()
        if (capsEpochNs == Long.MIN_VALUE || now - capsEpochNs >= CAPS_TTL_NS) {
            capsCache = CANDIDATES.associateWith { queryCapsUncached(it) }
            capsEpochNs = now
        }
        return capsCache[backend]
    }

    private fun queryCapsUncached(backend: Int): MCDR_ABI.FgCaps? = try {
        MCDR_ABI.queryFgCaps(backend)
    } catch (t: Throwable) {
        IllusionLogger.debugMessage("queryFgCaps($backend) failed: $t")
        null
    }

    /** 让下一次 [capsFor] 重新问 native（用户刚改过设置，能力表可能因此变化）。 */
    private fun invalidateCaps() {
        capsEpochNs = Long.MIN_VALUE
    }

    /**
     * 当前会用的后端：**用户在视频设置里点开的那一家**（`0` = 一家都没选 ⇒ 帧生成关）。
     *
     * <p>为什么没有「自动挑」：帧生成不是能用就用的东西 —— 它改的是呈现链路，三家的观感、
     * 延迟和兼容性都不一样，选哪家是用户的决定。视频设置里每家的段内各有一个开关，点开
     * 一家另一家自动关（见 [requestBackend]），全部关掉就是不启用。
     */
    @JvmStatic
    fun activeBackend(): Int = selectedBackend

    /**
     * 是否在视频设置里显示帧生成的行。
     *
     * <p>判据 = 任一后端 `available` 或**运行库在**（实验性后端 available 还是 0，但用户
     * 需要能看见并打开它）；老 DLL 退回「有 D3D12 适配器」的老判据。
     */
    @JvmStatic
    fun isAvailable(): Boolean {
        var sawCaps = false
        for (backend in CANDIDATES) {
            val caps = capsFor(backend) ?: continue
            sawCaps = true
            if (caps.available) {
                return true
            }
        }
        return if (sawCaps) false else D3D12AdapterInfo.current != null
    }

    /**
     * 设置页上的一句话状态：`available` + native 给的原因。
     *
     * <p>只在打开设置页时调（会跨 ABI），别挂到每帧路径上。
     */
    @JvmStatic
    fun statusLine(): Component {
        val backend = activeBackend()
        // 一家都没选是**默认状态**，不是“ABI 太旧”。以前这里直接 `capsFor(0)` 拿到 null，
        // 于是显示 "native 库没有帧生成入口"，对着一个正常的安装报了个吓人的假故障。
        if (backend == 0) {
            return Component.translatable("dxr_mc.video.fg.off")
        }
        val caps = capsFor(backend) ?: return Component.translatable("dxr_mc.video.fg.noabi")
        return if (caps.available) {
            Component.translatable("dxr_mc.video.fg.available", caps.note)
        } else {
            Component.translatable("dxr_mc.video.fg.unavailable", caps.note)
        }
    }

    /**
     * 运行中的一句话状态：native 的 `mcdr_dx12FgStatus` 原文（`picked/applied/chain/mode/
     * rebuilds/tracks/latency` 全在里面）。
     *
     * <p>只在已开启时返回内容，免得设置页上挂一串 `[fg] picked=none`。
     */
    @JvmStatic
    fun runtimeLine(): Component {
        if (!isEnabled()) {
            return Component.empty()
        }
        return try {
            Arena.ofConfined().use { arena ->
                val buffer = arena.allocate(384L)
                val written = MCDR_ABI.D3D12FgStatus(buffer, 384)
                if (written <= 0) {
                    Component.empty()
                } else {
                    Component.literal(buffer.getString(0, Charsets.UTF_8).trim())
                }
            }
        } catch (t: Throwable) {
            Component.empty()
        }
    }

    /**
     * P300：native 侧把帧生成**回滚**了，而配置里仍然写着「开」时返回一句警告；其余情况返回 null。
     *
     * <p>回滚的真实场景：帧生成开着的时候把 SR 后端从 DLSS 换成 FSR/XeSS —— DLSS-G 的
     * `ScalingOutputColor` 只能来自 SL 的 SR 输出，换掉之后它再也拿不到合法输入，native 侧连续
     * 60 帧不可用就自己关掉并把期望值清成 0（`mcdr_dx12FgState` 的 `desiredMode`）。
     *
     * <p>为什么界面要管这件事：只看 `options.txt` 的话，行上还写着「开」、实际上什么都没插 ——
     * 这正是 P300 要消灭的「界面说假话」。**不改**用户的存档值：把选择权留给他，我们只把事实说清楚。
     */
    @JvmStatic
    fun rollbackLine(): Component? {
        if (!isEnabled()) {
            return null
        }
        val packed = MCDR_ABI.D3D12FgState()
        if (packed == Int.MIN_VALUE) {
            return null
        }
        if ((packed and 0xFF) != 0) {
            return null
        }
        val reason = capsFor(activeBackend())?.note?.takeIf { it.isNotBlank() } ?: "-"
        return Component.translatable("dxr_mc.video.fg.rolled_back", reason)
    }

    /** 当前模式（0 关 / 1 开）；「自动」由倍率档位 0 表达，见 [nativeMode]。 */
    @JvmStatic
    fun mode(): Int = currentMode

    /** 当前倍率档位：0 = 自动，1..5 = 2×..6×。 */
    @JvmStatic
    fun frames(): Int = currentFrames

    /** 帧生成是否开启（固定倍率与自动都算）。 */
    @JvmStatic
    fun isEnabled(): Boolean = currentMode != 0 && selectedBackend != 0

    /**
     * P306ac：这一帧能不能走「呈现世界层 + 后端合成 UI」的 hudless 路径。
     *
     * 三个条件缺一不可：用户选了 FSR-FG 且开着；**这一帧确实有 3D 世界层**（`level != null`，
     * 菜单 / 加载屏没有可插帧的世界层，那两处直通合成层）；后端自己说它现在就合成得了 UI
     * （`D3D12FgUiCompositionReady`：链已包 + UI 纹理已交过去）。
     *
     * <p>这里只回答「想不想」。真正的 latch 在
     * [dev.nts.expdxr.d3d12.render.advanced.smaa.D3D12GUISplit.armUiComposition]，
     * 那里在 present 之后裁决一次，保证同一帧里 SR、界面与呈现三者的选择一致。
     */
    @JvmStatic
    fun hudlessWanted(): Boolean = isEnabled()
        && selectedBackend == MCDR_ABI.FG_BACKEND_FSRFG
        && Minecraft.getInstance().level != null

    /** 下发给 native 的模式：0 关 / 1 固定倍率 / 2 动态（自动）。 */
    private fun nativeMode(): Int = when {
        !isEnabled() -> 0
        currentFrames <= MIN_FRAMES -> 2
        else -> 1
    }

    /** 下发给 native 的额外帧数；自动档固定 1（具体张数由后端与驱动决定）。 */
    private fun nativeFrames(): Int {
        if (currentFrames <= MIN_FRAMES) {
            return 1
        }
        // FSR-FG 的 SDK 只插 1 张，`numFrames` 给多少都是 2×。这里夹住，避免 native 侧按 3/4
        // 去报 `presented` 把状态行说成 3×/4×（设置界面已经不再给 FSR 显示倍率行）。
        if (activeBackend() == MCDR_ABI.FG_BACKEND_FSRFG) {
            return 1
        }
        return currentFrames
    }

    /**
     * 这次该往哪个后端下发：开启时选第一个可用的；关闭时选**上次真正下发的那个**。
     *
     * <p>关闭这一支必须是后者：如果上次生效的是别的后端，把 0 发给 DLSS-G 是关不掉的。
     */
    private fun backendForPush(): Int = if (isEnabled()) selectedBackend else pushedBackend

    private fun push() {
        // 用户刚改过设置（或刚 load 完），能力表可能因此变化 —— 先作废缓存再判。
        invalidateCaps()
        val backend = backendForPush()
        val caps = capsFor(backend)

        // P300（补 P281 的已知缺口 / 验收 A8）：配置文件可以把超分写成 FSR/XeSS 又把帧生成打开 ——
        // 界面会挡住，配置文件不会。这里只报「native 库根本没有帧生成入口」这一种结构性问题；
        // 「现在不可用」的原因交给 native 说：它会按 0.5 s 的节奏自己重试后端，过了宽限期
        // （300 帧 ≈ 5 s）还没成就由渲染线程打一行 warn（P296 的 `warnedNotUsable`）。
        // 为什么不在设置线程判：P296 实测（2026-10-27 13:06）`Options.load` 阶段 slInit 还没跑完，
        // 这时读到的 note 必然是 "needs Reflex, unavailable"，判一次就是一条假故障。
        if (isEnabled() && !warnedUnavailable && caps == null) {
            warnedUnavailable = true
            IllusionLogger.warnMessage(
                "frame generation is on but this native library has no frame-generation ABI"
            )
        }

        ensurePrerequisites(backend, caps)

        // P296：走后端无关入口（`mcdr_dx12FgSetMode`，ABI v4）。它只记录「期望的 mode」，
        // 真正下发 `slDLSSGSetOptions` 在渲染线程的 present 之前（官方文档 §6：生效点是
        // 下一次 Present），跨 0 边界的链重建也由它挂（D13）。
        val mode = nativeMode()
        val frames = nativeFrames()
        val viaFacade = pushThroughFgFacade(backend, mode, frames)
        if (viaFacade) {
            pushedBackend = backend
        } else {
            // 老 DLL 没有 ABI v4 的导出：退回 P213 的专用入口，行为与升级前一致。
            MCDR_ABI.D3D12SetDlssgMode(mode, frames)
            pushedBackend = MCDR_ABI.FG_BACKEND_DLSSG
        }
        // P306r：这一行与 native 的 `P306 setMode` 成对 —— 两边都出现才说明链路通；
        // 只有这一行没有 native 那行，就是门面失败（下面 `warnMessage` 会说明原因）。
        IllusionLogger.infoMessage(
            "frame generation -> native: backend=${BACKEND_NAMES[backend] ?: backend} "
                + "mode=$mode frames=$frames viaFacade=$viaFacade"
        )
    }

    /**
     * 后端私有的前置条件 —— 整个仓库里唯一知道「今天这些条件是 Reflex 与 DLSS」的地方。
     *
     * <p>能力位怎么读：`needsReflex` = 该后端要求系统级低延迟可用（DLSS-G 是硬要求：
     * SDK 会因为 Reflex 没开而在 `shouldInterpolate` 里静默禁用插帧）；
     * 而「帧生成的输入是超分输出」这件事只对走 Streamline 的那家成立，所以这里单独按
     * [MCDR_ABI.FG_BACKEND_DLSSG] 判，不伪造成通用能力位。
     */
    private fun ensurePrerequisites(backend: Int, caps: MCDR_ABI.FgCaps?) {
        if (!isEnabled()) {
            return
        }
        if (caps != null && caps.needsReflex && NvidiaReflex.mode() == 0) {
            NvidiaReflex.forceOn()
        }
        if (backend == MCDR_ABI.FG_BACKEND_DLSSG && !DeepLearningSuperSampling.isEnabled()) {
            // DLSS-G 的 HUDless 与输出色都来自 SL 的超分结果，关着超分就没有输入。
            DeepLearningSuperSampling.option().set(true)
        }
    }

    /**
     * 把「后端 + 模式 + 倍率」交给 ABI v4 的帧生成门面。
     *
     * <p>只填三项（后端号 / 模式 / 生成帧数）。`Arena` 分配的内存默认清零，所以「只写三项」
     * 即是完整载荷；其余字段是逐帧数据（尺寸、纹理句柄、帧号），由 native 在 present 时自己
     * 取 —— 本项目的 FG 输入就是 SR 的输出，JVM 没有独立资源可给。
     *
     * <p>`RESET` 位这里填「关闭时为 1」：v1 的 DLSS-G provider 不消费它（Streamline 自己管
     * 帧号），它是留给 FSR-FG / XeSS-FG 的语义 —— 那两家要求后端帧号严格单调 +1，重开时必须清轨。
     *
     * @return true = 新入口可用且已被调用；false = 老 DLL，调用方应回退到 P213 的入口。
     */
    private fun pushThroughFgFacade(backend: Int, mode: Int, frames: Int): Boolean {
        val result = try {
            Arena.ofConfined().use { arena ->
                val desc = arena.allocate(MCDRLayout.FG_DISPATCH)
                desc.set(JAVA_INT, MCDRLayout.FG_DISPATCH_BACKEND, backend)
                desc.set(JAVA_INT, MCDRLayout.FG_DISPATCH_MODE, mode)
                desc.set(JAVA_INT, MCDRLayout.FG_DISPATCH_NUM_FRAMES, frames)
                desc.set(JAVA_INT, MCDRLayout.FG_DISPATCH_RESET, if (mode == 0) 1 else 0)
                MCDR_ABI.D3D12FgSetMode(desc)
            }
        } catch (t: Throwable) {
            // P306r：这条以前只写 System.err —— 玩家从日志里看不到，等于静默降级。
            IllusionLogger.warnMessage("frame generation: ABI v4 push failed: $t")
            Int.MIN_VALUE
        }
        if (result == Int.MIN_VALUE) {
            IllusionLogger.warnMessage(
                "frame generation: native frame-generation entry point is missing; "
                    + "falling back to the DLSS-G-only path"
            )
        }
        return result != Int.MIN_VALUE
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
        // 旧配置里的 "dynamic" 现在等价于「开 + 倍率最低档（自动）」，读回来时做一次迁移。
        if (rawMode == "dynamic") {
            currentMode = 1
            currentFrames = MIN_FRAMES
        }
        // 选中的后端；名字不认识就是"一家都没选"，帧生成随之关掉。
        val rawBackend = entries[BACKEND_KEY]?.trim()?.removeSurrounding("\"")?.lowercase().orEmpty()
        selectedBackend = BACKEND_BY_NAME[rawBackend] ?: 0
        if (selectedBackend == 0) {
            currentMode = 0
        }
        // 绕开 OptionInstance 的回调（`Options.load` 期间不触发），直接把值塞回选项对象。
        OPTION.set(currentMode)
        FRAMES_OPTION.set(currentFrames)
        syncOptions()
        // P306r：用户报"设置开了但没生效"时，这一行就是"应用侧读到了什么"的唯一证据。
        IllusionLogger.infoMessage(
            "frame generation loaded: mode=${currentMode} frames=${currentFrames} "
                + "backend=${BACKEND_NAMES[selectedBackend] ?: "none"}"
        )
        push()
    }

    /** 写回 `options.txt`。 */
    @JvmStatic
    fun save(file: File) {
        writeOption(file, OPTIONS_KEY, currentMode.toString())
        writeOption(file, FRAMES_KEY, currentFrames.toString())
        writeOption(file, BACKEND_KEY, BACKEND_NAMES[selectedBackend].orEmpty())
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
