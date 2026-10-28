package dev.nts.expdxr.dxrmc.framegen

import dev.nts.expdxr.Illusion
import dev.nts.expdxr.d3d12.extern.MCDR_ABI
import dev.nts.expdxr.dxrmc.sr.SuperResolution
import net.minecraft.client.Minecraft
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.common.NeoForge

/**
 * 自动化验收钩子（不是功能入口）：系统属性 `dxrmc.selfTest` = `action@tick,...`，tick 从世界加载完成起算。
 * 走的是与玩家操作相同的路径；只有「强制选后端」会直推 native 绕开可用性推断。
 * 规格、动作表与降级说明见 docs/framegen/README.md §4。
 */
object FrameGenerationSelfTest {
    /** 系统属性名；gradle 的 `-Pdxrmc.selfTest` 会转发成它。 */
    const val PROPERTY: String = "dxrmc.selfTest"

    private data class Step(val atTick: Long, val action: String) {
        var fired: Boolean = false
    }

    private var installed: Boolean = false
    private var forcedFrameGenBackend: Int = MCDR_ABI.FG_BACKEND_NONE

    /** 空表 = 没启用，或规格写错。 */
    private var plan: List<Step> = emptyList()

    /** 世界 tick 计数；负数 = 还没进世界。 */
    private var ticks: Long = -1

    /** 读属性并（必要时）挂 tick 监听；必须在客户端调用。 */
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
        // 时钟从进入世界开始：标题界面也在 tick，按进程启动算会让动作提前。
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
                    // 门面在 available=0 时会传 mode=0，这里直推 native 绕开可用性推断。
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
                // 界面只显示一个后端的状态行，三家的完整能力表只有这里能取证。
                "probe-fg" -> probeFrameGeneration()
                // 强制 picked 后端：界面不会选 available=0 的后端，只有这里能让它们跑起来。
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

    /** 直接调 `mcdr_dx12FgSetMode`，绕开门面的可用性推断（available=0 时门面会传 mode=0）。
     * 只给自动化用：让 native 把 provider 建起来、把链重建挂上。 */
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
