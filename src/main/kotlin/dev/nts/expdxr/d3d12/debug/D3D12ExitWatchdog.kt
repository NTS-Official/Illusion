package dev.nts.expdxr.d3d12.debug

import java.io.FileWriter
import kotlin.collections.iterator

/**
 * 退出卡死看守：见 docs/misc/debug-and-crash.md §1.5。
 * 只在**见过一次** `Render thread` 之后才判退出，否则慢启动会被误判成关停卡死。
 * 判定成立时必须 `System.exit(1)`，否则启动器作为父进程会一直等下去。
 */
object D3D12ExitWatchdog {
    private const val RENDER_THREAD_NAME = "Render thread"
    private const val POLL_MS = 5_000L
    private const val GRACE_MS = 15_000L

    @Volatile
    private var started = false

    @JvmStatic
    fun start() {
        if (started) return
        started = true

        val watchdog = Thread({
            try {
                var sawRenderThread = false
                while (true) {
                    Thread.sleep(POLL_MS)
                    val renderAlive = renderThreadAlive()
                    if (renderAlive) {
                        sawRenderThread = true
                        continue
                    }
                    // 没见过 = 还在启动，不是关停卡死。
                    if (!sawRenderThread) continue

                    // 正常关停几乎立刻退出 JVM，所以再宽限 15 s 复核。
                    Thread.sleep(GRACE_MS)
                    if (!renderThreadAlive()) {
                        dumpAndExit()
                    }
                }
            } catch (e: InterruptedException) {
                // 守护线程，无需清理
            }
        }, "backend-exit-watchdog")
        watchdog.isDaemon = true
        watchdog.start()
    }

    private fun renderThreadAlive(): Boolean =
        Thread.getAllStackTraces().keys.any { RENDER_THREAD_NAME == it.name }

    private fun dumpAndExit() {
        val msg = buildString {
            append("WATCHDOG: render thread gone but the JVM is still alive after 20s, forcing exit\n")
            for ((t, stack) in Thread.getAllStackTraces()) {
                append("Thread ").append(t.name)
                    .append(" [").append(t.state).append("] daemon=")
                    .append(t.isDaemon).append('\n')
                for (el in stack) {
                    append("    at ").append(el).append('\n')
                }
            }
        }
        System.err.println(msg)
        System.err.flush()
        try {
            val path = System.getProperty("java.io.tmpdir") ?: "."
            FileWriter("${path}\\backend-java.log", true).use { it.write("${msg}\n") }
        } catch (ignored: Exception) {
            // best effort：写不了文件也照样退出
        }
        System.err.println("WATCHDOG: System.exit(1)")
        System.exit(1)
    }
}
