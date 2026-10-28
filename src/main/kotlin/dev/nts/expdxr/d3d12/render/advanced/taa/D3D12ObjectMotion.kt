package dev.nts.expdxr.d3d12.render.advanced.taa

import net.minecraft.client.Minecraft
import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import org.slf4j.LoggerFactory
import kotlin.math.max

/**
 * Per-object motion vectors, CPU side: this frame's object snapshot collection.
 *
 * Per-object matrices are not available: the model matrix is baked into the vertices on the CPU and
 * `ModelViewMat` carries only the camera rotation, so the only usable delta is each object's own
 * world-space displacement.
 *
 * Rows hold world-space absolute positions. [D3D12Velocity] subtracts *this frame's* camera when it
 * builds the GPU buffer — subtracting any other frame's camera would inject the camera motion as
 * object velocity, while a static object under a static camera must stay at exactly zero.
 *
 * Public API only, no mixin and no access transformer. See docs/antialiasing/README.md §3.4.
 */
object D3D12ObjectMotion {
    private val LOGGER = LoggerFactory.getLogger("dxrmc-objectmotion")

    /** 16 floats per row; must match the struct in [D3D12Velocity]'s inline HLSL byte for byte. */
    const val ROW_FLOATS: Int = 16

    /** Max objects reported per frame; the rest is dropped and counted. */
    const val MAX_OBJECTS: Int = 256

    /**
     * Cull radius in blocks, camera to entity origin (Euclidean): far rows would only cost slots.
     */
    private const val MAX_DISTANCE_BLOCKS: Double = 96.0

    /** uint offsets into the 16-float row; index 4 is unused, 5..11 mirror the HLSL struct. */
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
     * One object at world-space absolute position — the camera subtraction happens in [writeWorldRows].
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
     * This frame's slots, deduplicated by [Entity.id] (a later write wins), so `size` == object count.
     */
    private val currentSnapshots: MutableMap<Int, Snapshot> = LinkedHashMap()

    /** Snapshot rolled by [writeWorldRows], at most once per frame (later calls must reuse it). */
    @Volatile
    private var writeSnapshots: Map<Int, Snapshot> = emptyMap()

    /** Frame number of [writeSnapshots]; `-1` = nothing rolled yet. */
    private var lastConsumedFrame: Long = -1L

    @Volatile
    private var frameNumber: Long = 0L

    @Volatile
    private var lastOverflowLoggedFrame: Long = -1L

    @Volatile
    private var overflowCount: Long = 0L

    /**
     * Per-frame collection entry, called from [D3D12Temporal.beginFrame] before the velocity pass.
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
        // Vanilla renders the camera entity only in third person or while sleeping.
        // TODO: verify against real first-person mods.
        // No frustum / section culling: the resulting set is a superset of vanilla's, which the
        // shader's AABB test makes safe — the only cost is a row.
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
            // Vanilla does not draw the local player while spectating.
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
     * Writes one row; `Mth.lerp` reproduces vanilla's render-state position from the same partial tick.
     * Half extents are floored at 0.01 so a zero-sized box still matches a pixel.
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
                    "object row buffer is full ($MAX_OBJECTS rows); the remaining objects skip per-object velocity correction this frame" +
                        " ($overflowCount dropped so far)"
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
            // The render origin is the feet, while the shader wants the box centre.
            centerYOffset = halfHeight,
            yaw = entity.yRot
        )
    }

    /** Advances one frame: clears the slots that [harvestVisible] then refills. */
    @Synchronized
    fun beginFrame() {
        currentSnapshots.clear()
        frameNumber++
    }

    /** Clears everything (world exit / teleport / device shutdown). */
    @Synchronized
    fun reset() {
        currentSnapshots.clear()
        writeSnapshots = emptyMap()
        lastConsumedFrame = -1L
        frameNumber = 0L
        overflowCount = 0L
    }

    /**
     * Writes this frame's rows into [target] (rows = `target.size / ROW_FLOATS`) and returns the count
     * actually written. [cameraX]/[cameraY]/[cameraZ] must be this frame's camera.
     * [currentSnapshots] rolls into [writeSnapshots] only once per frame — otherwise the second
     * serialization of the frame would read empty slots and report 0 rows.
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
            // Lift to the box centre: the shader treats this Y as the centre, not the feet.
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
     * `p` in [0,1] is vanilla's `lerp(partialTick, old, now)`; `p > 1` extrapolates the last tick's
     * velocity, i.e. `now + (p-1) * (now-old)`.
     */
    private fun extrapolate(old: Double, now: Double, p: Double): Double = when {
        p >= 1.0 -> now + (p - 1.0) * (now - old)
        p <= 0.0 -> old + p * (now - old)
        else -> old + p * (now - old)
    }
}
