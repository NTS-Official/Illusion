package dev.nts.expdxr.d3d12.render.advanced.taa

import com.mojang.blaze3d.buffers.GpuBuffer
import com.mojang.blaze3d.buffers.GpuBufferSlice
import dev.nts.expdxr.d3d12.render.advanced.vrs.D3D12RenderScale
import dev.nts.expdxr.d3d12.render.core.D3D12GpuBuffer
import net.minecraft.client.Minecraft
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.nio.ByteOrder

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
 *     因此拦截 [dev.nts.expdxr.d3d12.backend.D3D12CommandEncoderBackend.writeToBuffer]
 *     就能拿到每帧的新鲜字节，不会像"原地改一次"那样留下过期 jitter。
 *  2. **上一帧相机矩阵**：`onLevelProjectionUpload` 在保存本帧**未抖动**投影的同时滚动历史
 *     （`previousProjection` / `previousJitter`），供下一帧算运动矢量。必须用未抖动矩阵，
 *     否则速度场会被 jitter 污染。
 *  3. **逐物体变换**：vanilla 的 `DynamicTransforms`(160B) 与 `ChunkSection`(96B) 都由
 *     `MappableRingBuffer(usage=130)` 承载、内容由 CPU 直接写进持久映射内存，所以后端可以在
 *     `setUniform` 时 `map(read)` 读回字节（`renderPass.setUniform` / 逐 core 的
 *     `UniformUploader.upload` 都收敛到同一个漏斗）。**按绘制序号配对**是明确的近似：
 *     只有两帧绘制顺序一致时才严格正确；实体/特征几何在提交时就把变换烘进了顶点
 *     （`ModelPart.render`），且同 RenderType 的连续几何会被合并成一次 core
 *     （`RenderTypeFeatureRenderer`），所以绘制级没有稳定的物体 id，更准的配对需要 mixin 暴露身份。
 *     区块（terrain）另有 `ChunkPosition`（整数区块原点）可用，比绘制序号稳得多 —— 单独记录。
 */
object D3D12Temporal {
    private val LOGGER: Logger = LoggerFactory.getLogger("dxrmc-temporal")

    /** 每个 jitter 循环的采样数（2 的幂更好：与 TAAU 的 8/16 采样档位对齐）。 */
    const val DEFAULT_JITTER_SAMPLES: Int = 16

    /**
     * P108d：逐物体/区块 UBO 的 **GPU 写映射读回**是否启用。**必须保持 false** —— 实机证明这条读回
     * 会把整帧渲染成一片常量清屏色（见 [captureUniform] 的说明）。相机的 model-view 改走 CPU 侧渲染
     * 状态（[captureCameraView]），不再依赖它。等 native 支持 `MAP_READ` 后再考虑恢复。
     */
    private const val CAPTURE_UNIFORMS_BY_READBACK: Boolean = false

    /**
     * P108d：为 true 时**冻结投影 jitter 且不替换投影上传**（见 [interceptWrite]）。由 DLSS 在开启期间置位 ——
     * 它必须走这条"无 jitter"的路：那条替换投影缓冲的路径会把整帧打成常量色。副作用是 DLSS 拿到的
     * 是**未抖动**的画面，所以 [jitter] 同时返回 0，两边保持一致。
     */
    @Volatile
    var jitterSuspended: Boolean = false

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

    /**
     * P76e 实验开关：`-Ddxrmc.temporal.jitter.dlss=on` 让**冻结期间（DLSS 开启）也注入 jitter**。
     * 默认 **off**，此时行为与 P108d 之后逐字节一致。
     *
     * 为什么要重新试：P108d 把"整帧塌成常量清屏色（190,179,167）"归因于替换投影上传，但
     * [interceptLevelProjectionWrite] 现在的写法是**就地改写调用方缓冲后原样返回**，而
     * `D3D12CommandEncoderBackend.writeToBuffer` 里 `val payload = jittered ?: data` 对
     * "返回 data" 与 "返回 null" 是同一个对象 —— 也就是说当年测的两条路其实都走到了就地改写。
     * 真正没测过的是"jitter 值本身非 0"这条路：冻结期间 [frame `beginFrame`] 把 jitter 强制
     * 清 0，画面当然不会坏。所以这次把生成与注入一起放开，用实机确认到底是哪一侧。
     */
    private const val DLSS_JITTER_PROPERTY: String = "dxrmc.temporal.jitter.dlss"

    /** 总开关。P75 默认关闭。 */
    @Volatile
    var enabled: Boolean = "true" == System.getProperty(TEMPORAL_PROPERTY)

    /** 是否真的把 jitter 注入投影矩阵（捕获路径不受它影响，便于 A/B 诊断）。 */
    @Volatile
    var jitterEnabled: Boolean = "false" != System.getProperty(JITTER_PROPERTY)

    /** P76e：冻结期间是否照旧生成并注入 jitter（实验开关，默认 false）。 */
    @Volatile
    var jitterWhileSuspended: Boolean = "on" == System.getProperty(DLSS_JITTER_PROPERTY)

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

    /**
     * P76g：本帧未抖动的关卡投影矩阵，直接取自 vanilla 的 CPU 渲染状态
     * （`LevelRenderState.cameraRenderState.projectionMatrix`，由 [captureProjection] 喂进来）。
     *
     * 为什么不能再用 UBO 写拦截的那份字节：实测 `writeToBuffer` 收到的 64 字节**根本不是投影矩阵**
     * （诊断 `proj4` 四行：对角线是 `4.66793E17`、`1.8E-43` 这类非规格化数，第三行 `m32 = -1` 不在位）。
     * 透视投影的 `w = -view.z` 全靠 `m32 = -1`，它一旦不在位，`prevClip.w` 就恒在 0 附近 ⇒
     * 着色器对**每个像素**都在 `if (prevClip.w <= 1e-4)` 处返回 0 ⇒ 运动矢量整张 0 ⇒
     * DLSS 把移动画面当静止累积 ⇒ 「拖影只在运动时出现、产生后不消失」（用户 m08677 / m08809）。
     *
     * 顺序上没有歧义：`cameraState.projectionMatrix` 与 `viewRotationMatrix` 同一份状态、同一个挂点
     * （[captureCameraView] 旁边）喂进来，喂的时候还在本帧 velocity pass 录制之前。
     */
    private var stateProjection: FloatArray? = null

    /** 上一帧同样的 [stateProjection]（[beginFrame] 里滚动）。 */
    private var previousStateProjection: FloatArray? = null

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

    /**
     * P108d：**本帧/上一帧的相机 model-view**，来自 vanilla 的 CPU 渲染状态
     * （`GameRenderer.gameRenderState().levelRenderState.cameraRenderState.viewRotationMatrix`，
     * 就是 `renderLevel` 交给 `levelRenderer.render` 的那个矩阵），由 DLSS 侧每帧经
     * [captureCameraView] 喂进来。取代原先从 UBO 写映射读回的办法（那条路会把画面打成常量色）。
     */
    private var stateCameraView: FloatArray? = null
    private var previousStateCameraView: FloatArray? = null

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
        // P76b：逐物体快照的跨帧历史也一起作废（传送 / 换世界后上一帧位置没有意义）。
        D3D12ObjectMotion.reset()
    }

    /**
     * 每帧开始时调用一次：滚动历史（本帧的 jitter/矩阵变成上一帧），并推进 jitter 序列。
     * 必须在**本帧任何 uniform 上传之前**调用（即帧首），否则本帧捕获的矩阵会被当成上一帧。
     * 生产接线里由 [onLevelProjectionUpload] 代劳（关卡投影每帧上传一次，正好在关卡绘制之前）。
     */
    @Synchronized
    fun beginFrame() {
        // P76f：相机世界坐标在**每帧帧首**直接向 Camera 取，不再从 Global Settings UBO 的字节里还原。
        // 旧路（`CameraBlockPos - CameraOffset`）实测读到的是垃圾：诊断行 `camPos=-4.6E23/5.4E14/-1.8E9`，
        // 每帧还在变。污染链是：CameraDelta 变成天文数字 → 世界位置被推到无穷远 →
        // `prevClip.w` 落到 -1e-4 以下 / 位移超过上界 → 着色器对**每个像素**都返回 0
        // ⇒ 运动矢量整张 0 ⇒ DLSS 认为画面从未移动过，拖影一旦产生就永不衰减
        // （用户实测 m08677「拖影产生了就不会消失」）。Camera 是权威来源，且与
        // [D3D12ObjectMotion.harvestVisible] 用的同一份，没有理由再解析字节。
        captureCameraFromCamera()
        previousJitterX = jitterX
        previousJitterY = jitterY
        previousProjection = projection
        previousViewProj = viewProj
        previousModelViews = modelViews
        modelViews = ArrayList(previousModelViews.size + 8)
        previousChunkSections = chunkSections
        chunkSections = ArrayList(previousChunkSections.size + 8)
        // P108d：CPU 侧相机 model-view 与本帧同帧滚动（见 [captureCameraView]）。
        previousStateCameraView = stateCameraView
        stateCameraView = null
        // P76g：CPU 侧关卡投影矩阵同帧滚动（见 [stateProjection]）。
        previousStateProjection = stateProjection
        stateProjection = null

        val n = jitterSamples
        ensureHalton(n)
        val index = (frameIndex % n).toInt()
        // Halton 值域 (0,1) → 平移成以 0 为中心的像素偏移 [-0.5, +0.5)。
        // P108d：`jitterSuspended`（DLSS 开启期间）默认必须一起判 —— 那时投影上传不被替换，所以
        // [jitter] 也必须返回 0，否则喂给 NGX 的 jitterOffset 会和实际渲染的画面不一致。
        // P76e：实验开关打开时连这一段一起放开，让"生成"与"注入"同生同灭（只放开一边只会喂错 jitterOffset）。
        if (enabled && jitterEnabled && (!jitterSuspended || jitterWhileSuspended)) {
            jitterX = halton2[index] - 0.5
            jitterY = halton3[index] - 0.5
        } else {
            jitterX = 0.0
            jitterY = 0.0
        }
        frameIndex++
        // P76a：速度附件与时间性状态同帧滚动（只在 velocity 开关打开时做任何事）。
        D3D12Velocity.beginFrame()
        // P76b：逐物体快照同帧采集。清空上一帧的槽，紧接着由公共 API 重新遍历一次实体表 ——
        // 实体的位置在 Minecraft.tick() 里就更新好了，而速度 pass 要到这一帧末尾才录制，
        // 所以这里采到的就是本帧渲染要用的那批位置。
        D3D12ObjectMotion.beginFrame()
        D3D12ObjectMotion.harvestVisible()
        // P76b 诊断：采集计数与写出行数（每 ~300 帧一行）。
        D3D12ObjectMotion.logProbe()
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
        // P76g：优先用 CPU 渲染状态那份（权威、且实测正常）；UBO 字节实测是垃圾，只留作兜底与对照。
        projection = stateProjection?.copyOf() ?: pristine.copyOf()
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
     * 写出拦截（由 [dev.nts.expdxr.d3d12.backend.D3D12CommandEncoderBackend.writeToBuffer] 调用）：
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
        if (jitterSuspended) {
            // P108d：DLSS 期间**绝不替换**关卡投影的上传。实测：这个方法一旦返回非 null，整帧就塌成
            // 一片清屏色（常量 190,179,167），且与 jitter 的数值无关（把 jitter 关成 0、字节一字不改
            // 也一样）；返回 null 时画面完全正常。这里只做历史滚动等记账（[interceptLevelProjectionWrite]
            // 内部会调 [onLevelProjectionUpload]），返回值一律丢掉，让"上传"这条路与"没开时间性渲染"
            // 逐字节一致。
            //
            // P76e 修：但**相机坐标的捕获不能跳过**。它是纯读、返回 null、一个字节都不改，与"替换上传"
            // 那条会把画面打坏的路无关；而跳过的后果实测是灾难性的（`dxrmc-temporal` 诊断恒为
            // `camera=null`）：
            //   - [cameraDelta] 恒为 null ⇒ 速度着色里的 `CameraDelta` 恒为 0 ⇒ 相机**平移**的视差整块
            //     丢失（只剩旋转项），走动时地面/近景的运动矢量系统性偏小，表现出"整片地面均匀地拖"；
            //   - `D3D12Velocity.uploadObjects()` 在相机为 null 时直接返回 0 行 ⇒ 逐物体修正整条失效
            //     （日志 `objects=0`）。
            // P76f：相机已在 [beginFrame] 里从 `Camera` 取过，这里不再解析 UBO 字节
            // （旧路读出来是 1e9~1e23 的垃圾，见那里的注释）。
            when (buffer.label()) {
                LEVEL_PROJECTION_LABEL -> interceptLevelProjectionWrite(destination, data)
                else -> {}
            }
            return null
        }
        return when (buffer.label()) {
            LEVEL_PROJECTION_LABEL -> interceptLevelProjectionWrite(destination, data)
            else -> null
        }
    }

    /**
     * 从 `Camera` 直接取本帧相机世界坐标（P76f）。这是权威来源，与
     * [D3D12ObjectMotion.harvestVisible] 用的是同一个 `gameRenderer.mainCamera()`。
     *
     * 只在 [beginFrame] 里调用一次 —— 这里同时承担历史滚动，多调一次就会把本帧当成上一帧。
     */
    private fun captureCameraFromCamera() {
        val pos = try {
            Minecraft.getInstance().gameRenderer.mainCamera().position()
        } catch (t: Throwable) {
            null
        } ?: return
        if (!pos.x.isFinite() || !pos.y.isFinite() || !pos.z.isFinite()) {
            return
        }
        captureCamera(pos.x.toFloat(), pos.y.toFloat(), pos.z.toFloat())
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
        if (!projectionLogged) {
            projectionLogged = true
            // P108d 取证（一次性）：记下调用方给的窗口与原始矩阵值。实测 position=0、remaining=64、
            // m[8]=m[9]=0（正常透视投影），所以"读错字节"不是画面塌成常量色的原因 —— 真凶是
            // [interceptWrite] 返回非 null（替换上传）这条路本身，见那里的说明。
            LOGGER.info("[backend] temporal: first level-projection write: position={} remaining={} "
                + "pristine[8]={} pristine[9]={}",
                data.position(), data.remaining(), pristine[8], pristine[9])
        }

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

        // P108d：**冻结期间（DLSS 开启）到记账为止，一个字节都不写、也不返回替换缓冲** ——
        // 实测这条写上传路径会把整帧打成常量色，连"写回与原字节完全相同的内容"都不行。
        // P76e：实验开关（[jitterWhileSuspended]）打开时继续往下走，就地改动上传字节。
        if (jitterSuspended && !jitterWhileSuspended) {
            return null
        }

        // P108d：**就地改写调用方自己的缓冲**（偏移按 position 算），不再造一个新的 ByteBuffer 返回。
        // 返回新缓冲时，`uploadStaging` 会用一个剩余长度相同的陌生缓冲，而调用方后续若有基于原缓冲
        // 的假设（长度/生命周期/身份）就会错位；就地改写的语义与"vanilla 写了一个抖动过的矩阵"
        // 完全一致，也顺带避免了 position 非 0 时读错字节（见上面的取证行）。
        return try {
            val view = data.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            view.position(data.position())
            view.asFloatBuffer().put(jittered, 0, 16)
            data
        } catch (t: Throwable) {
            // 只读缓冲等：退回"新缓冲"—— 新缓冲从 0 起自带整份 64B 负载，remaining 与调用方一致。
            val out = ByteBuffer.allocateDirect(data.remaining()).order(ByteOrder.LITTLE_ENDIAN)
            out.asFloatBuffer().put(jittered, 0, 16)
            out
        }
    }

    /**
     * 逐物体 UBO 的捕获入口（由 [dev.nts.expdxr.d3d12.backend.D3D12RenderPassBackend.setUniform]
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
     *
     * ⚠️ P108d（用户 m11364）**停用**：这条 write-map 读回会把整帧打成一片清屏色（实测
     * `-Pdxrmc.temporal=true` 时画面 77% 是常量 190,179,167；把 jitter 关成 0、投影字节一字不改，
     * 只留这条读回，仍然是同样的米白 —— 见交付说明）。它读的是 vanilla 的 `DynamicTransforms` /
     * `ChunkSection` 环（usage 130 没有 MAP_READ），用"写映射"绕过去读；这个映射看起来会破坏
     * vanilla 环里的内容（几何塌成常量色）。**相机的 model-view 改从 CPU 侧渲染状态取**
     * （[captureCameraView]，由 DLSS 侧每帧喂进来），不再需要任何 GPU 读回。
     */
    fun captureUniform(name: String, value: GpuBufferSlice) {
        if (!enabled) {
            return
        }
        // P108d：GPU 读回整条关闭（理由见上）。逐物体/区块矩阵的 TAAU 历史因此暂时没有数据 ——
        // 那部分尚未接线，而"画面能被读回打坏"是必须立刻停掉的。
        if (CAPTURE_UNIFORMS_BY_READBACK) {
            captureUniformByReadback(name, value)
        }
    }

    /** P108d：被停用的旧读回实现（保留以备 native 支持 MAP_READ 后恢复）。 */
    private fun captureUniformByReadback(name: String, value: GpuBufferSlice) {
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

    /** P108d 取证：只打一行「第一次投影写入的窗口」（position/remaining/pristine 值）。 */
    private var projectionLogged: Boolean = false

    /** 低频诊断（每 [LOG_INTERVAL_MILLIS] 一行）：P75 靠它确认挂钩点生效。 */
    private fun logStatus() {
        val now = System.currentTimeMillis()
        if (now - lastLogMillis < LOG_INTERVAL_MILLIS) {
            return
        }
        lastLogMillis = now
        LOGGER.info("[backend] temporal: {}", describe())
    }

    /**
     * 渲染目标像素尺寸：窗口帧缓冲尺寸 × `[render] renderScale`（P105）。
     *
     * 渲染缩放之前两者同尺寸（原注释「vanilla 无渲染缩放」）；P105 之后主渲染目标的纹理是
     * 窗口尺寸的一个百分比（见 [D3D12RenderScale]），而 jitter 的单位是**渲染目标的像素**，
     * 所以这里必须跟着缩放 —— 否则缩放档位越低，抖动幅值就越偏小。
     */
    private fun renderSize(): IntArray? {
        return try {
            val window = Minecraft.getInstance().window
            val width = D3D12RenderScale.scaled(window.width)
            val height = D3D12RenderScale.scaled(window.height)
            if (width <= 0 || height <= 0) null else intArrayOf(width, height)
        } catch (t: Throwable) {
            null
        }
    }

    /** 从（可能不是 0 起点的）ByteBuffer 的 [offset] 字节处读 count 个小端 float。 */
    private fun readFloats(data: ByteBuffer, offset: Int, count: Int): FloatArray? {
        val fb = data.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        // P108d：偏移是**相对调用方当前 position** 的（`uploadStaging` 就是按 position..limit 拷字节）。
        // 之前用绝对偏移，position 非 0 时会读到错位的字节 —— 投影矩阵被读成垃圾再写回去，
        // 整个画面塌成一片清屏色（实测：`-Pdxrmc.temporal=true` 必现）。
        val base = data.position() + offset
        if (offset < 0 || fb.limit() - base < count * 4) {
            return null
        }
        fb.position(base)
        val slice = fb.slice().asFloatBuffer()
        if (slice.remaining() < count) {
            return null
        }
        val out = FloatArray(count)
        slice.get(out)
        return out
    }

    /** 从（可能不是 0 起点的）ByteBuffer 的 [offset] 字节处读 count 个小端 int（偏移相对 position）。 */
    private fun readInts(data: ByteBuffer, offset: Int, count: Int): IntArray? {
        val fb = data.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val base = data.position() + offset
        if (offset < 0 || fb.limit() - base < count * 4) {
            return null
        }
        fb.position(base)
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

    /**
     * 保存本帧未抖动的关卡投影矩阵（列主序 16 float）。
     *
     * P76g：改写到 [stateProjection]（同一帧以第一次为准）。原实现直接覆盖 [projection]，而那条路
     * 唯一的生产者就是 UBO 写拦截 —— 实测它读出来的是垃圾字节（见 [stateProjection] 的注释），
     * 于是透视的 `m32 = -1` 不在位、`prevClip.w` 恒 0、运动矢量整张 0。现在由 DLSS 侧从
     * `cameraRenderState.projectionMatrix` 喂这份权威矩阵。
     */
    @Synchronized
    fun captureProjection(matrix: FloatArray) {
        if (matrix.size != 16 || stateProjection != null) {
            return
        }
        stateProjection = matrix.copyOf()
    }

    /** 本帧未抖动的投影矩阵。 */
    @Synchronized
    fun currentProjection(): FloatArray? = stateProjection?.copyOf() ?: projection?.copyOf()

    /** 上一帧未抖动的投影矩阵；第一帧为 null。 */
    @Synchronized
    fun previousProjection(): FloatArray? = previousStateProjection?.copyOf() ?: previousProjection?.copyOf()

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
        // P108d：优先用 CPU 侧渲染状态喂进来的那张（[captureCameraView]）—— UBO 读回已停用。
        stateCameraView?.let { return it.copyOf() }
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
        previousStateCameraView?.let { return it.copyOf() }
        if (previousChunkSections.isNotEmpty()) {
            return previousChunkSections[0].modelView.copyOf()
        }
        if (previousModelViews.isNotEmpty()) {
            return previousModelViews[0].copyOf()
        }
        return null
    }

    /**
     * P108d：把**本帧**的相机 model-view 记下来（列主序 16 float）。数据来自 vanilla 的 CPU 渲染状态，
     * 不经任何 GPU 读回 —— 原先从 `DynamicTransforms`/`ChunkSection` 环写映射读回的做法会把整帧
     * 打成常量色（见 [captureUniform]）。
     *
     * 只在 [beginFrame] 之后、本帧第一次绘制之前调用；同一帧重复调用以**第一次**为准
     * （相机矩阵一帧只有一个）。
     */
    @Synchronized
    fun captureCameraView(matrix: FloatArray) {
        if (matrix.size != 16 || stateCameraView != null) {
            return
        }
        stateCameraView = matrix.copyOf()
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

    /**
     * 两帧之间相机的**平移**增量（本帧 − 上一帧）；没有历史时为 null。
     *
     * 速度 pass 必须补这一项：terrain / 实体顶点是**相机相对**坐标，深度反投影出来的世界位置
     * 也是「相对本帧相机」的，而 [previousCameraView] 只带旋转、不带平移 —— 直接拿它重投影等于
     * 假设相机没动过，于是相机自身平移造成的视差整块丢失（近景最先糊）。
     * 把世界位置换到「相对上一帧相机」再加这个增量即可：
     * `prevWorld = world + (camNow - camPrev)`。
     */
    @Synchronized
    fun cameraDelta(): FloatArray? {
        val now = cameraPosition ?: return null
        val prev = previousCameraPosition ?: return null
        return floatArrayOf(now[0] - prev[0], now[1] - prev[1], now[2] - prev[2])
    }

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
                var sum = 0.0
                for (k in 0 until 4) {
                    sum += a[k * 4 + r].toDouble() * b[c4 + k].toDouble()
                }
                out[c4 + r] = sum.toFloat()
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
    fun describe(): String = ("enabled=${enabled} jitterOn=${jitterEnabled}"
            + " frame=${frameIndex}"
            + " samples=${jitterSamples}"
            + " jitter=(${jitterX},${jitterY})"
            + " prevJitter=(${previousJitterX},${previousJitterY})"
            + " projection=${if (projection == null) "null" else "set"}"
            + " prevProjection=${if (previousProjection == null) "null" else "set"}"
            + " modelViews(thisFrame/prevFrame)=${modelViews.size}/${previousModelViews.size}"
            + " chunkSections(thisFrame/prevFrame)=${chunkSections.size}/"
            + previousChunkSections.size
            + " camera=${if (cameraPosition == null) "null" else "set"}"
            // P76f 诊断：相机世界坐标与"上一帧 → 本帧"的位移量。与 native 侧
            // `DXRMC_P108X_DUMP_MV=1` 打出的运动矢量读数按时间戳对齐，就能回答
            // "mv 全 0 到底是通路坏了、还是这一刻相机根本没动"。
            + " camPos=${cameraPosition?.joinToString("/") ?: "-"}"
            + " camDelta=${cameraDelta()?.joinToString("/") ?: "-"}")

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
