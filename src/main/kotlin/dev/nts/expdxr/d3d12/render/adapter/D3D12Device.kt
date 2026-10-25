package dev.nts.expdxr.d3d12.render.adapter

import com.mojang.renderpearl.api.GpuFormat
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import com.mojang.renderpearl.api.buffers.GpuBuffer
import com.mojang.renderpearl.api.pipeline.ColorTargetState
import com.mojang.renderpearl.api.pipeline.DepthStencilState
import com.mojang.renderpearl.api.pipeline.RenderPipeline
import com.mojang.renderpearl.api.pipeline.CompareOp
import com.mojang.renderpearl.api.pipeline.ShaderSource
import com.mojang.renderpearl.api.pipeline.ShaderType
import com.mojang.renderpearl.api.commands.GpuQueryPool
import com.mojang.renderpearl.api.device.DeviceFeatures
import com.mojang.renderpearl.api.device.DeviceInfo
import com.mojang.renderpearl.api.device.DeviceLimits
import com.mojang.renderpearl.api.device.DeviceType
import com.mojang.renderpearl.api.device.HintsAndWorkarounds
import com.mojang.renderpearl.api.textures.AddressMode
import com.mojang.renderpearl.api.textures.FilterMode
import com.mojang.renderpearl.api.textures.GpuSampler
import com.mojang.renderpearl.api.textures.GpuTexture
import com.mojang.renderpearl.api.textures.GpuTextureView
import com.mojang.renderpearl.backend.api.BackendRenderPipeline
import com.mojang.renderpearl.backend.api.CommandEncoderBackend
import com.mojang.renderpearl.backend.api.GpuDeviceBackend
import com.mojang.renderpearl.backend.api.GpuSurfaceBackend
import com.mojang.renderpearl.util.ShaderCompileException
import dev.nts.expdxr.misc.IllusionLogger
import dev.nts.expdxr.d3d12.backend.D3D12CommandEncoderBackend
import dev.nts.expdxr.d3d12.extern.MCDRMeshPipelineDesc
import dev.nts.expdxr.d3d12.render.advanced.mesh.D3D12MeshletShader
import dev.nts.expdxr.d3d12.render.advanced.terrain.D3D12TerrainSampler
import dev.nts.expdxr.d3d12.render.core.D3D12GpuQueryPool
import dev.nts.expdxr.d3d12.render.core.D3D12GpuSurface
import dev.nts.expdxr.d3d12.render.core.D3D12AsyncContext
import dev.nts.expdxr.d3d12.backend.D3D12BindGroupEntry
import dev.nts.expdxr.d3d12.backend.D3D12CompiledRenderPipeline
import dev.nts.expdxr.d3d12.backend.D3D12PipelineBridge
import dev.nts.expdxr.d3d12.render.shader.D3D12CompiledShader
import dev.nts.expdxr.d3d12.render.shader.D3D12FallbackShaderSource
import dev.nts.expdxr.d3d12.render.core.D3D12GpuBuffer
import dev.nts.expdxr.d3d12.render.core.D3D12GpuTexture
import dev.nts.expdxr.d3d12.render.core.D3D12GpuTextureView
import dev.nts.expdxr.d3d12.extern.MCDRPipelineDesc
import dev.nts.expdxr.misc.D3D12Settings
import dev.nts.expdxr.d3d12.extern.MCDR_ABI
import dev.nts.expdxr.d3d12.render.shader.D3D12GpuSampler
import dev.nts.expdxr.d3d12.render.shader.D3D12IntermediaryShaderModule
import dev.nts.expdxr.d3d12.render.shader.D3D12ShaderCompiler
import dev.nts.expdxr.d3d12.render.shader.injectShaderDefines
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.ShaderDefines
import net.minecraft.resources.Identifier
import org.lwjgl.util.shaderc.ShadercIncludeResult
import org.lwjgl.sdl.SDLError
import org.lwjgl.sdl.SDLProperties
import org.lwjgl.sdl.SDLVideo
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.util.*
import java.util.function.BooleanSupplier
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

    // 26.3 的 backend SPI 只保留 String 版 createTexture / (texture, baseMip, mipCount) 版
    // createTextureView；Supplier 重载与单参 createTextureView 降级为内部辅助函数。
    fun createTexture(label: Supplier<String>?, usage: @GpuTexture.Usage Int,
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

    fun createTextureView(texture: GpuTexture): GpuTextureView {
        return this.createTextureView(texture, 0, texture.getMipLevels())
    }

    override fun createTextureView(texture: GpuTexture, baseMipLevel: Int, mipLevels: Int): GpuTextureView {
        return D3D12GpuTextureView(texture, baseMipLevel, mipLevels)
    }

    override fun createBuffer(label: Supplier<String>?, usage: @GpuBuffer.Usage Int, size: Long): GpuBuffer {
        // P75：保留 label（识别 UBO 身份用，见 D3D12GpuBuffer.label）。
        val name = label?.get() ?: ""
        // P199g：定位“每秒创建一个 READBACK 缓冲且从不销毁”的调用方（临时诊断）。
        if (usage and GpuBuffer.USAGE_MAP_READ != 0) {
            val n = G_P199G_COUNT.getAndIncrement()
            if (n < 24) {
                val stack = Thread.currentThread().stackTrace
                    .drop(1).take(9).joinToString(" <- ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
                IllusionLogger.warnMessage(
                    "[P199g] MAP_READ buffer label='${name}' usage=0x${usage.toUInt().toString(16)} size=${size} @ $stack")
            }
        }
        return D3D12GpuBuffer(usage, size, name)
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

    /**
     * 26.3：窗口由 SDL3 创建，句柄不再是 GLFW 的 `GLFWwindow*`，而是 `SDL_Window*`。
     * HWND 按平台属性取（`SDL_PROP_WINDOW_WIN32_HWND_POINTER`），跨 FFM 边界仍然只是一个
     * `uint64_t hwnd` —— native 侧的 `mcdr_dx12CreateSurface(uint64_t)` 因此**不用改**。
     *
     * `isIconified` 是 26.3 新加的参数（窗口最小化时游戏会跳过 configure / acquire）；
     * 这里建 surface 只用得到窗口句柄，最小化由游戏自己判断。
     */
    override fun createSurface(windowHandle: Long, isIconified: BooleanSupplier): GpuSurfaceBackend {
        val hwnd = win32Hwnd(windowHandle)
        if (hwnd == 0L) {
            throw IllegalStateException("SDL_PROP_WINDOW_WIN32_HWND_POINTER is null for SDL window "
                + "${windowHandle} (video driver: ${SDLVideo.SDL_GetCurrentVideoDriver()}, "
                + "last SDL error: ${SDLError.SDL_GetError()})")
        }
        return D3D12GpuSurface(hwnd)
    }

    /** `SDL_Window*` -> `HWND`（Win32 属性由 SDL 暴露，不再需要 GLFW 的 platform 扩展）。 */
    private fun win32Hwnd(window: Long): Long {
        val props = SDLVideo.SDL_GetWindowProperties(window)
        if (props == 0) return 0L
        return SDLProperties.SDL_GetPointerProperty(
            props, SDLVideo.SDL_PROP_WINDOW_WIN32_HWND_POINTER, 0L)
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
        // P228：缓存里可能是**已被 close 掉**的对象 —— vanilla 在资源重载（改语言/开关资源包，
        // 例如「高对比度」）时会把 `CompiledRenderPipeline` 全部 close，而我们的缓存仍然握着同一个
        // 实例；`D3D12CompiledRenderPipeline.isValid()` 现在是 `handle != 0`，close 之后就是 false。
        // 旧代码直接 `computeIfAbsent` 把它原样返回 ⇒ `setPipeline` 抛
        // "Pipeline … is not valid (shader compilation failed)"，而且**一条 COMPILE FAILED 都不会打**
        // （因为根本没编译）。实测：开高对比度触发 ReloadableResourceManager 重载 → pipeline/gui
        // 崩在 GuiRenderer.executeDraw（known_bugs P228）。
        // 所以命中缓存时先验活，失效就丢掉重编。
        if (!flipY) {
            return this.pipelineCache.compute(pipeline) { _, cached ->
                if (cached != null && cached.isValid()) {
                    cached
                } else {
                    this.compilePipeline(pipeline, this.resolveShaderSource(pipeline), false)
                }
            }!!
        }
        return this.pipelineCacheFlipY.compute(FlipYKey(pipeline, true)) { _, cached ->
            if (cached != null && cached.isValid()) {
                cached
            } else {
                this.compilePipeline(pipeline, this.resolveShaderSource(pipeline), true)
            }
        }!!
    }

    /**
     * 26.3 的 `ShaderSource` 是按次传入的（`FrontendGpuDevice.compilePipeline` 的第二个参数），
     * `createDevice` 已经收不到 defaultShaderSource，所以真实管线的源只能从
     * [D3D12PipelineBridge] 取；取不到才退回构造时给的兜底源（自检用内嵌 GLSL、
     * LoadingOverlay 首帧用 [dev.nts.expdxr.d3d12.render.shader.D3D12FallbackShaderSource]）。
     *
     * <p>flipY 变体也必须走这里：它由 [dev.nts.expdxr.d3d12.backend.D3D12RenderPassBackend.setPipeline]
     * 在首帧之后触发，那时 `defaultShaderSource` 往往为 null，用错源会静默产出 INVALID 管线。
     */
    private fun resolveShaderSource(pipeline: RenderPipeline): ShaderSource? {
        return D3D12PipelineBridge.lookupSource(pipeline.location.toString()) ?: this.defaultShaderSource
    }

    /**
     * 26.3：backend 的编译入口只收到 [BackendRenderPipeline.CreateInfo]（SPIR-V 模块 + 状态），
     * 拿不到 vanilla 的 [RenderPipeline]；而本后端的 GLSL→HLSL 链、`shaderDefines`、mesh 变体判定
     * 都依赖后者。vanilla 每次编译都会先经过 `FrontendGpuDevice.compilePipeline(RenderPipeline, …)`，
     * 由 `FrontendGpuDeviceCompilePipelineMixin` 在 [D3D12PipelineBridge] 里登记，这里按
     * `CreateInfo.name()`（= `RenderPipeline.getLocation().toString()`）查回。
     */
    override fun compilePipeline(
        info: BackendRenderPipeline.CreateInfo
    ): BackendRenderPipeline.Pending {
        val pipeline = D3D12PipelineBridge.lookup(info.name())
        if (pipeline == null) {
            LOGGER.error("[backend-java] compilePipeline({}) has no registered RenderPipeline "
                + "({} shaders); returning NULL pending", info.name(), info.shaders().size)
            return BackendRenderPipeline.Pending.NULL
        }
        // 26.3：前端回调 `setUniform(index, …)` 里的 index 就是 CreateInfo.uniforms 的下标
        // （PipelineBuilder 按 SPIR-V 描述符出现顺序 computeIfAbsent 编号）。原样存下来，
        // setPipeline 时用它把下标翻回名字。注意顺序 ≠ flattenUniforms(bindGroupLayouts)。
        val uniformNames = info.uniforms().map { it.name() }
        // P191（迁移期诊断，默认不出声）：地形逐段数据的 uniform 名单/类型，排查位置错乱时把
        // `[logging] logLevel` 调到 DEBUG 再看。
        if (info.name().contains("terrain")) {
            val detail = info.uniforms().joinToString(", ") { "${it.name()}:${it.type()}" }
            IllusionLogger.debugMessage("[backend-java] P191 uniforms ${info.name()}: [$detail]")
        }
        return object : BackendRenderPipeline.Pending {
            override fun finishCompile(): BackendRenderPipeline {
                val compiled = this@D3D12Device.getOrCompilePipeline(pipeline, false)
                compiled.setUniformNames(uniformNames)
                return compiled
            }
        }
    }

    fun clearPipelineCache() {
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

    /**
     * 26.3 用「GPU 时间戳校准偏移」取代 26.2 的 `getTimestampNow()`。本后端的时间戳 query
     * 与呈现帧同源，偏移恒为 0。
     */
    override fun getTimestampCalibrationOffset(): Long {
        return 0L
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
        val closeStarted = System.nanoTime()
        IllusionLogger.debugMessage("[backend-java] P184 device.close: enter")
        // P220：地形 mip-bias 采样器由本模组持有，必须在设备销毁前释放。
        D3D12TerrainSampler.release()
        val encoder = this.sharedCommandEncoder
        if (encoder != null) {
            encoder.close()
            MCDR_ABI.D3D12DestroyCommandEncoder(encoder.nativeHandle())
            this.sharedCommandEncoder = null
        }
        IllusionLogger.debugMessage("[backend-java] P184 device.close: encoder done")
        sharedEncoderForFrame = null  // P104b：别再让帧中后处理摸到已销毁的 ctx
        this.clearPipelineCache()
        val compiler = this.glslCompiler
        if (compiler != null) {
            compiler.close()
            this.glslCompiler = null
        }
        // P33：先停 worker 池并销毁 bundle/描述符 native 资源，再销毁渲染线程。
        IllusionLogger.debugMessage("[backend-java] P184 device.close: cache+compiler done")
        val asyncStarted = System.nanoTime()
        D3D12AsyncContext.shutdown()
        // P33：销毁独立渲染线程。
        MCDR_ABI.D3D12DestroyAsyncRenderer()
        IllusionLogger.debugMessage("[backend-java] P184 device.close: async renderer done")
        val asyncMs = (System.nanoTime() - asyncStarted) / 1_000_000
        // P31：趁 DXGI/D3D12 仍然存活，显式释放设备上下文。若留给
        // DLL_PROCESS_DETACH 去析构，ComPtr::Release() 会在 dxgi.dll 已 detach
        // 之后执行 → 原生异常，进程退出码变成 2170（渲染本身无错，是退出路径的
        // 析构顺序问题）。
        MCDR_ABI.D3D12DestroyDevice()
        // The F3 mixin and the mod system-specs lines key off this being non-null;
        // clear it once the backend is gone so a GL/Vulkan fallback shows no DX12 rows.
        D3D12AdapterInfo.current = null
        IllusionLogger.debugMessage(
            "[backend-java] D3D12Device.close took ${(System.nanoTime() - closeStarted) / 1_000_000} ms" +
                " (async ${asyncMs} ms)"
        )
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
        val vertexId = pipeline.shaders[ShaderType.VERTEX] ?: pipeline.location
        val fragmentId = pipeline.shaders[ShaderType.FRAGMENT] ?: pipeline.location
        val vertexShader = this.getOrCompileShader(
            vertexId, ShaderType.VERTEX, pipeline.shaderDefines, shaderSource)
        val fragmentShader = this.getOrCompileShader(
            fragmentId, ShaderType.FRAGMENT, pipeline.shaderDefines, shaderSource)
        if (vertexShader === D3D12IntermediaryShaderModule.INVALID) {
            LOGGER.error("[backend-java] {} COMPILE FAILED (vertex): shader {} invalid",
                pipeName, vertexId)
            return D3D12CompiledRenderPipeline(pipeline, 0L, vertexShader, fragmentShader, "", "")
        }
        if (fragmentShader === D3D12IntermediaryShaderModule.INVALID) {
            LOGGER.error("[backend-java] {} COMPILE FAILED (fragment): shader {} invalid",
                pipeName, fragmentId)
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
            val result = D3D12CompiledRenderPipeline(
                pipeline, handle,
                vertexShader, fragmentShader, compiled.vertexHlsl, compiled.fragmentHlsl
            )
            // P230：D3D12 没有宽线。`LINELIST` 恒为 1px，而 MC 的 Vulkan 后端用管线
            // `lineWidth` 画宽线；`rendertype_lines.vsh` 只把端点沿屏幕横移 `LineWidth/2`，
            // 于是在我们这里是"1px 长边 + 两端小横杠"（方块选中框的转角像毛线团）。
            // 修法：为线管线另建一条 **TRIANGLES** 拓扑的 PSO —— 每段 4 个顶点配的 6 个索引
            // （端帽/端帽/长边，`RenderSystem.sharedSequentialLines`）正好摊成两个三角形，
            // 拼出一条 `LineWidth` 像素宽的带子。draw 时临时切过去，见 drawIndexed。
            val topology = pipeline.primitiveTopology
            if (topology == com.mojang.renderpearl.api.pipeline.PrimitiveTopology.LINES ||
                topology == com.mojang.renderpearl.api.pipeline.PrimitiveTopology.DEBUG_LINES) {
                val quadDesc = buildNativeDesc(compiled, pipeline, flipY)
                // 带子两面都可能朝向相机（端点横移方向由屏幕方向决定），一律禁用剔除。
                quadDesc.topology(
                    com.mojang.renderpearl.api.pipeline.PrimitiveTopology.TRIANGLES.ordinal,
                    false, pipeline.polygonMode.ordinal)
                val quadPso = MCDR_ABI.D3D12CreateGraphicsPipeline(quadDesc)
                if (quadPso != 0L) {
                    result.attachLineQuadVariant(quadPso)
                    LOGGER.info("[dxrmc] P230 line quad variant for {}: pso={}", pipeName, quadPso)
                } else {
                    LOGGER.warn("[dxrmc] P230 line quad variant for {} failed; 线会保持 1px", pipeName)
                }
            }
            // P217：26.3 的地形管线全部建 meshlet 变体 —— 不透明（solid/cutout）、
            // OIT 半透明（oit_*_terrain，顺序无关，可以直接 mesh）、以及经典半透明
            // （translucent_terrain，排序依赖，暂不派发）。管线名里都带 "terrain"。
            if (D3D12Settings.meshTerrain &&
                  pipeName.contains("terrain") && !pipeName.contains("shadow")) {
                val variant = createMeshVariant(pipeline, compiled, flipY)
                if (variant.meshletPso != 0L) {
                    result.attachMeshletVariant(variant.meshletPso, variant.meshletAs)
                    LOGGER.info("[dxrmc] P217 meshlet variant for {}: pso={} as={}",
                        pipeName, variant.meshletPso, variant.meshletAs)
                } else {
                    LOGGER.warn("[dxrmc] P217 meshlet variant for {} failed", pipeName)
                }
            }
            return result
        } catch (e: ShaderCompileException) {
            LOGGER.error("[backend-java] {} COMPILE FAILED (compile): {}", pipeName, e.message)
            return D3D12CompiledRenderPipeline(pipeline, 0L, vertexShader, fragmentShader, "", "")
        }
    }

    /** P138：mesh 变体的产物（`pso == 0` 表示建失败）。 */
    /** P217：meshlet 变体的产物（`meshletPso == 0` 表示建失败）。 */
    private data class MeshVariantResult(val meshletPso: Long, val meshletAs: Boolean)

    /**
     * P217：为一条地形管线建 **meshlet 变体**。
     *
     * MS 由 [D3D12MeshletShader] 把地形 VS 包成 `ms_main`（每线程组 = 16 quad 的 meshlet，
     * 索引算术展开、不读索引缓冲），PS 复用同一条管线的片段着色器；根签名 = 地形管线自己的
     * 绑定点布局 + mesh 尾参（b8 小常量 / t14 顶点 / t15 段表 / t16 meshlet 表）。
     * `[mesh] terrainCull` 打开时再编一个 `as_main`（6 向 + 视锥剔除）。
     *
     * 旧 P128/P137 段表变体（126 索引/组、读索引缓冲）已删除 —— 26.3 的 MDI 地形从不派发它。
     */
    private fun createMeshVariant(pipeline: RenderPipeline, compiled: D3D12CompiledShader,
                                  flipY: Boolean): MeshVariantResult {
        val format = pipeline.vertexFormatBindings.firstOrNull() ?: return MeshVariantResult(0L, false)
        val cull = pipeline.isCull && !flipY &&
            !pipeline.location.toString().contains("animate_sprite")

        // PS：26.3 的地形 PS 自己按段读 chunkVisibility（VS 输出插值量），直接编译原版即可。
        val psDxil = MCDR_ABI.D3D12CompileShaderDxc(compiled.fragmentHlsl, "main", "ps_6_0")
            ?: run {
                LOGGER.warn("[dxrmc] P217 地形 PS 编译失败（{}）：{}", pipeline.location, MCDR_ABI.D3D12LastError())
                return MeshVariantResult(0L, false)
            }

        fun buildMeshDesc(msBytes: ByteArray, asBytes: ByteArray?): MCDRMeshPipelineDesc {
            val meshDesc = MCDRMeshPipelineDesc().shaders(msBytes, asBytes, psDxil)
            val colorStates = pipeline.colorTargetStates
            for (index in colorStates.indices) {
                val target = colorStates[index] ?: continue
                meshDesc.addColorTarget(toColorTarget(target))
            }
            val depthState = pipeline.depthStencilState
            if (depthState != null) {
                meshDesc.depth(GpuFormat.D32_FLOAT.ordinal, depthState.writeDepth(), depthState.depthTest().ordinal)
            }
            meshDesc.topology(cull, pipeline.polygonMode.ordinal)
            return meshDesc
        }

        val bindings = compiled.entries.mapIndexed { index, entry ->
            val type = when (entry.type) {
                D3D12BindGroupEntry.Type.UNIFORM_BUFFER -> 0
                D3D12BindGroupEntry.Type.SAMPLED_IMAGE -> 1
                D3D12BindGroupEntry.Type.TEXEL_BUFFER -> 2
            }
            type to index
        }
        val signature = MCDR_ABI.D3D12CreateMeshRootSignature(bindings, 1)
        if (signature == 0L) {
            LOGGER.error("[dxrmc] P217 mesh variant for {}: createMeshRootSignature returned 0", pipeline.location)
            return MeshVariantResult(0L, false)
        }
        var meshletHandle = 0L
        var meshletAs = false
        try {
            val wantAs = D3D12Settings.meshTerrainCull
            var hlsl = D3D12MeshletShader.build(compiled.vertexHlsl, format, cull = wantAs)
            var asActive = wantAs
            if (hlsl == null && wantAs) {
                // uniform 解析不到：退回不剔除的 meshlet（剔除判据不全会成片剔掉地形）。
                LOGGER.warn("[dxrmc] P217 AS 生成失败（{}），退回不剔除的 meshlet", pipeline.location)
                hlsl = D3D12MeshletShader.build(compiled.vertexHlsl, format, cull = false)
                asActive = false
            }
            var meshletMs = hlsl?.let { MCDR_ABI.D3D12CompileShaderDxc(it, "ms_main", "ms_6_9") }
            var meshletAsBytes = if (asActive && hlsl != null) {
                MCDR_ABI.D3D12CompileShaderDxc(hlsl, "as_main", "as_6_9")
            } else {
                null
            }
            if (meshletMs == null || (asActive && meshletAsBytes == null)) {
                LOGGER.warn("[dxrmc] P217 meshlet MS/AS 编译失败（{}）：{}，退回不剔除版本",
                    pipeline.location, MCDR_ABI.D3D12LastError())
                hlsl = D3D12MeshletShader.build(compiled.vertexHlsl, format, cull = false)
                meshletMs = hlsl?.let { MCDR_ABI.D3D12CompileShaderDxc(it, "ms_main", "ms_6_9") }
                meshletAsBytes = null
                asActive = false
            }
            if (meshletMs == null) {
                LOGGER.warn("[dxrmc] P217 meshlet MS 生成/编译失败（{}）：{}",
                    pipeline.location, MCDR_ABI.D3D12LastError())
            } else {
                val withAs = meshletAsBytes != null
                meshletHandle = MCDR_ABI.D3D12CreateMeshPipelineWithRootSignature(
                    buildMeshDesc(meshletMs, if (withAs) meshletAsBytes else null), signature)
                if (meshletHandle == 0L) {
                    LOGGER.warn("[dxrmc] P217 meshlet PSO 创建失败（{}）：{}",
                        pipeline.location, MCDR_ABI.D3D12LastError())
                    if (withAs) {
                        meshletHandle = MCDR_ABI.D3D12CreateMeshPipelineWithRootSignature(
                            buildMeshDesc(meshletMs, null), signature)
                        if (meshletHandle != 0L) {
                            LOGGER.warn("[dxrmc] P217 meshlet AS PSO 失败，退回不剔除（{}）", pipeline.location)
                        }
                    }
                } else {
                    meshletAs = withAs
                }
            }
        } finally {
            MCDR_ABI.D3D12ReleaseRootSignature(signature)
        }
        return MeshVariantResult(meshletHandle, meshletAs)
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
        val source = shaderSource?.getShader(key.id, key.type)
            ?: D3D12FallbackShaderSource.get(key.id, key.type)
        if (source == null) {
            LOGGER.error("Couldn't find source for {} shader ({})", key.type, key.id)
            return D3D12IntermediaryShaderModule.INVALID
        }
        val sourceWithDefines = injectShaderDefines(source, key.defines)
        // 26.3：GLSL `#include <minecraft:xxx.glsl>` 由官方 GlslCompiler 的 shaderc include
        // 回调解析（`ShaderSource.getInclude`），26.2 那个 GlslPreprocessor 已删。我们这条
        // 自建编译链没有回调，所以把 include 也在这里文本展开（见 expandShaderIncludes）。
        val includeResolver: ((Identifier) -> String?)? = if (shaderSource != null) {
            { id -> readShaderInclude(shaderSource, id) }
        } else {
            D3D12FallbackShaderSource::getInclude
        }
        try {
            return this.getOrCreateCompiler().createIntermediary(
                key.id.toDebugFileName(), sourceWithDefines, key.type, includeResolver)
        } catch (e: ShaderCompileException) {
            LOGGER.error("Couldn't compile {} shader {}: {}", key.type, key.id, e.message)
            return D3D12IntermediaryShaderModule.INVALID
        }
    }

    /**
     * 读一条 `#include`（`shaders/include/` 下的源码）。
     *
     * P197：优先从 `ResourceManager` 直接读文件，**不要**依赖 `ShaderSource.getInclude` 返回的
     * `CachedIncludeSource`。那个 `includeResultPtr()` 指向的结构只在 vanilla 自己的 shaderc
     * include 回调期间有效，回调返回后 vanilla 会释放它；我们在之后异步读到的是一块已释放内存
     * ——表现是 include 文本尾部混进几个 U+FFFD（`glsl_*.failed.glsl` 里能看到 `�~�<`），
     * 进而让 shaderc 报 "unexpected token"。
     *
     * 资源路径与 vanilla 一致：`<namespace>:shaders/include/<path>`（`ShaderManager` 的
     * `ldc "shaders/include"`）。资源包覆盖同样生效，因为读的就是当前 ResourceManager。
     * 读不到时才退回 `getInclude`（可能仍在缓存有效期内，聊胜于无），并丢弃含替换字符的结果。
     */
    private fun readShaderInclude(shaderSource: ShaderSource, id: Identifier): String? {
        readIncludeFromResources(id)?.let { if ('\uFFFD' !in it) return it }
        val cached = try {
            shaderSource.getInclude(id)
        } catch (e: Exception) {
            LOGGER.debug("[backend-java] include {} lookup failed: {}", id, e.message)
            null
        } ?: return null
        return try {
            val text = ShadercIncludeResult.create(cached.includeResultPtr()).contentString()
            if ('\uFFFD' in text) {
                LOGGER.warn("[backend-java] include {} from cache looks corrupted (freed buffer?)", id)
                null
            } else {
                text
            }
        } catch (e: Throwable) {
            LOGGER.debug("[backend-java] include {} content unreadable: {}", id, e.message)
            null
        }
    }

    /** 直接按原版路径 `shaders/include/<path>` 从当前资源管理器读 include 源码。 */
    private fun readIncludeFromResources(id: Identifier): String? {
        return try {
            val location = Identifier.fromNamespaceAndPath(id.namespace, "shaders/include/" + id.path)
            Minecraft.getInstance().resourceManager.openAsReader(location).use { it.readText() }
        } catch (e: Exception) {
            LOGGER.debug("[backend-java] include {} resource read failed: {}", id, e.message)
            null
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
        if (bindings.isEmpty()) {
            LOGGER.warn("[backend-java] {}: formatBindings 为空 inputs={}", pipeline.location, vertexShaderInputs)
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
        // P191（迁移期诊断，默认不出声）：地形管线的 slot/stepRate 一览。26.3 把
        // ChunkPosition/ChunkVisibility 挪成了逐实例顶点属性（slot 1, stepRate=1），排查位置/错位时
        // 把 `[logging] logLevel` 调到 DEBUG 再看这里。
        if (pipeline.location.toString().contains("terrain")) {
            val slots = bindings.indices.joinToString(", ") { i ->
                val fmt = bindings[i]
                if (fmt == null) "s$i=null" else "s$i(stride=${fmt.vertexSize},step=${fmt.stepRate}," +
                    "elems=${fmt.elements.joinToString("|") { e -> e.name() }})"
            }
            IllusionLogger.debugMessage("[backend-java] P191 ${pipeline.location}: inputs=$vertexShaderInputs slots=[$slots]")
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
            // P217：26.3 的默认十字准星走 `pipeline/crosshair` 这条**带深度测试**的 GUI 管线，
            // 而 GUI 目标在 prepareForGui() 里把深度清成 0.0（反向 Z 下即"最远"），准星按 GREATER
            // 比较会被整条丢弃 —— 热键栏/文字走免深度测试的管线，所以只有准星消失。准星是 2D 贴图，
            // 关掉深度测试既符合本意，也不会影响世界内任何管线。
            if (loc.contains("pipeline/crosshair")) {
                dss = DepthStencilState(CompareOp.ALWAYS_PASS, false)
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
        private val G_P199G_COUNT = java.util.concurrent.atomic.AtomicInteger()
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
                DeviceLimits(16, 256, 16384, Long.MAX_VALUE, 4096, 8, 65536),
                DeviceFeatures(true, true, false, true, true, true, true, true),
                emptySet(),
                HintsAndWorkarounds(false, false, false, false),
                if (haveNative) nativeInfo.deviceTypeEnum() else DeviceType.DISCRETE
            )
        }
    }
}
