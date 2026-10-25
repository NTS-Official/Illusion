package dev.nts.expdxr.d3d12.render.advanced.mesh

/**
 * P217：地形顶点解码生成器。
 *
 * <p>mesh shader 没有输入装配阶段，必须按 `VertexFormat` 把 `DefaultVertexFormat.BLOCK`
 * 的原始字节解成 spvc 生成的 `SPIRV_Cross_Input` 字段，再对每个顶点调用 `vs_main`。
 * 这里只做代码生成（纯函数、可在任意线程跑），不碰 GPU。
 *
 * <p>`ChunkPosition` / `ChunkVisibility` 不在 28 B 顶点流里 —— 它们是 slot 1 的逐实例数据，
 * 由 [D3D12MeshletShader] 从 `SectionDesc` 直接填进输入结构体，不经过本文件。
 */
object D3D12MeshVertexDecode {

    /** `SPIRV_Cross_Input` 的一个字段（HLSL 类型 + 名字）。 */
    data class Field(val type: String, val name: String)

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
    internal fun decodeField(field: Field, gpuFormat: String, offset: Int, stride: Int): String {
        val (count, bits, kind) = parseGpuFormat(gpuFormat)
        val read = when (kind) {
            "FLOAT" -> "meshReadF32"
            "UNORM" -> when (bits) { 8 -> "meshReadUNorm8"; 16 -> "meshReadUNorm16"; else -> "meshReadUNorm32" }
            "SNORM" -> when (bits) { 8 -> "meshReadSNorm8"; 16 -> "meshReadSNorm16"; else -> null }
            "SINT" -> when (bits) { 8 -> "meshReadSInt8"; 16 -> "meshReadSInt16"; else -> "meshReadSInt32" }
            "UINT" -> when (bits) { 8 -> "meshReadUInt8"; 16 -> "meshReadUInt16"; else -> "meshReadUInt32" }
            else -> null
        } ?: return "        vin.${field.name} = (${field.type})0;  // 未支持的格式 $gpuFormat\n"

        val convert = if (field.type.startsWith("int")) "int4" else "float4"
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
