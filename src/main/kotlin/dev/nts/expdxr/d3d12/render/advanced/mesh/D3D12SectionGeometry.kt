package dev.nts.expdxr.d3d12.render.advanced.mesh

import net.minecraft.core.SectionPos
import dev.nts.expdxr.misc.D3D12Settings
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicIntegerArray
import java.util.concurrent.atomic.AtomicLong

/**
 * P125（mesh 引入 Step 2）：**section 几何注册表**（上传时刻采集）。
 *
 * <p>采集点是 `SectionRenderDispatcher$RenderSection.addSectionBuffersToUberBuffer` 的 HEAD
 * （见 `SectionUploadCaptureMixin`）。相比 `SectionCompiler.compile` 的 RETURN，这里的好处是：
 * 该方法是 section 数据进入 GPU 前的必经之路（实测 `compile` 的回调在本机一次都不触发，
 * 而 `CompileTask.doTask` 的探针每次都打）；同时能拿到 section 节点、层、`CompiledSectionMesh`
 * 的索引数与顶点/索引缓冲字节数。
 *
 * <p>**不缓存 GPU 句柄**：`RenderSectionBufferSlice` 里的 `GpuBuffer` + offset 会随
 * UberGpuBuffer 扩容/整理变化（计划里明确禁止长期持有失效句柄）。这里只保留 CPU 侧元信息，
 * 绘制时再按 (section, layer) 现取 slice。
 */
object D3D12SectionGeometry {
    private val LOGGER = LoggerFactory.getLogger("dxr_mc/mesh")

    /** 单层（SOLID/CUTOUT/TRANSLUCENT）的上传规模。 */
    data class LayerUpload(
        val layer: String,
        val indexCount: Int,
        val vertexBytes: Int,
        val indexBytes: Int,
        /**
         * P217：该段的 meshlet 表（方案 §二，每条 32 B）。
         *
         * <p>只有 `[mesh] terrain = true` 时才真正切分（默认为空表，零成本）；开启后
         * **每次上传都切**，因为 Stage 2 的派发需要全部可见段的 meshlet，而不是采样。
         * 未开启时只在前 [MESHLET_SHADOW_UPLOADS] 次做 shadow 统计。
         */
        val meshlets: List<D3D12MeshletBuilder.Meshlet> = emptyList(),
    ) {
        val triangles: Int get() = indexCount / 3
        val meshletBytes: Int get() = meshlets.size * D3D12MeshletBuilder.MESHLET_DESC_BYTES
    }

    /** 一个 section 的几何快照；`generation` 每次重建递增（判断"这帧看到的是不是同一版本"）。 */
    data class SectionGeometry(
        val key: Long,
        val epoch: Int,
        val generation: Long,
        val capturedAtNanos: Long,
        val layers: Map<String, LayerUpload>,
    ) {
        val triangles: Int get() = layers.values.sumOf { it.triangles }
    }

    private val sections = ConcurrentHashMap<Long, SectionGeometry>()
    private var epoch: Int = 0
    private var generation: Long = 0
    private var levelIdentity: Int = 0

    /** 采集计数（计划要求：真实 section/三角形/更新频率与采集成本计数）。 */
    private val uploads = AtomicLong()
    private val capturedTriangles = AtomicLong()
    private val emptyUploads = AtomicLong()
    private val vertexBytesTotal = AtomicLong()
    private val indexBytesTotal = AtomicLong()
    private val captureNanosTotal = AtomicLong()

    /**
     * P217：meshlet 切分（shadow）。只对**前 [MESHLET_SHADOW_UPLOADS] 次**上传做切分 ——
     * 目的是拿到真实规模（每段 meshlet 数、方向分布、单方向纯度），不是给每次上传加成本。
     * 统计结果用 [meshletSummary] 打一行，之后进分桶/上传改造时这段会被真实产物取代。
     */
    private const val MESHLET_SHADOW_UPLOADS = 64
    private val meshletUploads = AtomicLong()
    private val meshletCount = AtomicLong()
    private val meshletPure = AtomicLong()
    private val meshletQuads = AtomicLong()
    private val meshletDirections = AtomicIntegerArray(7)

    fun sectionCount(): Int = sections.size

    fun epoch(): Int = epoch

    fun find(sectionNode: Long): SectionGeometry? = sections[sectionNode]

    /**
     * 记录一次上传。`levelIdentity` 变化（切世界/重连）时清空注册表并推进 epoch。
     * 由渲染线程与 worker 线程共同调用，因此内部只用并发容器 + 幂等的清空判断。
     */
    @JvmStatic
    fun captureUpload(sectionNode: Long, layer: String, indexCount: Int, vertexBytes: Int,
                      indexBytes: Int, levelIdentity: Int, vertex: ByteBuffer?) {
        val started = System.nanoTime()
        if (levelIdentity != this.levelIdentity) {
            sections.clear()
            this.levelIdentity = levelIdentity
            epoch++
        }
        // P217：`[mesh] terrain` 打开时**每次上传都切** meshlet（Stage 2 的派发需要全量段表）；
        // 未打开时只做前 [MESHLET_SHADOW_UPLOADS] 次 shadow 统计，默认路径成本为零。
        // 切分失败 / 缓冲为空都静默跳过 —— 这段绝不能影响上传本身。
        val shadowLimit = if (D3D12Settings.meshTerrain) Int.MAX_VALUE else MESHLET_SHADOW_UPLOADS
        var meshlets: List<D3D12MeshletBuilder.Meshlet> = emptyList()
        if (vertex != null && indexCount > 0 && meshletUploads.get() < shadowLimit) {
            val built = try {
                D3D12MeshletBuilder.build(vertex)
            } catch (ignored: Throwable) {
                null
            }
            if (built != null) {
                meshlets = built.meshlets
                val n = meshletUploads.incrementAndGet()
                meshletQuads.addAndGet(built.quads.toLong())
                meshletCount.addAndGet(built.meshletCount.toLong())
                meshletPure.addAndGet(built.pureMeshlets.toLong())
                for (i in 0 until 7) {
                    meshletDirections.addAndGet(i, built.directionCounts[i])
                }
                if (!D3D12Settings.meshTerrain &&
                    (n == MESHLET_SHADOW_UPLOADS.toLong() || n % 16L == 0L)) {
                    LOGGER.warn("meshlet shadow: {}", meshletSummary())
                }
            }
        }
        val upload = LayerUpload(layer, indexCount, vertexBytes, indexBytes, meshlets)
        val existing = sections[sectionNode]
        val layers = if (existing == null) {
            LinkedHashMap<String, LayerUpload>(3)
        } else {
            LinkedHashMap(existing.layers)
        }
        layers[layer] = upload
        val geometry = SectionGeometry(
            key = sectionNode,
            epoch = epoch,
            generation = ++generation,
            capturedAtNanos = started,
            layers = layers,
        )
        sections[sectionNode] = geometry
        val total = uploads.incrementAndGet()
        if (indexCount <= 0) {
            emptyUploads.incrementAndGet()
        } else {
            capturedTriangles.addAndGet(upload.triangles.toLong())
        }
        vertexBytesTotal.addAndGet(vertexBytes.toLong())
        indexBytesTotal.addAndGet(indexBytes.toLong())
        captureNanosTotal.addAndGet(System.nanoTime() - started)
        if (total <= 3L || total % 1024L == 0L) {
            val pos = SectionPos.of(sectionNode)
            LOGGER.warn("section upload #{} at ({},{},{}) layer={} index={} vBytes={} iBytes={} | {}",
                total, pos.x(), pos.y(), pos.z(), layer, indexCount, vertexBytes, indexBytes, summary())
        }
    }

    /** 采集成本与规模的一行摘要（日志用；调用方自己控制频率）。 */
    fun summary(): String {
        val count = uploads.get()
        val avgUs = if (count == 0L) 0.0 else captureNanosTotal.get() / 1000.0 / count
        return "section geometry: sections=${sections.size} uploads=$count " +
            "triangles=${capturedTriangles.get()} empty=${emptyUploads.get()} " +
            "vMB=${vertexBytesTotal.get() / (1024 * 1024)} iMB=${indexBytesTotal.get() / (1024 * 1024)} " +
            "avg=${"%.1f".format(avgUs)}us epoch=$epoch gen=$generation"
    }

    /**
     * P217：meshlet shadow 统计的一行摘要。
     *
     * <p>`pure` = 整段只含单一朝向（或全是非轴对齐）的 meshlet 占比 —— 方案 §三 的
     * "按朝向分桶"要把它推到接近 100%，否则 AS 的位掩码背面剔除对混合 meshlet 只能整段放行。
     * `dirs` 是逐 Quad 的朝向分布（+X,-X,+Y,-Y,+Z,-Z,non-axis）。
     */
    fun meshletSummary(): String {
        val sections = meshletUploads.get()
        val meshlets = meshletCount.get()
        val purePct = if (meshlets == 0L) 0.0 else meshletPure.get() * 100.0 / meshlets
        val perSection = if (sections == 0L) 0.0 else meshlets.toDouble() / sections
        val dirs = (0 until 7).joinToString(",") { meshletDirections.get(it).toString() }
        return "sections=$sections meshlets=$meshlets perSection=${"%.1f".format(perSection)} " +
            "quads=${meshletQuads.get()} pure=${"%.1f".format(purePct)}% dirs=[$dirs]"
    }
}