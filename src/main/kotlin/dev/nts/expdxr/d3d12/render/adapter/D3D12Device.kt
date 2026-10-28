package dev.nts.expdxr.d3d12.render.adapter

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
import dev.nts.expdxr.misc.settings.D3D12Settings
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
    /**
     * flipY 变体管线缓存（GUI 离屏 pass 专用）。键是值语义的 [FlipYKey]、每次调用新建实例，必须用
     * HashMap 按 equals/hashCode 查；误用 IdentityHashMap 会永远 miss → 每帧重编译，帧率崩。
     */
    private val pipelineCacheFlipY: MutableMap<FlipYKey, D3D12CompiledRenderPipeline> = HashMap()
    private val shaderCache: MutableMap<ShaderCompilationKey, D3D12IntermediaryShaderModule> = HashMap()
    private var glslCompiler: D3D12ShaderCompiler? = null

    init {
        val probe = MCDR_ABI.ID3D12CreateDevice()
        LOGGER.info("Device probe + resource self-test: {}", probe)
        val frequency = MCDR_ABI.D3D12GetTimestampFrequency()
        if (frequency == 0L) {
            LOGGER.warn("GetTimestampFrequency returned 0; timestampPeriod=1.0")
        }
        // 适配器信息取自 DXGI/DXCore（F3 与系统信息行读 D3D12AdapterInfo.current）；查询失败保持 null。
        val nativeInfo = D3D12AdapterInfo.parse(MCDR_ABI.ID3D12GetAdapterInfo())
        if (nativeInfo !== D3D12AdapterInfo.UNKNOWN) {
            D3D12AdapterInfo.current = nativeInfo
            LOGGER.info("Adapter: {}", nativeInfo.summary())
        } else {
            LOGGER.warn("Adapter info unavailable; keeping the probe-derived fallback")
        }
        this.deviceInfo = buildDeviceInfo(parseAdapterName(probe), nativeInfo, frequency)
        // 启动独立渲染线程（native 负责 acquire/present/fence-wait，主线程录制命令）。
        // 诊断开关 `[debug] asyncRenderThread = false` 时不启动，此时不得依赖异步提交语义。
        if (D3D12Settings.asyncRenderThread) {
            MCDR_ABI.D3D12InitAsyncRenderer(1)
        } else {
            LOGGER.warn("async renderer disabled ([debug] asyncRenderThread = false) — diagnostic mode only")
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
        // 临时诊断：追踪 READBACK 缓冲的创建方。
        if (usage and GpuBuffer.USAGE_MAP_READ != 0) {
            val n = G_P199G_COUNT.getAndIncrement()
            if (n < 24) {
                val stack = Thread.currentThread().stackTrace
                    .drop(1).take(9).joinToString(" <- ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
                IllusionLogger.warnMessage(
                    "MAP_READ buffer label='${name}' usage=0x${usage.toUInt().toString(16)} size=${size} @ $stack")
            }
        }
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
            LOGGER.error("compilePipeline({}) has no registered RenderPipeline "
                + "({} shaders); returning NULL pending", info.name(), info.shaders().size)
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
        // 必须趁 DXGI/D3D12 仍存活时显式释放设备：留给 DLL_PROCESS_DETACH 会让 ComPtr::Release()
        // 在 dxgi.dll 已 detach 后执行 → 原生异常，退出码变成 2170。
        MCDR_ABI.D3D12DestroyDevice()
        // 清掉适配器信息：F3 与系统信息行按它非空决定是否显示 DX12 行。
        D3D12AdapterInfo.current = null
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
            LOGGER.error("{} COMPILE FAILED (vertex): shader {} invalid",
                pipeName, vertexId)
            return D3D12CompiledRenderPipeline(pipeline, 0L, vertexShader, fragmentShader, "", "")
        }
        if (fragmentShader === D3D12IntermediaryShaderModule.INVALID) {
            LOGGER.error("{} COMPILE FAILED (fragment): shader {} invalid",
                pipeName, fragmentId)
            return D3D12CompiledRenderPipeline(pipeline, 0L, vertexShader, fragmentShader, "", "")
        }
        try {
            val compiled = this.getOrCreateCompiler()
                .compile(pipeline, vertexShader, fragmentShader, flipY)
            val desc = buildNativeDesc(compiled, pipeline, flipY)
            val handle = MCDR_ABI.D3D12CreateGraphicsPipeline(desc)
            if (handle == 0L) {
                LOGGER.error("{} COMPILE FAILED (native): dx12CreateGraphicsPipeline returned 0",
                    pipeName)
                return D3D12CompiledRenderPipeline(pipeline, 0L, vertexShader, fragmentShader, "", "")
            }
            LOGGER.info("{} COMPILE OK (handle={})", pipeName, handle)
            val result = D3D12CompiledRenderPipeline(
                pipeline, handle,
                vertexShader, fragmentShader, compiled.vertexHlsl, compiled.fragmentHlsl
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
                    LOGGER.info("line quad variant for {}: pso={}", pipeName, quadPso)
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
                    LOGGER.info("meshlet variant for {}: pso={} as={}",
                        pipeName, variant.meshletPso, variant.meshletAs)
                } else {
                    LOGGER.warn("meshlet variant for {} failed", pipeName)
                }
            }
            return result
        } catch (e: ShaderCompileException) {
            LOGGER.error("{} COMPILE FAILED (compile): {}", pipeName, e.message)
            return D3D12CompiledRenderPipeline(pipeline, 0L, vertexShader, fragmentShader, "", "")
        }
    }

    /** meshlet 变体产物（`meshletPso == 0` 表示建失败）。 */
    private data class MeshVariantResult(val meshletPso: Long, val meshletAs: Boolean)

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
            }
            type to index
        }
        val signature = MCDR_ABI.D3D12CreateMeshRootSignature(bindings, 1)
        if (signature == 0L) {
            LOGGER.error("mesh variant for {}: createMeshRootSignature returned 0", pipeline.location)
            return MeshVariantResult(0L, false)
        }
        var meshletHandle = 0L
        var meshletAs = false
        try {
            val wantAs = D3D12Settings.meshTerrainCull
            var hlsl = D3D12MeshletShader.build(compiled.vertexHlsl, format, cull = wantAs)
            var asActive = wantAs
            if (hlsl == null && wantAs) {
                // uniform 解析不到时退回不剔除的 meshlet（剔除判据不全会成片剔掉地形）。
                LOGGER.warn("AS build failed ({}), falling back to meshlets without culling", pipeline.location)
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
                LOGGER.warn("meshlet MS/AS failed to compile ({}): {}, falling back to the non-culling variant",
                    pipeline.location, MCDR_ABI.D3D12LastError())
                hlsl = D3D12MeshletShader.build(compiled.vertexHlsl, format, cull = false)
                meshletMs = hlsl?.let { MCDR_ABI.D3D12CompileShaderDxc(it, "ms_main", "ms_6_9") }
                meshletAsBytes = null
                asActive = false
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
                            LOGGER.warn("meshlet AS PSO failed, falling back to no culling ({})", pipeline.location)
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
        // 主源是 ShaderManager 的 compilationCache，首次 apply() 之前恒返回 null → 回退到 preloadUiShader 捕获的
        // vanilla 包源，否则 LoadingOverlay 首帧画 mojang_logo 会因 INVALID 管线终结 JVM。
        val source = shaderSource?.getShader(key.id, key.type)
            ?: D3D12FallbackShaderSource.get(key.id, key.type)
        if (source == null) {
            LOGGER.error("Couldn't find source for {} shader ({})", key.type, key.id)
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
        try {
            return this.getOrCreateCompiler().createIntermediary(
                key.id.toDebugFileName(), sourceWithDefines, key.type, includeResolver)
        } catch (e: ShaderCompileException) {
            LOGGER.error("Couldn't compile {} shader {}: {}", key.type, key.id, e.message)
            return D3D12IntermediaryShaderModule.INVALID
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
                LOGGER.warn("include {} from cache looks corrupted (freed buffer?)", id)
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
            LOGGER.debug("include {} resource read failed: {}", id, e.message)
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
            LOGGER.warn("{}: formatBindings is empty, inputs={}", pipeline.location, vertexShaderInputs)
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

        // type：0=CBV 1=SRV+sampler 2=SRV(texel)；reg = 条目序号，与 HLSL shader register 一一对应
        for (i in 0 until entries.size) {
            val type = when (entries[i].type) {
                D3D12BindGroupEntry.Type.UNIFORM_BUFFER -> 0
                D3D12BindGroupEntry.Type.SAMPLED_IMAGE -> 1
                D3D12BindGroupEntry.Type.TEXEL_BUFFER -> 2
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
        private val G_P199G_COUNT = java.util.concurrent.atomic.AtomicInteger()
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
