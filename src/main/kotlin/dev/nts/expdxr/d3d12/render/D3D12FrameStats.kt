package dev.nts.expdxr.d3d12.render

import dev.nts.expdxr.d3d12.render.adapter.D3D12AdapterInfo
import dev.nts.expdxr.dxrmc.nvidia.dlss.DLSSFrameGeneration
import kotlin.math.roundToInt

/**
 * F3 帧率行的「rendered/output」双数值与 `(FG n.nx)` 倍率（P166 / P213，帧生成真正接上后）。
 *
 * - rendered = 原版 [net.minecraft.client.Minecraft.getFps] 的计数（真正渲染出来的帧）；
 * - output   = 本类在 [dev.nts.expdxr.d3d12.render.core.D3D12GpuSurface.present] 里数到的
 *              「真正提交给交换链的帧」，每帧的增量取自 native 的 `mcdr_dx12GetDlssgPresented`。
 *
 * 帧生成**关着**时整行保持原版形状（`"123 fps T: …"`）：双数值只用来体现「输出帧率高于渲染
 * 帧率」，没有生成帧时它只会多出两个相等的数字（用户 m01219 报的就是关掉 FG 后仍显示
 * `120/240 (FG 2.0x)`，因为 native 那时不会把每帧提交数复位到 1）。
 *
 * 帧生成开着时要动两点：
 * 1. 生成帧的呈现必须经过同一个计数点 —— 目前 native 一次性提交多帧，JVM 只调用一次
 *    present，所以增量必须取自 native（`frames` 参数），不能在本类里加 N。
 * 2. rendered 继续取原版 fps（真实渲染帧），output 继续取本类，语义才与「帧生成让输出帧率
 *    高于渲染帧率」一致。
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
     * 把原版那行 `"<rendered> fps T: …"` 改成 `"<rendered>/<output> fps (FG n.nx) T: …"`。
     *
     * 帧生成关着（或后端不是 D3D12、output 还没有样本：进游戏第一秒、暂停时不 present）时原样
     * 返回 —— 不显示假的 0、0/0，也不显示上一次开 FG 时遗留的倍率。
     */
    @JvmStatic
    fun fpsLine(vanilla: String): String {
        if (D3D12AdapterInfo.current == null) {
            return vanilla
        }
        // 用户 m01219：关掉帧生成后这里仍会显示 `120/240 (FG 2.0x)` —— native 的“每帧提交数”
        // 那时停在旧值上。双数值只在真的有生成帧时才有意义，关着就交给原版。
        if (!DLSSFrameGeneration.isEnabled()) {
            smoothedMultiplier = 1.0
            return vanilla
        }
        val match = FPS_LINE.matchEntire(vanilla) ?: return vanilla
        val rendered = match.groupValues[1].toIntOrNull() ?: return vanilla
        val output = if (outputFps > 0) outputFps else rendered
        // P213：用 output/rendered 的比值（不是单帧 presented）做平滑，避免数字闪烁。
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