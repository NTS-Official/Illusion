package io.github.dxegp.experimental.raytracing

import io.github.dxegp.d3d12.backend.D3D12CommandEncoderBackend
import io.github.dxegp.d3d12.extern.MCDR_ABI
import io.github.dxegp.misc.IllusionLogger
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import kotlin.math.round

/**
 * 逐阶段 GPU 耗时（S2 2-f）：G-Buffer pass / 光追派发 / 合光 / 上屏 blit 各一段，攒满一个统计窗口后。
 * 绝不能在同一帧里刚写完就读，那会把渲染线程卡在等待上。
 * 见 docs/rt/README.md
 */
object D3D12RtStats {
    private const val POOL_SIZE = 8

    private const val SLOT_START = 0

    /** G-Buffer pass 结束；调用方按名字打点，不要传裸数字。 */
    const val SLOT_GBUFFER = 1

    /** 光追派发结束。 */
    const val SLOT_TRACE = 2

    /** 降噪派发结束；降噪没跑时由调用方紧跟着光追打点（这一段就算 0）。 */
    const val SLOT_DENOISE = 3

    const val SLOT_SR = 4

    const val SLOT_COMPOSITE = 5

    /** 上屏 blit 结束。 */
    const val SLOT_BLIT = 6

    /** 槽位总数；读查询与缓冲都要它，写死数字会让两者悄悄对不上。 */
    private const val SLOT_COUNT = SLOT_BLIT + 1

    /** 统计窗口：太短会把日志刷满，太长则第一个窗口要等半分钟才见得到数。 */
    private const val WINDOW_NANOS = 5_000_000_000L

    private const val FILE_NAME = "rt_stats.json"

    private var pool = 0L
    private var frequency = 0L
    private var windowStartNanos = 0L
    private var windowFrames = 0L

    private var deepestSlot = SLOT_START
    private var writtenSlots = 0
    private var sampleFrame = 0L
    private data class PendingWindow(val metrics: String, val count: Int, val frame: Long)
    private var pendingWindow: PendingWindow? = null
    private var warnings = 0

    private val timestamps = LongArray(SLOT_COUNT)

    /** 本窗口累计的编码耗时（纳秒）与 compute 派发次数：CPU 提交时间是六类基线之一。 */
    private var cpuNanosTotal = 0L
    private var dispatchesTotal = 0L

    private var vramPeakBytes = 0L

    /** 上一帧的规模，给 JSON 行标注这组耗时跑在多大规模上。 */
    private var frameWidth = 0
    private var frameHeight = 0
    private var traceWidth = 0
    private var traceHeight = 0
    private var recordCount = 0

    private var blasBuilds = 0L
    private var blasRefits = 0L
    private var blasCompacts = 0L
    private var blasVramPeak = 0L
    private var blasScratchPeak = 0L
    private var tlasInstances = 0
    private var tlasMicros = 0L

    /** BLAS 侧录制耗时（微秒，窗口累计）与单条峰值：与 TLAS 分开记，成本才能按段对账。 */
    private var blasEncodeMicros = 0L
    private var blasBuildPeakMicros = 0L

    private var tier = "none"

    private fun ensurePool(): Boolean {
        if (pool != 0L) return true
        frequency = 0L
        val created = MCDR_ABI.D3D12CreateQueryPool(POOL_SIZE)
        if (created == 0L) {
            fail("query pool create failed: ${MCDR_ABI.D3D12LastError()}")
            return false
        }
        val createdFrequency = MCDR_ABI.D3D12GetTimestampFrequency()
        if (createdFrequency <= 0L) {
            MCDR_ABI.D3D12DestroyQueryPool(created)
            fail("timestamp frequency unavailable")
            return false
        }
        pool = created
        frequency = createdFrequency
        return true
    }

    fun beginFrame(encoder: D3D12CommandEncoderBackend?, width: Int, height: Int) {
        if (encoder == null || !ensurePool()) return
        pollWindow()
        val now = System.nanoTime()
        if (windowStartNanos == 0L) {
            windowStartNanos = now
        } else if (now - windowStartNanos >= WINDOW_NANOS && windowFrames > 0L &&
            pendingWindow == null && queueWindow(encoder)) {
            windowStartNanos = now
            windowFrames = 0L
            cpuNanosTotal = 0L
            dispatchesTotal = 0L
            blasBuilds = 0L
            blasRefits = 0L
            blasCompacts = 0L
            blasEncodeMicros = 0L
            blasBuildPeakMicros = 0L
            tlasMicros = 0L
        }
        frameWidth = width
        frameHeight = height
        writtenSlots = 0
        deepestSlot = SLOT_START
        sampleFrame++
        // 帧计数放在这里：一帧开始就一定记到 START，上屏那一步失败也不会让整份统计停更。
        windowFrames++
        mark(encoder, SLOT_START)
    }

    /** 在命令列表里打一个时间戳；槽位含义见 `SLOT_*`。 */
    fun mark(encoder: D3D12CommandEncoderBackend?, slot: Int) {
        if (encoder == null || pool == 0L) return
        encoder.ensureListOpen()
        MCDR_ABI.D3D12WriteTimestamp(encoder.nativeHandle(), pool, slot)
        writtenSlots = writtenSlots or (1 shl slot)
        if (slot > deepestSlot) {
            deepestSlot = slot
        }
    }

    fun nativeTransactionQueries(): Long = pool

    fun acceptNativeTimestamps(mask: Int) {
        for (offset in 0..3) {
            if (mask and (1 shl offset) == 0) continue
            val slot = SLOT_TRACE + offset
            writtenSlots = writtenSlots or (1 shl slot)
            deepestSlot = maxOf(deepestSlot, slot)
        }
    }

    /** 编码侧耗时（纳秒）：统计窗口内累加，flush 时除以帧数得到每帧 CPU 提交时间。 */
    fun onCpuEncode(nanos: Long) {
        if (nanos > 0L) {
            cpuNanosTotal += nanos
        }
    }

    /** 本帧的 compute 派发次数（光追 1 次 + 合光 1 次，调试视图另加 1 次）。 */
    fun onDispatches(count: Int) {
        if (count > 0) {
            dispatchesTotal += count
        }
    }

    /** 我们持有的显存（G-Buffer + 辐亮度 + 镜像 + 常量），取窗口内峰值。 */
    fun onVram(bytes: Long) {
        if (bytes > vramPeakBytes) {
            vramPeakBytes = bytes
        }
    }

    /** 本帧新建了几条 BLAS（拓扑变）与 refit 了几条（只有顶点动）。 */
    fun onBlasBuilt(builds: Int, refits: Int) {
        if (builds > 0) blasBuilds += builds
        if (refits > 0) blasRefits += refits
    }

    /** 压缩拷贝了几条，以及因此持有的加速结构显存与 scratch 峰值。 */
    fun onBlasCompacted(count: Int, heldBytes: Long, scratchBytes: Long) {
        if (count > 0) blasCompacts += count
        if (heldBytes > blasVramPeak) blasVramPeak = heldBytes
        if (scratchBytes > blasScratchPeak) blasScratchPeak = scratchBytes
    }

    /**
     * BLAS 侧的录制耗时（微秒）：本帧所有重建/refit/压缩的 CPU 时间，以及单条 BLAS 的峰值。
     *
     * 与 [onTlasBuilt] 分开记：两者都是 CPU 侧录制时间，GPU 侧要等时间戳槽扩到加速结构才有。
     */
    fun onBlasEncoded(micros: Long, peakBuildMicros: Long) {
        if (micros > 0L) blasEncodeMicros += micros
        if (peakBuildMicros > blasBuildPeakMicros) blasBuildPeakMicros = peakBuildMicros
    }

    /** TLAS 每帧重建：实例数与这次录制的 CPU 耗时（微秒）。 */
    fun onTlasBuilt(instances: Int, micros: Long) {
        tlasInstances = instances
        if (micros > 0L) tlasMicros += micros
    }

    /** 记录本窗口实际跑的追踪档；只影响统计的 `tier` 一格，不参与任何判据。 */
    fun setTier(name: String) {
        tier = name
    }

    /** 派发规模只有真正派发的那一帧才知道，记下来给 JSON 行用。 */
    fun onTraceDispatched(width: Int, height: Int, records: Int) {
        traceWidth = width
        traceHeight = height
        recordCount = records
    }

    fun sceneJson(scene: MCDR_ABI.RtSceneStats?): String = if (scene == null) "null" else buildString {
        append("{\"world_epoch\":").append(scene.worldEpoch).append(',')
        append("\"frame_id\":").append(scene.frameId).append(',')
        append("\"target_snapshot\":").append(scene.targetSnapshotId).append(',')
        append("\"revision\":").append(scene.sceneRevision).append(',')
        append("\"live_revision\":").append(scene.liveRevision).append(',')
        append("\"pending_snapshot\":").append(scene.pendingSnapshotId).append(',')
        append("\"records\":").append(scene.recordCount).append(',')
        append("\"recorded\":").append(scene.recordedCount).append(',')
        append("\"pending_builds\":").append(scene.pendingBuildCount).append(',')
        append("\"obsolete\":").append(scene.obsoleteCount).append(',')
        append("\"absent\":").append(scene.absentCount).append(',')
        append("\"proxy_sections\":").append(scene.proxySectionCount).append(',')
        append("\"frame_builds\":").append(scene.buildsThisFrame).append(',')
        append("\"oldest_age_frames\":").append(scene.oldestAgeFrames).append('}')
    }

    fun workBudgetJson(work: MCDR_ABI.RtSceneWorkBudget?): String = if (work == null) "null" else buildString {
        append("{\"world_epoch\":${work.worldEpoch},\"frame_id\":${work.frameId},")
        append("\"cpu_limit_ns\":${work.cpuLimitNanos},\"gpu_limit_ns\":${work.gpuLimitNanos},")
        append("\"triangle_limit\":${work.triangleLimit},\"cpu_ns\":${work.cpuNanos},")
        append("\"predicted_gpu_ns\":${work.predictedNanos},\"triangles\":${work.triangles},")
        append("\"publications\":${work.publications},\"deferred_total\":${work.deferred},")
        append("\"oversized_total\":${work.oversized},\"samples_total\":${work.samples},")
        append("\"last_gpu_ns\":${work.lastGpuNanos},\"gpu_ns_total\":${work.totalGpuNanos},")
        append("\"cpu_overruns_total\":${work.cpuOverruns},\"gpu_overruns_total\":${work.gpuOverruns},")
        append("\"pending_samples\":${work.pendingSamples},\"cancelled_samples_total\":${work.cancelledSamples},")
        append("\"failures_total\":${work.failures},\"peak_gpu_ns\":${work.peakGpuNanos},")
        append("\"last_gpu_frame\":${work.lastGpuFrame},\"last_gpu_world\":${work.lastGpuWorld},")
        append("\"last_cpu_ns\":${work.lastCpuNanos},\"cost_factor_1024\":${work.factor}}")
    }

    fun captureRecoveryJson(): String {
        val recovery = D3D12RtGeometryRecovery.stats()
        return buildString {
            append("{\"missing_layers\":").append(recovery.backlog.layers).append(',')
            append("\"oldest_age_ms\":").append(recovery.backlog.oldestAgeNanos / 1_000_000.0).append(',')
            append("\"prepared_regions\":").append(recovery.preparedRegions).append(',')
            append("\"in_flight\":").append(recovery.inFlight).append(',')
            append("\"retry_entries\":").append(recovery.retryEntries).append(',')
            append("\"scanned_total\":").append(recovery.scanned).append(',')
            append("\"submitted_total\":").append(recovery.submitted).append(',')
            append("\"deferred_total\":").append(recovery.deferred).append(',')
            append("\"discarded_total\":").append(recovery.discarded).append(',')
            append("\"copies_total\":").append(recovery.copies).append(',')
            append("\"copy_ms_total\":").append(recovery.copyNanos / 1_000_000.0).append(',')
            append("\"copy_ms_max\":").append(recovery.maxCopyNanos / 1_000_000.0).append(',')
            append("\"over_slice_copies_total\":").append(recovery.overSliceCopies).append('}')
        }
    }

    private fun queueWindow(encoder: D3D12CommandEncoderBackend): Boolean {
        val count = (0..deepestSlot).takeWhile { writtenSlots and (1 shl it) != 0 }.size
        if (count < 2) return false
        encoder.ensureListOpen()
        if (!MCDR_ABI.D3D12QueueQueryValues(encoder.nativeHandle(), pool, SLOT_START, count)) {
            fail("query snapshot unavailable")
            return false
        }
        val scene = D3D12RtBlas.sceneStats()
        val work = scene?.let { state -> D3D12RtBlas.workBudget()?.takeIf {
            it.worldEpoch == state.worldEpoch && it.frameId == state.frameId
        } }
        val metrics = buildString {
            append("{\"time\":\"").append(Instant.now()).append("\",")
            // 档位写进统计：光追"有没有效果"的排查看这一格，不用翻日志。
            append("\"tier\":\"").append(tier).append("\",")
            append("\"frames\":").append(windowFrames).append(',')
            append("\"width\":").append(frameWidth).append(',')
            append("\"height\":").append(frameHeight).append(',')
            append("\"trace_width\":").append(traceWidth).append(',')
            append("\"trace_height\":").append(traceHeight).append(',')
            append("\"records\":").append(recordCount).append(',')
            append("\"triangles\":").append(D3D12RtGeometryPool.triangleCount()).append(',')
            append("\"cpu_ms\":").append(perFrame(cpuNanosTotal / 1.0e6)).append(',')
            append("\"dispatches\":").append(perFrame(dispatchesTotal.toDouble())).append(',')
            append("\"vram_mb\":").append(round(vramPeakBytes / 1048576.0 * 10.0) / 10.0).append(',')
            append("\"blas_builds\":").append(blasBuilds).append(',')
            append("\"blas_refits\":").append(blasRefits).append(',')
            append("\"blas_compacts\":").append(blasCompacts).append(',')
            append("\"blas_ms\":").append(blasMs()).append(',')
            append("\"blas_build_us\":").append(blasBuildPeakMicros).append(',')
            append("\"blas_vram_mb\":").append(round(blasVramPeak / 1048576.0 * 10.0) / 10.0).append(',')
            append("\"blas_scratch_mb\":").append(round(blasScratchPeak / 1048576.0 * 10.0) / 10.0).append(',')
            append("\"tlas_instances\":").append(tlasInstances).append(',')
            append("\"tlas_ms\":").append(tlasMs()).append(',')
            append("\"scene\":").append(sceneJson(scene)).append(',')
            append("\"scene_work\":").append(workBudgetJson(work)).append(',')
            append("\"capture_recovery\":").append(captureRecoveryJson()).append(',')
        }
        pendingWindow = PendingWindow(metrics, count, sampleFrame)
        return true
    }

    private fun pollWindow() {
        val pending = pendingWindow ?: return
        when (MCDR_ABI.D3D12TryReadQueryValues(pool, SLOT_START, pending.count, timestamps)) {
            0 -> return
            1 -> pendingWindow = null
            else -> {
                fail("query snapshot read failed")
                MCDR_ABI.D3D12DestroyQueryPool(pool)
                pool = 0L
                pendingWindow = null
                writtenSlots = 0
                ensurePool()
                return
            }
        }
        val total = ms(SLOT_START, pending.count - 1)
        if (total <= 0.0) return
        val line = buildString {
            append(pending.metrics)
            append("\"gpu_sample_frame\":").append(pending.frame).append(',')
            append("\"gpu_sample_slots\":").append(pending.count).append(',')
            val names = arrayOf("gbuffer_ms", "trace_ms", "denoise_ms", "sr_ms", "composite_ms", "blit_ms")
            for ([index, name] in names.withIndex()) {
                append('"').append(name).append("\":")
                if (index + 1 < pending.count) append(ms(index, index + 1)) else append("null")
                append(',')
            }
            append("\"total_ms\":").append(total).append('}')
        }
        if (appendLine(line)) IllusionLogger.debugMessage("rt stats: GPU sample ${pending.frame} (${total}ms)")
    }

    /** 窗口内的每帧均值；没有帧就别除出 NaN。 */
    private fun perFrame(total: Double): Double {
        if (windowFrames <= 0L) return 0.0
        return round(total / windowFrames * 100.0) / 100.0
    }

    /** TLAS 每帧录制的 CPU 耗时（毫秒，两位小数）。 */
    private fun tlasMs(): Double = perFrame(tlasMicros / 1000.0)

    /** BLAS 侧每帧录制的 CPU 耗时（毫秒，两位小数）。 */
    private fun blasMs(): Double = perFrame(blasEncodeMicros / 1000.0)

    private fun ms(from: Int, to: Int): Double = elapsedMillis(timestamps[from], timestamps[to])

    private fun elapsedMillis(from: Long, to: Long): Double {
        if (frequency <= 0L || from <= 0L || to <= from) return 0.0
        // 两位小数：GPU 计时精度远高于毫秒，写多位小数只是噪声。
        return round((to - from) * 1000.0 / frequency * 100.0) / 100.0
    }

    private fun appendLine(line: String): Boolean {
        return try {
            val path = Path.of(System.getProperty("user.dir"), "dxrmc", FILE_NAME)
            Files.createDirectories(path.parent)
            Files.writeString(
                path, line + System.lineSeparator(), Charsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND,
            )
            true
        } catch (t: Throwable) {
            fail("stats write failed: ${t.message}")
            false
        }
    }

    /** 光追关掉时由 tick 调：池还回去，下次开光追重新建。 */
    fun release() {
        if (pool != 0L) {
            MCDR_ABI.D3D12DestroyQueryPool(pool)
            pool = 0L
        }
        frequency = 0L
        windowStartNanos = 0L
        windowFrames = 0L
        deepestSlot = SLOT_START
        writtenSlots = 0
        sampleFrame = 0L
        pendingWindow = null
        traceWidth = 0
        traceHeight = 0
        recordCount = 0
        cpuNanosTotal = 0L
        dispatchesTotal = 0L
        vramPeakBytes = 0L
        blasBuilds = 0L
        blasRefits = 0L
        blasCompacts = 0L
        blasVramPeak = 0L
        blasScratchPeak = 0L
        tlasInstances = 0
        tlasMicros = 0L
        blasEncodeMicros = 0L
        blasBuildPeakMicros = 0L
    }

    private fun fail(reason: String) {
        warnings++
        if (warnings <= 4) {
            IllusionLogger.warnMessage("rt stats: $reason")
        }
    }
}
