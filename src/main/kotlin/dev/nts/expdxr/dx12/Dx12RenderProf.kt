package dev.nts.expdxr.dx12

import java.lang.management.ManagementFactory
import java.util.Locale

/**
 * P50：渲染线程分段计时。
 *
 * 目的：定位 frameInterval 中除 dx12 后端（P3 已覆盖）之外的 CPU 开销分布——
 * 是 MC 的渲染状态提取（extract）、命令录制（render）、世界渲染（renderLevel）、
 * GUI，还是 Present/提交。
 *
 * 各阶段由 mixin 在目标方法 HEAD/RETURN 处调用 begin/end 累加，每 INTERVAL 帧
 * 输出一次均值。开关沿用 DX12_PROF=1（或 DX12_LOG_VERBOSE=1）；关闭时每个埋点
 * 只是一次 static final boolean 判断，运行期无额外开销。
 *
 * 输出示例：
 *   [dx12-java] P50 render n=300 frameInterval=4.05ms frame=3.98ms extract=1.10ms
 *     render=2.40ms[world=2.10 gui=0.25]ms blit=0.05ms present=0.16ms
 *
 * 注：frame − (extract + render + blit + present) 即 update/executePendingTasks/
 * frameLimiter 等其余开销（未单独埋点，用残差观察）。
 *
 * Kotlin 互操作：本类是 17 个 Java mixin 的埋点目标，它们通过
 * `Dx12RenderProf.begin(x)` / `Dx12RenderProf.RENDER_GROUP` 这类**静态**形式访问，
 * 因此 `begin/end/frameStart/frameEnd` 必须标 `@JvmStatic`，阶段常量必须用
 * `const val`（在 object 中会生成寄宿类的静态字段），不能写成普通 `val`。
 */
object Dx12RenderProf {

    @JvmField
    val ENABLED: Boolean = McdrAbi.PROF

    private const val INTERVAL = 300

    /** Minecraft.renderFrame 全程（含 extract/render/present）。 */
    const val FRAME = 0

    /** GameRenderer.extract：渲染状态提取。 */
    const val EXTRACT = 1

    /** GameRenderer.render：本帧命令录制。 */
    const val RENDER = 2

    /** GameRenderer.renderLevel：世界渲染（RENDER 的子集）。 */
    const val WORLD = 3

    /** GuiRenderer.render（RENDER 的子集）。 */
    const val GUI = 4

    /** Dx12GpuSurface.blitFromTexture：等 back buffer + blit 录制。 */
    const val BLIT = 5

    /** Dx12GpuSurface.present：swapchain Present。 */
    const val PRESENT = 6

    // ---- P51：renderLevel（WORLD）内部细分，定位世界渲染 2~3.6ms 的构成 ----

    /** LevelRenderer.submitFeatures：实体/方块实体/Gizmo 提交。 */
    const val SUBMIT_FEATURES = 7

    /** LevelRenderer.prepareChunkRenders：遍历可见 section × layer 生成绘制列表。 */
    const val PREPARE_CHUNKS = 8

    /** LevelRenderer.compileSections：分发区块重建任务。 */
    const val COMPILE_SECTIONS = 9

    /** SectionRenderDispatcher.uploadTerrainBuffersToGpu：地形顶点/索引上传。 */
    const val UPLOAD_TERRAIN = 10

    /** ChunkSectionsToRender.renderGroup：实际绘制 pass（含 multidraw 批量）。 */
    const val RENDER_GROUP = 11

    /** SectionOcclusionGraph.update：可见性/遮挡图更新。 */
    const val OCCLUSION = 12

    // ---- P52：world 中尚未归因的 ~1.1ms 继续细分 ----

    /** FeatureRenderDispatcher.prepareFrame：实体/方块实体提交排序与准备。 */
    const val PREPARE_FEATURES = 13

    /** FrameGraphBuilder.execute：整帧所有 pass 体的执行（含 renderGroup）。 */
    const val EXECUTE_FRAMEGRAPH = 14

    /** PreparedFrame.executeSolid：实体/方块实体实际绘制。 */
    const val EXEC_FEATURES = 15

    private const val N = 16

    private val acc = LongArray(N)
    private val mark = LongArray(N)
    private var lastFrameStartNs = 0L
    private var accIntervalNs = 0L
    private var frames = 0
    private var samplerStarted = false

    // ---- P52：本区间内 GC 次数/耗时增量 + 当前堆占用，用于判断是否有分配风暴。 ----
    private var gcCountBase = -1L
    private var gcTimeBase = -1L

    @JvmStatic
    fun begin(stage: Int) {
        if (ENABLED) {
            mark[stage] = System.nanoTime()
        }
    }

    @JvmStatic
    fun end(stage: Int) {
        if (ENABLED) {
            acc[stage] += System.nanoTime() - mark[stage]
        }
    }

    /** 帧首：累计相邻帧起始的时间差（即真实 frameInterval）。 */
    @JvmStatic
    fun frameStart() {
        if (!ENABLED) {
            return
        }
        if (!samplerStarted) {
            samplerStarted = true
            Dx12StackSampler.attach()
            Dx12StackSampler.start()
        }
        val now = System.nanoTime()
        if (lastFrameStartNs != 0L) {
            accIntervalNs += now - lastFrameStartNs
        }
        lastFrameStartNs = now
    }

    /** 帧尾：每 INTERVAL 帧输出一次均值并清零。 */
    @JvmStatic
    fun frameEnd() {
        if (!ENABLED) {
            return
        }
        if (++frames % INTERVAL != 0) {
            return
        }
        val inv = 1.0 / INTERVAL
        System.err.println(
            "[dx12-java] P50 render n=$INTERVAL" +
                " frameInterval=${ms(accIntervalNs * inv)}ms" +
                " frame=${ms(acc[FRAME] * inv)}ms" +
                " extract=${ms(acc[EXTRACT] * inv)}ms" +
                " render=${ms(acc[RENDER] * inv)}ms" +
                "[world=${ms(acc[WORLD] * inv)} gui=${ms(acc[GUI] * inv)}]ms" +
                " blit=${ms(acc[BLIT] * inv)}ms" +
                " present=${ms(acc[PRESENT] * inv)}ms"
        )
        System.err.println(
            "[dx12-java] P51 world n=$INTERVAL" +
                " submitFeatures=${ms(acc[SUBMIT_FEATURES] * inv)}ms" +
                " prepareChunks=${ms(acc[PREPARE_CHUNKS] * inv)}ms" +
                " renderGroup=${ms(acc[RENDER_GROUP] * inv)}ms" +
                " compileSections=${ms(acc[COMPILE_SECTIONS] * inv)}ms" +
                " uploadTerrain=${ms(acc[UPLOAD_TERRAIN] * inv)}ms" +
                " occlusion=${ms(acc[OCCLUSION] * inv)}ms"
        )
        System.err.println(
            "[dx12-java] P52 world2 n=$INTERVAL" +
                " prepareFeatures=${ms(acc[PREPARE_FEATURES] * inv)}ms" +
                " execFrameGraph=${ms(acc[EXECUTE_FRAMEGRAPH] * inv)}ms" +
                " [execFeaturesSolid=${ms(acc[EXEC_FEATURES] * inv)}ms]" +
                " " + gcStats()
        )
        System.err.println(Dx12StackSampler.report(10))
        System.err.flush()
        acc.fill(0L)
        accIntervalNs = 0L
    }

    private fun ms(ns: Double): String = String.format(Locale.ROOT, "%.2f", ns / 1_000_000.0)

    private fun gcStats(): String {
        var count = 0L
        var time = 0L
        for (gc in ManagementFactory.getGarbageCollectorMXBeans()) {
            val c = gc.collectionCount
            val t = gc.collectionTime
            if (c > 0L) {
                count += c
            }
            if (t > 0L) {
                time += t
            }
        }
        if (gcCountBase < 0L) {
            gcCountBase = count
            gcTimeBase = time
        }
        val dc = count - gcCountBase
        val dt = time - gcTimeBase
        gcCountBase = count
        gcTimeBase = time
        val usedMb = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) shr 20
        return String.format(Locale.ROOT, "gc(per%dF)=%d/%.1fms heap=%dMB", INTERVAL, dc, dt.toDouble(), usedMb)
    }
}
