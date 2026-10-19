package dev.nts.expdxr.d3d12.render.advanced.taa

import net.minecraft.client.Minecraft
import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import org.slf4j.LoggerFactory
import kotlin.math.max

/**
 * P76b — 逐物体真运动矢量：CPU 侧的「本帧物体」快照采集。
 *
 * ## 为什么需要这一层（P76a 的局限）
 *
 * [D3D12Velocity] 只用主深度 + 上一帧 view-projection 做**相机重投影**，对静态几何严格正确，
 * 但它把所有几何（含移动实体）都当成静止世界，所以移动中的猪、玩家、掉落物都会拖影。
 *
 * 而 26.2 的渲染管线里**拿不到逐物体的变换**：物体的 model 矩阵在 CPU 侧就被烘进顶点了
 * （`ModelPart` 里 `builder.addVertex(matrix.transformPosition(...))`），顶点着色器只有
 * `gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0)`，其中 `ModelViewMat` 只是
 * **相机视图旋转**（`GameRenderer` 把它设成 `cameraState.viewRotationMatrix`）。
 * 提交路径上也没有任何物体身份：`SubmitNode` 是 10 行的标记接口，
 * `OrderedSubmitNodeCollector` 唯一带身份的入口是 `submitFlame` / `submitLeash` 上的
 * `EntityRenderState`。
 *
 * **注意**：逐物体模型矩阵既拿不到，也不需要拿 —— 见下面「与速度 pass 的集成」。
 *
 * ## 采集：只用公共 API，没有 mixin，也没有 access transformer
 *
 * 逐帧遍历 `ClientLevel.entitiesForRendering()`，直接从 `Entity` 上读**公共**字段/取值器：
 *
 * - 当前插值位置：`Mth.lerp(partialTick, entity.xOld, entity.x)` —— 与 vanilla 在
 *   `EntityRenderer.createRenderState` 里 `state.x = Mth.lerp(partialTicks, entity.xOld, entity.getX())`
 *   完全同一个公式；
 * - 上一帧同相位位置：同一条 `xOld → getX()` 端点按 `partialTick - 1.0` **外推**（见 [extrapolate]）；
 * - 包围盒：`entity.bbWidth` / `entity.bbHeight`（渲染原点是**脚**，所以竖直方向要抬半个身高）；
 * - 朝向：`entity.yRot`；
 * - `partialTick` 与 vanilla 同一来源：
 *   `deltaTracker.getGameTimeDeltaPartialTick(!tickRateManager.isEntityFrozen(entity))`。
 *
 * 早先的两条路都走不通，才落到这里：
 *
 * 1. **`RegisterRenderStateModifiersEvent`**（NeoForge 公共事件，在 `EntityRenderer.createRenderState`
 *    里被调用）—— 实机实测回调一次都没来（`fired=0`，连续 23 次采样），成因未查明；
 * 2. **注入 `LevelExtractor.extractEntity` 的 mixin** —— 运行期报
 *    `InjectionError ... Scanned 0 target(s)`，卡在注入本身。
 *
 * 而这条路需要的每一个成员都是 public（`Minecraft.level`、`Minecraft.getDeltaTracker()`、
 * `Minecraft.gameRenderer.mainCamera()`、`ClientLevel.entitiesForRendering()`、
 * `Level.tickRateManager()`、`Camera.entity()/isDetached()/position()`），所以**既不需要 mixin、
 * 也不需要 AT** —— 纯粹是「vanilla 本来就把这些暴露了」。
 *
 * ## 与 vanilla 的可见集差在哪（有意为之）
 *
 * vanilla 的可见性判定是 `LevelExtractor.isEntityVisible`：
 * `entityRenderDispatcher.shouldRender(entity, frustum, camX, camY, camZ)` **且**
 * `level.isOutsideBuildHeight(...) || levelRenderer.isSectionCompiledAndVisible(...)`。
 * 那需要本帧的 `Frustum`，而它只作为 `extract` 的局部变量存在。
 *
 * 这里**不做视锥裁剪、也不做区块可见性裁剪**，只用距离（[MAX_DISTANCE_BLOCKS]）+ `isInvisible`
 * 过滤。这样得到的集合是 vanilla 可见集的**超集**，而超出来的部分是安全的：
 * 真正的判定发生在着色器里（像素的世界位置是否落在该物体的 AABB 内），一个没被渲染的物体
 * 不会和任何像素重叠。代价只是占掉行槽，所以才有距离裁剪与 [MAX_OBJECTS] 上限。
 *
 * ## 本类负责什么
 *
 * 只负责**记数据**：本帧每个物体一行 [Snapshot]（世界坐标的当前/上一帧位置 + AABB 半尺寸 +
 * 朝向）。**不碰任何 GPU 资源** —— 那是 [D3D12Velocity] 的事。
 *
 * ## 与速度 pass 的集成
 *
 * [D3D12Velocity] 在录制前调 [writeWorldRows]：把世界坐标减掉**本帧**相机位置换成相机相对坐标，
 * 写进 GPU 缓冲。减本帧相机是关键 —— 静态物体两帧世界坐标相同，减同一个相机后差分为 0，
 * 与「静态相机 + 静态几何 ⇒ 运动矢量为 0」的约定一致；减错就会把整段相机位移当成物体速度注入。
 *
 * 着色器逐像素先按 AABB 判定「这个像素属于哪个物体」，命中就用物体自身的位移重投影，
 * 未命中才退回相机重投影。所以物体位移**必须是世界位移**，而不是屏幕空间差。
 */
object D3D12ObjectMotion {
    private val LOGGER = LoggerFactory.getLogger("dxrmc-objectmotion")

    /** 结构化缓冲每行 16 个 float，与 [D3D12Velocity] 内联 HLSL 的结构体严格对应。 */
    const val ROW_FLOATS: Int = 16

    /** 单帧最多上报多少物体（超出即丢弃并计数）。 */
    const val MAX_OBJECTS: Int = 256

    /**
     * 距离裁剪半径（方块，相机到**实体原点**的欧氏距离）。
     *
     * 96 格之外的运动物体在屏幕上只剩几像素，AABB 命中与否对观感没有意义；而裁剪掉它们
     * 才能保证拥挤场景下 [MAX_OBJECTS] 的槽位留给近处物体。
     */
    private const val MAX_DISTANCE_BLOCKS: Double = 96.0

    /** GPU 行的字段下标。 */
    const val FIELD_ID: Int = 0
    const val FIELD_CUR_X: Int = 1
    const val FIELD_CUR_Y: Int = 2
    const val FIELD_CUR_Z: Int = 3
    const val FIELD_PREV_X: Int = 5
    const val FIELD_PREV_Y: Int = 6
    const val FIELD_PREV_Z: Int = 7
    const val FIELD_HALF_X: Int = 8
    const val FIELD_HALF_Y: Int = 9
    const val FIELD_HALF_Z: Int = 10
    const val FIELD_YAW: Int = 11

    /**
     * 一个物体的本帧快照（**世界坐标**绝对位置）。
     *
     * 存世界坐标而不是相机相对坐标：相机相对坐标的差会把相机位移混进物体速度里，而世界坐标
     * 由 [D3D12Velocity] 在写缓冲时统一减去本帧相机位置。
     */
    private class Snapshot(
        val id: Int,
        val currX: Float, val currY: Float, val currZ: Float,
        val prevX: Float, val prevY: Float, val prevZ: Float,
        val halfX: Float, val halfY: Float, val halfZ: Float,
        val centerYOffset: Float,
        val yaw: Float
    )

    /**
     * 本帧采集槽。同一个物体一帧只会出现一次（按 [Entity.id] 去重，后到覆盖），
     * 所以这个 map 的 size 就是本帧的物体数。
     */
    private val currentSnapshots: MutableMap<Int, Snapshot> = LinkedHashMap()

    /** 最近一次被 [writeWorldRows] 取走的那一份（每帧只滚动一次，见 [lastConsumedFrame]）。 */
    @Volatile
    private var writeSnapshots: Map<Int, Snapshot> = emptyMap()

    /** [writeSnapshots] 对应的帧号；`-1` 表示还没取过任何一份。 */
    private var lastConsumedFrame: Long = -1L

    @Volatile
    private var frameNumber: Long = 0L

    @Volatile
    private var lastOverflowLoggedFrame: Long = -1L

    @Volatile
    private var overflowCount: Long = 0L

    /**
     * 逐帧采集入口：遍历客户端世界里可参与渲染的实体，每个记一行。
     *
     * 调用点在 [D3D12Temporal.beginFrame]（时间性 jitter 推进的同一时刻）。那里是**安全的**：
     * 实体的位置在 `Minecraft.tick()` 里就已更新，而速度 pass 要到这一帧末尾才录制。
     */
    @Synchronized
    fun harvestVisible() {
        val minecraft = Minecraft.getInstance()
        val level = minecraft.level ?: return
        val camera = minecraft.gameRenderer.mainCamera()
        val cameraPos = camera.position()
        val camX = cameraPos.x()
        val camY = cameraPos.y()
        val camZ = cameraPos.z()
        val cameraEntity = camera.entity()
        // 原版：相机实体自己只有在第三人称或睡觉时才被渲染。
        // TODO: 未来需要测试兼容真实第一人称模组
        val cameraEntityRenderable = camera.isDetached() ||
            (cameraEntity is LivingEntity && cameraEntity.isSleeping)
        val tickRateManager = level.tickRateManager()
        val deltaTracker = minecraft.deltaTracker
        val localPlayer = minecraft.player
        val maxDistanceSq = MAX_DISTANCE_BLOCKS * MAX_DISTANCE_BLOCKS

        for (entity in level.entitiesForRendering()) {
            val dx = entity.x - camX
            val dy = entity.y - camY
            val dz = entity.z - camZ
            val distanceSq = dx * dx + dy * dy + dz * dz
            if (entity.isRemoved || !entity.isAlive || entity.isInvisible) {
                continue
            }
            if (entity === cameraEntity && !cameraEntityRenderable) {
                continue
            }
            // vanilla：本地玩家在被观察（观战）时不画自己。
            if (entity === localPlayer && entity.isSpectator) {
                continue
            }
            if (distanceSq > maxDistanceSq) {
                continue
            }
            val partial = deltaTracker.getGameTimeDeltaPartialTick(!tickRateManager.isEntityFrozen(entity))
            harvest(entity, partial)
        }
    }

    /**
     * 记录一个物体的一行快照。
     *
     * 位置用 `Mth.lerp` 复算而不是读 `EntityRenderState`：后者只有在 extract 阶段才拿得到，
     * 而这条路径的全部价值就是**不依赖任何回调**。`partialTick` 与 vanilla 同一来源。
     */
    private fun harvest(entity: Entity, partialTick: Float) {
        val partial = partialTick.toDouble()
        val id = entity.id
        val x = Mth.lerp(partial, entity.xOld, entity.x)
        val y = Mth.lerp(partial, entity.yOld, entity.y)
        val z = Mth.lerp(partial, entity.zOld, entity.z)
        if (!x.isFinite() || !y.isFinite() || !z.isFinite()) {
            return
        }
        if (currentSnapshots.size >= MAX_OBJECTS && !currentSnapshots.containsKey(id)) {
            overflowCount++
            if (lastOverflowLoggedFrame != frameNumber) {
                lastOverflowLoggedFrame = frameNumber
                LOGGER.warn(
                    "P76b: 物体行已满（$MAX_OBJECTS 行），本帧其余物体不参与逐物体速度修正" +
                        "（累计丢弃 $overflowCount 个）"
                )
            }
            return
        }
        val halfWidth = max(entity.bbWidth * 0.5f, 0.01f)
        val halfHeight = max(entity.bbHeight * 0.5f, 0.01f)
        currentSnapshots[id] = Snapshot(
            id = id,
            currX = x.toFloat(), currY = y.toFloat(), currZ = z.toFloat(),
            prevX = extrapolate(entity.xOld, entity.x, partial - 1.0).toFloat(),
            prevY = extrapolate(entity.yOld, entity.y, partial - 1.0).toFloat(),
            prevZ = extrapolate(entity.zOld, entity.z, partial - 1.0).toFloat(),
            halfX = halfWidth,
            halfY = halfHeight,
            halfZ = halfWidth,
            // 实体的渲染原点是脚，包围盒在竖直方向要抬到几何中心。
            centerYOffset = halfHeight,
            yaw = entity.yRot
        )
    }

    /**
     * 帧推进：清空本帧的采集槽并让帧号 +1。
     *
     * 调用时机与 [D3D12Velocity] 的乒乓交换同一时刻（[D3D12Temporal.beginFrame]），
     * 紧随其后的 [harvestVisible] 再填这一帧的数据。
     */
    @Synchronized
    fun beginFrame() {
        currentSnapshots.clear()
        frameNumber++
    }

    /** 彻底清空（退出世界 / 传送 / 关设备）。 */
    @Synchronized
    fun reset() {
        currentSnapshots.clear()
        writeSnapshots = emptyMap()
        lastConsumedFrame = -1L
        frameNumber = 0L
        overflowCount = 0L
    }

    /**
     * 把本帧采集到的物体写成 GPU 结构化缓冲的行，返回真正写入的行数。
     *
     * [cameraX]/[cameraY]/[cameraZ] 是**本帧**的相机世界位置（见类注释：为什么必须是本帧）。
     *
     * 每帧只滚动一次：第一次调用时把 [currentSnapshots] 拷进 [writeSnapshots]，同一帧内的
     * 后续调用直接复用 —— 否则第二次调用会读到空槽而把行数误报成 0。
     */
    @Synchronized
    fun writeWorldRows(target: FloatArray, cameraX: Float, cameraY: Float, cameraZ: Float): Int {
        if (lastConsumedFrame != frameNumber) {
            writeSnapshots = HashMap(currentSnapshots)
            lastConsumedFrame = frameNumber
        }
        val source = writeSnapshots
        if (source.isEmpty()) {
            return 0
        }
        val capacity = target.size / ROW_FLOATS
        val limit = if (source.size < capacity) source.size else capacity
        var written = 0
        for (snapshot in source.values) {
            if (written >= limit) {
                break
            }
            val offset = written * ROW_FLOATS
            target[offset + FIELD_ID] = snapshot.id.toFloat()
            target[offset + FIELD_CUR_X] = snapshot.currX - cameraX
            // 实体的渲染原点是**脚**，而包围盒以几何中心为准 —— 把这一行抬到盒子中心，
            // 否则着色器会把 [y, y + 2 * halfHeight] 当成 [-halfHeight, +halfHeight]。
            target[offset + FIELD_CUR_Y] = snapshot.currY - cameraY + snapshot.centerYOffset
            target[offset + FIELD_CUR_Z] = snapshot.currZ - cameraZ
            target[offset + FIELD_PREV_X] = snapshot.prevX - cameraX
            target[offset + FIELD_PREV_Y] = snapshot.prevY - cameraY + snapshot.centerYOffset
            target[offset + FIELD_PREV_Z] = snapshot.prevZ - cameraZ
            target[offset + FIELD_HALF_X] = snapshot.halfX
            target[offset + FIELD_HALF_Y] = snapshot.halfY
            target[offset + FIELD_HALF_Z] = snapshot.halfZ
            target[offset + FIELD_YAW] = snapshot.yaw
            written++
        }
        return written
    }

    /**
     * 端点外推到任意渲染相位。
     *
     * `p ∈ [0,1]`：`lerp(p, old, now)`，与 vanilla 的
     * `state.x = lerp(partialTick, entity.xOld, entity.getX())` 完全一致。
     *
     * `p > 1`（上一帧的渲染相位落到当前 tick 之后）：假定上一 tick 的速度不变，
     * 即 `now + (p-1) * (now-old)`。
     */
    private fun extrapolate(old: Double, now: Double, p: Double): Double = when {
        p >= 1.0 -> now + (p - 1.0) * (now - old)
        p <= 0.0 -> old + p * (now - old)
        else -> old + p * (now - old)
    }
}
