package dev.nts.expdxr.d3d12.render.advanced.mesh

import com.mojang.renderpearl.api.vertex.VertexFormat

/**
 * 地形 MS/AS 生成器（meshlet 版）：每组 = 1 meshlet（16 quad / 64 顶点 / 32 三角形），索引按 `SV_GroupThreadID`
 * 算术展开、不读索引缓冲；`cull=true` 额外生成 `as_main`（6 向背面 + 视锥）。契约 t14 顶点池（28 B/顶点）/
 * t15 SectionDesc / t16 MeshletDesc / b8；VS 由 spvc 产出必须 row_major（`mul(float4, M)`，不要 `mul(M, v)`）。
 * 见 docs/mesh&vrs/README.md §2.5 / §2.5.4。
 */
object D3D12MeshletShader {

    /** 一个 meshlet 的线程数 = 最多顶点数（16 quad × 4 顶点）。 */
    const val THREADS = 64

    /** 每组最多图元数（16 quad × 2 三角形）。 */
    const val MAX_PRIMITIVES = 32

    const val VERTEX_STRIDE = 28

    /**
     * 生成 MS 源码；`vsHlsl` 必须是 spvc 产出的地形 VS（含 `SPIRV_Cross_Input` 与 `main`）。`cull=true` 还需
     * 解析出 CameraBlockPos/CameraOffset/ModelViewMat/ProjMat 四个 uniform，任一缺失返回 null，调用方必须回退。
     */
    fun build(vsHlsl: String, format: VertexFormat?, stride: Int = VERTEX_STRIDE,
              cull: Boolean = false): String? {
        val entry = "SPIRV_Cross_Output main(SPIRV_Cross_Input stage_input)"
        if (!vsHlsl.contains(entry)) return null
        val fields = D3D12MeshVertexDecode.parseInputFields(vsHlsl)
        if (fields.isEmpty()) return null
        val renamed = vsHlsl.replace(entry, "SPIRV_Cross_Output vs_main(SPIRV_Cross_Input stage_input)")
        val cu = if (cull) resolveCullUniforms(renamed) ?: return null else null

        val decode = buildString {
            for (field in fields) {
                val element = format?.let { f -> runCatching { f.getElement(field.name) }.getOrNull() }
                when {
                    element != null ->
                        append(D3D12MeshVertexDecode.decodeField(field, element.format().name,
                            element.offset(), stride))
                    // 逐段属性：不在 28 B 顶点流里（属 slot 1），从 SectionDesc 填。
                    field.name.contains("ChunkPosition", ignoreCase = true) ->
                        appendLine("        vin.${field.name} = int3(s.chunkX, s.chunkY, s.chunkZ);")
                    field.name.contains("ChunkVisibility", ignoreCase = true) ->
                        appendLine("        vin.${field.name} = s.visibility;")
                    else ->
                        appendLine("        vin.${field.name} = (${field.type})0;  // 无匹配的顶点元素")
                }
            }
        }

        // 可选的 amplification shader：组大小 32 = payload 槽位数。
        val asBlock = if (cu == null) "" else """

// ===== amplification shader（6 向背面 + 视锥）：见 docs/mesh&vrs/README.md §2.5 =====
// 一个线程看一个 meshlet；存活的组号压缩进 128 B payload（32 槽 = 组大小），再整组 DispatchMesh。
// 两个 Array 用 groupshared 汇总，DispatchMesh 只由 0 号线程以外也必须同值调用 —— 这里在
// GroupMemoryBarrierWithGroupSync 之后所有线程读同一个 gPayloadCount，控制流统一。
struct MeshPayload { uint slot[32]; };
groupshared MeshPayload gPayload;
groupshared uint gPayloadCount;

[numthreads(32, 1, 1)]
void as_main(uint3 gid : SV_GroupID, uint3 tid : SV_GroupThreadID)
{
    if (tid.x == 0u) { gPayloadCount = 0u; }
    GroupMemoryBarrierWithGroupSync();

    uint local = gid.x * 32u + tid.x;
    bool visible = false;
    // gCullEnabled=0 时全放行（仍走 payload 压缩，只跳过两级剔除）。
    bool cullOn = gCullEnabled != 0u;
    if (local < gMeshletCount) {
        MeshletDesc m = loadMeshlet(local);
        SectionDesc s = loadSection(m.sectionId);
        uint dirMask = (m.packedMax >> 24) & 0xFFu;
        float3 localMin = float3(m.packedMin & 0xFFu, (m.packedMin >> 8) & 0xFFu,
                                 (m.packedMin >> 16) & 0xFFu) / 15.9375;
        float3 localMax = float3(m.packedMax & 0xFFu, (m.packedMax >> 8) & 0xFFu,
                                 (m.packedMax >> 16) & 0xFFu) / 15.9375;
        // 相机相对坐标：段的方块坐标 - CameraBlockPos + 局部 AABB + CameraOffset。
        float3 base = (float3(s.chunkX, s.chunkY, s.chunkZ) - float3(${cu.cameraBlockPos}))
                    + ${cu.cameraOffset};
        float3 mn = base + localMin;
        float3 mx = base + localMax;

        // 1) 6 向背面：相机在原点附近，AABB 落在哪个半空间就只可能看到对应朝向的面；留 1 方块
        //    余量抵消 CameraOffset 的小数部分。**bit 6（DIR_NON_AXIS）必须单独放行**：交叉模型
        //    （矮草、花）的面在 XZ 上转 45°，主轴 0.707 < AXIS_DOMINANCE(0.85)，只置
        //    DIR_NON_AXIS；不放行则平原草/花整片消失。
        const uint DIR_NON_AXIS = 64u;  // = D3D12MeshletBuilder.DIR_NON_AXIS (1 shl 6)
        uint visibleDirs = 0u;
        if (mn.x < 1.0) { visibleDirs |= 1u; }
        if (mx.x > -1.0) { visibleDirs |= 2u; }
        if (mn.y < 1.0) { visibleDirs |= 4u; }
        if (mx.y > -1.0) { visibleDirs |= 8u; }
        if (mn.z < 1.0) { visibleDirs |= 16u; }
        if (mx.z > -1.0) { visibleDirs |= 32u; }
        visible = !cullOn || ((dirMask & DIR_NON_AXIS) != 0u) || ((dirMask & visibleDirs) != 0u);

        // 2) 视锥：中心点进视空间再进裁剪空间，半径按投影的**列向量**换算。reversed-Z（近=1、远=0），
        //    JOML 的 m23 恒为 -1 ⇒ `clip.w = -z_view`，相机前方为**正**：W 只能取 clip.w，写成
        //    -clip.w 会把 64 格以外全部剔掉（§2.5.4）。
        if (visible && cullOn) {
            float3 centerRel = (mn + mx) * 0.5f;
            float radius = length((mx - mn) * 0.5f);
            float4 viewPos = mul(float4(centerRel, 1.0), ${cu.modelViewMat});
            float4 clip = mul(viewPos, ${cu.projMat});
            float4 p0 = ${cu.projMat}[0];
            float4 p1 = ${cu.projMat}[1];
            float4 p3 = ${cu.projMat}[3];
            float W = clip.w;
            // 平面函数与它的梯度必须配对：`clip.x + W` 的梯度是 col0 + col3，`W - clip.x` 是 col3 - col0。
            // 透视下 col0.xyz ⟂ col3.xyz，两者模长相同，但按对应关系写死才不会被将来的投影改动坑到。
            float rL = radius * length(p0.xyz + p3.xyz);
            float rR = radius * length(p0.xyz - p3.xyz);
            float rB = radius * length(p1.xyz + p3.xyz);
            float rT = radius * length(p1.xyz - p3.xyz);
            float rN = radius * length(p3.xyz);
            // 余量 64：允许相机后方 64 格以内（meshlet 半径 ≤ ~14）；调到 8 会剔掉地平线远 meshlet。
            const float slack = 64.0;
            visible = (clip.x + W >= -rL - slack) && (W - clip.x >= -rR - slack)
                   && (clip.y + W >= -rB - slack) && (W - clip.y >= -rT - slack)
                   && (W >= -rN - slack);
        }
    }
    if (visible) {
        uint at;
        InterlockedAdd(gPayloadCount, 1u, at);
        gPayload.slot[at] = local;
    }
    GroupMemoryBarrierWithGroupSync();
    DispatchMesh(gPayloadCount, 1u, 1u, gPayload);
}
"""
        val msPayloadParam = if (cu == null) "" else ",\n             in payload MeshPayload payload"
        val msMeshletId = if (cu == null) "gid.x" else "payload.slot[gid.x]"

        // 逐图元着色率只在解析出 CameraBlockPos/CameraOffset 时生成（距离用相机相对坐标）；不写 SV_ShadingRate 时组合器的 MIN 会把粗档位压回 1X1（安全失败）。
        val vrsPrimStruct = if (cu == null) "" else
            "struct SPIRV_Cross_Prim { uint shadingRate : SV_ShadingRate; };\n"
        val vrsPrimParam = if (cu == null) "" else
            ",\n             out primitives SPIRV_Cross_Prim prims[$MAX_PRIMITIVES]"
        val vrsRate = if (cu == null) "" else """
    // 按"段的盒子"而不是"meshlet 的盒子"定档：同段跨阈值会同屏出现 1X2/2X2，TAA 夹紧后细碎闪烁。
    float3 vrsBase = (float3(s.chunkX, s.chunkY, s.chunkZ) - float3(${cu.cameraBlockPos}))
                   + ${cu.cameraOffset};
    float3 vrsMin = vrsBase;
    float3 vrsMax = vrsBase + float3(16.0, 16.0, 16.0);
    // 用 AABB 的"近点"而不是中心：大块近处地形的中心可能很远，但它就在脚边。
    float vrsDistance = max(0.0, length((vrsMin + vrsMax) * 0.5) - length((vrsMax - vrsMin) * 0.5));
    uint primRate = 0u;  // 1X1
    if (gVrsBand.w > 0.5) {
        // 远带速率由 CPU 给（gVrsBand.z）：1 = 1X2，5 = 2X2；"关掉 2X2"只需把这里换成 1。
        uint farRate = (uint)(gVrsBand.z + 0.5);
        primRate = vrsDistance < gVrsBand.x ? 0u : (vrsDistance < gVrsBand.y ? 1u : farRate);
    }
        """
        val vrsStore = if (cu == null) "" else
            "        prims[vid * 2u].shadingRate = primRate;\n" +
                "        prims[vid * 2u + 1u].shadingRate = primRate;\n"

        return renamed + """

// ===== meshlet 包装（无索引缓冲）：32 B/条，与 D3D12MeshletBuilder.Meshlet.pack 一一对应，见 docs/mesh&vrs/README.md §2.5.3 =====
struct MeshletDesc {
    uint packedMin;          // R8 minXYZ + quadCount<<24
    uint packedMax;          // R8 maxXYZ + directionMask<<24（AS 用，MS 不读）
    uint sectionId;
    uint localVertexOffset;  // 段内顶点号（= firstQuad * 4）
    uint firstQuad;          // 段内 quad 号（调试）
    uint pad0;
    uint pad1;
    uint pad2;
};
// 26.3 的逐段数据（slot1 CHUNK_DATA_INSTANCED 的内容）由 CPU 抄进段表，MS 不必再绑 slot1。
struct SectionDesc {
    uint vertexBase;         // 该段在全局顶点池里的起始顶点号
    uint quadBase;           // 保留：全局 quad 起始（调试/分桶用）
    uint meshletBase;        // 该段在 meshlet 表里的起始下标
    uint meshletCount;
    int  chunkX;
    int  chunkY;
    int  chunkZ;
    float visibility;
};
// 段表 / meshlet 表都是 RAW SRV（ByteAddressBuffer，配 native 的 writeRawBufferSRV）：步长固定 32 B、由下面的 load* 手动解析（SRV 无 StructureByteStride）。
ByteAddressBuffer gSections : register(t15, space0);
ByteAddressBuffer gMeshlets : register(t16, space0);
ByteAddressBuffer meshVertices : register(t14, space0);
cbuffer MeshletGlobals : register(b8, space0) {
    uint gMeshletCount;
    // 0 = 关闭剔除（AS 全放行），1 = 开剔除。只改常量、不换 PSO。
    uint gCullEnabled;
    uint gMeshletGlobalsPad1;
    uint gMeshletGlobalsPad2;
    // 逐图元着色率的三档距离带（相机相对、单位方块）：x = 近带外沿（d < x ⇒ 1X1），
    // y = 中带外沿（x <= d < y ⇒ 1X2），更远 ⇒ 2X2；w = 1 启用（0 时恒 1X1，等价于不写语义）。
    float4 gVrsBand;
};

SectionDesc loadSection(uint id) {
    uint b = id * 32u;
    SectionDesc s;
    s.vertexBase = gSections.Load(b + 0u);
    s.quadBase = gSections.Load(b + 4u);
    s.meshletBase = gSections.Load(b + 8u);
    s.meshletCount = gSections.Load(b + 12u);
    s.chunkX = asint(gSections.Load(b + 16u));
    s.chunkY = asint(gSections.Load(b + 20u));
    s.chunkZ = asint(gSections.Load(b + 24u));
    s.visibility = asfloat(gSections.Load(b + 28u));
    return s;
}
MeshletDesc loadMeshlet(uint id) {
    uint b = id * 32u;
    MeshletDesc m;
    m.packedMin = gMeshlets.Load(b + 0u);
    m.packedMax = gMeshlets.Load(b + 4u);
    m.sectionId = gMeshlets.Load(b + 8u);
    m.localVertexOffset = gMeshlets.Load(b + 12u);
    m.firstQuad = gMeshlets.Load(b + 16u);
    m.pad0 = gMeshlets.Load(b + 20u);
    m.pad1 = gMeshlets.Load(b + 24u);
    m.pad2 = gMeshlets.Load(b + 28u);
    return m;
}

float4 meshReadF32(uint a) { return asfloat(meshVertices.Load4(a)); }
float4 meshReadUNorm8(uint a) {
    uint w = meshVertices.Load(a);
    return float4(w & 255u, (w >> 8) & 255u, (w >> 16) & 255u, (w >> 24) & 255u) / 255.0;
}
float4 meshReadUNorm16(uint a) {
    uint2 w = meshVertices.Load2(a);
    return float4(w.x & 65535u, w.x >> 16, w.y & 65535u, w.y >> 16) / 65535.0;
}
float4 meshReadUNorm32(uint a) { return float4(meshVertices.Load4(a)); }
float4 meshReadSNorm8(uint a) {
    uint w = meshVertices.Load(a);
    int4 s = int4(int(w << 24) >> 24, int(w << 16) >> 24, int(w << 8) >> 24, int(w) >> 24);
    return max(float4(s) / 127.0, -1.0);
}
float4 meshReadSNorm16(uint a) {
    uint2 w = meshVertices.Load2(a);
    int4 s = int4(int(w.x << 16) >> 16, int(w.x) >> 16, int(w.y << 16) >> 16, int(w.y) >> 16);
    return max(float4(s) / 32767.0, -1.0);
}
int4 meshReadSInt8(uint a) {
    uint w = meshVertices.Load(a);
    return int4(int(w << 24) >> 24, int(w << 16) >> 24, int(w << 8) >> 24, int(w) >> 24);
}
int4 meshReadSInt16(uint a) {
    uint2 w = meshVertices.Load2(a);
    return int4(int(w.x << 16) >> 16, int(w.x) >> 16, int(w.y << 16) >> 16, int(w.y) >> 16);
}
int4 meshReadSInt32(uint a) { return int4(meshVertices.Load4(a)); }
uint4 meshReadUInt8(uint a) {
    uint w = meshVertices.Load(a);
    return uint4(w & 255u, (w >> 8) & 255u, (w >> 16) & 255u, (w >> 24) & 255u);
}
uint4 meshReadUInt16(uint a) {
    uint2 w = meshVertices.Load2(a);
    return uint4(w.x & 65535u, w.x >> 16, w.y & 65535u, w.y >> 16);
}
uint4 meshReadUInt32(uint a) { return meshVertices.Load4(a); }
$asBlock$vrsPrimStruct
// 线程组 = 一个 meshlet：前 quadCount*4 线程各解一个顶点，前 quadCount 线程算术写出一个 quad 的两个三角形；不读索引，带 AS 时 meshlet 号来自 payload。
[numthreads($THREADS, 1, 1)]
[outputtopology("triangle")]
void ms_main(uint3 gid : SV_GroupID, uint3 tid : SV_GroupThreadID$msPayloadParam,
             out vertices SPIRV_Cross_Output verts[$THREADS]$vrsPrimParam,
             out indices uint3 tris[$MAX_PRIMITIVES])
{
    MeshletDesc m = loadMeshlet($msMeshletId);
    SectionDesc s = loadSection(m.sectionId);
    uint quadCount = (m.packedMin >> 24) & 0xFFu;
    uint vertexCount = quadCount * 4u;
    uint primCount = quadCount * 2u;
    // 必须在统一控制流里调用（所有线程同一个值）。
    SetMeshOutputCounts(vertexCount, primCount);
$vrsRate
    uint vid = tid.x;
    if (vid < vertexCount) {
        uint vertexIndex = s.vertexBase + m.localVertexOffset + vid;
        SPIRV_Cross_Input vin = (SPIRV_Cross_Input)0;
$decode        verts[vid] = vs_main(vin);
    }

    // 算术索引：MC 的每个 quad 固定 4 个顶点、绕序 (0,1,2) / (0,2,3)。
    if (vid < quadCount) {
        uint baseV = vid * 4u;
        tris[vid * 2u] = uint3(baseV, baseV + 1u, baseV + 2u);
        tris[vid * 2u + 1u] = uint3(baseV, baseV + 2u, baseV + 3u);
$vrsStore    }
}
"""
    }

    /** VS 里 AS 需要的四个 uniform 名（由 spvc 生成，名字随版本可能带前缀）。 */
    private data class CullUniforms(val cameraBlockPos: String, val cameraOffset: String,
                                    val modelViewMat: String, val projMat: String)

    /**
     * 从 VS 源码解析 AS 需要的 uniform；缺任何一个返回 null（判据全 0 的 AS 会把地形成片剔掉）。
     */
    private fun resolveCullUniforms(hlsl: String): CullUniforms? {
        val cbp = findUniform(hlsl, "CameraBlockPos") ?: return null
        val coff = findUniform(hlsl, "CameraOffset") ?: return null
        val mvm = findUniform(hlsl, "ModelViewMat", requireMatrix = true) ?: return null
        val proj = findUniform(hlsl, "ProjMat", requireMatrix = true) ?: return null
        return CullUniforms(cbp, coff, mvm, proj)
    }

    /** 找成员名：先按名字精确/前缀匹配，矩阵还要求前面确实是 `float4x4` 声明。 */
    private fun findUniform(hlsl: String, needle: String, requireMatrix: Boolean = false): String? {
        val pattern = if (requireMatrix) {
            Regex("float4x4\\s+([A-Za-z_][A-Za-z0-9_]*" + Regex.escape(needle) + "[A-Za-z0-9_]*)")
        } else {
            Regex("\\b([A-Za-z_][A-Za-z0-9_]*" + Regex.escape(needle) + "[A-Za-z0-9_]*)\\b")
        }
        return pattern.find(hlsl)?.groupValues?.get(1)
    }
}
