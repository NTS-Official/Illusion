package io.github.dxegp.experimental.raytracing

import io.github.dxegp.d3d12.render.advanced.mesh.D3D12SectionGeometry
import io.github.dxegp.misc.IllusionLogger
import io.github.dxegp.misc.settings.D3D12Settings
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.renderer.LevelRenderer
import net.minecraft.client.renderer.ViewArea
import net.minecraft.client.renderer.chunk.RenderRegionCache
import net.minecraft.client.renderer.chunk.RenderSectionRegion
import net.minecraft.client.renderer.chunk.SectionMesh
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher
import net.minecraft.core.SectionPos
import net.neoforged.neoforge.client.ClientHooks
import net.neoforged.neoforge.client.event.AddSectionGeometryEvent
import net.neoforged.neoforge.client.event.ExtractLevelRenderStateEvent
import net.neoforged.neoforge.common.NeoForge
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

object D3D12RtGeometryRecovery {
    private const val CANDIDATES_PER_EXTRACTION = 64
    private const val REGIONS_PER_EXTRACTION = 2
    private const val IN_FLIGHT_LIMIT = 4
    private const val COMPILE_QUEUE_LIMIT = 4
    private const val PREPARATION_SLICE_NANOS = 1_000_000L

    private data class Prepared(
        val target: D3D12RtGeometryPool.CaptureTarget,
        val level: ClientLevel,
        val renderer: LevelRenderer,
        val viewArea: ViewArea,
        val section: SectionRenderDispatcher.RenderSection,
        val mesh: SectionMesh,
        val compileTask: Any?,
        val region: RenderSectionRegion,
        val additional: List<AddSectionGeometryEvent.AdditionalSectionRenderer>,
    )

    private data class Flight(val prepared: Prepared)

    data class Stats(val backlog: D3D12RtGeometryPool.CaptureBacklog, val preparedRegions: Int,
                     val inFlight: Int, val retryEntries: Int, val scanned: Long, val submitted: Long,
                     val deferred: Long, val discarded: Long, val copies: Long, val copyNanos: Long,
                     val maxCopyNanos: Long, val overSliceCopies: Long)

    private val pending = AtomicReference<List<Prepared>>(emptyList())
    private val flights = ConcurrentHashMap<Any, Flight>()
    private val backoff = D3D12RtCaptureBackoff()
    private val scanned = AtomicLong()
    private val submitted = AtomicLong()
    private val deferred = AtomicLong()
    private val discarded = AtomicLong()
    private val copies = AtomicLong()
    private val copyNanos = AtomicLong()
    private val maxCopyNanos = AtomicLong()
    private val overSliceCopies = AtomicLong()
    private val failures = AtomicLong()

    fun register() {
        NeoForge.EVENT_BUS.addListener<ExtractLevelRenderStateEvent>(::prepare)
    }

    fun clear() {
        pending.set(emptyList())
        flights.clear()
        backoff.clear()
    }

    fun stats(): Stats = Stats(D3D12RtGeometryPool.captureBacklog(), pending.get().size, flights.size,
        backoff.size(), scanned.get(), submitted.get(), deferred.get(), discarded.get(), copies.get(),
        copyNanos.get(), maxCopyNanos.get(), overSliceCopies.get())

    private fun prune(level: ClientLevel, epoch: Int) {
        flights.entries.removeAll { [task, flight] ->
            val prepared = flight.prepared
            val state = prepared.section as D3D12SectionCompileState
            val remove = prepared.level !== level || prepared.target.epoch != epoch ||
                state.compileTaskIdentity !== task || !state.hasPendingCompile()
            if (remove && prepared.level === level && prepared.target.epoch == epoch &&
                state.compileTaskIdentity === task && !state.hasPendingCompile()) {
                for (target in D3D12RtGeometryPool.missingCaptures(prepared.target.sectionNode)) postpone(target, true)
            }
            remove
        }
        backoff.retainCurrent(epoch)
    }

    private fun hasCapacity(renderer: LevelRenderer): Boolean {
        val dispatcher = renderer.sectionRenderDispatcher() ?: return false
        return flights.size < IN_FLIGHT_LIMIT && dispatcher.compileQueueSize < COMPILE_QUEUE_LIMIT &&
            dispatcher.freeBufferCount > 0
    }

    private fun postpone(target: D3D12RtGeometryPool.CaptureTarget, failed: Boolean = false) {
        deferred.incrementAndGet()
        backoff.defer(target, System.nanoTime(), failed)
    }

    private fun reportFailure(failure: Exception) {
        val count = failures.incrementAndGet()
        if (count <= 4 || count and (count - 1) == 0L) {
            IllusionLogger.warnMessage("RT capture: recovery deferred (${failure.javaClass.simpleName})")
        }
    }

    private fun prepare(event: ExtractLevelRenderStateEvent) {
        discarded.addAndGet(pending.getAndSet(emptyList()).size.toLong())
        if (!D3D12Settings.rtEnabled) {
            flights.clear()
            backoff.clear()
            return
        }
        val client = Minecraft.getInstance()
        val level = event.level
        if (client.level !== level) return
        val epoch = D3D12SectionGeometry.epoch()
        prune(level, epoch)
        val renderer = client.levelRenderer
        val viewArea = renderer.viewArea() ?: return
        pending.set(prepareBatch(level, renderer, viewArea, RenderRegionCache()))
    }

    private fun prepareBatch(level: ClientLevel, renderer: LevelRenderer, viewArea: ViewArea,
                             cache: RenderRegionCache): List<Prepared> {
        if (!hasCapacity(renderer)) return emptyList()
        val batch = mutableListOf<Prepared>()
        val sections = mutableSetOf<Long>()
        val started = System.nanoTime()
        val limit = D3D12RtGeometryPool.captureBacklog(started).layers.coerceAtMost(CANDIDATES_PER_EXTRACTION)
        repeat(limit) {
            if (batch.size >= REGIONS_PER_EXTRACTION || batch.size + flights.size >= IN_FLIGHT_LIMIT ||
                System.nanoTime() - started >= PREPARATION_SLICE_NANOS) return@repeat
            val target = D3D12RtGeometryPool.nextMissingCapture() ?: return@repeat
            scanned.incrementAndGet()
            if (!sections.add(target.sectionNode) || !backoff.isReady(target, System.nanoTime())) return@repeat
            val position = SectionPos.of(target.sectionNode)
            val section = viewArea.getRenderSectionAt(position.origin())
            if (section == null || section.sectionNode != target.sectionNode) {
                postpone(target)
                return@repeat
            }
            val state = section as D3D12SectionCompileState
            val task = state.compileTaskIdentity
            val mesh = section.sectionMesh.get()
            if (state.hasPendingCompile() ||
                !(-1..1).all { deltaX -> (-1..1).all { deltaZ -> level.hasChunk(position.x() + deltaX, position.z() + deltaZ) } }) {
                postpone(target)
                return@repeat
            }
            try {
                val copyStarted = System.nanoTime()
                val region: RenderSectionRegion
                val additional: List<AddSectionGeometryEvent.AdditionalSectionRenderer>
                try {
                    region = cache.createRegion(level, target.sectionNode)
                    additional = ClientHooks.gatherAdditionalRenderers(position.origin(), level)
                } finally {
                    val elapsed = System.nanoTime() - copyStarted
                    copies.incrementAndGet()
                    copyNanos.addAndGet(elapsed)
                    maxCopyNanos.accumulateAndGet(elapsed, ::maxOf)
                    if (elapsed > PREPARATION_SLICE_NANOS) overSliceCopies.incrementAndGet()
                }
                batch += Prepared(target, level, renderer, viewArea, section, mesh, task, region, additional)
            } catch (failure: Exception) {
                postpone(target, true)
                reportFailure(failure)
            }
        }
        return batch.toList()
    }

    @JvmStatic
    fun submit(renderer: LevelRenderer) {
        val batch = pending.getAndSet(emptyList())
        if (batch.isEmpty()) return
        val client = Minecraft.getInstance()
        val level = client.level
        if (!D3D12Settings.rtEnabled || level == null) {
            discarded.addAndGet(batch.size.toLong())
            return
        }
        prune(level, D3D12SectionGeometry.epoch())
        for (prepared in batch) {
            val section = prepared.section
            val state = section as D3D12SectionCompileState
            if (prepared.level !== level || prepared.renderer !== renderer || client.levelRenderer !== renderer ||
                renderer.viewArea() !== prepared.viewArea || section.sectionNode != prepared.target.sectionNode ||
                prepared.viewArea.getRenderSectionAt(SectionPos.of(prepared.target.sectionNode).origin()) !== section ||
                section.sectionMesh.get() !== prepared.mesh || state.compileTaskIdentity !== prepared.compileTask ||
                !D3D12RtGeometryPool.isMissing(prepared.target)) {
                discarded.incrementAndGet()
                continue
            }
            if (state.hasPendingCompile() || !hasCapacity(renderer)) {
                postpone(prepared.target)
                continue
            }
            try {
                section.compileAsync(prepared.region, prepared.additional)
                val task = state.compileTaskIdentity
                if (task != null) flights[task] = Flight(prepared)
                submitted.incrementAndGet()
                backoff.defer(prepared.target, System.nanoTime())
            } catch (failure: Exception) {
                postpone(prepared.target, true)
                reportFailure(failure)
            }
        }
    }
}
