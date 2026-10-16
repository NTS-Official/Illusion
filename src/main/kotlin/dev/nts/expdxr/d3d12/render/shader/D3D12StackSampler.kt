package dev.nts.expdxr.d3d12.render.shader

import dev.nts.expdxr.Illusion.Companion.CLASSPATH
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.LongAdder

/**
 * P53：渲染线程轻量采样分析器（poor-man's profiler）。
 *
 * 为什么需要它：P50/P51/P52 的分段计时把时间记在 MC 的方法边界上，但 MC 的
 * 那些窗口内部会调用我们的 JNI 后端——例如 {@code stagedVertexBuffer.upload()}、
 * SectionRenderDispatcher 的 buffer slice 获取、帧图资源获取、clear/lightmap/
 * outline 等。因此"MC 固有 Java"与"我们后端开销"在分段计时里是混在一起的，
 * 无法归因。
 *
 * 本类用一个后台线程每 {@link #INTERVAL_MS} 毫秒抓一次渲染线程的调用栈，统计
 * 栈顶（跳过本分析器与埋点方法）落点。这样可以直接回答：渲染线程的时间究竟花在
 * {@code McdrAbi.backend*}（我们的前端/JNI）还是 {@code net.minecraft.*} /
 * {@code com.mojang.blaze3d.*}（MC/Blaze3D 自身逻辑）里。
 *
 * 输出为"类名.方法名"（不再只有方法名，避免 writeBytes 这类 JDK/日志方法无法
 * 区分）；并对 top3 热点额外打印 8 层调用链，用于定位真正的调用者。
 *
 * 说明：{@code Thread.getStackTrace()} 会强制目标线程进入安全点，本身有可观开销
 * （约 1~2%），仅用于诊断，DX12_PROF=1 时才启用。
 */
object D3D12StackSampler {
    private const val INTERVAL_MS = 2L
    /** 每个 key 保存一份栈摘要，上限防止异常增长。 */
    private const val STACK_CAP = 512
    /** 栈摘要展示的层数。 */
    private const val STACK_DEPTH = 8

    private val COUNTS: MutableMap<String, LongAdder> = ConcurrentHashMap()
    private val STACKS: MutableMap<String, String> = ConcurrentHashMap()
    private val TOTAL = LongAdder()
    private val OURS = LongAdder()

    @Volatile
    private var target: Thread? = null
    private var sampler: Thread? = null
    private var started = false

    /** 在渲染线程上调用，登记采样目标。 */
    @JvmStatic
    fun attach() {
        target = Thread.currentThread()
    }

    @JvmStatic
    @Synchronized
    fun start() {
        if (started) {
            return
        }
        started = true
        sampler = Thread(D3D12StackSampler::loop, "backend-stack-sampler")
        sampler!!.isDaemon = true
        sampler!!.priority = Thread.MAX_PRIORITY
        sampler!!.start()
    }

    private fun loop() {
        while (!Thread.currentThread().isInterrupted) {
            try {
                val t = target
                if (t != null && t.isAlive) {
                    val stack = t.stackTrace
                    var i = 0
                    while (i < stack.size && skip(stack[i])) {
                        i++
                    }
                    if (i < stack.size) {
                        val top = stack[i]
                        val key = "${simpleName(top.className)}.${top.methodName}"
                        COUNTS.computeIfAbsent(key) { LongAdder() }.increment()
                        TOTAL.increment()
                        if (top.className.startsWith("$CLASSPATH.")) {
                            OURS.increment()
                        }
                        if (STACKS.size < STACK_CAP) {
                            val topIndex = i
                            STACKS.computeIfAbsent(key) { excerpt(stack, topIndex) }
                        }
                    }
                }
                Thread.sleep(INTERVAL_MS)
            } catch (ie: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            } catch (ignored: Throwable) {
                // 诊断线程绝不干扰游戏
            }
        }
    }

    /** 跳过本分析器自身与 P50~P52 的埋点方法，露出下面真正的业务方法。 */
    private fun skip(e: StackTraceElement): Boolean {
        val cn = e.className
        if (cn.startsWith("$CLASSPATH.backend.render.shader.D3D12StackSampler")
            || cn.startsWith("$CLASSPATH.backend.render.adapter.D3D12RenderProf")) {
            return true
        }
        return e.methodName.startsWith("D3D12Prof")
    }

    /** 输出本区间采样直方图、top3 调用链（各自一行）并清零。 */
    @JvmStatic
    fun report(topN: Int): String {
        val total = TOTAL.sum()
        val ours = OURS.sum()
        TOTAL.reset()
        OURS.reset()

        val entries: MutableList<Map.Entry<String, LongAdder>> = ArrayList(COUNTS.size)
        for (e in COUNTS.entries) {
            val v = e.value.sum()
            if (v > 0L) {
                entries.add(e)
            }
        }
        COUNTS.clear()
        if (entries.isEmpty()) {
            return "[backend-java] P53 sample: no samples yet"
        }
        entries.sortWith(Comparator.comparingLong<Map.Entry<String, LongAdder>> { it.value.sum() }.reversed())
        return buildString {
            append("[backend-java] P53 sample n=").append(total)
              .append(" ours=").append("%.0f%%".format(Locale.ROOT, if (total == 0L) 0.0 else 100.0 * ours / total))
              .append(" mc=").append("%.0f%%".format(Locale.ROOT, if (total == 0L) 0.0 else 100.0 * (total - ours) / total))
              .append(" |")
            val n = topN.coerceAtMost(entries.size)
            for (i in 0 until n) {
                val e = entries[i]
                append(' ').append(e.key).append('=').append(e.value.sum())
            }
            // top3 调用链：热点 <- 调用者 <- 再上层 ...（定位真实归属）
            val nt = 3.coerceAtMost(entries.size)
            for (i in 0 until nt) {
                val key = entries[i].key
                val ex = STACKS[key]
                if (ex != null) {
                    append("\n[backend-java] P53 trace ").append(key).append(" <= ").append(ex)
                }
            }
            STACKS.clear()
        }
    }

    /** 从采样栈的第 from 层起，拼接 STACK_DEPTH 层 "类.方法" 摘要。 */
    private fun excerpt(stack: Array<StackTraceElement>, from: Int): String {
        val end = stack.size.coerceAtMost(from + STACK_DEPTH)
        return (from until end).joinToString(" <= ") { "${simpleName(stack[it].className)}.${stack[it].methodName}" }
    }

    private fun simpleName(fq: String): String {
        val dot = fq.lastIndexOf('.')
        return if (dot < 0) fq else fq.substring(dot + 1)
    }
}
