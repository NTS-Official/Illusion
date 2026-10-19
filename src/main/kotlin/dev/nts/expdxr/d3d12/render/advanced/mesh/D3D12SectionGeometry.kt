package dev.nts.expdxr.d3d12.render.advanced.mesh

import net.minecraft.core.SectionPos
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
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
    ) {
        val triangles: Int get() = indexCount / 3
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

    fun sectionCount(): Int = sections.size

    fun epoch(): Int = epoch

    fun find(sectionNode: Long): SectionGeometry? = sections[sectionNode]

    /**
     * 记录一次上传。`levelIdentity` 变化（切世界/重连）时清空注册表并推进 epoch。
     * 由渲染线程与 worker 线程共同调用，因此内部只用并发容器 + 幂等的清空判断。
     */
    @JvmStatic
    fun captureUpload(sectionNode: Long, layer: String, indexCount: Int, vertexBytes: Int,
                      indexBytes: Int, levelIdentity: Int) {
        val started = System.nanoTime()
        if (levelIdentity != this.levelIdentity) {
            sections.clear()
            this.levelIdentity = levelIdentity
            epoch++
        }
        val upload = LayerUpload(layer, indexCount, vertexBytes, indexBytes)
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
            LOGGER.warn("[dxrmc] section upload #{} at ({},{},{}) layer={} index={} vBytes={} iBytes={} | {}",
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
}