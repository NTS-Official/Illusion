package io.github.dxegp.experimental.raytracing

import io.github.dxegp.d3d12.render.advanced.mesh.D3D12SectionGeometry
import net.minecraft.core.SectionPos
import kotlin.math.ceil
import kotlin.math.floor

object D3D12RtSpatialSources {
    data class Layer(val layer: Int, val version: Int?, val captured: Boolean)

    class Source internal constructor(
        val sectionNode: Long,
        val state: Int,
        val bounds: D3D12RtGeometryPool.OutputBounds?,
        val scope: D3D12SectionGeometry.CompileScope?,
        val captureVersion: Int?,
        layers: List<Layer>,
        val layerSetKnown: Boolean,
    ) {
        val layers: List<Layer> = java.util.List.copyOf(layers)

        internal fun influences(sectionNode: Long, fallback: D3D12RtGeometryPool.OutputBounds? = null): Boolean {
            val support = bounds ?: fallback ?: return true
            val relativeX = (SectionPos.x(sectionNode).toLong() - SectionPos.x(this.sectionNode)) * 16L
            val relativeY = (SectionPos.y(sectionNode).toLong() - SectionPos.y(this.sectionNode)) * 16L
            val relativeZ = (SectionPos.z(sectionNode).toLong() - SectionPos.z(this.sectionNode)) * 16L
            return support.minimumX.toDouble() <= relativeX + 16.0 && support.maximumX.toDouble() >= relativeX &&
                support.minimumY.toDouble() <= relativeY + 16.0 && support.maximumY.toDouble() >= relativeY &&
                support.minimumZ.toDouble() <= relativeZ + 16.0 && support.maximumZ.toDouble() >= relativeZ
        }
    }

    class SectionRange internal constructor(val minimumX: Int, val minimumY: Int, val minimumZ: Int,
                                            val maximumX: Int, val maximumY: Int, val maximumZ: Int) {
        fun contains(sectionNode: Long): Boolean = SectionPos.x(sectionNode) in minimumX..maximumX &&
            SectionPos.y(sectionNode) in minimumY..maximumY && SectionPos.z(sectionNode) in minimumZ..maximumZ

        internal fun hasUnobserved(observed: Set<Long>): Boolean {
            var count = 1L
            for (width in listOf(maximumX - minimumX + 1, maximumY - minimumY + 1, maximumZ - minimumZ + 1)) {
                if (width.toLong() > observed.size.toLong() / count) return true
                count *= width
            }
            for (sectionZ in minimumZ..maximumZ) {
                for (sectionY in minimumY..maximumY) {
                    for (sectionX in minimumX..maximumX) {
                        if (SectionPos.asLong(sectionX, sectionY, sectionZ) !in observed) return true
                    }
                }
            }
            return false
        }

        internal fun countAtMost(maximum: Int): Int? {
            var count = 1L
            for (width in listOf(maximumX - minimumX + 1, maximumY - minimumY + 1, maximumZ - minimumZ + 1)) {
                if (width <= 0) return 0
                if (width.toLong() > maximum.toLong() / count) return null
                count *= width
            }
            return count.toInt()
        }
    }

    class Requirements internal constructor(val sectionNode: Long, sources: List<Source>,
                                           val unobservedMayInfluence: Boolean,
                                           val unobservedRange: SectionRange?) {
        val sources: List<Source> = java.util.List.copyOf(sources)
    }

    class Snapshot @JvmOverloads internal constructor(val epoch: Int, val revision: Long, sourceFactory: () -> List<Source>,
                                                      val universe: D3D12RtSourceUniverse.Snapshot =
                                                          D3D12RtSourceUniverse.unavailable) {
        val sources: List<Source> by lazy { java.util.List.copyOf(sourceFactory()) }
        private val observed by lazy { sources.mapTo(hashSetOf()) { it.sectionNode } }
        val unobservedMayInfluence: Boolean get() = true
        val spatialFlags: Int get() = if (universe.bounds == null) 2 else 0

        fun unobservedRangeAt(sectionNode: Long): SectionRange? {
            val bounds = universe.bounds ?: return null
            fun lower(target: Int, maximum: Float, limit: Int): Int =
                (target.toLong() - floor(maximum.toDouble() / 16.0)
                    .coerceIn(-limit * 2.0, limit * 2.0).toLong()).coerceIn(-limit.toLong(), limit - 1L).toInt()
            fun upper(target: Int, minimum: Float, limit: Int): Int =
                (target.toLong() + 1L - ceil(minimum.toDouble() / 16.0)
                    .coerceIn(-limit * 2.0, limit * 2.0).toLong()).coerceIn(-limit.toLong(), limit - 1L).toInt()
            return SectionRange(lower(SectionPos.x(sectionNode), bounds.maximumX, 1 shl 21),
                lower(SectionPos.y(sectionNode), bounds.maximumY, 1 shl 19),
                lower(SectionPos.z(sectionNode), bounds.maximumZ, 1 shl 21),
                upper(SectionPos.x(sectionNode), bounds.minimumX, 1 shl 21),
                upper(SectionPos.y(sectionNode), bounds.minimumY, 1 shl 19),
                upper(SectionPos.z(sectionNode), bounds.minimumZ, 1 shl 21))
        }

        fun hasUnobservedAt(sectionNode: Long): Boolean = unobservedRangeAt(sectionNode)?.hasUnobserved(observed) ?: true

        fun requiredAt(sectionNode: Long): Requirements = Requirements(sectionNode,
            sources.filter { it.influences(sectionNode, universe.bounds) }, hasUnobservedAt(sectionNode), unobservedRangeAt(sectionNode))

        private val certifiable by lazy {
            val universeBounds = universe.bounds
            fun valid(bounds: D3D12RtGeometryPool.OutputBounds): Boolean =
                bounds.minimumX.isFinite() && bounds.minimumY.isFinite() && bounds.minimumZ.isFinite() &&
                    bounds.maximumX.isFinite() && bounds.maximumY.isFinite() && bounds.maximumZ.isFinite() &&
                    bounds.minimumX <= 0f && bounds.minimumY <= 0f && bounds.minimumZ <= 0f &&
                    bounds.maximumX >= 16f && bounds.maximumY >= 16f && bounds.maximumZ >= 16f
            universeBounds != null && valid(universeBounds) && observed.size == sources.size &&
                sources.none { source -> source.bounds?.let { bounds ->
                    !valid(bounds) || bounds.minimumX < universeBounds.minimumX || bounds.minimumY < universeBounds.minimumY ||
                        bounds.minimumZ < universeBounds.minimumZ || bounds.maximumX > universeBounds.maximumX ||
                        bounds.maximumY > universeBounds.maximumY || bounds.maximumZ > universeBounds.maximumZ
                } == true }
        }

        internal fun certifiedPages(pages: List<D3D12RtSpatialDirectory.Page>, targets: Set<Long>): List<IntArray> {
            require(pages.size <= D3D12RtSpatialDirectory.MAX_PAGES && pages.distinct().size == pages.size)
            val side = D3D12RtSpatialDirectory.PAGE_SIDE
            val universeBounds = universe.bounds
            if (!certifiable || universeBounds == null) return pages.map { IntArray(side * side * side) }
            val indices = pages.withIndex().associate { [index, page] -> page to index }
            val candidates = List(pages.size) { mutableListOf<Source>() }
            fun lower(origin: Int, minimum: Float): Int =
                (origin.toLong() + ceil(minimum.toDouble() / 16.0).coerceIn(-8388608.0, 8388608.0).toLong() - 1L)
                    .coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt().floorDiv(side)
            fun upper(origin: Int, maximum: Float): Int =
                (origin.toLong() + floor(maximum.toDouble() / 16.0).coerceIn(-8388608.0, 8388608.0).toLong())
                    .coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt().floorDiv(side)
            for (source in sources) {
                val minX = lower(SectionPos.x(source.sectionNode), universeBounds.minimumX)
                val minY = lower(SectionPos.y(source.sectionNode), universeBounds.minimumY)
                val minZ = lower(SectionPos.z(source.sectionNode), universeBounds.minimumZ)
                val maxX = upper(SectionPos.x(source.sectionNode), universeBounds.maximumX)
                val maxY = upper(SectionPos.y(source.sectionNode), universeBounds.maximumY)
                val maxZ = upper(SectionPos.z(source.sectionNode), universeBounds.maximumZ)
                val width = maxX.toLong() - minX + 1L
                val height = maxY.toLong() - minY + 1L
                val depth = maxZ.toLong() - minZ + 1L
                if (width > pages.size || height > pages.size || depth > pages.size ||
                    width * height > pages.size || width * height * depth > pages.size) {
                    pages.forEachIndexed { index, page ->
                        if (page.column in minX..maxX && page.row in minY..maxY && page.slice in minZ..maxZ)
                            candidates[index] += source
                    }
                } else {
                    for (slice in minZ..maxZ) for (row in minY..maxY) for (column in minX..maxX) {
                        indices[D3D12RtSpatialDirectory.Page(column, row, slice)]?.let { candidates[it] += source }
                    }
                }
            }
            return pages.mapIndexed { index, page ->
                certifiedStates(page.column * side, page.row * side, page.slice * side, side, targets, candidates[index])
            }
        }

        internal fun certifiedStates(minimumX: Int, minimumY: Int, minimumZ: Int, side: Int,
                                     targets: Set<Long>, candidates: List<Source> = sources): IntArray {
            require(side in 1..39)
            val states = IntArray(side * side * side)
            val universeBounds = universe.bounds ?: return states
            if (!certifiable) return states
            val padded = side + 1
            val possible = IntArray(padded * padded * padded)
            val unavailable = IntArray(possible.size)
            val nonempty = IntArray(possible.size)
            fun range(origin: Int, minimum: Float, maximum: Float, grid: Int): IntRange {
                val relative = origin.toLong() - grid
                val first = relative + ceil(minimum.toDouble() / 16.0).coerceIn(-8388608.0, 8388608.0).toLong() - 1L
                val last = relative + floor(maximum.toDouble() / 16.0).coerceIn(-8388608.0, 8388608.0).toLong()
                return first.coerceIn(0L, side.toLong()).toInt()..last.coerceIn(-1L, side - 1L).toInt()
            }
            fun add(values: IntArray, source: Source, bounds: D3D12RtGeometryPool.OutputBounds) {
                val horizontal = range(SectionPos.x(source.sectionNode), bounds.minimumX, bounds.maximumX, minimumX)
                val vertical = range(SectionPos.y(source.sectionNode), bounds.minimumY, bounds.maximumY, minimumY)
                val depth = range(SectionPos.z(source.sectionNode), bounds.minimumZ, bounds.maximumZ, minimumZ)
                if (horizontal.isEmpty() || vertical.isEmpty() || depth.isEmpty()) return
                for (corner in 0 until 8) {
                    val column = if (corner and 1 == 0) horizontal.first else horizontal.last + 1
                    val row = if (corner and 2 == 0) vertical.first else vertical.last + 1
                    val slice = if (corner and 4 == 0) depth.first else depth.last + 1
                    values[(slice * padded + row) * padded + column] += if (corner.countOneBits() and 1 == 0) 1 else -1
                }
            }
            for (source in candidates) {
                add(possible, source, universeBounds)
                val complete = source.state == 5 && source.captureVersion == null && source.layerSetKnown &&
                    source.scope?.epoch == epoch && source.bounds != null &&
                    source.layers.all { it.captured && it.version != null } &&
                    (source.layers.isEmpty() || source.sectionNode in targets)
                val bounds = source.bounds ?: universeBounds
                if (!complete) add(unavailable, source, bounds)
                else if (source.layers.isNotEmpty()) add(nonempty, source, bounds)
            }
            for (values in listOf(possible, unavailable, nonempty)) {
                for (slice in 0 until padded) for (row in 0 until padded) for (column in 1 until padded) {
                    val index = (slice * padded + row) * padded + column
                    values[index] += values[index - 1]
                }
                for (slice in 0 until padded) for (row in 1 until padded) for (column in 0 until padded) {
                    val index = (slice * padded + row) * padded + column
                    values[index] += values[index - padded]
                }
                for (slice in 1 until padded) for (row in 0 until padded) for (column in 0 until padded) {
                    val index = (slice * padded + row) * padded + column
                    values[index] += values[index - padded * padded]
                }
            }
            for (slice in 0 until side) for (row in 0 until side) for (column in 0 until side) {
                val index = (slice * padded + row) * padded + column
                if (unavailable[index] != 0) continue
                val node = SectionPos.asLong(minimumX + column, minimumY + row, minimumZ + slice)
                val required = unobservedRangeAt(node)?.countAtMost(sources.size) ?: continue
                if (possible[index] == required) states[(slice * side + row) * side + column] =
                    if (nonempty[index] == 0) 1 else 2
            }
            return states
        }
    }
}
