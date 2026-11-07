package io.github.dxegp.d3d12.render.adapter

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.mojang.renderpearl.api.GpuFormat
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
import io.github.dxegp.misc.IllusionLogger
import io.github.dxegp.d3d12.backend.D3D12CommandEncoderBackend
import io.github.dxegp.d3d12.extern.MCDRMeshPipelineDesc
import io.github.dxegp.d3d12.render.advanced.mesh.D3D12MeshletShader
import io.github.dxegp.experimental.raytracing.D3D12RtGBuffer
import io.github.dxegp.experimental.raytracing.D3D12RtGBufferShader
import io.github.dxegp.d3d12.render.advanced.terrain.D3D12TerrainSampler
import io.github.dxegp.d3d12.render.core.D3D12GpuQueryPool
import io.github.dxegp.d3d12.render.core.D3D12GpuSurface
import io.github.dxegp.d3d12.render.core.D3D12AsyncContext
import io.github.dxegp.d3d12.backend.D3D12BindGroupEntry
import io.github.dxegp.d3d12.backend.D3D12CompiledRenderPipeline
import io.github.dxegp.d3d12.backend.D3D12PipelineBridge
import io.github.dxegp.d3d12.render.shader.D3D12CompiledShader
import io.github.dxegp.d3d12.render.shader.D3D12FallbackShaderSource
import io.github.dxegp.d3d12.render.core.D3D12GpuBuffer
import io.github.dxegp.d3d12.render.core.D3D12GpuTexture
import io.github.dxegp.d3d12.render.core.D3D12GpuTextureView
import io.github.dxegp.d3d12.extern.MCDRPipelineDesc
import io.github.dxegp.misc.settings.D3D12Settings
import io.github.dxegp.d3d12.extern.MCDR_ABI
import io.github.dxegp.d3d12.render.shader.D3D12GpuSampler
import io.github.dxegp.d3d12.render.shader.D3D12IntermediaryShaderModule
import io.github.dxegp.d3d12.render.shader.D3D12ShaderCompiler
import io.github.dxegp.d3d12.render.shader.IllusionShaderBridge
import io.github.dxegp.d3d12.render.shader.expandShaderIncludes
import io.github.dxegp.d3d12.render.shader.injectShaderDefines
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
 * vanilla [GpuDeviceBackend] 的 D3D12 实现：资源、管线与着色器编译全部经 MCDR.dll。
 * 一个设备只有一个共享 [D3D12CommandEncoderBackend]；失败即崩、不回退。
 * 见 docs/misc/backend-and-device.md §3.1。
 */
class D3D12Device(private val defaultShaderSource: ShaderSource?) : GpuDeviceBackend {
    private val deviceInfo: DeviceInfo
    private var timestampCtx: Long = 0L

    // 共享 CommandEncoder：createCommandEncoder() 每次返回同一实例（blit 与 submit 同 encoder、
    // 整帧一次提交）。每次 new 独立 ctx 会让 submit 提交空列表 → backbuffer 呈现未初始化垃圾。
    private var sharedCommandEncoder: D3D12CommandEncoderBackend? = null
    // 同一实例登记到伴生对象，供帧中后处理（世界层 SMAA）取用。

    private val pipelineCache: MutableMap<RenderPipeline, D3D12CompiledRenderPipeline> = IdentityHashMap()
    /** 已经报过「G-Buffer 变体建不出来」的管线：同类失败只报一次，避免每帧刷屏。 */
    private val gbufferFailures: MutableSet<String> = HashSet()
    /** 已经报过「G-Buffer 变体状态」的管线，每条只报一次。 */
    private val gbufferOk: MutableSet<String> = HashSet()
    /**
     * flipY 变体管线缓存（GUI 离屏 pass 专用）。键是值语义的 [FlipYKey]、每次调用新建实例，必须用
     * HashMap 按 equals/hashCode 查；误用 IdentityHashMap 会永远 miss → 每帧重编译，帧率崩。
     */
    private val pipelineCacheFlipY: MutableMap<FlipYKey, D3D12CompiledRenderPipeline> = HashMap()
    private val shaderCache: MutableMap<ShaderCompilationKey, D3D12IntermediaryShaderModule> = HashMap()
    private var glslCompiler: D3D12ShaderCompiler? = null
    /** `[debug] shaderConverter` shadow counters: every distinct compiled stage converts once. */
    private val converterStages = java.util.concurrent.atomic.AtomicInteger()
    private val converterSucceeded = java.util.concurrent.atomic.AtomicInteger()
    private val converterFailed = java.util.concurrent.atomic.AtomicInteger()
    /** Failure kinds of the shadow path, keyed by the ABI error kind (or `includes` / `threw`). */
    private val converterFailedKinds =
        java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>()
    private val converterDumps = java.util.concurrent.atomic.AtomicInteger()
    private val converterPrimaryDumps = java.util.concurrent.atomic.AtomicInteger()
    // 生成的 mesh 源码落盘上限（诊断用，见 dxrmc/converter/mesh/）。
    private val meshDumps = java.util.concurrent.atomic.AtomicInteger()
    private val converterWarned = java.util.concurrent.atomic.AtomicBoolean()

    init {
        val probe = MCDR_ABI.ID3D12CreateDevice()
        LOGGER.debug("device probe: {}", probe)
        val frequency = MCDR_ABI.D3D12GetTimestampFrequency()
        if (frequency == 0L) {
            LOGGER.warn("GetTimestampFrequency 0; timestampPeriod=1.0")
        }
        // 适配器信息取自 DXGI/DXCore（F3 与系统信息行读 D3D12AdapterInfo.current）；查询失败保持 null。
        val nativeInfo = D3D12AdapterInfo.parse(MCDR_ABI.ID3D12GetAdapterInfo())
        if (nativeInfo !== D3D12AdapterInfo.UNKNOWN) {
            D3D12AdapterInfo.current = nativeInfo
            LOGGER.info("Adapter: {}", nativeInfo.summary())
        } else {
            LOGGER.warn("adapter info unavailable, keeping probe fallback")
        }
        this.deviceInfo = buildDeviceInfo(parseAdapterName(probe), nativeInfo, frequency)
        current = this
        // 启动独立渲染线程（native 负责 acquire/present/fence-wait，主线程录制命令）。
        // 诊断开关 `[debug] asyncRenderThread = false` 时不启动，此时不得依赖异步提交语义。
        if (D3D12Settings.asyncRenderThread) {
            MCDR_ABI.D3D12InitAsyncRenderer(1)
        } else {
            LOGGER.warn("async renderer off ([debug] asyncRenderThread = false)")
        }
        // CPU/GPU 并行录制上下文（分区描述符堆 + bundle 池 + worker 池）；失败自动退回单线程。
        D3D12AsyncContext.init()
        initialized = true
    }

    override fun createSampler(addressModeU: AddressMode, addressModeV: AddressMode,
        minFilter: FilterMode, magFilter: FilterMode, maxAnisotropy: Int, maxLod: OptionalDouble): GpuSampler {
        return D3D12GpuSampler(addressModeU, addressModeV, minFilter, magFilter, maxAnisotropy, maxLod)
    }

    // 26.3 的 backend SPI 只保留 String 版 createTexture 与带 mip 范围的 createTextureView，其余是内部重载。
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
        // 保留 label：UBO 身份识别用（见 D3D12GpuBuffer.label）。
        val name = label?.get() ?: ""
        return D3D12GpuBuffer(usage, size, name)
    }

    override fun createBuffer(label: Supplier<String>?, usage: @GpuBuffer.Usage Int,
        data: ByteBuffer): GpuBuffer {
        // 镜像官方 VulkanDevice.createBuffer(data)：带 COPY_DST 分配后经命令编码器上传。
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

    /**
     * SDL3 窗口句柄（`SDL_Window*`）→ HWND 由 `SDL_PROP_WINDOW_WIN32_HWND_POINTER` 取，跨 FFM 边界仍是一个
     * `uint64_t`。`isIconified` 是 26.3 新参，建 surface 用不到（最小化由游戏自己判断）。
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

    private fun win32Hwnd(window: Long): Long {
        val props = SDLVideo.SDL_GetWindowProperties(window)
        if (props == 0) return 0L
        return SDLProperties.SDL_GetPointerProperty(
            props, SDLVideo.SDL_PROP_WINDOW_WIN32_HWND_POINTER, 0L)
    }

    override fun createCommandEncoder(): CommandEncoderBackend {
        // 共享单例：全帧 blit/绘制/submit 落同一 CommandContext。
        var encoder = this.sharedCommandEncoder
        if (encoder == null) {
            encoder = D3D12CommandEncoderBackend(this)
            this.sharedCommandEncoder = encoder
        }
        sharedEncoderForFrame = encoder
        return encoder
    }

    private data class FlipYKey(val pipeline: RenderPipeline, val flipY: Boolean)

    fun getOrCompilePipeline(pipeline: RenderPipeline, flipY: Boolean): D3D12CompiledRenderPipeline {
        // 缓存里可能是**已被 close** 的对象：vanilla 资源重载（改语言/开关资源包）会 close 全部
        // CompiledRenderPipeline，而缓存仍握着同一实例 → setPipeline 抛 "not valid" 且一条
        // COMPILE FAILED 都不打。命中后必须验活，失效就丢掉重编。
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
     * 26.3 的 ShaderSource 按次传入，真实管线源只能从 [D3D12PipelineBridge] 取，取不到才退回构造时的兜底源。
     * flipY 变体（首帧之后由 [D3D12RenderPassBackend.setPipeline] 触发）也必须走这里：源用错会静默产出 INVALID 管线。
     */
    private fun resolveShaderSource(pipeline: RenderPipeline): ShaderSource? {
        return D3D12PipelineBridge.lookupSource(pipeline.location.toString()) ?: this.defaultShaderSource
    }

    /**
     * backend 编译入口只收到 [BackendRenderPipeline.CreateInfo]，拿不到 vanilla RenderPipeline，而 GLSL→HLSL 链、
     * shaderDefines、mesh 变体判定都依赖后者 —— 按 `CreateInfo.name()` 从 [D3D12PipelineBridge] 查回。
     */
    override fun compilePipeline(
        info: BackendRenderPipeline.CreateInfo
    ): BackendRenderPipeline.Pending {
        val pipeline = D3D12PipelineBridge.lookup(info.name())
        if (pipeline == null) {
            LOGGER.error("compilePipeline({}): no RenderPipeline, {} shaders, NULL",
                info.name(), info.shaders().size)
            return BackendRenderPipeline.Pending.NULL
        }
        // setUniform(index) 的 index 就是 CreateInfo.uniforms 的下标（≠ flattenUniforms(bindGroupLayouts) 的顺序），
        // 必须原样存下来，setPipeline 时按下标翻回名字。
        val uniformNames = info.uniforms().map { it.name() }
        // 地形 uniform 名单/类型诊断：`[logging] logLevel` 调到 DEBUG 才出声。
        if (info.name().contains("terrain")) {
            val detail = info.uniforms().joinToString(", ") { "${it.name()}:${it.type()}" }
            IllusionLogger.debugMessage("uniforms ${info.name()}: [$detail]")
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

    /** 26.3 用 GPU 时间戳校准偏移取代 getTimestampNow()；本后端时间戳 query 与呈现帧同源，偏移恒 0。 */
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
        // 先销毁共享 encoder 再清管线缓存。encoder.close() 对共享 encoder **不**调
        // dx12DestroyCommandEncoder（避免 CubeMap.render() 内部 close 顺手销毁 ctx），所以这里显式销毁。
        val closeStarted = System.nanoTime()
        IllusionLogger.debugMessage("device.close: enter")
        // 地形 mip-bias 采样器由本模组持有，必须在设备销毁前释放。
        D3D12TerrainSampler.release()
        val encoder = this.sharedCommandEncoder
        if (encoder != null) {
            encoder.close()
            MCDR_ABI.D3D12DestroyCommandEncoder(encoder.nativeHandle())
            this.sharedCommandEncoder = null
        }
        IllusionLogger.debugMessage("device.close: encoder done")
        sharedEncoderForFrame = null  // 帧中后处理不能再摸到已销毁的 ctx
        this.clearPipelineCache()
        val compiler = this.glslCompiler
        if (compiler != null) {
            compiler.close()
            this.glslCompiler = null
        }
        // 先停 worker 池、销毁 bundle/描述符 native 资源，再销毁渲染线程。
        IllusionLogger.debugMessage("device.close: cache+compiler done")
        val asyncStarted = System.nanoTime()
        D3D12AsyncContext.shutdown()
        MCDR_ABI.D3D12DestroyAsyncRenderer()
        IllusionLogger.debugMessage("device.close: async renderer done")
        val asyncMs = (System.nanoTime() - asyncStarted) / 1_000_000
        MCDR_ABI.D3D12QueryRtTrackStats()?.let { s ->
            IllusionLogger.infoMessage(
                "rt state shadow: transitions=${s.transitions} firstUse=${s.firstUse} " +
                    "uav=${s.uavBarriers} tracked=${s.tracked} mismatches=${s.mismatches} " +
                    "promotions=${s.promotions} widened=${s.widened} ${s.note}"
            )
        }
        // 必须趁 DXGI/D3D12 仍存活时显式释放设备：留给 DLL_PROCESS_DETACH 会让 ComPtr::Release()
        // 在 dxgi.dll 已 detach 后执行 → 原生异常，退出码变成 2170。
        MCDR_ABI.D3D12DestroyDevice()
        // 清掉适配器信息：F3 与系统信息行按它非空决定是否显示 DX12 行。
        D3D12AdapterInfo.current = null
        if (D3D12Settings.shaderConverter && converterStages.get() > 0) {
            val failureKinds = converterFailedKinds.entries
                .sortedByDescending { it.value.get() }
                .joinToString(", ") { "${it.key}=${it.value.get()}" }
            IllusionLogger.infoMessage(
                "shader converter shadow: ${converterStages.get()} stages, " +
                    "${converterSucceeded.get()} ok, ${converterFailed.get()} failed" +
                    (if (failureKinds.isEmpty()) "" else " ($failureKinds)") +
                    ", " + IllusionShaderBridge.cacheSummary())
        }
        if (D3D12Settings.shaderConverterPrimary) {
            val breakdown = converterPrimarySkips.entries.sortedByDescending { it.value.get() }
                .joinToString(", ") { "${it.key}=${it.value.get()}" }
            IllusionLogger.infoMessage(
                "shader converter primary: ${converterPrimaryApplied.get()} pipelines rendered " +
                    "converted HLSL, ${converterPrimarySkipped()} kept the old chain" +
                    if (breakdown.isEmpty()) "" else " ($breakdown)")
        }
        IllusionLogger.debugMessage(
            "D3D12Device.close took ${(System.nanoTime() - closeStarted) / 1_000_000} ms" +
                " (async ${asyncMs} ms)"
        )
    }

    /**
     * 编译一条管线：取 shader（缓存）→ GLSL 编译 → HLSL → 打包 desc → native D3DCompile + root signature。
     * 任何失败都返回 handle=0 的无效管线（isValid()==false）。@param flipY 注入 Y-flip 并关闭背面剔除。
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
            LOGGER.error("{} compile failed: vertex shader {} invalid",
                pipeName, vertexId)
            return D3D12CompiledRenderPipeline(pipeline, 0L, vertexShader, fragmentShader, "", "")
        }
        if (fragmentShader === D3D12IntermediaryShaderModule.INVALID) {
            LOGGER.error("{} compile failed: fragment shader {} invalid",
                pipeName, fragmentId)
            return D3D12CompiledRenderPipeline(pipeline, 0L, vertexShader, fragmentShader, "", "")
        }
        try {
            val spirvCompiled = this.getOrCreateCompiler()
                .compile(pipeline, vertexShader, fragmentShader, flipY)
            val converted = applyConverterPrimary(spirvCompiled, pipeline, flipY)
            if (converted != null) {
                val result = this.createPipeline(converted, pipeline, vertexShader, fragmentShader, flipY)
                if (result != null) {
                    converterPrimaryApplied.incrementAndGet()
                    return result
                }
                // 转换产物过得了 DXC 却被设备拒绝（PSO 建不出来）：让它整条管线失效是不可接受的，
                // 记下产物并退回 SPIR-V 链重试一次——两条链独立编译，坏一边不影响另一边。
                dumpConverterFailure(pipeline, converted)
                LOGGER.warn("{}: device rejected the converted shader, falling back to the SPIR-V chain",
                    pipeName)
            }
            val fallback = this.createPipeline(spirvCompiled, pipeline, vertexShader, fragmentShader, flipY)
            if (fallback != null) {
                return fallback
            }
            LOGGER.error("{} compile failed: dx12CreateGraphicsPipeline = 0", pipeName)
            return D3D12CompiledRenderPipeline(pipeline, 0L, vertexShader, fragmentShader, "", "")
        } catch (e: ShaderCompileException) {
            LOGGER.error("{} compile failed: {}", pipeName, e.message)
            return D3D12CompiledRenderPipeline(pipeline, 0L, vertexShader, fragmentShader, "", "")
        }
    }

    /**
     * 按 [compiled] 建 PSO 与派生变体（线四边形、meshlet）。返回 null 表示这条链的产物建不出管线，
     * 由 [compilePipeline] 决定是否换另一条链重试——转换器接管后这是"坏产物不废管线"的唯一保险。
     */
    private fun createPipeline(compiled: D3D12CompiledShader, pipeline: RenderPipeline,
        vertexShader: D3D12IntermediaryShaderModule, fragmentShader: D3D12IntermediaryShaderModule,
        flipY: Boolean): D3D12CompiledRenderPipeline? {
        val pipeName = pipeline.location.toString()
        val desc = try {
            buildNativeDesc(compiled, pipeline, flipY)
        } catch (e: RuntimeException) {
            LOGGER.warn("{}: binding desc failed ({})", pipeName, e.message)
            return null
        }
        val handle = MCDR_ABI.D3D12CreateGraphicsPipeline(desc)
        if (handle == 0L) {
            LOGGER.debug("{}: dx12CreateGraphicsPipeline = 0 ({})", pipeName, MCDR_ABI.D3D12LastError())
            return null
        }
        LOGGER.debug("{} compiled (handle={})", pipeName, handle)
        val result = D3D12CompiledRenderPipeline(
            pipeline, handle,
            vertexShader, fragmentShader, compiled.vertexHlsl, compiled.fragmentHlsl,
            compiled.rawBuffers
        )
        // D3D12 没有宽线：LINELIST 恒 1px，而 rendertype_lines.vsh 只把端点横移 LineWidth/2 →
        // 这里会画成「1px 长边 + 两端小横杠」。为线管线另建 TRIANGLES 拓扑 PSO，6 索引正好摊成两个三角形。
        val topology = pipeline.primitiveTopology
        if (topology == com.mojang.renderpearl.api.pipeline.PrimitiveTopology.LINES ||
            topology == com.mojang.renderpearl.api.pipeline.PrimitiveTopology.DEBUG_LINES) {
            val quadDesc = buildNativeDesc(compiled, pipeline, flipY)
            // 带子两面都可能朝向相机，一律禁用剔除。
            quadDesc.topology(
                com.mojang.renderpearl.api.pipeline.PrimitiveTopology.TRIANGLES.ordinal,
                false, pipeline.polygonMode.ordinal)
            val quadPso = MCDR_ABI.D3D12CreateGraphicsPipeline(quadDesc)
            if (quadPso != 0L) {
                result.attachLineQuadVariant(quadPso)
                LOGGER.debug("line quad variant for {}: pso={}", pipeName, quadPso)
            } else {
                LOGGER.warn("line quad variant for {} failed; lines stay 1px", pipeName)
            }
        }
        // 地形管线全部建 meshlet 变体（solid/cutout/OIT 半透明；经典半透明排序依赖，暂不派发），
        // 判定靠管线名里的 "terrain"。
        if (D3D12Settings.meshTerrain &&
              pipeName.contains("terrain") && !pipeName.contains("shadow")) {
            val variant = createMeshVariant(pipeline, compiled, flipY)
            if (variant.meshletPso != 0L) {
                result.attachMeshletVariant(variant.meshletPso, variant.meshletAs)
                LOGGER.debug("meshlet variant for {}: as={}", pipeName, variant.meshletAs)
            } else {
                LOGGER.warn("meshlet variant for {} failed", pipeName)
            }
            if (variant.gbufferProgram != 0L) {
                result.attachGBufferProgram(variant.gbufferProgram)
            }
        }
        return result
    }

    /** meshlet 变体产物（`meshletPso == 0` 表示建失败）。 */
    private data class MeshVariantResult(val meshletPso: Long, val meshletAs: Boolean,
                                          val gbufferProgram: Long = 0L)

    /**
     * 为一条地形管线建 meshlet 变体：[D3D12MeshletShader] 把地形 VS 包成 `ms_main`（每线程组 = 16 quad，
     * 索引算术展开、不读索引缓冲），PS 复用原片段着色器；`[mesh] terrainCull` 打开时再编 `as_main`。
     */
    private fun createMeshVariant(pipeline: RenderPipeline, compiled: D3D12CompiledShader,
                                  flipY: Boolean): MeshVariantResult {
        val format = pipeline.vertexFormatBindings.firstOrNull() ?: return MeshVariantResult(0L, false)
        val cull = pipeline.isCull && !flipY &&
            !pipeline.location.toString().contains("animate_sprite")

        // 26.3 的地形 PS 自己按段读 chunkVisibility（VS 输出插值量），直接编译原版即可。
        val psDxil = MCDR_ABI.D3D12CompileShaderDxc(compiled.fragmentHlsl, "main", "ps_6_0")
            ?: run {
                LOGGER.warn("terrain PS failed to compile ({}): {}", pipeline.location, MCDR_ABI.D3D12LastError())
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
                D3D12BindGroupEntry.Type.RAW_BUFFER -> 3
            }
            type to index
        }
        val signature = MCDR_ABI.D3D12CreateMeshRootSignature(bindings, 1)
        if (signature == 0L) {
            LOGGER.error("mesh variant for {}: createMeshRootSignature = 0", pipeline.location)
            return MeshVariantResult(0L, false)
        }
        var meshletHandle = 0L
        var meshletAs = false
        var gbufferHandle = 0L
        try {
            val wantAs = D3D12Settings.meshTerrainCull
            var hlsl = D3D12MeshletShader.build(compiled.vertexHlsl, format, cull = wantAs)
            var asActive = wantAs
            if (hlsl == null && wantAs) {
                // uniform 解析不到时退回不剔除的 meshlet（剔除判据不全会成片剔掉地形）。
                LOGGER.warn("AS build failed ({}), meshlets without culling", pipeline.location)
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
                LOGGER.warn("meshlet MS/AS compile failed ({}): {}, no culling",
                    pipeline.location, MCDR_ABI.D3D12LastError())
                hlsl = D3D12MeshletShader.build(compiled.vertexHlsl, format, cull = false)
                meshletMs = hlsl?.let { MCDR_ABI.D3D12CompileShaderDxc(it, "ms_main", "ms_6_9") }
                meshletAsBytes = null
                asActive = false
            }
            if (hlsl != null && meshDumps.getAndIncrement() < 4) {
                runCatching {
                    val dir = java.nio.file.Path.of(System.getProperty("user.dir"), "dxrmc", "converter", "mesh")
                    java.nio.file.Files.createDirectories(dir)
                    val base = pipeline.location.toString().replace(Regex("[^A-Za-z0-9_.-]"), "_")
                    java.nio.file.Files.writeString(dir.resolve("$base.ms.hlsl"), hlsl)
                    java.nio.file.Files.writeString(dir.resolve("$base.vs.hlsl"), compiled.vertexHlsl)
                }
            }
            if (meshletMs == null) {
                LOGGER.warn("meshlet MS failed to build ({}): {}",
                    pipeline.location, MCDR_ABI.D3D12LastError())
            } else {
                val withAs = meshletAsBytes != null
                meshletHandle = MCDR_ABI.D3D12CreateMeshPipelineWithRootSignature(
                    buildMeshDesc(meshletMs, if (withAs) meshletAsBytes else null), signature)
                if (meshletHandle == 0L) {
                    LOGGER.warn("meshlet PSO creation failed ({}): {}",
                        pipeline.location, MCDR_ABI.D3D12LastError())
                    if (withAs) {
                        meshletHandle = MCDR_ABI.D3D12CreateMeshPipelineWithRootSignature(
                            buildMeshDesc(meshletMs, null), signature)
                        if (meshletHandle != 0L) {
                            LOGGER.warn("meshlet AS PSO failed, no culling ({})", pipeline.location)
                        }
                    }
                } else {
                    meshletAs = withAs
                }
            }
            if (D3D12RtGBuffer.isActive() && isGBufferLayer(pipeline.location.toString())) {
                gbufferHandle = createGBufferVariant(pipeline, compiled, format, cull, bindings)
            }
        } finally {
            MCDR_ABI.D3D12ReleaseRootSignature(signature)
        }
        if (D3D12RtGBuffer.isActive() && isGBufferLayer(pipeline.location.toString()) &&
            gbufferOk.add(pipeline.location.toString())) {
            // 一行一条管线：变体有没有建起来是「世界里有没有光追」的分水岭，必须能从日志读出来。
            LOGGER.info("gbuffer: variant {} ({})", if (gbufferHandle != 0L) "ready" else "missing",
                pipeline.location)
        }
        return MeshVariantResult(meshletHandle, meshletAs, gbufferHandle)
    }

    /** G-Buffer 只收不透明地形：半透明走 OIT/S4，阴影管线没有颜色输出。 */
    private fun isGBufferLayer(location: String): Boolean =
        !location.contains("translucent") && !location.contains("oit")

    /**
     * 为一条地形管线建 G-Buffer 变体：MS 复用 meshlet 包装（恒不带 AS，按原始 meshlet 数派发）并多输出
     * 一条相机相对世界坐标，PS 由 [D3D12RtGBufferShader] 生成（图集采样 × 顶点色当反照率、屏幕空间导数当法线）。
     *
     * 深度写自己的那份（`D3D12RtGBuffer` 的 DSV）：借主 pass 的深度做只读比较时，同帧第二次绘制
     * 的深度值浮点略差就会被整片拒绝，留下地平线窄带与近处楔形的漏画。见 docs/rt/README.md §8。
     */
    private fun createGBufferVariant(pipeline: RenderPipeline, compiled: D3D12CompiledShader,
                                     format: com.mojang.renderpearl.api.vertex.VertexFormat?,
                                      cull: Boolean, bindings: List<Pair<Int, Int>>): Long {
        val sources = D3D12RtGBufferShader.build(compiled.vertexHlsl, compiled.fragmentHlsl,
            format, D3D12RtGBuffer.DEFAULT_ROUGHNESS)
        if (sources == null) {
            // 静默返回 0 会让整条光追链消失（没有变体就没有重放批次），所以这里必须留一行。
            if (gbufferFailures.add(pipeline.location.toString())) {
                LOGGER.warn("gbuffer: variant unavailable ({}): {}",
                    D3D12RtGBufferShader.lastFailure, pipeline.location)
            }
            return 0L
        }
        // 诊断：着色器是拿 spvc 输出拼出来的，离线只能验证手写部分，注入的产物要落盘才能复查。
        if (D3D12Settings.dumpHlsl) {
            try {
                val dir = java.io.File("dxrmc/hlsl")
                dir.mkdirs()
                val stamp = pipeline.location.toString().hashCode().toUInt().toString(16)
                java.io.File(dir, "gbuffer_ms_$stamp.hlsl").writeText(sources.meshHlsl)
                java.io.File(dir, "gbuffer_ps_$stamp.hlsl").writeText(sources.pixelHlsl)
            } catch (e: java.io.IOException) {
                LOGGER.warn("gbuffer HLSL dump failed ({}): {}", pipeline.location, e.message)
            }
        }
        // 两个阶段都单独编译：只报第一个错会让下一个错误等下一次启动才暴露。
        val msBytes = MCDR_ABI.D3D12CompileShaderDxc(sources.meshHlsl, "ms_main", "ms_6_9")
        if (msBytes == null) {
            LOGGER.warn("gbuffer MS failed to compile ({}): {}", pipeline.location, MCDR_ABI.D3D12LastError())
            dumpFailedShader(pipeline, "ms", sources.meshHlsl)
        }
        val psBytes = MCDR_ABI.D3D12CompileShaderDxc(sources.pixelHlsl, "gbuf_main", "ps_6_0")
        if (psBytes == null) {
            LOGGER.warn("gbuffer PS failed to compile ({}): {}", pipeline.location, MCDR_ABI.D3D12LastError())
            dumpFailedShader(pipeline, "ps", sources.pixelHlsl)
        }
        if (msBytes == null || psBytes == null) {
            return 0L
        }
        val handle = MCDR_ABI.D3D12CreateRtGBufferProgram(msBytes, psBytes, bindings, cull, pipeline.polygonMode.ordinal)
        if (handle == 0L) {
            LOGGER.warn("gbuffer: program creation failed ({})", pipeline.location)
            LOGGER.debug("gbuffer: {}", MCDR_ABI.D3D12LastError())
            return 0L
        }
        return handle
    }

    /** 编译失败时无条件落盘：注入点在 spvc 输出里，没有现场产物就只能靠猜。 */
    private fun dumpFailedShader(pipeline: RenderPipeline, stage: String, hlsl: String) {
        try {
            val dir = java.io.File("dxrmc/hlsl")
            dir.mkdirs()
            val stamp = pipeline.location.toString().hashCode().toUInt().toString(16)
            java.io.File(dir, "gbuffer_failed_${stage}_$stamp.hlsl").writeText(hlsl)
        } catch (e: java.io.IOException) {
            LOGGER.warn("gbuffer HLSL dump failed ({}): {}", pipeline.location, e.message)
        }
    }
    private fun getOrCompileShader(id: Identifier, type: ShaderType,
        defines: ShaderDefines, shaderSource: ShaderSource?): D3D12IntermediaryShaderModule {
        val key = ShaderCompilationKey(id, type, defines)
        return this.shaderCache.computeIfAbsent(key, { ignored -> this.compileShader(key, shaderSource) })
    }

    private fun compileShader(key: ShaderCompilationKey,
        shaderSource: ShaderSource?): D3D12IntermediaryShaderModule {
        // 主源是 ShaderManager 的 compilationCache，首次 apply() 之前恒返回 null → 回退到 preloadUiShader 捕获的
        // vanilla 包源，否则 LoadingOverlay 首帧画 mojang_logo 会因 INVALID 管线终结 JVM。
        val source = shaderSource?.getShader(key.id, key.type)
            ?: D3D12FallbackShaderSource.get(key.id, key.type)
        if (source == null) {
            LOGGER.error("no source for {} shader ({})", key.type, key.id)
            return D3D12IntermediaryShaderModule.INVALID
        }
        val sourceWithDefines = injectShaderDefines(source, key.defines)
        // 26.3 的 `#include <minecraft:xxx.glsl>` 由官方 GlslCompiler 的 shaderc 回调解析；本自建链没有回调，
        // 所以在这里文本展开（见 readShaderInclude）。
        val includeResolver: ((Identifier) -> String?)? = if (shaderSource != null) {
            { id -> readShaderInclude(shaderSource, id) }
        } else {
            D3D12FallbackShaderSource::getInclude
        }
        if (D3D12Settings.shaderConverter) {
            shadowConvert(key, sourceWithDefines, includeResolver)
        }
        try {
            return this.getOrCreateCompiler().createIntermediary(
                key.id.toDebugFileName(), sourceWithDefines, key.type, includeResolver)
        } catch (e: ShaderCompileException) {
            LOGGER.error("{} shader {} compile failed: {}", key.type, key.id, e.message)
            return D3D12IntermediaryShaderModule.INVALID
        }
    }

    /**
     * `[debug] shaderConverter`: convert the same source with `illusion_shader.dll` beside the
     * shaderc chain and record the outcome. Best-effort only — the old chain owns rendering, so a
     * converter failure just logs.
     */
    private fun shadowConvert(key: ShaderCompilationKey, source: String,
        includeResolver: ((Identifier) -> String?)?) {
        if (!IllusionShaderBridge.load(IllusionShaderBridge.configuredPath())) {
            if (!converterWarned.getAndSet(true)) {
                LOGGER.warn("shader converter: shadow conversion disabled, see the load error above")
            }
            return
        }
        val expanded = try {
            if (includeResolver != null) {
                expandShaderIncludes(source, includeResolver)
            } else {
                source
            }
        } catch (e: Exception) {
            converterStages.incrementAndGet()
            converterFailed.incrementAndGet()
            converterFailedKinds.computeIfAbsent("includes") {
                java.util.concurrent.atomic.AtomicInteger()
            }.incrementAndGet()
            LOGGER.warn("shader converter: expanding includes for {} failed: {}", key.id, e.message)
            dumpConverterSource(key, "raw", source)
            return
        }
        converterStages.incrementAndGet()
        val stage = if (key.type == ShaderType.VERTEX) "vertex" else "fragment"
        val request = JsonObject().apply {
            addProperty("path", key.id.toDebugFileName())
            addProperty("source", expanded)
            addProperty("stage", stage)
        }
        val response = try {
            IllusionShaderBridge.convert(request)
        } catch (e: Throwable) {
            converterFailed.incrementAndGet()
            converterFailedKinds.computeIfAbsent("threw") {
                java.util.concurrent.atomic.AtomicInteger()
            }.incrementAndGet()
            LOGGER.warn("shader converter: {} {} threw: {}", stage, key.id, e.message)
            return
        } ?: return
        if (!response.ok) {
            converterFailed.incrementAndGet()
            converterFailedKinds.computeIfAbsent(response.errorKind ?: "unknown") {
                java.util.concurrent.atomic.AtomicInteger()
            }.incrementAndGet()
            LOGGER.warn("shader converter: {} {} failed ({}): {}", stage, key.id,
                response.errorKind, response.errorMessage)
            dumpConverterSource(key, stage, expanded)
            return
        }
        converterSucceeded.incrementAndGet()
        val hlsl = response.hlsl ?: ""
        LOGGER.debug("shader converter: {} {} ok, {} bytes HLSL", stage, key.id, hlsl.length)
        if (response.warnings.isNotEmpty()) {
            LOGGER.debug("shader converter: {} {} warnings: {}", stage, key.id, response.warnings.joinToString("; "))
        }
        dumpConverterArtifact(key, stage, hlsl, response.manifestJson)
        if (D3D12Settings.shaderConverterPrimary) {
            converterStage(key, expanded, response)?.let { converterReady[converterKey(key)] = it }
        }
    }

    /** One stage converted by the standalone DLL, with the resource names the host has to match. */
    private data class ConverterStage(
        val hlsl: String,
        val source: String,
        val constantBuffers: List<String>,
        val textures: List<String>,
        val samplers: List<String>,
        val storageBuffers: List<String>,
        /**
         * Storage images: converted, but the host binding list has no UAV slots yet, so a pipeline
         * that writes one stays on the old chain.
         */
        val storageImages: List<String>,
        /** Vertex input names in manifest order; `inputs` holds their locations, index for index. */
        val inputNames: List<String>,
        val inputs: List<Int>,
    )

    /** `ShaderCompilationKey` compares by identity, so the converter cache is keyed by value. */
    private val converterReady =
        java.util.concurrent.ConcurrentHashMap<String, ConverterStage>()

    private fun converterKey(key: ShaderCompilationKey): String =
        "${key.id}|${key.type}|${key.defines}"
    private val converterPrimaryApplied = java.util.concurrent.atomic.AtomicInteger()
    private val converterPrimarySeen = java.util.concurrent.atomic.AtomicInteger()
    /** Full-screen blit vertex shader; the old chain flips `texCoord` for these (docs/shaders/README.md §8.3). */
    private val SCREEN_QUAD_SHADER = "minecraft:core/screenquad"
    private val SAMPLES_TEXCOORD = Regex("""texture(?:Lod|Grad|Proj|Offset)?\s*\([^;]*\btexCoord\b""")
    /** Skip reason -> pipelines kept on the old chain; the reason text is a stable label. */
    private val converterPrimarySkips = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>()

    private fun converterPrimarySkipped(): Int = converterPrimarySkips.values.sumOf { it.get() }

    private fun converterRequest(key: ShaderCompilationKey, source: String, stage: String,
        bindings: Map<String, Int>?, flipY: Boolean,
        vertexAttributes: List<String> = emptyList(),
        flipTexcoord: String? = null,
        vertexOutputs: JsonArray? = null): JsonObject = JsonObject().apply {
        addProperty("path", key.id.toDebugFileName())
        addProperty("source", source)
        addProperty("stage", stage)
        addProperty("register_layout", "host")
        if (flipY) addProperty("flip_y", true)
        if (flipTexcoord != null) addProperty("flip_texcoord", flipTexcoord)
        if (bindings != null) {
            add("bindings", JsonObject().apply {
                for ((name, register) in bindings) {
                    addProperty(name, register)
                }
            })
        }
        if (vertexAttributes.isNotEmpty()) {
            add("vertex_attributes", com.google.gson.JsonArray().apply {
                for (name in vertexAttributes) {
                    add(name)
                }
            })
        }
        if (vertexOutputs != null) add("vertex_outputs", vertexOutputs)
    }

    /**
     * The vertex stage's outputs, in declaration order, as the fragment conversion needs them.
     *
     * D3D12 links the stages by signature register and component mask, and the compiler assigns
     * those while packing each stage's parameters in order; a fragment stage that consumes only
     * part of the vertex interface therefore packs its inputs differently and the PSO is rejected
     * with `0x80070057`. Handing the vertex list to the fragment conversion lets it pad its inputs
     * to the same interface.
     */
    private fun stageOutputs(response: IllusionShaderBridge.Response): JsonArray? {
        val json = response.manifestJson ?: return null
        val manifest = try {
            com.google.gson.JsonParser.parseString(json).asJsonObject
        } catch (e: RuntimeException) {
            return null
        }
        val outputs = manifest.getAsJsonObject("stage_io")?.getAsJsonArray("outputs") ?: return null
        if (outputs.isEmpty) return null
        return JsonArray().apply {
            for (element in outputs) {
                val entry = element.asJsonObject
                val location = entry.get("location")?.asInt ?: continue
                val hlslType = entry.get("hlsl_type")?.asString ?: continue
                add(JsonObject().apply {
                    addProperty("location", location)
                    addProperty("hlsl_type", hlslType)
                    entry.get("interpolation")?.asString?.let { addProperty("interpolation", it) }
                })
            }
        }
    }

    /**
     * Attribute names in the order the input layout lists them: the converter numbers `LOC<n>`
     * after this sequence, so a shader that declares its attributes in another order still reads
     * the right bytes.
     */
    private fun vertexAttributeOrder(pipeline: RenderPipeline): List<String> {
        val bindings = pipeline.vertexFormatBindings
        val names = ArrayList<String>()
        for (i in bindings.indices) {
            val format = bindings[i] ?: continue
            for (element in format.elements) {
                names.add(element.name())
            }
        }
        return names
    }

    /** Reads the vertex input names and locations out of one converter response, in manifest order. */
    private fun stageInputs(response: IllusionShaderBridge.Response): List<Pair<String, Int>> {
        val json = response.manifestJson ?: return emptyList()
        val manifest = try {
            com.google.gson.JsonParser.parseString(json).asJsonObject
        } catch (e: RuntimeException) {
            return emptyList()
        }
        val inputs = manifest.getAsJsonObject("stage_io")?.getAsJsonArray("inputs") ?: return emptyList()
        return inputs.mapNotNull { element ->
            val entry = element.asJsonObject
            val name = entry.get("name")?.asString ?: return@mapNotNull null
            val location = entry.get("location")?.asInt ?: return@mapNotNull null
            name to location
        }
    }

    /** Reads the resource names and input locations out of a converter manifest. */
    private fun converterStage(key: ShaderCompilationKey, source: String,
        response: IllusionShaderBridge.Response): ConverterStage? {
        val hlsl = response.hlsl ?: return null
        val manifestJson = response.manifestJson ?: return null
        val manifest = try {
            com.google.gson.JsonParser.parseString(manifestJson).asJsonObject
        } catch (e: RuntimeException) {
            LOGGER.warn("shader converter: {} manifest is not JSON: {}", key.id, e.message)
            return null
        }
        val resources = manifest.getAsJsonObject("resources") ?: return null
        fun names(field: String): List<String> =
            resources.getAsJsonArray(field)?.mapNotNull {
                it.asJsonObject.get("name")?.asString
            } ?: emptyList()
        val stageInputs = manifest.getAsJsonObject("stage_io")?.getAsJsonArray("inputs")
            ?.mapNotNull { element ->
                val entry = element.asJsonObject
                val location = entry.get("location")?.asInt ?: return@mapNotNull null
                val name = entry.get("name")?.asString ?: return@mapNotNull null
                name to location
            } ?: emptyList()
        return ConverterStage(
            hlsl = hlsl,
            source = source,
            // The manifest prefixes constant buffers with `constants_`; the host names them by
            // the block (struct) name, which is what the converter binds on as well.
            constantBuffers = names("constant_buffers").map { it.removePrefix("constants_") },
            textures = names("textures"),
            samplers = names("samplers"),
            storageBuffers = names("storage_buffers"),
            storageImages = names("storage_images"),
            inputNames = stageInputs.map { it.first },
            inputs = stageInputs.map { it.second },
        )
    }

    /**
     * Renders this pipeline with the converter's HLSL when every resource it uses has a register in
     * the host's merged binding list, and leaves the SPIRV-Cross result alone otherwise.
     */
    private fun applyConverterPrimary(compiled: D3D12CompiledShader, pipeline: RenderPipeline,
        flipY: Boolean): D3D12CompiledShader? {
        if (!D3D12Settings.shaderConverterPrimary) return null
        val pipeName = pipeline.location.toString()
        if (converterPrimarySeen.incrementAndGet() <= 10) {
            LOGGER.debug("shader converter primary: considering {} (flipY {})", pipeName, flipY)
        }
        val filter = D3D12Settings.shaderConverterPipelines.split(',').map { it.trim() }
            .filter { it.isNotEmpty() }
        if (filter.isNotEmpty() && filter.none { pipeName.contains(it) }) {
            return primarySkip(pipeName, "pipeline filter")
        }
        // Terrain has no indexed-draw fallback: it is drawn through the meshlet variant the host wraps
        // around its vertex shader. The wrap understands both the spvc and the converter's shapes, so
        // the check runs on the converted HLSL below instead of skipping the pipeline up front.
        // The atlas bake pass renders a full-screen blit whose converted PSO used to be rejected
        // with 0x80070057 because the fragment stage consumed fewer inputs than the vertex stage
        // wrote; the conversion pads the interface now, so the pipeline renders converted HLSL.
        val vertexId = pipeline.shaders[ShaderType.VERTEX] ?: pipeline.location
        val fragmentId = pipeline.shaders[ShaderType.FRAGMENT] ?: pipeline.location
        val vertexKey = ShaderCompilationKey(vertexId, ShaderType.VERTEX, pipeline.shaderDefines)
        val fragmentKey = ShaderCompilationKey(fragmentId, ShaderType.FRAGMENT, pipeline.shaderDefines)
        val vertex = converterReady[converterKey(vertexKey)]
            ?: return primarySkip(pipeName, "vertex artifact missing", vertexId.toString())
        val fragment = converterReady[converterKey(fragmentKey)]
            ?: return primarySkip(pipeName, "fragment artifact missing", fragmentId.toString())
        // The old chain flips `texCoord` in two cases: the lightmap update pass, and full-screen
        // blits whose fragment samples with `texCoord` (a raster-to-raster copy would otherwise be
        // mirrored vertically). `blit_depth` also writes the depth attachment, so without the flip
        // the OIT depth bounds are mirrored and clouds cull against the wrong terrain depth.
        val flipTexcoord = when {
            flipY -> null
            pipeName.contains("lightmap") -> "texCoord"
            pipeline.shaders[ShaderType.VERTEX]?.toString() == SCREEN_QUAD_SHADER &&
                SAMPLES_TEXCOORD.containsMatchIn(fragment.source) -> "texCoord"
            else -> null
        }
        // The atlas bake pass renders into the atlas with no depth attachment, so the pass flag is
        // false there; the old chain flips its vertex stage unconditionally. Without the same flip
        // each baked frame lands mirrored inside its cell and the whole atlas is scrambled.
        val flipVertices = flipY || pipeName.contains("animate_sprite")
        // Buffer textures come out of the converter as `ByteAddressBuffer`: the manifest reports the
        // lowered storage buffer under the GLSL uniform's name, so a texel buffer entry is only
        // convertible when the converter lowered it. Anything else stays on the old typed view.
        val rawBuffers = (vertex.storageBuffers + fragment.storageBuffers).toSet()
        // Storage images need a UAV slot, and this binding list only carries SRV/CBV entries.
        val storageImages = vertex.storageImages + fragment.storageImages
        if (storageImages.isNotEmpty()) {
            return primarySkip(pipeName, "storage image", storageImages.joinToString(","))
        }
        // The host numbers b/t/s by the entry index, so the entry list is the binding plan.
        val registers = HashMap<String, Int>()
        for ((index, entry) in compiled.entries.withIndex()) {
            if (entry.type == D3D12BindGroupEntry.Type.TEXEL_BUFFER && entry.name !in rawBuffers) {
                return primarySkip(pipeName, "texel buffer", entry.name)
            }
            registers[entry.name] = index
        }
        fun plan(stage: ConverterStage, label: String): Map<String, Int>? {
            val plan = LinkedHashMap<String, Int>()
            // A split sampler binds to its texture's register, so only textures need an entry.
            for (name in stage.constantBuffers + stage.textures + stage.storageBuffers) {
                plan[name] = registers[name] ?: return primarySkip(pipeName, "missing resource", "$label $name")
            }
            return plan
        }
        val vertexPlan = plan(vertex, "vertex") ?: return null
        val fragmentPlan = plan(fragment, "fragment") ?: return null
        val vertexResponse = try {
            IllusionShaderBridge.convert(
                converterRequest(vertexKey, vertex.source, "vertex", vertexPlan, flipVertices,
                    vertexAttributeOrder(pipeline)))
        } catch (e: Throwable) {
            return primarySkip(pipeName, "vertex conversion threw", e.message)
        } ?: return null
        if (!vertexResponse.ok) {
            return primarySkip(pipeName, "vertex conversion failed", vertexResponse.errorMessage)
        }
        // The meshlet wrap is built from this HLSL; without it the terrain would not be drawn at all,
        // so the pipeline has to stay on the old chain when the converted shape cannot be wrapped.
        if (D3D12Settings.meshTerrain && pipeName.contains("terrain") &&
            !pipeName.contains("shadow") &&
            D3D12MeshletShader.build(vertexResponse.hlsl ?: return primarySkip(pipeName, "vertex artifact missing"),
                pipeline.vertexFormatBindings.firstOrNull(), cull = false) == null) {
            return primarySkip(pipeName, "meshlet variant")
        }
        val fragmentResponse = try {
            IllusionShaderBridge.convert(
                converterRequest(fragmentKey, fragment.source, "fragment", fragmentPlan, flipY,
                    flipTexcoord = flipTexcoord, vertexOutputs = stageOutputs(vertexResponse)))
        } catch (e: Throwable) {
            return primarySkip(pipeName, "fragment conversion threw", e.message)
        } ?: return null
        if (!fragmentResponse.ok) {
            return primarySkip(pipeName, "fragment conversion failed", fragmentResponse.errorMessage)
        }
        val vertexHlsl = vertexResponse.hlsl ?: return null
        val fragmentHlsl = fragmentResponse.hlsl ?: return null
        // 和 meshlet 包装同一条依赖：G-Buffer 变体是从这份 HLSL 生成的，生成不出来就没有重放批次，
        // 世界照常渲染但光追整条链安静消失。所以转换形状不满足时回旧链。
        // 判据必须与 createMeshVariant 里建变体的条件逐条对齐，否则会把根本不会有变体的管线也踢回旧链。
        if (D3D12RtGBuffer.isActive() && D3D12Settings.meshTerrain &&
            pipeName.contains("terrain") && !pipeName.contains("shadow") && isGBufferLayer(pipeName) &&
            D3D12RtGBufferShader.build(vertexHlsl, fragmentHlsl,
                pipeline.vertexFormatBindings.firstOrNull(), D3D12RtGBuffer.DEFAULT_ROUGHNESS) == null) {
            return primarySkip(pipeName, "gbuffer variant", D3D12RtGBufferShader.lastFailure)
        }
        // The cached artifact came from the shadow pass, which numbers locations by declaration
        // order; the primary pass re-converted with the host attribute order, so read the locations
        // out of that response instead of out of the cached manifest.
        val inputLocations = stageInputs(vertexResponse)
        val orderedNames = vertexAttributeOrder(pipeline).filter { it in compiled.vertexShaderInputs }
        val locationByName = inputLocations.toMap()
        val missing = inputLocations.map { it.first }.filter { it !in orderedNames }
        if (missing.isNotEmpty()) {
            return primarySkip(pipeName, "vertex attribute missing from the layout", missing.joinToString(","))
        }
        // Naga drops inputs the shader never reads, so a layout element can be missing from the
        // converted signature; D3D12 accepts those with a filler semantic.
        val semantics = orderedNames.mapIndexed { index, name ->
            locationByName[name]?.let { "LOC$it" } ?: "UNUSED$index"
        }.toMutableList()
        LOGGER.debug("shader converter primary: {} renders converted HLSL ({} resources), semantics {}",
            pipeName, registers.size, semantics.joinToString(","))
        dumpPrimaryArtifact(pipeName, vertexPlan, fragmentPlan, vertexHlsl, fragmentHlsl)
        return compiled.copy(
            vertexHlsl = vertexHlsl,
            fragmentHlsl = fragmentHlsl,
            semanticNames = semantics,
            rawBuffers = rawBuffers,
            entries = compiled.entries.map { entry ->
                if (entry.type == D3D12BindGroupEntry.Type.TEXEL_BUFFER && entry.name in rawBuffers) {
                    entry.copy(type = D3D12BindGroupEntry.Type.RAW_BUFFER, texelBufferFormat = null)
                } else {
                    entry
                }
            },
        )
    }

    /**
     * Records a pipeline that asked for the converter but had to stay on the old chain.
     *
     * `reason` is a fixed category because the closing summary counts by it; anything that varies
     * per pipeline goes into `detail`, which only reaches the per-pipeline debug line.
     */
    private fun primarySkip(pipeline: String, reason: String, detail: String? = null): Nothing? {
        val skips = converterPrimarySkips.computeIfAbsent(reason) { java.util.concurrent.atomic.AtomicInteger() }
        if (skips.incrementAndGet() <= 20) {
            if (detail == null) {
                LOGGER.debug("shader converter primary: {} keeps the old chain ({})", pipeline, reason)
            } else {
                LOGGER.debug("shader converter primary: {} keeps the old chain ({}: {})", pipeline, reason, detail)
            }
        }
        return null
    }

    /**
     * Writes the primary HLSL next to the game, in `dxrmc/converter/primary`, keyed by pipeline name.
     *
     * The shadow artifacts in `dxrmc/converter` use the path register layout, so they do not show the
     * registers the host actually binds; this dump carries the host plan as `plan.txt`.
     */
    private fun dumpPrimaryArtifact(pipeline: String, vertexPlan: Map<String, Int>, fragmentPlan: Map<String, Int>,
        vertexHlsl: String, fragmentHlsl: String) {
        if (converterPrimaryDumps.incrementAndGet() > 400) return
        try {
            val dir = java.nio.file.Path.of(System.getProperty("user.dir"), "dxrmc", "converter", "primary")
            java.nio.file.Files.createDirectories(dir)
            val base = pipeline.replace(Regex("[^A-Za-z0-9._-]"), "_")
            java.nio.file.Files.writeString(dir.resolve("$base.vs.hlsl"), vertexHlsl)
            java.nio.file.Files.writeString(dir.resolve("$base.ps.hlsl"), fragmentHlsl)
            val plan = buildString {
                appendLine("vertex")
                vertexPlan.forEach { (name, register) -> appendLine("$name = $register") }
                appendLine("fragment")
                fragmentPlan.forEach { (name, register) -> appendLine("$name = $register") }
            }
            java.nio.file.Files.writeString(dir.resolve("$base.plan.txt"), plan)
        } catch (e: java.io.IOException) {
            LOGGER.warn("shader converter primary: cannot dump {} ({})", pipeline, e.message)
        }
    }

    /** Writes the HLSL and the host binding plan of a pipeline whose PSO creation failed. */
    private fun dumpConverterFailure(pipeline: RenderPipeline, compiled: D3D12CompiledShader) {        try {
            val dir = java.nio.file.Path.of(System.getProperty("user.dir"), "dxrmc", "converter")
            java.nio.file.Files.createDirectories(dir)
            val base = pipeline.location.toString().replace(Regex("[^A-Za-z0-9._-]"), "_")
            java.nio.file.Files.writeString(dir.resolve("failed_$base.vs.hlsl"), compiled.vertexHlsl)
            java.nio.file.Files.writeString(dir.resolve("failed_$base.ps.hlsl"), compiled.fragmentHlsl)
            val plan = compiled.entries.withIndex().joinToString("\n") { (i, e) -> "$i ${e.type} ${e.name}" }
            java.nio.file.Files.writeString(dir.resolve("failed_$base.entries.txt"), plan)
            val semantics = compiled.semanticNames.joinToString(", ")
            java.nio.file.Files.writeString(dir.resolve("failed_$base.io.txt"), semantics)
            LOGGER.debug("shader converter primary: dumped failing pipeline {} ({} entries)", pipeline.location,
                compiled.entries.size)
        } catch (e: Throwable) {
            LOGGER.warn("shader converter primary: cannot dump {} ({})", pipeline.location, e.message)
        }
    }

    /** Keep the first 400 converted stages next to the game for inspection (HLSL + manifest). */
    private fun dumpConverterArtifact(key: ShaderCompilationKey, stage: String, hlsl: String,
        manifestJson: String?) {
        if (converterDumps.incrementAndGet() > 400) return
        try {
            val dir = java.nio.file.Path.of(System.getProperty("user.dir"), "dxrmc", "converter")
            java.nio.file.Files.createDirectories(dir)
            val name = key.id.toString().replace(Regex("[^A-Za-z0-9._-]"), "_") + "." + stage
            java.nio.file.Files.writeString(dir.resolve("$name.hlsl"), hlsl)
            if (manifestJson != null) {
                java.nio.file.Files.writeString(dir.resolve("$name.manifest.json"), manifestJson)
            }
        } catch (e: java.io.IOException) {
            LOGGER.debug("shader converter: writing artifacts failed: {}", e.message)
        }
    }

    /** Keep the source that failed to convert, so the difference to the corpus copy is inspectable. */
    private fun dumpConverterSource(key: ShaderCompilationKey, stage: String, source: String) {
        try {
            val dir = java.nio.file.Path.of(System.getProperty("user.dir"), "dxrmc", "converter")
            java.nio.file.Files.createDirectories(dir)
            val name = key.id.toString().replace(Regex("[^A-Za-z0-9._-]"), "_") + "." + stage + ".failed.glsl"
            java.nio.file.Files.writeString(dir.resolve(name), source)
        } catch (e: java.io.IOException) {
            LOGGER.debug("shader converter: writing the failed source failed: {}", e.message)
        }
    }

    /**
     * 读一条 `#include`（`shaders/include/` 下）。必须优先从 ResourceManager 直读：`ShaderSource.getInclude`
     * 给出的结构只在 vanilla 回调期间有效，之后再读是已释放内存（尾部混进 U+FFFD → shaderc "unexpected token"）。
     * 路径与 vanilla 一致：`<namespace>:shaders/include/<path>`；读不到才退回 getInclude 并丢弃含替换字符的结果。
     */
    private fun readShaderInclude(shaderSource: ShaderSource, id: Identifier): String? {
        readIncludeFromResources(id)?.let { if ('\uFFFD' !in it) return it }
        val cached = try {
            shaderSource.getInclude(id)
        } catch (e: Exception) {
            LOGGER.debug("include {} lookup failed: {}", id, e.message)
            null
        } ?: return null
        return try {
            val text = ShadercIncludeResult.create(cached.includeResultPtr()).contentString()
            if ('\uFFFD' in text) {
                LOGGER.warn("include {} cached copy looks corrupted", id)
                null
            } else {
                text
            }
        } catch (e: Throwable) {
            LOGGER.debug("include {} content unreadable: {}", id, e.message)
            null
        }
    }

    private fun readIncludeFromResources(id: Identifier): String? {
        return try {
            val location = Identifier.fromNamespaceAndPath(id.namespace, "shaders/include/" + id.path)
            Minecraft.getInstance().resourceManager.openAsReader(location).use { it.readText() }
        } catch (e: Exception) {
            LOGGER.debug("include {} read failed: {}", id, e.message)
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
     * 生成 D3D12 输入布局元素：只含顶点着色器实际声明的输入，attribLocation 分配必须与
     * [D3D12IntermediaryShaderModule.rebind] 完全一致。
     */
    private fun buildVertexInputElements(pipeline: RenderPipeline,
        vertexShaderInputs: List<String>): MutableList<IntArray> {
        val elements = ArrayList<IntArray>()
        var attribLocation = 0
        val bindings = pipeline.vertexFormatBindings
        if (bindings.isEmpty()) {
            LOGGER.warn("{}: formatBindings empty, inputs={}", pipeline.location, vertexShaderInputs)
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
        // 地形 slot/stepRate 诊断：26.3 把 ChunkPosition/ChunkVisibility 挪成逐实例属性（slot 1, stepRate=1）。
        if (pipeline.location.toString().contains("terrain")) {
            val slots = bindings.indices.joinToString(", ") { i ->
                val fmt = bindings[i]
                if (fmt == null) "s$i=null" else "s$i(stride=${fmt.vertexSize},step=${fmt.stepRate}," +
                    "elems=${fmt.elements.joinToString("|") { e -> e.name() }})"
            }
            IllusionLogger.debugMessage("${pipeline.location}: inputs=$vertexShaderInputs slots=[$slots]")
        }
        return elements
    }

    /**
     * 构建 `mcdr_dx12CreateGraphicsPipeline` 的 desc。FFM 下用 [MCDRPipelineDesc] 与 native 的
     * `mcdr_pipeline_desc` 共享字段定义，启动自检会抛出任何漂移（不再手工打包 ByteBuffer）。
     */
    private fun buildNativeDesc(compiled: D3D12CompiledShader,
                                pipeline: RenderPipeline, flipY: Boolean): MCDRPipelineDesc {
        val colorTargets = pipeline.colorTargetStates
        val colorCount = colorTargets.size
        val hasDepth = pipeline.depthStencilState != null
        val inputElements = buildVertexInputElements(pipeline, compiled.vertexShaderInputs)
        val semanticNames = compiled.semanticNames
        // semanticNames 可能少于 inputElements（gui 管线 HLSL 只有 1 个输入但格式有 2 个）：补 TEXCOORDn。
        while (semanticNames.size < inputElements.size) {
            semanticNames.add("TEXCOORD${semanticNames.size}")
        }
        // 语义名必须与 inputElements 逐项对齐（native 启动自检兜底）。
        val entries = compiled.entries

        val desc = MCDRPipelineDesc()
            .shaders(compiled.vertexHlsl, compiled.fragmentHlsl)

        for (i in 0 until colorCount) {
            // ColorTargetState 数组长度是 MAX_COLOR_TARGETS，未用槽位为 null，跳过即可。
            val target = colorTargets[i] ?: continue
            desc.addColorTarget(toColorTarget(target))
        }

        val loc = pipeline.location.toString()
        if (hasDepth) {
            // flipY + /item_cutout + NEVER_PASS 的换向分支：NEVER_PASS 在客户端源码里无人使用，属死代码，
            // 保留仅为不改变行为。深度问题再现时从 DepthStencilState 的实际取值与深度清屏值查起。
            var dss = pipeline.depthStencilState!!
            if (flipY && loc.contains("/item_cutout") && dss.depthTest() == CompareOp.NEVER_PASS) {
                dss = DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, dss.writeDepth())
            }
            // 准星走带深度测试的 GUI 管线，而 GUI 目标深度清成 0.0（反向 Z 下即最远）→ GREATER 比较会整条丢弃。
            // 准星是 2D 贴图，关掉深度测试既符合本意，也不影响世界内任何管线。
            if (loc.contains("pipeline/crosshair")) {
                dss = DepthStencilState(CompareOp.ALWAYS_PASS, false)
            }
            desc.depth(GpuFormat.D32_FLOAT.ordinal, dss.writeDepth(), dss.depthTest().ordinal)
        }

        var cull = pipeline.isCull
        // shader Y-flip 会反转绕序（CCW→CW）：flipY 的 pass 里每条管线都注入了翻转 ⇒ 必须整体关剔除；
        // 只可能「多画」，不会少画（白名单漏掉的 entity_translucent 会被背面剔除吃掉）。
        if (loc.contains("animate_sprite") || flipY) {
            cull = false
        }
        desc.topology(pipeline.primitiveTopology.ordinal, cull,
            pipeline.polygonMode.ordinal)

        // 顺序：location, binding, format, offset, stride, stepRate, HLSL 语义
        for (i in 0 until inputElements.size) {
            val element = inputElements[i]
            desc.addInputElement(
                MCDRPipelineDesc.InputElement(
                element[0],
                element[1],
                element[2],
                element[3],
                element[4],
                element[5],
                semanticNames[i]))
        }

        // type：0=CBV 1=SRV+sampler 2=SRV(texel) 3=SRV(raw)；reg = 条目序号，与 HLSL shader register 一一对应
        for (i in 0 until entries.size) {
            val type = when (entries[i].type) {
                D3D12BindGroupEntry.Type.UNIFORM_BUFFER -> 0
                D3D12BindGroupEntry.Type.SAMPLED_IMAGE -> 1
                D3D12BindGroupEntry.Type.TEXEL_BUFFER -> 2
                D3D12BindGroupEntry.Type.RAW_BUFFER -> 3
            }
            desc.addBinding(type, i)
        }
        return desc
    }

    /** `null`（withUnusedColorTargetState）= 槽位未使用：format=-1（DXGI_FORMAT_UNKNOWN）、无混合、无写入。 */
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

    private class ShaderCompilationKey(val id: Identifier, val type: ShaderType, val defines: ShaderDefines) {
        override fun toString(): String {
            val s = "${this.id.toString()} (${this.type})"
            return if (this.defines.isEmpty) s else "${s} with ${this.defines}"
        }
    }

    companion object {
        private val LOGGER: Logger = LoggerFactory.getLogger("dxrmc")

        /** 标记 DX12 后端是否已成功初始化。 */
        @Volatile
        private var initialized: Boolean = false

        @JvmStatic
        fun isInitialized(): Boolean {
            return initialized
        }

        /**
         * 整帧共享的 CommandEncoder（[createCommandEncoder] 里登记、close() 里清空）。官方 GpuDevice 不暴露
         * backend、`CommandEncoder.backend()` 是 protected，Java 侧要靠这个出口在帧中间追加后处理。
         * null = 设备未起或已销毁，调用方直接跳过。
         */
        @Volatile
        private var sharedEncoderForFrame: D3D12CommandEncoderBackend? = null

        @JvmStatic
        fun sharedCommandEncoder(): D3D12CommandEncoderBackend? {
            return sharedEncoderForFrame
        }

        /** 最近创建、且还活着的设备；光追自检要自带编码器，标题界面就能跑（不必先建世界）。 */
        @Volatile
        private var current: D3D12Device? = null

        @JvmStatic
        fun current(): D3D12Device? {
            return current
        }

        private fun parseAdapterName(probe: String?): String {
            if (probe == null || probe.isBlank()) {
                return "D3D12 adapter"
            }
            val sep = probe.indexOf(" (")
            return if (sep < 0) probe else probe.substring(0, sep)
        }

        /** 组装 F3 可见的设备描述（对齐 VulkanDevice / GlHeuristics）：值全部来自 native 适配器查询，常量仅作回落。 */
        private fun buildDeviceInfo(
            adapterName: String,
            nativeInfo: D3D12AdapterInfo,
            timestampFrequency: Long
        ): DeviceInfo {
            val timestampPeriod = if (timestampFrequency > 0L) 1.0f / timestampFrequency.toFloat() else 1.0f
            val haveNative = nativeInfo !== D3D12AdapterInfo.UNKNOWN
            val name = if (haveNative && nativeInfo.name.isNotEmpty()) nativeInfo.name else adapterName
            return DeviceInfo(
                name,
                if (haveNative) nativeInfo.vendorName() else "D3D12 Unknown",
                if (haveNative) nativeInfo.driverInfo() else "MCDR D3D12 Driver",
                true,                                       // D3D12 NDC depth is 0..1
                if (haveNative) nativeInfo.backendName() else "DirectX 12",
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













