package dev.nts.expdxr.d3d12.render.shader

import dev.nts.expdxr.Illusion.Companion.CLASSPATH
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.LongAdder

/**
 * 渲染线程 CPU 调用栈采样器（不是纹理采样器）：每 [INTERVAL_MS] 抓一次栈顶，把后端开销与 MC/Blaze3D 分开归因。
 * `Thread.getStackTrace()` 会强制目标线程进安全点、开销约 1~2%，只在性能剖析开关打开时启用。见 docs/shaders/README.md §15
 */
object D3D12StackSampler {
    private const val INTERVAL_MS = 2L
    /** 每个 key 存一份栈摘要，上限防止无界增长。 */
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
                // 诊断线程绝不干扰游戏。
            }
        }
    }

    /** 跳过本分析器自身与 `D3D12Prof*` 埋点帧，露出下面真正的业务方法。 */
    private fun skip(e: StackTraceElement): Boolean {
        val cn = e.className
        if (cn.startsWith("$CLASSPATH.backend.render.shader.D3D12StackSampler")
            || cn.startsWith("$CLASSPATH.backend.render.adapter.D3D12RenderProf")) {
            return true
        }
        return e.methodName.startsWith("D3D12Prof")
    }

    /** 输出区间采样直方图、top3 调用链（各自一行），并清零。 */
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
            return "stack sample: no samples yet"
        }
        entries.sortWith(Comparator.comparingLong<Map.Entry<String, LongAdder>> { it.value.sum() }.reversed())
        return buildString {
            append("stack sample n=").append(total)
              .append(" ours=").append("%.0f%%".format(Locale.ROOT, if (total == 0L) 0.0 else 100.0 * ours / total))
              .append(" mc=").append("%.0f%%".format(Locale.ROOT, if (total == 0L) 0.0 else 100.0 * (total - ours) / total))
              .append(" |")
            val n = topN.coerceAtMost(entries.size)
            for (i in 0 until n) {
                val e = entries[i]
                append(' ').append(e.key).append('=').append(e.value.sum())
            }
            // top3 调用链：热点 <- 调用者 <- 再上层（定位真实归属）。
            val nt = 3.coerceAtMost(entries.size)
            for (i in 0 until nt) {
                val key = entries[i].key
                val ex = STACKS[key]
                if (ex != null) {
                    append("\nstack sample trace ").append(key).append(" <= ").append(ex)
                }
            }
            STACKS.clear()
        }
    }

    /** 从采样栈的第 from 层起，拼接 [STACK_DEPTH] 层 "类.方法" 摘要。 */
    private fun excerpt(stack: Array<StackTraceElement>, from: Int): String {
        val end = stack.size.coerceAtMost(from + STACK_DEPTH)
        return (from until end).joinToString(" <= ") { "${simpleName(stack[it].className)}.${stack[it].methodName}" }
    }

    private fun simpleName(fq: String): String {
        val dot = fq.lastIndexOf('.')
        return if (dot < 0) fq else fq.substring(dot + 1)
    }
}
