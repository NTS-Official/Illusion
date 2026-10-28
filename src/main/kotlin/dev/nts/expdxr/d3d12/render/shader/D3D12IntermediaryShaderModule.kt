package dev.nts.expdxr.d3d12.render.shader

import com.mojang.renderpearl.util.ShaderCompileException
import dev.nts.expdxr.d3d12.backend.D3D12BindGroupEntry
import java.nio.ByteBuffer
import java.nio.IntBuffer
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil
import org.lwjgl.util.spvc.Spvc
import org.lwjgl.util.spvc.SpvcHlslVertexAttributeRemap
import org.lwjgl.util.spvc.SpvcReflectedResource

/**
 * 一个着色器模块：SPIR-V 字节码 + 反射快照（UBO/sampler/in-out 的 decoration 字节偏移）。
 * 改写规则与寄存器约定见 docs/shaders/README.md §6、§10
 */
class D3D12IntermediaryShaderModule(
    private val name: String,
    private val spirv: ByteBuffer?,
    private val uniformBuffers: List<SpvUniformBuffer>,
    private val samplers: List<SpvSampler>,
    private val outputs: List<SpvVariable>,
    private val inputs: List<SpvVariable>
) : AutoCloseable {

    /** bindingOffset 是 SPIR-V 内的字节偏移（不是字下标）。 */
    class SpvUniformBuffer(private val name: String, private val bindingOffset: Int) {
        fun name(): String = name

        fun bindingOffset(): Int = bindingOffset
    }

    /** dimensions: 1=2D、3=Cube、5=Buffer。 */
    class SpvSampler(private val name: String, private val bindingOffset: Int, private val dimensions: Int) {
        fun name(): String = name

        fun bindingOffset(): Int = bindingOffset

        fun dimensions(): Int = dimensions
    }

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
     * 原地改写 decoration：location 按 [inputVariables] 顺序编号、binding 直接取 [entries] 下标 i；
     * 同名多 location（矩阵）共享起始点后连续递增；未被消耗的反射项一律抛异常。
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
     * spvc HLSL 后端生成源码：`HLSL_SHADER_MODEL = 51` 只是语法档（DXIL 由 DXC 按 6_9 编），
     * 两个 `POINT_*_COMPAT` 必须开，否则 vanilla `debug_point` 编译失败 → 资源包被移除、UI 黑屏。
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
                throwIfError(Spvc.spvc_compiler_options_set_uint(options,
                    Spvc.SPVC_COMPILER_OPTION_HLSL_POINT_SIZE_COMPAT, 1),
                    "Couldn't set HLSL point size compat")
                throwIfError(Spvc.spvc_compiler_options_set_uint(options,
                    Spvc.SPVC_COMPILER_OPTION_HLSL_POINT_COORD_COMPAT, 1),
                    "Couldn't set HLSL point coord compat")
                throwIfError(Spvc.spvc_compiler_install_compiler_options(compiler, options),
                    "Couldn't install compiler options")
                if (isVertex && this.inputs.isNotEmpty()) {
                    var inputIdx = 0
                    var prevLoc = -1
                    for (input in this.inputs) {
                        val loc = spv.asIntBuffer().get(input.locationOffset())
                        if (loc != prevLoc) {
                            // 语义按 SPIR-V location 分配（0→POSITION，n→TEXCOORD(n-1)），与顶点声明顺序无关。
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
                    // 附上 SPIRV-Cross 的错误描述，便于定位是哪个 builtin/指令不支持。
                    val detail = Spvc.spvc_context_get_last_error_string(context)
                    throwIfError(compileResult, "Couldn't compile HLSL"
                        + (if (detail != null && detail.isNotEmpty()) ": ${detail}" else ""))
                }
                val address = pointer.get(0)
                val hlsl = MemoryUtil.memUTF8(address)
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

    /** SPIR-V 工作副本：rebind 是原地改写，缓存里的原始模块永不修改。 */
    fun cloneForCompile(): D3D12IntermediaryShaderModule {
        if (this.spirv == null) return this
        val dup = MemoryUtil.memCalloc(this.spirv.remaining())
        MemoryUtil.memCopy(this.spirv, dup)
        return D3D12IntermediaryShaderModule(
            this.name, dup, this.uniformBuffers, this.samplers, this.outputs, this.inputs)
    }

    companion object {
        /** 无效模块占位（`spirv == null`）；对它 [rebind]/[toHlsl] 抛异常。 */
        @JvmField
        val INVALID: D3D12IntermediaryShaderModule =
            D3D12IntermediaryShaderModule("invalid", null, listOf(), listOf(), listOf(), listOf())

        /** static 之后允许的字符（区分 `static float4` 与 `static_cast`）。 */
        private val AFTER_STATIC_ALLOWED: Set<Char> = setOf(' ', '\t', '\n', '\r')

        /** 反射 UBO/sampler/output/input，并把输出变量的 location 打平为 0..n-1。 */
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

        /** decoration 的字节偏移（33=BINDING、30=LOCATION）。 */
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
