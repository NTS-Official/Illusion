package dev.nts.expdxr.d3d12.render.advanced.mesh

import com.mojang.blaze3d.vertex.VertexFormat

/**
 * P128（mesh Step 3 后半第 1 项）：**顶点解码生成器**。
 *
 * <p>地形 VS 由 spvc 生成、形如 `SPIRV_Cross_Output main(SPIRV_Cross_Input stage_input)`。
 * mesh shader 不能走输入装配，必须自己按顶点格式把原始字节解成 `SPIRV_Cross_Input` 的字段，
 * 再对每个顶点调用 `vs_main`。这里就是"按 `VertexFormat` 生成解码赋值"的那一半：
 * 只做代码生成（纯函数、可在任何线程跑），不碰 GPU。
 *
 * <p>格式来自 `VertexFormatElement(name, offset, GpuFormat)`；`GpuFormat` 是
 * `RGBA8_UNORM` / `RG16_UINT` / `RGB32_FLOAT` 这类枚举名，直接按
 * 分量数 + 位宽 + 数值种类解析，避免维护一张上百项的映射表。
 */
object D3D12MeshVertexDecode {
    /** `SPIRV_Cross_Input` 的一个字段（HLSL 类型 + 名字）。 */
    data class Field(val type: String, val name: String)

    /**
     * 把 [vsHlsl] 包装成 mesh shader 源码：入口改名 `vs_main`，追加取段表 + 顶点/索引缓冲的
     * `ms_main`。`format` 为 null 或某字段找不到对应元素时，该字段解码为 0（并在注释里标出）。
     */
    fun buildMeshHlsl(vsHlsl: String, format: VertexFormat?, threads: Int = 64): String {
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
        val renamed = vsHlsl.replace(
            "SPIRV_Cross_Output main(SPIRV_Cross_Input stage_input)",
            "SPIRV_Cross_Output vs_main(SPIRV_Cross_Input stage_input)"
        )
        return renamed + """

// ===================== P128：mesh 包装（顶点拉取） =====================
struct MeshSegment { uint firstIndex; uint indexCount; int baseVertex; uint pad; };
cbuffer MeshSegments : register(b8, space0) { MeshSegment meshSegments[256]; };
ByteAddressBuffer meshVertices : register(t14, space0);
ByteAddressBuffer meshIndices : register(t15, space0);

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

[numthreads($threads, 1, 1)]
[outputtopology("triangle")]
void ms_main(uint3 gid : SV_GroupID, uint3 tid : SV_GroupThreadID,
             out vertices SPIRV_Cross_Output verts[$threads],
             out indices uint3 tris[$threads])
{
    MeshSegment seg = meshSegments[gid.x];
    uint triCount = seg.indexCount / 3u;
    SetMeshOutputCounts(seg.indexCount, triCount);
    uint vid = tid.x;
    if (vid >= seg.indexCount) {
        return;
    }
    uint index = meshIndices.Load(seg.firstIndex * 4u + vid * 4u);
    uint vertexIndex = index + uint(seg.baseVertex);
    uint addr = vertexIndex * ${stride}u;
    SPIRV_Cross_Input vin = (SPIRV_Cross_Input)0;
$decode
    verts[vid] = vs_main(vin);
    if ((vid % 3u) == 0u) {
        tris[vid / 3u] = uint3(vid, vid + 1u, vid + 2u);
    }
}
"""
    }

    /** 从 VS HLSL 里解析 `struct SPIRV_Cross_Input { ... };` 的字段（类型 + 名字）。 */
    fun parseInputFields(vsHlsl: String): List<Field> {
        val start = vsHlsl.indexOf("struct SPIRV_Cross_Input")
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
}