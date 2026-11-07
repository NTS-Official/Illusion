package io.github.dxegp.experimental.raytracing

import io.github.dxegp.d3d12.render.advanced.mesh.D3D12MeshletBuilder
import io.github.dxegp.d3d12.render.advanced.mesh.D3D12SectionGeometry
import io.github.dxegp.misc.IllusionLogger
import io.github.dxegp.misc.settings.D3D12Settings
import net.minecraft.core.SectionPos
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.lang.ref.WeakReference
import java.util.TreeMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

/**
 * S2 的导出几何缓存：把每段每层的顶点（28 B/顶点，`DefaultVertexFormat.BLOCK`）、索引与逐 quad。
 * 只在内容变化时写，重传同数据不会重复拷贝；超容量停止分配并撤销无法更新的记录。
 * 见 docs/rt/README.md
 */
object D3D12RtGeometryPool {
    /** 顶点 / 索引 / 材质池上限。12~16 区块半径全部层的顶点约 60~120 MB，留一倍余量。 */
    private const val VERTEX_CAP = 768L * 1024 * 1024
    private const val INDEX_CAP = 96L * 1024 * 1024
    private const val MATERIAL_CAP = 96L * 1024 * 1024
    private const val DORMANT_RECORD_LIMIT = 1024
    private const val DORMANT_BYTE_LIMIT = 128L * 1024 * 1024
    private const val RETAINED_SOURCE_SECTION_LIMIT = 1024
    private const val RETAINED_SOURCE_BYTE_LIMIT = 128L * 1024 * 1024

    private const val STRIDE = D3D12MeshletBuilder.VERTEX_STRIDE
    private const val QUAD_VERTICES = 4
    private const val QUAD_INDICES = 6
    private const val SECTION_CAPTURE_LAYER = -1

    /** 逐 quad 材质索引的字节数（u16，指向 [D3D12RtMaterialTable] 的记录）。 */
    private const val MATERIAL_BYTES = 2

    data class OutputBounds(val minimumX: Float, val minimumY: Float, val minimumZ: Float,
                            val maximumX: Float, val maximumY: Float, val maximumZ: Float)

    /** 一条"段-层"记录；偏移是相对池首的字节偏移。 */
    data class Record(
        val sectionNode: Long,
        val layer: Int,
        val originX: Int,
        val originY: Int,
        val originZ: Int,
        val geometryVersion: Int,
        val flags: Int,
        val vertexOffset: Long,
        val vertexCount: Int,
        val indexOffset: Long,
        val indexCount: Int,
        val indexStride: Int,
        val quadCount: Int,
        val materialOffset: Long,
        val contentVersion: Int = geometryVersion,
        val outputBounds: OutputBounds? = null,
    ) {
        val triangles: Int get() = indexCount / 3
    }

    private data class RecordKey(val sectionNode: Long, val layer: Int)

    private data class MissingCapture(val version: Int, val sinceNanos: Long)

    data class CaptureTarget(val epoch: Int, val sectionNode: Long, val layer: Int,
                             val version: Int, val sinceNanos: Long)

    data class CaptureBacklog(val layers: Int, val oldestAgeNanos: Long)

    class Content internal constructor(private val storage: ByteBuffer) {
        val identity: Long = nextContent.incrementAndGet()
        val byteCount: Int get() = storage.capacity()
        val bytes: ByteBuffer get() = storage.asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN)
        internal fun writable(): ByteBuffer = storage.duplicate().order(ByteOrder.LITTLE_ENDIAN)
    }

    data class ContentRecord(val record: Record, val vertices: Content, val indices: Content, val quads: Content)

    private val nextContent = AtomicLong()
    private val retainedContent = mutableListOf<WeakReference<Content>>()

    private fun content(buffer: ByteBuffer): Content = Content(buffer).also {
        retainedContent += WeakReference(it)
    }

    private class GeometryArena(private val capacityLimit: Int) {
        private val allocations = TreeMap<Int, Content>()
        private val freeRanges = TreeMap<Int, Int>()
        var allocatedBytes = 0L
            private set
        var highWater = 0
            private set
        val allocationCount: Int get() = allocations.size

        fun canAllocate(byteCount: Int): Boolean = byteCount >= 0 &&
            (byteCount <= capacityLimit - highWater || freeRanges.values.any { it >= byteCount })

        fun allocate(byteCount: Int): Int? {
            require(byteCount >= 0)
            if (byteCount == 0) return 0
            val free = freeRanges.entries.firstOrNull { it.value >= byteCount }
            val offset = free?.key ?: highWater
            if (byteCount > capacityLimit - offset) return null
            val buffer = ByteBuffer.allocate(byteCount).order(ByteOrder.LITTLE_ENDIAN)
            allocations[offset] = content(buffer)
            if (free != null) {
                val remaining = free.value - byteCount
                freeRanges.remove(offset)
                if (remaining > 0) freeRanges[offset + byteCount] = remaining
            } else {
                highWater += byteCount
            }
            allocatedBytes += byteCount
            return offset
        }

        fun release(offset: Int, byteCount: Int) {
            if (byteCount == 0) return
            val allocation = checkNotNull(allocations.remove(offset))
            check(allocation.byteCount == byteCount)
            allocatedBytes -= byteCount
            var start = offset
            var length = byteCount
            freeRanges.lowerEntry(start)?.let { previous ->
                if (previous.key + previous.value == start) {
                    start = previous.key
                    length += previous.value
                    freeRanges.remove(previous.key)
                }
            }
            freeRanges.ceilingEntry(start)?.let { next ->
                if (start + length == next.key) {
                    length += next.value
                    freeRanges.remove(next.key)
                }
            }
            if (start + length == highWater) highWater = start else freeRanges[start] = length
        }

        fun view(offset: Long, byteCount: Int): ByteBuffer {
            require(offset in 0..Int.MAX_VALUE.toLong() && byteCount >= 0)
            if (byteCount == 0) return ByteBuffer.allocate(0).order(ByteOrder.LITTLE_ENDIAN)
            val allocation = checkNotNull(allocations[offset.toInt()])
            require(allocation.byteCount == byteCount)
            return allocation.writable()
        }

        fun export(offset: Long, byteCount: Int): Content = checkNotNull(allocations[offset.toInt()]).also {
            require(it.byteCount == byteCount)
        }

        fun replace(offset: Long, buffer: ByteBuffer) {
            require(checkNotNull(allocations[offset.toInt()]).byteCount == buffer.remaining())
            allocations[offset.toInt()] = content(buffer)
        }

        fun snapshot(): ByteBuffer {
            val output = ByteBuffer.allocate(highWater).order(ByteOrder.LITTLE_ENDIAN)
            for ([offset, allocation] in allocations) {
                output.position(offset)
                output.put(allocation.bytes)
            }
            output.position(0)
            return output.asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN)
        }
    }

    private data class Allocation(
        val vertexOffset: Int,
        val vertexBytes: Int,
        val indexOffset: Int,
        val indexBytes: Int,
        val materialOffset: Int,
        val materialBytes: Int,
    )

    private val records = ConcurrentHashMap<RecordKey, Record>()
    private val missingVersions = linkedMapOf<RecordKey, MissingCapture>()
    private val missingSections = linkedMapOf<Long, MissingCapture>()
    private data class ProducerManifest(val support: D3D12RtModelSupport.Manifest, val versions: Map<Int, Int>,
                                        val requiredLayers: Set<Int>)
    private val producerManifests = mutableMapOf<Long, ProducerManifest>()
    private data class SectionPublication(val node: Long, val epoch: Int, val layers: Set<Int>)
    private var sectionPublication: SectionPublication? = null
    private data class SourceRequirement(val support: D3D12RtModelSupport.SourceManifest, val version: Int)
    private val sourceRequirements = mutableMapOf<Long, SourceRequirement>()
    private var nextSectionCaptureVersion = 0
    private var captureAgeDirty = true
    private var oldestCaptureNanos: Long? = null
    private val captureOrder = linkedSetOf<RecordKey>()
    private val dormantRecords = linkedMapOf<RecordKey, Record>()
    private var dormantBytes = 0L
    private val retainedSources = linkedMapOf<Long, Long>()
    private var retainedSourceBytes = 0L
    private var mirroredRecords: List<Record> = emptyList()
    private var mirroredSlice: MirrorSlice? = null
    private var geometryEpoch = 0
    private var cachedVersions: Versions? = null
    private var cachedSpatialSources: D3D12RtSpatialSources.Snapshot? = null
    private var sourceUniverse = D3D12RtSourceUniverse.unknown
    private var currentSourceUniverse = D3D12RtSourceUniverse.unavailable
    private var cachedCoverage: Coverage? = null
    private data class SpatialGrid(val sources: D3D12RtSpatialSources.Snapshot,
                                   val region: D3D12RtSpatialDirectory.Region,
                                   val targets: Set<Long>, val bytes: ByteBuffer)
    private var cachedSpatialGrid: SpatialGrid? = null
    private val invalidSections = mutableSetOf<Long>()
    private enum class SpatialState(val code: Int) {
        UNLOADED(3), DIRTY(4), LOADED_UNPROVEN(5),
    }
    private val spatialStates = mutableMapOf<Long, SpatialState>()
    private var vertices = GeometryArena(VERTEX_CAP.toInt())
    private var indices = GeometryArena(INDEX_CAP.toInt())
    private var materials = GeometryArena(MATERIAL_CAP.toInt())

    private val revision = AtomicLong()
    private val published = AtomicLong()
    private val skipped = AtomicLong()
    private val resets = AtomicLong()
    private val copiedBytes = AtomicLong()
    private val hidden = AtomicLong()

    private var full = false

    /**
     * 发布一段一层的几何。`vertex` / `index` 是上传时看到的字节：。
     * 见 docs/rt/README.md
     */
    @Synchronized
    @JvmStatic
    fun publish(sectionNode: Long, layer: String, geometryVersion: Int,
                vertex: ByteBuffer, index: ByteBuffer?, indexCount: Int): Boolean {
        val kind = D3D12SectionGeometry.Layer.of(layer)
        // 半透明不参与三角形追踪（S4 的 OIT 才处理它），复制进来只是白占池子。
        if (kind == D3D12SectionGeometry.Layer.TRANSLUCENT) {
            hidden.incrementAndGet()
            return false
        }
        val key = RecordKey(sectionNode, kind.ordinal)
        if (!D3D12Settings.rtEnabled) {
            if (indexCount > 0 || vertex.hasRemaining()) markMissing(key, geometryVersion)
            return false
        }
        try {
            val published = publishRecord(sectionNode, kind, key, geometryVersion, vertex, index, indexCount)
            if (records.containsKey(key)) {
                if (removeMissing(key)) revision.incrementAndGet()
            } else if (vertex.hasRemaining()) {
                markMissing(key, geometryVersion)
            }
            return published
        } catch (failure: Throwable) {
            markMissing(key, geometryVersion)
            throw failure
        }
    }

    private fun publishRecord(sectionNode: Long, kind: D3D12SectionGeometry.Layer, key: RecordKey,
                              geometryVersion: Int, vertex: ByteBuffer, index: ByteBuffer?, indexCount: Int): Boolean {
        val previous = records[key]
        if (previous != null && previous.contentVersion == geometryVersion) {
            skipped.incrementAndGet()
            return false
        }
        val vertexBytes = vertex.remaining()
        val vertexCount = vertexBytes / STRIDE
        if (vertexCount <= 0) {
            return false
        }
        val quadCount = vertexCount / QUAD_VERTICES
        val effectiveIndexCount = if (indexCount > 0) indexCount else quadCount * QUAD_INDICES
        val indexStride = if (index != null && indexCount > 0) {
            if (index.remaining() / indexCount >= 4) 4 else 2
        } else {
            // 原版共享顺序缓冲的元素大小按顶点数选；我们合成同样规则的索引。
            if (vertexCount > 0xFFFF) 4 else 2
        }
        val indexBytes = effectiveIndexCount.toLong() * indexStride
        val materialBytes = quadCount.toLong() * MATERIAL_BYTES
        if (previous != null && matchesGeometry(previous, vertex, index, effectiveIndexCount, indexStride)) {
            skipped.incrementAndGet()
            return false
        }
        val dormant = dormantRecords[key]
        if (previous == null && dormant != null &&
            matchesGeometry(dormant, vertex, index, effectiveIndexCount, indexStride)) {
            val materialIds = D3D12RtMaterialTable.quadMaterials(vertex, vertex.position(), dormant.quadCount)
            remapMaterials(dormant, materialIds)
            records[key] = dormant
            removeDormant(key)
            mirroredSlice = null
            full = false
            published.incrementAndGet()
            revision.incrementAndGet()
            return true
        }
        val geometryIdentity = (previous ?: dormant)?.takeIf {
            matchesGeometry(it, vertex, index, effectiveIndexCount, indexStride, false)
        }?.geometryVersion ?: geometryVersion
        removeDormant(key)?.let(::releaseRecord)
        if (previous != null) {
            records.remove(key)
            releaseRecord(previous)
            mirroredSlice = null
            revision.incrementAndGet()
        }
        val materialIds = D3D12RtMaterialTable.quadMaterials(vertex, vertex.position(), quadCount)
        val allocation = allocateStorage(vertexBytes, indexBytes, materialBytes) ?: run {
            if (!full) {
                full = true
                IllusionLogger.warnMessage("RT pool: storage budget exhausted")
            }
            skipped.incrementAndGet()
            return false
        }
        var committed = false
        var stagedRecord: Record? = null
        try {
            val position = SectionPos.of(sectionNode)
            val record = Record(
                sectionNode = sectionNode,
                layer = kind.ordinal,
                originX = position.x() * 16,
                originY = position.y() * 16,
                originZ = position.z() * 16,
                geometryVersion = geometryIdentity,
                flags = kind.flag,
                vertexOffset = allocation.vertexOffset.toLong(),
                vertexCount = vertexCount,
                indexOffset = allocation.indexOffset.toLong(),
                indexCount = effectiveIndexCount,
                indexStride = indexStride,
                quadCount = quadCount,
                materialOffset = allocation.materialOffset.toLong(),
                outputBounds = outputBounds(vertex),
                contentVersion = geometryVersion,
            )
            stagedRecord = record
            vertices.view(record.vertexOffset, vertexBytes).put(vertex.duplicate())
            val indexData = indices.view(record.indexOffset, allocation.indexBytes)
            if (index != null && indexCount > 0) {
                indexData.put(index.duplicate())
            } else {
                writeSequentialQuadIndices(indexData, quadCount, indexStride)
            }
            val materialData = materials.view(record.materialOffset, allocation.materialBytes)
            for (materialId in materialIds) materialData.putShort(materialId)
            records[key] = record
            committed = true
        } finally {
            if (!committed) {
                stagedRecord?.let { records.remove(key, it) }
                releaseStorage(allocation)
            }
        }
        full = false
        copiedBytes.addAndGet(vertexBytes + indexBytes + materialBytes)
        published.incrementAndGet()
        revision.incrementAndGet()
        return true
    }

    /** 每次成功写入、材质回填与清空都会 +1：上传镜像靠它判断要不要重传。 */
    @JvmStatic
    fun revision(): Long = revision.get()

    @JvmStatic
    @Synchronized
    fun markMissing(sectionNode: Long, layer: String, geometryVersion: Int) {
        val kind = D3D12SectionGeometry.Layer.of(layer)
        if (kind == D3D12SectionGeometry.Layer.TRANSLUCENT) return
        markMissing(RecordKey(sectionNode, kind.ordinal), geometryVersion)
    }

    private fun markMissing(key: RecordKey, geometryVersion: Int) {
        val previous = records.remove(key)
        previous?.let(::releaseRecord)
        val previousMissing = missingVersions[key]
        missingVersions[key] = MissingCapture(geometryVersion, previousMissing?.sinceNanos ?: System.nanoTime())
        if (previousMissing == null) captureAgeDirty = true
        captureOrder += key
        val changed = previousMissing?.version != geometryVersion
        if (previous != null) mirroredSlice = null
        if (changed || previous != null) {
            revision.incrementAndGet()
        }
    }

    private fun removeMissing(key: RecordKey): Boolean {
        captureOrder.remove(key)
        return (missingVersions.remove(key) != null).also { if (it) captureAgeDirty = true }
    }

    private fun missingCapture(key: RecordKey): MissingCapture? =
        if (key.layer == SECTION_CAPTURE_LAYER) missingSections[key.sectionNode] else missingVersions[key]

    private fun oldestCapture(sectionNode: Long): MissingCapture? {
        var oldest = missingSections[sectionNode]
        for (layer in D3D12SectionGeometry.Layer.entries) {
            val missing = missingVersions[RecordKey(sectionNode, layer.ordinal)] ?: continue
            if (oldest == null || missing.sinceNanos < oldest.sinceNanos) oldest = missing
        }
        return oldest
    }

    private fun removeSectionCapture(sectionNode: Long): Boolean {
        captureOrder.remove(RecordKey(sectionNode, SECTION_CAPTURE_LAYER))
        return (missingSections.remove(sectionNode) != null).also { if (it) captureAgeDirty = true }
    }

    @JvmStatic
    @Synchronized
    fun nextMissingCapture(): CaptureTarget? {
        val key = captureOrder.firstOrNull() ?: return null
        captureOrder.remove(key)
        captureOrder += key
        val missing = checkNotNull(missingCapture(key))
        return CaptureTarget(geometryEpoch, key.sectionNode, key.layer, missing.version, missing.sinceNanos)
    }

    @JvmStatic
    @Synchronized
    fun isMissing(target: CaptureTarget): Boolean = target.epoch == geometryEpoch &&
        missingCapture(RecordKey(target.sectionNode, target.layer))?.version == target.version

    @JvmStatic
    @Synchronized
    fun hasSectionCapture(sectionNode: Long): Boolean = missingSections.containsKey(sectionNode)

    @JvmStatic
    @Synchronized
    fun missingCaptures(sectionNode: Long): List<CaptureTarget> = buildList {
        missingSections[sectionNode]?.let { missing ->
            add(CaptureTarget(geometryEpoch, sectionNode, SECTION_CAPTURE_LAYER, missing.version, missing.sinceNanos))
        }
        for (layer in D3D12SectionGeometry.Layer.entries) {
            val missing = missingVersions[RecordKey(sectionNode, layer.ordinal)] ?: continue
            add(CaptureTarget(geometryEpoch, sectionNode, layer.ordinal, missing.version, missing.sinceNanos))
        }
    }

    @JvmStatic
    @Synchronized
    fun captureBacklog(nowNanos: Long = System.nanoTime()): CaptureBacklog {
        if (captureAgeDirty) {
            oldestCaptureNanos = listOfNotNull(missingVersions.values.minOfOrNull { it.sinceNanos },
                missingSections.values.minOfOrNull { it.sinceNanos }).minOrNull()
            captureAgeDirty = false
        }
        return CaptureBacklog(missingVersions.size + missingSections.size,
            oldestCaptureNanos?.let { (nowNanos - it).coerceAtLeast(0) } ?: 0)
    }

    @JvmStatic
    @Synchronized
    fun invalidateSection(sectionNode: Long) {
        invalidateSections(longArrayOf(sectionNode)) {}
    }

    @JvmStatic
    @Synchronized
    @JvmOverloads
    fun invalidateModelResources(required: LongArray, newUniverse: D3D12RtSourceUniverse = D3D12RtSourceUniverse.unknown,
                                 publish: () -> Unit) {
        val nodes = buildSet {
            addAll(required.asList())
            addAll(records.keys.map { it.sectionNode })
            addAll(missingVersions.keys.map { it.sectionNode })
            addAll(missingSections.keys)
            addAll(invalidSections)
        }.filterTo(linkedSetOf()) { spatialStates[it] != SpatialState.UNLOADED }
        val previous = missingSections.toMutableMap()
        for ([key, missing] in missingVersions) {
            if ((previous[key.sectionNode]?.sinceNanos ?: Long.MAX_VALUE) > missing.sinceNanos) {
                previous[key.sectionNode] = missing
            }
        }
        records.values.forEach(::releaseRecord)
        dormantRecords.values.forEach(::releaseRecord)
        records.clear()
        dormantRecords.clear()
        dormantBytes = 0L
        retainedSources.clear()
        retainedSourceBytes = 0L
        missingVersions.clear()
        missingSections.clear()
        producerManifests.clear()
        sourceRequirements.clear()
        invalidSections.clear()
        captureOrder.clear()
        captureAgeDirty = true
        oldestCaptureNanos = null
        mirroredRecords = emptyList()
        mirroredSlice = null
        full = false
        try {
            for (node in nodes) queueSectionCapture(node, SpatialState.DIRTY, previous[node])
            sourceUniverse = newUniverse
            currentSourceUniverse = newUniverse.snapshot()
            publish()
        } finally {
            revision.incrementAndGet()
        }
    }

    @JvmStatic
    @Synchronized
    fun invalidateSections(nodes: LongArray, publish: () -> Unit) {
        val active = nodes.filterTo(linkedSetOf()) { spatialStates[it] != SpatialState.UNLOADED }
        val previous = active.associateWith(::oldestCapture)
        val retired = retireSections(active)
        try {
            for (node in active) queueSectionCapture(node, SpatialState.DIRTY, previous[node])
            publish()
        } finally {
            if (retired || active.isNotEmpty()) revision.incrementAndGet()
        }
    }

    private fun queueSectionCapture(sectionNode: Long, state: SpatialState, previous: MissingCapture?) {
        invalidSections += sectionNode
        spatialStates[sectionNode] = state
        missingSections[sectionNode] = MissingCapture(++nextSectionCaptureVersion,
            previous?.sinceNanos ?: System.nanoTime())
        captureAgeDirty = true
        captureOrder += RecordKey(sectionNode, SECTION_CAPTURE_LAYER)
    }

    @JvmStatic
    @Synchronized
    fun isSectionUnloaded(sectionNode: Long): Boolean = spatialStates[sectionNode] == SpatialState.UNLOADED

    @JvmStatic
    @Synchronized
    fun replaceChunk(nodes: LongArray, loaded: Boolean, dependent: LongArray = longArrayOf(),
                     retired: LongArray = longArrayOf(), publish: () -> Unit) {
        val state = if (loaded) SpatialState.LOADED_UNPROVEN else SpatialState.UNLOADED
        val changedNodes = nodes.toSet()
        val affected = changedNodes + retired.asList() + dependent.asList()
        val previous = affected.associateWith(::oldestCapture)
        var changed = retireSections(affected)
        try {
            for (node in retired) {
                if (spatialStates.put(node, SpatialState.UNLOADED) != SpatialState.UNLOADED) changed = true
            }
            for (node in nodes) {
                if (spatialStates.put(node, state) != state) changed = true
            }
            val required = if (loaded) changedNodes + dependent.asList() else dependent.asList().toSet()
            for (node in required) {
                if (spatialStates[node] != SpatialState.UNLOADED) {
                    queueSectionCapture(node, if (node in changedNodes) state else SpatialState.DIRTY, previous[node])
                    changed = true
                }
            }
            publish()
        } finally {
            if (changed) revision.incrementAndGet()
        }
    }

    @JvmStatic
    @Synchronized
    fun releaseSection(sectionNode: Long, publish: () -> Unit) {
        val previous = oldestCapture(sectionNode)
        val state = spatialStates[sectionNode] ?: SpatialState.LOADED_UNPROVEN
        retireSections(setOf(sectionNode))
        try {
            if (state != SpatialState.UNLOADED) queueSectionCapture(sectionNode, state, previous)
            publish()
        } finally {
            revision.incrementAndGet()
        }
    }

    @JvmStatic
    @Synchronized
    internal fun retainSection(sectionNode: Long, scope: D3D12SectionGeometry.CompileScope): LongArray? {
        val producer = producerManifests[sectionNode] ?: return null
        if (producer.support.scope !== scope || scope.sectionNode != sectionNode || scope.epoch != geometryEpoch ||
            spatialStates[sectionNode] != SpatialState.LOADED_UNPROVEN || sectionNode in invalidSections ||
            missingSections.containsKey(sectionNode)) return null
        val current = D3D12SectionGeometry.Layer.entries.mapNotNull { records[RecordKey(sectionNode, it.ordinal)] }
        if (current.mapTo(hashSetOf()) { it.layer } != producer.requiredLayers ||
            producer.versions != current.associate { it.layer to it.geometryVersion } ||
            D3D12SectionGeometry.Layer.entries.any { missingVersions.containsKey(RecordKey(sectionNode, it.ordinal)) }) return null
        val byteCount = current.sumOf(::geometryByteCount)
        if (byteCount > RETAINED_SOURCE_BYTE_LIMIT) return null
        if (retainedSources.containsKey(sectionNode)) return longArrayOf()
        retainedSources[sectionNode] = byteCount
        retainedSourceBytes += byteCount
        return buildList {
            while (retainedSources.size > RETAINED_SOURCE_SECTION_LIMIT || retainedSourceBytes > RETAINED_SOURCE_BYTE_LIMIT) {
                val oldest = retainedSources.keys.first()
                releaseSection(oldest) {}
                add(oldest)
            }
        }.toLongArray()
    }

    @JvmStatic
    @JvmOverloads
    @Synchronized
    fun observeCompiledSection(sectionNode: Long, manifest: D3D12RtModelSupport.Manifest? = null) {
        val keys = D3D12SectionGeometry.Layer.entries.map { RecordKey(sectionNode, it.ordinal) }
        val current = keys.mapNotNull { records[it] }
        val missing = keys.mapNotNull { key -> missingVersions[key]?.let { key to it } }.toMap()
        val publication = sectionPublication?.takeIf { it.node == sectionNode && it.epoch == geometryEpoch }
        val accepted = manifest?.takeIf { support ->
            publication != null && support.scope.sectionNode == sectionNode && support.scope.epoch == geometryEpoch && support.complete &&
                publication.layers.all { support.bounds(it) != null } &&
                current.all { support.contains(it.layer, it.outputBounds) } &&
                missing.keys.all { support.bounds(it.layer) != null }
        }?.let { support ->
            ProducerManifest(support, buildMap {
                for (record in current) put(record.layer, record.geometryVersion)
                for ([key, requirement] in missing) put(key.layer, requirement.version)
            }, checkNotNull(publication).layers)
        }
        val previous = producerManifests.remove(sectionNode)
        if (accepted != null) producerManifests[sectionNode] = accepted
        val stateChanged = spatialStates.put(sectionNode, SpatialState.LOADED_UNPROVEN) != SpatialState.LOADED_UNPROVEN
        if (previous != accepted || stateChanged) {
            revision.incrementAndGet()
        }
    }

    @JvmStatic
    @Synchronized
    fun publishSourceSupport(target: CaptureTarget, manifest: D3D12RtModelSupport.SourceManifest): Boolean {
        if (target.layer != SECTION_CAPTURE_LAYER || target.sectionNode != manifest.scope.sectionNode ||
            target.epoch != manifest.scope.epoch || !isMissing(target)) return false
        val bounds = manifest.bounds
        val accepted = bounds?.takeIf {
            it.minimumX.isFinite() && it.minimumY.isFinite() && it.minimumZ.isFinite() &&
                it.maximumX.isFinite() && it.maximumY.isFinite() && it.maximumZ.isFinite() &&
                it.minimumX <= 0f && it.minimumY <= 0f && it.minimumZ <= 0f &&
                it.maximumX >= 16f && it.maximumY >= 16f && it.maximumZ >= 16f
        }?.let { SourceRequirement(manifest, target.version) }
        val previous = sourceRequirements.remove(target.sectionNode)
        if (accepted != null) sourceRequirements[target.sectionNode] = accepted
        if (previous != accepted) revision.incrementAndGet()
        return accepted != null
    }

    @JvmStatic
    @Synchronized
    fun revokeSourceSupport(manifest: D3D12RtModelSupport.SourceManifest) {
        val node = manifest.scope.sectionNode
        if (sourceRequirements[node]?.support !== manifest) return
        sourceRequirements.remove(node)
        revision.incrementAndGet()
    }

    @JvmStatic
    @Synchronized
    fun replaceSection(sectionNode: Long, layers: Set<String> = emptySet(), publish: () -> Unit) {
        val retained = layers.map { D3D12SectionGeometry.Layer.of(it).ordinal }.toSet()
        val previous = oldestCapture(sectionNode)
        val changed = retireSections(setOf(sectionNode), retained)
        val previousPublication = sectionPublication
        val publication = SectionPublication(sectionNode, geometryEpoch, retained)
        sectionPublication = publication
        try {
            publish()
        } catch (failure: Throwable) {
            if (sectionPublication === publication && producerManifests.remove(sectionNode) != null) revision.incrementAndGet()
            throw failure
        } finally {
            if (sectionPublication === publication) {
                sectionPublication = previousPublication?.takeIf { it.epoch == geometryEpoch }
                if (previous != null) {
                    for (layer in retained) {
                        val key = RecordKey(sectionNode, layer)
                        val missing = missingVersions[key] ?: continue
                        if (missing.sinceNanos > previous.sinceNanos) {
                            missingVersions[key] = missing.copy(sinceNanos = previous.sinceNanos)
                            captureAgeDirty = true
                        }
                    }
                }
                if (changed) revision.incrementAndGet()
            }
        }
    }

    private fun retireSections(nodes: Set<Long>, retained: Set<Int> = emptySet()): Boolean {
        var changed = false
        for (node in nodes) {
            retainedSources.remove(node)?.let { retainedSourceBytes -= it }
            if (producerManifests.remove(node) != null) changed = true
            if (sourceRequirements.remove(node) != null) changed = true
            if (invalidSections.remove(node)) changed = true
            if (removeSectionCapture(node)) changed = true
        }
        val missing = missingVersions.keys.filter { it.sectionNode in nodes && it.layer !in retained }
        for (key in missing) removeMissing(key)
        val removed = records.entries.removeAll { entry ->
            if (entry.value.sectionNode in nodes && entry.value.layer !in retained) {
                retainDormant(entry.key, entry.value)
                true
            } else {
                false
            }
        }
        return changed || removed || missing.isNotEmpty()
    }

    private fun geometryByteCount(record: Record): Long =
        record.vertexCount.toLong() * STRIDE + record.indexCount.toLong() * record.indexStride +
            record.quadCount.toLong() * MATERIAL_BYTES + RECORD_BYTES

    private fun removeDormant(key: RecordKey): Record? = dormantRecords.remove(key)?.also {
        dormantBytes -= geometryByteCount(it)
    }

    private fun retainDormant(key: RecordKey, record: Record) {
        removeDormant(key)?.let(::releaseRecord)
        val byteCount = geometryByteCount(record)
        if (byteCount > DORMANT_BYTE_LIMIT) {
            releaseRecord(record)
            return
        }
        dormantRecords[key] = record
        dormantBytes += byteCount
        while (dormantRecords.size > DORMANT_RECORD_LIMIT || dormantBytes > DORMANT_BYTE_LIMIT) {
            evictDormant()
        }
    }

    private fun evictDormant(): Boolean {
        val oldest = dormantRecords.entries.firstOrNull() ?: return false
        val record = checkNotNull(removeDormant(oldest.key))
        releaseRecord(record)
        return true
    }

    private fun allocateStorage(vertexBytes: Int, indexBytes: Long, materialBytes: Long): Allocation? {
        if (vertexBytes > VERTEX_CAP || indexBytes !in 0..INDEX_CAP || materialBytes !in 0..MATERIAL_CAP) return null
        val indexSize = indexBytes.toInt()
        val materialSize = materialBytes.toInt()
        while (!vertices.canAllocate(vertexBytes) || !indices.canAllocate(indexSize) ||
            !materials.canAllocate(materialSize)) {
            if (!evictDormant()) return null
        }
        var vertexOffset: Int? = null
        var indexOffset: Int? = null
        var materialOffset: Int? = null
        var complete = false
        try {
            vertexOffset = checkNotNull(vertices.allocate(vertexBytes))
            indexOffset = checkNotNull(indices.allocate(indexSize))
            materialOffset = checkNotNull(materials.allocate(materialSize))
            val allocation = Allocation(vertexOffset, vertexBytes, indexOffset, indexSize, materialOffset, materialSize)
            complete = true
            return allocation
        } finally {
            if (!complete) {
                vertexOffset?.let { vertices.release(it, vertexBytes) }
                indexOffset?.let { indices.release(it, indexSize) }
                materialOffset?.let { materials.release(it, materialSize) }
            }
        }
    }

    private fun releaseStorage(allocation: Allocation) {
        vertices.release(allocation.vertexOffset, allocation.vertexBytes)
        indices.release(allocation.indexOffset, allocation.indexBytes)
        materials.release(allocation.materialOffset, allocation.materialBytes)
    }

    private fun releaseRecord(record: Record) {
        vertices.release(record.vertexOffset.toInt(), record.vertexCount * STRIDE)
        indices.release(record.indexOffset.toInt(), record.indexCount * record.indexStride)
        materials.release(record.materialOffset.toInt(), record.quadCount * MATERIAL_BYTES)
    }

    private fun matchesGeometry(previous: Record, vertex: ByteBuffer, index: ByteBuffer?,
                                indexCount: Int, indexStride: Int, includeShading: Boolean = true): Boolean {
        if (previous.vertexCount * STRIDE != vertex.remaining() || previous.indexCount != indexCount ||
            previous.indexStride != indexStride) return false
        val incoming = vertex.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val oldVertices = vertices.view(previous.vertexOffset, vertex.remaining())
        for (vertexIndex in 0 until previous.vertexCount) {
            val oldOffset = vertexIndex * STRIDE
            val newOffset = incoming.position() + vertexIndex * STRIDE
            if (oldVertices.getLong(oldOffset) != incoming.getLong(newOffset) ||
                oldVertices.getInt(oldOffset + 8) != incoming.getInt(newOffset + 8)) return false
            if (includeShading && (oldVertices.getInt(oldOffset + 12) != incoming.getInt(newOffset + 12) ||
                oldVertices.getLong(oldOffset + 16) != incoming.getLong(newOffset + 16))) return false
        }
        val oldIndices = indices.view(previous.indexOffset, indexCount * indexStride)
        if (index != null) return index.remaining() == oldIndices.remaining() && oldIndices == index
        val pattern = intArrayOf(0, 1, 2, 0, 2, 3)
        oldIndices.order(ByteOrder.LITTLE_ENDIAN)
        for (indexNumber in 0 until indexCount) {
            val actual = if (indexStride == 4) oldIndices.int else oldIndices.short.toInt() and 0xffff
            if (actual != indexNumber / QUAD_INDICES * QUAD_VERTICES + pattern[indexNumber % QUAD_INDICES]) return false
        }
        return true
    }

    @JvmStatic
    fun recordCount(): Int = records.size

    @JvmStatic
    fun records(): Collection<Record> = records.values

    @JvmStatic
    @Synchronized
    fun isCurrent(sectionNode: Long, layer: Int, geometryVersion: Int): Boolean =
        records[RecordKey(sectionNode, layer)]?.geometryVersion == geometryVersion

    data class Versions(val epoch: Int, val revision: Long, val records: ByteBuffer, val outputs: ByteBuffer)

    @JvmStatic
    @Synchronized
    fun versions(): Versions {
        val universe = sourceUniverse.snapshot()
        if (universe !== currentSourceUniverse) {
            currentSourceUniverse = universe
            revision.incrementAndGet()
        }
        val currentRevision = revision.get()
        cachedVersions?.takeIf { it.epoch == geometryEpoch && it.revision == currentRevision }?.let { return it }
        val data = ByteBuffer.allocate((records.size + missingVersions.size) * 16).order(ByteOrder.LITTLE_ENDIAN)
        val outputs = ByteBuffer.allocate((records.size + missingVersions.size + missingSections.size) * 48)
            .order(ByteOrder.LITTLE_ENDIAN)
        fun output(node: Long, layer: Int, version: Int, bounds: OutputBounds?) {
            outputs.putLong(node).putInt(layer).putInt(version).putInt(if (bounds == null) 0 else 1).putInt(0)
            outputs.putFloat(bounds?.minimumX ?: 0f).putFloat(bounds?.minimumY ?: 0f)
                .putFloat(bounds?.minimumZ ?: 0f).putFloat(bounds?.maximumX ?: 0f)
                .putFloat(bounds?.maximumY ?: 0f).putFloat(bounds?.maximumZ ?: 0f)
        }
        for (record in records.values) {
            data.putLong(record.sectionNode).putInt(record.layer).putInt(record.geometryVersion)
            output(record.sectionNode, record.layer, record.geometryVersion, record.outputBounds)
        }
        for ([key, missing] in missingVersions) {
            data.putLong(key.sectionNode).putInt(key.layer).putInt(missing.version)
            val support = producerManifests[key.sectionNode]?.takeIf { it.versions[key.layer] == missing.version }
            output(key.sectionNode, key.layer, missing.version, support?.support?.bounds(key.layer) ?: universe.bounds)
        }
        for ([node, missing] in missingSections) {
            val support = sourceRequirements[node]?.takeIf { it.version == missing.version }
            output(node, SECTION_CAPTURE_LAYER, missing.version, support?.support?.bounds ?: universe.bounds)
        }
        data.flip()
        outputs.flip()
        return Versions(geometryEpoch, currentRevision, data.asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN),
            outputs.asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN))
            .also { cachedVersions = it }
    }

    @JvmStatic
    @Synchronized
    fun sourceInventory(): D3D12RtSpatialSources.Snapshot {
        val versions = versions()
        return sourceInventory(versions)
    }

    private fun sourceInventory(versions: Versions): D3D12RtSpatialSources.Snapshot {
        cachedSpatialSources?.takeIf { it.epoch == versions.epoch && it.revision == versions.revision }?.let { return it }
        val frozenRecords = records.toMap()
        val frozenMissingLayers = missingVersions.toMap()
        val frozenMissingSections = missingSections.toMap()
        val frozenProducers = producerManifests.toMap()
        val frozenSupports = sourceRequirements.toMap()
        val frozenStates = spatialStates.toMap()
        return D3D12RtSpatialSources.Snapshot(versions.epoch, versions.revision, {
            val layers = mutableMapOf<Long, MutableList<D3D12RtSpatialSources.Layer>>()
            for (record in frozenRecords.values) {
                layers.getOrPut(record.sectionNode) { mutableListOf() } +=
                    D3D12RtSpatialSources.Layer(record.layer, record.geometryVersion, true)
            }
            for ([key, missing] in frozenMissingLayers) {
                layers.getOrPut(key.sectionNode) { mutableListOf() } +=
                    D3D12RtSpatialSources.Layer(key.layer, missing.version, false)
            }
            val nodes = sortedSetOf<Long>().apply {
                addAll(frozenStates.keys)
                addAll(layers.keys)
                addAll(frozenMissingSections.keys)
                addAll(frozenProducers.keys)
            }
            nodes.map { node ->
                val currentLayers = layers[node].orEmpty().sortedBy { it.layer }
                val producer = frozenProducers[node]?.takeIf { candidate ->
                    candidate.versions == currentLayers.associate { it.layer to it.version }
                }
                val source = frozenSupports[node]?.takeIf { it.version == frozenMissingSections[node]?.version }
                val support = (source?.support?.bounds ?: producer?.support?.sourceBounds)?.takeIf { bounds ->
                    currentLayers.all { layer ->
                        if (!layer.captured) true else frozenRecords[RecordKey(node, layer.layer)]?.outputBounds?.let { output ->
                            output.minimumX >= bounds.minimumX && output.minimumY >= bounds.minimumY &&
                                output.minimumZ >= bounds.minimumZ && output.maximumX <= bounds.maximumX &&
                                output.maximumY <= bounds.maximumY && output.maximumZ <= bounds.maximumZ
                        } == true
                    }
                }
                val requiredLayers = currentLayers.toMutableList()
                if (producer != null) {
                    for (layer in producer.requiredLayers) {
                        if (currentLayers.none { it.layer == layer }) {
                            requiredLayers += D3D12RtSpatialSources.Layer(layer, null, false)
                        }
                    }
                }
                D3D12RtSpatialSources.Source(node, frozenStates[node]?.code ?: 0, support,
                    source?.support?.scope ?: producer?.support?.scope, frozenMissingSections[node]?.version,
                    requiredLayers.sortedBy { it.layer }, producer != null)
            }
        }, currentSourceUniverse).also { cachedSpatialSources = it }
    }

    data class Coverage(val versions: Versions, val nodes: ByteBuffer, val spatial: ByteBuffer,
                        val centerX: Float, val centerY: Float, val centerZ: Float, val radius: Float,
                        val sources: D3D12RtSpatialSources.Snapshot)

    private fun spatialGrid(centerX: Float, centerY: Float, centerZ: Float, radius: Float,
                            sources: D3D12RtSpatialSources.Snapshot, targets: Set<Long>): ByteBuffer {
        val region = D3D12RtSpatialDirectory.region(centerX, centerY, centerZ, radius)
        cachedSpatialGrid?.takeIf { it.sources === sources && it.region == region && it.targets == targets }?.let {
            return it.bytes.asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN)
        }
        val frozen = D3D12RtSpatialDirectory.build(region, sources, targets) { spatialStates[it]?.code ?: 0 }
        cachedSpatialGrid = SpatialGrid(sources, region, targets.toSet(), frozen)
        return frozen.asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN)
    }

    private fun outputBounds(vertex: ByteBuffer): OutputBounds? {
        if (!vertex.hasRemaining() || vertex.remaining() % STRIDE != 0) return null
        val source = vertex.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        var minimumX = 0f
        var minimumY = 0f
        var minimumZ = 0f
        var maximumX = 16f
        var maximumY = 16f
        var maximumZ = 16f
        for (offset in source.position() until source.limit() step STRIDE) {
            val positionX = source.getFloat(offset)
            val positionY = source.getFloat(offset + 4)
            val positionZ = source.getFloat(offset + 8)
            if (!positionX.isFinite() || !positionY.isFinite() || !positionZ.isFinite()) return null
            minimumX = minOf(minimumX, positionX)
            minimumY = minOf(minimumY, positionY)
            minimumZ = minOf(minimumZ, positionZ)
            maximumX = maxOf(maximumX, positionX)
            maximumY = maxOf(maximumY, positionY)
            maximumZ = maxOf(maximumZ, positionZ)
        }
        return OutputBounds(minimumX, minimumY, minimumZ, maximumX, maximumY, maximumZ)
    }

    private fun outputDistanceSquared(record: Record, centerX: Float, centerY: Float, centerZ: Float): Double {
        val bounds = record.outputBounds ?: return 0.0
        val localX = centerX.toDouble() - record.originX
        val localY = centerY.toDouble() - record.originY
        val localZ = centerZ.toDouble() - record.originZ
        val deltaX = maxOf(bounds.minimumX - localX, 0.0, localX - bounds.maximumX)
        val deltaY = maxOf(bounds.minimumY - localY, 0.0, localY - bounds.maximumY)
        val deltaZ = maxOf(bounds.minimumZ - localZ, 0.0, localZ - bounds.maximumZ)
        return deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ
    }

    @JvmStatic
    @Synchronized
    fun coverageNear(centerX: Float, centerY: Float, centerZ: Float, radius: Float): Coverage {
        require(centerX.isFinite() && centerY.isFinite() && centerZ.isFinite() && radius.isFinite() && radius >= 0f)
        val versions = versions()
        cachedCoverage?.takeIf {
            it.versions === versions && it.centerX == centerX && it.centerY == centerY &&
                it.centerZ == centerZ && it.radius == radius
        }?.let { return it }
        val nodes = sortedSetOf<Long>()
        val limit = radius.toDouble() * radius
        for (record in records.values) {
            if (outputDistanceSquared(record, centerX, centerY, centerZ) <= limit) nodes += record.sectionNode
        }
        nodes += invalidSections
        nodes += missingVersions.keys.map { it.sectionNode }
        val targets = ByteBuffer.allocate(nodes.size * 8).order(ByteOrder.LITTLE_ENDIAN)
        for (node in nodes) targets.putLong(node)
        targets.flip()
        val sources = sourceInventory(versions)
        return Coverage(versions, targets.asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN),
            spatialGrid(centerX, centerY, centerZ, radius, sources, nodes),
            centerX, centerY, centerZ, radius, sources)
            .also { cachedCoverage = it }
    }

    @JvmStatic
    @Synchronized
    fun changedSections(published: ByteBuffer, current: ByteBuffer): Set<Long> {
        val before = published.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val after = current.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val versions = mutableMapOf<RecordKey, Int>()
        val changed = mutableSetOf<Long>()
        for (base in 0 until before.limit() step RECORD_BYTES) {
            val node = before.getLong(base + 56)
            val key = RecordKey(node, before.getInt(base + 12))
            val version = before.getInt(base + 44)
            versions[key] = version
            if (records[key]?.geometryVersion != version) changed += node
        }
        for (base in 0 until after.limit() step RECORD_BYTES) {
            val node = after.getLong(base + 56)
            val key = RecordKey(node, after.getInt(base + 12))
            if (versions[key] != after.getInt(base + 44)) changed += node
        }
        return changed
    }

    @JvmStatic
    fun triangleCount(): Long = records.values.sumOf { it.indexCount.toLong() / 3 }

    /** 顶点 / 索引 / 材质池的独立只读快照，逻辑偏移与 Record 对应。 */
    @JvmStatic
    @Synchronized
    fun vertexBytes(): ByteBuffer = vertices.snapshot()

    @JvmStatic
    @Synchronized
    fun indexBytes(): ByteBuffer = indices.snapshot()

    @JvmStatic
    @Synchronized
    fun materialBytes(): ByteBuffer = materials.snapshot()

    data class StorageStats(
        val vertexBytes: Long,
        val indexBytes: Long,
        val materialBytes: Long,
        val highWaterBytes: Long,
        val allocationCount: Int,
        val dormantRecordCount: Int,
        val dormantBytes: Long,
        val retainedContentBytes: Long,
        val retainedSourceSections: Int,
        val retainedSourceBytes: Long,
    ) {
        val residentBytes: Long get() = vertexBytes + indexBytes + materialBytes
    }

    @JvmStatic
    @Synchronized
    fun storageStats(): StorageStats {
        var retainedBytes = 0L
        retainedContent.removeAll { reference ->
            val allocation = reference.get()
            if (allocation == null) true else {
                retainedBytes += allocation.byteCount
                false
            }
        }
        return StorageStats(vertices.allocatedBytes, indices.allocatedBytes,
            materials.allocatedBytes, vertices.highWater.toLong() + indices.highWater + materials.highWater,
            vertices.allocationCount + indices.allocationCount + materials.allocationCount,
            dormantRecords.size, dormantBytes, retainedBytes, retainedSources.size, retainedSourceBytes)
    }

    @JvmStatic
    fun summary(): String = "rt geometry pool: records=${records.size} published=${published.get()} " +
        "skipped=${skipped.get()} hidden=${hidden.get()} full=$full triangles=${triangleCount()} " +
        "v=${vertices.allocatedBytes shr 20}MB i=${indices.allocatedBytes shr 20}MB " +
        "m=${materials.allocatedBytes shr 20}MB copied=${copiedBytes.get() shr 20}MB"

    /**
     * 材质表刚烘好时回填全部记录的材质索引：表就绪之前的上传只能写兜底值。
     * 见 docs/rt/README.md
     */
    @JvmStatic
    @Synchronized
    fun refreshMaterials(): Int {
        if (materials.allocatedBytes == 0L) {
            return 0
        }
        var updated = 0
        for (record in records.values) {
            if (record.quadCount <= 0) continue
            val vertexData = vertices.view(record.vertexOffset, record.vertexCount * STRIDE)
            val materialIds = D3D12RtMaterialTable.quadMaterials(vertexData, 0, record.quadCount)
            if (remapMaterials(record, materialIds)) updated++
        }
        if (updated > 0) {
            revision.incrementAndGet()
            mirroredSlice = null
        }
        return updated
    }

    private fun remapMaterials(record: Record, ids: ShortArray): Boolean {
        val previous = materials.view(record.materialOffset, record.quadCount * MATERIAL_BYTES)
        if (ids.indices.all { previous.getShort(it * MATERIAL_BYTES) == ids[it] }) return false
        val replacement = ByteBuffer.allocate(previous.remaining()).order(ByteOrder.LITTLE_ENDIAN)
        for (id in ids) replacement.putShort(id)
        replacement.flip()
        materials.replace(record.materialOffset, replacement)
        return true
    }

    /**
     * 每条记录的索引必须落在自己的顶点区间内；返回违规记录数。
     * 偏移或步长错一位时首个索引就会越界，抽样足够抓住结构错误。
     * 见 docs/rt/README.md
     */
    @JvmStatic
    @Synchronized
    fun validate(): Int {
        var bad = 0
        for (record in records.values) {
            if (record.indexCount <= 0) continue
            val view = indices.view(record.indexOffset, record.indexCount * record.indexStride)
            val step = (record.indexCount / VALIDATE_SAMPLES).coerceAtLeast(1)
            var violation = false
            var indexNumber = 0
            while (indexNumber < record.indexCount) {
                val offset = indexNumber * record.indexStride
                if (offset + record.indexStride > view.limit()) {
                    violation = true
                    break
                }
                val value = if (record.indexStride == 4) view.getInt(offset) else view.getShort(offset).toInt() and 0xFFFF
                if (value >= record.vertexCount) {
                    violation = true
                    break
                }
                indexNumber += step
            }
            if (!violation) {
                val last = (record.indexCount - 1) * record.indexStride
                if (last + record.indexStride > view.limit()) {
                    violation = true
                } else {
                    val value = if (record.indexStride == 4) view.getInt(last) else view.getShort(last).toInt() and 0xFFFF
                    violation = value >= record.vertexCount
                }
            }
            if (violation) {
                bad++
            }
        }
        return bad
    }

    private const val VALIDATE_SAMPLES = 64

    /** 一条记录在 GPU 侧的字节数（64 B，见 rt_plan §3.4 的 `SectionRecord`）。 */
    const val RECORD_BYTES = 64

    /**
     * 把当前所有记录打包成 `RECORD_BYTES` 步长的字节块，供 blocks 当 `ByteAddressBuffer` 读。
     * 见 docs/rt/README.md
     */
    @JvmStatic
    @Synchronized
    fun recordBytes(): ByteBuffer {
        val ordered = records.values.sortedWith(
            compareBy({ it.sectionNode }, { it.layer }))
        val buffer = ByteBuffer.allocate(ordered.size * RECORD_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        for (r in ordered) {
            buffer.putInt(r.originX)
            buffer.putInt(r.originY)
            buffer.putInt(r.originZ)
            buffer.putInt(r.layer)
            buffer.putLong(r.vertexOffset)
            buffer.putLong(r.indexOffset)
            buffer.putInt(r.vertexCount)
            buffer.putInt(r.indexCount)
            buffer.putInt(r.indexStride or (r.flags shl 8))
            buffer.putInt(r.geometryVersion)
            buffer.putInt(r.materialOffset.toInt())
            buffer.putInt(r.quadCount)
            buffer.putLong(r.sectionNode)
        }
        buffer.flip()
        return buffer
    }

    /**
     * 相机附近那一小片几何的紧凑副本，供 compute 上传成镜像缓冲。
     * 的 memcpy 加一次大分配，画面会直接卡死。这里只挑相机附近、且总量封顶的一批段，几十毫秒就能传完。
     * 见 docs/rt/README.md
     */
    class MirrorSlice(
        val geometryEpoch: Int,
        val geometryRevision: Long,
        val records: ByteBuffer,
        val contents: List<ContentRecord>,
        val vertexBytes: Int,
        val indexBytes: Int,
        val quadBytes: Int,
        val recordCount: Int,
        val triangles: Long,
    ) {
        val vertices: ByteBuffer by lazy { pack(vertexBytes, 16) { it.vertices } }
        val indices: ByteBuffer by lazy { pack(indexBytes, 24) { it.indices } }
        val quads: ByteBuffer by lazy { pack(quadBytes, 48) { it.quads } }

        private fun pack(byteCount: Int, offsetField: Int, select: (ContentRecord) -> Content): ByteBuffer {
            val output = ByteBuffer.allocateDirect(pad4(byteCount.toLong())).order(ByteOrder.LITTLE_ENDIAN)
            for ([index, entry] in contents.withIndex()) {
                val offset = if (offsetField == 48) records.getInt(index * RECORD_BYTES + offsetField) * MATERIAL_BYTES
                    else records.getLong(index * RECORD_BYTES + offsetField).toInt()
                output.position(offset)
                output.put(select(entry).bytes)
            }
            output.limit(byteCount).position(0)
            return output.asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN)
        }
    }

    /**
     * 以当前输出界选择完整段层组，导出 64 B 记录与不可变内容清单。
     * 见 docs/rt/README.md
     */
    private fun pad4(bytes: Long): Int = ((bytes + 3L) and -4L).toInt()

    private val contractLog = java.util.concurrent.atomic.AtomicBoolean(false)

    @JvmStatic
    @Synchronized
    fun mirrorNear(x: Float, y: Float, z: Float, radius: Float, maxRecords: Int,
                   maxBytes: Long): MirrorSlice? {
        if (maxRecords <= 0 || maxBytes <= 0) {
            return null
        }
        require(x.isFinite() && y.isFinite() && z.isFinite() && radius.isFinite() && radius >= 0f)
        val limit = radius.toDouble() * radius
        val intersecting = hashSetOf<Long>()
        for (record in records.values) {
            if (outputDistanceSquared(record, x, y, z) <= limit) intersecting += record.sectionNode
        }
        val picked = ArrayList<Pair<Record, Double>>(records.size.coerceAtMost(256))
        for (record in records.values) {
            if (record.sectionNode !in intersecting) continue
            val deltaX = record.originX + 8.0 - x
            val deltaY = record.originY + 8.0 - y
            val deltaZ = record.originZ + 8.0 - z
            val distance = deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ
            picked.add(record to distance)
        }
        picked.sortWith(compareBy<Pair<Record, Double>> { it.second }.thenBy { it.first.sectionNode })
        // 先按上限挑出真正要装的段，算出精确字节数再分配：一次装不下的大段不能撑爆输出缓冲。
        val chosen = ArrayList<Record>(minOf(picked.size, maxRecords))
        var verticesBytes = 0L
        var indicesBytes = 0L
        var quadsBytes = 0L
        for (sectionRecords in picked.groupBy({ it.first.sectionNode }, { it.first }).values) {
            if (sectionRecords.size > maxRecords - chosen.size) break
            val vertexBytes = sectionRecords.sumOf { it.vertexCount.toLong() * STRIDE }
            val indexBytes = sectionRecords.sumOf { pad4(it.indexCount.toLong() * it.indexStride).toLong() }
            val quadBytes = sectionRecords.sumOf { it.quadCount.toLong() * MATERIAL_BYTES }
            if (verticesBytes + indicesBytes + quadsBytes + vertexBytes + indexBytes + quadBytes > maxBytes) {
                break
            }
            chosen.addAll(sectionRecords)
            verticesBytes += vertexBytes
            indicesBytes += indexBytes
            quadsBytes += quadBytes
        }
        if (chosen.isEmpty() && !coverageNear(x, y, z, radius).nodes.hasRemaining()) {
            return null
        }
        chosen.sortWith(compareBy<Record> { it.sectionNode }.thenBy { it.layer })
        mirroredSlice?.let { cached ->
            if (mirroredRecords == chosen) return cached
        }
        // raw SRV 的元素粒度是 4 字节（NumElements = size/4），长度不是 4 的倍数时最后几个字节读不到：
        // 索引按 u16 计，奇数个索引就只有 2 字节余量，会把三条边之一读成 0 得到退化三角形。
        val recordsOut = ByteBuffer.allocateDirect(chosen.size * RECORD_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        val contents = ArrayList<ContentRecord>(chosen.size)
        var vertexPosition = 0L
        var indexPosition = 0L
        var quadPosition = 0
        var triangles = 0L
        for (r in chosen) {
            val vertexBytes = r.vertexCount.toLong() * STRIDE
            val indexBytes = r.indexCount.toLong() * r.indexStride
            val quadBytes = r.quadCount.toLong() * MATERIAL_BYTES
            recordsOut.putInt(r.originX)
            recordsOut.putInt(r.originY)
            recordsOut.putInt(r.originZ)
            recordsOut.putInt(r.layer)
            indexPosition = pad4(indexPosition).toLong()
            recordsOut.putLong(vertexPosition)
            recordsOut.putLong(indexPosition)
            recordsOut.putInt(r.vertexCount)
            recordsOut.putInt(r.indexCount)
            recordsOut.putInt(r.indexStride or (r.flags shl 8))
            recordsOut.putInt(r.geometryVersion)
            // 材质偏移以 quad 为单位（shader 里 `RtQuadMaterial` 自己乘 2），不是字节偏移。
            recordsOut.putInt(quadPosition / MATERIAL_BYTES)
            recordsOut.putInt(r.quadCount)
            recordsOut.putLong(r.sectionNode)
            contents += ContentRecord(r, vertices.export(r.vertexOffset, vertexBytes.toInt()),
                indices.export(r.indexOffset, indexBytes.toInt()), materials.export(r.materialOffset, quadBytes.toInt()))
            vertexPosition += vertexBytes
            indexPosition += indexBytes
            quadPosition += quadBytes.toInt()
            triangles += r.triangles
        }
        // 一次性对账：记录原点与顶点坐标必须分别是「世界坐标」与「段内局部坐标」，
        // 两者语义搞反了（或都加了原点）会让几何整体跑到别处，射线一条都打不到。
        if (contractLog.compareAndSet(false, true) && contents.isNotEmpty()) {
            val first = recordsOut.getInt(0)
            val second = recordsOut.getInt(4)
            val third = recordsOut.getInt(8)
            val firstVertices = contents.first().vertices.bytes
            val vx = firstVertices.getFloat(0)
            val vy = firstVertices.getFloat(4)
            val vz = firstVertices.getFloat(8)
            io.github.dxegp.misc.IllusionLogger.infoMessage(
                "rt mirror: origin=($first,$second,$third) vertex=($vx,$vy,$vz)")
        }
        recordsOut.flip()
        return MirrorSlice(geometryEpoch, revision.get(), recordsOut.asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN),
            contents, vertexPosition.toInt(), pad4(indexPosition), quadPosition, chosen.size, triangles).also {
            mirroredRecords = chosen
            mirroredSlice = it
        }
    }

    /** 逐层三角形数与顶点数的一行摘要，验收时与 `section geometry` 行对账。 */
    @JvmStatic
    fun layerSummary(): String {
        val byLayer = IntArray(4)
        val verts = LongArray(4)
        for (r in records.values) {
            byLayer[r.layer] += r.triangles
            verts[r.layer] += r.vertexCount.toLong()
        }
        return "rt geometry pool layers: solid=${byLayer[0]} cutout=${byLayer[1]} " +
            "translucent=${byLayer[2]} other=${byLayer[3]} | v solid=${verts[0]} cutout=${verts[1]}"
    }

    @JvmStatic
    @Synchronized
    fun clear(epoch: Int) {
        geometryEpoch = epoch
        cachedVersions = null
        cachedSpatialSources = null
        cachedCoverage = null
        cachedSpatialGrid = null
        sectionPublication = null
        if (records.isNotEmpty()) {
            resets.incrementAndGet()
        }
        full = false
        mirroredRecords = emptyList()
        mirroredSlice = null
        hidden.set(0)
        revision.incrementAndGet()
        records.clear()
        missingVersions.clear()
        missingSections.clear()
        producerManifests.clear()
        sourceRequirements.clear()
        captureAgeDirty = true
        oldestCaptureNanos = null
        invalidSections.clear()
        spatialStates.clear()
        captureOrder.clear()
        dormantRecords.clear()
        dormantBytes = 0L
        retainedSources.clear()
        retainedSourceBytes = 0L
        resetPools()
    }

    private fun resetPools() {
        revision.incrementAndGet()
        vertices = GeometryArena(VERTEX_CAP.toInt())
        indices = GeometryArena(INDEX_CAP.toInt())
        materials = GeometryArena(MATERIAL_CAP.toInt())
    }

    /** 原版共享顺序 quad 缓冲的模式：`(4q, 4q+1, 4q+2, 4q, 4q+2, 4q+3)`。 */
    private fun writeSequentialQuadIndices(target: ByteBuffer, quadCount: Int, stride: Int) {
        val pattern = IntArray(QUAD_INDICES)
        for (q in 0 until quadCount) {
            val base = q * QUAD_VERTICES
            pattern[0] = base
            pattern[1] = base + 1
            pattern[2] = base + 2
            pattern[3] = base
            pattern[4] = base + 2
            pattern[5] = base + 3
            for (value in pattern) {
                if (stride == 4) target.putInt(value) else target.putShort(value.toShort())
            }
        }
    }
}


