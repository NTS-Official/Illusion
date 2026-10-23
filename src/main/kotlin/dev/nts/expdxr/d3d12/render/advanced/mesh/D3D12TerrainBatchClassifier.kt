package dev.nts.expdxr.d3d12.render.advanced.mesh

import com.mojang.renderpearl.api.buffers.GpuBuffer
import com.mojang.renderpearl.api.pipeline.RenderPipeline
import com.mojang.renderpearl.api.pipeline.IndexType
import com.mojang.renderpearl.api.commands.RenderPass
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

    /**
     * P133（Step 3 后半第一步：段表取证）：一个 draw 对应的 mesh 段。
     * `firstIndex/indexCount/baseVertex` 正是 MS 里 `cbuffer SegmentTable` 的三个字段；
     * `slot` 是顶点缓冲槽位（槽位不同意味着这一批不能只绑一个 VB）。
     */
    data class Segment(val firstIndex: Int, val indexCount: Int, val baseVertex: Int, val slot: Int)

    /** 整批的段表候选：一次性 DispatchMesh(groups = [count]) 的输入。 */
    data class SegmentPlan(
        val count: Int,
        val slots: Int,
        val vertexBuffers: Int,
        val indexBuffers: Int,
        val uploaders: Int,
        val indexCountTotal: Long,
        val first: List<Segment>,
    )

    private var logged: Int = 0
    private val seenLocations = HashSet<String>(4)
    private var maxDrawsSeen: Int = 0
    private var maxIndicesSeen: Long = 0
    private var terrainBatches: Long = 0
    private var candidateBatches: Long = 0
    private var candidateDraws: Long = 0
    private var candidateIndices: Long = 0

    private var plansBuilt: Long = 0
    private var planSegments: Long = 0
    private var planLogged: Int = 0

    /** 段表演练的尝试次数（由 [shouldPlanSegments] 自增，与 [plansBuilt] 区分：那个只数真建了的）。 */
    private var planAttempts: Long = 0

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
     * 段表有没有必要真的建出来（P141 续八）。
     *
     * <p>[segments] 每次要为整批 draw 新建 4 个 `HashSet` 加一个 `Segment` 对象，而
     * [noteSegments] 只在前 2 次和每 512 次打一行日志 —— 原先每帧白建 2000 多个对象。
     * 走到这里就记一次"尝试"，与 [noteSegments] 的打印条件保持一致。
     */
    fun shouldPlanSegments(): Boolean {
        planAttempts++
        return planLogged < 2L || planAttempts % 512L == 0L
    }

    /**
     * 从一个批次的 draw 列表摊出段表（只读，不分配 GPU 资源）。
     * 只在 [Verdict.TERRAIN_MESH_CANDIDATE] 且 [shouldPlanSegments] 命中时调用。
     */
    fun <T : Any> segments(draws: List<RenderPass.Draw<T>>): SegmentPlan {
        val slots = HashSet<Int>(4)
        // P133 续：单次 DispatchMesh 要求整批共用一个 VB/IB —— 这里把"整批到底是几个 buffer"
        // 量出来（identityHashCode 即可，不需要真的持有引用）。
        val vertexBuffers = HashSet<Int>(4)
        val indexBuffers = HashSet<Int>(4)
        val uploaders = HashSet<Int>(4)
        val list = ArrayList<Segment>(draws.size)
        var total = 0L
        for (draw in draws) {
            slots.add(draw.slot())
            vertexBuffers.add(System.identityHashCode(draw.vertexBuffer()))
            indexBuffers.add(System.identityHashCode(draw.indexBuffer()))
            uploaders.add(System.identityHashCode(draw.uniformUploaderConsumer()))
            total += draw.indexCount()
            list.add(Segment(draw.firstIndex(), draw.indexCount(), draw.baseVertex(), draw.slot()))
        }
        plansBuilt++
        planSegments += list.size
        return SegmentPlan(list.size, slots.size, vertexBuffers.size, indexBuffers.size,
            uploaders.size, total, list)
    }

    /** 记录段表规模：前 2 次与每 512 次打一行，附带前 3 段的具体数值。 */
    fun noteSegments(location: String, plan: SegmentPlan) {
        if (planLogged < 2 || plansBuilt % 512L == 0L) {
            planLogged++
            val head = plan.first.take(3).joinToString(", ") {
                "(fi=${it.firstIndex},n=${it.indexCount},bv=${it.baseVertex},slot=${it.slot})"
            }
            LOGGER.info(
                "[dxrmc] P133 segment plan: {} segments={} slots={} vb#={} ib#={} uploaders={} indices={} first=[{}] | plans={} segmentsTotal={}",
                location, plan.count, plan.slots, plan.vertexBuffers, plan.indexBuffers, plan.uploaders,
                plan.indexCountTotal, head, plansBuilt, planSegments,
            )
        }
    }

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