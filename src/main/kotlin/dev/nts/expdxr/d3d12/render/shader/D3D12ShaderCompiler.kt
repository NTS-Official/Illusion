package dev.nts.expdxr.d3d12.render.shader

import com.mojang.renderpearl.api.pipeline.BindGroupLayout
import com.mojang.renderpearl.api.pipeline.RenderPipeline
import com.mojang.renderpearl.api.pipeline.ShaderType
import com.mojang.renderpearl.api.pipeline.UniformType
import com.mojang.renderpearl.util.ShaderCompileException
import dev.nts.expdxr.misc.D3D12Settings
import dev.nts.expdxr.misc.IllusionLogger
import dev.nts.expdxr.d3d12.backend.D3D12BindGroupEntry
import net.minecraft.client.renderer.ShaderDefines
import net.minecraft.resources.Identifier
import org.lwjgl.system.MemoryUtil
import org.lwjgl.util.shaderc.Shaderc
import java.util.*
import java.util.regex.Pattern

/**
 * DX12 着色器编译器：GLSL -> SPIR-V（shaderc）-> 反射 + rebind（spvc）->
 * HLSL SM5.1（spvc）。镜像官方 {@code com.mojang.blaze3d.vulkan.glsl.GlslCompiler}，
 * 仅把最后的 "创建 Vulkan shader module" 换成 "输出 HLSL 字符串"（DXBC 编译在
 * 原生层 D3DCompile 完成）。
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
            .build()
    }

    /**
     * GLSL -> SPIR-V（shaderc），并立即用 spvc 反射出绑定信息。
     * 对应官方 {@code GlslCompiler.createIntermediary}。
     */
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
                // 失败现场落盘：26.3 的 include 展开 + defines 注入都是文本级的，没有原文件对照
                // 时只能看 shaderc 给的行号，先把真正送进编译器的源码留下来。
                runCatching {
                    val dir = java.nio.file.Path.of(System.getProperty("user.dir"), "dxrmc")
                    java.nio.file.Files.createDirectories(dir)
                    // 顶点/片元同名着色器会在不同线程同时编译，文件名里必须带 stage 和线程，
                    // 否则两个线程互相截断/覆盖同一个文件，落盘内容会出现乱码字节（P197 排查时
                    // 一度被误认成 include 读取越界）。
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

    /**
     * 组装一条管线的着色器：收集绑定（addToBindGroup）-> rebind（重写
     * SPIR-V binding/location）-> 输出 HLSL。
     * 对应官方 {@code GlslCompiler.compile}。
     */
    @Throws(ShaderCompileException::class)
    fun compile(pipeline: RenderPipeline, vertex: D3D12IntermediaryShaderModule,
                fragment: D3D12IntermediaryShaderModule, flipY: Boolean): D3D12CompiledShader {
        val entries = mutableListOf<D3D12BindGroupEntry>()
        addToBindGroup(entries, vertex, pipeline)
        addToBindGroup(entries, fragment, pipeline)

        val vertexOutputNames = mutableListOf<String>()
        for (variable in vertex.outputs()) {
            vertexOutputNames.add(variable.name())
        }
        val vertexInputNames = mutableListOf<String>()
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
        // P127 临时探针：把生成的 HLSL 落盘，用于评估"把 VS 包进 MS"的可行性。
        // 环境变量 MCDR_DUMP_HLSL=1 开启，最多 60 对文件（避免刷盘）。
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
        // P31 / P101b：在 GUI 离屏 pass（flipY=true，即 usage==13 且带深度附件的
        // GuiItemAtlas / PictureInPicture 图集）中，pass 的正交投影是 invertY=true
        // （GL 底左约定），而 D3D12 的 NDC Y 向上、纹理 v=0 在顶部 ⇒ 该 pass 里渲染的
        // **任何**几何都会被多镜像一次。因此判据必须是"整个 pass 一起翻"，而不是按
        // 管线名逐个打补丁：needsYFlip = isAtlasBake || flipY。
        // P101 实测（2026-10-14，`[P101] pass census` 13 条 + `[P28]` 注入日志）：
        // 物品栏里的玩家模型走 minecraft:pipeline/entity_translucent
        // （PlayerModel.java:36 `super(root, RenderTypes::entityTranslucent)`），
        // 旧的 entity_cutout|item_cutout 白名单漏掉它 ⇒ 该 pass 里模型上下颠倒，
        // 正是用户报的"头在底下 腿在上面"。needsYFlip 是单个布尔，与 isAtlasBake
        // 同时命中时也只注入一次（不会翻两次回到原样）。
        // 这些管线在 flipY=false 时保持原始着色器，主世界 3D 渲染方向不受影响。
        //
        // Fix ATLAS-BAKE-VFLIP（图集烘制 Y 镜像）：ANIMATE_SPRITE_BLIT /
        // ANIMATE_SPRITE_INTERPOLATE 仅在 TextureAtlas.uploadInitialContents /
        // SpriteContents.drawToAtlas 使用，即把每个 sprite 的 scratch 贴图经
        // ortho2D(0, W, 0, H) 渲染进 TextureAtlas 的 mipViews（usage=15，**无深度附件**）。
        // 根因与 lightmap / P31 相同：GL/Vulkan 下 ortho2D 的 y=0 落在纹理 v=0，而 D3D12
        // 相反（v=0 在顶部）→ 整张图集内容整体 Y 镜像。此后所有按 sprite UV 采样图集的
        // GUI 图标/按钮/血条、方块与物品纹理都会"串图 / 错位 / 缺失"。
        // 该 pass 无深度附件，被 P31 的 flipY 判定（usage==13 且带深度）排除，故对这两个
        // 仅用于图集烘制的管线**无条件**注入 gl_Position.y 翻转。
        val pipelineLocation = pipeline.location.toString()
        val isAtlasBake = pipelineLocation.contains("animate_sprite")
        // P101b：flipY 的 pass 里**所有**管线都要翻（白名单是历史遗留的补丁式写法，
        // 已实证漏掉 entity_translucent ⇒ 玩家模型颠倒）。
        val needsYFlip = isAtlasBake || flipY
        if (needsYFlip) {
            val before = vertexHlsl
            vertexHlsl = injectVertexYFlip(vertexHlsl)
            if (before == vertexHlsl) {
                IllusionLogger.warnMessage("[backend-java] [P28] WARN: no gl_Position assignment found in ${pipelineLocation}"
                    + " vertex HLSL, Y-flip NOT injected")
            }
        }
        // Fix LIGHTMAP-VFLIP（正式修复，替代已确认的 lmflip 探针）：16×16 readback 证明
        // 光贴图内容亮暗渐变正确，但 sky=15 亮行写在与 terrain 采样端相反的一侧，导致白天
        // 方块采样到暗行（lightmapviz 实测 ≈26-46、whitelight 探针可恢复明亮、uv2viz 证明
        // UV2 无误）。根因同 P28/P31：GL bottom-up 约定在 D3D12 无自动翻转，而本 pass 无
        // 深度附件被 P31 的 flipY 判定（usage=13 且带深度）排除。修复：在 lightmap 更新 pass
        // 的 frag_main 入口把 texCoord.y 镜像（sky 轴整体反转），与 terrain 采样方位对齐；
        // 探针实测白天地形恢复明亮。用 !flipY 排除潜在 P31 变体的重复注入。
        if (pipelineLocation.contains("lightmap") && !flipY) {
            val needle = "void frag_main()\n{"
            val repl = "$needle\n    texCoord.y = 1.0f - texCoord.y;"
            if (fragmentHlsl.contains(needle)) {
                fragmentHlsl = fragmentHlsl.replace(needle, repl)
            } else {
                IllusionLogger.warnMessage("[backend-java] [FIX] WARN: frag_main needle not found for "
                    + pipeline.location)
            }
        }
        // Fix S2：语义名称由 toHlsl() 通过 spvc_compiler_hlsl_add_vertex_attribute_remap 注入，
        // 基准 é vertex.inputs() (SPIR-V unique inputs após rebind), não vertexInputNames (formato).
        // BUG-01 fix: usar vertex.inputs().size() garante alinhamento com spvc remap count.
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
        /**
         * P28：在 spvc 生成的顶点 HLSL 中，于每个 {@code gl_Position = ...;} 赋值后插入
         * {@code gl_Position.y = -gl_Position.y;}，把 GL bottom-up NDC 修正为 D3D12
         * top-down NDC（图集 blit 专用；ortho 下 w=1，clip 空间直接翻转 Y 即正确）。
         */
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
            if (count == 0) {
                return hlsl
            }
            sb.append(hlsl.substring(last))
            return sb.toString()
        }

        /** 镜像官方 addToBindGroup：按 shader 声明的 UBO/sampler 顺序收集绑定。 */
        @Throws(ShaderCompileException::class)
        private fun addToBindGroup(entries: MutableList<D3D12BindGroupEntry>,
                                   shader: D3D12IntermediaryShaderModule, pipeline: RenderPipeline) {
            var uniformDescription: Optional<BindGroupLayout.UniformDescription>
            for (buffer in shader.uniformBuffers()) {
                val name = buffer.name()
                uniformDescription = BindGroupLayout.flattenUniforms(pipeline.bindGroupLayouts).stream()
                    .filter { d -> d.name() == name }.findFirst()
                if (uniformDescription.isEmpty) {
                    IllusionLogger.debugFormat("[backend-java] addToBindGroup: uniform '%s' not found in %s%n",
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
                // 26.3：flattenUniforms 会返回**所有** uniform（含 COMBINED_IMAGE_SAMPLER），
                // 而 26.2 的语义是「出现在这里 = texel buffer」（采样器走 flattenSamplers）。
                // 所以判据必须从「名字存在」改成「类型是 TEXEL_BUFFER」，否则 Sampler0 这类
                // 组合采样器会被当成 UTB，dim=1 直接抛 "must have type of SpvDimBuffer"。
                val isTexelBuffer = uniformDescription.isPresent &&
                    uniformDescription.get().type() == UniformType.TEXEL_BUFFER
                if (isTexelBuffer) {
                    if (sampler.dimensions() != 5) {
                        IllusionLogger.debugFormat("[backend-java] addToBindGroup: UTB '%s' in %s has dim %d (expected 5)%n",
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
                // 26.3 删掉了 BindGroupLayout.flattenSamplers：采样器统一由 flattenUniforms 给出，
                // 用 UniformType.COMBINED_IMAGE_SAMPLER 过滤出采样器/纹理对的名字。
                val samplerNames = BindGroupLayout.flattenUniforms(pipeline.bindGroupLayouts)
                if (samplerNames.stream().noneMatch { u ->
                        u.type() == UniformType.COMBINED_IMAGE_SAMPLER && u.name() == name
                    }) {
                    IllusionLogger.debugFormat("[backend-java] addToBindGroup: sampler '%s' not found in %s%n",
                        name, pipeline.location)
                    throw ShaderCompileException("Unable to find shader defined uniform ($name)")
                }
                if (sampler.dimensions() != 1 && sampler.dimensions() != 3) {
                    IllusionLogger.debugFormat("[backend-java] addToBindGroup: sampled texture '%s' in %s has dim %d (expected 1 or 3)%n",
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

/**
 * 26.3 删掉了 `com.mojang.blaze3d.preprocessor.GlslPreprocessor`：defines 不再由 blaze3d
 * 注入，而是由 [ShaderDefines.asSourceDirectives] 生成 `#define` 行、各后端自己决定怎么用
 * （官方 shaderc 后端走编译宏，我们把它们写进源码 —— 零额外状态，落盘的 GLSL 也自解释）。
 *
 * 插入位置必须在 `#version` 之后：GLSL 规定 `#version` 是第一条指令，写在它前面 shaderc
 * 会直接报 parse error。
 */
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

/** `#include <minecraft:fog.glsl>`（26.3 的唯一形式；`#moj_import` 已不存在）。 */
private val GLSL_INCLUDE_PATTERN =
    Pattern.compile("^[ \\t]*#[ \\t]*include[ \\t]+<([^>\\r\\n]+)>[ \\t]*\\r?$", Pattern.MULTILINE)

/** 展开深度上限：normal 链最长 core 着色器到叶子 include 约 4~6 层，留足余量。 */
private const val MAX_INCLUDE_DEPTH = 16

/** 展开后源码上限（字符）：防止 include 互相引用导致无限膨胀。 */
private const val MAX_EXPANDED_CHARS = 8 * 1024 * 1024

/**
 * 26.3 把 GLSL include 解析从 `GlslPreprocessor` 挪到了官方 `GlslCompiler` 的 shaderc
 * include 回调（`shaderc_compile_options_set_include_callbacks` + `ShaderSource.getInclude`），
 * 我们这条自建的 GLSL→SPIR-V 路径没有回调，因此必须在送进 shaderc 之前**文本展开**。
 *
 * 逐行匹配 `#include <id>`，用 [includeResolver] 取回被包含源码后递归展开；取不到时
 * 原样保留指令（shaderc 会报 "unexpected include directive"，错误信息仍指向真实行号）。
 * 不做条件编译感知 —— vanilla 的 include 文件都自带 `#ifndef` 守卫，重复/越界包含是幂等的。
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
        val included = try {
            includeResolver(Identifier.parse(idText))
        } catch (e: Exception) {
            IllusionLogger.debugFormat("[backend-java] include '%s' unresolved: %s%n", idText, e.message)
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
        if (out.length > MAX_EXPANDED_CHARS) {
            IllusionLogger.warnFormat("[backend-java] shader include expansion exceeded %d chars, truncated%n", MAX_EXPANDED_CHARS)
            break
        }
    } while (matcher.find())
    out.append(source, last, source.length)
    return out.toString()
}
