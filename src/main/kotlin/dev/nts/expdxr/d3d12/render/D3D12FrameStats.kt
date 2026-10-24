package dev.nts.expdxr.d3d12.render

import dev.nts.expdxr.d3d12.render.adapter.D3D12AdapterInfo
import kotlin.math.roundToInt

/**
 * F3 帧率行的「rendered/output」双数值（P166，当前是帧生成的占位实现）。
 *
 * 现在还没有帧生成，所以稳态下两个数字相等：
 * - rendered = 原版 [net.minecraft.client.Minecraft.getFps] 的计数（真正渲染出来的帧）；
 * - output   = 本类在 [dev.nts.expdxr.d3d12.render.core.D3D12GpuSurface.present] 里数到的
 *              「真正提交给交换链的帧」。
 *
 * 接上帧生成后要动两处：
 * 1. 生成帧的呈现必须经过同一个计数点。如果将来改由 native 在一次 present 里提交多帧
 *    （DLSS-G / Optical Flow 就长这样，JVM 只调用一次），计数就得搬进 native 的 present
 *    路径，而不是在 [onFramePresented] 里加 N —— 否则 output 永远等于 rendered。
 * 2. rendered 继续取原版 fps（即真实渲染帧），output 继续取本类，语义才与「帧生成让
 *    输出帧率高于渲染帧率」一致。
 *
 * 只有 D3D12 后端计数：[fpsLine] 在 [D3D12AdapterInfo.current] 为 null（OpenGL / Vulkan）
 * 时原样返回，其他后端既不加斜杠也不显示 0。
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
     * P213：输出/渲染比值的指数平滑值。
     *
     * <p>F3 每秒会被重画很多次，而 `presented` 每帧都可能变（动态多帧会在 1/2/3 之间跳），
     * 直接显示单帧值会闪成“有时候有、有时候没有”。这里对比值做低通，再按阈值决定要不要显示。
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

    /**
     * 把原版那行 `"<rendered> fps T: …"` 改成 `"<rendered>/<output> fps T: …"`。
     *
     * 非 D3D12 后端、或 output 还没有样本（进游戏第一秒、暂停时不 present）时保持原样，
     * 不显示一个假的 0 或 0/0。
     */
    @JvmStatic
    fun fpsLine(vanilla: String): String {
        if (D3D12AdapterInfo.current == null) {
            return vanilla
        }
        val match = FPS_LINE.matchEntire(vanilla) ?: return vanilla
        val rendered = match.groupValues[1].toIntOrNull() ?: return vanilla
        val output = if (outputFps > 0) outputFps else rendered
        // P213：用 output/rendered 的比值（不是单帧 presented）做平滑，避免数字闪烁。
        val instant = if (rendered > 0) output.toDouble() / rendered else 1.0
        smoothedMultiplier = smoothedMultiplier * 0.8 + instant * 0.2
        val frameGen = if (smoothedMultiplier >= 1.25) {
            val tenths = (smoothedMultiplier * 10.0).roundToInt()
            " (FG ${tenths / 10}.${tenths % 10}x)"
        } else {
            ""
        }
        return "${match.groupValues[1]}/$output fps$frameGen${match.groupValues[2]}"
    }
}