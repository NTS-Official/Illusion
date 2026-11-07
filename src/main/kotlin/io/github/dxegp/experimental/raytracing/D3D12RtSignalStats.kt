package io.github.dxegp.experimental.raytracing

import io.github.dxegp.d3d12.extern.MCDR_ABI
import io.github.dxegp.misc.IllusionLogger
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant

internal object D3D12RtSignalStats {
    private const val SRV_COUNT = 4
    private const val UAV_COUNT = 1
    private const val INTERVAL_NANOS = 250_000_000L
    private const val MAX_GROUP_BYTES = 64L shl 20
    private const val FLUSH_ROWS = 20
    private val counterNames = listOf(
        "pixels", "primary_hit", "primary_unknown", "primary_miss", "direct_unknown",
        "diffuse_geometry_unknown", "diffuse_shadow_unknown", "specular_geometry_unknown",
        "specular_shadow_unknown", "primary_rejected", "gbuffer_missing", "rt_owned",
    )
    private val resultBytes = counterNames.size * Int.SIZE_BYTES.toLong()
    private var countPipeline = 0L
    private var reducePipeline = 0L
    private var groupBuffer = 0L
    private var groupBytes = 0L
    private var resultBuffer = 0L
    private var readback = 0L
    private var query = 0L
    private var lastSampleNanos = 0L
    private var warnings = 0
    private val timestamps = LongArray(1)
    private val rows = ArrayList<String>(FLUSH_ROWS)
    private var pending: Pending? = null

    data class Frame(
        val frameId: Long,
        val snapshotId: Long,
        val requestedSnapshotId: Long,
        val worldEpoch: Int,
        val sceneRevision: Long,
        val liveRevision: Long,
        val historyFrame: Long,
        val resetReasons: Int,
        val denoiser: Int,
        val encoding: Int,
        val upscaler: Int,
        val temporalResolve: Boolean,
        val cameraX: Float,
        val cameraY: Float,
        val cameraZ: Float,
        val scene: MCDR_ABI.RtSceneOutput?,
        val sceneFrameId: Long,
    )

    private data class Pending(val frame: Frame, val width: Int, val height: Int, val time: Instant,
                               val sceneStats: MCDR_ABI.RtSceneStats?, val work: MCDR_ABI.RtSceneWorkBudget?)

    fun record(encoder: Long, frame: Frame, width: Int, height: Int,
               direct: Long, indirect: Long, albedo: Long) {
        try {
            recordSample(encoder, frame, width, height, direct, indirect, albedo)
        } catch (_: Exception) {
            fail("sample failed")
            releaseBuffers()
        }
    }

    private fun recordSample(encoder: Long, frame: Frame, width: Int, height: Int,
                             direct: Long, indirect: Long, albedo: Long) {
        poll()
        val now = System.nanoTime()
        if (pending != null || lastSampleNanos != 0L && now - lastSampleNanos < INTERVAL_NANOS) return
        lastSampleNanos = now
        if (width <= 0 || height <= 0 || direct == 0L || indirect == 0L || albedo == 0L) return
        val groupWidth = (width + 7) / 8
        val groupHeight = (height + 7) / 8
        val needed = groupWidth.toLong() * groupHeight * resultBytes
        if (needed > MAX_GROUP_BYTES || !prepare(needed)) return
        val constants = intArrayOf(width, height, groupWidth, groupWidth * groupHeight)
        val counted = MCDR_ABI.D3D12DispatchComputeEx(encoder, countPipeline,
            longArrayOf(direct, indirect, albedo, direct), longArrayOf(groupBuffer),
            constants, 0L, 0L, groupWidth, groupHeight, 1)
        val reduced = counted && MCDR_ABI.D3D12DispatchComputeEx(encoder, reducePipeline,
            longArrayOf(direct, indirect, albedo, groupBuffer), longArrayOf(resultBuffer),
            constants, 0L, 0L, counterNames.size, 1, 1)
        if (reduced) {
            MCDR_ABI.D3D12CopyBuffer(encoder, resultBuffer, 0L, readback, 0L, resultBytes)
            MCDR_ABI.D3D12WriteTimestamp(encoder, query, 0)
        }
        if (!reduced || !MCDR_ABI.D3D12QueueQueryValues(encoder, query, 0, 1)) {
            fail("sample rejected")
            releaseBuffers()
            return
        }
        val scene = D3D12RtBlas.sceneStats()?.takeIf {
            it.frameId == frame.sceneFrameId && it.worldEpoch == frame.worldEpoch.toLong() &&
                it.sceneRevision == frame.sceneRevision && it.liveRevision == frame.liveRevision &&
                it.targetSnapshotId == frame.requestedSnapshotId
        }
        val work = scene?.let { state -> D3D12RtBlas.workBudget()?.takeIf {
            it.worldEpoch == state.worldEpoch && it.frameId == state.frameId
        } }
        pending = Pending(frame, width, height, Instant.now(), scene, work)
    }

    fun poll() {
        try {
            pollSample()
        } catch (_: Exception) {
            fail("readback failed")
            releaseBuffers()
        }
    }

    private fun pollSample() {
        val sample = pending ?: return
        when (MCDR_ABI.D3D12TryReadQueryValues(query, 0, 1, timestamps)) {
            0 -> return
            1 -> pending = null
            else -> {
                fail("sample cancelled")
                releaseBuffers()
                return
            }
        }
        val mapped = MCDR_ABI.D3D12MapBuffer(readback, 0L, resultBytes, true, false)
        if (mapped == null) {
            fail("readback unavailable")
            releaseBuffers()
            return
        }
        val values = try {
            LongArray(counterNames.size) { mapped.getInt(it * Int.SIZE_BYTES).toUInt().toLong() }
        } finally {
            MCDR_ABI.D3D12UnmapBuffer(readback)
        }
        val pixels = sample.width.toLong() * sample.height
        if (values[0] != pixels || values.any { it > pixels } ||
            values[1] + values[2] + values[3] != pixels || values[9] + values[10] + values[11] != values[1]) {
            fail("invalid counters")
            return
        }
        val frame = sample.frame
        rows += buildString {
            append("{\"time\":\"${sample.time}\",\"frame\":${frame.frameId},")
            append("\"scene_frame\":${frame.sceneFrameId},")
            append("\"snapshot\":${frame.snapshotId},\"requested_snapshot\":${frame.requestedSnapshotId},")
            append("\"world_epoch\":${frame.worldEpoch},\"scene_revision\":${frame.sceneRevision},")
            append("\"live_revision\":${frame.liveRevision},\"history_frame\":${frame.historyFrame},")
            append("\"reset_reasons\":${frame.resetReasons},\"denoiser\":${frame.denoiser},")
            append("\"encoding\":${frame.encoding},\"upscaler\":${frame.upscaler},")
            append("\"temporal_resolve\":${frame.temporalResolve},")
            append("\"camera\":[${frame.cameraX},${frame.cameraY},${frame.cameraZ}],")
            append("\"width\":${sample.width},\"height\":${sample.height},")
            append("\"scene\":").append(D3D12RtStats.sceneJson(sample.sceneStats)).append(',')
            append("\"scene_work\":").append(D3D12RtStats.workBudgetJson(sample.work)).append(',')
            frame.scene?.let { scene ->
                append("\"pending_snapshot\":${scene.pendingSnapshotId},")
                append("\"instances\":${scene.active.instanceCount},\"blas\":${scene.active.blasCount},")
                append("\"candidate_built\":${scene.candidate.blasCount},")
                append("\"candidate_ready\":${scene.candidate.ready},")
                append("\"candidate_builds\":${scene.candidate.buildCount},")
            }
            for ([index, name] in counterNames.withIndex()) {
                if (index != 0) append(',')
                append("\"$name\":${values[index]}")
            }
            append('}')
        }
        if (rows.size >= FLUSH_ROWS) flush()
    }

    private fun prepare(needed: Long): Boolean {
        if (countPipeline == 0L || reducePipeline == 0L) {
            val source = D3D12RtShaderSources.load(D3D12ShaderPacks.sourcePath("rt_signal_stats.hlsl"))
            val countDxil = MCDR_ABI.D3D12CompileShaderDxc(source, "RtSignalStats", "cs_6_9")
            val reduceDxil = MCDR_ABI.D3D12CompileShaderDxc(source, "RtSignalStatsReduce", "cs_6_9")
            if (countDxil != null && reduceDxil != null) {
                countPipeline = MCDR_ABI.D3D12CreateComputePipelineEx(countDxil, SRV_COUNT, UAV_COUNT)
                reducePipeline = MCDR_ABI.D3D12CreateComputePipelineEx(reduceDxil, SRV_COUNT, UAV_COUNT)
            }
            if (countPipeline == 0L || reducePipeline == 0L) {
                fail("pipeline unavailable")
                release()
                return false
            }
        }
        if (needed > groupBytes) {
            releaseBuffers()
            groupBuffer = MCDR_ABI.ID3D12CreateBuffer(1024, needed)
            groupBytes = needed
        }
        if (resultBuffer == 0L) resultBuffer = MCDR_ABI.ID3D12CreateBuffer(1024, resultBytes)
        if (readback == 0L) readback = MCDR_ABI.ID3D12CreateBuffer(1, resultBytes)
        if (query == 0L) query = MCDR_ABI.D3D12CreateQueryPool(1)
        if (groupBuffer == 0L || resultBuffer == 0L || readback == 0L || query == 0L) {
            fail("resources unavailable")
            releaseBuffers()
            return false
        }
        return true
    }

    private fun releaseBuffers() {
        pending = null
        if (query != 0L) MCDR_ABI.D3D12DestroyQueryPool(query)
        query = 0L
        for (buffer in longArrayOf(groupBuffer, resultBuffer, readback)) {
            if (buffer != 0L) MCDR_ABI.ID3D12DestroyResource(buffer)
        }
        groupBuffer = 0L
        resultBuffer = 0L
        readback = 0L
        groupBytes = 0L
    }

    private fun flush() {
        if (rows.isEmpty()) return
        try {
            val path = Path.of(System.getProperty("user.dir"), "dxrmc", "rt_validity.jsonl")
            Files.createDirectories(path.parent)
            Files.writeString(path, rows.joinToString("\n", postfix = "\n"), Charsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        } catch (_: Exception) {
            fail("write failed")
        } finally {
            rows.clear()
        }
    }

    fun release() {
        if (countPipeline == 0L && reducePipeline == 0L && groupBuffer == 0L && pending == null && rows.isEmpty()) return
        poll()
        releaseBuffers()
        for (pipeline in longArrayOf(countPipeline, reducePipeline)) {
            if (pipeline != 0L) MCDR_ABI.ID3D12DestroyResource(pipeline)
        }
        countPipeline = 0L
        reducePipeline = 0L
        lastSampleNanos = 0L
        flush()
    }

    private fun fail(reason: String) {
        warnings++
        if (warnings <= 4 || warnings and (warnings - 1) == 0) {
            IllusionLogger.warnMessage("rt validity: $reason")
        }
    }
}
