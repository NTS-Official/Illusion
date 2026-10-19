package dev.nts.expdxr.d3d12.render.adapter

import dev.nts.expdxr.utils.IllusionLogger

import com.mojang.blaze3d.GpuFormat
import com.mojang.blaze3d.buffers.GpuBuffer
import com.mojang.blaze3d.pipeline.ColorTargetState
import com.mojang.blaze3d.pipeline.CompiledRenderPipeline
import com.mojang.blaze3d.pipeline.DepthStencilState
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.platform.CompareOp
import com.mojang.blaze3d.preprocessor.GlslPreprocessor
import com.mojang.blaze3d.shaders.ShaderSource
import com.mojang.blaze3d.shaders.ShaderType
import com.mojang.blaze3d.systems.*
import com.mojang.blaze3d.textures.*
import com.mojang.blaze3d.vulkan.glsl.ShaderCompileException
import dev.nts.expdxr.d3d12.backend.D3D12CommandEncoderBackend
import dev.nts.expdxr.d3d12.render.advanced.mesh.D3D12MeshPipelineCache
import dev.nts.expdxr.d3d12.render.core.D3D12GpuQueryPool
import dev.nts.expdxr.d3d12.render.core.D3D12GpuSurface
import dev.nts.expdxr.d3d12.render.core.D3D12AsyncContext
import dev.nts.expdxr.d3d12.backend.D3D12BindGroupEntry
import dev.nts.expdxr.d3d12.backend.D3D12CompiledRenderPipeline
import dev.nts.expdxr.d3d12.render.shader.D3D12CompiledShader
import dev.nts.expdxr.d3d12.render.shader.D3D12FallbackShaderSource
import dev.nts.expdxr.d3d12.render.core.D3D12GpuBuffer
import dev.nts.expdxr.d3d12.render.core.D3D12GpuTexture
import dev.nts.expdxr.d3d12.render.core.D3D12GpuTextureView
import dev.nts.expdxr.d3d12.extern.MCDRPipelineDesc
import dev.nts.expdxr.d3d12.D3D12Settings
import dev.nts.expdxr.d3d12.extern.MCDR_ABI
import dev.nts.expdxr.d3d12.render.shader.D3D12GpuSampler
import dev.nts.expdxr.d3d12.render.shader.D3D12IntermediaryShaderModule
import dev.nts.expdxr.d3d12.render.shader.D3D12ShaderCompiler
import net.minecraft.client.renderer.ShaderDefines
import net.minecraft.resources.Identifier
import org.lwjgl.glfw.GLFWNativeWin32
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.util.*
import java.util.function.Supplier

/**
 * D3D12 implementation of the vanilla [GpuDeviceBackend] factory.
 *
 * P2 scope: every resource-creating method (texture / buffer / sampler /
 * texture view) creates real D3D12 resources through dx12_mc.dll. The render
 * pipeline methods (surface / command encoder / pipeline / query pool) are
 * stubbed and arrive in P3+.
 */
class D3D12Device(private val defaultShaderSource: ShaderSource?) : GpuDeviceBackend {
    private val deviceInfo: DeviceInfo
    private var timestampCtx: Long = 0L

    /**
     * P124：mesh shader 管线的独立缓存（见 [D3D12MeshPipelineCache]）。
     *
     * <p>不挂在 `pipelineCache` 上：那张表的键是 vanilla `RenderPipeline`，mesh 管线（MS/AS、
     * 无 IA）表达不出来。管线对象由本设备持有，`clearPipelineCache()` 里一并释放。
     */
    val meshPipelineCache: D3D12MeshPipelineCache = D3D12MeshPipelineCache()

    // 官方 VulkanDevice 构造时创建一次共享 CommandEncoder，createCommandEncoder()
    // 每次返回同一实例（blit 与 submit 用同一 encoder，整帧命令一次提交）。
    // 之前每次 new 一个独立 CommandContext：绘制指令记在 A、帧末 submit 却提交
    // 新建的空 ctx B → GPU 每帧执行空命令列表 → backbuffer 呈现未初始化垃圾
    // （疯狂闪烁）。改为懒加载共享单例复现官方语义。
    private var sharedCommandEncoder: D3D12CommandEncoderBackend? = null
    // P104b：同一实例登记到伴生对象，供**帧中**需要往当前命令列表追加后处理的地方取用
    // （世界层 SMAA：`D3D12SMAAWorld` 在 GameRenderer 画完世界、GUI 之前调用一次）。
    // 官方 GpuDevice 不暴露 backend，Java 侧也拿不到 CommandEncoderBackend，所以留这个出口。

    // P4: pipeline + shader caches（镜像官方 VulkanDevice）
    private val pipelineCache: MutableMap<RenderPipeline, D3D12CompiledRenderPipeline> = IdentityHashMap()
    /**
     * P31：flipY 变体管线缓存（GUI 离屏 pass 专用，避免与主世界实体管线共享）。
     * 注意：键是值语义的 [FlipYKey]（每次调用都会新建实例），必须用 HashMap
     * 按 equals/hashCode 查找；若误用 IdentityHashMap 则按 `==` 比较，永远 miss
     * → flipY 管线每帧重编译（spvc + HLSL dump + D3DCompile），是低帧率主因。
     */
    private val pipelineCacheFlipY: MutableMap<FlipYKey, D3D12CompiledRenderPipeline> = HashMap()
    private val shaderCache: MutableMap<ShaderCompilationKey, D3D12IntermediaryShaderModule> = HashMap()
    private var glslCompiler: D3D12ShaderCompiler? = null

    init {
        val probe = MCDR_ABI.ID3D12CreateDevice()
        LOGGER.info("[backend] Device probe + resource self-test: {}", probe)
        val frequency = MCDR_ABI.D3D12GetTimestampFrequency()
        if (frequency == 0L) {
            LOGGER.warn("[backend] GetTimestampFrequency returned 0; timestampPeriod=1.0")
        }
        // P34: real adapter facts from DXGI/DXCore replace the previously hardcoded
        // "D3D12" / "D3D12 driver" / DeviceType.DISCRETE. D3D12AdapterInfo.current is what
        // the F3 mixin and the mod-supplied system-specs lines read; it stays null when
        // the query fails so the F3 block falls back to the vanilla-looking constants.
        val nativeInfo = D3D12AdapterInfo.parse(MCDR_ABI.ID3D12GetAdapterInfo())
        if (nativeInfo !== D3D12AdapterInfo.UNKNOWN) {
            D3D12AdapterInfo.current = nativeInfo
            LOGGER.info("[backend] Adapter: {}", nativeInfo.summary())
        } else {
            LOGGER.warn("[backend] Adapter info unavailable; keeping the probe-derived fallback")
        }
        this.deviceInfo = buildDeviceInfo(parseAdapterName(probe), nativeInfo, frequency)
        // P33：启动独立渲染线程（异步流水线：渲染线程负责 acquire/present/fence-wait，
        // 主线程负责命令录制）。在 createCommandEncoder 之前初始化，确保渲染线程已就绪。
        // 诊断开关：配置 `[debug] asyncRenderThread = false` 时不启动异步渲染线程（只在取证时使用）。
        if (D3D12Settings.asyncRenderThread) {
            MCDR_ABI.D3D12InitAsyncRenderer(1)
        } else {
            LOGGER.warn("[backend] async renderer disabled ([debug] asyncRenderThread = false) — diagnostic mode only")
        }
        // P33：初始化 CPU/GPU 并行录制上下文（分区描述符堆 + bundle 池 + worker 池）。
        // 失败会自动退回单线程录制，不影响功能。
        D3D12AsyncContext.init()
        initialized = true
    }

    // -----------------------------------------------------------------------
    // P2: real D3D12 resources
    // -----------------------------------------------------------------------

    override fun createSampler(addressModeU: AddressMode, addressModeV: AddressMode,
        minFilter: FilterMode, magFilter: FilterMode, maxAnisotropy: Int, maxLod: OptionalDouble): GpuSampler {
        return D3D12GpuSampler(addressModeU, addressModeV, minFilter, magFilter, maxAnisotropy, maxLod)
    }

    override fun createTexture(label: Supplier<String>?, usage: @GpuTexture.Usage Int,
        format: GpuFormat, width: Int, height: Int, depthOrLayers: Int, mipLevels: Int): GpuTexture {
        val name = label?.get() ?: ""
        return D3D12GpuTexture(
            usage, name, format, width, height,
            depthOrLayers, mipLevels
        )
    }

    override fun createTexture(label: String?, usage: @GpuTexture.Usage Int,
        format: GpuFormat, width: Int, height: Int, depthOrLayers: Int, mipLevels: Int): GpuTexture {
        val name = label ?: ""
        return D3D12GpuTexture(
            usage, name, format, width, height,
            depthOrLayers, mipLevels
        )
    }

    override fun createTextureView(texture: GpuTexture): GpuTextureView {
        return this.createTextureView(texture, 0, texture.mipLevels)
    }

    override fun createTextureView(texture: GpuTexture, baseMipLevel: Int, mipLevels: Int): GpuTextureView {
        return D3D12GpuTextureView(texture, baseMipLevel, mipLevels)
    }

    override fun createBuffer(label: Supplier<String>?, usage: @GpuBuffer.Usage Int, size: Long): GpuBuffer {
        // P75：保留 label（识别 UBO 身份用，见 D3D12GpuBuffer.label）。
        return D3D12GpuBuffer(usage, size, label?.get() ?: "")
    }

    override fun createBuffer(label: Supplier<String>?, usage: @GpuBuffer.Usage Int,
        data: ByteBuffer): GpuBuffer {
        // Mirror of the official VulkanDevice.createBuffer(data): allocate a buffer
        // with COPY_DST and upload the data through the command encoder.
        val buffer = this.createBuffer(label, usage or GpuBuffer.USAGE_COPY_DST, data.remaining().toLong())
        val encoder = D3D12CommandEncoderBackend()
        try {
            encoder.writeToBuffer(buffer.slice(), data)
            encoder.submit()
        } finally {
            encoder.close()
        }
        return buffer
    }

    // -----------------------------------------------------------------------
    // P3+: stubbed until the render layer lands
    // -----------------------------------------------------------------------

    override fun createSurface(windowHandle: Long): GpuSurfaceBackend {
        val hwnd = GLFWNativeWin32.glfwGetWin32Window(windowHandle)
        if (hwnd == 0L) {
            throw IllegalStateException("glfwGetWin32Window returned null for window ${windowHandle}")
        }
        return D3D12GpuSurface(hwnd)
    }

    override fun createCommandEncoder(): CommandEncoderBackend {
        // 共享单例：全帧 blit/绘制/submit 落同一 CommandContext（官方语义）。
        var encoder = this.sharedCommandEncoder
        if (encoder == null) {
            encoder = D3D12CommandEncoderBackend(this)
            this.sharedCommandEncoder = encoder
        }
        // P104b：登记到伴生对象（见 sharedCommandEncoder() 的注释）。
        sharedEncoderForFrame = encoder
        return encoder
    }

    /** P31：管线+flipY 的组合缓存键。 */
    private data class FlipYKey(val pipeline: RenderPipeline, val flipY: Boolean)

    fun getOrCompilePipeline(pipeline: RenderPipeline, flipY: Boolean): D3D12CompiledRenderPipeline {
        if (!flipY) {
            return this.pipelineCache.computeIfAbsent(pipeline,
                { ignored -> this.compilePipeline(pipeline, this.defaultShaderSource, false) })
        }
        return this.pipelineCacheFlipY.computeIfAbsent(FlipYKey(pipeline, true),
            { ignored -> this.compilePipeline(pipeline, this.defaultShaderSource, true) })
    }

    override fun precompilePipeline(pipeline: RenderPipeline,
        shaderSource: ShaderSource?): CompiledRenderPipeline {
        val source = shaderSource ?: this.defaultShaderSource
        return this.pipelineCache.computeIfAbsent(pipeline,
            { ignored -> this.compilePipeline(pipeline, source, false) })
    }

    override fun clearPipelineCache() {
        // P124：mesh 管线先释放（同一设备持有，必须在设备销毁前）。
        this.meshPipelineCache.releaseAll()
        this.pipelineCache.values.forEach { p -> p.close() }
        this.pipelineCache.clear()
        this.pipelineCacheFlipY.values.forEach { p -> p.close() }
        this.pipelineCacheFlipY.clear()
        this.shaderCache.values.forEach { s -> s.close() }
        this.shaderCache.clear()
    }

    override fun createTimestampQueryPool(size: Int): GpuQueryPool {
        return D3D12GpuQueryPool(size)
    }

    override fun getTimestampNow(): Long {
        // The native call needs a command context; keep one lazily for timestamps.
        if (this.timestampCtx == 0L) {
            this.timestampCtx = MCDR_ABI.D3D12CreateCommandEncoder()
            if (this.timestampCtx == 0L) {
                throw IllegalStateException("dx12CreateCommandEncoder returned a null handle")
            }
        }
        return MCDR_ABI.D3D12GetTimestampNow(this.timestampCtx)
    }

    override fun getLastDebugMessages(): MutableList<String> {
        return ArrayList()
    }

    override fun isDebuggingEnabled(): Boolean {
        return false
    }

    override fun getDeviceInfo(): DeviceInfo {
        return this.deviceInfo
    }
    override fun close() {
        // 镜像官方 VulkanDevice.close()：先销毁共享 encoder 再清管线缓存。
        // 注意：encoder.close() 对共享 encoder 不调用 dx12DestroyCommandEncoder
        // （避免 CubeMap.render() 内部 close 时意外销毁 ctx），此处显式销毁。
        val encoder = this.sharedCommandEncoder
        if (encoder != null) {
            encoder.close()
            MCDR_ABI.D3D12DestroyCommandEncoder(encoder.nativeHandle())
            this.sharedCommandEncoder = null
        }
        sharedEncoderForFrame = null  // P104b：别再让帧中后处理摸到已销毁的 ctx
        this.clearPipelineCache()
        val compiler = this.glslCompiler
        if (compiler != null) {
            compiler.close()
            this.glslCompiler = null
        }
        // P33：先停 worker 池并销毁 bundle/描述符 native 资源，再销毁渲染线程。
        D3D12AsyncContext.shutdown()
        // P33：销毁独立渲染线程。
        MCDR_ABI.D3D12DestroyAsyncRenderer()
        // P31：趁 DXGI/D3D12 仍然存活，显式释放设备上下文。若留给
        // DLL_PROCESS_DETACH 去析构，ComPtr::Release() 会在 dxgi.dll 已 detach
        // 之后执行 → 原生异常，进程退出码变成 2170（渲染本身无错，是退出路径的
        // 析构顺序问题）。
        MCDR_ABI.D3D12DestroyDevice()
        // The F3 mixin and the mod system-specs lines key off this being non-null;
        // clear it once the backend is gone so a GL/Vulkan fallback shows no DX12 rows.
        D3D12AdapterInfo.current = null
    }

    // -----------------------------------------------------------------------
    // P4: pipeline + shader compilation（镜像官方 VulkanDevice）
    // -----------------------------------------------------------------------

    /**
     * 编译一条管线：取顶点/片元 shader（缓存）-> GLSL 编译 -> HLSL -> 打包
     * desc -> 原生层 D3DCompile + root signature + 双 PSO。任何失败都返回
     * handle=0 的无效管线（isValid()==false），镜像官方 compilePipeline。
     *
     * @param flipY P31：true 时注入 shader Y-flip + 关闭背面剔除（GUI 离屏 pass 专用）。
     */
    private fun compilePipeline(pipeline: RenderPipeline,
        shaderSource: ShaderSource?, flipY: Boolean): D3D12CompiledRenderPipeline {
        val pipeName = pipeline.location.toString()
        val vertexShader = this.getOrCompileShader(
            pipeline.vertexShader, ShaderType.VERTEX, pipeline.shaderDefines, shaderSource)
        val fragmentShader = this.getOrCompileShader(
            pipeline.fragmentShader, ShaderType.FRAGMENT, pipeline.shaderDefines, shaderSource)
        if (vertexShader === D3D12IntermediaryShaderModule.INVALID) {
            LOGGER.error("[backend-java] {} COMPILE FAILED (vertex): shader {} invalid",
                pipeName, pipeline.vertexShader)
            return D3D12CompiledRenderPipeline(pipeline, 0L, vertexShader, fragmentShader, "", "")
        }
        if (fragmentShader === D3D12IntermediaryShaderModule.INVALID) {
            LOGGER.error("[backend-java] {} COMPILE FAILED (fragment): shader {} invalid",
                pipeName, pipeline.fragmentShader)
            return D3D12CompiledRenderPipeline(pipeline, 0L, vertexShader, fragmentShader, "", "")
        }
        try {
            val compiled = this.getOrCreateCompiler()
                .compile(pipeline, vertexShader, fragmentShader, flipY)
            val desc = buildNativeDesc(compiled, pipeline, flipY)
            val handle = MCDR_ABI.D3D12CreateGraphicsPipeline(desc)
            if (handle == 0L) {
                LOGGER.error("[backend-java] {} COMPILE FAILED (native): dx12CreateGraphicsPipeline returned 0",
                    pipeName)
                return D3D12CompiledRenderPipeline(pipeline, 0L, vertexShader, fragmentShader, "", "")
            }
            LOGGER.info("[backend-java] {} COMPILE OK (handle={})", pipeName, handle)
            return D3D12CompiledRenderPipeline(
                pipeline, handle,
                vertexShader, fragmentShader, compiled.vertexHlsl, compiled.fragmentHlsl
            )
        } catch (e: ShaderCompileException) {
            LOGGER.error("[backend-java] {} COMPILE FAILED (compile): {}", pipeName, e.message)
            return D3D12CompiledRenderPipeline(pipeline, 0L, vertexShader, fragmentShader, "", "")
        }
    }

    private fun getOrCompileShader(id: Identifier, type: ShaderType,
        defines: ShaderDefines, shaderSource: ShaderSource?): D3D12IntermediaryShaderModule {
        val key = ShaderCompilationKey(id, type, defines)
        return this.shaderCache.computeIfAbsent(key, { ignored -> this.compileShader(key, shaderSource) })
    }

    private fun compileShader(key: ShaderCompilationKey,
        shaderSource: ShaderSource?): D3D12IntermediaryShaderModule {
        // P30：主源是 ShaderManager 的 compilationCache，首次 apply() 之前为
        // Configs.EMPTY（ShaderManager.java:80）→ 恒返回 null。此时回退到
        // preloadUiShader 捕获的 vanilla 包源，否则 LoadingOverlay 首帧画
        // mojang_logo 会因 INVALID 管线抛异常直接终结 JVM。
        val source = shaderSource?.get(key.id, key.type)
            ?: D3D12FallbackShaderSource.get(key.id, key.type)
        if (source == null) {
            LOGGER.error("Couldn't find source for {} shader ({})", key.type, key.id)
            return D3D12IntermediaryShaderModule.INVALID
        }
        val sourceWithDefines = GlslPreprocessor.injectDefines(source, key.defines)
        try {
            return this.getOrCreateCompiler().createIntermediary(
                key.id.toDebugFileName(), sourceWithDefines, key.type)
        } catch (e: ShaderCompileException) {
            LOGGER.error("Couldn't compile {} shader {}: {}", key.type, key.id, e.message)
            return D3D12IntermediaryShaderModule.INVALID
        }
    }

    private fun getOrCreateCompiler(): D3D12ShaderCompiler {
        var compiler = this.glslCompiler
        if (compiler == null) {
            compiler = D3D12ShaderCompiler()
            this.glslCompiler = compiler
        }
        return compiler
    }
    /**
     * 生成 D3D12 输入布局元素：仅包含顶点着色器实际声明的输入（与
     * [D3D12IntermediaryShaderModule.rebind] 的 attribLocation 分配
     * 完全一致），每元素 {location, binding, formatOrdinal, offset, stride, stepRate}。
     */
    private fun buildVertexInputElements(pipeline: RenderPipeline,
        vertexShaderInputs: List<String>): MutableList<IntArray> {
        val elements = ArrayList<IntArray>()
        var attribLocation = 0
        val bindings = pipeline.vertexFormatBindings
        if (bindings == null || bindings.size == 0) {
            LOGGER.warn("[backend-java] {}: formatBindings={} inputs={}",
                pipeline.location, if (bindings == null) "null" else bindings.size, vertexShaderInputs)
            if (bindings != null) {
                for (i in bindings.indices) {
                    val fmt = bindings[i] ?: continue
                    val text = fmt.elements.joinToString(",") { e -> "${e.name()}(${e.format()})" }
                    LOGGER.warn("[backend-java]   slot[{}] stride={} elements=[{}]", i,
                        fmt.vertexSize, text)
                }
            }
        }
        for (i in bindings.indices) {
            val format = bindings[i] ?: continue
            val stride = format.vertexSize
            val stepRate = format.stepRate
            for (element in format.elements) {
                if (!vertexShaderInputs.contains(element.name())) continue
                val fmtOrd = element.format().ordinal
                elements.add(intArrayOf(
                    attribLocation, i, fmtOrd,
                    element.offset(), stride, stepRate
                ))
                attribLocation++
            }
        }
        return elements
    }

    /**
     * 构建原生层 `mcdr_dx12CreateGraphicsPipeline` 的 desc。
     *
     * 旧实现把整个描述符手工打包成一个 little-endian [ByteBuffer]（约 100 行
     * `putInt`/`putU8`），布局只存在于 Javadoc 里，两端随时可能静默漂移；
     * native 侧的语义名字段还单独占一段，靠位置回填。
     * FFM 下改为填充 [MCDRPipelineDesc]，与 native 的 `mcdr_pipeline_desc`
     * 共享同一份字段定义（[dev.nts.expdxr.d3d12.extern.PIPELINE_DESC]），启动自检会直接抛出任何漂移。
     */
    private fun buildNativeDesc(compiled: D3D12CompiledShader,
                                pipeline: RenderPipeline, flipY: Boolean): MCDRPipelineDesc {
        val colorTargets = pipeline.colorTargetStates
        val colorCount = colorTargets.size
        val hasDepth = pipeline.depthStencilState != null
        val inputElements = buildVertexInputElements(pipeline, compiled.vertexShaderInputs)
        val semanticNames = compiled.semanticNames
        // 补齐：semanticNames 数量可能少于 inputElements（如 gui 管线 HLSL 只有 1 个输入，
        // 但顶点格式有 2 个元素）。多余的元素自动生成 TEXCOORDn 语义。
        while (semanticNames.size < inputElements.size) {
            semanticNames.add("TEXCOORD${semanticNames.size}")
        }
        // P21：语义名与 inputElements 必须逐项对齐（BUG-01 fix 后由 native 启动自检兜底）。
        val entries = compiled.entries

        val desc = MCDRPipelineDesc()
            .shaders(compiled.vertexHlsl, compiled.fragmentHlsl)

        for (i in 0 until colorCount) {
            // Kotlin 侧元素类型是 `ColorTargetState?`：vanilla 的数组按 `MAX_COLOR_TARGETS`
            // 长度分配，未用槽位为 null（本版 `RenderPipeline` 只填 active 前缀，未遇到的
            // 空槽位直接跳过即可，与 `build()` 的 `Arrays.copyOf(activeCount)` 语义一致）。
            val target = colorTargets[i] ?: continue
            desc.addColorTarget(toColorTarget(target))
        }

        val loc = pipeline.location.toString()
        if (hasDepth) {
            // P31 遗留 hack（P101b 复核后确认在 26.2 上是**死代码**，保留仅为不改变行为）：
            // 26.2 的 `DepthStencilState.DEFAULT` 是 `(LESS_THAN_OR_EQUAL, true)`
            // （blaze3d/pipeline/DepthStencilState.java:9），而 `CompareOp.NEVER_PASS` 在
            // 整个客户端源码里只出现在枚举定义与 GlConst.java:153（`case NEVER_PASS -> 512`），
            // 没有任何管线使用它 ⇒ 这条分支永远不成立。真正会导致"物品整块消失"的
            // 深度测试问题若再次出现，要从 DepthStencilState 的实际取值与深度清屏值查起。
            var dss = pipeline.depthStencilState!!
            if (flipY && loc.contains("/item_cutout") && dss.depthTest() == CompareOp.NEVER_PASS) {
                dss = DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, dss.writeDepth())
            }
            desc.depth(GpuFormat.D32_FLOAT.ordinal, dss.writeDepth(), dss.depthTest().ordinal)
        }

        var cull = pipeline.isCull
        // P101b：shader Y-flip 会反转三角形绕序（CCW→CW）。flipY 的 pass 里**每一条**管线
        // 都被注入了 Y 翻转 ⇒ 该 pass 内必须整体关闭剔除，白名单（animate_sprite /
        // entity_cutout / item_cutout）漏掉的管线会被背面剔除吃掉（玩家模型 entity_translucent
        // 就属于这一类）。这里只可能"多画"，不会少画。
        if (loc.contains("animate_sprite") || flipY) {
            cull = false
        }
        desc.topology(pipeline.primitiveTopology.ordinal, cull,
            pipeline.polygonMode.ordinal)

        for (i in 0 until inputElements.size) {
            val element = inputElements[i]
            desc.addInputElement(
                MCDRPipelineDesc.InputElement(
                element[0],   // location
                element[1],   // binding
                element[2],   // format ordinal
                element[3],   // offset
                element[4],   // stride
                element[5],   // stepRate
                semanticNames[i]))   // HLSL 语义（旧实现靠一段独立数组按位置回填）
        }

        for (i in 0 until entries.size) {
            val type = when (entries[i].type) {
                D3D12BindGroupEntry.Type.UNIFORM_BUFFER -> 0   // CBV
                D3D12BindGroupEntry.Type.SAMPLED_IMAGE -> 1    // SRV + sampler
                D3D12BindGroupEntry.Type.TEXEL_BUFFER -> 2     // SRV (texel buffer)
                else -> 0
            }
            desc.addBinding(type, i)   // reg = 条目序号（与 HLSL shader register 一一对应）
        }
        return desc
    }

    /**
     * 转换一个颜色目标。`null`（withUnusedColorTargetState）表示该槽位未使用：
     * format=-1（DXGI_FORMAT_UNKNOWN）、无混合、无写入。
     */
    private fun toColorTarget(state: ColorTargetState?): MCDRPipelineDesc.ColorTarget {
        val target = MCDRPipelineDesc.ColorTarget()
        if (state == null) {
            return target.format(-1)
        }
        target.format(state.format().ordinal).writeMask(state.writeMask())
        if (state.blendFunction().isPresent) {
            val blend = state.blendFunction().get()
            target.blend(blend.color().sourceFactor().ordinal, blend.color().destFactor().ordinal,
                blend.color().op().ordinal, blend.alpha().sourceFactor().ordinal,
                blend.alpha().destFactor().ordinal, blend.alpha().op().ordinal)
        }
        return target
    }

    /** 着色器编译缓存键：id + 阶段 + defines（镜像官方 VulkanDevice.ShaderCompilationKey）。 */
    private class ShaderCompilationKey(val id: Identifier, val type: ShaderType, val defines: ShaderDefines) {
        override fun toString(): String {
            val s = "${this.id.toString()} (${this.type})"
            return if (this.defines.isEmpty) s else "${s} with ${this.defines}"
        }
    }
    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    companion object {
        private val LOGGER: Logger = LoggerFactory.getLogger("dxrmc")

        /** P15 诊断：标记 DX12 后端是否已成功初始化。 */
        @Volatile
        private var initialized: Boolean = false

        @JvmStatic
        fun isInitialized(): Boolean {
            return initialized
        }

        /**
         * P104b：整帧共享的 CommandEncoder（[createCommandEncoder] 里登记，device.close() 里清空）。
         *
         * 为什么需要这个出口：世界层 SMAA 必须在**帧中间**（世界画完、GUI 未画）往本帧那条
         * 命令列表追加三阶段后处理，而官方 `GpuDevice` 不暴露它的 backend、
         * `CommandEncoder.backend()` 是 protected，Java 侧拿不到 `CommandEncoderBackend`。
         * 返回 null = 设备还没起来（或已销毁），调用方直接跳过。
         */
        @Volatile
        private var sharedEncoderForFrame: D3D12CommandEncoderBackend? = null

        @JvmStatic
        fun sharedCommandEncoder(): D3D12CommandEncoderBackend? {
            return sharedEncoderForFrame
        }

        private fun parseAdapterName(probe: String?): String {
            if (probe == null || probe.isBlank()) {
                return "D3D12 adapter"
            }
            val sep = probe.indexOf(" (")
            return if (sep < 0) probe else probe.substring(0, sep)
        }

        /**
         * Build the F3-visible device description the way the vanilla Vulkan and OpenGL
         * backends do (VulkanDevice / GlHeuristics), except every value now comes from the
         * native adapter query instead of being hardcoded; the old constants survive only
         * as a fallback for when that query returned nothing.
         */
        private fun buildDeviceInfo(
            adapterName: String,
            nativeInfo: D3D12AdapterInfo,
            timestampFrequency: Long
        ): DeviceInfo {
            val timestampPeriod = if (timestampFrequency > 0L) 1.0f / timestampFrequency.toFloat() else 1.0f
            val haveNative = nativeInfo !== D3D12AdapterInfo.UNKNOWN
            val name = if (haveNative && nativeInfo.name.isNotEmpty()) nativeInfo.name else adapterName
            return DeviceInfo(
                name,                                                        // name
                if (haveNative) nativeInfo.vendorName() else "D3D12 Unknown",        // vendorName
                if (haveNative) nativeInfo.driverInfo() else "MCDR D3D12 Driver", // driverInfo
                true,                                                        // isZZeroToOne: D3D12 NDC depth is 0..1
                if (haveNative) nativeInfo.backendName() else "DirectX 12",        // backendName
                timestampPeriod,
                DeviceLimits(16, 256, 16384, Long.MAX_VALUE, 4096, 8),
                DeviceFeatures(true, true, false, true, true, true, true),
                emptySet(),
                HintsAndWorkarounds(false, false),
                if (haveNative) nativeInfo.deviceTypeEnum() else DeviceType.DISCRETE
            )
        }
    }
}
