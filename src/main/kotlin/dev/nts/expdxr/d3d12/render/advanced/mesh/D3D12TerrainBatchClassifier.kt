package dev.nts.expdxr.d3d12.render.advanced.mesh

import com.mojang.blaze3d.buffers.GpuBuffer
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.IndexType
import com.mojang.blaze3d.systems.RenderPass
import org.slf4j.LoggerFactory

/**
 * P126（mesh Step 3 第一半：「整批预检与 mesh 分流」的判定基础）：
 * 在 `drawMultipleIndexed` 的 bundle 路径前，把一个批次（同一条管线的一串 draw）的
 * 规模与结构摊平成可判定的 [BatchInfo]，并给出**先只记录、不改变行为**的分流结论。
 *
 * <p>为什么先做这一步：地形批次能否被 mesh 接管，取决于三件事是否同时成立 ——
 * （1）管线是地形管线（`minecraft:pipeline/terrain_*`）；
 * （2）索引缓冲是**共享的默认索引缓冲**（那么每个 draw 的 `firstIndex/indexCount` 就是一段
 * 可直接喂给 mesh shader 的范围，不需要重建索引）；
 * （3）批次足够大（draw 数与索引总数），否则分发开销盖过收益。
 * 这三条都只能从真实帧里量出来，所以先量。
 */
object D3D12TerrainBatchClassifier {
    private val LOGGER = LoggerFactory.getLogger("dxr_mc/mesh")
    private const val LOG_LIMIT = 24

    /** 一个批次的摊平结果。 */
    data class BatchInfo(
        val location: String,
        val draws: Int,
        val indexTotal: Long,
        val maxIndexCount: Int,

        val sharedIndexBuffer: Boolean,
        val indexType: String,
    )

    /** 分流结论：目前只记录，Step 3 后半才会真正改走 mesh。 */
    enum class Verdict {
        /** 非地形管线，原样走 IMR。 */
        NOT_TERRAIN,
        /** 地形管线但本批不具备接管条件（索引不共享 / 太小），走 IMR。 */
        TERRAIN_IMR,
        /** 地形管线且具备接管条件（Step 3 后半接上 mesh 派发）。 */
        TERRAIN_MESH_CANDIDATE,
    }

    private var logged: Int = 0
    private val seenLocations = HashSet<String>(4)
    private var maxDrawsSeen: Int = 0
    private var maxIndicesSeen: Long = 0
    private var terrainBatches: Long = 0
    private var candidateBatches: Long = 0
    private var candidateDraws: Long = 0
    private var candidateIndices: Long = 0

    fun isTerrain(location: String): Boolean = location.contains("terrain")

    fun classify(info: BatchInfo): Verdict {
        if (!isTerrain(info.location)) {
            return Verdict.NOT_TERRAIN
        }
        if (!info.sharedIndexBuffer || info.indexTotal <= 0L) {
            return Verdict.TERRAIN_IMR
        }
        // 阈值按"分发一次至少省下 N 个 draw"取；先用 64 个 draw 作为观测量，不做硬开关。
        return if (info.draws >= 64) Verdict.TERRAIN_MESH_CANDIDATE else Verdict.TERRAIN_IMR
    }

    /** 记录一个地形批次；分流结论目前只进日志与计数，不改变绘制路径。 */
    fun note(info: BatchInfo, verdict: Verdict) {
        if (verdict == Verdict.NOT_TERRAIN) {
            return
        }
        terrainBatches++
        if (verdict == Verdict.TERRAIN_MESH_CANDIDATE) {
            candidateBatches++
            candidateDraws += info.draws
            candidateIndices += info.indexTotal
        }
        if (info.draws > maxDrawsSeen) maxDrawsSeen = info.draws
        if (info.indexTotal > maxIndicesSeen) maxIndicesSeen = info.indexTotal
        // 每条地形管线只打第一条，另加"每 256 个批次"的规模摘要 —— 用来量批次的真实粒度。
        if (seenLocations.add(info.location) || terrainBatches % 256L == 0L) {
            LOGGER.info(
                "[dxrmc] mesh precheck: {} draws={} indices={} maxIdx={} sharedIB={} idxType={} -> {} | {}",
                info.location, info.draws, info.indexTotal, info.maxIndexCount,
                info.sharedIndexBuffer, info.indexType, verdict, summary(),
            )
        }
    }

    fun summary(): String =
        "mesh precheck: terrainBatches=$terrainBatches candidates=$candidateBatches maxDraws=$maxDrawsSeen maxIndices=$maxIndicesSeen " +
            "candidateDraws=$candidateDraws candidateIndices=$candidateIndices"

    /**
     * 从 vanilla 的 draw 列表摊平出 [BatchInfo]。`draws` 已由调用方 materialize，
     * 因此这里只做一次线性扫描，不分配中间列表。
     */
    fun <T : Any> flatten(pipeline: RenderPipeline, draws: List<RenderPass.Draw<T>>,
                          defaultIndexBuffer: GpuBuffer?, defaultIndexType: IndexType?): BatchInfo {
        var indexTotal = 0L
        var maxIndex = 0
        var shared = true
        var indexType = defaultIndexType?.toString() ?: "none"
        for (draw in draws) {
            val count = draw.indexCount()
            indexTotal += count
            if (count > maxIndex) maxIndex = count
            if (draw.indexBuffer() != null) shared = false
            if (draw.indexType() != null) indexType = draw.indexType().toString()
        }
        return BatchInfo(
            location = pipeline.location.toString(),
            draws = draws.size,
            indexTotal = indexTotal,
            maxIndexCount = maxIndex,
            sharedIndexBuffer = shared && defaultIndexBuffer != null,
            indexType = indexType,
        )
    }
}