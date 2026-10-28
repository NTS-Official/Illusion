package dev.nts.expdxr.d3d12.render.advanced.vrs

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.serialization.Codec
import dev.nts.expdxr.Illusion
import dev.nts.expdxr.d3d12.render.adapter.D3D12AdapterInfo
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Optional
import net.minecraft.client.Minecraft
import net.minecraft.client.OptionInstance
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.network.chat.Component
import net.minecraft.util.Mth

/**
 * 渲染缩放：把"渲染多少像素"与"显示多少像素"解耦 —— 主渲染目标仍按窗口尺寸**记账**，实际纹理按本百分比
 * 缩放（`MainTargetRenderScaleMixin`）；世界层辅助目标必须按同一比例分配（[worldLayerSize]）。
 * 界面按窗口 1:1 渲染，不参与缩放。取值是视频设置第四组"Illusion 设置"里的滑条，持久化在 `options.txt`。
 * 见 docs/mesh&vrs/README.md §4。
 */
object D3D12RenderScale {
    /** 百分比下限（非 DLSS 模式）：再低没有可读画面。 */
    private const val MIN_PERCENT = 25

    /** 百分比上限：400% 已是 16 倍像素。 */
    private const val MAX_PERCENT = 400

    /** 滑条步进 25%（25/50/…/400 共 16 档）。 */
    private const val STEP_PERCENT = 25

    /** 默认 100 = 完全不缩放。 */
    private const val DEFAULT_PERCENT = 100

    // DLSS 模式有自己的取值集合与持久化键

    /**
     * DLSS 模式的取值集合 25..100、步进 1：DLSS 开着时滑条被挡位绑定成只读（挡位是 33/50/58/67），
     * 1% 步进才取得到这些值，它只承担"显示当前输入比例"。
     */
    private const val DLSS_MIN_PERCENT = 25
    private const val DLSS_MAX_PERCENT = 100
    private const val DLSS_STEP_PERCENT = 1

    /** DLSS 模式默认 58%（"平衡"挡位的输入比例），与 [DeepLearningSuperSampling.QUALITY_DEFAULT] 一致。 */
    private const val DLSS_DEFAULT_PERCENT = 58

    /** 原版 `options.txt` 里的键名；与 `dxrMcAaMode`/`dxrMcShadingRate` 同一套管道。 */
    const val OPTIONS_KEY: String = "dxrMcRenderScale"

    /** DLSS 模式自己的键：两种模式各自记住上次调的值。 */
    const val DLSS_OPTIONS_KEY: String = "dxrMcRenderScaleDlss"

    /** 0 = 非 DLSS（25..400 步进 25），1 = DLSS（25..100 步进 1）。 */
    private const val MODE_PLAIN = 0
    private const val MODE_DLSS = 1

    private val MODE_KEYS = arrayOf(OPTIONS_KEY, DLSS_OPTIONS_KEY)
    private val MODE_DEFAULTS = intArrayOf(DEFAULT_PERCENT, DLSS_DEFAULT_PERCENT)
    private val MODE_STEPS = intArrayOf(STEP_PERCENT, DLSS_STEP_PERCENT)

    /** 当前模式，由 DLSS 侧经 [setDlssMode] 置位；只在客户端 / 渲染线程读写，`@Volatile` 足够。 */
    @Volatile
    private var mode: Int = MODE_PLAIN

    /** 每个模式记住的值（下标同 [MODE_KEYS]）：两个 `options.txt` 键在内存里的镜像。 */
    private val values = intArrayOf(DEFAULT_PERCENT, DLSS_DEFAULT_PERCENT)

    /** 100 = 完全不缩放（默认）。 */
    @Volatile
    private var percent: Int = 100

    /**
     * 滑条需要重新同步的代号：由本类自己改值时自增。vanilla 的滑条只在构造与 `onRelease` 时对齐取值，
     * 而 `OPTION.set` 值不变就不触发回调 —— 否则滑条停在旧位置、标题却按新范围算，显示值与生效值无关。
     * 界面每 tick 比对本代号并调 `OptionsList.resetOption`（本类不认识界面对象，依赖保持单向）。
     */
    @Volatile
    private var syncGeneration: Int = 0

    /** 界面据此判断"该重新同步滑条了"。只增不减。 */
    @JvmStatic
    fun syncGeneration(): Int = syncGeneration

    /** 界面把滑条重新对齐到生效值之后打一行：滑条数字对不上时先看它。 */
    @JvmStatic
    fun logSliderResync() {
        Illusion.LOGGER.info(
            "render-scale slider re-synced to {}% ({} mode, range {}..{}%, step {}%)",
            percent, if (mode == MODE_DLSS) "DLSS" else "plain", minOf(), maxOf(), stepOf(),
        )
    }

    private fun minOf(): Int = if (mode == MODE_DLSS) DLSS_MIN_PERCENT else MIN_PERCENT

    /** 当前模式下允许的最小百分比（供超分门面算"抬到多少才够"时用）。 */
    @JvmStatic
    fun minimumPercent(): Int = minOf()

    private fun maxOf(): Int = if (mode == MODE_DLSS) DLSS_MAX_PERCENT else MAX_PERCENT

    private fun stepOf(): Int = MODE_STEPS[mode]

    @JvmStatic
    fun setPercent(value: Int) {
        // DLAA 锁定是**最高优先级**，必须比模式夹取更早生效：否则 `apply` → `store` → 这里会把 100 夹回
        // DLSS 模式的上限，主目标按错误比例分配、native 拒绝 DLAA 并每帧重建 NGX 特征。
        // 所有路径（滑条、`setDlssMode`、`load`、`store`）最终都走这里，所以锁只能在这里生效。
        percent = if (dlaaLock) DEFAULT_PERCENT else value.coerceIn(minOf(), maxOf())
    }

    @JvmStatic
    fun percent(): Int = percent

    /** 当前模式下记住的原始值（不夹取），`Options.save` 用它写两个键。 */
    @JvmStatic
    fun storedPlain(): Int = values[MODE_PLAIN]

    @JvmStatic
    fun storedDlss(): Int = values[MODE_DLSS]

    /** 是否走 DLSS 模式下的取值集合（取证用）。 */
    @JvmStatic
    fun isDlssMode(): Boolean = mode == MODE_DLSS

    /**
     * DLSS 开关切换时调用：只置 `@Volatile` 标志，绝不反向初始化 DLSS 对象（否则与该类形成类初始化
     * 顺序耦合 —— 两者谁先谁后由 mixin 注入顺序决定）。切换动作 = 当前模式的值存回自己的键 → 载入目标
     * 模式的值 → 应用并打一行取证日志；**不自动恢复"开 DLSS 前的值"**，两个模式各记自己的键。
     * @return true = 模式真的变了（调用方据此决定是否打日志）
     */
    @JvmStatic
    fun setDlssMode(active: Boolean): Boolean {
        val target = if (active) MODE_DLSS else MODE_PLAIN
        if (target == mode) {
            return false
        }
        // 1) 当前模式的值存回自己的键（DLAA 锁定期间 `OPTION` 是 100，覆盖记忆会丢掉用户原值）。
        if (!dlaaLock) {
            values[mode] = OPTION.get()
        }
        // 2) 载入目标模式的值（锁定期间只留在记忆里，不生效）。
        val restored = values[target]
        mode = target
        // DLAA 锁定期间缩放必须恒为 100：让 DLSS 模式的上限把它夹走会让 render != target，
        // native 的 DLAA 分支直接拒绝。模式切换照做，取值交给 DLAA 锁。
        if (dlaaLock) {
            // 补齐状态 + 按需重建 + 让滑条重新同步，全在 [setProgrammatically] 里。
            setProgrammatically(DEFAULT_PERCENT)
            logModeSwitch()
            return true
        }
        // vanilla 的 `OptionInstance.set` 在 `Minecraft.isRunning()` 为假时只改值、不触发回调，而
        // `Options.load` 正跑在 `Minecraft` 构造里。所以两条路都走完：游戏在跑时 `OPTION.set` 会触发
        // `apply`，否则由 [store] 顶上（主目标此刻还不存在，不需要重建）。
        setProgrammatically(restored)
        logModeSwitch()
        return true
    }

    /** [setDlssMode] / [setDlaaLock] 里"我们自己改值"的统一入口（P76i）。 */
    private fun setProgrammatically(value: Int) {
        val appliesBefore = applyCount
        OPTION.set(value)
        val effective = denormalise(value)
        if (percent != effective) {
            // 回调没来（值没变 ⇒ vanilla 的 `set` 直接返回；或游戏还没跑起来 ⇒ 只改值）。
            store(effective)
        }
        // 值没变时 vanilla 完全不触发回调，主目标不会重建，但 AA / DLSS 已换模式 —— 这里补一次重建。
        if (applyCount == appliesBefore) {
            rebuildMainTarget()
        }
        syncGeneration++
    }

    /** `apply` 被调用次数：判断 `OptionInstance.set` 有没有触发回调（值没变 / 游戏没跑都不调）。 */
    @Volatile
    private var applyCount: Int = 0

    /** [setDlssMode] 的取证行：两个键与生效值一起打出来。 */
    private fun logModeSwitch() {
        Illusion.LOGGER.info(
            "renderScale range follows the DLSS mode: {} ({}..{}, step {}%); effective {}%, " +
                "keys {}={} / {}={}",
            if (mode == MODE_DLSS) "DLSS on" else "DLSS off",
            minOf(), maxOf(), stepOf(), percent,
            OPTIONS_KEY, values[MODE_PLAIN], DLSS_OPTIONS_KEY, values[MODE_DLSS],
        )
    }

    /** 把当前值写回**当前模式**的键；另一模式的格子不动。 */
    private fun storeValue(value: Int) {
        values[mode] = value
    }

    /** 是否真的在缩放；false 时调用方可以整条短路。 */
    @JvmStatic
    fun isEnabled(): Boolean = percent != 100

    /**
     * 滑条取值域（形状照 vanilla 的 `OptionInstance.IntRange`）：`fromSliderValue` 吸附到当前步进的倍数
     * （[snap]）并夹到首尾；`next`/`previous` 必须走一整档，否则原版的 `current ± 1` 会被吸附回原值、
     * 方向键看起来没反应。`toSliderValue` 与原版逐字一致。
     */
    private object PercentRange : OptionInstance.SliderableValueSet<Int> {
        override fun validateValue(value: Int): Optional<Int> = Optional.of(denormalise(value))

        override fun codec(): Codec<Int> = Codec.intRange(MIN_PERCENT, MAX_PERCENT)

        override fun toSliderValue(value: Int): Double =
            // DLAA 锁定时取值恒为 100，落在 DLSS 模式的 50..90 之外；直接贴到最右端。
            if (dlaaLock) 1.0 else when (value) {
                minOf() -> 0.0
                maxOf() -> 1.0
                else -> Mth.map(value + 0.5, minOf().toDouble(), maxOf() + 1.0, 0.0, 1.0)
            }

        override fun fromSliderValue(slider: Double): Int {
            val raw = Mth.floor(
                Mth.map(slider.coerceIn(0.0, 1.0), 0.0, 1.0, minOf().toDouble(), maxOf() + 1.0),
            )
            return denormalise(raw)
        }

        override fun next(current: Int): Optional<Int> =
            Optional.of(
                if (dlaaLock) DEFAULT_PERCENT
                else (current + stepOf()).coerceAtMost(maxOf()),
            )

        override fun previous(current: Int): Optional<Int> =
            Optional.of(
                if (dlaaLock) DEFAULT_PERCENT
                else (current - stepOf()).coerceAtLeast(minOf()),
            )
    }

    /**
     * DLAA 要求**渲染分辨率 == 目标分辨率**，所以选中时把缩放强制为 100，且滑条怎么拖都只给出 100
     * （[denormalise]）。置灰而不隐藏：控件是建界面时按 `PercentRange` 造好的，隐藏要走 `OptionsList` 重建。
     */
    @Volatile
    private var dlaaLock: Boolean = false

    /** 界面临时置灰的依据。 */
    @JvmStatic
    fun isLockedByDlaa(): Boolean = dlaaLock

    /**
     * 选中/离开 DLAA 时调用：进入时立刻置 100（走正常 setter，会重建主目标），离开时恢复锁定前该模式的值
     * （DLAA 期间 [apply] 不写回记忆）。
     */
    @JvmStatic
    fun setDlaaLock(active: Boolean) {
        if (dlaaLock == active) {
            return
        }
        dlaaLock = active
        Illusion.LOGGER.info(
            "DLAA {} render-scale lock; DLAA needs render == target, so "
                + "the scale is {}100%",
            if (active) "engaged the" else "released the", if (active) "pinned to " else "free from ")
        if (active) {
            val before = percent
            setProgrammatically(DEFAULT_PERCENT)
            // 回调不会来时（游戏没跑 / 值没变）补一行显式读数：DLAA 的验收判据要求看到 `render scale = 100%`。
            if (percent == before) {
                logScaleState()
            }
        } else {
            val restored = denormalise(values[mode])
            val before = percent
            setProgrammatically(restored)
            if (percent == before) {
                logScaleState()
            }
        }
    }

    /** 把滑条给出的原始值规整成当前允许的取值：DLAA 锁定恒为 100，否则按模式步进吸附并夹取。 */
    private fun denormalise(value: Int): Int =
        if (dlaaLock) 100 else snap(value).coerceIn(minOf(), maxOf())

    /** 吸附到当前步进的最近一档（`minOf()` 必是步进的倍数，按步进取整即可）。 */
    private fun snap(value: Int): Int {
        val step = stepOf()
        return ((value + step / 2) / step) * step
    }

    /**
     * 视频设置第四组"Illusion 设置"里的渲染缩放滑条：标题由 `toString` 自带（用原版 `options.percent_value`），
     * 回调即写状态 + 打日志 + 让主目标按新比例重建。
     */
    private val OPTION: OptionInstance<Int> = OptionInstance(
        "options.renderScale",
        { Tooltip.create(Component.translatable("options.renderScale.tooltip")) },
        { caption, value -> Component.translatable("options.percent_value", caption, value) },
        PercentRange,
        DEFAULT_PERCENT,
        { value -> apply(value) },
    )

    @JvmStatic
    fun option(): OptionInstance<Int> = OPTION

    /** 界面里的当前取值，也是写回 `options.txt` 的值。 */
    @JvmStatic
    fun current(): Int = OPTION.get()

    /** 非 D3D12 后端时按钮置灰。 */
    @JvmStatic
    fun isAvailable(): Boolean = D3D12AdapterInfo.current != null

    /** 选项回调：记状态 → 日志 → 主目标按新比例重建；只改**当前模式**的键。 */
    private fun apply(value: Int) {
        applyCount++
        // DLAA 锁定期间 `OPTION` 是 100、不是用户值：写回记忆会让退出 DLAA 恢复成 100。
        if (!dlaaLock) {
            storeValue(value)
        }
        store(value)
        rebuildMainTarget()
    }

    /** 只记状态与日志，不碰渲染目标（`Options.load` 走这条，主目标会按新比例创建）。 */
    private fun store(value: Int) {
        setPercent(value)
        logScaleState()
    }

    /** 当前生效缩放的一行取证日志：[store] 与 DLAA 锁定/释放共用，避免只读到"模式范围"却看不到生效值。 */
    private fun logScaleState() {
        if (percent == DEFAULT_PERCENT) {
            if (dlaaLock) {
                Illusion.LOGGER.info(
                    "[${Illusion.MODID}] render scale = 100% (DLAA lock: render == target is required, "
                        + "so the scale stays pinned until DLAA is released)",
                )
            } else {
                Illusion.LOGGER.info(
                    "[${Illusion.MODID}] render scale off: the world is shaded at window resolution")
            }
        } else {
            Illusion.LOGGER.info(
                "[${Illusion.MODID}] render scale = $percent%; the world's render target is created at that " +
                    "fraction of the window and the GUI keeps a window-sized target of its own",
            )
        }
    }

    /**
     * 让主目标按新比例重建，且**必须排到帧边界**：`MainTarget` 只在逻辑尺寸变化时重建纹理，而百分比不改
     * 逻辑尺寸，所以要主动调一次 `GameRenderer.resize`；`Minecraft.execute` 在渲染线程上会内联执行，就地
     * resize 会关掉本帧仍在使用的深度纹理（`Depth texture is closed`）。`Minecraft.schedule` 只入队，由
     * `runTick` 的 `runAllTasks` 在两帧之间执行 —— 滑条回调与 DLSS 自愈都走这里。100% 也要走：从 150%
     * 改回 100% 同样是一次"逻辑尺寸没变但附件必须重建"。
     */
    @JvmStatic
    fun scheduledRebuild() {
        rebuildMainTarget()
    }

    /**
     * 帧边界上按新比例重建（供渲染线程内的调用方使用）：帧中间直接改 [setPercent] 只改字段，主目标附件仍是
     * 旧比例，而本帧后半段创建的世界层目标已按新比例分配 → `Depth texture: size does not match`。
     */
    @JvmStatic
    fun setPercentAtFrameBoundary(value: Int) {
        val minecraft = try {
            Minecraft.getInstance()
        } catch (t: Throwable) {
            null
        }
        if (minecraft == null) {
            return
        }
        minecraft.schedule { setProgrammatically(value) }
    }
    private fun rebuildMainTarget() {
        val minecraft = try {
            Minecraft.getInstance()
        } catch (t: Throwable) {
            null
        }
        if (minecraft == null) {
            Illusion.LOGGER.info(
                "[${Illusion.MODID}] the client is not up yet; the main render target will be created at " +
                    "$percent% of the window.",
            )
            return
        }
        try {
            // 只入队，**不用** `execute`：后者在渲染线程上会内联执行（见上面的说明）。
            minecraft.schedule(
                Runnable {
                    try {
                        val window = minecraft.window
                        minecraft.gameRenderer.resize(window.width, window.height)
                        Illusion.LOGGER.info(
                            "[${Illusion.MODID}] main render target re-created for the new render scale.",
                        )
                    } catch (t: Throwable) {
                        Illusion.LOGGER.warn(
                            "[${Illusion.MODID}] could not re-create the main render target now ($t); the new render " +
                                "scale applies on the next window resize or restart.",
                        )
                    }
                },
            )
        } catch (t: Throwable) {
            Illusion.LOGGER.warn(
                "[${Illusion.MODID}] could not schedule the main render target refresh ($t); the new render " +
                    "scale applies on the next window resize or restart.",
            )
        }
    }

    /**
     * 从 `options.txt` 读回两个键（行格式 `key:value`）；文件里没有这行也要记一次默认值。这里**不**重建主目标：
     * `Options.load` 发生在 `Minecraft` 构造里（`gameRenderer` 还是 null），主目标本来就会按新比例创建。
     */
    @JvmStatic
    fun load(file: File) {
        if (!file.isFile) {
            return
        }
        try {
            val lines = file.readLines(StandardCharsets.UTF_8)
            // 两个键都读进内存镜像，只有**当前模式**的值会应用。
            for (index in MODE_KEYS.indices) {
                val key = MODE_KEYS[index]
                for (line in lines) {
                    if (!line.startsWith("$key:")) {
                        continue
                    }
                    val value = line.substring(key.length + 1).trim().toIntOrNull()
                    if (value != null) {
                        values[index] = value
                    }
                    break
                }
            }
        } catch (t: Throwable) {
            Illusion.LOGGER.warn("[${Illusion.MODID}] could not read $OPTIONS_KEY from $file", t)
            return
        }
        // 当前模式下记住的值吸附/夹取后生效；DLAA 锁定期间不论文件里是多少都只能是 100。
        val effective = denormalise(values[mode])
        OPTION.set(effective)
        // 游戏没跑起来时 `OPTION.set` 不触发回调 —— 补一次"只记状态、不重建主目标"的路径。
        if (percent != effective) {
            store(effective)
        }
    }

    /**
     * 写回 `options.txt`：**两个键一起写**（各模式记住自己的值），先丢掉自己的旧行，重复保存不会堆叠。
     */
    @JvmStatic
    fun save(file: File) {
        if (!file.isFile) {
            return
        }
        try {
            // DLAA 锁定期间 `OPTION` 是 100、不是用户值：落盘要保留记忆里的原值，退出 DLAA 才恢复得回来。
            if (!dlaaLock) {
                values[mode] = OPTION.get()
            }
            val eol = System.lineSeparator()
            val owned = MODE_KEYS.toSet()
            val kept = file.readLines(StandardCharsets.UTF_8)
                .filterNot { line -> owned.any { key -> line.startsWith("$key:") } }
            val written = MODE_KEYS.indices.map { index -> "${MODE_KEYS[index]}:${values[index]}" }
            val text = (kept + written).joinToString(eol) + eol
            file.writeText(text, StandardCharsets.UTF_8)
        } catch (t: Throwable) {
            Illusion.LOGGER.warn("[${Illusion.MODID}] could not write $OPTIONS_KEY to $file", t)
        }
    }

    /**
     * 窗口像素数 → 渲染像素数，至少 1（0 尺寸纹理非法）。`MainTargetRenderScaleMixin` 也走这里，故 `@JvmStatic`。
     */
    /** 只在第一次"选了缩放但 D3D12 没在跑"时打一行，避免每帧刷屏。 */
    @Volatile
    private var warnedScaledWithoutBackend = false

    @JvmStatic
    fun scaled(value: Int): Int {
        return scaledAt(value, percent)
    }

    /**
     * **主目标附件上一次真的按哪个比例建的**：[percent] 是"用户要什么"，这个是"主目标现在是什么"，两者在
     * 帧中间可能不一致（重建排在帧边界）。世界层目标必须按这个值分配，否则主目标与世界层尺寸不同 → OIT 的
     * depth-bounds pass 抛 `Depth texture: size does not match expected attachment size`。
     */
    @Volatile
    private var appliedPercent: Int = DEFAULT_PERCENT

    /** 只打一次取证行（每帧两次调用，不能刷屏）。 */
    @Volatile
    private var loggedPendingPercent = false

    /** [scaled] 的显式比例版本：世界层目标要按"已生效"的比例算。 */
    private fun scaledAt(value: Int, p: Int): Int {
        if (p == 100 || value <= 0) {
            return value
        }
        // 只有 D3D12 后端真的驱动这一帧时才缩放主目标：原版 GL 的呈现路径按**窗口**尺寸读主目标，
        // 读一个更小的纹理直接抛 `Source texture ... is not large enough`。
        if (!isAvailable()) {
            if (!warnedScaledWithoutBackend) {
                warnedScaledWithoutBackend = true
                Illusion.LOGGER.info(
                    "renderScale={}% recorded, but the D3D12 backend is not driving rendering this session, so the main render target keeps the window size" +
                        " (the vanilla present path cannot read a scaled main target); it applies once D3D12 takes over again",
                    p,
                )
            }
            return value
        }
        val scaled = (value.toLong() * p.toLong()) / 100L
        return if (scaled < 1L) 1 else scaled.toInt()
    }

    /** 只在第一次按世界层尺寸重建辅助目标时打一行（`LevelRenderer.render` 每帧都走这里）。 */
    @Volatile
    private var loggedWorldLayerSize = false

    /**
     * 世界层辅助目标（translucent / item_entity / particles / weather / clouds 与后处理内部目标）该用的尺寸。
     * 原版用主目标的 `width/height` 同时记账和分配这些目标，而缩放只改主目标的**纹理**，于是
     * `copyDepthFrom` 拿目标尺寸去读更小的源深度 → `Source texture ... is not large enough`。
     * 修法是把这些目标按渲染尺寸分配（夹取拷贝矩形不行：合成链逐像素比深度，尺寸不一致会错位）。
     * 取"已生效"的比例且不写日志 —— 本条每帧被调用两次（宽、高），带日志会变成帧率计。
     */
    @JvmStatic
    fun worldLayerSize(value: Int): Int {
        // 按**已生效**的比例算：否则主目标还是旧尺寸、世界层已是新尺寸，OIT 的 pass 立刻崩。
        val applied = appliedPercent
        if (applied != percent && !loggedPendingPercent) {
            loggedPendingPercent = true
            Illusion.LOGGER.info(
                "the world layer allocates at the already-applied {}% first (the user has asked for {}%); the main target is rebuilt at the frame boundary, "
                    + "and only then do both switch to the new scale",
                applied, percent,
            )
        }
        val result = attachmentDimensionAt(value, applied)
        if (result != value && !loggedWorldLayerSize) {
            loggedWorldLayerSize = true
            Illusion.LOGGER.info(
                "world-layer auxiliary targets (translucent/particles/weather/clouds plus the post-processing internals) follow the render size" +
                    " (main target {} -> {}, renderScale={}%); the depth copy and the composite return to one coordinate space",
                value, result, applied,
            )
        }
        return result
    }

    /**
     * 主渲染目标附件（color / depth）的尺寸，两个 Java mixin 都走这里：`MainTargetRenderScaleMixin`（构造）
     * 与 `RenderTargetCreateBuffersScaleMixin`（窗口 resize —— 少了它窗口一变大缩放就静默失效）。
     * **只能挂在"重建纹理"的路径上**：每次调用都打一行日志，每帧调用的地方必须走 [worldLayerSize]。
     */
    @JvmStatic
    fun scaledForAttachment(value: Int): Int {
        // 这里是主目标附件真的被创建的那条路径，所以"已生效的比例"在这里更新，世界层随之换到同一个数。
        appliedPercent = percent
        loggedPendingPercent = false
        val result = attachmentDimension(value)
        if (result != value) {
            // 只有真正缩放时才打；主目标每次（重）建纹理最多四行（两个附件 × 宽高）。
            Illusion.LOGGER.info("main render target attachments {} -> {} (renderScale={}%)", value, result, percent)
        } else if (dlaaLock && percent == DEFAULT_PERCENT) {
            // DLAA 的验收条件是"附件尺寸 == 窗口尺寸"，所以 100% 时也要留一行显式读数。
            Illusion.LOGGER.info(
                "main render target attachments {} -> {} (renderScale=100%, DLAA locked: render == target)",
                value, result,
            )
        }
        return result
    }

    /**
     * [scaledForAttachment] / [worldLayerSize] 共用的换算，**不写日志**；> 100% 时不越过设备 maxTextureSize
     * （vanilla 的尺寸检查只按未缩放值算，越过去会 OOM 并掉到 854x480 兜底）。
     */
    private fun attachmentDimension(value: Int): Int = attachmentDimensionAt(value, percent)

    /** [attachmentDimension] 的显式比例版本。 */
    private fun attachmentDimensionAt(value: Int, p: Int): Int {
        val scaled = scaledAt(value, p)
        if (scaled <= value) {
            return scaled
        }
        val maxTextureSize = RenderSystem.getDevice().deviceInfo.limits().maxTextureSize()
        return if (value <= maxTextureSize) minOf(scaled, maxTextureSize) else value
    }
}
