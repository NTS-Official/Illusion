package dev.nts.expdxr.dxrmc.framegen

import com.mojang.serialization.Codec
import dev.nts.expdxr.d3d12.extern.MCDRLayout
import dev.nts.expdxr.d3d12.extern.MCDR_ABI
import dev.nts.expdxr.d3d12.render.adapter.D3D12AdapterInfo
import dev.nts.expdxr.dxrmc.nvidia.reflex.NvidiaReflex
import dev.nts.expdxr.dxrmc.sr.dlss.DeepLearningSuperSampling
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
 * 帧生成的后端无关门面：只做策略、持久化、文案与 `fgSetMode` 推送；一帧的活全在 native。
 * 后端私有前置条件收敛在 [ensurePrerequisites]；时序与不变量见 docs/framegen/README.md §2、§6。
 */
object FrameGeneration {

    // 三个 options.txt 键名不改：改名等于丢掉老用户的设置；BACKEND_KEY 空串 = 一家都没选。
    const val OPTIONS_KEY: String = "dxrMcFrameGeneration"

    const val FRAMES_KEY: String = "dxrMcFrameGenerationFrames"

    const val BACKEND_KEY: String = "dxrMcFrameGenBackend"

    /** 下标即下发给 native 的模式值（0 关 / 1 开），不得重排。 */
    private val MODE_NAMES: List<String> = listOf("off", "on")

    private const val DEFAULT_MODE: Int = 0

    /** 档位：0 = 自动（动态倍率），1..5 = 2×..6×；上限对齐 DLSS-G 的 `numFramesToGenerateMax`。 */
    private const val MIN_FRAMES: Int = 0
    private const val MAX_FRAMES: Int = 5
    private const val DEFAULT_FRAMES: Int = 1

    /** 单位纳秒；能力表走跨 ABI 调用，设置页构造期会连问多次。 */
    private const val CAPS_TTL_NS: Long = 1_000_000_000L

    /** 批量查能力表与判可见性用的全部后端；枚举值见 docs/framegen/README.md §5.3。 */
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

    private var currentMode: Int = DEFAULT_MODE

    /** 用户选中的后端；`0` = 一家都没选 ⇒ 帧生成关。 */
    private var selectedBackend: Int = 0

    /** `OptionInstance.set` 会回调进 [requestBackend]，同步显示时必须挡掉。 */
    private var syncingOptions: Boolean = false

    /** 后端号 ↔ `options.txt` 里的短名（不改）。 */
    private val BACKEND_NAMES: Map<Int, String> = linkedMapOf(
        MCDR_ABI.FG_BACKEND_DLSSG to "dlssg",
        MCDR_ABI.FG_BACKEND_FSRFG to "fsrfg",
        MCDR_ABI.FG_BACKEND_XESSFG to "xessfg",
    )
    private val BACKEND_BY_NAME: Map<String, Int> =
        BACKEND_NAMES.entries.associate { (backend, name) -> name to backend }

    /** 每家一个开关；点开一家自动关掉其它（三家改的是同一条呈现链路）。 */
    private val BACKEND_OPTIONS: Map<Int, OptionInstance<Boolean>> =
        BACKEND_NAMES.keys.associateWith { backend ->
            val name = BACKEND_NAMES.getValue(backend)
            OptionInstance.createBoolean(
                "options.frameGeneration.$name",
                { Tooltip.create(Component.translatable("options.frameGeneration.$name.tooltip")) },
                false,
            ) { on -> requestBackend(backend, on) }
        }

    private var currentFrames: Int = DEFAULT_FRAMES

    /** 上次真正下发过的后端：关闭时必须发给它，发给别人关不掉。 */
    private var pushedBackend: Int = MCDR_ABI.FG_BACKEND_DLSSG

    private var warnedUnavailable: Boolean = false

    private var capsCache: Map<Int, MCDR_ABI.FgCaps?> = emptyMap()
    private var capsEpochNs: Long = Long.MIN_VALUE

    /** 某一家在视频设置里的开关；后端号不认识时返回 null。 */
    @JvmStatic
    fun backendOption(backend: Int): OptionInstance<Boolean>? = BACKEND_OPTIONS[backend]

    /** 点开关：开 = 选中这家（互锁关掉其它家），关 = 取消选中。
     * 选中即代表「帧生成开」，这里同时把模式切到 1；界面上没有第二个总开关，全灭 = 不启用。 */
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
            // 状态没变，只把显示按回去，不 push。
            syncOptions()
            return
        }
        syncOptions()
        push()
    }

    /** 把三个开关的显示同步成真实状态；回调由 [syncingOptions] 挡掉。 */
    private fun syncOptions() {
        // 构造期 `Minecraft.getInstance()` 还是 null，`OptionInstance.set` 会 NPE。
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

    /** 能力表；`null` = 查不到（老 DLL 没有该导出，或调用失败）。它是可用性的唯一事实来源。
     * 按 [CAPS_TTL_NS] 缓存：设置页构造条目时会连问多次，而它只在改设置时有意义地变化。 */
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

    /** 用户刚改过设置，能力表可能因此变化。 */
    private fun invalidateCaps() {
        capsEpochNs = Long.MIN_VALUE
    }

    /** 当前会用的后端 = 用户在视频设置里点开的那一家；`0` = 一家都没选 ⇒ 帧生成关。
     * 不做自动挑后端：三家观感、延迟、兼容都不同，选哪家是用户的决定。 */
    @JvmStatic
    fun activeBackend(): Int = selectedBackend

    /** 是否显示帧生成设置行：任一后端 `available`，或运行库在场（实验性后端此时 available 仍为 0）。
     * 老 DLL 没有能力表时才退回「有 D3D12 适配器」。 */
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

    /** 设置页状态行 = `available` + native 的 note；会跨 ABI，只在打开设置页时调。 */
    @JvmStatic
    fun statusLine(): Component = statusLine(activeBackend())

    /** 某一家的状态行：厂商子页各问自己那一家，不跟着"当前选中的后端"走。 */
    @JvmStatic
    fun statusLine(backend: Int): Component {
        // 一家都没选是默认状态，不是「ABI 太旧」——别去查 capsFor(0)。
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

    /** 这一家是否明确不可用；查不到能力表（老 DLL）时不算不可用。 */
    @JvmStatic
    fun isBackendUnavailable(backend: Int): Boolean = capsFor(backend)?.available == false

    /** 某一家的运行状态行：只有该家真的开着才有内容。 */
    @JvmStatic
    fun runtimeLine(backend: Int): Component =
        if (isEnabled() && activeBackend() == backend) runtimeLine() else Component.empty()

    /** 运行中状态行：native 原文（picked/applied/chain/mode/rebuilds/tracks/latency）。
     * 未开启时返回空，免得设置页挂一串空状态。 */
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

    /** native 已回滚而设置仍写着「开」时返回警告，其余 null；不改用户的存档值，只把事实说清楚。 */
    // 判据 = fgState 的 desiredMode 位为 0，位布局见 docs/framegen/README.md §2.6。
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

    /** 当前模式（0 关 / 1 开）；「自动」由倍率档位 0 表达。 */
    @JvmStatic
    fun mode(): Int = currentMode

    /** 倍率档位：0 = 自动，1..5 = 2×..6×。 */
    @JvmStatic
    fun frames(): Int = currentFrames

    /** 帧生成是否开启（固定倍率与自动都算）。 */
    @JvmStatic
    fun isEnabled(): Boolean = currentMode != 0 && selectedBackend != 0

    /**
     * 本帧是否走 hudless（只呈现世界层、UI 由后端合成）：仅 FSR-FG 需要，且本帧必须有 3D 世界层
     * （菜单 / 加载屏直通合成）。这里只回答「想不想」，真正的 latch 在
     * [dev.nts.expdxr.d3d12.render.advanced.smaa.D3D12GUISplit.armUiComposition]（present 之后裁决一次）。
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

    /** 下发给 native 的额外帧数；自动档固定 1。 */
    private fun nativeFrames(): Int {
        if (currentFrames <= MIN_FRAMES) {
            return 1
        }
        // FSR-FG 只插 1 张，给多少都是 2×；不夹住会让状态行报成 3×/4×。
        if (activeBackend() == MCDR_ABI.FG_BACKEND_FSRFG) {
            return 1
        }
        return currentFrames
    }

    /** 下发给谁：开启时给选中的后端，关闭时给上次真正下发的那个（发给别人关不掉）。 */
    private fun backendForPush(): Int = if (isEnabled()) selectedBackend else pushedBackend

    private fun push() {
        // 先作废能力表缓存：用户刚改过设置，能力表可能因此变化。
        invalidateCaps()
        val backend = backendForPush()
        val caps = capsFor(backend)

        // 只报「这个 native 库根本没有帧生成入口」；「暂时不可用」交给 native 自己重试并升级为警告。
        // 别在这里判可用性：Options.load 阶段 slInit 还没跑完，读到的 note 必然是假故障。
        if (isEnabled() && !warnedUnavailable && caps == null) {
            warnedUnavailable = true
            IllusionLogger.warnMessage(
                "frame generation is on but this native library has no frame-generation ABI"
            )
        }

        ensurePrerequisites(backend, caps)

        // 走后端无关入口（ABI v4）：只记录期望值，真正下发在渲染线程 Present 之前。
        val mode = nativeMode()
        val frames = nativeFrames()
        val viaFacade = pushThroughFgFacade(backend, mode, frames)
        if (viaFacade) {
            pushedBackend = backend
        } else {
            // 老 DLL 没有 ABI v4 导出：退回 DLSS-G 专用入口。
            MCDR_ABI.D3D12SetDlssgMode(mode, frames)
            pushedBackend = MCDR_ABI.FG_BACKEND_DLSSG
        }
        // 与 native 那行成对：只有这一行、没有 native 的，就是门面失败。
        IllusionLogger.infoMessage(
            "frame generation -> native: backend=${BACKEND_NAMES[backend] ?: backend} "
                + "mode=$mode frames=$frames viaFacade=$viaFacade"
        )
    }

    /** 后端私有前置条件：`needsReflex` 的后端强制开 Reflex（不看能力位之外的推断）。
     * DLSS-G 另需 DLSS 超分开着 —— 它的 HUDless 与输出色都来自 SL 的超分结果。 */
    private fun ensurePrerequisites(backend: Int, caps: MCDR_ABI.FgCaps?) {
        if (!isEnabled()) {
            return
        }
        if (caps != null && caps.needsReflex && NvidiaReflex.mode() == 0) {
            NvidiaReflex.forceOn()
        }
        if (backend == MCDR_ABI.FG_BACKEND_DLSSG && !DeepLearningSuperSampling.isEnabled()) {
            // DLSS-G 的输入是 SL 的超分输出，关着超分就没有输入。
            DeepLearningSuperSampling.option().set(true)
        }
    }

    /**
     * 填 `mcdr_fg_dispatch` 的三项（后端 / 模式 / 帧数）。`Arena` 内存已清零，其余逐帧字段由 native 自取。
     * `RESET` 位 = 「本次是关闭」：包装型后端要求后端帧号严格 +1，重开时必须清轨。
     * @return true = ABI v4 入口可用；false = 老 DLL，调用方回退到 DLSS-G 专用入口。
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
            // 必须走 IllusionLogger：只写 System.err 玩家日志里看不到，等于静默降级。
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

    /** 从 `options.txt` 读回（认名字与数字两种写法），并立刻推给 native。 */
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
        // 旧配置的 "dynamic" 迁移成「开 + 档位 0」。
        if (rawMode == "dynamic") {
            currentMode = 1
            currentFrames = MIN_FRAMES
        }
        // 名字不认识 = 一家都没选，帧生成随之关掉。
        val rawBackend = entries[BACKEND_KEY]?.trim()?.removeSurrounding("\"")?.lowercase().orEmpty()
        selectedBackend = BACKEND_BY_NAME[rawBackend] ?: 0
        if (selectedBackend == 0) {
            currentMode = 0
        }
        // Options.load 期间 set 不触发回调，这里绕开它直接塞值。
        OPTION.set(currentMode)
        FRAMES_OPTION.set(currentFrames)
        syncOptions()
        // 「设置开了但没生效」时，这行是应用侧读到什么的唯一证据。
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
