package dev.nts.expdxr.d3d12.render.shader

import com.mojang.renderpearl.api.pipeline.BindGroupLayout
import com.mojang.renderpearl.api.pipeline.RenderPipeline
import com.mojang.renderpearl.api.pipeline.ShaderType
import com.mojang.renderpearl.api.pipeline.UniformType
import com.mojang.renderpearl.util.ShaderCompileException
import dev.nts.expdxr.misc.settings.D3D12Settings
import dev.nts.expdxr.misc.IllusionLogger
import dev.nts.expdxr.d3d12.backend.D3D12BindGroupEntry
import net.minecraft.client.renderer.ShaderDefines
import net.minecraft.resources.Identifier
import org.lwjgl.system.MemoryUtil
import org.lwjgl.util.shaderc.Shaderc
import java.util.*
import java.util.regex.Pattern

/**
 * 着色器编译器：GLSL →(shaderc) SPIR-V →(spvc) 重编号 + HLSL 文本 + 绑定条目，再按 pass 打 Y / lightmap / blit 补丁；
 * DXIL 由原生层 DXC 生成。链路、判据与失败分级见 docs/shaders/README.md §2、§5~§9、§12
 */
class D3D12ShaderCompiler : AutoCloseable {
    private val shaderCompiler = Shaderc.shaderc_compiler_initialize()
    private val shaderOptions = Shaderc.shaderc_compile_options_initialize()
    private val globalDefines: ShaderDefines

    init {
        Shaderc.shaderc_compile_options_set_target_env(this.shaderOptions, 0, 0x402000)  // Vulkan 1.2
        Shaderc.shaderc_compile_options_set_auto_bind_uniforms(this.shaderOptions, true)
        Shaderc.shaderc_compile_options_set_auto_map_locations(this.shaderOptions, true)
        Shaderc.shaderc_compile_options_set_generate_debug_info(this.shaderOptions)
        Shaderc.shaderc_compile_options_set_optimization_level(this.shaderOptions, 0)
        this.globalDefines = ShaderDefines.builder()
            .define("gl_VertexID", "gl_VertexIndex")
            .define("gl_InstanceID", "gl_InstanceIndex")
            // D3D12 深度是 0..1 且 26.3 用 reversed-Z；vanilla `oit_common.glsl` 靠这个宏决定是否再按 GL 的 [-1,1] 映射一次。
            .define("RENDERPEARL_DEPTH_IS_ZERO_TO_ONE")
            .build()
    }

    /** GLSL → SPIR-V（shaderc），并立即用 spvc 反射出绑定信息。 */
    @Throws(ShaderCompileException::class)
    fun createIntermediary(filename: String, source: String, type: ShaderType,
                           includeResolver: ((Identifier) -> String?)? = null): D3D12IntermediaryShaderModule {
        var source = source
        source = injectShaderDefines(source, this.globalDefines)
        if (includeResolver != null) {
            source = expandShaderIncludes(source, includeResolver)
        }
        val shaderType = if (type == ShaderType.FRAGMENT) 1 else 0  // SHADERC_FRAGMENT_SHADER=1, VERTEX=0
        val sourceBuffer = MemoryUtil.memUTF8(source, false)
        val filenameBuffer = MemoryUtil.memUTF8(filename)
        val entrypointBuffer = MemoryUtil.memUTF8("main")
        val result = Shaderc.shaderc_compile_into_spv(this.shaderCompiler, sourceBuffer,
            shaderType, filenameBuffer, entrypointBuffer, this.shaderOptions)
        try {
            val status = Shaderc.shaderc_result_get_compilation_status(result)
            if (status != 0) {
                // 失败现场：把实际送进编译器的源码落盘，否则只剩 shaderc 行号可查。
                runCatching {
                    val dir = java.nio.file.Path.of(System.getProperty("user.dir"), "dxrmc")
                    java.nio.file.Files.createDirectories(dir)
                    // 文件名必须带 stage 与线程：顶点/片元并发编译同名会互相覆盖、截断。
                    val safe = filename.replace('/', '_').replace(':', '_')
                    val stage = if (type == ShaderType.FRAGMENT) "frag" else "vert"
                    val unique = "${safe}.${stage}.${Thread.currentThread().threadId()}.failed.glsl"
                    java.nio.file.Files.writeString(dir.resolve("glsl_$unique"), source)
                }
                throw ShaderCompileException(
                    "Couldn't parse GLSL: ${Shaderc.shaderc_result_get_error_message(result)}")
            }
            val spirv = Shaderc.shaderc_result_get_bytes(result)!!
            val copy = MemoryUtil.memCalloc(spirv.remaining())
            MemoryUtil.memCopy(spirv, copy)
            return D3D12IntermediaryShaderModule.createFromSpirv(filename, copy)
        } finally {
            Shaderc.shaderc_result_release(result)
            MemoryUtil.memFree(entrypointBuffer)
            MemoryUtil.memFree(filenameBuffer)
            MemoryUtil.memFree(sourceBuffer)
        }
    }

    /** 收集绑定 → 克隆并重编号 → 生成 HLSL → 打 Y/lightmap/blit 补丁。 */
    @Throws(ShaderCompileException::class)
    fun compile(pipeline: RenderPipeline, vertex: D3D12IntermediaryShaderModule,
                fragment: D3D12IntermediaryShaderModule, flipY: Boolean): D3D12CompiledShader {
        // 顶点 → 片元各调一次：调用顺序就是 entries 下标，也就是 SPIR-V binding 与寄存器号。
        val entries = mutableListOf<D3D12BindGroupEntry>()
        addToBindGroup(entries, vertex, pipeline)
        addToBindGroup(entries, fragment, pipeline)

        val vertexOutputNames = mutableListOf<String>()
        // 顶点输出名（反射时 location 已打平）作为片元输入的重绑定名单。
        for (variable in vertex.outputs()) {
            vertexOutputNames.add(variable.name())
        }
        val vertexInputNames = mutableListOf<String>()
        // 按 vertexFormatBindings 的元素顺序收集 → 重编号后 location 与顶点格式元素下标一致。
        for (vertexFormat in pipeline.vertexFormatBindings) {
            if (vertexFormat == null) continue
            for (attribute in vertexFormat.elements) {
                vertexInputNames.add(attribute.name())
            }
        }
        // 克隆 SPIR-V 工作副本：原始缓存模块不可变，避免多次 rebind() 互相污染。
        val vertexWork = vertex.cloneForCompile()
        val fragmentWork = fragment.cloneForCompile()
        vertexWork.rebind(vertexInputNames, entries)
        fragmentWork.rebind(vertexOutputNames, entries)

        var vertexHlsl = vertexWork.toHlsl(true)
        var fragmentHlsl = fragmentWork.toHlsl(false)
        // 诊断：HLSL 落盘（配置项 dumpHlsl 或 MCDR_DUMP_HLSL=1），最多 60 对。
        if (D3D12Settings.dumpHlsl || System.getenv("MCDR_DUMP_HLSL") == "1") {
            val dir = java.io.File("dxrmc/hlsl")
            dir.mkdirs()
            val existing = dir.listFiles()?.size ?: 0
            if (existing < 60) {
                val stamp = Integer.toHexString(vertexHlsl.hashCode())
                java.io.File(dir, "vs_$stamp.hlsl").writeText(vertexHlsl)
                java.io.File(dir, "ps_$stamp.hlsl").writeText(fragmentHlsl)
            }
        }
        // needsYFlip = isAtlasBake || flipY：flipY 的 pass 里「所有」管线都要翻（按管线名白名单必漏）；
        // animate_sprite 图集烘制无深度附件、被 flipY 判据排除，故无条件翻。见 §8.1
        val pipelineLocation = pipeline.location.toString()
        val isAtlasBake = pipelineLocation.contains("animate_sprite")
        val needsYFlip = isAtlasBake || flipY
        if (needsYFlip) {
            val before = vertexHlsl
            vertexHlsl = injectVertexYFlip(vertexHlsl)
            if (before == vertexHlsl) {
                // 注入点没命中只 warn，管线照旧编译（失败分级见 §12.1）。
                IllusionLogger.warnMessage("no gl_Position assignment found in ${pipelineLocation}"
                    + " vertex HLSL, Y-flip NOT injected")
            }
        }
        // lightmap 更新 pass 在 frag_main 入口镜像 texCoord.y，与 terrain 采样方位对齐；`!flipY` 防止重复翻。
        // 见 §8.2
        if (pipelineLocation.contains("lightmap") && !flipY) {
            // needle 依赖 spvc 的输出格式，模板一变就永远不命中（只 warn、不注入）。
            val needle = "void frag_main()\n{"
            val repl = "$needle\n    texCoord.y = 1.0f - texCoord.y;"
            if (fragmentHlsl.contains(needle)) {
                fragmentHlsl = fragmentHlsl.replace(needle, repl)
            } else {
                IllusionLogger.warnMessage("[FIX] WARN: frag_main needle not found for "
                    + pipeline.location)
            }
        }
        // 全屏 blit 用 texCoord 采样 raster 目标时必然上下镜像（raster 第 0 行 = 画面顶部）；
        // 只对「用 texCoord 采样」的片元翻转、`!flipY`，lightmap 是拿 texCoord 当数据，已在上面单独处理。见 §8.3
        val isScreenQuad = pipeline.shaders[ShaderType.VERTEX]?.toString() == SCREEN_QUAD_SHADER
        if (isScreenQuad && !flipY && !pipelineLocation.contains("lightmap")
            && SAMPLES_WITH_TEXCOORD.containsMatchIn(fragmentHlsl)) {
            val needle = "void frag_main()\n{"
            val repl = "$needle\n    texCoord.y = 1.0f - texCoord.y;  // raster 目标行序上下颠倒"
            if (fragmentHlsl.contains(needle)) {
                fragmentHlsl = fragmentHlsl.replace(needle, repl)
            } else {
                IllusionLogger.warnMessage("frag_main needle not found for "
                    + pipelineLocation)
            }
        }
        // 基准是 SPIR-V unique inputs 数量（与上一步的 remap 条目数对齐），不是 vertexInputNames。
        val semanticNames = mutableListOf<String>()
        for (i in 0 until vertex.inputs().size) {
            semanticNames.add(if (i == 0) "POSITION" else ("TEXCOORD${i - 1}"))
        }
        return D3D12CompiledShader(vertexHlsl, fragmentHlsl, entries, vertexInputNames, semanticNames)
    }

    override fun close() {
        Shaderc.shaderc_compile_options_release(this.shaderOptions)
        Shaderc.shaderc_compiler_release(this.shaderCompiler)
    }

    private companion object {
        /** vanilla 全屏 pass（`screenquad.vsh`，3 顶点无顶点缓冲）的着色器 location。 */
        private const val SCREEN_QUAD_SHADER = "minecraft:core/screenquad"

        /** 「用 texCoord 采样」的特征：只认这种形态，`gl_FragCoord` / `texelFetch` 是逐像素原地取，翻了反而错。 */
        private val SAMPLES_WITH_TEXCOORD =
            Regex("Sample(?:Level|Bias|Grad|Cmp(?:Level)?)?\\s*\\([^)]*\\btexCoord\\b")

        /** 在每个 `gl_Position = …;` 赋值后插入 `gl_Position.y = -gl_Position.y;`（GL bottom-up NDC → D3D12 top-down）。 */
        private fun injectVertexYFlip(hlsl: String): String {
            val m = Pattern
                .compile("(?m)^([ \\t]*gl_Position\\s*=\\s*[^;]+;)$")
                .matcher(hlsl)
            val sb = StringBuilder()
            var last = 0
            var count = 0
            while (m.find()) {
                val stmt = m.group(1)
                val indent = stmt.substring(0, stmt.length - stmt.trimStart().length)
                sb.append(hlsl, last, m.start())
                sb.append(stmt).append('\n').append(indent).append("gl_Position.y = -gl_Position.y;")
                last = m.end()
                count++
            }
            // 一个都没命中时原样返回，由调用方 warn。
            if (count == 0) {
                return hlsl
            }
            sb.append(hlsl.substring(last))
            return sb.toString()
        }

        /** 按 shader 声明的 UBO/sampler 顺序收集绑定，同名去重（先到先得）。 */
        @Throws(ShaderCompileException::class)
        private fun addToBindGroup(entries: MutableList<D3D12BindGroupEntry>,
                                   shader: D3D12IntermediaryShaderModule, pipeline: RenderPipeline) {
            var uniformDescription: Optional<BindGroupLayout.UniformDescription>
            for (buffer in shader.uniformBuffers()) {
                val name = buffer.name()
                uniformDescription = BindGroupLayout.flattenUniforms(pipeline.bindGroupLayouts).stream()
                    .filter { d -> d.name() == name }.findFirst()
                if (uniformDescription.isEmpty) {
                    IllusionLogger.debugFormat("addToBindGroup: uniform '%s' not found in %s",
                        name, pipeline.location)
                    throw ShaderCompileException("Unable to find shader defined uniform ($name)")
                }
                if (!entries.stream().noneMatch { e ->
                        e.type == D3D12BindGroupEntry.Type.UNIFORM_BUFFER && e.name == name
                    }) continue
                entries.add(D3D12BindGroupEntry(D3D12BindGroupEntry.Type.UNIFORM_BUFFER, name, null))
            }
            for (sampler in shader.samplers()) {
                val name = sampler.name()
                uniformDescription = BindGroupLayout.flattenUniforms(pipeline.bindGroupLayouts).stream()
                    .filter { d -> d.name() == name }.findFirst()
                // 26.3 的 flattenUniforms 返回所有 uniform（含 COMBINED_IMAGE_SAMPLER），
                // 故「是不是 texel buffer」只能看 UniformType，不能看名字是否存在。
                val isTexelBuffer = uniformDescription.isPresent &&
                    uniformDescription.get().type() == UniformType.TEXEL_BUFFER
                if (isTexelBuffer) {
                    if (sampler.dimensions() != 5) {
                        IllusionLogger.debugFormat("addToBindGroup: UTB '%s' in %s has dim %d (expected 5)",
                            name, pipeline.location, sampler.dimensions())
                        throw ShaderCompileException("UTB ($name) must have type of SpvDimBuffer")
                    }
                    if (!entries.stream().noneMatch { e ->
                            e.type == D3D12BindGroupEntry.Type.TEXEL_BUFFER && e.name == name
                        }) continue
                    entries.add(
                        D3D12BindGroupEntry(
                            D3D12BindGroupEntry.Type.TEXEL_BUFFER, name,
                            uniformDescription.get().gpuFormat()
                        )
                    )
                    continue
                }
                // 26.3 没有 flattenSamplers：采样器也从 flattenUniforms 按 COMBINED_IMAGE_SAMPLER 过滤。
                val samplerNames = BindGroupLayout.flattenUniforms(pipeline.bindGroupLayouts)
                if (samplerNames.stream().noneMatch { u ->
                        u.type() == UniformType.COMBINED_IMAGE_SAMPLER && u.name() == name
                    }) {
                    IllusionLogger.debugFormat("addToBindGroup: sampler '%s' not found in %s",
                        name, pipeline.location)
                    throw ShaderCompileException("Unable to find shader defined uniform ($name)")
                }
                if (sampler.dimensions() != 1 && sampler.dimensions() != 3) {
                    IllusionLogger.debugFormat("addToBindGroup: sampled texture '%s' in %s has dim %d (expected 1 or 3)",
                        name, pipeline.location, sampler.dimensions())
                    throw ShaderCompileException(
                        "Sampled texture ($name) must have type of SpvDim2D or SpvDimCube"
                    )
                }
                if (!entries.stream().noneMatch { e ->
                        e.type == D3D12BindGroupEntry.Type.SAMPLED_IMAGE && e.name == name
                    }) continue
                entries.add(D3D12BindGroupEntry(D3D12BindGroupEntry.Type.SAMPLED_IMAGE, name, null))
            }
        }

    }
}

/** 注入 `#define`；必须插在 `#version` 之后 —— 写在它前面 shaderc 直接 parse error。 */
internal fun injectShaderDefines(source: String, defines: ShaderDefines): String {
    if (defines.isEmpty) return source
    var directives = defines.asSourceDirectives()
    if (directives.isEmpty()) return source
    if (!directives.endsWith("\n")) directives += "\n"
    val version = source.indexOf("#version")
    if (version < 0) return directives + source
    val lineEnd = source.indexOf('\n', version)
    if (lineEnd < 0) return source + "\n" + directives
    return source.substring(0, lineEnd + 1) + directives + source.substring(lineEnd + 1)
}

/** `#include <minecraft:fog.glsl>`（26.3 的唯一形式）。 */
private val GLSL_INCLUDE_PATTERN =
    Pattern.compile("^[ \\t]*#[ \\t]*include[ \\t]+<([^>\\r\\n]+)>[ \\t]*\\r?$", Pattern.MULTILINE)

/** 展开深度上限：正常链约 4~6 层，留足余量。 */
private const val MAX_INCLUDE_DEPTH = 16

/** 展开后源码的字符上限，防止 include 互引无限膨胀。 */
private const val MAX_EXPANDED_CHARS = 8 * 1024 * 1024

/**
 * 送进 shaderc 前文本展开 `#include <id>`；解析不到的指令原样保留，让 shaderc 报错并指向真实行号。
 * 不做条件编译感知（vanilla include 自带 `#ifndef` 守卫，重复展开幂等）；展开处包 `// >>> include` 便于对照行号。
 */
internal fun expandShaderIncludes(source: String, includeResolver: (Identifier) -> String?): String {
    return expandIncludesRecursive(source, includeResolver, 0)
}

private fun expandIncludesRecursive(source: String, includeResolver: (Identifier) -> String?,
                                    depth: Int): String {
    if (depth >= MAX_INCLUDE_DEPTH) return source
    val matcher = GLSL_INCLUDE_PATTERN.matcher(source)
    if (!matcher.find()) return source
    val out = StringBuilder(source.length + 1024)
    var last = 0
    do {
        out.append(source, last, matcher.start())
        val idText = matcher.group(1)!!.trim()
        // 解析失败按「取不到」处理：日志 debug、指令原样保留。
        val included = try {
            includeResolver(Identifier.parse(idText))
        } catch (e: Exception) {
            IllusionLogger.debugFormat("include '%s' unresolved: %s", idText, e.message)
            null
        }
        if (included == null) {
            out.append(matcher.group())
        } else {
            out.append("// >>> include ").append(idText).append('\n')
            out.append(expandIncludesRecursive(included, includeResolver, depth + 1))
            out.append("// <<< include ").append(idText).append('\n')
        }
        last = matcher.end()
        // 超过上限就截断并停止展开（其余源码原样接在后面）。
        if (out.length > MAX_EXPANDED_CHARS) {
            IllusionLogger.warnFormat("shader include expansion exceeded %d chars, truncated", MAX_EXPANDED_CHARS)
            break
        }
    } while (matcher.find())
    out.append(source, last, source.length)
    return out.toString()
}
