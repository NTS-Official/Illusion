package dev.nts.expdxr.dxrmc.framegen

import dev.nts.expdxr.Illusion
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
 * 动作：`fg-on` / `fg-off` / `sr-dlss` / `sr-fsr` / `sr-xess` / `sr-none`。
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
            Illusion.LOGGER.warn("[dxrmc] {} self test: could not parse \"{}\" (want act@tick,act@tick)", PREFIX, spec)
            return
        }
        Illusion.LOGGER.info(
            "[dxrmc] {} self test armed: {} (ticks are counted from the moment the world is loaded)",
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
            Illusion.LOGGER.info("[dxrmc] {} self test: world loaded, clock started", PREFIX)
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
            Illusion.LOGGER.info("[dxrmc] {} self test: all steps fired, hook idle", PREFIX)
        }
    }

    private fun fire(step: Step) {
        Illusion.LOGGER.info("[dxrmc] {} self test: {} at tick {}", PREFIX, step.action, ticks)
        try {
            when (step.action) {
                "fg-on" -> FrameGeneration.option().set(1)
                "fg-off" -> FrameGeneration.option().set(0)
                "sr-dlss" -> SuperResolution.onDlssToggle(true)
                "sr-none" -> SuperResolution.onDlssToggle(false)
                "sr-fsr" -> SuperResolution.fsrOption().set(true)
                "sr-xess" -> SuperResolution.xessOption().set(true)
            }
        } catch (t: Throwable) {
            Illusion.LOGGER.warn("[dxrmc] {} self test: {} failed: {}", PREFIX, step.action, t.toString())
        }
    }

    private const val PREFIX: String = "frame-generation"

    private val ACTIONS: Set<String> = setOf("fg-on", "fg-off", "sr-dlss", "sr-fsr", "sr-xess", "sr-none")
}
