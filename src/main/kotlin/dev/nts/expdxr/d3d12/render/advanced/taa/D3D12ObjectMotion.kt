package dev.nts.expdxr.d3d12.render.advanced.taa

import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.entity.EntityRenderer
import net.minecraft.client.renderer.entity.state.EntityRenderState
import net.minecraft.util.Mth
import net.minecraft.util.context.ContextKey
import net.minecraft.world.entity.Entity
import org.slf4j.LoggerFactory
import kotlin.math.max

/**
 * P76b — 逐物体真运动矢量：CPU 侧的「本帧可见物体」快照采集。
 *
 * ## 为什么需要这一层（P76a 的局限）
 *
 * [D3D12Velocity] 只用主深度 + 上一帧 view-projection 做**相机重投影**，对静态几何严格正确，
 * 但它把所有几何（含移动实体）都当成静止世界，所以移动中的猪、玩家、掉落物都会拖影。
 *
 * 而 26.2 的渲染管线里**拿不到逐物体的变换**：物体的 model 矩阵在 CPU 侧就被烘进顶点了
 * （`ModelPart.compile` 里 `builder.addVertex(matrix.transformPosition(...))`），
 * 顶点着色器只有 `gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0)`，其中
 * `ModelViewMat` 只是**相机视图旋转**（`GameRenderer.java:537` 把它设成 `cameraState.viewRotationMatrix`）。
 * 提交路径上也没有任何物体身份：`SubmitNode` 是 10 行的标记接口，
 * `OrderedSubmitNodeCollector` 唯一带身份的入口是 `submitFlame` / `submitLeash` 上的 `EntityRenderState`。
 *
 * 唯一的出路是在 **extract 阶段**抓 `(Entity, state)` 这一对：NeoForge 的
 * [net.neoforged.neoforge.client.renderstate.RenderStateExtensions.onUpdateEntityRenderState]
 * 在 `EntityRenderer.createRenderState` 里被调用（`EntityRenderer.java:165`），那时 `Entity` 还在
 * 手上，`xOld` / `getX()` / `yRotO` / `getYRot()` 这些跨帧端点都可读，而且每帧每个可见物体只跑一次。
 * 那是**公共 API**（`RegisterRenderStateModifiersEvent`），不需要 mixin。
 *
 * ## 本类负责什么
 *
 * 只负责**记数据**：本帧每个可见物体一行 [Snapshot]（当前插值位置 + 上一帧同刻位置 + AABB 半径 +
 * 朝向），并保留上一帧那份供 GPU 端配对。**不碰任何 GPU 资源** —— 那是 [D3D12Velocity] 的事。
 *
 * ## 相位约定
 *
 * 快照用**渲染相位**而不是 tick：物体在 tick `t` 与 `t+1` 之间的插值端点就是
 * `xOld` → `getX()`，partialTick 落在 `[0,1)`。为了让位置能与「上一帧的同一渲染相位」对上，
 * 这里按 [phase] 存端点并**外推**上一帧的渲染相位 `phase - 1.0`
 * （一帧 ≈ 一个 tick，这正是 vanilla 那句
 * `state.x = lerp(partialTick, entity.xOld, entity.getX())` 本身的假设）。
 *
 * ## 与速度 pass 的集成
 *
 * [D3D12Velocity] 在录制前调 [writeRows] 把上一帧的行写进 GPU 结构化缓冲；速度着色器逐像素先按
 * 当前 AABB 判定「这个像素属于哪个物体」，命中就用物体自身的位移重投影，未命中才退回相机重投影。
 */
object D3D12ObjectMotion {
    private val LOGGER = LoggerFactory.getLogger("dxrmc-objectmotion")

    /**
     * 身份写进 render state 用的 key。
     * 注意：[EntityRenderState] 每帧重建，且 `onUpdateEntityRenderState` 开头就
     * `resetRenderData()`，所以这个值只在**当前帧内**有效 —— 正合本用途。
     */
    private val IDENTITY_KEY: ContextKey<Int> = ContextKey.vanilla("dxrmc_object_id")

    /** 结构化缓冲每行 16 个 float，与 [D3D12Velocity] 内联 HLSL 的结构体严格对应。 */
    const val ROW_FLOATS: Int = 16

    /** 单帧最多上报多少物体（超出即丢弃并计数）。 */
    const val MAX_OBJECTS: Int = 256

    /** 诊断日志间隔（帧）。 */
    private const val LOG_INTERVAL_FRAMES: Long = 300L

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

    /** 一个物体的本帧快照。 */
    private class Snapshot(
        val id: Int,
        val currX: Float, val currY: Float, val currZ: Float,
        val prevX: Float, val prevY: Float, val prevZ: Float,
        val halfX: Float, val halfY: Float, val halfZ: Float,
        val yaw: Float
    ) {
        /** 写进 GPU 结构化缓冲的一行（[ROW_FLOATS] 个 float）。 */
        fun writeInto(row: FloatArray, offset: Int) {
            row[offset + FIELD_ID] = id.toFloat()
            row[offset + FIELD_CUR_X] = currX
            row[offset + FIELD_CUR_Y] = currY
            row[offset + FIELD_CUR_Z] = currZ
            // [4] 保留（以后放速度模长 / 置信度）。
            row[offset + FIELD_PREV_X] = prevX
            row[offset + FIELD_PREV_Y] = prevY
            row[offset + FIELD_PREV_Z] = prevZ
            row[offset + FIELD_HALF_X] = halfX
            row[offset + FIELD_HALF_Y] = halfY
            row[offset + FIELD_HALF_Z] = halfZ
            row[offset + FIELD_YAW] = yaw
            // [12..15] 保留。
        }
    }

    /**
     * 一个物体的本帧快照（世界坐标绝对位置）。
     *
     * 与 [Snapshot] 的区别：这里存的是**世界坐标**而不是相机相对坐标 —— 「路径 B」的调用点在
     * `extractVisibleEntities` 里，拿不到渲染用的相机相对原点，而且相机动了以后相机相对坐标
     * 的差会把相机位移混进物体速度里。世界坐标由 [D3D12Velocity] 在写缓冲时减去相机相对原点。
     */
    private class WorldSnapshot(
        val id: Int,
        val currX: Float, val currY: Float, val currZ: Float,
        val prevX: Float, val prevY: Float, val prevZ: Float,
        val halfX: Float, val halfY: Float, val halfZ: Float,
        val centerYOffset: Float,
        val yaw: Float
    )

    /**
     * 每帧重填。Extract 阶段同一个物体可能被建多个 state（多部件 / 多个 renderer），
     * 用 id 去重、后到覆盖 —— 与最终提交的那份最接近。
     */
    private val currentSnapshots: MutableMap<Int, Snapshot> = LinkedHashMap()

    /** 上一帧的成品。在 [beginFrame] 里由 [currentSnapshots] 滚动过来。 */
    @Volatile
    private var previousSnapshots: Map<Int, Snapshot> = emptyMap()

    @Volatile
    private var frameNumber: Long = 0L

    /** 上一次滚动时上一帧有多少行（日志对照用）。 */
    @Volatile
    private var lastRolledCount: Int = 0

    @Volatile
    private var lastOverflowLoggedFrame: Long = -1L

    /**
     * 「路径 B」：直接采到的本帧可见物体（世界坐标）。
     *
     * 与 [currentSnapshots] 的区别只有一点 —— 后者依赖 NeoForge 的
     * `RegisterRenderStateModifiersEvent` 回调，而实测该回调在一些场景下一个都不来；
     * 这条路径由 [LevelExtractorMixin] 在 `extractVisibleEntities` 开头直接遍历
     * `ClientLevel.entitiesForRendering()` 得到，**不依赖任何回调**，所以只要有可见实体就一定非空。
     * 两个计数同时打印，一眼就能分清「钩子没挂上」和「这帧真的没有可见物体」。
     */
    private val currentWorldSnapshots: MutableMap<Int, WorldSnapshot> = LinkedHashMap()

    /** 最近一次被 [writeWorldRows] 消费掉的那一份世界快照（每份只被写出去一次）。 */
    @Volatile
    private var writeWorldSnapshots: Map<Int, WorldSnapshot> = emptyMap()

    /** 累计 [harvest] 看到过多少个可见实体（用来算每帧增量的诊断量）。 */
    @Volatile
    private var seenCount: Long = 0L

    /** 诊断：`onEntityRenderState` 被调用过多少次（含被拒绝/溢出的）。全 0 且物体数为 0 就说明 */
    /** 回调根本没被挂上，而不是"这帧没有可见物体" —— 这两者在日志里必须能区分。 */
    @Volatile
    private var callbackCount: Long = 0L

    /** 诊断：[harvest] 被调用过多少次（每次调用对应一帧的可见实体遍历）。 */
    @Volatile
    private var harvestCount: Long = 0L

    /** 诊断：上次报 [harvestCount] 时用掉的值，用来算区间增量。 */
    private var lastHarvestReported: Long = 0L

    /** 诊断：本帧 [harvest] 看到了多少个可见实体。 */
    private var seenThisFrame: Int = 0

    /** 诊断：自上次报告以来单帧看到的最多可见实体数。 */
    private var harvestSeenRolling: Int = 0

    /** 诊断：上次报回调计数时用掉的 [callbackCount] 值，用来算区间增量。 */
    private var lastCallbackReported: Long = 0L

    @Volatile
    private var overflowCount: Long = 0L

    @Volatile
    private var rejectedCount: Long = 0L

    /** 注册到 `RegisterRenderStateModifiersEvent` 的入口（见 [IllusionClient]）。 */
    fun register(event: net.neoforged.neoforge.client.renderstate.RegisterRenderStateModifiersEvent) {
        event.registerEntityModifier(EntityRenderer::class.java) { entity, state ->
            onEntityRenderState(entity, state)
        }
        LOGGER.info("P76b: 逐物体运动快照已挂到 EntityRenderer（公共 API，无 mixin）")
    }

    /**
     * 一个可见物体刚完成 extract 时的回调：先把身份塞进 render state，再记一行快照。
     *
     * 泛型参数来自事件注册的 `Class<out EntityRenderer<…>>`，这里只依赖
     * `Entity` / `EntityRenderState` 两个上界，所以基类回调可以覆盖全部 renderer。
     */
    fun <E : Entity, S : EntityRenderState> onEntityRenderState(entity: E, state: S) {
        callbackCount++
        state.setRenderData(IDENTITY_KEY, entity.id)
        record(entity, state)
    }

    /** 取出塞进 render state 的身份（0 = 没登记过）。 */
    fun identityOf(state: EntityRenderState): Int = state.getRenderData(IDENTITY_KEY) ?: 0

    /**
     * 「路径 B」采集：**每个可见实体调一次**，由 [LevelExtractorVisibleEntitiesMixin] 挂在
     * `LevelExtractor.extractVisibleEntities` 里 `this.extractEntity(entity, partialEntity)`
     * 那一行之前 —— 也就是 vanilla 已经做完 `isEntityVisible` 过滤、拿到此刻精确的
     * `partialEntity` 之后。
     *
     * 为什么不在方法头一次拿整张实体表：`ClientLevel.entitiesForRendering()` 返回的是
     * **整个客户端世界**的实体（实测这个测试世界有 300+ 个），而真正走到渲染的只有通过
     * `isEntityVisible` 的那几个（实测 1 个）。在方法头采集会把 300 个不可见实体全部当成
     * "可见物体"，既超缓冲又会让速度着色器用错误的 AABB 覆盖大量像素。
     *
     * 位置用 `Mth.lerp` 复算而不是读 render state：这条路径的价值就是**不依赖**任何回调
     * （公共 API 的 `RegisterRenderStateModifiersEvent` 实测一次都没来），而 render state
     * 只在回调里才拿得到。`partialTick` 与 vanilla 同一来源。
     */
    @JvmStatic
    @Synchronized
    fun harvest(entity: Entity, partialTick: Float) {
        harvestCount++
        val partial = partialTick.toDouble()
        seenCount++
        val id = entity.id
        val x = Mth.lerp(partial, entity.xOld, entity.x)
        val y = Mth.lerp(partial, entity.yOld, entity.y)
        val z = Mth.lerp(partial, entity.zOld, entity.z)
        if (!x.isFinite() || !y.isFinite() || !z.isFinite()) {
            rejectedCount++
            return
        }
        if (currentWorldSnapshots.size >= MAX_OBJECTS && !currentWorldSnapshots.containsKey(id)) {
            overflowCount++
            if (lastOverflowLoggedFrame != frameNumber) {
                lastOverflowLoggedFrame = frameNumber
                LOGGER.warn(
                    "P76b: 物体缓冲已满（$MAX_OBJECTS 行），本帧后续可见实体不参与逐物体速度修正" +
                        "（累计丢弃 $overflowCount 个）"
                )
            }
            return
        }
        val halfWidth = max(entity.bbWidth * 0.5f, 0.01f)
        val halfHeight = max(entity.bbHeight * 0.5f, 0.01f)
        currentWorldSnapshots[id] = WorldSnapshot(
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

    /** 采集一个物体的快照。 */
    private fun record(entity: Entity, state: EntityRenderState) {
        val id = entity.id
        val x = state.x
        val y = state.y
        val z = state.z
        if (!x.isFinite() || !y.isFinite() || !z.isFinite()) {
            rejectedCount++
            return
        }
        if (currentSnapshots.size >= MAX_OBJECTS && !currentSnapshots.containsKey(id)) {
            overflowCount++
            if (lastOverflowLoggedFrame != frameNumber) {
                lastOverflowLoggedFrame = frameNumber
                LOGGER.warn(
                    "P76b: 物体缓冲已满（$MAX_OBJECTS 行），本帧后续物体不参与逐物体速度修正" +
                        "（累计丢弃 $overflowCount 个）"
                )
            }
            return
        }
        val partial = state.partialTick.toDouble()
        val halfWidth = max(state.boundingBoxWidth * 0.5f, 0.01f)
        val halfHeight = max(state.boundingBoxHeight * 0.5f, 0.01f)
        currentSnapshots[id] = Snapshot(
            id = id,
            currX = x.toFloat(), currY = y.toFloat(), currZ = z.toFloat(),
            prevX = extrapolate(entity.xOld, entity.x, partial - 1.0).toFloat(),
            prevY = extrapolate(entity.yOld, entity.y, partial - 1.0).toFloat(),
            prevZ = extrapolate(entity.zOld, entity.z, partial - 1.0).toFloat(),
            halfX = halfWidth,
            halfY = halfHeight,
            halfZ = max(halfWidth, halfHeight),
            yaw = entity.yRot
        )
    }

    /**
     * 帧推进：把本帧采集的成品滚成「上一帧」，清空本帧的采集槽。
     *
     * 调用时机必须**在 extract 阶段之前**，且在速度 pass 消费完上一帧数据之后 ——
     * 也就是 [D3D12Temporal.beginFrame] 里（与 [D3D12Velocity] 的乒乓交换同一时刻）。
     */
    @Synchronized
    fun beginFrame() {
        lastRolledCount = currentSnapshots.size
        previousSnapshots = HashMap(currentSnapshots)
        currentSnapshots.clear()
        // 路径 B **故意不在这里滚动**：extract 阶段跑在 [beginFrame] 之后（实机日志：
        // `P76b 诊断` 排在速度 pass 那行之后、帧号同步），所以本帧采到的世界快照永远落在
        // `currentWorldSnapshots` 里，只有等这一帧的录制把它消费掉才能滚走 —— 见 [writeWorldRows]。
        if (seenThisFrame > harvestSeenRolling) {
            harvestSeenRolling = seenThisFrame
        }
        seenThisFrame = 0
        frameNumber++
    }

    /** 彻底清空（退出世界 / 传送 / 关设备）。 */
    @Synchronized
    fun reset() {
        currentSnapshots.clear()
        previousSnapshots = emptyMap()
        currentWorldSnapshots.clear()
        writeWorldSnapshots = emptyMap()
        frameNumber = 0L
        lastRolledCount = 0
        overflowCount = 0L
        rejectedCount = 0L
        harvestSeenRolling = 0
        seenThisFrame = 0
    }

    /** 本帧已采集的物体数。 */
    @Synchronized
    fun currentCount(): Int = currentSnapshots.size

    /** 上一帧采集到的物体数。 */
    fun previousCount(): Int = previousSnapshots.size

    fun lastRolled(): Int = lastRolledCount

    fun overflow(): Long = overflowCount

    fun rejected(): Long = rejectedCount

    fun frame(): Long = frameNumber

    /**
     * 有可用的物体行可写，且时间性 jitter 是开的（否则上一帧位置没有意义）。
     *
     * 看 [currentWorldSnapshots]（本帧 extract 刚采的那批）而不是已消费的那份 —— 写得出去。
     */
    fun isActive(): Boolean =
        D3D12Temporal.enabled && (currentWorldSnapshots.isNotEmpty() || previousSnapshots.isNotEmpty())

    /**
     * 把上一帧的快照写成 GPU 结构化缓冲的行，返回真正写入的行数。
     *
     * 只写上一帧：速度 pass 在**本帧渲染完成之后**才录制，像素来自本帧，而它要回答的是
     * 「这个像素上一帧在哪」，所以配对对象是上一帧的快照。
     */
    @Synchronized
    fun writeRows(target: FloatArray): Int {
        val source = previousSnapshots
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
            snapshot.writeInto(target, written * ROW_FLOATS)
            written++
        }
        return written
    }

    fun describe(): String =
        "objects(thisFrame=${currentSnapshots.size} lastFrame=${previousSnapshots.size} " +
            "rolled=$lastRolledCount overflow=$overflowCount rejected=$rejectedCount frame=$frameNumber " +
            "| harvest(visible=$seenCount peak=$harvestSeenRolling " +
            "thisFrame=${currentWorldSnapshots.size} written=${writeWorldSnapshots.size}))"

    /** 「路径 B」最近一次真正写进 GPU 缓冲的物体数。 */
    fun worldCount(): Int = writeWorldSnapshots.size

    /**
     * 「路径 B」：把上一帧的世界坐标快照换算成**相机相对**坐标后写成 GPU 行。
     *
     * [cameraX]/[cameraY]/[cameraZ] 是**本帧**的相机世界位置。减本帧相机而不是上一帧：
     * 静态物体在两帧的世界坐标相同，减同一个本帧相机后 `prev == curr`，差分为 0 —— 与
     * 「静态相机 + 静态几何 ⇒ 运动矢量为 0」的既有约定一致（也正是不这样做就会把整段相机
     * 位移当成物体速度注入的那个已知陷阱）。
     */
    @Synchronized
    fun writeWorldRows(target: FloatArray, cameraX: Float, cameraY: Float, cameraZ: Float): Int {
        // 滚动点在这里，不在 [beginFrame]：extract 跑在 [beginFrame] 之后，
        // 所以「本帧渲染用的那一份世界快照」此刻正躺在 [currentWorldSnapshots] 里，
        // 它才是速度着色器要的「上一帧」（每行自带 `pos(p)` 与 `pos(p-1)` 两个端点）。
        // 消费一次即滚走，下一帧不会再被写出去。
        if (currentWorldSnapshots.isNotEmpty()) {
            writeWorldSnapshots = HashMap(currentWorldSnapshots)
        }
        val source = writeWorldSnapshots
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
            // 否则着色器会把 [y, y+2*halfHeight] 当成 [-halfHeight, +halfHeight]。
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
     * 诊断：每 [LOG_INTERVAL_FRAMES] 帧报一次回调与采集情况。
     *
     * 存在的理由：`objects=0` 有两种完全不同的成因 —— **回调没挂上** 与 **这帧真的没有可见物体**。
     * 只看物体数分不出来，而这两者的修法完全不同。`fired` 是自上次报告以来的回调增量，
     * `harvested` 是同一区间内 [harvest] 看到的实体数增量 —— 两者一起看即可判定：
     *
     * - `harvested=0`：这帧**真的**没有可见实体，`fired=0` 不代表钩子有问题；
     * - `harvested>0` 而 `fired=0`：回调路径确实没生效，走「路径 B」。
     */
    @Synchronized
    fun logProbe() {
        if (frameNumber % LOG_INTERVAL_FRAMES != 0L) {
            return
        }
        val fired = callbackCount - lastCallbackReported
        lastCallbackReported = callbackCount
        val harvested = harvestCount - lastHarvestReported
        lastHarvestReported = harvestCount
        LOGGER.info(
            "P76b 诊断: fired=$fired (累计 $callbackCount) harvestCalls=$harvested (累计 $harvestCount) " +
                "objects(thisFrame=${currentSnapshots.size} lastFrame=${previousSnapshots.size}) " +
                "harvest(thisFrame=${currentWorldSnapshots.size} written=${writeWorldSnapshots.size} " +
                "seen=$seenCount peak=$harvestSeenRolling) " +
                "rolled=$lastRolledCount overflow=$overflowCount rejected=$rejectedCount frame=$frameNumber " +
                "| ${visibleEntityProbe()}"
        )
    }

    /**
     * 诊断：当前客户端世界里有多少实体可参与渲染，以及相机实体是否被排除。
     *
     * 为什么需要：`fired=0` 有两种成因 —— 钩子没挂上、或确实没有可见实体（相机实体在第一人称下
     * 被 `LevelExtractor` 排除）。这两个数字能把它们分开。
     */
    private fun visibleEntityProbe(): String {
        val level = Minecraft.getInstance().level ?: return "no world"
        return runCatching {
            val camera = Minecraft.getInstance().cameraEntity
            var self = 0
            var total = 0
            for (entity in level.entitiesForRendering()) {
                total++
                if (entity === camera) {
                    self++
                }
            }
            "level: visible=$total cameraEntity=$self"
        }.getOrElse { "probe failed: $it" }
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