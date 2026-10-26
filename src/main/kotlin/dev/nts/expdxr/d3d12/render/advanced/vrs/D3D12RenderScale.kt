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
 * P105：渲染缩放 —— 超采样（SSAA / 超分）系列的基础：把"渲染多少像素"与"显示多少像素"解耦。
 *
 * 机制：主渲染目标（vanilla 的 `MainTarget`）仍然按窗口尺寸**记账**
 * （`MainTarget.width/height`、GUI 的 ortho、GameRenderer 的每帧尺寸比对都保持不变），但它
 * **实际创建的纹理**按这里的百分比缩放（见
 * `dev.nts.expdxr.mixin.render.MainTargetRenderScaleMixin`）。渲染链里一切"输出尺寸"都取自
 * 纹理本身（`D3D12RenderPassBackend.outputWidth/outputHeight`、blit 的 viewport/scissor），
 * 于是世界就在这个分辨率上着色；最后的呈现 blit 用线性采样器把源纹理拉伸/压缩到 back buffer
 * （P106 之后由一次独立的 texture→texture blit 完成）。全程没有新的 pass、没有新的 native 侧配置。
 *
 * 与 VRS 的关系：[dev.nts.expdxr.misc.D3D12VRSSettings] 管的是"每块像素着色几次"（区域级、由渲染器
 * 自动决定，P238 起只有 OFF/AUTO 两档），这里管的是"总共渲染多少像素"（全局级）。两者叠加，画面着色
 * 成本才真正与显示分辨率脱钩。
 *
 * 已知限制（P106 之后仍在的）：界面自己按窗口 1:1 渲染（这正是 P106 的目的），
 * 所以界面**不**参与缩放；世界层由一次带线性采样的 texture→texture blit 铺满界面那张窗口尺寸的目标
 * （`D3D12GuiSplit`，见 `planned_implementations.md` §16.3）。
 *
 * >100% 时同理：目前只有一个线性采样抽点，不算真正的盒式降采样，所以超采样档会偏软/闪，
 * 需要等超采样系列把降采样滤镜补上。
 *
 * 取值入口（用户 m10294 起）：它是一项**普通视频设置**，挂在原生视频设置界面新增的第四组
 * "Illusion 设置"里（25% 步进的滑条，见 [OptionInstance] / [PercentRange]），不再读模组配置；
 * 持久化在 vanilla 自己的 `options.txt`（键 [OPTIONS_KEY]），由
 * `dev.nts.expdxr.mixin.options.OptionsRenderOptionsMixin` 在 `Options.load`/`save` 尾部接管。
 */
object D3D12RenderScale {
    /** 百分比下限：再低就没有可读的画面了（**非 DLSS** 模式的集合）。 */
    private const val MIN_PERCENT = 25

    /** 百分比上限：400% 已经是 16 倍像素，超过就没有实际意义。 */
    private const val MAX_PERCENT = 400

    /** 滑条步进：25% 一档（25/50/…/400 共 16 档）。 */
    private const val STEP_PERCENT = 25

    /** 默认值 = 完全不缩放。 */
    private const val DEFAULT_PERCENT = 100

    // ------------------------------------------------------------------
    // P108y（用户 m11139 / m11147）：DLSS 模式有**自己的一档集合与自己的持久化键**
    // ------------------------------------------------------------------

    /**
     * DLSS 模式的有效集合：25..100、步进 1（用户 m07665）。
     *
     * <p>旧集合是 50..90/步进 10（用户 m11139），那时这一行还是**可拖的**滑条；现在 DLSS 开着
     * 时它被绑到挡位、只读（[DeepLearningSuperSampling.bindRenderScaleToDlss]），只承担“显示当前
     * 输入比例”的作用，所以放宽到 25..100 并允许 1% 的精细值 —— 四个挡位分别是 33/50/58/67，
     * 落在旧集合里会有一半取不到。
     */
    private const val DLSS_MIN_PERCENT = 25
    private const val DLSS_MAX_PERCENT = 100
    private const val DLSS_STEP_PERCENT = 1

    /**
     * DLSS 模式的默认值 = 58%（用户 m07702：默认用“平衡”挡位的输入比例）。
     *
     * <p>与 [DeepLearningSuperSampling.QUALITY_DEFAULT]（平衡）保持一致：首次打开 DLSS 时挡位与比例
     * 天生对得上，不会出现“默认是质量、比例却是 58%”这种一启动就被绑定逻辑改掉的情况。
     */
    private const val DLSS_DEFAULT_PERCENT = 58

    /** 原版 `options.txt` 里的键名；与 `dxrMcAaMode`/`dxrMcShadingRate` 同一套管道。 */
    const val OPTIONS_KEY: String = "dxrMcRenderScale"

    /** DLSS 模式自己的键（用户 m11147）：两种模式各自记住"用户上次调的值"。 */
    const val DLSS_OPTIONS_KEY: String = "dxrMcRenderScaleDlss"

    /** 0 = 非 DLSS（25..400 步进 25），1 = DLSS（50..90 步进 10）。 */
    private const val MODE_PLAIN = 0
    private const val MODE_DLSS = 1

    private val MODE_KEYS = arrayOf(OPTIONS_KEY, DLSS_OPTIONS_KEY)
    private val MODE_DEFAULTS = intArrayOf(DEFAULT_PERCENT, DLSS_DEFAULT_PERCENT)
    private val MODE_STEPS = intArrayOf(STEP_PERCENT, DLSS_STEP_PERCENT)

    /**
     * 当前模式（用户 m11147）：由 DLSS 侧在 `push` / `load` 末尾经 [setDlssMode] 置位。
     * 只渲染线程/客户端线程读写，`@Volatile` 足够。
     */
    @Volatile
    private var mode: Int = MODE_PLAIN

    /**
     * 每个模式"记住的"值（下标同 [MODE_KEYS]）。这是两个 `options.txt` 键在内存里的镜像：
     * 滑条只改**当前模式**这一格，另一格原样留着。
     */
    private val values = intArrayOf(DEFAULT_PERCENT, DLSS_DEFAULT_PERCENT)

    /** 100 = 完全不缩放（默认）。 */
    @Volatile
    private var percent: Int = 100

    /**
     * P76i（用户 m10601）：**滑条需要重新同步**的代号，每次由本类自己（而不是玩家拖滑条）
     * 改动取值时自增。
     *
     * 为什么需要它：vanilla 的 `OptionInstanceSliderButton` 只在**构造**时和 `onRelease` 调
     * `resetValue()` 的那一刻，才把滑块位置对齐到 `OptionInstance.get()`；`OptionInstance.set`
     * 又是一条"值没变就什么都不做"的路径（见 `OptionInstance.set` 里的 `Objects.equals`）。
     * 于是切 DLSS 开关、进/出 DLAA 时，模型里的百分比已经变了，画面上那个滑条却**停在旧位置**，
     * 而它的标题又是拿"旧位置 + 新取值范围"算出来的 —— 显示的数字和真正生效的值可以完全无关
     * （实测：DLAA 把缩放钉到 100%，滑条却按 DLSS 的 50..90 把同一个位置读成 50%）。
     *
     * 界面侧（`VideoSettingsScreenMixin`）每 tick 比对一次代号，变了就调
     * `OptionsList.resetOption` —— 那是 vanilla 自己的公共入口，内部就是
     * `ResettableOptionWidget.resetValue()`。本类不认识任何界面对象，依赖方向保持单向。
     */
    @Volatile
    private var syncGeneration: Int = 0

    /** 界面据此判断"该重新同步滑条了"。只增不减。 */
    @JvmStatic
    fun syncGeneration(): Int = syncGeneration

    /**
     * 界面把滑条重新对齐到生效值之后打一行（P76i）。只在代号变化时调用（切 DLSS 开关、进/出
     * DLAA），所以每次切换最多一行 —— 它是"界面确实刷新了"的取证判据，滑条的数字对不上时先看它。
     */
    @JvmStatic
    fun logSliderResync() {
        Illusion.LOGGER.info(
            "[${Illusion.MODID}] P76i: render-scale slider re-synced to {}% ({} mode, range {}..{}%, step {}%)",
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
        // P108aa（用户 m11796）：DLAA 锁定是**最高优先级**，比模式夹取更早生效。曾经只把锁加在
        // 滑条取值（[denormalise]）上，而 `apply` → `store` → `setPercent` 仍会把 100 夹回 DLSS
        // 模式的 90：主目标附件按 90% 分配、native 拒绝 DLAA（render != target）并每帧重建 NGX
        // 特征。所有路径（滑条、`setDlssMode`、`load`、`store`）最终都走这里，所以锁必须在这里生效。
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
     * P108y（用户 m11147）：DLSS 开关切换时由 `DeepLearningSuperSampling` 的 **push / load**
     * 末尾调用。**只置一个 `@Volatile` 标志**，绝不反过来去初始化 DLSS 对象 —— 否则
     * `D3D12RenderScale` 与 `DeepLearningSuperSampling` 之间会形成类初始化顺序耦合
     * （两个对象在 `Options.load` 里谁先谁后由 mixin 注入顺序决定，不能依赖）。
     *
     * <p>切换动作（用户 m11147 定的语义）：
     * <ol>
     *   <li>先把**当前模式**的值存回它自己的键（只改内存镜像，落盘由 `Options.save` 负责）；</li>
     *   <li>再载入**目标模式**的值（DLSS 开：没记录过用 70；DLSS 关：没记录过用 100）；</li>
     *   <li>应用（`OPTION.set` → [apply] → 重建主渲染目标）并打一行 INFO，把两个键的值与
     *       生效值都打出来，便于取证。</li>
     * </ol>
     *
     * <p>**不自动恢复"开 DLSS 前的值"** —— 两模式各有自己的键，"关掉 DLSS 保留当前值"这件事
     * 自然由 `dxrMcRenderScaleDlss` 记住；这样行为可预期（关掉 DLSS 时看到的就是它自己那把尺）。
     *
     * @return true = 模式真的变了（调用方据此决定是否打日志）
     */
    @JvmStatic
    fun setDlssMode(active: Boolean): Boolean {
        val target = if (active) MODE_DLSS else MODE_PLAIN
        if (target == mode) {
            return false
        }
        // 1) 当前模式的值存回自己的键（内存镜像）。DLAA 锁定期间 `OPTION` 是 100，不是用户值 ——
        //    拿它覆盖记忆会让"退出 DLAA 恢复原值"变成恢复 100。
        if (!dlaaLock) {
            values[mode] = OPTION.get()
        }
        // 2) 载入目标模式的值（锁定期间只留在记忆里，不生效）。
        val restored = values[target]
        mode = target
        // P108aa：DLAA 锁定期间缩放必须恒为 100 —— 绝不能让"DLSS 模式的 50..90"把它夹走
        //（实测过：DLAA 生效后 `setDlssMode(true)` 把 100 夹成 90，于是 render 2304x1200 而
        // target 2560x1334，native 的 DLAA 分支直接拒绝）。模式切换照做，取值交给 DLAA 锁。
        if (dlaaLock) {
            // 补齐状态 + 按需重建 + 让滑条重新同步，全在 [setProgrammatically] 里。
            setProgrammatically(DEFAULT_PERCENT)
            logModeSwitch()
            return true
        }
        // 3) 应用。这里有一个 vanilla 的坑：`OptionInstance.set` 在 `Minecraft.isRunning()` 为假时
        // **只改 option 的值、不触发回调**（见 `OptionInstance.set`），而 `Options.load` 恰好跑在
        // `Minecraft` 构造函数里 —— 那时 `isRunning()` 还是 false。于是回调里的 `apply`（写状态 +
        // 重建主目标）不会跑，`percent` 会停在旧值：实测过"文件里 dlss=true + dlss 键=60，启动后
        // 世界仍按 100% 渲染"。所以这里**两条路都走完**：
        //   - 游戏在跑：`OPTION.set` 会触发 `apply`（顺带重建主目标），下面那次 [store] 是幂等的；
        //   - 游戏没跑：回调不会来，由下面那次 [store] 顶上。主目标此刻还不存在，本来也不需要重建
        //     （它会在第一次创建时按新比例分配），所以这里**不**调 `rebuildMainTarget()`。
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
        // P76i（用户 m10601）：值**没变**时 vanilla 完全不会触发回调，主目标因此不会重建 ——
        // 但 AA / DLSS 已经换了模式，渲染链该按新模式重新长一遍。这里在"回调确实没跑"的
        // 情况下强制补一次重建（排在帧边界，见 [rebuildMainTarget]），值变了的那条路
        // `apply` 已经重建过，不重复。
        if (applyCount == appliesBefore) {
            rebuildMainTarget()
        }
        syncGeneration++
    }

    /**
     * `apply` 被调用的次数（P76i）。[setProgrammatically] 用它判断 `OptionInstance.set` **到底有没有**
     * 触发回调：vanilla 在"值没变"与"游戏还没跑起来"两种情况下都不调，那条路径得由我们自己补重建。
     */
    @Volatile
    private var applyCount: Int = 0

    /** [setDlssMode] 的取证行：两个键与生效值一起打出来。 */
    private fun logModeSwitch() {
        Illusion.LOGGER.info(
            "[${Illusion.MODID}] P105: renderScale 范围随 DLSS 切换：{}（{}..{}，步进 {}%）；生效 {}%，" +
                "两个键 {}={} / {}={}",
            if (mode == MODE_DLSS) "DLSS 开" else "DLSS 关",
            minOf(), maxOf(), stepOf(), percent,
            OPTIONS_KEY, values[MODE_PLAIN], DLSS_OPTIONS_KEY, values[MODE_DLSS],
        )
    }

    /**
     * P108y：把当前值写回**当前模式的键**。滑条拖动（[apply]）与模式切换都走这里；
     * 另一模式的格子不动。
     */
    private fun storeValue(value: Int) {
        values[mode] = value
    }

    /** 是否真的在缩放；false 时调用方可以整条短路。 */
    @JvmStatic
    fun isEnabled(): Boolean = percent != 100

    /**
     * 25% 步进的滑条取值域：形状照 vanilla 的 `OptionInstance.IntRange`
     * （`OptionInstance.IntRangeBase` 的默认实现），两处不同：
     *
     * - `fromSliderValue` 不落在任意整数上，而是**吸附到 25 的倍数**：先把滑条位置线性换成
     *   25..401 的整数（与原版同式），再走 [snap]（`((v + 12) / 25) * 25`，整数除法向下取整），
     *   最后 `coerceIn` 兜住首尾（滑条拉到 1.0 时上式给出 400）。
     * - `next`/`previous`（键盘左右键在滑条上步进）也走一档 25，否则原版的 `current ± 1`
     *   会被吸附回原值、方向键看起来没反应。
     *
     * `toSliderValue` 与原版逐字一致：首尾贴到 0/1，中间按 `(v + 0.5)` 映射 —— 25..400 每档等宽，
     * 所以手柄位置与刻度一一对应。
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
     * DLAA 锁定（用户 m11796）：DLAA 要求**渲染分辨率 == 目标分辨率**，所以选中 DLAA 时把缩放
     * 强制为 **100**，并且让滑条无论怎么拖都只给出 100（[denormalise]）。由
     * `D3D12AntiAliasing` 在选中/离开 DLAA 时调用。
     *
     * <p>为什么不干脆把滑条隐藏：vanilla 的控件是建界面时按 `PercentRange` 造好的，隐藏要走
     * `OptionsList` 的 rebuild（工程里没有干净入口）。置灰 + 取值恒为 100 的语义最直白，
     * 界面侧再由 `VideoSettingsScreenMixin` 把滑条 disable 掉。
     */
    @Volatile
    private var dlaaLock: Boolean = false

    /** 界面临时置灰的依据。 */
    @JvmStatic
    fun isLockedByDlaa(): Boolean = dlaaLock

    /**
     * 选中/离开 DLAA 时调用。进入锁定时把缩放**立刻**置为 100（走正常 setter：会重建主渲染目标），
     * 离开时恢复**锁定前该模式的用户值**（DLAA 期间 `apply` 不写回记忆，见 [apply]）。
     */
    @JvmStatic
    fun setDlaaLock(active: Boolean) {
        if (dlaaLock == active) {
            return
        }
        dlaaLock = active
        Illusion.LOGGER.info(
            "[${Illusion.MODID}] P105: DLAA {} render-scale lock; DLAA needs render == target, so "
                + "the scale is {}100%",
            if (active) "engaged the" else "released the", if (active) "pinned to " else "free from ")
        if (active) {
            val before = percent
            setProgrammatically(DEFAULT_PERCENT)
            // 游戏没跑时回调不会触发；本来就已经 100% 时回调也不改变状态 —— 两种情况都补一行
            // 显式读数（DLAA 的验收判据要求看到 `render scale = 100%`）。
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

    /**
     * 把滑条给出的原始值规整成**当前允许的**取值：DLAA 锁定期间恒为 100，否则按模式步进吸附并夹取。
     */
    private fun denormalise(value: Int): Int =
        if (dlaaLock) 100 else snap(value).coerceIn(minOf(), maxOf())

    /**
     * 吸附公式：离 [value] 最近的**当前步进**的倍数，从 [minOf] 起算。非 DLSS 模式即
     * `((v + 12) / 25) * 25`（37 -> 25、38 -> 50）；DLSS 模式是 10 的倍数。
     *
     * <p>`minOf()` 必是步进的倍数（25/25、50/10），所以直接按步进取整即可。
     */
    private fun snap(value: Int): Int {
        val step = stepOf()
        return ((value + step / 2) / step) * step
    }

    /**
     * 原版视频设置界面第四组"Illusion 设置"里的渲染缩放滑条（用户 m10294：从 `[render] renderScale`
     * 迁移过来）。
     *
     * 滑条的标题由 `toString` 自己带上（滑条按钮不像 CycleButton 那样加前缀），这里直接用原版
     * `options.percent_value`（鼠标灵敏度/伽马那个 `"%s: %s%%"`），所以不用为本项目新增取值格式键。
     * 回调就是迁移前 `Illusion.applyRenderScaleConfig()` 的动作：写状态 + 打日志 + 让主目标按新比例重建。
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

    /** True while the game really renders through DXRMC's D3D12 backend（非 D3D12 时按钮置灰）。 */
    @JvmStatic
    fun isAvailable(): Boolean = D3D12AdapterInfo.current != null

    /** 选项回调：记状态 → 日志 → 主目标按新比例重建。只改**当前模式**的键。 */
    private fun apply(value: Int) {
        applyCount++
        // DLAA 锁定期间 `OPTION` 被强制成 100，那**不是**用户值：写回记忆会把用户原来的 70
        // 覆盖掉，退出 DLAA 就恢复不出来。用户值留在 values[mode] 里不动。
        if (!dlaaLock) {
            storeValue(value)
        }
        store(value)
        rebuildMainTarget()
    }

    /**
     * 只记状态与日志，不碰渲染目标：`Options.load` 走这条（那时 `Minecraft.gameRenderer` 还没建，
     * 主渲染目标会在第一次创建时按新比例分配）。
     */
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
                    "fraction of the window and the GUI keeps a window-sized target of its own (P106)",
            )
        }
    }

    /**
     * 让主目标按新比例重建 —— 迁移前 `Illusion.applyRenderScaleConfig()` 的后半段，逐句搬过来。
     *
     * `MainTarget` 只在**逻辑尺寸**变化时才重建纹理，而这里的百分比不改变逻辑尺寸（P105 的整个前提），
     * 所以必须主动调一次 `GameRenderer.resize`：`RenderTarget.resize` 无条件 `destroyBuffers` +
     * `createBuffers`，于是附件按新比例重分配、逻辑尺寸保持不变。100% 时也要走这条 —— 从 150% 改回
     * 100% 同样是一次"逻辑尺寸没变但附件必须重建"，少了它主目标会停在上一个比例。
     *
     * 全部动作都排到客户端线程（`RenderTarget.resize` 里有 `assertOnRenderThread`），并且是尽力而为：
     * 失败就算了，下次窗口尺寸变化或下次启动一样会生效。
     *
     * <p>P108aa（用户 m11796 崩溃修复）：**必须排到帧边界，不能就地重建**。`Minecraft.execute`
     * 在渲染线程上会**内联执行**（`BlockableEventLoop.execute`：`scheduleExecutables()` 在
     * `runningTask() == false && isSameThread()` 时为假 → `doRunTask`），而 DLAA 释放 / 滑条
     * 回调正是从 `GameRenderer.render` 内的钩子来的 —— 就地 `gameRenderer.resize` 会关掉本帧仍在
     * 使用的深度纹理，随后 `GameRenderer.render:443` 的 `clearDepthTexture` 抛
     * `IllegalStateException: Depth texture is closed`（实测 crash-2026-10-17_16.57.21）。
     * `Minecraft.schedule` 只入队，由 `Minecraft.runTick` 的 `scheduledExecutables`
     * （`runAllTasks`，在 `tick()` / `renderFrame()` 之前）执行，于是重建落在帧边界。
     */
    /**
     * P211d：把“主目标按当前比例重建”暴露给渲染器内部调用（DLSS 的自愈检查）。
     *
     * <p>与滑条回调走同一条路径（`rebuildMainTarget` → `minecraft.schedule`），因此同样落在帧边界，
     * 不会在帧中间关掉仍在使用的深度纹理。调用方负责限频。
     */
    @JvmStatic
    fun scheduledRebuild() {
        rebuildMainTarget()
    }

    /**
     * P282c：在**帧边界**上按新比例重建，供渲染线程内的调用方使用（超分门面的 100% 自愈）。
     *
     * <p>帧中间直接改 [setPercent] 只改了字段、主目标附件仍是旧比例，而本帧**后半段**创建的世界层
     * 目标会按新比例分配，于是 `RenderTarget.copyDepthFrom` 抛
     * `IllegalArgumentException: Depth texture: size does not match expected attachment size.
     * Is 854x480 expected 572x321`（实测 crash-2026-10-26_16.46.00-client）。
     * 走 `Minecraft.schedule` 则在 `runTick` 的 `runAllTasks` 里执行 —— 介于两帧之间。
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
     * Restores the scale from vanilla's `options.txt`（行格式 `key:value`，与 vanilla 自己的键同形）。
     *
     * 文件里没有这一行时（首次运行 / 用户删过 / 刚从模组配置迁移过来）也要记一次默认值，让 JVM 状态、
     * 界面与 `options.txt` 三者一致。这里**不**重建主目标：`Options.load` 发生在 `Minecraft` 构造函数
     * 里（`gameRenderer` 还是 null），主目标本来就是按新比例创建的。
     */
    @JvmStatic
    fun load(file: File) {
        if (!file.isFile) {
            return
        }
        try {
            val lines = file.readLines(StandardCharsets.UTF_8)
            // P108y（用户 m11147）：两个键都读进内存镜像；**当前模式**的那个值才应用。
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
        // 当前模式下记住的值吸附/夹取后生效。DLAA 锁定期间不论文件里写的是多少都只能是 100；
        // 用户原值已读进 values[mode]，退出锁定时恢复。
        val effective = denormalise(values[mode])
        OPTION.set(effective)
        // OPTION.set 在游戏没跑起来时不触发回调（与 D3D12AntiAliasing/DeepLearningSuperSampling 同理），
        // 所以这里补一次"只记状态、不重建主目标"的路径（主目标本来就是按新比例创建的）。
        if (percent != effective) {
            store(effective)
        }
    }

    /**
     * Writes the scale back into vanilla's `options.txt`（vanilla 每次保存都整份重写，所以要在
     * `Options.save` 尾部补行；先丢掉自己的旧行，重复保存不会堆叠）。
     *
     * <p>P108y（用户 m11147）：**两个键一起写**。当前模式的值以 `OPTION` 为准（用户刚拖过滑条
     * 时它是最新的），另一模式的值用内存镜像 —— 于是"各模式记住自己上次的值"在落盘后依然成立。
     */
    @JvmStatic
    fun save(file: File) {
        if (!file.isFile) {
            return
        }
        try {
            // DLAA 锁定期间 `OPTION` 是锁定用的 100，不是用户值：写盘要保留记忆里的原值，
            // 下次启动（DLAA 仍选中时）再由锁定作用到 100，退出 DLAA 才恢复得回来。
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
     * 把一个窗口像素数换算成渲染像素数。至少 1：0 尺寸的纹理非法，窗口最小化时也会走到这里。
     *
     * <p>`MainTargetRenderScaleMixin`（Java）也走这里，所以标了 `@JvmStatic`。
     */
    /** 只在第一次"选了缩放但 D3D12 没在跑"时打一行，避免每帧刷屏。 */
    @Volatile
    private var warnedScaledWithoutBackend = false

    @JvmStatic
    fun scaled(value: Int): Int {
        val p = percent
        if (p == 100 || value <= 0) {
            return value
        }
        // 只有在 D3D12 后端真的驱动这一帧时才缩放主渲染目标：原版的呈现/合成路径
        // （GL 的 blit）会按**窗口**尺寸去读主目标，读一个比它小的纹理直接抛
        // `Source texture (640x360) is not large enough to read a rectangle of 854x480`。
        // 走 GL（preferredGraphicsBackend 不是 d3d12、或 DXR_MC_GL_CONTEXT 逃生门、
        // 或设备创建失败回落）时 `D3D12AdapterInfo.current == null`，这里就原样返回。
        if (!isAvailable()) {
            if (!warnedScaledWithoutBackend) {
                warnedScaledWithoutBackend = true
                Illusion.LOGGER.info(
                    "P105: renderScale={}% 已记录，但这一轮 D3D12 后端没在驱动渲染，主渲染目标按窗口尺寸分配" +
                        "（原版呈现路径读不了缩小的主目标）；切回 D3D12 后自动生效。",
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
     * 世界层辅助目标（translucent / item_entity / particles / weather / clouds、以及世界后处理链
     * 的内部目标）该用的尺寸。
     *
     * <p>原版在 `LevelRenderer.render` 里用 `mainRenderTarget().width/height` 同时**记账**和**分配**
     * 这些目标 —— 那是窗口尺寸，而 P105 只缩放主目标的**纹理**（逻辑尺寸刻意保持窗口尺寸）。
     * 两者于是错位：这些辅助目标是窗口大小、主目标是渲染大小，而
     * `LevelRenderer.java:456/460/464` 的 `copyDepthFrom` 原版拿**目标**的 `this.width/height`
     * 当拷贝矩形去读**源**的深度纹理 ⇒ 直接抛
     * `IllegalArgumentException: Source texture (1920x1000) is not large enough to read a rectangle of 2560x1334`。
     *
     * <p>修复是把这些世界层目标按渲染尺寸分配，而不是去夹取拷贝矩形：`transparency.json` 的合成链
     * 在同一像素坐标系里采样与比较深度（`MainDepth`/`TranslucentDepth`/… 全部逐像素对照），
     * 目标比源大只会让拷贝只填左上角一块、深度与颜色彻底错位（半透明/粒子穿帮），
     * 不是尺寸校验能盖住的问题。
     *
     * <p>取 [attachmentDimension] 的同一个值（与 [scaledForAttachment] 同源，但**不写每帧日志**），
     * 正是主目标附件实际用的那个值：辅助目标与主目标纹理尺寸一致，拷贝矩形、合成、深度比较三者
     * 才落在同一坐标系。之所以不能直接调 [scaledForAttachment]：那条每次调用都按「主目标重建纹理」
     * 打一行 `P105:`，而本条每帧都要被调用两次（宽、高）—— 2026-10-16 实机就因此刷了一屏 P105，
     * 把 P105 那行从「重建取证」变成了「帧率计」。
     *
     * <p>100%（默认）与「D3D12 后端没在跑」时 [scaled] 原样返回入参 ⇒ 这条是完全无副作用的恒等变换。
     */
    @JvmStatic
    fun worldLayerSize(value: Int): Int {
        val result = attachmentDimension(value)
        if (result != value && !loggedWorldLayerSize) {
            loggedWorldLayerSize = true
            Illusion.LOGGER.info(
                "P105b: 世界层辅助目标（translucent/particles/weather/clouds + 后处理内部目标）按渲染尺寸分配" +
                    "（主目标 {} -> {}，renderScale={}%）；深度拷贝与合成回到同一坐标系",
                value, result, percent,
            )
        }
        return result
    }

    /**
     * 主渲染目标附件（color/depth 纹理）的尺寸：在 [attachmentDimension] 之上再做取证日志。
     *
     * <p>两个 Java mixin 都走这里，所以标了 `@JvmStatic`：
     * `MainTargetRenderScaleMixin`（构造路径）与 `RenderTargetCreateBuffersScaleMixin`
     * （窗口尺寸变化时的 resize 路径 —— 少了它，窗口一变大 render scale 就静默失效）。
     *
     * <p>**只能挂在"重建纹理"的路径上**：这里每次调用都会打一行 `P105:`，所以每帧都要调用的地方
     * （例如 `LevelRenderer.render` 里的世界层目标尺寸）必须走 [attachmentDimension] / [worldLayerSize]。
     */
    @JvmStatic
    fun scaledForAttachment(value: Int): Int {
        val result = attachmentDimension(value)
        if (result != value) {
            // 取证用：只有真正缩放时才打；主目标每次（重）建纹理最多四行
            // （allocateColorAttachment / allocateDepthAttachment 两处分配 × 宽、高各一）。
            Illusion.LOGGER.info("P105: 主渲染目标附件 {} -> {}（renderScale={}%）", value, result, percent)
        } else if (dlaaLock && percent == DEFAULT_PERCENT) {
            // DLAA 的验收条件就是"附件尺寸 == 窗口尺寸"，所以 100% 时也要留一行显式读数
            //（`result == value` 是恒等变换，正常路径刻意不打，以免变成每帧日志）。
            Illusion.LOGGER.info(
                "P105: 主渲染目标附件 {} -> {}（renderScale=100%，DLAA 锁定：render == target）",
                value, result,
            )
        }
        return result
    }

    /**
     * [scaledForAttachment] / [worldLayerSize] 共用的换算，**不写日志**。
     *
     * <p>超采样（> 100%）时不能越过设备的 maxTextureSize：vanilla 的尺寸检查只按**未缩放**的值算，
     * 越过去会 OOM 并掉到 854x480 兜底（再被缩放一次，可能又 OOM）。
     */
    private fun attachmentDimension(value: Int): Int {
        val scaled = scaled(value)
        if (scaled <= value) {
            return scaled
        }
        val maxTextureSize = RenderSystem.getDevice().deviceInfo.limits().maxTextureSize()
        return if (value <= maxTextureSize) minOf(scaled, maxTextureSize) else value
    }
}
