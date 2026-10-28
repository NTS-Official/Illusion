package dev.nts.expdxr.d3d12.render.advanced.mesh

import net.minecraft.client.renderer.DynamicGpuData
import net.minecraft.core.SectionPos
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap

/**
 * 捕获 vanilla 每帧的 MDI 段列表（参数缓冲在 UPLOAD 堆、CPU 读不回，只能在这里记）。
 * 键用 `GpuBufferSlice.offset`，绘制时按同一 offset 取回批次。见 docs/mesh&vrs/README.md §2.3。
 */
object D3D12MeshDrawCapture {

    /** 一批 MDI：`IntArray(drawCount * 4)` = 每段 (indexCount, firstIndex, baseVertex, baseInstance)。 */
    class Batch(val offset: Long, val strided: IntArray, val drawCount: Int)

    private val batches = ConcurrentHashMap<Long, Batch>()

    /** 段位置（`SectionPos.asLong`），按 baseInstance 索引。 */
    @Volatile private var sectionKeys: LongArray = LongArray(0)
    @Volatile private var sectionVisibility: FloatArray = FloatArray(0)

    /** 方块坐标（写进 SectionDesc 的 `chunkX/Y/Z`，着色器里再减 `CameraBlockPos`）。 */
    @Volatile private var sectionBlockX: IntArray = IntArray(0)
    @Volatile private var sectionBlockY: IntArray = IntArray(0)
    @Volatile private var sectionBlockZ: IntArray = IntArray(0)

    /** 每帧清空：一次 `writeChunkSectionCommands` 写全部图层，不能按旧 offset 做过期淘汰。 */
    @JvmStatic
    fun beginFrame() {
        batches.clear()
    }

    @JvmStatic
    fun captureBatch(offset: Long, draws: List<DynamicGpuData.IndexedDraw>) {
        if (draws.isEmpty()) return
        val packed = IntArray(draws.size * 4)
        for (i in draws.indices) {
            val d = draws[i]
            packed[i * 4] = d.indexCount()
            packed[i * 4 + 1] = d.firstIndex()
            packed[i * 4 + 2] = d.baseVertex()
            packed[i * 4 + 3] = d.baseInstance()
        }
        batches[offset] = Batch(offset, packed, draws.size)
    }

    @JvmStatic
    fun captureSections(infos: List<DynamicGpuData.ChunkSectionInfo>) {
        val keys = LongArray(infos.size)
        val visibility = FloatArray(infos.size)
        val bx = IntArray(infos.size)
        val by = IntArray(infos.size)
        val bz = IntArray(infos.size)
        for (i in infos.indices) {
            val s = infos[i]
            // x/y/z 是**方块坐标**（写进 slot1 的 ChunkPosition），注册表键却是 SectionPos.asLong（段坐标）：
            // 拿方块坐标当键实测 369 段只命中 1 段。
            keys[i] = SectionPos.asLong(
                SectionPos.blockToSectionCoord(s.x()),
                SectionPos.blockToSectionCoord(s.y()),
                SectionPos.blockToSectionCoord(s.z()),
            )
            visibility[i] = s.visibility()
            bx[i] = s.x()
            by[i] = s.y()
            bz[i] = s.z()
        }
        sectionKeys = keys
        sectionVisibility = visibility
        sectionBlockX = bx
        sectionBlockY = by
        sectionBlockZ = bz
    }

    fun batch(offset: Long): Batch? = batches[offset]

    /** 一批 MDI 打完包的两张表（32 B/条，直接上传成 t15/t16 的 RAW SRV）。 */
    class BatchTables(val sections: ByteArray, val meshlets: ByteArray, val sectionCount: Int,
                      val meshletCount: Int)

    /**
     * 打包段表 + meshlet 表（32 B/条）；任一段缺 meshlet / 缺该层即整批返回 null —— 地形无 IMR 回退，
     * 部分派发只会画出缺块的画面。`baseVertex` 相对 `vertexSliceOffset`，所以 vertexBase 直接写 `baseVertex`。
     */
    fun buildTables(batch: Batch, layer: String, vertexSliceOffset: Long): BatchTables? {
        val blocksX = sectionBlockX
        val blocksY = sectionBlockY
        val blocksZ = sectionBlockZ
        val visibilities = sectionVisibility
        if (blocksX.size < batch.drawCount) return null
        val keys = sectionKeys

        val perSection = arrayOfNulls<List<D3D12MeshletBuilder.Meshlet>>(batch.drawCount)
        var meshletTotal = 0
        for (i in 0 until batch.drawCount) {
            val baseInstance = batch.strided[i * 4 + 3]
            if (baseInstance < 0 || baseInstance >= keys.size) return null
            val geometry = D3D12SectionGeometry.find(keys[baseInstance]) ?: return null
            val upload = geometry.layers[layer] ?: return null
            if (upload.meshlets.isEmpty()) return null
            perSection[i] = upload.meshlets
            meshletTotal += upload.meshlets.size
        }

        val sections = ByteBuffer.allocate(batch.drawCount * 32).order(ByteOrder.LITTLE_ENDIAN)
        val meshlets = ByteBuffer.allocate(meshletTotal * 32).order(ByteOrder.LITTLE_ENDIAN)
        var meshletBase = 0
        for (i in 0 until batch.drawCount) {
            val baseInstance = batch.strided[i * 4 + 3]
            val baseVertex = batch.strided[i * 4 + 2]
            val list = perSection[i] ?: return null
            sections.putInt(baseVertex)          // vertexBase：相对绑定的顶点切片
            sections.putInt(baseVertex / 4)      // quadBase（调试）
            sections.putInt(meshletBase)
            sections.putInt(list.size)
            sections.putInt(blocksX[baseInstance])
            sections.putInt(blocksY[baseInstance])
            sections.putInt(blocksZ[baseInstance])
            sections.putFloat(visibilities[baseInstance])
            for (m in list) {
                m.pack(meshlets, i, m.firstQuad * D3D12MeshletBuilder.VERTICES_PER_QUAD)
            }
            meshletBase += list.size
        }
        return BatchTables(sections.array(), meshlets.array(), batch.drawCount, meshletTotal)
    }

    /** `baseInstance` → 该段的 `SectionPos.asLong`；越界返回 0。 */
    fun sectionKey(baseInstance: Int): Long {
        val keys = sectionKeys
        return if (baseInstance in keys.indices) keys[baseInstance] else 0L
    }
}
