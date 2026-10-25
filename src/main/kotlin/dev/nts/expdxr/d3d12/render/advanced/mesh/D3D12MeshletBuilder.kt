package dev.nts.expdxr.d3d12.render.advanced.mesh

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * P217（mesh 管线重构 §一/§二）：**meshlet 生成器**（shadow 模式，只统计不绘制）。
 *
 * 方案的 `TerrainVertex`（28 B、无法线）在 26.3 上成立 —— 顶点格式由
 * `com.mojang.blaze3d.vertex.DefaultVertexFormat.BLOCK` 定义：
 * `Position RGB32_FLOAT@0` + `Color RGBA8_UNORM@12` + `UV0 RG32_FLOAT@16` + `UV2 RG16_SINT@24`
 * = 28 B，没有 normal。逐区块的 `ChunkPosition`（RGB32_SINT，12 B）与 `ChunkVisibility`
 * （R32_FLOAT，4 B）在**另一个 VertexFormat `CHUNK_DATA_INSTANCED`（slot 1，stepRate=1，
 * 16 B/段）**里 —— 所以朝向只能像方案说的那样，在**分桶/切 Meshlet 时静态写入**
 * [Meshlet.directionMask]，不从顶点读。
 *
 * 这里做的是"切分 + 量化 AABB + 方向掩码"的 CPU 侧纯函数：
 * - 每 [QUADS_PER_MESHLET] 个 Quad 切一个 meshlet（方案 §二：16 Quad / 64 顶点 / 32 三角形）；
 * - Quad 的法线用 `cross(p1-p0, p2-p0)` 现算（顶点里没有法线字段），主轴分量决定 6 向之一，
 *   主轴不够占优（斜面、十字草）记 [DIR_NON_AXIS]；
 * - 局部 AABB 按 `[0,16] -> [0,255]`（`x15.9375`）量化，与方案 §二 的 packedAABB 对齐。
 *
 * **本类不改任何绘制状态**：先在 shadow 模式里跑出真实规模（每段 meshlet 数、方向分布、
 * 单方向纯度），再决定分桶重排与 MS 的实现细节。
 */
object D3D12MeshletBuilder {

    /** `DefaultVertexFormat.BLOCK` 的步长。 */
    const val VERTEX_STRIDE = 28

    /** 方案 §二：1 Meshlet = 16 Quad = 64 顶点 / 32 三角形。 */
    const val QUADS_PER_MESHLET = 16

    /** 一个 Quad 的顶点数（MC 的 BufferBuilder 固定按 4 个顶点出一个面）。 */
    const val VERTICES_PER_QUAD = 4

    /** 每条 `MeshletDescriptor` 的字节数（见 [Meshlet.pack]）。 */
    const val MESHLET_DESC_BYTES = 32

    /** 方向掩码的位（与方案 §二 的 directionMask 一致）。 */
    const val DIR_POS_X = 1 shl 0
    const val DIR_NEG_X = 1 shl 1
    const val DIR_POS_Y = 1 shl 2
    const val DIR_NEG_Y = 1 shl 3
    const val DIR_POS_Z = 1 shl 4
    const val DIR_NEG_Z = 1 shl 5

    /** bit 6：交叉面 / 双面材质（草、花、斜面）—— 不能做背面剔除。 */
    const val DIR_NON_AXIS = 1 shl 6

    /** 主轴分量占优阈值：`|n.axis| >= 0.85 * |n|` 才算"纯轴对齐"。 */
    private const val AXIS_DOMINANCE = 0.85f

    /** 量化系数：`[0,16] * 15.9375 = [0,255]`（16 -> 255 而非 256，正好装进 uint8）。 */
    private const val QUANTIZE = 15.9375f

    /**
     * 一个 meshlet 的元数据。字段顺序即方案 §二 的 `MeshletDescriptor`：
     * 两个量化 AABB（`minXYZ + quadCount`、`maxXYZ + directionMask`）+ sectionID + localVertexOffset。
     */
    data class Meshlet(
        /** 在 section 内的 quad 序号（不是索引号）。 */
        val firstQuad: Int,
        val quadCount: Int,
        /** 量化后的局部 AABB（0..255，对应 [0,16] 方块）。 */
        val quantMinX: Int, val quantMinY: Int, val quantMinZ: Int,
        val quantMaxX: Int, val quantMaxY: Int, val quantMaxZ: Int,
        /** 该 meshlet 内出现过的方向位（多个 = 混合朝向，分桶后应变成单一位）。 */
        val directionMask: Int,
    ) {
        /** 单方向（或非轴对齐）的 meshlet —— 分桶的目标产物。 */
        val isPure: Boolean get() = Integer.bitCount(directionMask) == 1

        /** 打包成方案 §二 的 32 B 记录（与 HLSL 侧一致，便于后续原样上传）。 */
        fun packedAabbMin(): Int =
            (quantMinX and 0xFF) or ((quantMinY and 0xFF) shl 8) or
                ((quantMinZ and 0xFF) shl 16) or ((quadCount and 0xFF) shl 24)

        fun packedAabbMax(): Int =
            (quantMaxX and 0xFF) or ((quantMaxY and 0xFF) shl 8) or
                ((quantMaxZ and 0xFF) shl 16) or ((directionMask and 0xFF) shl 24)

        /**
         * 写一条 **32 B** 的 `MeshletDescriptor`（8 个 uint32，2 条正好占一条 64 B cache line）。
         *
         * <p>布局（与将来的 HLSL `StructuredBuffer<MeshletDesc>` 一一对应）：
         * `[0] packedMin`、`[1] packedMax`、`[2] sectionID`、`[3] localVertexOffset`、
         * `[4] firstQuad`（调试/分桶用）、`[5..7] reserved`。
         * 方案原文写的 16 B 装不下 `directionMask` + `sectionID` + `localVertexOffset`（见 known_bugs P217）。
         */
        fun pack(dst: ByteBuffer, sectionId: Int, localVertexOffset: Int) {
            dst.putInt(packedAabbMin())
            dst.putInt(packedAabbMax())
            dst.putInt(sectionId)
            dst.putInt(localVertexOffset)
            dst.putInt(firstQuad)
            dst.putInt(0)
            dst.putInt(0)
            dst.putInt(0)
        }
    }

    /** 一次切分的结果。 */
    data class Result(
        val quads: Int,
        val vertices: Int,
        val meshlets: List<Meshlet>,
        /** 逐 Quad 的方向直方图，下标 = 方向位序号 0..6（顺序同 [DIR_POS_X]..[DIR_NON_AXIS]）。 */
        val directionCounts: IntArray,
    ) {
        val meshletCount: Int get() = meshlets.size
        val pureMeshlets: Int get() = meshlets.count { it.isPure }
    }

    /**
     * 把一段 `BLOCK` 格式的顶点流切成 meshlet。
     *
     * @param vertex 该 section 的顶点缓冲（`ByteBuffer` 的 `position..limit` 就是有效数据）。
     * @param stride 顶点步长；26.3 固定 28，参数化只为将来复用。
     * @return 无法切分（顶点数不是 4 的倍数 / 不足一个 Quad）时返回 null。
     */
    fun build(vertex: ByteBuffer, stride: Int = VERTEX_STRIDE): Result? {
        if (stride < VERTEX_STRIDE) return null
        val bytes = vertex.remaining()
        val vertices = bytes / stride
        val quads = vertices / VERTICES_PER_QUAD
        if (quads <= 0) return null

        val buf = vertex.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val base = buf.position()
        val quadDirs = IntArray(quads)
        val quadMin = FloatArray(quads * 3)
        val quadMax = FloatArray(quads * 3)

        for (q in 0 until quads) {
            val v0 = base + q * VERTICES_PER_QUAD * stride
            val x0 = buf.getFloat(v0)
            val y0 = buf.getFloat(v0 + 4)
            val z0 = buf.getFloat(v0 + 8)
            val v1 = v0 + stride
            val v2 = v0 + 2 * stride
            // cross(p1-p0, p2-p0)：顶点里没法线，只能用绕序反推面朝向。
            val ax = buf.getFloat(v1) - x0
            val ay = buf.getFloat(v1 + 4) - y0
            val az = buf.getFloat(v1 + 8) - z0
            val bx = buf.getFloat(v2) - x0
            val by = buf.getFloat(v2 + 4) - y0
            val bz = buf.getFloat(v2 + 8) - z0
            val nx = ay * bz - az * by
            val ny = az * bx - ax * bz
            val nz = ax * by - ay * bx

            quadDirs[q] = directionOf(nx, ny, nz)

            var minX = Float.MAX_VALUE
            var minY = Float.MAX_VALUE
            var minZ = Float.MAX_VALUE
            var maxX = -Float.MAX_VALUE
            var maxY = -Float.MAX_VALUE
            var maxZ = -Float.MAX_VALUE
            for (v in 0 until VERTICES_PER_QUAD) {
                val off = v0 + v * stride
                val x = buf.getFloat(off)
                val y = buf.getFloat(off + 4)
                val z = buf.getFloat(off + 8)
                if (x < minX) minX = x
                if (y < minY) minY = y
                if (z < minZ) minZ = z
                if (x > maxX) maxX = x
                if (y > maxY) maxY = y
                if (z > maxZ) maxZ = z
            }
            quadMin[q * 3] = minX
            quadMin[q * 3 + 1] = minY
            quadMin[q * 3 + 2] = minZ
            quadMax[q * 3] = maxX
            quadMax[q * 3 + 1] = maxY
            quadMax[q * 3 + 2] = maxZ
        }

        val meshlets = ArrayList<Meshlet>((quads + QUADS_PER_MESHLET - 1) / QUADS_PER_MESHLET)
        var first = 0
        while (first < quads) {
            val count = minOf(QUADS_PER_MESHLET, quads - first)
            var mask = 0
            var minX = Float.MAX_VALUE
            var minY = Float.MAX_VALUE
            var minZ = Float.MAX_VALUE
            var maxX = -Float.MAX_VALUE
            var maxY = -Float.MAX_VALUE
            var maxZ = -Float.MAX_VALUE
            for (q in first until first + count) {
                mask = mask or quadDirs[q]
                minX = minOf(minX, quadMin[q * 3])
                minY = minOf(minY, quadMin[q * 3 + 1])
                minZ = minOf(minZ, quadMin[q * 3 + 2])
                maxX = maxOf(maxX, quadMax[q * 3])
                maxY = maxOf(maxY, quadMax[q * 3 + 1])
                maxZ = maxOf(maxZ, quadMax[q * 3 + 2])
            }
            meshlets.add(
                Meshlet(
                    firstQuad = first,
                    quadCount = count,
                    quantMinX = quantize(minX), quantMinY = quantize(minY), quantMinZ = quantize(minZ),
                    quantMaxX = quantize(maxX), quantMaxY = quantize(maxY), quantMaxZ = quantize(maxZ),
                    directionMask = mask,
                )
            )
            first += count
        }
        val directionCounts = IntArray(7)
        for (d in quadDirs) {
            val bit = Integer.numberOfTrailingZeros(d)
            if (bit in 0..6) directionCounts[bit]++
        }
        return Result(quads = quads, vertices = vertices, meshlets = meshlets,
            directionCounts = directionCounts)
    }

    /** 面法线 -> 6 向位掩码之一；主轴不占优（斜面/十字草）返回 [DIR_NON_AXIS]。 */
    private fun directionOf(nx: Float, ny: Float, nz: Float): Int {
        val len = sqrt(nx * nx + ny * ny + nz * nz)
        if (len < 1e-6f) return DIR_NON_AXIS
        val ax = abs(nx)
        val ay = abs(ny)
        val az = abs(nz)
        val maxComponent = maxOf(ax, ay, az)
        // 每个分量的"占优"按整条法线长度判定，避免斜坡被误判成单轴。
        if (maxComponent < AXIS_DOMINANCE * len) return DIR_NON_AXIS
        return when (maxComponent) {
            ax -> if (nx > 0f) DIR_POS_X else DIR_NEG_X
            ay -> if (ny > 0f) DIR_POS_Y else DIR_NEG_Y
            else -> if (nz > 0f) DIR_POS_Z else DIR_NEG_Z
        }
    }

    private fun quantize(v: Float): Int = (v * QUANTIZE).roundToInt().coerceIn(0, 255)

    /** 量化值的反解（GLSL 侧应写成 `q / 15.9375`，与这里互为逆运算）。 */
    fun dequantize(q: Int): Float = (q and 0xFF) / QUANTIZE
}
