package dev.nts.expdxr.dxrmc.framegen

import dev.nts.expdxr.Illusion
import dev.nts.expdxr.d3d12.extern.MCDR_ABI
import dev.nts.expdxr.dxrmc.sr.SuperResolution
import net.minecraft.client.Minecraft
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.common.NeoForge

/**
 * P300 验收用的自动化钩子（**只给自动化跑测试**，与 `-Pdxrmc.quickPlayWorld` / `-Pdxrmc.noPopup`
 * 同一条约定：构建脚本里的 `-Pdxrmc.*` 不是功能入口）。
 *
 * <p>为什么需要它：A6（会话中途关帧生成）与 A11（会话中途把超分换成 FSR/XeSS）都要求「运行中
 * 改设置」，无人值守的冒烟脚本改不了 —— 它只能改 `options.txt` 再重启，而那样跨不过「0 → 开」
 * 之外的任何边界。有了这个钩子，一次 `gradlew runClient -Pdxrmc.selfTest=…` 就能把两条都演一遍，
 * 而且走的是**和玩家操作完全相同**的路径（`OptionInstance.set` → 门面的 `push()` → ABI）。
 *
 * <p>规格：[PROPERTY] 是 `动作@tick` 的逗号分隔表，tick 从**进入世界**那一刻开始算（20 tick = 1 s）。
 * 动作：`fg-on` / `fg-off` / `sr-dlss` / `sr-fsr` / `sr-xess` / `sr-none` / `probe-fg`（打印三家能力表）。
 * 例：`-Pdxrmc.selfTest=fg-on@20,fg-off@400` = 进世界 1 s 后开帧生成，20 s 后关掉。
 *
 * <p>没设这个属性时 [install] 只做一次 `System.getProperty` 就返回，不注册任何监听器。
 */
object FrameGenerationSelfTest {
    /** 系统属性名（`-Ddxrmc.selfTest=…`；gradle 的 `-Pdxrmc.selfTest=…` 会转发成它）。 */
    const val PROPERTY: String = "dxrmc.selfTest"

    private data class Step(val atTick: Long, val action: String) {
        var fired: Boolean = false
    }

    private var installed: Boolean = false
    private var forcedFrameGenBackend: Int = MCDR_ABI.FG_BACKEND_NONE

    /** 解析后的计划；空表 = 没启用（或规格写错）。 */
    private var plan: List<Step> = emptyList()

    /** 进入世界之后的 tick 计数；负数 = 还没进世界。 */
    private var ticks: Long = -1

    /**
     * 读属性并（必要时）挂上 tick 监听。必须在客户端调用（[SuperResolution] 与 [Minecraft] 都是
     * 纯客户端类）。
     */
    fun install() {
        if (installed) {
            return
        }
        installed = true
        val spec = System.getProperty(PROPERTY)?.trim().orEmpty()
        if (spec.isEmpty()) {
            return
        }
        plan = parse(spec)
        if (plan.isEmpty()) {
            Illusion.LOGGER.warn("{} self test: could not parse \"{}\" (want act@tick,act@tick)", PREFIX, spec)
            return
        }
        Illusion.LOGGER.info(
            "{} self test armed: {} (ticks are counted from the moment the world is loaded)",
            PREFIX, plan.joinToString(", ") { "${it.action}@${it.atTick}" },
        )
        NeoForge.EVENT_BUS.addListener<ClientTickEvent.Post> { tick() }
    }

    private fun parse(spec: String): List<Step> {
        val out = ArrayList<Step>()
        for (part in spec.split(',')) {
            val text = part.trim()
            if (text.isEmpty()) {
                continue
            }
            val at = text.lastIndexOf('@')
            if (at <= 0 || at == text.length - 1) {
                return emptyList()
            }
            val action = text.substring(0, at).trim().lowercase()
            val tick = text.substring(at + 1).trim().toLongOrNull() ?: return emptyList()
            if (tick < 0 || action !in ACTIONS) {
                return emptyList()
            }
            out.add(Step(tick, action))
        }
        return out.sortedBy { it.atTick }
    }

    private fun tick() {
        if (plan.isEmpty()) {
            return
        }
        // 时钟从「进入世界」开始：标题界面也在 tick，按进程启动算会让所有动作提前发生。
        if (ticks < 0) {
            if (Minecraft.getInstance()?.level == null) {
                return
            }
            ticks = 0
            Illusion.LOGGER.info("{} self test: world loaded, clock started", PREFIX)
        }
        ticks++
        for (step in plan) {
            if (step.fired || ticks < step.atTick) {
                continue
            }
            step.fired = true
            fire(step)
        }
        if (plan.all { it.fired }) {
            plan = emptyList()
            Illusion.LOGGER.info("{} self test: all steps fired, hook idle", PREFIX)
        }
    }

    private fun fire(step: Step) {
        Illusion.LOGGER.info("{} self test: {} at tick {}", PREFIX, step.action, ticks)
        try {
            when (step.action) {
                "fg-on" -> {
                    val backend = forcedFrameGenBackend.takeIf { it != MCDR_ABI.FG_BACKEND_NONE }
                        ?: FrameGeneration.activeBackend().takeIf { it != 0 }
                        ?: MCDR_ABI.FG_BACKEND_DLSSG
                    FrameGeneration.requestBackend(backend, true)
                    // P305ab：门面在包装型后端 available=0 时会把 mode 传成 0，`fgSetMode` 走"关闭"早退，
                    // 于是强制 picked 也永远到不了包装。钩子这里直接把「开」发下去，绕开可用性推断。
                    forcedFrameGenBackend = backend
                    pushFrameGenModeDirectly(1)
                }
                "fg-off" -> {
                    val backend = forcedFrameGenBackend.takeIf { it != MCDR_ABI.FG_BACKEND_NONE }
                        ?: FrameGeneration.activeBackend().takeIf { it != 0 }
                    if (backend != null && backend != MCDR_ABI.FG_BACKEND_NONE) {
                        FrameGeneration.requestBackend(backend, false)
                        forcedFrameGenBackend = backend
                        pushFrameGenModeDirectly(0)
                    }
                }
                "sr-dlss" -> SuperResolution.onDlssToggle(true)
                "sr-none" -> SuperResolution.onDlssToggle(false)
                "sr-fsr" -> SuperResolution.fsrOption().set(true)
                "sr-xess" -> SuperResolution.xessOption().set(true)
                // P304：把三家的能力表各打一行。只有自动化跑测试才会用到 —— 界面按
                // "第一个可用" 提前返回，所以 FSR-FG / XeSS-FG 的探测结果平时看不到，
                // 而"这台机器缺哪个 DLL、为什么不可用"恰恰是要取证的东西。
                "probe-fg" -> probeFrameGeneration()
                // P305d：强制指定 picked 后端。**只有自动化跑测试才用**：界面永远挑"第一个可用"的
                // 后端，所以没有这条就没法让 XeSS-FG / FSR-FG 的代码路径真的跑起来。
                "fg-backend-xess" -> pickFrameGenBackend(MCDR_ABI.FG_BACKEND_XESSFG)
                "fg-backend-fsr" -> pickFrameGenBackend(MCDR_ABI.FG_BACKEND_FSRFG)
                "fg-backend-dlssg" -> pickFrameGenBackend(MCDR_ABI.FG_BACKEND_DLSSG)
            }
        } catch (t: Throwable) {
            Illusion.LOGGER.warn("{} self test: {} failed: {}", PREFIX, step.action, t.toString())
        }
    }

    private fun pickFrameGenBackend(backend: Int) {
        forcedFrameGenBackend = backend
        val status = MCDR_ABI.D3D12FgPick(backend)
        Illusion.LOGGER.info("{} self test: pick frame-generation backend {} -> status {}", PREFIX, backend, status)
    }

    private fun probeFrameGeneration() {
        for ((name, backend) in listOf(
            "dlssg" to MCDR_ABI.FG_BACKEND_DLSSG,
            "fsr-fg" to MCDR_ABI.FG_BACKEND_FSRFG,
            "xess-fg" to MCDR_ABI.FG_BACKEND_XESSFG,
        )) {
            val caps = MCDR_ABI.queryFgCaps(backend)
            if (caps == null) {
                Illusion.LOGGER.info("{} probe {}: no ABI v4 entry points", PREFIX, name)
                continue
            }
            Illusion.LOGGER.info(
                "{} probe {}: available={} runtime={} device={} chainCompat={} needsReflex={} " +
                    "needsUi={} needsHudless={} transitions={} maxGen={} chain={} latency={} note={}",
                PREFIX, name, caps.available, caps.runtimeLoaded, caps.deviceSupported, caps.chainCompatible,
                caps.needsReflex, caps.needsUiResource, caps.needsHudless, caps.transitionsInputs,
                caps.maxGeneratedFrames, caps.chainOwner, caps.latencyProvider, caps.note,
            )
        }
    }

    private const val PREFIX: String = "frame-generation"

    private val ACTIONS: Set<String> = setOf(
        "fg-on", "fg-off", "sr-dlss", "sr-fsr", "sr-xess", "sr-none", "probe-fg",
        "fg-backend-xess", "fg-backend-fsr", "fg-backend-dlssg")

    /**
     * P305ab：直接调 `mcdr_dx12FgSetMode`（不经过门面的可用性推断）。
     *
     * 只给自动化用：包装型后端（FSR-FG / XeSS-FG）在 `available=0` 时门面不会选它，`nativeMode()` 也会
     * 得 0，`fgSetMode` 于是早退，强制的 picked 永远走不到包装。这里显式要求「开」，让 native 把 provider
     * 建起来、把链重建挂上。
     */
    private fun pushFrameGenModeDirectly(mode: Int) {
        val backend = forcedFrameGenBackend
        if (backend == MCDR_ABI.FG_BACKEND_NONE) {
            return
        }
        try {
            java.lang.foreign.Arena.ofConfined().use { arena ->
                val desc = arena.allocate(dev.nts.expdxr.d3d12.extern.MCDRLayout.FG_DISPATCH)
                val i32 = java.lang.foreign.ValueLayout.JAVA_INT
                desc.set(i32, dev.nts.expdxr.d3d12.extern.MCDRLayout.FG_DISPATCH_BACKEND, backend)
                desc.set(i32, dev.nts.expdxr.d3d12.extern.MCDRLayout.FG_DISPATCH_MODE, mode)
                desc.set(i32, dev.nts.expdxr.d3d12.extern.MCDRLayout.FG_DISPATCH_NUM_FRAMES, 1)
                MCDR_ABI.D3D12FgSetMode(desc)
            }
        } catch (t: Throwable) {
            Illusion.LOGGER.warn("self-test could not push frame generation mode directly: {}", t.message)
        }
    }
}
