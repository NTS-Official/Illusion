package dev.nts.expdxr.d3d12.backend

import com.mojang.renderpearl.api.device.BackendCreationException
import com.mojang.renderpearl.api.device.GpuBackend
import com.mojang.renderpearl.api.device.GpuDevice
import com.mojang.renderpearl.api.device.GpuSurface
import com.mojang.renderpearl.api.device.SurfaceException
import com.mojang.renderpearl.frontend.FrontendGpuDevice
import dev.nts.expdxr.d3d12.render.advanced.mesh.D3D12MeshShader
import com.mojang.renderpearl.api.GpuFormat
import com.mojang.renderpearl.api.buffers.GpuBuffer
import com.mojang.renderpearl.api.commands.GpuFence
import com.mojang.renderpearl.api.device.GpuDebugOptions
import com.mojang.renderpearl.api.pipeline.ShaderSource
import com.mojang.renderpearl.api.pipeline.ShaderType
import com.mojang.renderpearl.api.textures.AddressMode
import com.mojang.renderpearl.api.textures.FilterMode
import com.mojang.renderpearl.api.textures.GpuSampler
import com.mojang.renderpearl.api.textures.GpuTexture
import dev.nts.expdxr.Illusion.Companion.NATIVE_NAME
import dev.nts.expdxr.MeshDiagnostics
import dev.nts.expdxr.misc.IllusionLogger
import dev.nts.expdxr.misc.settings.D3D12Settings
import dev.nts.expdxr.d3d12.debug.D3D12Fatal
import dev.nts.expdxr.d3d12.extern.MCDRMeshPipelineDesc
import dev.nts.expdxr.d3d12.extern.MCDRPipelineDesc
import dev.nts.expdxr.d3d12.extern.MCDR_ABI
import dev.nts.expdxr.d3d12.render.adapter.D3D12Device
import dev.nts.expdxr.d3d12.render.core.D3D12GpuTexture
import dev.nts.expdxr.d3d12.render.core.D3D12GpuSurface
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.resources.Identifier
import org.lwjgl.sdl.SDLError
import org.lwjgl.sdl.SDLVideo
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.util.*
import java.util.function.BooleanSupplier

/**
 * Direct3D 12 [GpuBackend] 实现：建窗口、建设备、跑自检链。
 *
 * 失败即崩、不回退 GL/Vulkan（崩溃路径见 [D3D12Fatal]）。
 * 初始化顺序与自检链的门槛见 docs/misc/backend-and-device.md §3.1。
 */
class D3D12Backend : GpuBackend {
    /** [createWindow] 建出来的 `SDL_Window*`；`createDevice` 里做 surface 自检时要用它。 */
    private var lastWindowHandle: Long = 0L

    override fun getName(): String = "D3D12"

    // d3d12.dll 是系统组件，不需要预加载或卸载。
    override fun loadLibrary() {}

    override fun unloadLibrary() {}

    /**
     * flags 是游戏算好的 SDL3 标志，**原样透传**。
     *
     * 绝不能或上 `SDL_WINDOW_OPENGL`：进程里一旦出现过被使用的 GL 上下文，RTSS/Afterburner 的 OSD
     * 就会改走 OpenGL 呈现路径、被 D3D12 子窗口整块盖住而永久不可见。
     */
    override fun createWindow(title: String?, width: Int, height: Int, flags: Long): Long {
        if (D3D12Settings.glContext) {
            LOGGER.warn("glContext = true has no effect since 26.3: the GL context belongs to "
                + "the OpenGL backend, the D3D12 backend never requests one")
        }
        val handle = SDLVideo.SDL_CreateWindow(title ?: "", width, height, flags)
        if (handle == 0L) {
            D3D12Fatal.crash("SDL_CreateWindow failed for DX12 (video driver: "
                + "${SDLVideo.SDL_GetCurrentVideoDriver()}): ${SDLError.SDL_GetError()}", null)
        }
        lastWindowHandle = handle
        LOGGER.info("[$NATIVE_NAME] SDL window: {}x{}, flags=0x{}", width, height, flags.toString(16))
        return handle
    }

    @Throws(BackendCreationException::class)
    override fun createDevice(debugOptions: GpuDebugOptions): GpuDevice {
        // 失败不回退：D3D12Fatal 抛 ReportedException，vanilla 的候选后端循环吞不掉。
        // [debug] failDevice = true 是这条崩溃路径的测试钩子，默认 false。
        if (D3D12Settings.failDevice) {
            D3D12Fatal.crash("simulated Direct3D 12 device failure ([debug] failDevice = true)", null)
        }
        try {
            // 这一次通常被**静默丢弃**：MCDR_ABI 句柄要等 D3D12Device 构造时才加载。
            D3D12Settings.pushToNative()
            // 内嵌 GLSL 只作兜底，真实 shader 源由 D3D12PipelineBridge 提供。
            val device = D3D12Device(EMBEDDED_SHADER_SOURCE)
            // ABI 已加载：必须在返回前再推一次，否则 [dlss] frameGeneration 这类
            // 「只在启动时读一次」的开关永远到不了 native。
            D3D12Settings.pushToNative()

            // 地形已无 IMR 回退：mesh 能力不足时必须留下玩家可见的 ERROR，不能让地形静默消失。
            if (!D3D12MeshShader.isSupported() && D3D12Settings.meshTerrain) {
                LOGGER.error("mesh shaders are unavailable but terrain has no IMR fallback: "
                    + "terrain batches will be skipped (see the mesh support reason above)")
            }
            try {
                selfTestJavaResources(device)
                selfTestCommandLayer(device)
                selfTestPipelines(device)
                // 自检链顺序固定，后面的依赖前面的；任一步抛异常都会先 device.close() 再抛出。
                if (D3D12Settings.selfTestMesh) {
                    selfTestMesh(device)
                }
                // compute 与 SRI 两段共用同一个开关（要 DXC 现场编译，不进关键路径）。
                if (D3D12Settings.selfTestCompute) {
                    selfTestCompute()
                }
                if (D3D12Settings.selfTestCompute) {
                    selfTestShadingRate()
                }
                // 取证用：[debug] meshDiagnostics = clear|pass|pass2
                D3D12Settings.meshDiagnostics.takeIf { it != MeshDiagnostics.NONE }?.let {
                    selfTestMeshDiag(it.name.lowercase())
                }
                // 默认跳过：自检抢先 Present 会把 RTSS/Afterburner 的 OSD 永久绑在自检交换链上。
                if (D3D12Settings.selfTestSurface) {
                    selfTestSurface(device, lastWindowHandle)
                } else {
                    LOGGER.info("Surface self-test skipped: swapped-chain self-test would steal "
                        + "the RTSS OSD binding (enable with [debug] selfTestSurface = true)")
                }
            } catch (t: Throwable) {
                device.close()
                throw t
            }
            // 必须清空：自检用内嵌 GLSL 填过 cache，不清的话真实 shader 永远命中、从不编译，屏幕是紫色。
            device.clearPipelineCache()
            return FrontendGpuDevice(device)
        } catch (t: Throwable) {
            LOGGER.error("D3D12 self-test failed", t)
            LOGGER.error("D3D12 failure is fatal by design: no GL/Vulkan fallback, "
                + "preferredGraphicsBackend is reset to default and the game crashes with a report")
            // 抛 ReportedException 而不是 BackendCreationException —— 后者会被回退循环吞掉。
            D3D12Fatal.crash("DX12 self-test failed: $t", t)
        }
    }
    companion object {
        private val LOGGER: Logger = LoggerFactory.getLogger("dxr_mc")


        /** 逐项走真实 FFM 路径建纹理 / 采样器 / 缓冲并读写，绑定坏了要在这里就叫出来。 */
        private fun selfTestJavaResources(device: D3D12Device) {
            val texture: GpuTexture = device.createTexture("backend-selftest",
                GpuTexture.USAGE_TEXTURE_BINDING or GpuTexture.USAGE_RENDER_ATTACHMENT,
                GpuFormat.RGBA8_UNORM, 64, 64, 1, 1)
            val sampler: GpuSampler = device.createSampler(AddressMode.REPEAT, AddressMode.REPEAT,
                FilterMode.NEAREST, FilterMode.NEAREST, 1, OptionalDouble.of(16.0))
            val buffer: GpuBuffer = device.createBuffer({ "backend-selftest" },
                GpuBuffer.USAGE_MAP_WRITE or GpuBuffer.USAGE_COPY_DST, 1024L)
            buffer.map(0L, 1024L, false, true).use { view ->
                val data: ByteBuffer = view.data()
                data.put(0, 0x5A.toByte())
                data.put(1023, 0x5A.toByte())
                if ((data.get(0).toInt() and 0xFF) != 0x5A
                    || (data.get(1023).toInt() and 0xFF) != 0x5A) {
                    throw IllegalStateException("buffer map write/read mismatch")
                }
            }
            device.createTextureView(texture, 0, texture.mipLevels).close()
            buffer.close()
            sampler.close()
            texture.close()
            LOGGER.info("Java resource self-test OK (texture/buffer/sampler/view via FFM)")
        }

        /** 命令层自检：录制上传/拷贝 → submit → 等围栏 → 缓冲与纹理各回读一遍比对。 */
        private fun selfTestCommandLayer(device: D3D12Device) {
            val size = 256
            val src: GpuBuffer = device.createBuffer({ "backend-selftest-src" },
                GpuBuffer.USAGE_COPY_DST or GpuBuffer.USAGE_COPY_SRC, size.toLong())
            val dst: GpuBuffer = device.createBuffer({ "backend-selftest-dst" },
                GpuBuffer.USAGE_MAP_READ, size.toLong())
            val data: ByteBuffer = ByteBuffer.allocate(size)
            for (i in 0 until size) {
                data.put(i, (i and 0xFF).toByte())
            }

            val texSize = 8
            val tex: GpuTexture = device.createTexture("backend-selftest-tex",
                GpuTexture.USAGE_TEXTURE_BINDING, GpuFormat.RGBA8_UNORM, texSize, texSize, 1, 1)
            val texDst: GpuBuffer = device.createBuffer({ "backend-selftest-texdst" },
                GpuBuffer.USAGE_MAP_READ, texSize.toLong() * texSize * 4)
            val texData: ByteBuffer = ByteBuffer.allocate(texSize * texSize * 4)
            for (i in 0 until texData.capacity()) {
                texData.put(i, (i and 0xFF).toByte())
            }

            val encoder: D3D12CommandEncoderBackend = D3D12CommandEncoderBackend()
            try {
                val fence: GpuFence = encoder.createFence()
                encoder.writeToBuffer(src.slice(), data)
                encoder.copyToBuffer(src.slice(), dst.slice())
                encoder.writeToTexture(tex, texData, 0, 0, 0, 0, texSize, texSize)
                encoder.copyTextureToBuffer(tex, texDst, 0L, {}, 0)
                encoder.submit()
                if (!fence.awaitCompletion(5000L)) {
                    throw IllegalStateException("command submit timed out after 5s")
                }
                fence.close()

                // 回读：拷贝后每个字节都必须还在。
                dst.map(0L, size.toLong(), true, false).use { view ->
                    val read: ByteBuffer = view.data()
                    IllusionLogger.debugMessage(
                        "self-test: reading dst readback, size=${size}"
                            + " dst.usage=${dst.usage().toUInt().toString(2)}")
                    for (i in 0 until size) {
                        val actual = read.get(i).toInt() and 0xFF
                        val expected = i and 0xFF
                        if (actual != expected) {
                            // 不匹配时把前后各 8 字节一起打出来。
                            val start = (i - 8).coerceAtLeast(0)
                            val end = (i + 8).coerceAtMost(size)
                            val neighbors = buildString {
                                for (j in start until end) {
                                    if (j > start) append(',')
                                    append("0x%02X".format(read.get(j).toInt() and 0xFF))
                                }
                            }
                            IllusionLogger.warnMessage(
                                "mismatch at %d: expected=0x%02X actual=0x%02X neighbors=[%s]"
                                    .format(i, expected, actual, neighbors))
                            throw IllegalStateException("buffer copy readback mismatch at $i")
                        }
                    }
                }

                val rowPitch = texSize * 4
                texDst.map(0L, texDst.size(), true, false).use { view ->
                    val read: ByteBuffer = view.data()
                    for (row in 0 until texSize) {
                        for (col in 0 until texSize) {
                            val expected = (row * texSize + col) * 4 and 0xFF
                            if ((read.get(row * rowPitch + col * 4).toInt() and 0xFF) != expected) {
                                throw IllegalStateException(
                                    "texture readback mismatch at ($col,$row)"
                                )
                            }
                        }
                    }
                }
            } finally {
                encoder.close()
                texDst.close()
                tex.close()
                dst.close()
                src.close()
            }
            LOGGER.info("Command layer self-test OK")
        }
        // -----------------------------------------------------------------------
        // 自检用内嵌 GLSL：资源包未加载时也能跑完整条 GLSL→SPIR-V→HLSL→DXIL→PSO 链。
        // -----------------------------------------------------------------------

        private val CORE_GUI_VSH = """
            #version 330

            // Can't moj_import in things used during startup, when resource packs don't exist.
            // This is a copy of dynamicimports.glsl and projection.glsl
            layout(std140) uniform DynamicTransforms {
                mat4 ModelViewMat;
                vec4 ColorModulator;
                vec3 ModelOffset;
                mat4 TextureMat;
            };
            layout(std140) uniform Projection {
                mat4 ProjMat;
            };

            in vec3 Position;
            in vec4 Color;

            out vec4 vertexColor;

            void main() {
                gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);

                vertexColor = Color;
            }
            """.trimIndent()

        private val CORE_GUI_FSH = """
            #version 330

            // Can't moj_import in things used during startup, when resource packs don't exist.
            // This is a copy of dynamicimports.glsl
            layout(std140) uniform DynamicTransforms {
                mat4 ModelViewMat;
                vec4 ColorModulator;
                vec3 ModelOffset;
                mat4 TextureMat;
            };

            in vec4 vertexColor;

            out vec4 fragColor;

            void main() {
                vec4 color = vertexColor;
                if (color.a == 0.0) {
                    discard;
                }
                fragColor = color * ColorModulator;
            }
            """.trimIndent()

        private val CORE_POSITION_TEX_COLOR_VSH = """
            #version 330

            // Can't moj_import in things used during startup, when resource packs don't exist.
            // This is a copy of dynamicimports.glsl and projection.glsl
            layout(std140) uniform DynamicTransforms {
                mat4 ModelViewMat;
                vec4 ColorModulator;
                vec3 ModelOffset;
                mat4 TextureMat;
            };
            layout(std140) uniform Projection {
                mat4 ProjMat;
            };

            in vec3 Position;
            in vec2 UV0;
            in vec4 Color;

            out vec2 texCoord0;
            out vec4 vertexColor;

            void main() {
                gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);

                texCoord0 = UV0;
                vertexColor = Color;
            }
            """.trimIndent()

        private val CORE_POSITION_TEX_COLOR_FSH = """
            #version 330

            // Can't moj_import in things used during startup, when resource packs don't exist.
            // This is a copy of dynamicimports.glsl
            layout(std140) uniform DynamicTransforms {
                mat4 ModelViewMat;
                vec4 ColorModulator;
                vec3 ModelOffset;
                mat4 TextureMat;
            };

            uniform sampler2D Sampler0;

            in vec2 texCoord0;
            in vec4 vertexColor;

            out vec4 fragColor;

            void main() {
                vec4 color = texture(Sampler0, texCoord0) * vertexColor;
                if (color.a == 0.0) {
                    discard;
                }
                fragColor = color * ColorModulator;
            }
            """.trimIndent()

        /** 自检 shader 源：createDevice 时资源包未加载，用内嵌 GLSL 顶替真实 ShaderSource。 */
        private val EMBEDDED_SHADER_SOURCE: ShaderSource = object : ShaderSource {
            override fun getShader(id: Identifier, type: ShaderType): String? {
                if (!id.path.startsWith("core/")) {
                    return null
                }
                return when (id.path) {
                    "core/gui" -> if (type == ShaderType.VERTEX) CORE_GUI_VSH else CORE_GUI_FSH
                    "core/position_tex_color" -> if (type == ShaderType.VERTEX) {
                        CORE_POSITION_TEX_COLOR_VSH
                    } else {
                        CORE_POSITION_TEX_COLOR_FSH
                    }
                    else -> null
                }
            }

            override fun getInclude(id: Identifier): ShaderSource.CachedIncludeSource? = null

            override fun close() {}
        }

        /** 用两条官方真实管线（GUI / GUI_TEXTURED）验证编译全链路；handle == 0 即判失败。 */
        private fun selfTestPipelines(device: D3D12Device) {
            val names = arrayOf("pipeline/gui", "pipeline/gui_textured")
            for (name in names) {
                val pipeline: D3D12CompiledRenderPipeline = when (name) {
                    "pipeline/gui" -> device.getOrCompilePipeline(RenderPipelines.GUI, false)
                    else -> device.getOrCompilePipeline(RenderPipelines.GUI_TEXTURED, false)
                }
                if (!pipeline.isValid()) {
                    throw IllegalStateException("native pipeline compile failed for $name")
                }
            }
            LOGGER.info("Pipeline self-test OK (GLSL->SPIR-V->HLSL->DXBC->PSO for core/gui + core/position_tex_color)")
            // 自检结果不能留在缓存里：否则 vanilla 用真实源编译时命中缓存，真实源永远不生效。
            device.clearPipelineCache()
        }

        /**
         * 诊断（`[debug] meshDiagnostics` = clear|pass|pass2）：把「写纹理 → 回读」最小化隔离，
         * 用来区分「回读通路坏了」和「render pass 没落到纹理上」。只在取证时使用。
         */
        private fun selfTestMeshDiag(mode: String) {
            val size = 64
            val texture = MCDR_ABI.ID3D12CreateTexture(
                GpuTexture.USAGE_RENDER_ATTACHMENT or GpuTexture.USAGE_COPY_SRC,
                GpuFormat.RGBA8_UNORM.ordinal, size, size, 1, 1)
            if (texture == 0L) {
                throw IllegalStateException("mesh diag: dx12CreateTexture failed: ${MCDR_ABI.D3D12LastError()}")
            }
            val ctx = MCDR_ABI.D3D12CreateCommandEncoder()
            if (ctx == 0L) {
                throw IllegalStateException("mesh diag: dx12CreateCommandEncoder failed: ${MCDR_ABI.D3D12LastError()}")
            }
            try {
                MCDR_ABI.D3D12BeginCommandList(ctx)
                when (mode) {
                    "clear" -> {
                        MCDR_ABI.D3D12ClearColorTexture(ctx, texture, 1.0f, 0.0f, 0.0f, 1.0f)
                        LOGGER.info("mesh diag(clear): clearColorTexture lastError=[{}]",
                            MCDR_ABI.D3D12LastError())
                    }
                    "pass" -> {
                        MCDR_ABI.D3D12BeginRenderPass(ctx, longArrayOf(texture), intArrayOf(0),
                            byteArrayOf(1), floatArrayOf(0.0f, 0.0f, 0.0f, 1.0f),
                            0L, 0, 0.toByte(), 0.0, 0, 0, size, size)
                        LOGGER.info("mesh diag(pass): beginRenderPass lastError=[{}]",
                            MCDR_ABI.D3D12LastError())
                        MCDR_ABI.D3D12EndRenderPass(ctx)
                    }
                    // pass2：先用 API 清红，再用一次 render pass 清黑。读到红 ⇒ pass 的写入没落在这张纹理上。
                    "pass2" -> {
                        MCDR_ABI.D3D12ClearColorTexture(ctx, texture, 1.0f, 0.0f, 0.0f, 1.0f)
                        MCDR_ABI.D3D12BeginRenderPass(ctx, longArrayOf(texture), intArrayOf(0),
                            byteArrayOf(1), floatArrayOf(0.0f, 0.0f, 0.0f, 1.0f),
                            0L, 0, 0.toByte(), 0.0, 0, 0, size, size)
                        LOGGER.info("mesh diag(pass2): beginRenderPass lastError=[{}]",
                            MCDR_ABI.D3D12LastError())
                        MCDR_ABI.D3D12EndRenderPass(ctx)
                    }
                    else -> throw IllegalArgumentException("mesh diag: unknown mode '$mode'")
                }
                val fence = MCDR_ABI.D3D12Submit(ctx)
                if (!MCDR_ABI.D3D12WaitForFence(ctx, fence, 5_000_000_000L)) {
                    throw IllegalStateException("mesh diag: fence wait timed out (5s)")
                }
                val pixels = MCDR_ABI.D3D12ReadbackTexturePixels(texture)
                LOGGER.info("mesh diag({}): fence={} readback={}", mode, fence,
                    if (pixels == null) "null" else (0 until 9).joinToString(" ") { i ->
                        "(${pixels[i * 4]},${pixels[i * 4 + 1]},${pixels[i * 4 + 2]},${pixels[i * 4 + 3]})"
                    })
                throw IllegalStateException("mesh diag($mode): diagnostic run finished — see the log line above")
            } finally {
                MCDR_ABI.D3D12DestroyCommandEncoder(ctx)
                MCDR_ABI.ID3D12DestroyResource(texture)
            }
        }

        /**
         * compute 最小通路自检：`cs_6_8` → compute PSO → Dispatch(2,2,1) → 回读，断言根 UAV 与根常量生效。
         *
         * 目标纹理必须带 `USAGE_STORAGE`(32)：native 只在这类纹身上置 `ALLOW_UNORDERED_ACCESS`。
         */
        private fun selfTestCompute() {
            val size = 16
            val tag = 200
            val usage = D3D12GpuTexture.USAGE_STORAGE or GpuTexture.USAGE_COPY_SRC
            val texture = MCDR_ABI.ID3D12CreateTexture(usage, GpuFormat.RGBA8_UNORM.ordinal, size, size, 1, 1)
            if (texture == 0L) {
                throw IllegalStateException("compute self-test: dx12CreateTexture failed: ${MCDR_ABI.D3D12LastError()}")
            }
            try {
                val hlsl = """
                    RWTexture2D<float4> gOutput : register(u0);
                    cbuffer Params : register(b0) { uint gTag; uint gPad0; uint gPad1; uint gPad2; };

                    [numthreads(8, 8, 1)]
                    void main(uint3 id : SV_DispatchThreadID) {
                        gOutput[id.xy] = float4(gTag / 255.0, 0.0, 0.0, 1.0);
                    }
                """.trimIndent()
                val dxil: ByteArray = MCDR_ABI.D3D12CompileShaderDxc(hlsl, "main", "cs_6_8")
                    ?: throw IllegalStateException(
                        "compute self-test: DXC compile failed for cs_6_8: ${MCDR_ABI.D3D12LastError()}")
                val pipeline = MCDR_ABI.D3D12CreateComputePipeline(dxil)
                if (pipeline == 0L) {
                    throw IllegalStateException(
                        "compute self-test: dx12CreateComputePipeline failed: ${MCDR_ABI.D3D12LastError()}")
                }
                try {
                    val ctx = MCDR_ABI.D3D12CreateCommandEncoder()
                    if (ctx == 0L) {
                        throw IllegalStateException(
                            "compute self-test: dx12CreateCommandEncoder failed: ${MCDR_ABI.D3D12LastError()}")
                    }
                    try {
                        MCDR_ABI.D3D12BeginCommandList(ctx)
                        if (!MCDR_ABI.D3D12DispatchCompute(ctx, pipeline, texture, intArrayOf(tag, 0, 0, 0), 2, 2, 1)) {
                            throw IllegalStateException(
                                "compute self-test: dx12DispatchCompute failed: ${MCDR_ABI.D3D12LastError()}")
                        }
                        val fence = MCDR_ABI.D3D12Submit(ctx)
                        if (!MCDR_ABI.D3D12WaitForFence(ctx, fence, 5_000_000_000L)) {
                            throw IllegalStateException("compute self-test: fence wait timed out (5s)")
                        }
                        val pixels = MCDR_ABI.D3D12ReadbackTexturePixels(texture)
                            ?: throw IllegalStateException(
                                "compute self-test: read-back returned nothing: ${MCDR_ABI.D3D12LastError()}")
                        val seen = (0 until 9).map { i ->
                            intArrayOf(pixels[i * 4], pixels[i * 4 + 1], pixels[i * 4 + 2], pixels[i * 4 + 3])
                        }
                        LOGGER.info("Compute self-test: dispatch ok, read-back={}", seen.joinToString(" ") { px ->
                            "(${px[0]},${px[1]},${px[2]},${px[3]})"
                        })
                        val bad = seen.firstOrNull { it[0] != tag || it[1] != 0 || it[2] != 0 || it[3] != 255 }
                        if (bad != null) {
                            throw IllegalStateException(
                                "compute self-test: unexpected pixel (${bad[0]},${bad[1]},${bad[2]},${bad[3]}), " +
                                    "expected ($tag,0,0,255)")
                        }
                        LOGGER.info("Compute self-test PASSED (cs_6_8 + descriptor-table UAV u0 + root constants b0)")
                    } finally {
                        MCDR_ABI.D3D12DestroyCommandEncoder(ctx)
                    }
                } finally {
                    MCDR_ABI.ID3D12DestroyResource(pipeline)
                }
            } finally {
                MCDR_ABI.ID3D12DestroyResource(texture)
            }
        }

        /** 着色率图像（R8_UINT SRI）自检：建图 → 两张 SRV → dispatch → 绑 SRI → 回读首像素。 */
        private fun selfTestShadingRate() {
            val tiles = 2
            val image = MCDR_ABI.D3D12CreateShadingRateImage(tiles, tiles)
            if (image == 0L) {
                throw IllegalStateException(
                    "SRI self-test: dx12CreateShadingRateImage failed: ${MCDR_ABI.D3D12LastError()}")
            }
            val usage = GpuTexture.USAGE_TEXTURE_BINDING or GpuTexture.USAGE_COPY_SRC
            val inputA = MCDR_ABI.ID3D12CreateTexture(usage, GpuFormat.RGBA8_UNORM.ordinal, 32, 32, 1, 1)
            val inputB = MCDR_ABI.ID3D12CreateTexture(usage, GpuFormat.RGBA8_UNORM.ordinal, 32, 32, 1, 1)
            try {
                if (inputA == 0L || inputB == 0L) {
                    throw IllegalStateException(
                        "SRI self-test: input texture creation failed: ${MCDR_ABI.D3D12LastError()}")
                }
                val viewA = MCDR_ABI.ID3D12CreateTextureView(inputA, 0, 1)
                val viewB = MCDR_ABI.ID3D12CreateTextureView(inputB, 0, 1)
                try {
                    val hlsl = """
                        Texture2D<float4> gDepth : register(t0);
                        Texture2D<float4> gMotion : register(t1);
                        RWTexture2D<uint> gOut : register(u0);
                        cbuffer Params : register(b0) { uint gTilesX; uint gTilesY; uint gTile; uint gPad; };

                        [numthreads(8, 8, 1)]
                        void main(uint3 id : SV_DispatchThreadID) {
                            if (id.x >= gTilesX || id.y >= gTilesY) return;
                            // 读一下两张 SRV，保证它们不会被 DXC 判成未使用（否则根签名里的
                            // 描述符表就成了摆设，自检也就证明不了绑定通路）。
                            float probe = gDepth.Load(int3(0, 0, 0)).x + gMotion.Load(int3(0, 0, 0)).x;
                            uint rate = (id.x == 0u && id.y == 0u) ? 5u : 0u;
                            if (probe > 1.0e30) rate = 1u;
                            gOut[id.xy] = rate;
                        }
                    """.trimIndent()
                    val dxil = MCDR_ABI.D3D12CompileShaderDxc(hlsl, "main", "cs_6_8")
                        ?: throw IllegalStateException(
                            "SRI self-test: DXC compile failed for cs_6_8: ${MCDR_ABI.D3D12LastError()}")
                    val pipeline = MCDR_ABI.D3D12CreateComputePipelineShadingRate(dxil)
                    if (pipeline == 0L) {
                        throw IllegalStateException(
                            "SRI self-test: dx12CreateComputePipelineShadingRate failed: " +
                                MCDR_ABI.D3D12LastError())
                    }
                    try {
                        val ctx = MCDR_ABI.D3D12CreateCommandEncoder()
                        if (ctx == 0L) {
                            throw IllegalStateException(
                                "SRI self-test: dx12CreateCommandEncoder failed: ${MCDR_ABI.D3D12LastError()}")
                        }
                        try {
                            MCDR_ABI.D3D12BeginCommandList(ctx)
                            // flags 的 bit1（时间钳制）留 0，读回的就是这一趟 compute 写的值。
                            val dispatched = MCDR_ABI.D3D12DispatchComputeShadingRate(
                                ctx, pipeline, viewA, viewB, viewA, image,
                                intArrayOf(tiles, tiles, 16, 0), 1, 1, 1)
                            if (!dispatched) {
                                throw IllegalStateException(
                                    "SRI self-test: dx12DispatchComputeShadingRate failed: " +
                                        MCDR_ABI.D3D12LastError())
                            }
                            if (!MCDR_ABI.D3D12SetShadingRateImage(ctx, image)) {
                                throw IllegalStateException(
                                    "SRI self-test: dx12SetShadingRateImage(bind) failed: " +
                                        MCDR_ABI.D3D12LastError())
                            }
                            MCDR_ABI.D3D12SetShadingRateImage(ctx, 0L)
                            val fence = MCDR_ABI.D3D12Submit(ctx)
                            if (!MCDR_ABI.D3D12WaitForFence(ctx, fence, 5_000_000_000L)) {
                                throw IllegalStateException("SRI self-test: fence wait timed out (5s)")
                            }
                            val px = MCDR_ABI.D3D12ReadbackTexturePixels(image)
                                ?: throw IllegalStateException(
                                    "SRI self-test: read-back returned nothing: ${MCDR_ABI.D3D12LastError()}")
                            LOGGER.info(
                                "SRI self-test: {}x{} tiles, first pixel={} raw={}",
                                tiles, tiles, px[0], px.joinToString(","))
                            if (px[0] != 5) {
                                throw IllegalStateException(
                                    "SRI self-test: tile(0,0) expected 5 (2X2), got ${px[0]}")
                            }
                            LOGGER.info(
                                "SRI self-test PASSED (R8_UINT image + 2 SRV + UAV + RSSetShadingRateImage)")
                        } finally {
                            MCDR_ABI.D3D12DestroyCommandEncoder(ctx)
                        }
                    } finally {
                        MCDR_ABI.ID3D12DestroyResource(pipeline)
                    }
                } finally {
                    MCDR_ABI.ID3D12DestroyResource(viewA)
                    MCDR_ABI.ID3D12DestroyResource(viewB)
                }
            } finally {
                MCDR_ABI.ID3D12DestroyResource(inputA)
                MCDR_ABI.ID3D12DestroyResource(inputB)
                MCDR_ABI.ID3D12DestroyResource(image)
            }
        }

        private fun selfTestMesh(device: D3D12Device) {
            val size = 64
            LOGGER.info("Mesh self-test starting (device={})", device)

            // PS 的输入语义必须与 `out vertices` 结构逐字段、逐顺序一致，否则 PSO 创建失败。
            val meshHlsl = """
                struct MeshVertex { float4 position : SV_Position; float4 color : COLOR0; };

                [numthreads(1, 1, 1)]
                [outputtopology("triangle")]
                void main(out vertices MeshVertex verts[3], out indices uint3 tris[1]) {
                    SetMeshOutputCounts(3, 1);
                    verts[0].position = float4(-1.0, -1.0, 0.0, 1.0);
                    verts[1].position = float4( 3.0, -1.0, 0.0, 1.0);
                    verts[2].position = float4(-1.0,  3.0, 0.0, 1.0);
                    verts[0].color = float4(1.0, 0.0, 0.0, 1.0);
                    verts[1].color = float4(1.0, 0.0, 0.0, 1.0);
                    verts[2].color = float4(1.0, 0.0, 0.0, 1.0);
                    tris[0] = uint3(0, 1, 2);
                }
            """.trimIndent()
            val pixelHlsl = """
                struct PsIn { float4 position : SV_Position; float4 color : COLOR0; };
                float4 main(PsIn input) : SV_Target { return input.color; }
            """.trimIndent()

            // ms_6_6 是 mesh 的最低可用档（ms_6_5 备选，ms_6_2 不存在）；图形管线那边硬要求 6_9。
            val meshDxil: ByteArray = MCDR_ABI.D3D12CompileShaderDxc(meshHlsl, "main", "ms_6_6")
                ?: throw IllegalStateException(
                    "mesh self-test: DXC compile failed for ms_6_6: ${MCDR_ABI.D3D12LastError()}")
            val pixelDxil: ByteArray = MCDR_ABI.D3D12CompileShaderDxc(pixelHlsl, "main", "ps_6_0")
                ?: throw IllegalStateException(
                    "mesh self-test: DXC compile failed for ps_6_0: ${MCDR_ABI.D3D12LastError()}")

            // 回读走 CopyTextureRegion：缺 COPY_SRC 会失败；当 RTV 又要求 RENDER_ATTACHMENT。
            val colorTarget = MCDRPipelineDesc.ColorTarget()
                .format(GpuFormat.RGBA8_UNORM.ordinal)
                .writeMask(0xF)
            val desc = MCDRMeshPipelineDesc()
                .shaders(meshDxil, null, pixelDxil)  // 不启用 amplification shader
                .topology(false, 0)
                .addColorTarget(colorTarget)

            // 自检同时覆盖外部根签名路径（b8 段表 + t14/t15 顶点/索引表），PSO 用这份签名建。
            val meshRootSig = MCDR_ABI.D3D12CreateMeshRootSignature(null, 0, 1)
            if (meshRootSig == 0L) {
                throw IllegalStateException("mesh self-test: dx12CreateMeshRootSignature returned 0: "
                    + MCDR_ABI.D3D12LastError())
            }
            var pipeline = 0L
            var texture = 0L
            var ctx = 0L
            try {
                pipeline = MCDR_ABI.D3D12CreateMeshPipelineWithRootSignature(desc, meshRootSig)
                if (pipeline == 0L) {
                    throw IllegalStateException("mesh self-test: dx12CreateMeshPipeline returned 0: "
                        + MCDR_ABI.D3D12LastError())
                }
                texture = MCDR_ABI.ID3D12CreateTexture(
                    GpuTexture.USAGE_RENDER_ATTACHMENT or GpuTexture.USAGE_COPY_SRC,
                    GpuFormat.RGBA8_UNORM.ordinal, size, size, 1, 1)
                if (texture == 0L) {
                    throw IllegalStateException("mesh self-test: dx12CreateTexture returned 0: "
                        + MCDR_ABI.D3D12LastError())
                }
                // 别在同一个命令列表里连续做两个 render pass + 两次回读：实测回读会互相错配。
                // 自检要求确定性，所以只用单纹理单 pass。
                ctx = MCDR_ABI.D3D12CreateCommandEncoder()
                if (ctx == 0L) {
                    throw IllegalStateException("mesh self-test: dx12CreateCommandEncoder returned 0: "
                        + MCDR_ABI.D3D12LastError())
                }

                MCDR_ABI.D3D12BeginCommandList(ctx)
                // 顺序敏感：render pass 之前必须先有一次普通 API 写入，否则 pass 的写入对回读不可见。
                // 这里先 API 清蓝，再用 LOAD（clearFlag=0）的 pass 只让 mesh 写红：
                // 读到蓝 = mesh 没画，读到红 = DispatchMesh 真的光栅化进了这张纹理。
                MCDR_ABI.D3D12ClearColorTexture(ctx, texture, 0.0f, 0.0f, 1.0f, 1.0f)
                LOGGER.info("mesh self-test: baseline clear(blue) lastError=[{}]", MCDR_ABI.D3D12LastError())
                MCDR_ABI.D3D12BeginRenderPass(ctx, longArrayOf(texture), intArrayOf(0),
                    byteArrayOf(0), floatArrayOf(0.0f, 0.0f, 0.0f, 1.0f),
                    0L, 0, 0.toByte(), 0.0, 0, 0, size, size)
                // BeginRenderPass 返回 void，失败只落在 lastError，且会被后续成功调用清掉：必须紧跟取。
                LOGGER.info("mesh self-test: beginRenderPass lastError=[{}]",
                    MCDR_ABI.D3D12LastError())
                if (!MCDR_ABI.D3D12SetViewport(ctx, 0, 0, size, size)
                    || !MCDR_ABI.D3D12SetScissor(ctx, 0, 0, size, size)) {
                    throw IllegalStateException("mesh self-test: set viewport/scissor failed")
                }
                if (!MCDR_ABI.D3D12SetPipeline(ctx, pipeline, false)) {
                    throw IllegalStateException("mesh self-test: dx12SetPipeline failed: "
                        + MCDR_ABI.D3D12LastError())
                }
                // dispatchMesh 要求本帧推过描述符：无绑定时显式推一次空绑定（count==0 直接返回 true）。
                if (!MCDR_ABI.D3D12PushDescriptors(ctx, null, null, null, null, null, null)) {
                    throw IllegalStateException("mesh self-test: dx12PushDescriptors failed: "
                        + MCDR_ABI.D3D12LastError())
                }
                // 组数 = ceil(64 / numthreads(1,1,1)) = 64 → 8x8x1 覆盖整张 64x64。
                if (!MCDR_ABI.D3D12DispatchMesh(ctx, 8, 8, 1)) {
                    throw IllegalStateException("mesh self-test: dx12DispatchMesh failed: "
                        + MCDR_ABI.D3D12LastError())
                }
                MCDR_ABI.D3D12EndRenderPass(ctx)
                // 不要显式 End：submitCommandList 自己 Close；listOpen==false 会被当空帧早退，
                // 于是 GPU 什么都没执行、fence 立即满足 → 回读全 0 且没有任何报错。
                val fence = MCDR_ABI.D3D12Submit(ctx)
                LOGGER.info("mesh self-test: submitted, fence={}", fence)
                if (!MCDR_ABI.D3D12WaitForFence(ctx, fence, 5_000_000_000L)) {
                    throw IllegalStateException("mesh self-test: fence wait timed out (5s)")
                }

                // 3x3 = 9 个采样点，每点 4 个分量（RGBA）。只要有一点明显是红就算通过。
                fun dump(px: IntArray): String = (0 until 9).joinToString(" ") { i ->
                    "(${px[i * 4]},${px[i * 4 + 1]},${px[i * 4 + 2]},${px[i * 4 + 3]})"
                }
                fun hasRed(px: IntArray): Boolean = (0 until 9).any { i ->
                    px[i * 4] > 200 && px[i * 4 + 1] < 60 && px[i * 4 + 2] < 60
                }
                var pixels = MCDR_ABI.D3D12ReadbackTexturePixels(texture)
                    ?: throw IllegalStateException(
                        "mesh self-test: dx12ReadbackTexturePixels returned null: "
                            + MCDR_ABI.D3D12LastError())
                LOGGER.info("mesh self-test: readback = [{}]", dump(pixels))
                if (!hasRed(pixels)) {
                    // 提交后立刻回读存在可见性竞态（整条列表的内容都读不到，alpha=0）：隔一下重读再判。
                    Thread.sleep(150)
                    val retry = MCDR_ABI.D3D12ReadbackTexturePixels(texture)
                    LOGGER.warn("mesh self-test: first readback = [{}]; retry after 150ms = [{}]",
                        dump(pixels), if (retry == null) "null" else dump(retry))
                    if (retry != null) pixels = retry
                }
                val red = (0 until 9).any { i ->
                    pixels[i * 4] > 200 && pixels[i * 4 + 1] < 60 && pixels[i * 4 + 2] < 60
                }
                if (!red) {
                    val dump = (0 until 9).joinToString(" ") { i ->
                        "(${pixels[i * 4]},${pixels[i * 4 + 1]},${pixels[i * 4 + 2]},${pixels[i * 4 + 3]})"
                    }
                    throw IllegalStateException("mesh self-test: no red pixel in 3x3 readback — pixels=["
                        + "${dump}] lastError=${MCDR_ABI.D3D12LastError()}")
                }
                LOGGER.info("Mesh self-test OK (DXC ms_6_6 + PSO + DispatchMesh -> red pixel readback)")
            } finally {
                // 逆序释放；句柄为 0 表示没建起来，跳过。
                if (ctx != 0L) MCDR_ABI.D3D12DestroyCommandEncoder(ctx)
                if (texture != 0L) MCDR_ABI.ID3D12DestroyResource(texture)
                if (pipeline != 0L) MCDR_ABI.D3D12DestroyPipeline(pipeline)
                MCDR_ABI.D3D12ReleaseRootSignature(meshRootSig)
            }
        }

        /**
         * 交换链自检：建 swapchain（绑真实 HWND）→ 清屏 → submit → present 一次。
         *
         * 必须用 [D3D12GpuSurface] 而不是 GpuSurface 包装类：清屏走非 blit 路径（null srcTex =
         * ClearRenderTargetView），绕过了 hasBlittedTexture 标志，[GpuSurface.present] 会抛异常。
         */
        @Throws(SurfaceException::class)
        private fun selfTestSurface(device: D3D12Device, window: Long) {
            val encoderBackend: D3D12CommandEncoderBackend = D3D12CommandEncoderBackend()
            var D3DSurface: D3D12GpuSurface? = null
            try {
                D3DSurface = device.createSurface(window, BooleanSupplier { false }) as D3D12GpuSurface
                val mode: GpuSurface.PresentMode = GpuSurface.PresentMode.getSupportedVsyncMode(
                    D3DSurface.supportedPresentModes(), false)
                D3DSurface.configure(GpuSurface.Configuration(640, 480, mode))
                D3DSurface.acquireNextTexture()

                // null texture = 不做 copy，只做 ClearRenderTargetView
                D3DSurface.clearToRed(encoderBackend)

                val fence: GpuFence = encoderBackend.createFence()
                encoderBackend.submit()
                // 必须等 GPU 完成 clear 后再销毁 swapchain，否则 backbuffer 在被使用时释放。
                if (!fence.awaitCompletion(5000L)) {
                    throw IllegalStateException("surface self-test submit timed out")
                }
                fence.close()
                D3DSurface.present()
                System.err.println(
                    "selfTestSurface: presented GREEN to the real window; seeing green means the render pipeline works")
            } finally {
                D3DSurface?.close()
                encoderBackend.close()
            }
            LOGGER.info("Surface self-test OK (pure green clear + present via FFM on real window)")
        }
    }
}
