package dev.nts.expdxr.d3d12.render.advanced.mesh

import com.mojang.renderpearl.api.vertex.VertexFormat

/**
 * P217：**meshlet 版地形 MS/AS 生成器**。
 *
 * <p>每条 `DispatchMesh` 的线程组 = 一个 meshlet（16 quad / 64 顶点 / 32 三角形），
 * 索引由 `SV_GroupThreadID` **算术展开**，完全不读索引缓冲。可选的 `as_main` 在
 * amplification 阶段做 6 向背面掩码 + 视锥剔除，剔除结果经 payload 压缩后再派发 MS。
 * 旧的 P128/P137 段表包装（读索引缓冲的 126 索引/组方案）已随重构删除。
 *
 * <p>数据契约（与 `D3D12MeshletBuilder` 的打包布局一一对应）：
 * - `t14` 顶点池（`ByteAddressBuffer`，28 B/顶点，`DefaultVertexFormat.BLOCK`）；
 * - `t15` `StructuredBuffer<SectionDesc>`，32 B/段；
 * - `t16` `StructuredBuffer<MeshletDesc>`，32 B/meshlet；
 * - `b8` 小常量（meshlet 总数，留给 AS/调试；MS 本身按 `SV_GroupID` 取）。
 *
 * <p>**26.3 的区块坐标不在 28 B 顶点里**：`ChunkPosition`（RGB32_SINT）与
 * `ChunkVisibility`（R32_FLOAT）属于 slot 1 的 `CHUNK_DATA_INSTANCED`（16 B/段）。这里把它们
 * 放进 `SectionDesc`，MS 直接写进 `SPIRV_Cross_Input` 的对应字段 —— 不用再绑一条顶点流。
 *
 * <p>矩阵约定：生成的 VS 由 spvc 产出、`row_major` + `mul(float4, M)`（行向量右乘）。本文件
 * 不改 VS 的入口，只把 `main` 改名 `vs_main` 后调用它，所以约定天然一致 —— **不要**在 MS 里
 * 自己写 `mul(M, v)`（那是方案伪代码的错误写法，见 known_bugs P217 §三）。
 */
object D3D12MeshletShader {

    /** 一个 meshlet 的线程数 = 最多顶点数（方案 §二：16 quad × 4 顶点）。 */
    const val THREADS = 64

    /** 每组最多图元数（16 quad × 2 三角形）。 */
    const val MAX_PRIMITIVES = 32

    /** 单顶点字节数（`DefaultVertexFormat.BLOCK`）。 */
    const val VERTEX_STRIDE = 28

    /**
     * 生成 MS 源码；`vsHlsl` 必须是 spvc 产出的地形 VS（含 `SPIRV_Cross_Input` 与 `main`）。
     *
     * @param cull 为 true 时额外生成 `as_main`（6 向背面 + 视锥剔除）并让 MS 从 payload 取
     *   meshlet 号；此时要求 VS 里能解析出 `CameraBlockPos`/`CameraOffset`/`ModelViewMat`/`ProjMat`
     *   四个 uniform（地形管线一定有），解析不到则返回 null，调用方退回不剔除的版本。
     * @return null 表示输入不是期望的形态，调用方应回退。
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
                    // 26.3 的逐段属性：不在 28 B 顶点流里，从 SectionDesc 填。
                    field.name.contains("ChunkPosition", ignoreCase = true) ->
                        appendLine("        vin.${field.name} = int3(s.chunkX, s.chunkY, s.chunkZ);")
                    field.name.contains("ChunkVisibility", ignoreCase = true) ->
                        appendLine("        vin.${field.name} = s.visibility;")
                    else ->
                        appendLine("        vin.${field.name} = (${field.type})0;  // 无匹配的顶点元素")
                }
            }
        }

        // P217：可选的 amplification shader（6 向背面 + 视锥）。组大小 32 = payload 槽位数。
        val asBlock = if (cu == null) "" else """

// ===================== P217：amplification shader（6 向背面 + 视锥） =====================
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
    // P217 同会话 A/B：gCullEnabled=0 时全放行（仍走 payload 压缩，只跳过两级剔除）。
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

        // 1) 6 向背面：相机在原点附近，AABB 落在哪个半空间就只可能看到对应朝向的面。
        //    留 1 方块余量抵消 CameraOffset 的小数部分。
        //
        //    P227：**bit 6（DIR_NON_AXIS）必须单独放行**。交叉模型（矮草、高草、花、树苗、火把……）
        //    的四个面在 XZ 上转 45°，法线主轴占比 ~0.707 < AXIS_DOMINANCE(0.85)，builder 会把它们的
        //    方向记成 DIR_NON_AXIS 并注明"不能做背面剔除"；但 visibleDirs 只会置 1/2/4/8/16/32 这几位，
        //    于是"整段都是草"的 meshlet（dirMask 只有 64）在任何视角下都 `dirMask & visibleDirs == 0`
        //    ⇒ 开 `[mesh] terrainCull` 时平原上的草/花整片消失。交叉面是双面材质，本来就没有"背面"。
        const uint DIR_NON_AXIS = 64u;  // = D3D12MeshletBuilder.DIR_NON_AXIS (1 shl 6)
        uint visibleDirs = 0u;
        if (mn.x < 1.0) { visibleDirs |= 1u; }
        if (mx.x > -1.0) { visibleDirs |= 2u; }
        if (mn.y < 1.0) { visibleDirs |= 4u; }
        if (mx.y > -1.0) { visibleDirs |= 8u; }
        if (mn.z < 1.0) { visibleDirs |= 16u; }
        if (mx.z > -1.0) { visibleDirs |= 32u; }
        visible = !cullOn || ((dirMask & DIR_NON_AXIS) != 0u) || ((dirMask & visibleDirs) != 0u);

        // 2) 视锥：中心点进视空间再进裁剪空间，半径按投影的**列向量**换算。
        //
        //    P225 修正符号：本项目投影由 `Projection.getMatrix()`（26.3）把 near/far **对调**后
        //    交给 JOML `setPerspective(..., zZeroToOne=true)` ⇒ **reversed-Z**（近平面=1、远平面=0），
        //    且 JOML 的 m23 恒为 **-1** ⇒ `clip.w = -z_view`，相机前方是**正**的（= 距离）。
        //    旧代码写 `W = -clip.w`（并注释"本项目 w 在相机前方是负的"），符号整体反了：
        //    远平面判据变成 `W >= -rN-64` ⇒ `距离 <= 64+0.05r` ⇒ **开剔除就把 64 格以外全部扔掉**。
        //    旁证：vanilla `oit_common.glsl` 的 `deviceToLinearDepth = ProjMat[3][2]/(d+ProjMat[2][2])`
        //    只有在 m23 = -1 时才返回**正的**相机距离（否则近/远符号自相矛盾），而 OIT 是能用的。
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
            // 余量保持 64（P217 §十）：`W >= -rN - 64` 现在的含义是"允许相机后方 64 格以内"
            // （一个 meshlet 的半径 ≤ ~14 格，所以跨过相机的那些不会被误剔）；侧面同理有余量，
            // 早先 8 会把地平线一带的远 meshlet 剔掉，别再调小。
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

        return renamed + """

// ===================== P217：meshlet 包装（无索引缓冲） =====================
// 每条记录 32 B；布局与 D3D12MeshletBuilder.Meshlet.pack 一一对应。
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
// 段表 / meshlet 表都用 RAW SRV（ByteAddressBuffer），与 native 的 writeRawBufferSRV 配套；
// 结构体步长固定 32 B，由下面的 load* 手动解析（SRV 不带 StructureByteStride）。
ByteAddressBuffer gSections : register(t15, space0);
ByteAddressBuffer gMeshlets : register(t16, space0);
ByteAddressBuffer meshVertices : register(t14, space0);
cbuffer MeshletGlobals : register(b8, space0) {
    uint gMeshletCount;
    // P217 同会话 A/B：0 = 关闭剔除（AS 全放行），1 = 开剔除。只改常量、不换 PSO。
    uint gCullEnabled;
    uint gMeshletGlobalsPad1;
    uint gMeshletGlobalsPad2;
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
$asBlock
// 线程组 = 一个 meshlet：前 quadCount*4 线程各解一个顶点，前 quadCount 线程算术写出 1 个 quad 的
// 两个三角形。没有任何索引读；带 AS 时 meshlet 号来自 payload。
[numthreads($THREADS, 1, 1)]
[outputtopology("triangle")]
void ms_main(uint3 gid : SV_GroupID, uint3 tid : SV_GroupThreadID$msPayloadParam,
             out vertices SPIRV_Cross_Output verts[$THREADS],
             out indices uint3 tris[$MAX_PRIMITIVES])
{
    MeshletDesc m = loadMeshlet($msMeshletId);
    SectionDesc s = loadSection(m.sectionId);
    uint quadCount = (m.packedMin >> 24) & 0xFFu;
    uint vertexCount = quadCount * 4u;
    uint primCount = quadCount * 2u;
    // 必须在统一控制流里调用（所有线程同一个值）。
    SetMeshOutputCounts(vertexCount, primCount);

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
    }
}
"""
    }

    /** VS 里 AS 需要的四个 uniform 名（由 spvc 生成，名字随版本可能带前缀）。 */
    private data class CullUniforms(val cameraBlockPos: String, val cameraOffset: String,
                                    val modelViewMat: String, val projMat: String)

    /**
     * 从 VS 源码里解析 AS 需要的 uniform。缺任何一个都返回 null —— 调用方据此退回不剔除版本，
     * 绝不生成一个"剔除判据里全是 0"的 AS（那会把地形成片剔掉）。
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
