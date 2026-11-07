package io.github.dxegp.d3d12.render.advanced.mesh

import net.minecraft.client.renderer.DynamicGpuData
import com.mojang.renderpearl.api.buffers.GpuBuffer
import io.github.dxegp.misc.IllusionLogger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.IdentityHashMap

object D3D12MeshDrawCapture {
    internal data class SliceKey(val layer: String, val offset: Long)

    internal class Snapshot(
        val slices: Map<GpuBuffer, Map<SliceKey, D3D12SectionGeometry.LayerUpload>>,
        val sections: List<DynamicGpuData.ChunkSectionInfo>,
    )

    class Batch internal constructor(
        val offset: Long,
        val strided: IntArray,
        val drawCount: Int,
        internal val snapshot: Snapshot,
    ) {
        internal val tables = IdentityHashMap<GpuBuffer, MutableMap<SliceKey, BatchTables>>()
    }

    private val batches = IdentityHashMap<GpuBuffer, MutableMap<Long, Batch>>()
    private val failures = hashMapOf<String, Long>()
    private var extracting = IdentityHashMap<GpuBuffer, MutableMap<SliceKey, D3D12SectionGeometry.LayerUpload>>()
    @Volatile private var snapshot = Snapshot(emptyMap(), emptyList())

    @JvmStatic
    fun beginExtraction() {
        extracting = IdentityHashMap()
    }

    @JvmStatic
    fun captureSlice(meshIdentity: Any, layer: String, vertexBuffer: GpuBuffer, vertexOffset: Long) {
        val upload = D3D12SectionGeometry.meshLayer(meshIdentity, layer) ?: run {
            reportFailure("metadata", layer) { "offset=$vertexOffset" }
            return
        }
        extracting.getOrPut(vertexBuffer) { hashMapOf() }[SliceKey(layer, vertexOffset)] = upload
    }

    @JvmStatic
    fun finishExtraction(infos: List<DynamicGpuData.ChunkSectionInfo>) {
        snapshot = Snapshot(extracting, infos.toList())
    }

    @JvmStatic
    fun beginFrame() {
        batches.clear()
    }

    @JvmStatic
    fun captureBatch(buffer: GpuBuffer, offset: Long, draws: List<DynamicGpuData.IndexedDraw>) {
        if (draws.isEmpty()) return
        val packed = IntArray(draws.size * 4)
        for ([index, draw] in draws.withIndex()) {
            packed[index * 4] = draw.indexCount()
            packed[index * 4 + 1] = draw.firstIndex()
            packed[index * 4 + 2] = draw.baseVertex()
            packed[index * 4 + 3] = draw.baseInstance()
        }
        batches.getOrPut(buffer) { hashMapOf() }[offset] = Batch(offset, packed, draws.size, snapshot)
    }

    fun batch(buffer: GpuBuffer, offset: Long): Batch? = batches[buffer]?.get(offset)

    class BatchTables(val sections: ByteArray, val meshlets: ByteArray, val sectionCount: Int,
                      val meshletCount: Int)

    private inline fun reportFailure(reason: String, layer: String, detail: () -> String) {
        val key = "$layer:$reason"
        val count = (failures[key] ?: 0L) + 1L
        failures[key] = count
        if (count <= 4L || count and (count - 1L) == 0L) {
            IllusionLogger.warnMessage("mesh tables: $reason ($layer, count=$count)")
            IllusionLogger.warnMessage("mesh tables: ${detail()}")
        }
    }

    fun buildTables(batch: Batch, layer: String, vertexBuffer: GpuBuffer, vertexSliceOffset: Long): BatchTables? {
        val tableKey = SliceKey(layer, vertexSliceOffset)
        batch.tables[vertexBuffer]?.get(tableKey)?.let { return it }
        val snapshot = batch.snapshot
        val slices = snapshot.slices[vertexBuffer] ?: run {
            reportFailure("buffer", layer) { "buffers=${snapshot.slices.size}, draws=${batch.drawCount}" }
            return null
        }
        val perSection = arrayOfNulls<D3D12SectionGeometry.LayerUpload>(batch.drawCount)
        var meshletTotal = 0
        for (drawIndex in 0 until batch.drawCount) {
            val baseInstance = batch.strided[drawIndex * 4 + 3]
            val baseVertex = batch.strided[drawIndex * 4 + 2]
            if (baseInstance !in snapshot.sections.indices || baseVertex < 0 || vertexSliceOffset < 0L) {
                reportFailure("draw", layer) { "instance=$baseInstance/${snapshot.sections.size}, vertex=$baseVertex" }
                return null
            }
            val vertexOffset = vertexSliceOffset + baseVertex.toLong() * D3D12MeshletBuilder.VERTEX_STRIDE
            val upload = slices[SliceKey(layer, vertexOffset)] ?: run {
                reportFailure("slice", layer) { "draw=$drawIndex, offset=$vertexOffset, slices=${slices.size}" }
                return null
            }
            if (upload.meshlets.isEmpty() || upload.indexCount != batch.strided[drawIndex * 4]) {
                reportFailure("mesh", layer) {
                    "indices=${upload.indexCount}/${batch.strided[drawIndex * 4]}, meshlets=${upload.meshlets.size}"
                }
                return null
            }
            perSection[drawIndex] = upload
            meshletTotal += upload.meshlets.size
        }

        val sections = ByteBuffer.allocate(batch.drawCount * 32).order(ByteOrder.LITTLE_ENDIAN)
        val meshlets = ByteBuffer.allocate(meshletTotal * 32).order(ByteOrder.LITTLE_ENDIAN)
        var meshletBase = 0
        for (drawIndex in 0 until batch.drawCount) {
            val baseInstance = batch.strided[drawIndex * 4 + 3]
            val baseVertex = batch.strided[drawIndex * 4 + 2]
            val section = snapshot.sections[baseInstance]
            val upload = perSection[drawIndex] ?: return null
            sections.putInt(baseVertex)
            sections.putInt(baseVertex / D3D12MeshletBuilder.VERTICES_PER_QUAD)
            sections.putInt(meshletBase)
            sections.putInt(upload.meshlets.size)
            sections.putInt(section.x())
            sections.putInt(section.y())
            sections.putInt(section.z())
            sections.putFloat(section.visibility())
            upload.appendMeshlets(meshlets, drawIndex)
            meshletBase += upload.meshlets.size
        }
        return BatchTables(sections.array(), meshlets.array(), batch.drawCount, meshletTotal).also {
            batch.tables.getOrPut(vertexBuffer) { hashMapOf() }[tableKey] = it
        }
    }

}
