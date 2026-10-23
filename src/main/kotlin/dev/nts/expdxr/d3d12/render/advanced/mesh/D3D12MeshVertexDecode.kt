package dev.nts.expdxr.d3d12.render.advanced.mesh

import com.mojang.renderpearl.api.vertex.VertexFormat

/**
 * P128（mesh Step 3 后半第 1 项）：**顶点解码生成器**。
 *
 * <p>地形 VS 由 spvc 生成、形如 `SPIRV_Cross_Output main(SPIRV_Cross_Input stage_input)`。
 * mesh shader 不能走输入装配，必须自己按顶点格式把原始字节解成 `SPIRV_Cross_Input` 的字段，
 * 再对每个顶点调用 `vs_main`。这里就是"按 `VertexFormat` 生成解码赋值"的那一半：
 * 只做代码生成（纯函数、可在任何线程跑），不碰 GPU。
 *
 * <p>P135：地形 VS 还带一个**逐区块**的 `cbuffer ChunkSection`（模型视图矩阵 + 区块原点）。
 * mesh 路径一条 `DispatchMesh` 覆盖 343 个区块，没有"每段一次绘制"来切 CBV，所以把这个
 * cbuffer 换成 `ByteAddressBuffer meshSections : register(t16)`，按 `gSegBase` 取本段那 96 字节，
 * 并用宏把原成员名映射到读取表达式 —— `vs_main` 一行都不用改。
 *
 * <p>格式来自 `VertexFormatElement(name, offset, GpuFormat)`；`GpuFormat` 是
 * `RGBA8_UNORM` / `RG16_UINT` / `RGB32_FLOAT` 这类枚举名，直接按
 * 分量数 + 位宽 + 数值种类解析，避免维护一张上百项的映射表。
 */
object D3D12MeshVertexDecode {
    /**
     * P137：一个 thread group 最多处理的索引数。
     *
     * <p>两条硬上限同时压着这个值：
     * 1. **mesh shader 的线程组上限是 128**（不是 compute 的 1024）。实测 dxc 的校验器直接
     *    拒绝 252：`Declared Thread Group X size 252 outside valid range [1..128]`（P137）。
     * 2. D3D12 每组上限 256 顶点 / 256 图元，而地形一段有 1536 个索引（QUADS，512 三角形）——
     *    一次 `SetMeshOutputCounts(1536, 512)` 根本发不出去。
     *
     * <p>所以一段切成 ceil(1536/126)=13 组，每组 126 个索引（42 三角形）。126 是 3 的倍数，
     * 保证切出来的每一片都是整三角形。**段表构建必须用同一个常量**。
     */
    const val MAX_INDICES_PER_GROUP = 126

    /**
     * P140：一个 amplification shader 线程组看多少个 meshlet。
     *
     * <p>本方案的 meshlet 就是 [MAX_INDICES_PER_GROUP] 切出来的那一片（≤126 顶点 / 42 三角形），
     * 所以这里同时是"一个 AS 组覆盖的段表项数"。AS 的线程组只有 X 方向一个维度，
     * `DispatchMesh(ceil(组数/128), 1, 1)` 覆盖全部。
     */
    const val MAX_MESHLETS_PER_AS_GROUP = 128

    /**
     * P141：`bounds_main` 一次 dispatch 覆盖的段表项数（= 一块 32 KB 的 b8 数组）。
     *
     * <p>compute 侧的段表是**静态数组** `MeshSegment meshSegments[N]`：根 CBV 没有大小，覆盖
     * 范围只能靠声明钉死，所以这里是一个常量，JVM 侧按它分批 dispatch —— `D3D12MeshBuildBounds`
     * 的 `segmentsOffset` / `boundsOffset` / `groupCount` 三个参数正好够用。
     *
     * <p>**不要拿它当 DrawMesh 的批大小**（那是 `MESH_GROUPS_PER_BATCH`，数值同为 1024 但职责
     * 不同）。续四的 +x~+z 象限闪烁就是把它当成了批大小：一次只算了段表前 1024 组，后面的组读到
     * 全 0 的包围球（球心在世界原点、半径 0）→ 只要世界原点不在画面上就被整片剔掉，而可见性又
     * 逐帧在 mesh / IMR 之间跳，看起来就是一片地形在闪。
     */
    const val MAX_BOUNDS_GROUPS = 1024

    /**
     * P140：第一版参与的视锥平面数 —— **只取 4 个侧面**。
     *
     * <p>近/远平面的提取公式取决于投影是 GL（z∈[-w,w]）还是 D3D（z∈[0,w]）约定，
     * 本项目这条链路没验证过；侧面平面（左右上下）两种约定完全相同，所以先只信这 4 个。
     */
    const val CULL_PLANES_SIDES = 4

    /** P138：VS→PS 插值字段名（`ChunkVisibility` 的载体）。 */
    const val VISIBILITY_FIELD = "chunkVisibility"

    /** P138：`ChunkVisibility` 在 96 字节段数据里的偏移（`chunksection.glsl`：mat4 之后）。 */
    const val VISIBILITY_OFFSET = 64

    /** `SPIRV_Cross_Input` 的一个字段（HLSL 类型 + 名字）。 */
    data class Field(val type: String, val name: String)

    /**
     * 把 [vsHlsl] 包装成 mesh shader 源码：入口改名 `vs_main`，追加取段表 + 顶点/索引缓冲的
     * `ms_main`。`format` 为 null 或某字段找不到对应元素时，该字段解码为 0（并在注释里标出）。
     *
     * `visibilitySemantic` 非空时，`SPIRV_Cross_Output` 会多一个
     * `float chunkVisibility : <semantic>` 字段，并在 `ms_main` 里按本段 96 字节的 offset 64 写入
     * （配合 [addVisibilityInterpolant] 打过补丁的 PS，逐段淡入就不再依赖逐 draw 的 CBV）。
     *
     * `coneCull` 打开 P141 B 阶段的背面法线锥剔除：AS 在自算包围球的同时逐三角形算法线，
     * 整个锥都背向相机时把这个 meshlet 一起剔掉（连顶点解码都省了）。只对**光栅化也剔背面**
     * 的管线有意义，所以调用方要把它和 `CullMode BACK` 绑在一起；锥要用逐顶点数据，因此只
     * 在 AS 自算包围球的分支里生效（包围球表那条路没有顶点数据）。
     */
    fun buildMeshHlsl(vsHlsl: String, format: VertexFormat?, threads: Int = MAX_INDICES_PER_GROUP,
        visibilitySemantic: String? = null, meshletCull: Boolean = false,
        cullPlanes: Int = CULL_PLANES_SIDES, coneCull: Boolean = false): String {
        val fields = parseInputFields(vsHlsl)
        val stride = format?.vertexSize ?: 0
        val decode = StringBuilder()
        for (field in fields) {
            val element = format?.let { f -> runCatching { f.getElement(field.name) }.getOrNull() }
            if (element == null || stride <= 0) {
                decode.append("        vin.${field.name} = (${field.type})0;  // 无匹配的顶点元素\n")
                continue
            }
            decode.append(decodeField(field, element.format().name, element.offset(), stride))
        }
        var renamed = vsHlsl.replace(
            "SPIRV_Cross_Output main(SPIRV_Cross_Input stage_input)",
            "SPIRV_Cross_Output vs_main(SPIRV_Cross_Input stage_input)"
        )
        // P135：逐区块 cbuffer → ByteAddressBuffer + 成员宏（宏在 gSegBase 之上取值）。
        // 只认 26.2 的布局：`mat4 ModelViewMat`(0..63) + `float ChunkVisibility`(64)，也就是
        // native 那 96 字节段记录的布局。26.3 的地形改用 `MULTIDRAW_TERRAIN` 逐顶点属性后，
        // 非 multidraw 管线里的 `ChunkSection` 只剩 `{ ivec3 ChunkPosition; float ChunkVisibility }`
        // —— 偏移对不上，硬套宏会把 ModelViewMat 的 float 当 ivec3 读，表现就是"区块内容正常、
        // 但位置错乱，转视角/移动时到处乱跳"。这种布局直接放弃替换，交给原版 cbuffer 绑定。
        val sectionMembers = parseCBuffer(renamed, "ChunkSection")
        val section = sectionMembers?.takeIf { members ->
            members.any { it.type.endsWith("4x4") && it.offset == 0 }
        }
        if (section != null) {
            renamed = stripCBuffer(renamed, "ChunkSection")
        }
        val sectionDecl = if (section == null) "" else buildSectionAccess(section)
        // P137：一个 thread group 只处理段里的一"片"（≤126 索引），所以段号来自段表项，
        // 而不是 group id —— 同一段会被多个 group 分摊。
        val sectionSet = if (section == null) "" else "    gSegBase = seg.sectionIndex * 96u;"
        // P138：逐段淡入系数（`ChunkVisibility`，offset 64）走 VS→PS 插值，PS 不再读逐 draw 的 CBV。
        // 26.3：`MULTIDRAW_TERRAIN` 地形把 `ChunkVisibility` 变成顶点属性（location 5），
        // VS 自己算 `out chunkVisibility` → `SPIRV_Cross_Output` 里**已经有**这个字段。
        // 这时若仍然按老路数写 `verts[vid].chunkVisibility = meshSectionF(64)`，就会用段表里
        // 的陈旧/零值覆盖原版算好的逐顶点淡入（表现为远景整片被雾色吃掉），所以直接交给原版。
        val vanillaOwnsVisibility = hasOutputField(renamed, "SPIRV_Cross_Output", VISIBILITY_FIELD)
        val visibilityField = if (section != null && visibilitySemantic != null && !vanillaOwnsVisibility) {
            addInterpolant(renamed, "SPIRV_Cross_Output", VISIBILITY_FIELD, visibilitySemantic)
        } else {
            null
        }
        if (visibilityField != null) {
            renamed = visibilityField
        }
        val visibilityWrite = if (visibilityField != null) {
            // 直接按段偏移读 t16：VS 的 cbuffer 里往往没有 `ChunkVisibility`
            // （spvc 只声明 VS 用到的成员，淡入系数只在 PS 用），所以不能用那个宏。
            "    verts[vid].$VISIBILITY_FIELD = meshSectionF(${VISIBILITY_OFFSET}u);" +
                "  // P138：逐段淡入系数\n"
        } else {
            ""
        }
        // 宏必须先于使用处定义，所以 section 声明拼在 VS 源码**之前**。
        // P140：AS 剔除只对"有 ChunkSection（能拿到 ModelViewMat）且 VS 里带 Projection
        // 矩阵"的管线启用 —— 视锥平面直接由 HLSL 从 Projection cbuffer 取，CPU 侧不提供矩阵
        // （`D3D12Temporal.currentProjection()` 只在 temporal 路径的 UBO 写拦截里有值，普通
        // 绘制时是 null；见 P140 实测 3583 次 "no level projection"）。
        // P140：AS 剔除只要求"VS 里带 Projection 矩阵"（视锥平面由 HLSL 从 Projection cbuffer 现算）。
        // 26.2 这里还要求 `section != null`（逐区块 cbuffer）当作"这是地形管线"的判据，但 26.3 的
        // 地形改成 `MULTIDRAW_TERRAIN` 顶点属性路线（`ChunkPosition`/`ChunkVisibility` 变 location
        // 4/5 的 in 变量），terrain 的 HLSL 里**不再有** `ChunkSection` cbuffer —— 沿用旧判据会让
        // 地形永远拿不到 AS（`as_main` 不生成 → dxc "missing entry point definition"）。
        // 是否地形已由调用方通过 `meshletCull` 决定，这里只补 Projection 这个硬前提。
        val projectionMember = if (meshletCull) {
            parseCBuffer(renamed, "Projection")?.firstOrNull { it.type.endsWith("4x4") }?.name
        } else {
            null
        }
        val cullActive = meshletCull && projectionMember != null
        val asThreads = MAX_MESHLETS_PER_AS_GROUP
        val cullDecl = if (!cullActive) "" else """
// P140：meshlet 包围球表（16 字节/组，与段表同序）：{ float3 模型空间球心; float 半径 }
ByteAddressBuffer meshletBounds : register(t17, space0);
// P140：本批在 t17/段表里的基址与组数（root 32 位常量，由 native 每批重设，dword 24/25）。
// 前 24 个 dword（frustumPlanes[6]）留作占位：视锥平面改由下面的 Projection 矩阵现算，
// 不再从 CPU 传，签名保持不动免得动 native（P140）。
cbuffer MeshCull : register(b9, space0) {
    float4 frustumPlanes[6];
    uint meshletBase;
    uint meshletCount;
    // meshCullPad.x = 包围球来源：0 = 读 t17 包围球表；1 = AS 从 t14 顶点流自算（t17 只是占位）。
    uint2 meshCullPad;
};
// P140：AS 压缩出来的存活组序号表（count 在首位，payload 在 AS → MS 之间传递）。
struct MeshPayload {
    uint slot[$asThreads];
};
// P140：`groupshared` 只能声明在**全局**作用域（放函数体会报
// "'groupshared' is not a valid modifier for a local variable"）。
groupshared MeshPayload gMeshPayload;
// P140：计数器单独放一个 groupshared uint —— 实测把 `count` 放进 groupshared struct 里再对它做
// `InterlockedAdd`，压缩出来的组数会不对（画面出现成片空洞）；挪出来就正常。
groupshared uint gPayloadCount;
"""
        // P141 B 阶段：背面法线锥剔除。锥要靠逐顶点数据才建得起来，所以只在 AS 自算包围球的
        // 分支里生成（包围球表那条路没有顶点数据）；`coneCull` 由调用方和 CullMode BACK 绑在一起。
        val coneActive = cullActive && coneCull
        val coneDecl = if (!coneActive) "" else """
        // P141 B 阶段：背面法线锥。逐三角形算视空间法线，锥轴取第一个非退化三角形的法线，
        // coneMin 是其余三角形法线与锥轴点积的最小值 —— 整个 meshlet 的法线都落在半角为
        // acos(coneMin) 的锥里。
        float3 coneAxis = float3(0.0, 0.0, 0.0);
        float coneMin = 1.0;
        bool coneValid = false;
        float3 conePrev1 = float3(0.0, 0.0, 0.0);
        float3 conePrev2 = float3(0.0, 0.0, 0.0);
"""
        val coneSample = if (!coneActive) "" else """
                // 索引流是三角形列表，只有 k % 3 == 2 才凑齐一个三角形；拿相邻三角形的两个
                // 顶点去算叉积得到的是退化面，会污染锥。
                if ((k % 3u) == 2u) {
                    float3 fn = cross(conePrev1 - conePrev2, vp - conePrev2);
                    float flen = length(fn);
                    if (flen > 1e-6) {
                        float3 nrm = fn / flen;
                        if (!coneValid) {
                            coneAxis = nrm;
                            coneValid = true;
                        } else {
                            coneMin = min(coneMin, dot(nrm, coneAxis));
                        }
                    }
                }
                conePrev2 = conePrev1;
                conePrev1 = vp;
"""
        val coneTest = if (!coneActive) "" else """
        // P141 B 阶段：整锥背向相机就把这个 meshlet 剔掉。视空间里相机在原点，球心方向就是
        // 视线方向；判据是保守的 —— 只有锥的最外侧（coneMin 侧多转一个半角）仍背向相机才算，
        // 这样被剔掉的三角形在光栅化时本来也会被 CullMode BACK 剔掉，画面不变。
        // kConeSign 是绕序符号：视空间叉积法线对应"正面"还是"背面"取决于投影的手性。符号错了
        // 的现象是近景地形成片破洞/消失。+1.0 已在实机上验过（p223：8 分钟正常游玩，用户确认
        // 地形正常、无破洞），不要再随手改。
        if (visible && coneValid && coneMin > 0.0) {
            const float kConeSign = 1.0;
            float eyeLen = length(center);
            if (eyeLen > 1.0) {
                float facing = dot(coneAxis, center / eyeLen);
                float sinTheta = sqrt(max(0.0, 1.0 - coneMin * coneMin));
                if (kConeSign * coneMin * facing - sinTheta > 0.0) {
                    visible = false;
                }
            }
        }
"""
        val asDecl = if (!cullActive) "" else """
// P140：amplification shader —— 每个线程看一个 meshlet，剔除后把存活组的序号压缩进 groupshared
// payload，最后整组一起 DispatchMesh(count)。被剔除的组连 mesh shader 都不会启动。
// 注意：`DispatchMesh` 必须在**统一控制流**里调用，放进 `if (tid.x == 0)` 会被校验器拒绝
// （`Non-Dominating DispatchMesh call`，P140 实测）。
[numthreads($asThreads, 1, 1)]
void as_main(uint3 gid : SV_GroupID, uint3 tid : SV_GroupThreadID)
{
    if (tid.x == 0u) {
        gPayloadCount = 0u;
    }
    GroupMemoryBarrierWithGroupSync();

    uint local = gid.x * ${asThreads}u + tid.x;
    bool visible = false;
    if (local < meshletCount) {
        MeshSegment seg = meshSegments[local];
        // std140 的 mat4 是 4 个列向量：M*v = c0*v.x + c1*v.y + c2*v.z + c3（GLSL 语义）。
        // **不要**用 `gSegBase` + `meshSectionL4()`：那个 static 与 ms_main 共享，AS 与 MS 的
        // 线程组会互相踩（P140 实测：整幅地形被换成别的段的矩阵，画面看起来像"雪原"）。
        // 这里按本段基址直接读 t16。
        uint asBase = seg.sectionIndex * 96u;
        float4 c0 = asfloat(meshSections.Load4(asBase + 0u));
        float4 c1 = asfloat(meshSections.Load4(asBase + 16u));
        float4 c2 = asfloat(meshSections.Load4(asBase + 32u));
        float4 c3 = asfloat(meshSections.Load4(asBase + 48u));
        float3 center;
        float radius;
${coneDecl}        if (meshCullPad.x == 0u) {
            // 有 CPU 侧包围球表（t17）：球心是模型空间坐标，用 ModelViewMat 的列向量变换到视空间。
            float4 bound = meshletBounds.Load4((local + meshletBase) * 16u);
            center = (c0 * bound.x + c1 * bound.y + c2 * bound.z + c3).xyz;
            radius = bound.w;
        } else {
            // 没有 CPU 表时，按 meshlet 逐顶点算 AABB 包围球（模型空间），再变换到视空间。
            float3 mn = float3(3.4e38, 3.4e38, 3.4e38);
            float3 mx = float3(-3.4e38, -3.4e38, -3.4e38);
            // P141：并行上下文里循环上限必须是常量或已验证的界，否则一旦段表读到垃圾
    // （UPLOAD 堆上的 CBV 越界只会返回 0，但 indexCount 可能是个天文数字）compute 会
    // 跑到 TDR —— 实测直接把设备打掉 0x887A0005。
    const uint kMaxIndices = 126u;
    uint count = min(seg.indexCount, kMaxIndices);
    [loop] for (uint k = 0u; k < count; ++k) {
                uint istart = (seg.firstIndex + k) * seg.indexStride;
                uint idx;
                if (seg.indexStride == 2u) {
                    uint w = meshIndices.Load(istart & ~3u);
                    idx = ((istart & 2u) == 0u) ? (w & 65535u) : (w >> 16);
                } else {
                    idx = meshIndices.Load(istart);
                }
                uint va = (idx + uint(seg.baseVertex)) * ${stride}u;
                float3 mp = asfloat(uint3(meshVertices.Load(va),
                                          meshVertices.Load(va + 4u),
                                          meshVertices.Load(va + 8u)));
                float3 vp = (c0 * mp.x + c1 * mp.y + c2 * mp.z + c3).xyz;
                mn = min(mn, vp);
                mx = max(mx, vp);
${coneSample}            }
            center = (mn + mx) * 0.5f;
            radius = length(mx - center);
        }
        // 投影矩阵就在同一份源码的 cbuffer 里（VS 已声明、根签名已按同寄存器绑定），CPU 不必提供。
        // 变换写法必须与 VS 一致：spvc 把 mat4 声明成 `row_major`，VS 用 `mul(float4, M)`（行向量右乘），
        // 等价于 GLSL 的 `M * v`；这里同样用 `mul(float4(center, 1.0), P)`。
        float4 pcol0 = $projectionMember[0];
        float4 pcol1 = $projectionMember[1];
        float4 pcol3 = $projectionMember[3];
        // 变换写法与 VS 一致：spvc 把 mat4 声明成 `row_major`、VS 用 `mul(float4, M)`（行向量右乘），
        // 这里同样用 `mul(float4(center, 1.0), P)`。实测 `mul(P, v)` 偏差更大，不要换。
        float4 clip = mul(float4(center, 1.0), $projectionMember);
        // **本引擎的投影 w 在相机前方是负的**（P141 实测：`clip.w < 0` 才等于"在镜头前"，
        // 用 `clip.w > 0` 会把近景成片剔掉）。所以取 W = -clip.w 作为正的深度来写视锥判据：
        //   左: x + W >= 0，法线 = P0 - P3；右: W - x >= 0，法线 = -(P0 + P3)；
        //   下/上同理用 P1；近: W >= 0，法线 = P3。
        // 球体的允许量按各自平面法线长度放大，否则透视边缘会被误剔。
        float W = -clip.w;
        float rA = radius * length(pcol0.xyz - pcol3.xyz);
        float rB = radius * length(pcol0.xyz + pcol3.xyz);
        float rC = radius * length(pcol1.xyz - pcol3.xyz);
        float rD = radius * length(pcol1.xyz + pcol3.xyz);
        float rN = radius * length(pcol3.xyz);
        // **保守余量**：这套 clip 坐标与顶点路径的比例并不完全一致（实测远处偏差随距离增长，
        // 约 50 个世界单位量级），所以判据留 64 单位的余量。留余量后与 IMR 逐像素一致
        // （P141 验收：cull vs IMR mean|Δ| 0.36，同模式两帧噪声 0.40），代价是剔得更保守。
        // 想做严格的视锥剔除，正确做法是让宿主提供投影矩阵（或标定这个比例），见 known_bugs.md P141。
        const float slack = 64.0;
        visible = (clip.x + W >= -rA - slack) && (W - clip.x >= -rB - slack)
                  && (clip.y + W >= -rC - slack) && (W - clip.y >= -rD - slack)
                  && (W >= -rN - slack);
${coneTest}    }
    if (visible) {
        uint at;
        InterlockedAdd(gPayloadCount, 1u, at);
        gMeshPayload.slot[at] = local;
    }
    GroupMemoryBarrierWithGroupSync();
    DispatchMesh(gPayloadCount, 1u, 1u, gMeshPayload);
}
"""
        val payloadParam = if (!cullActive) "" else ", in payload MeshPayload payload"
        val segmentLookup = if (!cullActive) "meshSegments[gid.x]" else "meshSegments[payload.slot[gid.x]]"
        return sectionDecl + renamed + """

// ===================== P128：mesh 包装（顶点拉取） =====================
// P137：每组只处理 ≤$threads 个索引（D3D12 每组上限 256 顶点/256 图元），
// 一段 1536 索引被切成 ceil(1536/126)=13 组；sectionIndex 指向 t16 里本段的 96 字节。
struct MeshSegment {
    uint firstIndex;    // 相对索引缓冲 SRV 起点的索引号
    uint indexCount;    // 本组要处理的索引数（≤$threads）
    int baseVertex;     // 相对顶点缓冲 SRV 起点的顶点号
    uint sectionIndex;  // t16 里的段号（×96 得到基址）
    uint indexStride;   // 2 = R16_UINT，4 = R32_UINT（RAW 视图看不出格式，只能显式带）
    uint pad0;
    uint pad1;
    uint pad2;
};
// P136：段表容量 1024（实测整批 solid_terrain 有 343~353 段；256 会越界）。
cbuffer MeshSegments : register(b8, space0) { MeshSegment meshSegments[1024]; };
ByteAddressBuffer meshVertices : register(t14, space0);
ByteAddressBuffer meshIndices : register(t15, space0);
$cullDecl

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
$asDecl
[numthreads($threads, 1, 1)]
[outputtopology("triangle")]
void ms_main(uint3 gid : SV_GroupID, uint3 tid : SV_GroupThreadID$payloadParam,
             out vertices SPIRV_Cross_Output verts[$threads],
             out indices uint3 tris[$threads])
{
    MeshSegment seg = $segmentLookup;
$sectionSet
    uint triCount = seg.indexCount / 3u;
    SetMeshOutputCounts(seg.indexCount, triCount);
    uint vid = tid.x;
    if (vid >= seg.indexCount) {
        return;
    }
    // 索引宽度只能显式带（RAW SRV 没有格式）：16 位时按 4 字节字取高低半字。
    uint istart = (seg.firstIndex + vid) * seg.indexStride;
    uint index;
    if (seg.indexStride == 2u) {
        uint w = meshIndices.Load(istart & ~3u);
        index = ((istart & 2u) == 0u) ? (w & 65535u) : (w >> 16);
    } else {
        index = meshIndices.Load(istart);
    }
    uint vertexIndex = index + uint(seg.baseVertex);
    uint addr = vertexIndex * ${stride}u;
    SPIRV_Cross_Input vin = (SPIRV_Cross_Input)0;
$decode
    verts[vid] = vs_main(vin);
$visibilityWrite    if ((vid % 3u) == 0u) {
        tris[vid / 3u] = uint3(vid, vid + 1u, vid + 2u);
    }
}
"""
    }

    /**
     * P135：cbuffer 成员（名字、HLSL 类型、相对段首的字节偏移）。
     * 偏移直接由 spvc 写出的 `packoffset(cN[.xyzw])` 换算，避免重新推导打包规则。
     */
    data class CBufferMember(val name: String, val type: String, val offset: Int)

    /** 解析指定 cbuffer 的成员表；找不到返回 null。 */
    fun parseCBuffer(vsHlsl: String, cbuffer: String): List<CBufferMember>? {
        val head = Regex("cbuffer\\s+" + Regex.escape(cbuffer) + "\\s*:[^{]*\\{").find(vsHlsl) ?: return null
        val close = vsHlsl.indexOf("};", head.range.last)
        if (close < 0) return null
        val body = vsHlsl.substring(head.range.last + 1, close)
        val members = mutableListOf<CBufferMember>()
        val line = Regex("^((?:row_major|column_major)\\s+)?([\\w<>]+)\\s+(\\w+)\\s*(?::\\s*packoffset\\(c(\\d+)(?:\\.([xyzw]))?\\))?$")
        for (raw in body.lines()) {
            val cleaned = raw.substringBefore("//").trim().removeSuffix(";").trim()
            if (cleaned.isEmpty()) continue
            val m = line.find(cleaned) ?: continue
            val base = m.groupValues[4].toIntOrNull() ?: 0
            val offset = base * 16 + when (m.groupValues[5]) {
                "y" -> 4
                "z" -> 8
                "w" -> 12
                else -> 0
            }
            members.add(CBufferMember(m.groupValues[3], m.groupValues[2], offset))
        }
        return members.ifEmpty { null }
    }

    /** 把 cbuffer 块从源码里删掉（连同 `cbuffer X : register(...)` 到 `};`）。 */
    private fun stripCBuffer(vsHlsl: String, cbuffer: String): String {
        val head = Regex("cbuffer\\s+" + Regex.escape(cbuffer) + "\\s*:[^{]*\\{").find(vsHlsl) ?: return vsHlsl
        val close = vsHlsl.indexOf("};", head.range.last)
        if (close < 0) return vsHlsl
        return vsHlsl.removeRange(head.range.first, close + 2)
    }

    /** 生成"按 gSegBase 读本段数据"的声明 + 成员宏。 */
    /** `struct <structName>` 里是否已经声明了 `field`（用于避免与 26.3 原生插值量撞名）。 */
    private fun hasOutputField(hlsl: String, structName: String, field: String): Boolean {
        val start = hlsl.indexOf("struct $structName")
        if (start < 0) return false
        val open = hlsl.indexOf('{', start)
        val close = hlsl.indexOf("};", open)
        if (open < 0 || close < 0) return false
        return Regex("\\b" + Regex.escape(field) + "\\s*:")
            .containsMatchIn(hlsl.substring(open, close))
    }

    /** 26.3：VS 的 `SPIRV_Cross_Output` 是否自带 `chunkVisibility`（MULTIDRAW_TERRAIN 路线）。 */
    fun vertexOwnsVisibility(vsHlsl: String): Boolean =
        hasOutputField(vsHlsl, "SPIRV_Cross_Output", VISIBILITY_FIELD)

    private fun buildSectionAccess(members: List<CBufferMember>): String {
        val sb = StringBuilder()
        sb.append("\n// P135：逐区块数据（原 cbuffer ChunkSection）\n")
        sb.append("ByteAddressBuffer meshSections : register(t16, space0);\n")
        sb.append("static uint gSegBase;\n")
        sb.append("float4 meshSectionL4(uint off) { return asfloat(meshSections.Load4(gSegBase + off)); }\n")
        sb.append("float meshSectionF(uint off) { return asfloat(meshSections.Load(gSegBase + off)); }\n")
        sb.append("int meshSectionI(uint off) { return asint(meshSections.Load(gSegBase + off)); }\n")
        for (m in members) {
            val type = m.type.removePrefix("row_major ").removePrefix("column_major ").trim()
            val expr = when {
                type.endsWith("4x4") -> "float4x4(meshSectionL4(${m.offset}u), meshSectionL4(${m.offset + 16}u), " +
                    "meshSectionL4(${m.offset + 32}u), meshSectionL4(${m.offset + 48}u))"
                type == "float" -> "meshSectionF(${m.offset}u)"
                type == "int" -> "meshSectionI(${m.offset}u)"
                type == "uint" -> "asuint(meshSectionI(${m.offset}u))"
                type.matches(Regex("float[234]")) -> {
                    val n = type.last().digitToInt()
                    val comps = (0 until n).joinToString(", ") { "meshSectionF(${m.offset + it * 4}u)" }
                    "$type($comps)"
                }
                type.matches(Regex("int[234]")) -> {
                    val n = type.last().digitToInt()
                    val comps = (0 until n).joinToString(", ") { "meshSectionI(${m.offset + it * 4}u)" }
                    "$type($comps)"
                }
                else -> null
            }
            if (expr != null) {
                sb.append("#define ").append(m.name).append(' ').append(expr).append('\n')
            }
        }
        return sb.toString()
    }

    /** 从 VS HLSL 里解析 `struct SPIRV_Cross_Input { ... };` 的字段（类型 + 名字）。 */
    fun parseInputFields(vsHlsl: String): List<Field> {        val start = vsHlsl.indexOf("struct SPIRV_Cross_Input")
        if (start < 0) return emptyList()
        val braceOpen = vsHlsl.indexOf('{', start)
        val braceClose = vsHlsl.indexOf("};", braceOpen)
        if (braceOpen < 0 || braceClose < 0) return emptyList()
        val body = vsHlsl.substring(braceOpen + 1, braceClose)
        val fields = mutableListOf<Field>()
        for (line in body.lines()) {
            val cleaned = line.substringBefore("//").trim()
            if (cleaned.isEmpty()) continue
            val match = Regex("^(\\w+)\\s+(\\w+)\\s*:\\s*\\w+\\s*;$").find(cleaned) ?: continue
            fields.add(Field(match.groupValues[1], match.groupValues[2]))
        }
        return fields
    }

    /** 生成 `vin.<name> = ...;`：按 GpuFormat 的分量数 / 位宽 / 种类选择读取函数再按字段类型截断。 */
    private fun decodeField(field: Field, gpuFormat: String, offset: Int, stride: Int): String {
        val (count, bits, kind) = parseGpuFormat(gpuFormat)
        val read = when (kind) {
            "FLOAT" -> "meshReadF32"
            "UNORM" -> when (bits) { 8 -> "meshReadUNorm8"; 16 -> "meshReadUNorm16"; else -> "meshReadUNorm32" }
            "SNORM" -> when (bits) { 8 -> "meshReadSNorm8"; 16 -> "meshReadSNorm16"; else -> null }
            "SINT" -> when (bits) { 8 -> "meshReadSInt8"; 16 -> "meshReadSInt16"; else -> "meshReadSInt32" }
            "UINT" -> when (bits) { 8 -> "meshReadUInt8"; 16 -> "meshReadUInt16"; else -> "meshReadUInt32" }
            else -> null
        } ?: return "        vin.${field.name} = (${field.type})0;  // 未支持的格式 $gpuFormat\n"

        val isInt = field.type.startsWith("int")
        val convert = if (isInt && !kind.contains("INT")) "int4" else if (isInt) "int4" else "float4"
        val swizzle = buildString {
            for (i in 0 until count) {
                if (i > 0) append(", ")
                append("t.").append("xyzw"[i])
            }
        }
        val addr = "vertexIndex * ${stride}u + ${offset}u"
        return "        { ${convert} t = ${convert}($read($addr)); " +
            "vin.${field.name} = ${field.type}($swizzle); }\n"
    }

    /** `RGBA8_UNORM` → (4, 8, "UNORM")；`R32_FLOAT` → (1, 32, "FLOAT")。 */
    private fun parseGpuFormat(name: String): Triple<Int, Int, String> {
        val kind = name.substringAfterLast('_', "FLOAT")
        val head = name.substringBefore('_')
        // `R32G32B32_FLOAT` 这种名字里每个分量都重复一次字母，分量数必须按 (字母+位宽) 的
        // 出现次数算，不能按字符串长度（第一版就是在这里把 float3 解析成了 4 分量，
        // 生成的 `float3(t.x, t.y, t.z, t.w)` 直接让 DXC 编译失败）。
        // 本工程的 GpuFormat 名字是 `RGB32_FLOAT` / `RGBA8_UNORM` 这种"字母连写 + 一个位宽"，
        // 分量数就是字母个数；`R32G32B32_FLOAT` 那种重复字母的写法按组数兜底。
        val letters = head.count { it.isLetter() }
        val groups = Regex("[RGBA]\\d+").findAll(head).count()
        val count = maxOf(letters, groups, 1).coerceAtMost(4)
        val bits = Regex("\\d+").find(head)?.value?.toIntOrNull() ?: 32
        return Triple(count, bits, kind)
    }

    /**
     * P138：挑一个 VS 输出里没占用的 `TEXCOORDn` 作为淡入系数的语义。
     *
     * <p>必须**先定语义再分别插进 VS 输出与 PS 输入**，两边字符串一致才能对接上。
     * 全被占用时返回 null（调用点退回"淡入段走 IMR"的旧路径）。
     */
    fun pickVisibilitySemantic(vsHlsl: String): String? {
        val used = HashSet<String>()
        val start = vsHlsl.indexOf("struct SPIRV_Cross_Output")
        if (start >= 0) {
            val open = vsHlsl.indexOf('{', start)
            val close = vsHlsl.indexOf("};", open)
            if (open >= 0 && close > open) {
                Regex(":\\s*(TEXCOORD\\d+)").findAll(vsHlsl.substring(open, close))
                    .forEach { used.add(it.groupValues[1]) }
            }
        }
        for (index in 0..15) {
            val candidate = "TEXCOORD$index"
            if (candidate !in used) return candidate
        }
        return null
    }

    /**
     * P138：往 `struct <structName>` 里插 `float chunkVisibility : <semantic>;`。
     * 已经插过（同一个语义）就原样返回；找不到该 struct 返回 null。
     */
    fun addInterpolant(hlsl: String, structName: String, field: String, semantic: String): String? {
        val start = hlsl.indexOf("struct $structName")
        if (start < 0) return null
        val open = hlsl.indexOf('{', start)
        val close = hlsl.indexOf("};", open)
        if (open < 0 || close < 0) return null
        val body = hlsl.substring(open, close)
        if (body.contains("$field :")) return hlsl
        val insertAt = close
        return hlsl.substring(0, insertAt) +
            "    float $field : $semantic;  // P138：逐段淡入系数（VS→PS 插值）\n" +
            hlsl.substring(insertAt)
    }

    /**
     * 26.3：原版 PS 是否**已经**自己消费 `chunkVisibility` 插值量。
     *
     * 26.2 的 `core/terrain.fsh` 从 `ChunkSection` UBO 里读逐 draw 的 `ChunkVisibility`，所以
     * P138 要给 PS 造一个人造插值量 + 文件级 `static float chunkVisibility`。26.3 把淡入改成
     * 顶点着色器输出（`terrain.vsh: layout(location = 4) out float chunkVisibility`）→
     * spvc 生成的 `SPIRV_Cross_Input` 里已经有 `float chunkVisibility : TEXCOORDn`，再打补丁
     * 会得到两个同名声明，DXC 直接 `redefinition of 'chunkVisibility'`。
     *
     * 检测到这种原生插值时，[patchFragmentVisibility] 应当跳过，调用点只需
     * `perSectionVisibility = true` 并用**未打补丁**的 PS 编译。
     */
    fun fragmentConsumesVisibility(psHlsl: String): Boolean {
        val start = psHlsl.indexOf("struct SPIRV_Cross_Input")
        if (start < 0) return false
        val open = psHlsl.indexOf('{', start)
        val close = psHlsl.indexOf("};", open)
        if (open < 0 || close < 0) return false
        return Regex("\\bfloat\\s+" + VISIBILITY_FIELD + "\\s*:")
            .containsMatchIn(psHlsl.substring(open, close))
    }

    /**
     * P138：给 PS 打上淡入系数的补丁 —— 让 PS 读**逐段**的 `ChunkVisibility`
     * （VS 通过插值字段传过来），而不是逐 draw 的 `ChunkSection` CBV 成员。
     *
     * <p>spvc 生成的 PS 结构很固定，四步都要做：
     * <ol>
     *   <li>`SPIRV_Cross_Input` 里加 `float chunkVisibility : <semantic>;`（语义必须与 VS 输出一致）；</li>
     *   <li>在 `SPIRV_Cross_Input` 之前加文件级 `static float chunkVisibility;`
     *       （spvc 把插值量都放成全局静态量）；</li>
     *   <li>`main()` 开头加 `chunkVisibility = stage_input.chunkVisibility;`；</li>
     *   <li>cbuffer 里的成员名带序号前缀（`_382_ChunkVisibility`，序号随入口点变）：
     *       **声明**改名 `cbFade`（保留占位，避免与新全局量撞名），**其余使用处**改成 `chunkVisibility`。</li>
     * </ol>
     *
     * <p>任何一步找不到预期结构就返回 null —— 调用点退回"淡入段走 IMR"的旧路径。
     * 宁可少优化，也不能让整批共用最后一组的系数（P137b 那个"整片地形渐渐出现"的 bug）。
     */
    fun patchFragmentVisibility(psHlsl: String, semantic: String): String? {
        if (System.getenv("MCDR_MESH_NO_PS_PATCH") == "1") return null  // 诊断用：强制回退 IMR 分流
        var hlsl = addInterpolant(psHlsl, "SPIRV_Cross_Input", VISIBILITY_FIELD, semantic) ?: return null
        val declaration = Regex("\\bfloat\\s+((?:_\\d+_)?ChunkVisibility)\\b").find(hlsl) ?: return null
        val member = declaration.groupValues[1]
        val inputStart = hlsl.indexOf("struct SPIRV_Cross_Input")
        if (inputStart < 0) return null
        hlsl = hlsl.substring(0, inputStart) + "static float $VISIBILITY_FIELD;\n\n" + hlsl.substring(inputStart)
        val entry = Regex("SPIRV_Cross_Output\\s+main\\s*\\(\\s*SPIRV_Cross_Input\\s+(\\w+)\\s*\\)\\s*\\{").find(hlsl)
            ?: return null
        val parameter = entry.groupValues[1]
        val bodyStart = entry.range.last + 1
        hlsl = hlsl.substring(0, bodyStart) +
            "\n    $VISIBILITY_FIELD = $parameter.$VISIBILITY_FIELD;" +
            hlsl.substring(bodyStart)
        // 声明（含序号前缀）先改名成 cbFade：这个名字不会再被引用，只是别和新全局量同名。
        val declarationAfter = Regex("\\bfloat\\s+" + Regex.escape(member) + "\\b").find(hlsl) ?: return null
        hlsl = hlsl.substring(0, declarationAfter.range.first) + "float cbFade" +
            hlsl.substring(declarationAfter.range.last + 1)
        return Regex("\\b" + Regex.escape(member) + "\\b").replace(hlsl, VISIBILITY_FIELD)
    }

    /**
     * P141：为 meshlet 包围球生成 compute 着色器（`cs_6_0`，入口 `bounds_main`）。
     *
     * <p>为什么要在 GPU 上算：AS 里的球体-视锥测试需要每个 meshlet 的包围球，而 CPU 侧拿不到
     * 区块顶点字节（`UberGpuBuffer` 的 staging→heap 拷贝不经 `map`）；把这段循环塞进 AS 里
     * 实测会成片多剔（P140 定论：同机位 mean|Δ|=33.99，而段级包围球是 0.20）。
     *
     * <p>每线程处理一个段表项（= 一个 meshlet，≤[MAX_INDICES_PER_GROUP] 个索引）：
     * 用与 MS 相同的索引/顶点寻址取位置 → **对象空间** AABB → 包围球
     * （center = AABB 中心，radius = 半对角线）。AS 侧再用 ChunkSection 的 ModelViewMat
     * 把它变换到视空间，与 MS 的顶点变换保持同一坐标系。
     *
     * <p>段表按 [MAX_BOUNDS_GROUPS]（1024 项 / 32 KB）一块处理：块内 `local` 是该块内的序号，
     * 调用方用 `segmentsOffset` / `boundsOffset` 逐块推进，所以下面对 1024 的硬上限是**每块**
     * 的上限，不是整张段表的上限 —— 第二块覆盖 `[1024, 2048)`，依此类推。
     *
     * @param stride 顶点缓冲的顶点大小（与段表里的 `vbStride` 同一个值）
     */
    fun buildBoundsHlsl(stride: Int, threads: Int = 64): String {
        val threadsText = threads.toString()
        return """
struct MeshSegment {
    uint firstIndex;
    uint indexCount;
    int  baseVertex;
    uint sectionIndex;
    uint indexStride;
    uint pad0;
    uint pad1;
    uint pad2;
};
cbuffer MeshSegments : register(b8, space0) {
    MeshSegment meshSegments[${MAX_BOUNDS_GROUPS}];
};
ByteAddressBuffer meshVertices : register(t14, space0);
ByteAddressBuffer meshIndices : register(t15, space0);
RWByteAddressBuffer meshletBounds : register(u0, space0);

// P141：每个线程算一个 meshlet 的对象空间包围球（16 B：center.xyz + radius）。
// $threadsText 个线程一组，覆盖**一块**段表（[MAX_BOUNDS_GROUPS] 项 / 32 KB）；多块由 JVM 侧
// 推进 segmentsOffset / boundsOffset 依次 dispatch。
[numthreads($threadsText, 1, 1)]
void bounds_main(uint3 gid : SV_GroupID, uint3 tid : SV_GroupThreadID) {
    uint local = gid.x * ${threadsText}u + tid.x;
    if (local >= ${MAX_BOUNDS_GROUPS}u) return;
    MeshSegment seg = meshSegments[local];
    float3 mn = float3(3.402823466e+38f, 3.402823466e+38f, 3.402823466e+38f);
    float3 mx = float3(-3.402823466e+38f, -3.402823466e+38f, -3.402823466e+38f);
    [loop] for (uint k = 0u; k < seg.indexCount; ++k) {
        uint istart = (seg.firstIndex + k) * seg.indexStride;
        uint index;
        if (seg.indexStride == 2u) {
            uint word = meshIndices.Load(istart & ~3u);
            index = ((istart & 2u) == 0u) ? (word & 65535u) : (word >> 16);
        } else {
            index = meshIndices.Load(istart);
        }
        uint va = (index + uint(seg.baseVertex)) * ${stride}u;
        float3 position = asfloat(uint3(meshVertices.Load(va),
                                        meshVertices.Load(va + 4u),
                                        meshVertices.Load(va + 8u)));
        mn = min(mn, position);
        mx = max(mx, position);
    }
    float3 center = (mn + mx) * 0.5f;
    float radius = length(mx - center);
    meshletBounds.Store4(local * 16u, asuint(float4(center, radius)));
}
""".trimIndent()
    }
}
