package dev.nts.expdxr.d3d12.render.advanced.mesh

import net.minecraft.client.renderer.DynamicGpuData
import net.minecraft.core.SectionPos
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap

/**
 * P217（mesh 重构 Stage 2 的前置）：**捕获 vanilla 每帧的 MDI 段列表**。
 *
 * <p>为什么能拿到：26.3 的 `DynamicGpuData` 把 MDI 的参数与逐段信息都做成**公开** record：
 * - `IndexedDraw(indexCount, instanceCount, firstIndex, baseVertex, baseInstance)` ——
 *   正是 `D3D12_INDIRECT_ARGUMENT_TYPE_DRAW_INDEXED` 的 20 B 内容；
 * - `ChunkSectionInfo(x, y, z, visibility)` —— slot 1（`CHUNK_DATA_INSTANCED`，16 B/段）的内容。
 *
 * <p>`writeChunkSectionCommands(List<List<IndexedDraw>>)` 返回的 `GpuBufferSlice[]` 就是
 * 每个 layer 的间接参数缓冲；这个 slice 会原样成为 `ChunkSectionsToRender$GpuMultiDrawIndexedIndirect`
 * 的 `indirectCommandBuffer`，最终由 `D3D12RenderPassBackend.drawIndexedIndirect` 收到 ——
 * 所以用 **slice.offset** 做键就能在绘制时精确取回"这一批到底是哪几段"。
 *
 * <p>参数缓冲本身在 UPLOAD 堆、CPU 读不到（P194 实测 `mapBuffer: buffer lacks MAP_READ`），
 * 这条捕获路径是绕过它的唯一干净办法。
 */
object D3D12MeshDrawCapture {

    /** 一批 MDI：`IntArray(drawCount * 4)` = 每段 (indexCount, firstIndex, baseVertex, baseInstance)。 */
    class Batch(val offset: Long, val strided: IntArray, val drawCount: Int)

    private val batches = ConcurrentHashMap<Long, Batch>()

    /** 最近一次 `writeChunkSectionsInstanced` 的段位置（`SectionPos.asLong`），按 baseInstance 索引。 */
    @Volatile private var sectionKeys: LongArray = LongArray(0)
    @Volatile private var sectionVisibility: FloatArray = FloatArray(0)

    /** 方块坐标（写进 SectionDesc 的 `chunkX/Y/Z`，着色器里再减 `CameraBlockPos`）。 */
    @Volatile private var sectionBlockX: IntArray = IntArray(0)
    @Volatile private var sectionBlockY: IntArray = IntArray(0)
    @Volatile private var sectionBlockZ: IntArray = IntArray(0)

    /**
     * P217：每帧写段表命令前清空上一帧的批次。
     *
     * <p>以前是"超过 64 条只留最新 offset"，那条启发式会把同一帧里**其它图层**的批次删掉
     * （实测进世界头几帧 `no captured MDI list for offset 0/2920/4620` → 地形没画）。
     * 现在按帧清空：`writeChunkSectionCommands` 一次写全部图层，清一次正好。
     */
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
            // `ChunkSectionInfo.x/y/z` 是**方块坐标**（写进 slot1 的 `ChunkPosition`，着色器里
            // 还要减 `CameraBlockPos`），而上传注册表的键是 `SectionPos.asLong`（段坐标）。
            // 直接拿方块坐标当键，实测 369 段只命中 1 段。
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
     * 把这一批 MDI 段打包成 **段表 + meshlet 表**（P217 Stage 2 的派发表）。
     *
     * <p>缺任何一段的 meshlet（还没上传完 / 不是这个 layer）就整批返回 null。地形已无 IMR
     * 回退，调用方会把这一批跳过并记一条 ERROR —— 部分派发会让画面缺块，不能做。
     *
     * <p>`vertexSliceOffset` 是这批段绑定的顶点切片起点；`baseVertex` 是相对该切片的顶点号，
     * 所以 SectionDesc.vertexBase 直接写 `baseVertex`（native 侧的 SRV 也从同一偏移起绑）。
     *
     * @param layer 该批对应的图层名（SOLID / CUTOUT / TRANSLUCENT），与上传注册表的 key 一致。
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
