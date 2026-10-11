package dev.nts.expdxr.dx12

import com.mojang.blaze3d.pipeline.BindGroupLayout
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.preprocessor.GlslPreprocessor
import com.mojang.blaze3d.shaders.ShaderType
import com.mojang.blaze3d.vertex.VertexFormat
import com.mojang.blaze3d.vertex.VertexFormatElement
import com.mojang.blaze3d.vulkan.glsl.ShaderCompileException
import java.nio.ByteBuffer
import java.util.ArrayList
import java.util.Optional
import java.util.regex.Pattern
import net.minecraft.client.renderer.ShaderDefines
import org.lwjgl.system.MemoryUtil
import org.lwjgl.util.shaderc.Shaderc

/**
 * DX12 着色器编译器：GLSL -> SPIR-V（shaderc）-> 反射 + rebind（spvc）->
 * HLSL SM5.1（spvc）。镜像官方 {@code com.mojang.blaze3d.vulkan.glsl.GlslCompiler}，
 * 仅把最后的 "创建 Vulkan shader module" 换成 "输出 HLSL 字符串"（DXBC 编译在
 * 原生层 D3DCompile 完成）。
 */
class Dx12ShaderCompiler : AutoCloseable {
    private val shaderCompiler: Long
    private val shaderOptions: Long
    private val globalDefines: ShaderDefines

    init {
        this.shaderCompiler = Shaderc.shaderc_compiler_initialize()
        this.shaderOptions = Shaderc.shaderc_compile_options_initialize()
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
    fun createIntermediary(filename: String, source: String, type: ShaderType): Dx12IntermediaryShaderModule {
        var source = source
        source = GlslPreprocessor.injectDefines(source, this.globalDefines)
        val shaderType = if (type == ShaderType.FRAGMENT) 1 else 0  // SHADERC_FRAGMENT_SHADER=1, VERTEX=0
        val sourceBuffer = MemoryUtil.memUTF8(source, false)
        val filenameBuffer = MemoryUtil.memUTF8(filename)
        val entrypointBuffer = MemoryUtil.memUTF8("main")
        val result = Shaderc.shaderc_compile_into_spv(this.shaderCompiler, sourceBuffer,
            shaderType, filenameBuffer, entrypointBuffer, this.shaderOptions)
        try {
            val status = Shaderc.shaderc_result_get_compilation_status(result)
            if (status != 0) {
                throw ShaderCompileException(
                    "Couldn't parse GLSL: " + Shaderc.shaderc_result_get_error_message(result))
            }
            val spirv = Shaderc.shaderc_result_get_bytes(result)!!
            val copy = MemoryUtil.memCalloc(spirv.remaining())
            MemoryUtil.memCopy(spirv, copy)
            return Dx12IntermediaryShaderModule.createFromSpirv(filename, copy)
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
    fun compile(pipeline: RenderPipeline, vertex: Dx12IntermediaryShaderModule,
        fragment: Dx12IntermediaryShaderModule, flipY: Boolean): Dx12CompiledShader {
        val entries = ArrayList<Dx12BindGroupEntry>()
        addToBindGroup(entries, vertex, pipeline)
        addToBindGroup(entries, fragment, pipeline)

        val vertexOutputNames = ArrayList<String>()
        for (variable in vertex.outputs()) {
            vertexOutputNames.add(variable.name())
        }
        val vertexInputNames = ArrayList<String>()
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
        // P31：在 GUI 离屏 pass（flipY=true，仅 GuiItemAtlas / PictureInPicture 离屏图集）
        // 中，对 entity_cutout / item_cutout / animate_sprite 管线注入 gl_Position.y 翻转，
        // 抵消这些 pass 的 invertY=true 正交投影在 D3D12（NDC Y 向上）下多出的一次 Y 镜像。
        // scissor 由 Dx12RenderPassBackend 原样直通，无需再翻转。
        // 注意：这些管线在 flipY=false 时保持原始着色器，主世界 3D 渲染方向不受影响。
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
        val needsYFlip = isAtlasBake
            || (flipY && (pipelineLocation.contains("entity_cutout")
                || pipelineLocation.contains("item_cutout")))
        // P28 诊断：打印 pipelineLocation + flipY + needsYFlip，便于追踪注入路径（P29：仅 verbose 输出）
        if (McdrAbi.LOG_VERBOSE) {
            System.err.println("[dx12-java] [P28] " + pipelineLocation + " flipY=" + flipY + " needsYFlip=" + needsYFlip)
            System.err.flush()
        }
        if (needsYFlip) {
            val before = vertexHlsl
            vertexHlsl = injectVertexYFlip(vertexHlsl)
            if (before == vertexHlsl) {
                System.err.println("[dx12-java] [P28] WARN: no gl_Position assignment found in " + pipelineLocation
                    + " vertex HLSL, Y-flip NOT injected")
                System.err.flush()
            } else {
                System.err.println("[dx12-java] [P28] inject Y-flip into " + pipelineLocation + " vertex HLSL")
                System.err.flush()
            }
        }
        if (DIAG_GREEN || DIAG_WHITE) {
            // P6 诊断：强制输出纯绿/纯白，区分"管线问题"vs"数据问题"
            // 屏幕变绿/白 => 管线通路正常，问题在纹理数据；仍红 => 管线根本问题。
            val loc = pipeline.location.toString()
            System.err.println("[dx12-java] [DIAG] " + loc
                + " frag before= " + fragmentHlsl.length + " bytes")
            val oldLenStr = fragmentHlsl.length.toString()
            fragmentHlsl = if (DIAG_GREEN)
                stripFragMainAndReplace(fragmentHlsl)
            else
                stripFragMainAndWhite(fragmentHlsl)
            System.err.println("[dx12-java] [DIAG] " + loc
                + " frag after= " + fragmentHlsl.length + " bytes"
                + " changed=" + (fragmentHlsl.length != Integer.parseInt(oldLenStr)))
            val head = if (fragmentHlsl.length > 120)
                fragmentHlsl.substring(0, 120) + "..."
            else
                fragmentHlsl
            System.err.println("[dx12-java] [DIAG] " + loc + " frag head=" + head)
            System.err.println("[dx12-java] [DIAG] " + loc
                + " frag forced " + (if (DIAG_GREEN) "GREEN" else "WHITE") + " (all pipelines)")
        }
        // 中灰诊断：只覆盖地形管线（solid/cutout/translucent_terrain），保留 GUI/天空
        // 不受影响，便于同帧对比。用于区分"光照乘积变暗"vs"主世界合成/呈现链压暗"。
        if (DIAG_GRAY >= 0.0f && pipeline.location.toString().contains("terrain")) {
            fragmentHlsl = stripFragMainAndGray(fragmentHlsl, DIAG_GRAY)
            System.err.println("[dx12-java] [DIAG] " + pipeline.location
                + " frag forced gray=" + DIAG_GRAY + " (terrain only, via " + DIAG_GRAY_SRC + ")")
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
            val repl = needle + "\n    texCoord.y = 1.0f - texCoord.y;"
            if (fragmentHlsl.contains(needle)) {
                fragmentHlsl = fragmentHlsl.replace(needle, repl)
                System.err.println("[dx12-java] [FIX] " + pipeline.location
                    + " lightmap frag texCoord.y mirrored (LIGHTMAP-VFLIP)")
            } else {
                System.err.println("[dx12-java] [FIX] WARN: frag_main needle not found for "
                    + pipeline.location)
            }
            System.err.flush()
        }
        // 白光照探针：只把 Lightmap 更新 pass 的输出白化（fragment=1.0），使 terrain 随后
        // 采样到的光照项恒为 1.0；地形管线本身不注入，保留真实纹理与基色做对比。
        // 注意顺序：先查 gray/white/green 全覆盖诊断未开启（否则冲突），此处只处理含
        // "lightmap" 的管线（pipeline/lightmap），不影响 GUI/天空。
        if (DIAG_WHITE_LIGHT && pipelineLocation.contains("lightmap")) {
            fragmentHlsl = stripFragMainAndWhite(fragmentHlsl)
            System.err.println("[dx12-java] [DIAG] " + pipeline.location
                + " frag forced WHITE on lightmap pass (light=1.0 probe, via "
                + DIAG_WHITE_LIGHT_SRC + ")")
            System.err.flush()
        }
        // UV2 可视化探针：把 terrain VS 里的"光照乘积"一行替换为直接发射 UV2 自身数值
        //   vertexColor = float4(UV2/240, 1, 0)
        // 使画面颜色直接反映顶点里真实的光照坐标范围：白天直射面（sky≈15→240）应接近
        // 亮橙红（r≈1）；若整片地形仍然发黑/偏暗 => UV2 数值范围不对（例如实际是 0..15
        // 的光照级而非 ×16 的 0..240），或 R16G16_SINT 输入布局/顶点数据解码错位。
        // fragment 仍按 tex*vertexColor 输出，不改变采样通路。触发：运行目录存在空文件
        // dx12_diag_uv2viz.flag。
        if (DIAG_UV2_VIZ && pipelineLocation.contains("terrain")) {
            val uv2Needle = "vertexColor = Color * sample_lightmap(Sampler2, _Sampler2_sampler, param_2);"
            val uv2Repl = "vertexColor = float4((float2(UV2) / 240.0f), 1.0f, 0.0f);"
            if (vertexHlsl.contains(uv2Needle)) {
                vertexHlsl = vertexHlsl.replace(uv2Needle, uv2Repl)
                System.err.println("[dx12-java] [DIAG] " + pipeline.location
                    + " UV2-viz injected (vertexColor=UV2/240) via " + UV2_VIZ_FLAG_NAME)
            } else {
                System.err.println("[dx12-java] [DIAG] WARN: UV2-viz needle not found in "
                    + pipeline.location + " vertex HLSL")
            }
            System.err.flush()
        }
        // Lightmap 可视化探针：直接输出 terrain 对 Sampler2 的原始采样值，用于确认
        // 地形 draw 时真正读到的那张 16×16 光贴图内容是否正常（uv2viz 已证明 UV2 正确）。
        if (DIAG_LIGHTMAP_VIZ && pipelineLocation.contains("terrain")) {
            val lmNeedle = "vertexColor = Color * sample_lightmap(Sampler2, _Sampler2_sampler, param_2);"
            val lmRepl = "vertexColor = float4(sample_lightmap(Sampler2, _Sampler2_sampler, param_2).rgb, 1.0f);"
            if (vertexHlsl.contains(lmNeedle)) {
                vertexHlsl = vertexHlsl.replace(lmNeedle, lmRepl)
                System.err.println("[dx12-java] [DIAG] " + pipeline.location
                    + " lightmap-viz injected (vertexColor=sample_lightmap) via " + LIGHTMAP_VIZ_FLAG_NAME)
            } else {
                System.err.println("[dx12-java] [DIAG] WARN: lightmap-viz needle not found in "
                    + pipeline.location + " vertex HLSL")
            }
            System.err.flush()
        }
        // 诊断 dump：把 terrain + lightmap 管线最终 HLSL 落盘（每管线一次）。terrain 用于查
        // UV2/光照采样换算；lightmap 用于查 LightmapInfo UBO 的 cbuffer 布局/寄存器是否与
        // CPU Std140 写入一致（怀疑 update pass 用错 uniform 写入了暗值）。
        if (DIAG_DUMP_SHADER && (pipelineLocation.contains("terrain") || pipelineLocation.contains("lightmap"))) {
            val tag = pipelineLocation.substring(pipelineLocation.lastIndexOf('/') + 1)
            if (gDumpedTerrainShaders.add(tag)) {
                try {
                    java.nio.file.Files.writeString(java.nio.file.Path.of(tag + "_frag.hlsl"), fragmentHlsl)
                    java.nio.file.Files.writeString(java.nio.file.Path.of(tag + "_vert.hlsl"), vertexHlsl)
                    System.err.println("[dx12-java] [DIAG] dumped HLSL for " + pipelineLocation)
                } catch (e: java.io.IOException) {
                    System.err.println("[dx12-java] [DIAG] dump failed for " + pipelineLocation + ": " + e)
                }
            }
        }
        // Fix S2：语义名称由 toHlsl() 通过 spvc_compiler_hlsl_add_vertex_attribute_remap 注入，
        // 基准 é vertex.inputs() (SPIR-V unique inputs após rebind), não vertexInputNames (formato).
        // BUG-01 fix: usar vertex.inputs().size() garante alinhamento com spvc remap count.
        val semanticNames = ArrayList<String>()
        for (i in 0 until vertex.inputs().size) {
            semanticNames.add(if (i == 0) "POSITION" else ("TEXCOORD" + (i - 1)))
        }
        return Dx12CompiledShader(vertexHlsl, fragmentHlsl, entries, vertexInputNames, semanticNames)
    }

    override fun close() {
        Shaderc.shaderc_compile_options_release(this.shaderOptions)
        Shaderc.shaderc_compiler_release(this.shaderCompiler)
    }

    companion object {
        // P20 诊断：所有 pipeline 强制输出纯绿色，区分"管线问题"vs"数据问题"。
        // 默认关闭（DX12_DIAG_GREEN=1 开启），避免正常游戏时闪绿屏。
        private val DIAG_GREEN: Boolean = "1" == System.getenv("DX12_DIAG_GREEN")
        // 诊断白色输出：验证管线通路（尤其 mojang_logo 管线）。
        // DX12_DIAG_WHITE=1 强制 fragment shader 输出纯白色，屏幕变白=管线通，仍红=纹理/上传问题。
        private val DIAG_WHITE: Boolean = "1" == System.getenv("DX12_DIAG_WHITE")
        // 诊断恒定中灰输出：只替换含 "terrain" 的管线 fragment 输出为恒定灰度。
        // 若截图中地形像素≈该灰度 → present/后期链路线性正确，变暗来自光照采样
        // （UV2/lightmap 乘积）；若显著低于该灰度 → 主世界 RT→屏幕链路系统性压暗。
        // 触发方式（任一）：
        //   1) 环境变量 DX12_DIAG_GRAY=0.5（0.0-1.0）；
        //   2) 在游戏运行目录（instance 根目录）创建空文件 dx12_diag_gray.flag（固定 0.5）。
        private const val GRAY_FLAG_NAME: String = "dx12_diag_gray.flag"
        private var DIAG_GRAY: Float = -1.0f
        private var DIAG_GRAY_SRC: String? = null

        // 光照贴图"全白"探针：把 Lightmap 更新 pass（pipeline/lightmap，每帧用 core/lightmap
        // 片段把光照状态画进 16×16 Lightmap 纹理）的 fragment 输出强制为常量白 → Lightmap
        // 内容整张变白 → 地形/方块采样的光照项恒等于 1.0，同时保留真实方块纹理与顶点色。
        // 若画面明显变亮 → 变暗来自光照乘积（lightmap 采样值 × UV2）；若仍偏暗 → 问题在
        // 更早链路（纹理/基色/上传）。触发方式（任一）：
        //   1) 环境变量 DX12_DIAG_WHITE_LIGHT=1；
        //   2) 在游戏运行目录（instance 根目录）创建空文件 dx12_diag_whitelight.flag。
        private const val WHITE_LIGHT_FLAG_NAME: String = "dx12_diag_whitelight.flag"
        private var DIAG_WHITE_LIGHT: Boolean = false
        private var DIAG_WHITE_LIGHT_SRC: String? = null

        // 诊断 dump：把 terrain 管线最终 HLSL（含 UV2 光照采样表达式）写到游戏运行目录，
        // 便于定位"光照乘积变暗"是 UV2 换算错还是采样坐标错。触发：运行目录存在
        // 空文件 dx12_diag_dumpshader.flag。每个管线只 dump 一次，文件名 = 管线末段 + _frag/_vert.hlsl。
        private const val DUMP_SHADER_FLAG_NAME: String = "dx12_diag_dumpshader.flag"
        private val DIAG_DUMP_SHADER: Boolean = java.io.File(DUMP_SHADER_FLAG_NAME).exists()
        private val gDumpedTerrainShaders: MutableSet<String> = java.util.HashSet()

        // UV2 可视化探针：把 terrain 顶点着色器里的
        //   vertexColor = Color * sample_lightmap(Sampler2, ..., UV2)
        // 替换为直接输出 UV2 数值（除以 240 归一化）→ 画面亮度直接反映 UV2 真实取值：
        // 太阳直射面接近亮橙(≈1.0) 说明 UV2 确为 0..240；若整体发黑/很暗，说明 UV2
        // 数据范围或解码不对（如实际是 0..15 的光照级而非 ×16）。触发：运行目录存在
        // 空文件 dx12_diag_uv2viz.flag。
        private const val UV2_VIZ_FLAG_NAME: String = "dx12_diag_uv2viz.flag"
        private val DIAG_UV2_VIZ: Boolean = java.io.File(UV2_VIZ_FLAG_NAME).exists()

        // Lightmap 可视化探针：terrain 顶点着色器不乘以顶点色，而是把 sample_lightmap(...)
        // 对 Sampler2 的"原始采样结果"直接当作 vertexColor 输出 → 画面颜色 = terrain 在
        // draw 时真正读到的光贴图纹素值。判读：向阳顶面若亮（接近白/暖）说明内容与绑定都
        // 正常，变暗在更下游；若大片暗 → 地形实际采样到的 16×16 内容本身是暗的/绑定错了。
        // 触发：运行目录存在空文件 dx12_diag_lightmapviz.flag。
        private const val LIGHTMAP_VIZ_FLAG_NAME: String = "dx12_diag_lightmapviz.flag"
        private val DIAG_LIGHTMAP_VIZ: Boolean = java.io.File(LIGHTMAP_VIZ_FLAG_NAME).exists()

        // P38 诊断：submit()（共享 encoder = 每帧）后读回 16×16 lightmap，验证内容与朝向。
        // （常量已移至 Dx12Device，此处保留仅供日志参考）
        private const val MAX_LIGHTMAP_DUMPS: Int = 5
        private const val LIGHTMAP_DUMP_INTERVAL: Int = 120

        init {
            // 诊断恒定中灰输出：环境变量优先，其次运行目录 flag 文件（镜像原 static 块）。
            var f = -1.0f
            var src: String? = null
            val env = System.getenv("DX12_DIAG_GRAY")
            if (env != null) {
                try {
                    val v = env.trim().toFloat()
                    if (v >= 0.0f && v <= 1.0f) {
                        f = v
                        src = "env"
                    }
                } catch (ignored: NumberFormatException) {
                }
            }
            if (f < 0.0f && java.io.File(GRAY_FLAG_NAME).exists()) {
                f = 0.5f
                src = "file"
            }
            DIAG_GRAY = f
            DIAG_GRAY_SRC = src

            // 光照贴图"全白"探针触发条件（镜像原 static 块）。
            var on = false
            var wsrc: String? = null
            if ("1" == System.getenv("DX12_DIAG_WHITE_LIGHT")) {
                on = true
                wsrc = "env"
            }
            if (!on && java.io.File(WHITE_LIGHT_FLAG_NAME).exists()) {
                on = true
                wsrc = "file"
            }
            DIAG_WHITE_LIGHT = on
            DIAG_WHITE_LIGHT_SRC = wsrc
        }

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
        private fun addToBindGroup(entries: MutableList<Dx12BindGroupEntry>,
            shader: Dx12IntermediaryShaderModule, pipeline: RenderPipeline) {
            var uniformDescription: Optional<BindGroupLayout.UniformDescription>
            for (buffer in shader.uniformBuffers()) {
                val name = buffer.name()
                uniformDescription = BindGroupLayout.flattenUniforms(pipeline.bindGroupLayouts).stream()
                    .filter { d -> d.name() == name }.findFirst()
                if (uniformDescription.isEmpty) {
                    System.err.printf("[dx12-java] addToBindGroup: uniform '%s' not found in %s%n",
                        name, pipeline.location)
                    throw ShaderCompileException("Unable to find shader defined uniform (" + name + ")")
                }
                if (!entries.stream().noneMatch { e ->
                        e.type == Dx12BindGroupEntry.Type.UNIFORM_BUFFER && e.name == name
                    }) continue
                entries.add(Dx12BindGroupEntry(Dx12BindGroupEntry.Type.UNIFORM_BUFFER, name, null))
            }
            for (sampler in shader.samplers()) {
                val name = sampler.name()
                uniformDescription = BindGroupLayout.flattenUniforms(pipeline.bindGroupLayouts).stream()
                    .filter { d -> d.name() == name }.findFirst()
                if (uniformDescription.isPresent) {
                    if (sampler.dimensions() != 5) {
                        System.err.printf("[dx12-java] addToBindGroup: UTB '%s' in %s has dim %d (expected 5)%n",
                            name, pipeline.location, sampler.dimensions())
                        throw ShaderCompileException("UTB (" + name + ") must have type of SpvDimBuffer")
                    }
                    if (!entries.stream().noneMatch { e ->
                            e.type == Dx12BindGroupEntry.Type.TEXEL_BUFFER && e.name == name
                        }) continue
                    entries.add(Dx12BindGroupEntry(Dx12BindGroupEntry.Type.TEXEL_BUFFER, name,
                        uniformDescription.get().gpuFormat()))
                    continue
                }
                if (BindGroupLayout.flattenSamplers(pipeline.bindGroupLayouts).stream().noneMatch { s -> s == name }) {
                    System.err.printf("[dx12-java] addToBindGroup: sampler '%s' not found in %s%n",
                        name, pipeline.location)
                    throw ShaderCompileException("Unable to find shader defined uniform (" + name + ")")
                }
                if (sampler.dimensions() != 1 && sampler.dimensions() != 3) {
                    System.err.printf("[dx12-java] addToBindGroup: sampled texture '%s' in %s has dim %d (expected 1 or 3)%n",
                        name, pipeline.location, sampler.dimensions())
                    throw ShaderCompileException(
                        "Sampled texture (" + name + ") must have type of SpvDim2D or SpvDimCube")
                }
                if (!entries.stream().noneMatch { e ->
                        e.type == Dx12BindGroupEntry.Type.SAMPLED_IMAGE && e.name == name
                    }) continue
                entries.add(Dx12BindGroupEntry(Dx12BindGroupEntry.Type.SAMPLED_IMAGE, name, null))
            }
        }

        /** 精确匹配含嵌套花括号（for/条件块等）的 frag_main 函数体并替换为纯绿色输出 */
        private fun stripFragMainAndReplace(hlsl: String): String {
            val lines = splitKeepTrailing(hlsl)
            val sb = StringBuilder()
            var i = 0
            while (i < lines.size) {
                val trimmed = lines[i].trim()
                // 匹配 "void frag_main()" 这一行（可能有空白字符），spvc 输出的 { 可能在下一行
                if (FRAG_MAIN_RE.matches(trimmed)) {
                    // 从当前行开始统计括号
                    var braceCount = 0
                    for (c in lines[i].toCharArray()) {
                        if (c == '{') braceCount++
                        else if (c == '}') braceCount--
                    }
                    // 如果当前行没有开括号，继续读下一行直到找到 {
                    while (braceCount <= 0 && i + 1 < lines.size) {
                        i++
                        for (c in lines[i].toCharArray()) {
                            if (c == '{') braceCount++
                            else if (c == '}') braceCount--
                        }
                    }
                    sb.append("void frag_main() { fragColor = float4(0.0, 1.0, 0.0, 1.0); }\n")
                    i++
                    // 继续读行直到平衡所有括号
                    while (i < lines.size && braceCount > 0) {
                        for (c in lines[i].toCharArray()) {
                            if (c == '{') braceCount++
                            else if (c == '}') braceCount--
                        }
                        i++
                    }
                    continue
                }
                sb.append(lines[i]).append('\n')
                i++
            }
            return sb.toString()
        }

        /** 同 stripFragMainAndReplace，但输出纯白色（用于诊断 mojang_logo 管线）。 */
        private fun stripFragMainAndWhite(hlsl: String): String {
            return stripFragMainWithColor(hlsl, "1.0, 1.0, 1.0, 1.0")
        }

        /** 同 stripFragMainAndWhite，但输出恒定灰度（用于诊断主世界合成/呈现链路是否压暗中灰）。 */
        private fun stripFragMainAndGray(hlsl: String, gray: Float): String {
            val c = String.format(java.util.Locale.ROOT, "%.4f", gray)
            return stripFragMainWithColor(hlsl, c + ", " + c + ", " + c + ", 1.0")
        }

        /** 通用：把 spvc 生成的 frag_main 函数体替换为输出指定颜色常量。 */
        private fun stripFragMainWithColor(hlsl: String, colorExpr: String): String {
            val lines = splitKeepTrailing(hlsl)
            val sb = StringBuilder()
            var i = 0
            while (i < lines.size) {
                val trimmed = lines[i].trim()
                if (FRAG_MAIN_RE.matches(trimmed)) {
                    var braceCount = 0
                    for (c in lines[i].toCharArray()) {
                        if (c == '{') braceCount++
                        else if (c == '}') braceCount--
                    }
                    while (braceCount <= 0 && i + 1 < lines.size) {
                        i++
                        for (c in lines[i].toCharArray()) {
                            if (c == '{') braceCount++
                            else if (c == '}') braceCount--
                        }
                    }
                    sb.append("void frag_main() { fragColor = float4(").append(colorExpr).append("); }\n")
                    i++
                    while (i < lines.size && braceCount > 0) {
                        for (c in lines[i].toCharArray()) {
                            if (c == '{') braceCount++
                            else if (c == '}') braceCount--
                        }
                        i++
                    }
                    continue
                }
                sb.append(lines[i]).append('\n')
                i++
            }
            return sb.toString()
        }

        private val FRAG_MAIN_RE: Regex = Regex("void\\s+frag_main\\(\\).*")

        /**
         * 等价于 Java `hlsl.split("\n", -1)`：保留末尾空串（原实现依赖它维持行数不变）。
         * Kotlin 的 `String.split(delimiter, limit = 0)` 与 Java 的 limit=-1 语义一致。
         */
        private fun splitKeepTrailing(hlsl: String): List<String> {
            return hlsl.split("\n", limit = 0)
        }
    }
}
