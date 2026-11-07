package io.github.dxegp.experimental.raytracing

import com.mojang.renderpearl.api.vertex.VertexFormat
import io.github.dxegp.d3d12.render.advanced.mesh.D3D12MeshletShader
import io.github.dxegp.d3d12.render.advanced.mesh.D3D12MeshVertexDecode
import java.util.Locale

/**
 * G-Buffer 的 MS/PS 源码生成器：MS 复用 [D3D12MeshletShader] 的 meshlet 包装并追加一条相机相对世界坐标，。
 * 两者必须共用同一条 varying 语义，否则 PS 拿不到世界坐标、法线整片为 0。
 * 见 docs/rt/README.md
 */
object D3D12RtGBufferShader {

    /** 生成结果：MS 与 PS 都在里面，`detail` 供调用方写日志（选了哪个图集/顶点色字段）。 */
    class Sources(val meshHlsl: String, val pixelHlsl: String, val detail: String)

    /** 注入的 varying 名；MS 输出与 PS 输入必须同名同语义。 */
    private const val WORLD_POS = "gbufWorldPos"

    /** spvc 的 PS 入口固定长这样（返回结构体名随管线变化，所以只匹配参数部分）。 */
    private const val PS_ENTRY_MARK = "main(SPIRV_Cross_Input"

    /** 多目标输出那一段在 shaderpack 里：`%WORLD%` / `%ALBEDO%` / `%ROUGHNESS%` / `%DEPTH_SCALE%` 由这里替换。 */
    private const val PS_TEMPLATE = "rt_gbuffer_ps.hlsl"
    private const val VARYING_TEMPLATE = "rt_gbuffer_varying.hlsl"
    private const val MS_PATCH_TEMPLATE = "rt_gbuffer_ms_patch.hlsl"
    private const val ALBEDO_SAMPLE = "rt_gbuffer_albedo_sample.hlsl"
    private const val ALBEDO_CONSTANT = "rt_gbuffer_albedo_constant.hlsl"
    private const val ALBEDO_TINT = "rt_gbuffer_tint.hlsl"

    // spvc 的声明带 space 限定（`register(t6, space0)`），漏掉它会一路退回常量反照率。
    private val TEXTURE_DECL = Regex("Texture2D\\s*<[^>]+>\\s+(\\w+)\\s*:\\s*register\\(t(\\d+)(?:\\s*,\\s*space\\d+)?\\)")
    private val SAMPLER_DECL = Regex("SamplerState\\s+(\\w+)\\s*:\\s*register\\(s(\\d+)(?:\\s*,\\s*space\\d+)?\\)")

    /**
     * [build] 最近一次失败的原因。
     *
     * 这个函数返回 null 时整条光追链会安静地消失（没有 G-Buffer 变体 → 没有重放批次 → 没有派发），
     * 所以调用点必须把这个字符串打出来，见 docs/rt/README.md §8。
     */
    var lastFailure: String? = null
        private set

    private fun fail(reason: String): Sources? {
        lastFailure = reason
        return null
    }

    /**
     * 生成 G-Buffer 的 MS/PS。返回 null 表示这条管线不适用（PS 找不到 spvc 入口、VS 结构体解析失败等），。
     * 见 docs/rt/README.md
     */
    fun build(vsHlsl: String, psHlsl: String, format: VertexFormat?,
              roughness: Float): Sources? {
        lastFailure = null
        val mesh = D3D12MeshletShader.build(vsHlsl, format, D3D12MeshletShader.VERTEX_STRIDE, cull = false)
            ?: return fail("meshlet shader build failed")
        val semantic = nextTexcoord(mesh) ?: return fail("no texcoord semantic")
        val varying = D3D12RtShaderSources.loadSnippet(D3D12ShaderPacks.sourcePath(VARYING_TEMPLATE))
            .replace("%WORLD%", WORLD_POS)
            .replace("%SEMANTIC%", semantic.toString()) + "\n"
        val meshInjected = injectIntoStruct(
            mesh.replace("float4 gVrsBand;", "float4 gVrsBand;\n    float4 gRtCameraOffset;"),
            "SPIRV_Cross_Output", varying)
            ?: return fail("mesh output struct missing")

        val positionField = D3D12MeshVertexDecode.parseInputFields(vsHlsl)
            .firstOrNull { it.name.contains("Position", ignoreCase = true) }?.name
            ?: return fail("no Position field")
        val chunkPos = "float3(s.chunkX, s.chunkY, s.chunkZ) + vin.$positionField"
        // 相机相对坐标：与 meshlet AS 的剔除口径一致，也保证 S3 的光线原点不会离 0 太远。
        val camera = D3D12MeshletShader.cameraUniformNames(vsHlsl)
        val worldPos = if (camera != null) {
            "$chunkPos - float3(${camera.first}) + ${camera.second} - gRtCameraOffset.xyz"
        } else {
            chunkPos
        }

        val callSite = "verts[vid] = vs_main(vin);"
        if (!meshInjected.contains(callSite)) return fail("meshlet call site missing")
        val entryAt = psHlsl.indexOf(PS_ENTRY_MARK)
        if (entryAt < 0) return fail("ps entry marker missing")
        val prefix = psHlsl.substring(0, psHlsl.lastIndexOf('\n', entryAt) + 1)
        val psFields = D3D12MeshVertexDecode.parseInputFields(prefix)
        val psPrefix = injectIntoStruct(prefix, "SPIRV_Cross_Input", varying)
            ?: return fail("ps input struct missing")

        val uvField = findField(psFields, "UV0", "uv0", "texCoord", "UV")
        val colorField = findField(psFields, "Color", "color", "tint")
        val inputColor = findField(D3D12MeshVertexDecode.parseInputFields(vsHlsl), "Color", "color", "tint")
        val tintAssignment = if (colorField != null && inputColor != null) {
            "gbufMerged.$colorField = vin.$inputColor;"
        } else {
            ""
        }
        val patch = D3D12RtShaderSources.loadSnippet(D3D12ShaderPacks.sourcePath(MS_PATCH_TEMPLATE))
            .replace("%CALL%", "vs_main(vin)")
            .replace("%WORLD_VALUE%", worldPos)
            .replace("%WORLD%", WORLD_POS)
            .replace("%TINT_ASSIGNMENT%", tintAssignment) + "\n"
        val meshSource = meshInjected.replace(callSite, patch)
        val texture = TEXTURE_DECL.find(psPrefix)
        val sampler = pickSampler(psPrefix, texture?.groupValues?.get(2))

        val albedo = buildString {
            if (texture != null && sampler != null && uvField != null) {
                appendLine(
                    D3D12RtShaderSources.loadSnippet(D3D12ShaderPacks.sourcePath(ALBEDO_SAMPLE))
                        .replace("%TEXTURE%", texture.groupValues[1])
                        .replace("%SAMPLER%", sampler.groupValues[1])
                        .replace("%UV%", uvField))
            } else {
                appendLine(D3D12RtShaderSources.loadSnippet(D3D12ShaderPacks.sourcePath(ALBEDO_CONSTANT)))
            }
            if (colorField != null) {
                appendLine(D3D12RtShaderSources.loadSnippet(D3D12ShaderPacks.sourcePath(ALBEDO_TINT)).replace("%COLOR%", colorField))
            }
        }

        val pixelSource = psPrefix + D3D12RtShaderSources.load(D3D12ShaderPacks.sourcePath(PS_TEMPLATE))
            .replace("%WORLD%", WORLD_POS)
            .replace("%ALBEDO%", albedo.trimEnd())
            .replace("%ROUGHNESS%", "%.4f".format(Locale.ROOT, roughness))
            .replace("%DEPTH_SCALE%", "%.1f".format(Locale.ROOT, D3D12RtGBuffer.DEPTH_SCALE))

        val detail = buildString {
            append("varying=TEXCOORD").append(semantic)
            append(" albedo=")
            append(
                if (texture != null && sampler != null && uvField != null) {
                    "${texture.groupValues[1]}/${sampler.groupValues[1]}*${colorField ?: "none"}"
                } else {
                    "constant*${colorField ?: "none"}"
                })
            append(" cameraRelative=").append(camera != null)
        }
        return Sources(meshSource, pixelSource, detail)
    }

    /** 找一个可用的采样器：优先与纹理同寄存器号，其次取第一个。 */
    private fun pickSampler(hlsl: String, textureRegister: String?): MatchResult? {
        val all = SAMPLER_DECL.findAll(hlsl).toList()
        if (all.isEmpty()) return null
        if (textureRegister != null) {
            all.firstOrNull { it.groupValues[2] == textureRegister }?.let { return it }
        }
        return all.first()
    }

    private fun findField(fields: List<D3D12MeshVertexDecode.Field>, vararg needles: String): String? =
        fields.firstOrNull { field -> needles.any { field.name.contains(it, ignoreCase = true) } }?.name

    /** 取 `SPIRV_Cross_Output` 里用到的最大 TEXCOORD 号 + 1，避让原版 varying。 */
    private fun nextTexcoord(meshHlsl: String): Int? {
        val start = meshHlsl.indexOf("struct SPIRV_Cross_Output")
        if (start < 0) return null
        val end = meshHlsl.indexOf("};", start)
        if (end < 0) return null
        var max = -1
        for (match in Regex("TEXCOORD(\\d+)").findAll(meshHlsl.substring(start, end))) {
            max = maxOf(max, match.groupValues[1].toIntOrNull() ?: -1)
        }
        val next = max + 1
        return if (next in 0..31) next else null
    }

    /** 在 `struct <name> { … }` 的右花括号前插入一行声明；结构体里没有嵌套花括号。 */
    private fun injectIntoStruct(hlsl: String, structName: String, declaration: String): String? {
        val start = hlsl.indexOf("struct $structName")
        if (start < 0) return null
        var index = hlsl.indexOf('{', start)
        if (index < 0) return null
        var depth = 0
        while (index < hlsl.length) {
            when (hlsl[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        return hlsl.substring(0, index) + declaration + hlsl.substring(index)
                    }
                }
            }
            index++
        }
        return null
    }
}
