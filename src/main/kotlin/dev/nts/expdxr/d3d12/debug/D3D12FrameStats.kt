package dev.nts.expdxr.d3d12.debug

import dev.nts.expdxr.d3d12.render.adapter.D3D12AdapterInfo
import dev.nts.expdxr.dxrmc.framegen.FrameGeneration
import kotlin.math.roundToInt

/**
 * F3 帧率行的 `rendered/output` 双数值与 `(FG n.nx)` 倍率。
 * rendered 取 vanilla fps；output 是本类在 present 里累加的提交帧数 —— 生成帧由 native 一次提交多帧，
 * 所以增量只能来自 native 的 `frames` 参数，不能在本类里加 N。
 * FG 关着、非 D3D12 后端、还没有样本时整行原样返回：不显示相等的双数值、假的 0，也不显示遗留倍率。
 * 见 docs/misc/debug-and-crash.md §6。
 */
object D3D12FrameStats {
    /** output 的统计窗口：与原版 fps 相同的 1 秒粒度，两个数字的抖动幅度才对得上。 */
    private const val WINDOW_NANOS = 1_000_000_000L

    /** 原版那行的形状：`123 fps T: inf (fifo)`。 */
    private val FPS_LINE = Regex("^(\\d+) fps(.*)$")

    // 只在渲染线程上访问（present() 与 F3 绘制同线程）。
    private var windowStartNanos = 0L
    private var windowFrames = 0
    private var outputFps = 0


    /**
     * output/rendered 的指数平滑值：`presented` 每帧会在 1/2/3 之间跳，直接用单帧值会闪。
     */
    private var smoothedMultiplier = 1.0

    /** D3D12 后端真正 present 了一帧；由 [dev.nts.expdxr.d3d12.render.core.D3D12GpuSurface.present] 调用。 */
    @JvmStatic
    fun onFramePresented(frames: Int = 1) {
        val now = System.nanoTime()
        if (windowStartNanos == 0L) {
            windowStartNanos = now
        }
        windowFrames += frames.coerceAtLeast(1)
        val elapsed = now - windowStartNanos
        if (elapsed >= WINDOW_NANOS) {
            outputFps = (windowFrames * 1_000_000_000.0 / elapsed).roundToInt()
            windowStartNanos = now
            windowFrames = 0
        }
    }

    /** 最近一个统计窗口里提交给交换链的帧数；还没有样本时返回 0。 */
    @JvmStatic
    fun outputFps(): Int = outputFps

    /** `<rendered>/<output> fps (FG n.nx)`；没有样本时 output 用 rendered 兜底，不显示假的 0。 */
    @JvmStatic
    fun fpsLine(vanilla: String): String {
        if (D3D12AdapterInfo.current == null) {
            return vanilla
        }
        if (!FrameGeneration.isEnabled()) {
            smoothedMultiplier = 1.0
            return vanilla
        }
        val match = FPS_LINE.matchEntire(vanilla) ?: return vanilla
        val rendered = match.groupValues[1].toIntOrNull() ?: return vanilla
        val output = if (outputFps > 0) outputFps else rendered
        val instant = if (rendered > 0) output.toDouble() / rendered else 1.0
        smoothedMultiplier = smoothedMultiplier * 0.8 + instant * 0.2
        // 刚打开 FG、Streamline 还没真正开始插帧的头几帧，比值仍是 1 —— 不显示 `(FG 1.0x)`。
        val frameGen = if (smoothedMultiplier >= 1.25) {
            val tenths = (smoothedMultiplier * 10.0).roundToInt()
            " (FG ${tenths / 10}.${tenths % 10}x)"
        } else {
            ""
        }
        return "${match.groupValues[1]}/$output fps$frameGen${match.groupValues[2]}"
    }
}