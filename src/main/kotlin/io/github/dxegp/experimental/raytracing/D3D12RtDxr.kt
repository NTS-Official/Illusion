package io.github.dxegp.experimental.raytracing

import com.mojang.renderpearl.api.buffers.GpuBuffer
import io.github.dxegp.d3d12.extern.MCDR_ABI
import io.github.dxegp.d3d12.render.adapter.D3D12Device
import io.github.dxegp.d3d12.render.core.D3D12GpuBuffer
import io.github.dxegp.misc.IllusionLogger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.function.Supplier

/**
 * B-DXR 正式档（S3 3-f）：状态对象 + SBT + `DispatchRays`。
 *
 * 着色器库为 `rt_dxr_lib.hlsl`（`lib_6_8`）。见 docs/rt/README.md §16。
 */
internal object D3D12RtDxr {

    const val SRV_COUNT = 6
    const val UAV_COUNT = 5
    const val MAX_RECURSION = 1

    private const val RAYGEN_EXPORT = "RtRayGen"
    private const val MISS_EXPORT = "RtMiss"
    private const val HIT_EXPORT = "RtClosestHit"
    private const val INVALID_EXPORT = "RtInvalidHit"
    private const val INVALID_INTERSECTION_EXPORT = "RtInvalidIntersection"
    private const val LIBRARY_SOURCE = "rt_dxr_lib.hlsl"
    private const val LIBRARY_PROFILE = "lib_6_8"

    /** SBT 记录步长：32 B 标识符 + 32 B 局部根参数，必须是 32 的倍数（原生侧会校验）。 */
    private const val RECORD_BYTES = 64
    private const val MISS_COUNT = 1

    private const val HIT_COUNT = 3
    private const val HIT_GROUP_OPAQUE = "HITOPAQUE"
    private const val HIT_GROUP_CUTOUT = "HITCUTOUT"
    private const val HIT_GROUP_INVALID = "HITINVALID"

    private const val RAYGEN_OFFSET = 0L
    private const val MISS_OFFSET = RECORD_BYTES.toLong()
    private const val HIT_OFFSET = (RECORD_BYTES * 2).toLong()

    /** 记录数 = raygen 1 + miss `MISS_COUNT` + 命中组 `HIT_COUNT`；多算一条会白占一块没人读的尾部。 */
    private const val TABLE_BYTES = RECORD_BYTES * (1 + MISS_COUNT + HIT_COUNT)

    /* 状态对象与 SBT 会被换包释放、被渲染线程派发：创建/释放要互斥，否则会在派发中途把句柄释放掉。 */
    private val lock = Any()

    private var pipeline = 0L
    private var table: D3D12GpuBuffer? = null
    private var summary = "not built"
    private var failed = false

    /** 建一次：编译库、建状态对象、写 SBT。失败只 warn 一次，之后当作不可用。 */
    fun pipeline(device: D3D12Device): Long = synchronized(lock) {
        val cached = pipeline
        if (cached != 0L || failed) return cached
        val source = D3D12RtShaderSources.load(D3D12ShaderPacks.sourcePath(LIBRARY_SOURCE))
        val dxil = MCDR_ABI.D3D12CompileShaderDxc(source, "", LIBRARY_PROFILE)
        if (dxil == null) {
            failed = true
            IllusionLogger.warnMessage(
                "rt dxr: DXC compile failed for $LIBRARY_SOURCE: ${MCDR_ABI.D3D12LastError()}")
            return 0L
        }
        val groups = listOf(
            MCDR_ABI.RtHitGroup(HIT_GROUP_OPAQUE, HIT_EXPORT, null, null),
            MCDR_ABI.RtHitGroup(HIT_GROUP_CUTOUT, HIT_EXPORT, null, null),
            MCDR_ABI.RtHitGroup(HIT_GROUP_INVALID, INVALID_EXPORT, null, INVALID_INTERSECTION_EXPORT),
        )
        val handle = MCDR_ABI.D3D12CreateRaytracingPipeline(
            dxil, RAYGEN_EXPORT, MISS_EXPORT, groups, SRV_COUNT, UAV_COUNT, MAX_RECURSION)
        if (handle == 0L) {
            failed = true
            IllusionLogger.warnMessage(
                "rt dxr: state object creation failed: ${MCDR_ABI.D3D12LastError()}")
            return 0L
        }
        val built = buildTable(device, handle)
        if (built == null) {
            MCDR_ABI.ID3D12DestroyResource(handle)
            failed = true
            return 0L
        }
        table = built
        pipeline = handle
        IllusionLogger.infoMessage(
            "rt dxr: state object ready, sbt $TABLE_BYTES B (raygen + $MISS_COUNT miss + $HIT_COUNT hit)")
        summary = "sbt $TABLE_BYTES B, raygen + $MISS_COUNT miss + $HIT_COUNT hit, ids ok"
        return handle
    }

    /**
     * 光线派发。`srvs` 必须是五个几何缓冲 + TLAS 视图（t0..t5），
     * `uavs` 是 direct、indirect、aux、specular、specularGrazing 五张 raw 缓冲。
     */
    fun dispatch(
        device: D3D12Device,
        ctx: Long,
        srvs: LongArray,
        uavs: LongArray,
        cbv: Long,
        cbvOffset: Long,
        width: Int,
        height: Int,
    ): Boolean {
        val state = pipeline(device)
        if (state == 0L) return false
        val shaderTable = synchronized(lock) { table } ?: return false
        return MCDR_ABI.D3D12DispatchRays(
            ctx, state, shaderTable.handle(),
            RAYGEN_OFFSET, MISS_OFFSET, RECORD_BYTES.toLong(), MISS_COUNT,
            HIT_OFFSET, RECORD_BYTES.toLong(), HIT_COUNT,
            srvs, uavs, intArrayOf(0), cbv, cbvOffset, width, height,
        )
    }

    fun summary(): String = summary

    fun release() = synchronized(lock) {
        table?.close()
        table = null
        if (pipeline != 0L) {
            MCDR_ABI.ID3D12DestroyResource(pipeline)
            pipeline = 0L
        }
        failed = false
    }

    private fun buildTable(device: D3D12Device, state: Long): D3D12GpuBuffer? {
        val bytes = ByteArray(TABLE_BYTES)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        // 命中组记录取的是**命中组导出名**的标识符，不是 closest-hit 的名字：
        // GetShaderIdentifier 只认建状态对象时登记的导出名，写错拿到空标识符，派发时整个 SBT 失效。
        val exports = listOf(RAYGEN_EXPORT, MISS_EXPORT, HIT_GROUP_OPAQUE, HIT_GROUP_CUTOUT, HIT_GROUP_INVALID)
        exports.forEachIndexed { index, export ->
            val identifier = MCDR_ABI.D3D12GetShaderIdentifier(state, export)
            if (identifier == null) {
                IllusionLogger.warnMessage("rt dxr: missing export '$export'")
                return null
            }
            buffer.position(index * RECORD_BYTES)
            buffer.put(identifier)
        }
        // SBT 由 GPU 读、CPU 只在建的时候写一次，用上传堆缓冲直写即可。
        val handle = device.createBuffer(
            Supplier { "raytracing-dxr-sbt" }, GpuBuffer.USAGE_MAP_WRITE, TABLE_BYTES.toLong(),
        ) as? D3D12GpuBuffer
        if (handle == null) {
            IllusionLogger.warnMessage("rt dxr: sbt allocate failed (${MCDR_ABI.D3D12LastError()})")
            return null
        }
        handle.map(0L, TABLE_BYTES.toLong(), false, true).use { view -> view.data().put(bytes) }
        return handle
    }
}
