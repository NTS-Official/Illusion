package dev.nts.expdxr.d3d12.debug

import java.io.FileWriter
import kotlin.collections.iterator

/**
 * P12b: global exit watchdog.
 *
 * When Minecraft shuts down but the render thread (= main thread) is already
 * gone while the process is still alive — for example a non-daemon worker
 * blocked in one of our backend's fence waits — the JVM hangs forever, and the
 * launcher (PCL2) waits on it as the parent process, which looks like "the
 * launcher is completely frozen". This daemon thread notices the render thread
 * disappearing, grants a 20-second grace period, and if the JVM is still alive
 * dumps every thread stack to stderr and to %TEMP%\backend-java.log and then
 * forces System.exit(1). That guarantees the launcher recovers while still
 * leaving evidence of the hang behind.
 *
 * Ported unchanged from the Fabric build's `Dx12Mod.startExitWatchdog()`; it is
 * modloader-agnostic. One safety guard was added: the watchdog only arms after
 * it has seen the render thread at least once, so a slow startup can never be
 * mistaken for a shutdown hang.
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
                    // Never observed -> we are still starting up, not shutting down.
                    if (!sawRenderThread) continue

                    // The render thread is gone: a normal shutdown exits the JVM
                    // almost immediately, so grant a further 15 seconds.
                    Thread.sleep(GRACE_MS)
                    if (!renderThreadAlive()) {
                        dumpAndExit()
                    }
                }
            } catch (e: InterruptedException) {
                // daemon thread — nothing to clean up
            }
        }, "backend-exit-watchdog")
        watchdog.isDaemon = true
        watchdog.start()
    }

    private fun renderThreadAlive(): Boolean =
        Thread.getAllStackTraces().keys.any { RENDER_THREAD_NAME == it.name }

    private fun dumpAndExit() {
        val msg = buildString {
            append("[backend-java] WATCHDOG: Render thread gone but JVM alive >20s, forcing exit\n")
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
            // best effort only
        }
        System.err.println("[backend-java] WATCHDOG: System.exit(1)")
        System.exit(1)
    }
}
