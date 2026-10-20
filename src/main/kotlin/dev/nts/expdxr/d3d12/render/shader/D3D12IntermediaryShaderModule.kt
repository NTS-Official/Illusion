package dev.nts.expdxr.d3d12.render.shader

import com.mojang.blaze3d.vulkan.glsl.ShaderCompileException
import dev.nts.expdxr.d3d12.backend.D3D12BindGroupEntry
import java.nio.ByteBuffer
import java.nio.IntBuffer
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil
import org.lwjgl.util.spvc.Spvc
import org.lwjgl.util.spvc.SpvcHlslVertexAttributeRemap
import org.lwjgl.util.spvc.SpvcReflectedResource

/**
 * 镜像官方 `com.mojang.blaze3d.vulkan.glsl.IntermediaryShaderModule`。
 *
 * 持有 shaderc 编译出的 SPIR-V 字节码 + spvc 反射出的绑定信息：
 * uniform buffer（SPVC 资源类型 1）、sampled image（7）、输出（4）、输入（3）。
 * [rebind] 按管线绑定顺序把 SPIR-V 中的 binding/location decoration 重写；
 * [toHlsl] 用 spvc 的 HLSL 后端（SM5.1）把重写后的 SPIR-V 转成 HLSL，
 * 交给原生层 D3DCompile 出 DXBC。
 */
class D3D12IntermediaryShaderModule(
    private val name: String,
    private val spirv: ByteBuffer?,
    private val uniformBuffers: List<SpvUniformBuffer>,
    private val samplers: List<SpvSampler>,
    private val outputs: List<SpvVariable>,
    private val inputs: List<SpvVariable>
) : AutoCloseable {

    /** SPIR-V 中 uniform buffer 块（bindingOffset 指向 binding decoration 的字节偏移）。 */
    class SpvUniformBuffer(private val name: String, private val bindingOffset: Int) {
        fun name(): String = name

        fun bindingOffset(): Int = bindingOffset
    }

    /** SPIR-V 中 combined image sampler（dimensions: 1=2D, 3=Cube, 5=Buffer）。 */
    class SpvSampler(private val name: String, private val bindingOffset: Int, private val dimensions: Int) {
        fun name(): String = name

        fun bindingOffset(): Int = bindingOffset

        fun dimensions(): Int = dimensions
    }

    /** SPIR-V 中输入/输出变量（locationOffset 指向 location decoration 的字节偏移）。 */
    class SpvVariable(private val name: String, private val locationOffset: Int) {
        fun name(): String = name

        fun locationOffset(): Int = locationOffset
    }

    fun name(): String = name

    fun spirv(): ByteBuffer? = spirv

    fun uniformBuffers(): List<SpvUniformBuffer> = uniformBuffers

    fun samplers(): List<SpvSampler> = samplers

    fun outputs(): List<SpvVariable> = outputs

    fun inputs(): List<SpvVariable> = inputs

    override fun close() {
        if (this.spirv != null) {
            MemoryUtil.memFree(this.spirv)
        }
    }

    /**
     * 按管线绑定顺序重写 SPIR-V 中的绑定/位置：
     * 输入变量按 vertex format 属性名顺序编 attribLocation；UBO/sampler 按
     * [entries] 顺序编 binding。剩余未匹配的资源会抛异常（官方行为）。
     */
    @Throws(ShaderCompileException::class)
    fun rebind(inputVariables: List<String>, entries: List<D3D12BindGroupEntry>) {
        if (this.spirv == null) {
            throw IllegalStateException("Attempt to use invalid shader")
        }
        val spvAsIntBuffer = this.spirv.asIntBuffer()
        val remainingInputs = mutableSetOf<String>()
        val remainingSamplers = mutableSetOf<String>()
        val remainingUniformBuffers = mutableSetOf<String>()
        for (input in this.inputs) {
            remainingInputs.add(input.name())
        }
        for (uniformBuffer in this.uniformBuffers) {
            remainingUniformBuffers.add(uniformBuffer.name())
        }
        for (sampler in this.samplers) {
            remainingSamplers.add(sampler.name())
        }
        var previousName: String? = null
        var attribLocation = 0
        for (i in 0 until inputVariables.size) {
            val variableName = inputVariables[i]
            val inputVariable = this.getInputVariable(variableName)
            if (inputVariable == null) continue
            if (variableName != previousName) {
                spvAsIntBuffer.put(inputVariable.locationOffset(), attribLocation)
                remainingInputs.remove(variableName)
            }
            ++attribLocation
            previousName = variableName
        }
        for (i in 0 until entries.size) {
            val entry = entries[i]
            when (entry.type) {
                D3D12BindGroupEntry.Type.UNIFORM_BUFFER -> {
                    val ubo = this.getUniformBuffer(entry.name)
                    if (ubo != null) {
                        spvAsIntBuffer.put(ubo.bindingOffset(), i)
                        remainingUniformBuffers.remove(entry.name)
                    }
                }
                D3D12BindGroupEntry.Type.SAMPLED_IMAGE -> {
                    val sampler = this.getSampler(entry.name)
                    if (sampler != null) {
                        if (sampler.dimensions() != 1 && sampler.dimensions() != 3) {
                            throw ShaderCompileException("Unsupported texture dimensions '${sampler.dimensions()}"
                                + "' for sampler ${entry.name}")
                        }
                        spvAsIntBuffer.put(sampler.bindingOffset(), i)
                        remainingSamplers.remove(entry.name)
                    }
                }
                D3D12BindGroupEntry.Type.TEXEL_BUFFER -> {
                    val sampler = this.getSampler(entry.name)
                    if (sampler != null) {
                        if (sampler.dimensions() != 5) {
                            throw ShaderCompileException("Unsupported texel buffer dimensions '"
                                + "${sampler.dimensions()}' for sampler ${entry.name}")
                        }
                        spvAsIntBuffer.put(sampler.bindingOffset(), i)
                        remainingSamplers.remove(entry.name)
                    }
                }
            }
        }
        if (remainingInputs.isNotEmpty()) {
            throw ShaderCompileException(
                "Shader expects input variables which are not being provided: ${remainingInputs}")
        }
        if (remainingUniformBuffers.isNotEmpty()) {
            throw ShaderCompileException(
                "Shader expects uniform buffers which are not being provided: ${remainingUniformBuffers}")
        }
        if (remainingSamplers.isNotEmpty()) {
            throw ShaderCompileException(
                "Shader expects samplers which are not being provided: ${remainingSamplers}")
        }
    }

    /**
     * 用 spvc HLSL 后端（SPVC_BACKEND_HLSL=2）把（已 rebind 的）SPIR-V 转成
     * HLSL，shader model 5.1（SPVC_COMPILER_OPTION_HLSL_SHADER_MODEL=51）。
     * 原生层随后用 d3dcompiler_47 把 HLSL 编成 vs_5_1/ps_5_1 字节码。
     */
    @Throws(ShaderCompileException::class)
    fun toHlsl(isVertex: Boolean): String {
        val spv = this.spirv
        if (spv == null) {
            throw IllegalStateException("Attempt to use invalid shader")
        }
        return MemoryStack.stackPush().use { stack ->
            val pointer = stack.callocPointer(1)
            throwIfError(Spvc.spvc_context_create(pointer), "Couldn't create spvc context")
            val context = pointer.get(0)
            try {
                throwIfError(Spvc.spvc_context_parse_spirv(context, spv.asIntBuffer(),
                    (spv.remaining() / 4).toLong(), pointer), "Couldn't parse spirv")
                val ir = pointer.get(0)
                throwIfError(Spvc.spvc_context_create_compiler(context, Spvc.SPVC_BACKEND_HLSL, ir, 1, pointer),
                    "Couldn't create HLSL compiler")
                val compiler = pointer.get(0)
                throwIfError(Spvc.spvc_compiler_create_compiler_options(compiler, pointer),
                    "Couldn't create compiler options")
                val options = pointer.get(0)
                throwIfError(Spvc.spvc_compiler_options_set_uint(options,
                    Spvc.SPVC_COMPILER_OPTION_HLSL_SHADER_MODEL, 51), "Couldn't set HLSL shader model")
                // P6 修复：GLSL 的 gl_PointSize / gl_PointCoord 在 SPIRV-Cross HLSL
                // 后端默认不支持（SPVC_ERROR_UNSUPPORTED_SPIRV）。vanilla
                // debug_point 管线（ShaderManager 必需）在 debug_point.vsh 里写
                // gl_PointSize = LineWidth，若不启用兼容选项则编译失败 → ShaderManager
                // apply() 抛异常 → 资源包全部移除 → UI 黑屏。启用后 spvc 会把点大小
                // 输出映射到 SV_PointSize / PSIZE 语义，点坐标映射为 PSIZE/语义。
                throwIfError(Spvc.spvc_compiler_options_set_uint(options,
                    Spvc.SPVC_COMPILER_OPTION_HLSL_POINT_SIZE_COMPAT, 1),
                    "Couldn't set HLSL point size compat")
                throwIfError(Spvc.spvc_compiler_options_set_uint(options,
                    Spvc.SPVC_COMPILER_OPTION_HLSL_POINT_COORD_COMPAT, 1),
                    "Couldn't set HLSL point coord compat")
                throwIfError(Spvc.spvc_compiler_install_compiler_options(compiler, options),
                    "Couldn't install compiler options")
                // Fix S2：为顶点着色器输入注入 D3D12 语义，避免 spvc 后端不生成正确语义导致
                // CreateGraphicsPipelineState E_INVALIDARG。location 0 → POSITION，其余 → TEXCOORD<n>。
                if (isVertex && this.inputs.isNotEmpty()) {
                    var inputIdx = 0
                    var prevLoc = -1
                    for (input in this.inputs) {
                        val loc = spv.asIntBuffer().get(input.locationOffset())
                        if (loc != prevLoc) {
                            // BUG-02 fix: 语义按 SPIR-V location 分配（rebind 后 location 与顶点
                            // 格式元素顺序一致：0→POSITION, n→TEXCOORD(n-1)），与 D3D12Device
                            // buildVertexInputElements 的 attribLocation 对齐。原实现按反射顺序
                            // （GLSL 声明顺序）分配，text.vsh 声明 Position,Color,UV0（格式为
                            // Position,UV0,Color）→ Color 得 TEXCOORD0、UV0 得 TEXCOORD1，
                            // 与 input layout 互换：TEXCOORD0 读 UV0 数据、TEXCOORD1 读 Color
                            // 数据 → 字形采样坐标错误（GUI 文字消失/错乱）。
                            val semantic = if (loc == 0) "POSITION" else "TEXCOORD${loc - 1}"
                            val remap = SpvcHlslVertexAttributeRemap.calloc(stack)
                            remap.location(loc)
                            remap.semantic(stack.UTF8(semantic))
                            throwIfError(Spvc.spvc_compiler_hlsl_add_vertex_attribute_remap(
                                compiler, remap, 1L), "Couldn't add vertex attribute remap")
                            ++inputIdx
                            prevLoc = loc
                        }
                    }
                }
                val compileResult = Spvc.spvc_compiler_compile(compiler, pointer)
                if (compileResult != 0) {
                    // P6 诊断：附加 SPIRV-Cross 内部错误描述（如具体哪个 builtin/指令不支持），
                    // 便于定位剩余管线编译失败的具体原因。
                    val detail = Spvc.spvc_context_get_last_error_string(context)
                    throwIfError(compileResult, "Couldn't compile HLSL"
                        + (if (detail != null && detail.isNotEmpty()) ": ${detail}" else ""))
                }
                val address = pointer.get(0)
                val hlsl = MemoryUtil.memUTF8(address)
                // Fix S2：语义通过 spvc_compiler_hlsl_add_vertex_attribute_remap 在编译前注入，
                // 确保 spvc HLSL 后端输出正确的 D3D12 语义（POSITION/TEXCOORD<n>）。
                val result = hlsl
                result
            } finally {
                Spvc.spvc_context_destroy(context)
            }
        }
    }

    private fun getUniformBuffer(name: String): SpvUniformBuffer? {
        for (ubo in this.uniformBuffers) {
            if (ubo.name() == name) {
                return ubo
            }
        }
        return null
    }

    private fun getSampler(name: String): SpvSampler? {
        for (sampler in this.samplers) {
            if (sampler.name() == name) {
                return sampler
            }
        }
        return null
    }

    private fun getInputVariable(name: String): SpvVariable? {
        for (variable in this.inputs) {
            if (variable.name() == name) {
                return variable
            }
        }
        return null
    }

    /**
     * 为单次编译创建 SPIR-V 工作副本。
     * rebind() 会原地修改 SPIR-V 的 decoration 字段，必须克隆后操作，
     * 否则同一缓存模块被多次 rebind() 后互相污染。
     */
    fun cloneForCompile(): D3D12IntermediaryShaderModule {
        if (this.spirv == null) return this
        val dup = MemoryUtil.memCalloc(this.spirv.remaining())
        MemoryUtil.memCopy(this.spirv, dup)
        return D3D12IntermediaryShaderModule(
            this.name, dup, this.uniformBuffers, this.samplers, this.outputs, this.inputs)
    }

    companion object {
        /** 无有效 SPIR-V 的占位模块（官方 INVALID）。 */
        @JvmField
        val INVALID: D3D12IntermediaryShaderModule =
            D3D12IntermediaryShaderModule("invalid", null, listOf(), listOf(), listOf(), listOf())

        /** static 关键字后允许的字符集合（区分 static float4 与 static_cast）。 */
        private val AFTER_STATIC_ALLOWED: Set<Char> = setOf(' ', '\t', '\n', '\r')

        /**
         * 用 spvc 解析 SPIR-V 并反射出 UBO/sampler/输出/输入绑定信息，
         * 随后把输出变量 location 打平为 0..n-1（与官方行为一致）。
         */
        @JvmStatic
        @Throws(ShaderCompileException::class)
        fun createFromSpirv(filename: String, spirv: ByteBuffer): D3D12IntermediaryShaderModule {
            val uniformBuffers = mutableListOf<SpvUniformBuffer>()
            val samplers = mutableListOf<SpvSampler>()
            val outputs = mutableListOf<SpvVariable>()
            val inputs = mutableListOf<SpvVariable>()
            MemoryStack.stackPush().use { stack ->
                val pointer = stack.callocPointer(1)
                val intReturnBuffer = stack.callocInt(1)
                throwIfError(Spvc.spvc_context_create(pointer), "Couldn't create spvc context")
                val context = pointer.get(0)
                try {
                    throwIfError(Spvc.spvc_context_parse_spirv(context, spirv.asIntBuffer(),
                        (spirv.remaining() / 4).toLong(), pointer), "Couldn't parse spirv")
                    val ir = pointer.get(0)
                    throwIfError(Spvc.spvc_context_create_compiler(context, 0, ir, 1, pointer),
                        "Couldn't create compiler")
                    val compiler = pointer.get(0)
                    throwIfError(Spvc.spvc_compiler_create_shader_resources(compiler, pointer),
                        "Couldn't create resource list")
                    val spvcResources = pointer.get(0)
                    val countPointer = stack.callocPointer(1)

                    // 1 = UNIFORM_BUFFER
                    throwIfError(Spvc.spvc_resources_get_resource_list_for_type(spvcResources, 1,
                        pointer, countPointer), "Couldn't list uniform buffers")
                    var spvcList = pointer.get(0)
                    var spvcCount = countPointer.get(0)
                    var resources = SpvcReflectedResource.create(spvcList, spvcCount.toInt())
                    for (i in 0 until spvcCount.toInt()) {
                        val resource = resources.get(i)
                        val name = resource.nameString()
                        val bindingOffset = getDecorationOffset(compiler, resource, 33, intReturnBuffer)
                        uniformBuffers.add(SpvUniformBuffer(name, bindingOffset))
                    }

                    // 7 = SAMPLED_IMAGE
                    throwIfError(Spvc.spvc_resources_get_resource_list_for_type(spvcResources, 7,
                        pointer, countPointer), "Couldn't list sampled images")
                    spvcList = pointer.get(0)
                    spvcCount = countPointer.get(0)
                    resources = SpvcReflectedResource.create(spvcList, spvcCount.toInt())
                    for (i in 0 until spvcCount.toInt()) {
                        val resource = resources.get(i)
                        val name = resource.nameString()
                        val bindingOffset = getDecorationOffset(compiler, resource, 33, intReturnBuffer)
                        val typeHandle = Spvc.spvc_compiler_get_type_handle(compiler, resource.type_id())
                        val dimension = Spvc.spvc_type_get_image_dimension(typeHandle)
                        samplers.add(SpvSampler(name, bindingOffset, dimension))
                    }

                    // 4 = STAGE_OUTPUT
                    throwIfError(Spvc.spvc_resources_get_resource_list_for_type(spvcResources, 4,
                        pointer, countPointer), "Couldn't list output variables")
                    spvcList = pointer.get(0)
                    spvcCount = countPointer.get(0)
                    resources = SpvcReflectedResource.create(spvcList, spvcCount.toInt())
                    for (i in 0 until spvcCount.toInt()) {
                        val resource = resources.get(i)
                        val name = resource.nameString()
                        val locationOffset = getDecorationOffset(compiler, resource, 30, intReturnBuffer)
                        outputs.add(SpvVariable(name, locationOffset))
                    }

                    // 3 = STAGE_INPUT
                    throwIfError(Spvc.spvc_resources_get_resource_list_for_type(spvcResources, 3,
                        pointer, countPointer), "Couldn't list input variables")
                    spvcList = pointer.get(0)
                    spvcCount = countPointer.get(0)
                    resources = SpvcReflectedResource.create(spvcList, spvcCount.toInt())
                    for (i in 0 until spvcCount.toInt()) {
                        val resource = resources.get(i)
                        val name = resource.nameString()
                        val locationOffset = getDecorationOffset(compiler, resource, 30, intReturnBuffer)
                        inputs.add(SpvVariable(name, locationOffset))
                    }
                } finally {
                    Spvc.spvc_context_destroy(context)
                }
            }
            val spvAsIntBuffer = spirv.asIntBuffer()
            for (i in 0 until outputs.size) {
                spvAsIntBuffer.put(outputs[i].locationOffset(), i)
            }
            return D3D12IntermediaryShaderModule(filename, spirv, uniformBuffers, samplers, outputs, inputs)
        }

        @Throws(ShaderCompileException::class)
        private fun throwIfError(result: Int, message: String) {
            if (result != 0) {
                val name = when (result) {
                    -1 -> "SPVC_ERROR_INVALID_SPIRV"
                    -2 -> "SPVC_ERROR_UNSUPPORTED_SPIRV"
                    -3 -> "SPVC_ERROR_OUT_OF_MEMORY"
                    -4 -> "SPVC_ERROR_INVALID_ARGUMENT"
                    else -> result.toString()
                }
                throw ShaderCompileException("${message} (${name})")
            }
        }

        /** 读取 SPIR-V 二进制中某 decoration（33=BINDING / 30=LOCATION）的字节偏移。 */
        @Throws(ShaderCompileException::class)
        private fun getDecorationOffset(compiler: Long, resource: SpvcReflectedResource,
            decoration: Int, returnBuffer: IntBuffer): Int {
            if (!Spvc.spvc_compiler_get_binary_offset_for_decoration(compiler, resource.id(),
                decoration, returnBuffer)) {
                throw ShaderCompileException(
                    "Couldn't find byte offset for location decoration of ${resource.nameString()}")
            }
            return returnBuffer.get(0)
        }
    }
}
