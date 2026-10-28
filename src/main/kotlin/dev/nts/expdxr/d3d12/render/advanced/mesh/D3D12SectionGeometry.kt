package dev.nts.expdxr.d3d12.render.advanced.mesh

import net.minecraft.core.SectionPos
import dev.nts.expdxr.misc.settings.D3D12Settings
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicIntegerArray
import java.util.concurrent.atomic.AtomicLong

/**
 * 段几何注册表：段上传时采集 CPU 侧元信息，不缓存 GPU 句柄（Uber 缓冲扩容/整理会让句柄失效）。
 * `levelIdentity` 变化（切世界/重连）时整体清空并递增 epoch。见 docs/mesh&vrs/README.md §2.1。
 */
object D3D12SectionGeometry {
    private val LOGGER = LoggerFactory.getLogger("dxr_mc/mesh")

    /** 单层（SOLID/CUTOUT/TRANSLUCENT）的上传规模。 */
    data class LayerUpload(
        val layer: String,
        val indexCount: Int,
        val vertexBytes: Int,
        val indexBytes: Int,
        /** 该段的 meshlet 表（32 B/条）；`[mesh] terrain` 打开时每次上传都切，否则只做 shadow 统计。 */
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

    /** 采集计数与成本。 */
    private val uploads = AtomicLong()
    private val capturedTriangles = AtomicLong()
    private val emptyUploads = AtomicLong()
    private val vertexBytesTotal = AtomicLong()
    private val indexBytesTotal = AtomicLong()
    private val captureNanosTotal = AtomicLong()

    /** shadow 统计只切前 [MESHLET_SHADOW_UPLOADS] 次上传，默认路径零成本。 */
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
     * 记录一次上传；`levelIdentity` 变化时清空注册表并推进 epoch。渲染线程与 worker 线程都会调用。
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
        // 切分是旁路数据：`[mesh] terrain` 打开时每次上传都切（派发要全量段表），否则只做前
        // [MESHLET_SHADOW_UPLOADS] 次统计。失败 / 缓冲为空都静默跳过 —— 绝不能影响上传本身。
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
     * shadow 统计摘要：`pure` = 单方向 meshlet 占比，`dirs` = 逐 Quad 朝向分布（+X…-Z, non-axis）。
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