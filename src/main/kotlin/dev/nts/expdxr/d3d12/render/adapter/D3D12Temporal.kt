package dev.nts.expdxr.d3d12.render.adapter

import com.mojang.blaze3d.buffers.GpuBuffer
import com.mojang.blaze3d.buffers.GpuBufferSlice
import dev.nts.expdxr.d3d12.render.draw.D3D12GpuBuffer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import net.minecraft.client.Minecraft
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * P75：时间性渲染（TAAU）的**基础状态**：亚像素 jitter + 上一帧/历史管理 + 逐物体变换记录。
 *
 * 本轮（用户 m02898 选择「基础三件套：速度缓冲 + jitter + 上一帧/历史管理」）只铺地基，
 * **不做**最终的 TAAU resolve，也**还没有**速度附件（那需要模组自建 2 个 color target 的管线变体，
 * 因为 vanilla 的 `RenderPass.setPipeline` 硬断言 color attachment 数量必须等于管线的 color target 数量）。
 * 这里刻意只放"跨帧状态 + 数学"，不碰任何 GL/D3D12 调用，这样它可以被单测/自检直接驱动，
 * 也不影响现有渲染路径（默认 enabled=false，所有挂钩点都是惰性的）。
 *
 * 三件事与它们的**数据来源**（P75 侦察结论，MC 26.2 / NeoForge 26.2.0.90）：
 *  1. **jitter**：Halton(2,3) 亚像素偏移，按帧序号循环；值域是**像素单位** [-0.5, +0.5)。
 *     注入方式不是改着色器，而是把偏移加进**关卡投影矩阵**（`m[0][2]`/`m[1][2]`，即列主序的
 *     [8]/[9]）：`clip.x += δ * view.z`，而透视投影下 `clip.w = -view.z = 距离`，
 *     所以 `δ = -2*jitterX/width` 恰好等于"在 NDC 里平移 jitter 像素" ✓。
 *     数据来源：vanilla `ProjectionMatrixBuffer("level")` 每帧都会重新上传
 *     （`GameRenderer.java:561` 走的是**不带缓存**的 `getBuffer(Matrix4f)` 重载），
 *     因此拦截 [dev.nts.expdxr.d3d12.render.backend.D3D12CommandEncoderBackend.writeToBuffer]
 *     就能拿到每帧的新鲜字节，不会像"原地改一次"那样留下过期 jitter。
 *  2. **上一帧相机矩阵**：`onLevelProjectionUpload` 在保存本帧**未抖动**投影的同时滚动历史
 *     （`previousProjection` / `previousJitter`），供下一帧算运动矢量。必须用未抖动矩阵，
 *     否则速度场会被 jitter 污染。
 *  3. **逐物体变换**：vanilla 的 `DynamicTransforms`(160B) 与 `ChunkSection`(96B) 都由
 *     `MappableRingBuffer(usage=130)` 承载、内容由 CPU 直接写进持久映射内存，所以后端可以在
 *     `setUniform` 时 `map(read)` 读回字节（`renderPass.setUniform` / 逐 draw 的
 *     `UniformUploader.upload` 都收敛到同一个漏斗）。**按绘制序号配对**是明确的近似：
 *     只有两帧绘制顺序一致时才严格正确；实体/特征几何在提交时就把变换烘进了顶点
 *     （`ModelPart.render`），且同 RenderType 的连续几何会被合并成一次 draw
 *     （`RenderTypeFeatureRenderer`），所以绘制级没有稳定的物体 id，更准的配对需要 mixin 暴露身份。
 *     区块（terrain）另有 `ChunkPosition`（整数区块原点）可用，比绘制序号稳得多 —— 单独记录。
 */
object D3D12Temporal {
    private val LOGGER: Logger = LoggerFactory.getLogger("dxrmc-temporal")

    /** 每个 jitter 循环的采样数（2 的幂更好：与 TAAU 的 8/16 采样档位对齐）。 */
    const val DEFAULT_JITTER_SAMPLES: Int = 16

    /**
     * vanilla `ProjectionMatrixBuffer` 的 buffer label 前缀/关卡实例后缀。
     * 源码：`ProjectionMatrixBuffer.java:27` 用 `"Camera projection matrix UBO " + name` 建缓冲，
     * `GameRenderer.java:129-130` 分别是 `"level"` 与 `"3d hud"`。
     * **只对 level 注入 jitter**：3D HUD/正交投影抖了会让物品栏与手持物错位。
     */
    const val LEVEL_PROJECTION_LABEL: String = "Camera projection matrix UBO level"

    /** vanilla `GlobalSettingsUniform` 的 buffer label（`GlobalSettingsUniform.java:18`）：相机世界坐标来源。 */
    const val GLOBALS_LABEL: String = "Global Settings UBO"

    /**
     * 开关来源：`-Ddxrmc.temporal=true`（Gradle 会像 `dxrmc.noPopup` 一样把 `-Pdxrmc.temporal=true`
     * 转发成 Loom run config 的系统属性）。默认 **false**：在速度附件接线完成前不允许影响现有画面。
     */
    private const val TEMPORAL_PROPERTY: String = "dxrmc.temporal"

    /**
     * 诊断开关：`-Ddxrmc.temporal.jitter=false` 只关掉**投影 jitter**、保留逐物体矩阵与相机捕获，
     * 用来把"画面异常"在 jitter 注入与 UBO 捕获两条路径之间二分定位。默认 true。
     */
    private const val JITTER_PROPERTY: String = "dxrmc.temporal.jitter"

    /** 总开关。P75 默认关闭。 */
    @Volatile
    var enabled: Boolean = "true" == System.getProperty(TEMPORAL_PROPERTY)

    /** 是否真的把 jitter 注入投影矩阵（捕获路径不受它影响，便于 A/B 诊断）。 */
    @Volatile
    var jitterEnabled: Boolean = "false" != System.getProperty(JITTER_PROPERTY)

    /** jitter 循环长度（可被外部按画质档位调整，至少 1）。 */
    @Volatile
    var jitterSamples: Int = DEFAULT_JITTER_SAMPLES
        set(value) {
            field = if (value < 1) 1 else value
        }

    private var frameIndex: Long = 0L

    private var jitterX: Double = 0.0
    private var jitterY: Double = 0.0
    private var previousJitterX: Double = 0.0
    private var previousJitterY: Double = 0.0

    /** 本帧**未抖动**的关卡投影矩阵（列主序 16 float，与原 uniform 字节一一对应）。 */
    private var projection: FloatArray? = null

    /** 上一帧未抖动的关卡投影矩阵。 */
    private var previousProjection: FloatArray? = null

    /** 兼容保留：把"view-projection"记成单矩阵的旧入口（当前接线用的是 projection + modelView）。 */
    private var viewProj: FloatArray? = null
    private var previousViewProj: FloatArray? = null

    /** 本帧逐物体模型矩阵（`DynamicTransforms`，按绘制顺序），以及上一帧的同名快照。 */
    private var modelViews: MutableList<FloatArray> = ArrayList()

    @Volatile
    private var previousModelViews: List<FloatArray> = emptyList()

    /** 本帧区块上传（`ChunkSection`，按绘制顺序）：模型矩阵 + 区块原点。 */
    private var chunkSections: MutableList<ChunkSection> = ArrayList()

    @Volatile
    private var previousChunkSections: List<ChunkSection> = emptyList()

    /** 相机世界坐标（`CameraBlockPos + CameraOffset`，来自 `Globals` UBO）的本帧/上一帧快照。 */
    private var cameraPosition: FloatArray? = null
    private var previousCameraPosition: FloatArray? = null

    /** Halton(2) 与 Halton(3) 的前若干个值，惰性生成并缓存。 */
    private var halton2: DoubleArray = DoubleArray(0)
    private var halton3: DoubleArray = DoubleArray(0)

    /**
     * 一次区块上传的记录。
     *
     * `modelView` 对所有区块是同一个（相机视图矩阵），`x/y/z` 是 `ChunkSectionInfo.ChunkPosition`
     * （区块级渲染原点，整数），terrain.vsh 里以 `Position + (ChunkPosition - CameraBlockPos) + CameraOffset`
     * 参与世界坐标计算 —— 所以区块的重投影要同时用到区块原点与相机世界坐标。
     */
    class ChunkSection(val modelView: FloatArray, val x: Int, val y: Int, val z: Int)

    @Synchronized
    fun reset() {
        frameIndex = 0L
        jitterX = 0.0
        jitterY = 0.0
        previousJitterX = 0.0
        previousJitterY = 0.0
        projection = null
        previousProjection = null
        viewProj = null
        previousViewProj = null
        modelViews = ArrayList()
        previousModelViews = emptyList()
        chunkSections = ArrayList()
        previousChunkSections = emptyList()
        cameraPosition = null
        previousCameraPosition = null
        // P76a：速度纹理也一起释放（未启用 velocity 时是空操作）。
        D3D12Velocity.reset()
    }

    /**
     * 每帧开始时调用一次：滚动历史（本帧的 jitter/矩阵变成上一帧），并推进 jitter 序列。
     * 必须在**本帧任何 uniform 上传之前**调用（即帧首），否则本帧捕获的矩阵会被当成上一帧。
     * 生产接线里由 [onLevelProjectionUpload] 代劳（关卡投影每帧上传一次，正好在关卡绘制之前）。
     */
    @Synchronized
    fun beginFrame() {
        previousJitterX = jitterX
        previousJitterY = jitterY
        previousProjection = projection
        previousViewProj = viewProj
        previousModelViews = modelViews
        modelViews = ArrayList(previousModelViews.size + 8)
        previousChunkSections = chunkSections
        chunkSections = ArrayList(previousChunkSections.size + 8)

        val n = jitterSamples
        ensureHalton(n)
        val index = (frameIndex % n).toInt()
        // Halton 值域 (0,1) → 平移成以 0 为中心的像素偏移 [-0.5, +0.5)。
        if (enabled && jitterEnabled) {
            jitterX = halton2[index] - 0.5
            jitterY = halton3[index] - 0.5
        } else {
            jitterX = 0.0
            jitterY = 0.0
        }
        frameIndex++
        // P76a：速度附件与时间性状态同帧滚动（只在 velocity 开关打开时做任何事）。
        D3D12Velocity.beginFrame()
    }

    /**
     * 关卡投影矩阵上传时调用（每帧一次，由写入拦截点驱动）：
     * 滚动历史 → 保存**未抖动**的投影 → 返回带 jitter 的副本供上传。
     *
     * @param pristine 本帧未抖动的关卡投影（列主序 16 float）
     * @param width/height 渲染目标像素尺寸（NDC 换算用；<=0 时该轴 jitter 记 0）
     * @return 需要实际上传的 16 float 投影矩阵
     */
    @Synchronized
    fun onLevelProjectionUpload(pristine: FloatArray, width: Int, height: Int): FloatArray {
        if (pristine.size != 16) {
            return pristine
        }
        beginFrame()
        projection = pristine.copyOf()
        // 低频诊断：挂钩点是否真的在跑、逐物体捕获有没有数据（P75 验证用，每 ~5s 一行）。
        logStatus()
        if (!enabled || !jitterEnabled) {
            return pristine.copyOf()
        }
        return jitterProjection(pristine, clipJitterX(width), clipJitterY(height))
    }

    /**
     * 把 clip 空间偏移加进投影矩阵：列主序 [8] = m[0][2]、[9] = m[1][2]。
     *
     * 推导：`clip.x = ... + m[0][2] * view.z`，透视下 `clip.w = -view.z = 距离`，
     * 于是 `m[0][2] += δ` 等价于 `clip.x += -δ * clip.w`；要平移 NDC 的 `c` 个单位的
     * 一半（即 c = 2*jitter/尺寸），取 `δ = -c`。
     */
    fun jitterProjection(pristine: FloatArray, clipX: Double, clipY: Double): FloatArray {
        val out = pristine.copyOf()
        out[8] = (out[8] - clipX).toFloat()
        out[9] = (out[9] - clipY).toFloat()
        return out
    }

    /** 本帧的 jitter（像素单位，[-0.5, +0.5)）。未启用时恒为 0。 */
    @Synchronized
    fun jitter(): DoubleArray = doubleArrayOf(jitterX, jitterY)

    /**
     * 写出拦截（由 [dev.nts.expdxr.d3d12.render.backend.D3D12CommandEncoderBackend.writeToBuffer] 调用）：
     *  - **关卡投影矩阵**（label `"Camera projection matrix UBO level"`，64 B）：捕获未抖动矩阵、按帧滚动历史，
     *    返回带 jitter 的 64 字节副本；
     *  - **相机 UBO**（label `"Global Settings UBO"`，56 B）：读 `ivec3 CameraBlockPos` + `vec3 CameraOffset`
     *    还原相机世界坐标（`cameraPos = blockPos - offset`，见 `GlobalSettingsUniform.update`），返回 null。
     *
     * 其余写入一律返回 null，调用方照常上传原数据。
     *
     * 为什么必须在写出处注入/捕获：这两个缓冲 usage 都是 136（UNIFORM|COPY_DST），**不可映射**
     * （强 map 会得到 native 警告 `mcdr_dx12MapBuffer: mapBuffer: buffer lacks MAP_READ`），
     * 而 vanilla 每帧都会经 `ProjectionMatrixBuffer.writeBuffer` / `GlobalSettingsUniform.update`
     * 用 `createCommandEncoder().writeToBuffer(...)` 重写它们 —— 这是唯一能拿到逐帧新鲜字节的地方。
     *
     * 同一帧内的重复写入用时间窗过滤：距上次滚动 < 2ms 视为同一帧，只返回当前帧已有的 jitter，
     * 不再推进序列，避免历史错位。
     */
    @Synchronized
    fun interceptWrite(destination: GpuBufferSlice, data: ByteBuffer): ByteBuffer? {
        if (!enabled) {
            return null
        }
        val buffer = destination.buffer()
        if (buffer !is D3D12GpuBuffer) {
            return null
        }
        return when (buffer.label()) {
            GLOBALS_LABEL -> {
                if (data.remaining() >= 32) {
                    val blockPos = readInts(data, 0, 3)
                    val offset = readFloats(data, 16, 3)
                    if (blockPos != null && offset != null) {
                        captureCamera(blockPos[0] - offset[0], blockPos[1] - offset[1],
                            blockPos[2] - offset[2])
                    }
                }
                null
            }
            LEVEL_PROJECTION_LABEL -> interceptLevelProjectionWrite(destination, data)
            else -> null
        }
    }

    /**
     * 关卡投影矩阵的 jitter 注入（只在 [interceptWrite] 里被调用）。
     * 见类注释"为什么投影矩阵是注入点"。
     */
    private fun interceptLevelProjectionWrite(destination: GpuBufferSlice, data: ByteBuffer): ByteBuffer? {
        if (data.remaining() != 64) {
            return null
        }
        val size = renderSize() ?: return null
        val pristine = readFloats(data, 0, 16) ?: return null

        val now = System.nanoTime()
        val jittered: FloatArray = if (now - lastProjectionUploadNanos < SAME_FRAME_NANOS
            && projection != null && lastSizeWidth == size[0] && lastSizeHeight == size[1]) {
            // 同帧重复写入：不滚动历史，沿用当前 jitter（投影本体已被本帧第一次写入捕获）。
            jitterProjection(pristine, clipJitterX(size[0]), clipJitterY(size[1]))
        } else {
            lastProjectionUploadNanos = now
            lastSizeWidth = size[0]
            lastSizeHeight = size[1]
            onLevelProjectionUpload(pristine, size[0], size[1])
        }

        val out = ByteBuffer.allocateDirect(64).order(ByteOrder.LITTLE_ENDIAN)
        out.asFloatBuffer().put(jittered, 0, 16)
        return out
    }

    /**
     * 逐物体 UBO 的捕获入口（由 [dev.nts.expdxr.d3d12.render.backend.D3D12RenderPassBackend.setUniform]
     * 漏斗调用）。用 uniform 名区分（vanilla 的 `setUniform` 只有名字，没有缓冲身份）：
     *  - `"DynamicTransforms"`：byte 0 起的 mat4 = 该次绘制的模型视图矩阵 → [recordModelView]
     *  - `"ChunkSection"`：byte 0 起的 mat4 + **byte 80 起的 ivec3 = 区块原点** → [recordChunkSection]
     *
     * 相机坐标**不在这里捕获**：`"Globals"`（Global Settings UBO，usage 136 = UNIFORM|COPY_DST）
     * 不可映射，强 map 会打 native 警告 `mapBuffer: buffer lacks MAP_READ`；
     * 它每帧经 `GlobalSettingsUniform.update` 的 `writeToBuffer` 写出，改在 [interceptWrite] 里读字节。
     *
     * 只读映射（`map(offset, len, true, false)`）在 vanilla 的 `MappableRingBuffer`（usage 130）
     * 上是被允许的：`DynamicUniformStorage` 本身就是 CPU 直接写这些环。
     * 任何 map 失败/尺寸不符都**静默跳过**：时间性数据缺失只会让速度场退化，绝不能影响渲染。
     */
    fun captureUniform(name: String, value: GpuBufferSlice) {
        if (!enabled) {
            return
        }
        try {
            when (name) {
                "DynamicTransforms" -> {
                    if (value.length() < 64L) {
                        return
                    }
                    val modelView = mapFloats(value.buffer(), value.offset(), 16) ?: return
                    recordModelView(modelView)
                }
                "ChunkSection" -> {
                    if (value.length() < 96L) {
                        return
                    }
                    val modelView = mapFloats(value.buffer(), value.offset(), 16) ?: return
                    val origin = mapInts(value.buffer(), value.offset() + 80L, 3) ?: return
                    recordChunkSection(modelView, origin[0], origin[1], origin[2])
                }
            }
        } catch (e: Exception) {
            // 不可映射/已关闭的缓冲：放弃这次捕获（P75 的捕获是纯附加信息）。
        }
    }

    /** 同一帧判定的时间窗（远小于 60fps 的 16.7ms）。 */
    private const val SAME_FRAME_NANOS: Long = 2_000_000L

    /** 诊断日志的最小间隔。 */
    private const val LOG_INTERVAL_MILLIS: Long = 5_000L

    private var lastProjectionUploadNanos: Long = 0L
    private var lastSizeWidth: Int = 0
    private var lastSizeHeight: Int = 0
    private var lastLogMillis: Long = 0L

    /** 低频诊断（每 [LOG_INTERVAL_MILLIS] 一行）：P75 靠它确认挂钩点生效。 */
    private fun logStatus() {
        val now = System.currentTimeMillis()
        if (now - lastLogMillis < LOG_INTERVAL_MILLIS) {
            return
        }
        lastLogMillis = now
        LOGGER.info("[d3d12] temporal: {}", describe())
    }

    /** 渲染目标像素尺寸（窗口帧缓冲，与 MainTarget/交换链同尺寸 —— vanilla 无渲染缩放）。 */
    private fun renderSize(): IntArray? {
        return try {
            val window = Minecraft.getInstance().window
            val width = window.width
            val height = window.height
            if (width <= 0 || height <= 0) null else intArrayOf(width, height)
        } catch (t: Throwable) {
            null
        }
    }

    /** 从（可能不是 0 起点的）ByteBuffer 的 [offset] 字节处读 count 个小端 float。 */
    private fun readFloats(data: ByteBuffer, offset: Int, count: Int): FloatArray? {
        val fb = data.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        if (offset < 0 || fb.limit() - offset < count * 4) {
            return null
        }
        fb.position(offset)
        val slice = fb.slice().asFloatBuffer()
        if (slice.remaining() < count) {
            return null
        }
        val out = FloatArray(count)
        slice.get(out)
        return out
    }

    /** 从（可能不是 0 起点的）ByteBuffer 的 [offset] 字节处读 count 个小端 int。 */
    private fun readInts(data: ByteBuffer, offset: Int, count: Int): IntArray? {
        val fb = data.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        if (offset < 0 || fb.limit() - offset < count * 4) {
            return null
        }
        fb.position(offset)
        val slice = fb.slice().asIntBuffer()
        if (slice.remaining() < count) {
            return null
        }
        val out = IntArray(count)
        slice.get(out)
        return out
    }

    /**
     * map 一个缓冲切片并读 count 个小端 float。
     *
     * ⚠️ P75 实测坑：vanilla 的动态 UBO 环（`DynamicUniformStorage` 的 `MappableRingBuffer`）
     * usage = 130（`USAGE_UNIFORM|USAGE_MAP_WRITE`），**没有 MAP_READ 位**；native 的
     * `mapBuffer` 会按 usage 位严格校验并打警告
     * `[mcdr] mcdr_dx12MapBuffer: mapBuffer: buffer lacks MAP_READ`，
     * 于是每帧上万行同步 stderr I/O 把渲染线程拖死（实测日志 250k 行、`waitForRenderThreadSubmit: timeout`）。
     *
     * 因此这里改为：先自查 usage 是否含 [GpuBuffer.USAGE_MAP_WRITE]（不含就直接放弃，绝不惊动 native），
     * 再以**写**映射申请到同一块持久映射内存，然后照常读取——UPLOAD 堆本来就是 CPU 可见的，
     * 读它是合法的（`mapBuffer` 只做 usage 位校验，返回的就是 `mappedPtr + offset`）。
     * vanilla 对这些环的映射是"写完立刻 unmap"的紧作用域，我们的 map/unmap 落在其后，不会截断它的写入。
     */
    private fun mapFloats(buffer: GpuBuffer, offset: Long, count: Int): FloatArray? {
        if (!isHostVisible(buffer)) {
            return null
        }
        buffer.map(offset, (count * 4).toLong(), false, true).use { view ->
            val fb = view.data().order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            if (fb.remaining() < count) {
                return null
            }
            val out = FloatArray(count)
            fb.get(out)
            return out
        }
    }

    /** map 一个缓冲切片并读 count 个小端 int（坑同上）。 */
    private fun mapInts(buffer: GpuBuffer, offset: Long, count: Int): IntArray? {
        if (!isHostVisible(buffer)) {
            return null
        }
        buffer.map(offset, (count * 4).toLong(), false, true).use { view ->
            val ib = view.data().order(ByteOrder.LITTLE_ENDIAN).asIntBuffer()
            if (ib.remaining() < count) {
                return null
            }
            val out = IntArray(count)
            ib.get(out)
            return out
        }
    }

    /**
     * 缓冲是否可安全做 CPU 映射（不含 MAP_READ 位也能读，见 [mapFloats]）：
     * 只要求 host-visible 所需的 MAP_WRITE 位，避免 native 打 `lacks MAP_READ` 警告刷屏。
     */
    private fun isHostVisible(buffer: GpuBuffer): Boolean {
        return try {
            (buffer.usage() and GpuBuffer.USAGE_MAP_WRITE) != 0
        } catch (t: Throwable) {
            false
        }
    }

    /** 上一帧的 jitter（像素单位）。 */
    @Synchronized
    fun previousJitter(): DoubleArray = doubleArrayOf(previousJitterX, previousJitterY)

    /** 本帧 jitter 换算到 clip 空间的 X 偏移（NDC 的 [-1,1] 对应整屏宽度）。 */
    @Synchronized
    fun clipJitterX(width: Int): Double = if (width <= 0) 0.0 else 2.0 * jitterX / width

    /** 本帧 jitter 换算到 clip 空间的 Y 偏移。 */
    @Synchronized
    fun clipJitterY(height: Int): Double = if (height <= 0) 0.0 else 2.0 * jitterY / height

    /** 保存本帧未抖动的关卡投影矩阵。 */
    @Synchronized
    fun captureProjection(matrix: FloatArray) {
        if (matrix.size != 16) {
            return
        }
        projection = matrix.copyOf()
    }

    /** 本帧未抖动的投影矩阵。 */
    @Synchronized
    fun currentProjection(): FloatArray? = projection?.copyOf()

    /** 上一帧未抖动的投影矩阵；第一帧为 null。 */
    @Synchronized
    fun previousProjection(): FloatArray? = previousProjection?.copyOf()

    /** 兼容旧入口：保存本帧未抖动的 view-projection（完整 16 float 列主序）。 */
    @Synchronized
    fun captureViewProj(matrix: FloatArray) {
        if (matrix.size != 16) {
            return
        }
        viewProj = matrix.copyOf()
    }

    /** 上一帧未抖动的 view-projection；第一帧为 null。 */
    @Synchronized
    fun previousViewProj(): FloatArray? = previousViewProj?.copyOf()

    /** 本帧已捕获的 view-projection。 */
    @Synchronized
    fun currentViewProj(): FloatArray? = viewProj?.copyOf()

    /** 记录一次逐物体模型矩阵上传（`DynamicTransforms`，按绘制顺序）。 */
    @Synchronized
    fun recordModelView(matrix: FloatArray) {
        if (matrix.size != 16) {
            return
        }
        modelViews.add(matrix.copyOf())
    }

    /** 按绘制序号取上一帧的模型矩阵；越界返回 null（该物体在本帧是新出现的）。 */
    @Synchronized
    fun previousModelViewAt(index: Int): FloatArray? {
        val list = previousModelViews
        if (index < 0 || index >= list.size) {
            return null
        }
        return list[index].copyOf()
    }

    /** 本帧已记录的模型矩阵数量。 */
    @Synchronized
    fun modelViewCount(): Int = modelViews.size

    /** 上一帧记录的模型矩阵数量。 */
    @Synchronized
    fun previousModelViewCount(): Int = previousModelViews.size

    /** 记录一次区块上传（`ChunkSection`，按绘制顺序）。 */
    @Synchronized
    fun recordChunkSection(modelView: FloatArray, x: Int, y: Int, z: Int) {
        if (modelView.size != 16) {
            return
        }
        chunkSections.add(ChunkSection(modelView.copyOf(), x, y, z))
    }

    /** 按绘制序号取上一帧的区块记录；越界返回 null。 */
    @Synchronized
    fun previousChunkSectionAt(index: Int): ChunkSection? {
        val list = previousChunkSections
        if (index < 0 || index >= list.size) {
            return null
        }
        val s = list[index]
        return ChunkSection(s.modelView.copyOf(), s.x, s.y, s.z)
    }

    /** 本帧已记录的区块数量。 */
    @Synchronized
    fun chunkSectionCount(): Int = chunkSections.size

    /** 上一帧记录的区块数量。 */
    @Synchronized
    fun previousChunkSectionCount(): Int = previousChunkSections.size

    /**
     * 本帧的**相机 model-view 矩阵**（P76a 速度 pass 用）。
     *
     * 为什么能从区块/物体记录里取：vanilla 的 terrain 把同一个相机 model-view 写进每个区块的
     * `ChunkSection`（`LevelRenderer` 只压入一次 `viewRotationMatrix`），实体/特征走的
     * `DynamicTransforms` 也是 `RenderSystem.getModelViewMatrixCopy()`。所以本帧任意一条记录
     * 就是相机 model-view —— 优先取区块（terrain 在 `main` pass 里最先画），否则退回任意记录。
     *
     * @return 16 float 副本；本帧还没有任何绘制时为 null。
     */
    @Synchronized
    fun currentCameraView(): FloatArray? {
        if (chunkSections.isNotEmpty()) {
            return chunkSections[0].modelView.copyOf()
        }
        if (modelViews.isNotEmpty()) {
            return modelViews[0].copyOf()
        }
        return null
    }

    /** 上一帧的相机 model-view 矩阵；第一帧或上一帧没有绘制时为 null。 */
    @Synchronized
    fun previousCameraView(): FloatArray? {
        if (previousChunkSections.isNotEmpty()) {
            return previousChunkSections[0].modelView.copyOf()
        }
        if (previousModelViews.isNotEmpty()) {
            return previousModelViews[0].copyOf()
        }
        return null
    }

    /**
     * 记录相机世界坐标（`Globals` UBO 的 `CameraBlockPos - CameraOffset`，见 `GlobalSettingsUniform.update`）。
     * 实体/terrain 顶点是**相机相对**坐标，重投影必须补上两帧之间的相机位移。
     *
     * 历史滚动发生在这里而不是 [beginFrame]：相机 UBO 每帧写一次，但**与关卡投影写入的先后顺序
     * 不由我们决定**，所以"新值到达即滚历史"才是顺序无关的正确做法。同值重复写入（同一帧内被写两次）
     * 不滚动，避免把本帧当成上一帧。
     */
    @Synchronized
    fun captureCamera(x: Float, y: Float, z: Float) {
        val current = cameraPosition
        if (current != null && current[0] == x && current[1] == y && current[2] == z) {
            return
        }
        previousCameraPosition = current
        cameraPosition = floatArrayOf(x, y, z)
    }

    /** 本帧相机世界坐标。 */
    @Synchronized
    fun currentCameraPosition(): FloatArray? = cameraPosition?.copyOf()

    /** 上一帧相机世界坐标；第一帧为 null。 */
    @Synchronized
    fun previousCameraPosition(): FloatArray? = previousCameraPosition?.copyOf()

    /** 本帧第 index 个物体的 view-projection（projection × modelView），用于速度场计算。 */
    @Synchronized
    fun currentViewProjAt(index: Int): FloatArray? {
        val p = projection ?: return null
        if (index < 0 || index >= modelViews.size) {
            return null
        }
        return multiply(p, modelViews[index])
    }

    /** 上一帧第 index 个物体的 view-projection（pairing 失败返回 null）。 */
    @Synchronized
    fun previousViewProjAt(index: Int): FloatArray? {
        val p = previousProjection ?: return null
        val list = previousModelViews
        if (index < 0 || index >= list.size) {
            return null
        }
        return multiply(p, list[index])
    }

    /** 列主序 4×4 矩阵相乘（`result = a * b`）。 */
    fun multiply(a: FloatArray, b: FloatArray): FloatArray {
        val out = FloatArray(16)
        for (c in 0 until 4) {
            val c4 = c * 4
            for (r in 0 until 4) {
                out[c4 + r] = a[r] * b[c4] + a[4 + r] * b[c4 + 1]
                    + a[8 + r] * b[c4 + 2] + a[12 + r] * b[c4 + 3]
            }
        }
        return out
    }

    /**
     * 诊断用：一行描述当前时间性状态。
     *
     * 注意 `modelViews`/`chunkSections` 打印的是 **`本帧已捕获数 / 上一帧捕获数`**，而本行是在
     * [onLevelProjectionUpload] 里 `beginFrame()` **之后**打的 —— 此时本帧还没开始绘制，所以
     * 稳定的形态是 `0/N`：本帧 0 条、上一帧 N 条。`N` 持续 > 0 就说明逐物体/区块矩阵捕获正常工作。
     */
    @Synchronized
    fun describe(): String = ("enabled=" + enabled + " jitterOn=" + jitterEnabled
            + " frame=" + frameIndex
            + " samples=" + jitterSamples
            + " jitter=(" + jitterX + "," + jitterY + ")"
            + " prevJitter=(" + previousJitterX + "," + previousJitterY + ")"
            + " projection=" + (if (projection == null) "null" else "set")
            + " prevProjection=" + (if (previousProjection == null) "null" else "set")
            + " modelViews(thisFrame/prevFrame)=" + modelViews.size + "/" + previousModelViews.size
            + " chunkSections(thisFrame/prevFrame)=" + chunkSections.size + "/"
            + previousChunkSections.size
            + " camera=" + (if (cameraPosition == null) "null" else "set"))

    /**
     * Halton 序列：van der Corput 基的 radical inverse。
     * 与 `Halton2/3` 的常见实现一致（base 2 用位翻转，base 3 用通用算法）。
     */
    private fun ensureHalton(n: Int) {
        if (halton2.size >= n && halton3.size >= n) {
            return
        }
        val size = if (n > DEFAULT_JITTER_SAMPLES) n else DEFAULT_JITTER_SAMPLES
        halton2 = DoubleArray(size) { i -> radicalInverse(i + 1L, 2) }
        halton3 = DoubleArray(size) { i -> radicalInverse(i + 1L, 3) }
    }

    private fun radicalInverse(index: Long, base: Int): Double {
        var result = 0.0
        var fraction = 1.0
        var i = index
        while (i > 0L) {
            fraction /= base
            result += fraction * (i % base)
            i /= base
        }
        return result
    }
}
