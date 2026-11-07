package io.github.dxegp.experimental.raytracing

import net.minecraft.core.SectionPos
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.PriorityQueue
import kotlin.math.ceil
import kotlin.math.floor

object D3D12RtSpatialDirectory {
    const val PAGE_SIDE = 8
    const val MAX_PAGES = 512
    const val PAGE_BYTES = PAGE_SIDE * PAGE_SIDE * PAGE_SIDE * 4
    const val MAX_BYTES = 64 + MAX_PAGES * (16 + PAGE_BYTES)

    data class Page(val column: Int, val row: Int, val slice: Int)

    data class Region(val minimumX: Int, val minimumY: Int, val minimumZ: Int,
                      val sizeX: Int, val sizeY: Int, val sizeZ: Int,
                      val focus: Page)

    fun region(centerX: Float, centerY: Float, centerZ: Float, radius: Float): Region {
        require(centerX.isFinite() && centerY.isFinite() && centerZ.isFinite() && radius.isFinite() && radius >= 0f)
        val reach = ceil(radius.toDouble() / 16.0).toLong().coerceIn(0L, 1L shl 22)
        fun extent(limit: Int): Int = (reach * 2L + 1L).coerceAtMost(limit * 2L).toInt()
        fun section(center: Float, limit: Int): Int =
            floor(center.toDouble() / 16.0).coerceIn(-limit.toDouble(), limit - 1.0).toInt()
        val sizeX = extent(1 shl 21)
        val sizeY = extent(1 shl 19)
        val sizeZ = sizeX
        val sectionX = section(centerX, 1 shl 21)
        val sectionY = section(centerY, 1 shl 19)
        val sectionZ = section(centerZ, 1 shl 21)
        return Region((sectionX.toLong() - reach).coerceIn(-(1L shl 21), (1L shl 21) - sizeX).toInt(),
            (sectionY.toLong() - reach).coerceIn(-(1L shl 19), (1L shl 19) - sizeY).toInt(),
            (sectionZ.toLong() - reach).coerceIn(-(1L shl 21), (1L shl 21) - sizeZ).toInt(),
            sizeX, sizeY, sizeZ, Page(sectionX.floorDiv(PAGE_SIDE), sectionY.floorDiv(PAGE_SIDE),
                sectionZ.floorDiv(PAGE_SIDE)))
    }

    internal fun pages(region: Region): List<Page> {
        val minimum = Page(region.minimumX.floorDiv(PAGE_SIDE), region.minimumY.floorDiv(PAGE_SIDE),
            region.minimumZ.floorDiv(PAGE_SIDE))
        val maximum = Page((region.minimumX + region.sizeX - 1).floorDiv(PAGE_SIDE),
            (region.minimumY + region.sizeY - 1).floorDiv(PAGE_SIDE),
            (region.minimumZ + region.sizeZ - 1).floorDiv(PAGE_SIDE))
        fun distance(page: Page): Long {
            val deltaX = page.column.toLong() - region.focus.column
            val deltaY = page.row.toLong() - region.focus.row
            val deltaZ = page.slice.toLong() - region.focus.slice
            return deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ
        }
        val pending = PriorityQueue(compareBy<Page> { distance(it) }.thenBy { it.slice }
            .thenBy { it.row }.thenBy { it.column })
        val visited = hashSetOf<Page>()
        fun enqueue(page: Page) {
            if (page.column in minimum.column..maximum.column && page.row in minimum.row..maximum.row &&
                page.slice in minimum.slice..maximum.slice && visited.add(page)) pending += page
        }
        enqueue(region.focus)
        return buildList {
            while (size < MAX_PAGES && pending.isNotEmpty()) {
                val page = pending.remove()
                add(page)
                enqueue(page.copy(column = page.column - 1))
                enqueue(page.copy(column = page.column + 1))
                enqueue(page.copy(row = page.row - 1))
                enqueue(page.copy(row = page.row + 1))
                enqueue(page.copy(slice = page.slice - 1))
                enqueue(page.copy(slice = page.slice + 1))
            }
        }.sortedWith(compareBy<Page> { it.slice }.thenBy { it.row }.thenBy { it.column })
    }

    internal fun build(region: Region, sources: D3D12RtSpatialSources.Snapshot, targets: Set<Long>,
                       stateAt: (Long) -> Int): ByteBuffer {
        val pages = pages(region)
        val certified = sources.certifiedPages(pages, targets)
        val populated = pages.mapIndexedNotNull { index, page ->
            val states = certified[index]
            for (slice in 0 until PAGE_SIDE) for (row in 0 until PAGE_SIDE) for (column in 0 until PAGE_SIDE) {
                val sectionX = page.column * PAGE_SIDE + column
                val sectionY = page.row * PAGE_SIDE + row
                val sectionZ = page.slice * PAGE_SIDE + slice
                val cell = (slice * PAGE_SIDE + row) * PAGE_SIDE + column
                states[cell] = if (sectionX !in region.minimumX until region.minimumX + region.sizeX ||
                    sectionY !in region.minimumY until region.minimumY + region.sizeY ||
                    sectionZ !in region.minimumZ until region.minimumZ + region.sizeZ) 0
                else states[cell].takeIf { it in 1..2 } ?: stateAt(SectionPos.asLong(sectionX, sectionY, sectionZ))
            }
            if (states.any { it != 0 }) page to states else null
        }
        val payload = ByteBuffer.allocate(64 + populated.size * (16 + PAGE_BYTES)).order(ByteOrder.LITTLE_ENDIAN)
        payload.putInt(0x53505452).putInt(2)
        payload.putInt(region.minimumX).putInt(region.minimumY).putInt(region.minimumZ)
        payload.putInt(region.sizeX).putInt(region.sizeY).putInt(region.sizeZ)
        repeat(6) { payload.putInt(0) }
        payload.putInt(sources.spatialFlags).putInt(populated.size)
        populated.forEachIndexed { index, entry ->
            val page = entry.first
            payload.putInt(page.column).putInt(page.row).putInt(page.slice)
                .putInt(64 + populated.size * 16 + index * PAGE_BYTES)
        }
        for (entry in populated) for (state in entry.second) payload.putInt(state)
        payload.flip()
        return payload.asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN)
    }
}
