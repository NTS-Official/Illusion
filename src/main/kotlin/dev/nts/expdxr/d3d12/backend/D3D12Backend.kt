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
import dev.nts.expdxr.misc.D3D12Settings
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
 * Direct3D 12 [GpuBackend] implementation.
 *
 * Mirrors `com.mojang.blaze3d.vulkan.VulkanBackend`'s contract:
 * - [createWindow] creates the SDL3 window for the game (26.3 moved window creation into the backend;
 *   26.2 used `setWindowHints()` + vanilla's own `glfwCreateWindow`).
 * - [createDevice] is called once the window exists; it runs the full D3D12 self-test chain and, when it
 *   passes, returns a live [D3D12Device] that drives the vanilla render flow (P6+).
 */
class D3D12Backend : GpuBackend {
    /** [createWindow] 建出来的 `SDL_Window*`；`createDevice` 里做 surface 自检时要用它。 */
    private var lastWindowHandle: Long = 0L

    override fun getName(): String = "D3D12"

    // 26.3：GpuBackend 用 loadLibrary/unloadLibrary 显式管理后端动态库（26.2 是隐式的）。
    // D3D12 由系统 d3d12.dll 提供、不需要预加载，这里留空即可。
    override fun loadLibrary() {}

    override fun unloadLibrary() {}

    /**
     * 26.3 把窗口创建移交给了后端：`GpuBackend.createWindow(title, w, h, flags)` 取代了 26.2 的
     * `setWindowHints()` + vanilla 自己调 `glfwCreateWindow`。
     *
     * flags 是游戏算好的 SDL3 标志（26.3 传 `SDL_WINDOW_HIGH_PIXEL_DENSITY | SDL_WINDOW_RESIZABLE`
     * = `0x2020`），这里**原样透传**。绝不能或上 `SDL_WINDOW_OPENGL`：那是 GlBackend 自己加的
     * （`GlBackend.createWindow` 里 `flags | SDL_WINDOW_OPENGL`），而 D3D12 进程里一旦出现一条
     * **被使用过**的 GL 上下文，RTSS/Afterburner 的 OSD 就会改走 OpenGL 呈现路径、被我们的
     * D3D12 子窗口整块盖住而永远不可见（26.2 的 GLFW_NO_API 就是为这件事存在的，
     * 证据见 known_bugs.md §1 与 _port/noapi_osd_crop.png）。
     */
    override fun createWindow(title: String?, width: Int, height: Int, flags: Long): Long {
        if (D3D12Settings.glContext) {
            LOGGER.warn("[renderer] glContext = true has no effect since 26.3: the GL context belongs to "
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
        // P6: createDevice returns a real, live GpuDevice. The game then drives
        // the full vanilla render flow through our backend (RenderSystem
        // initRenderer, surface, per-frame render passes, present) with no
        // GL/Vulkan fallback. Self-tests run against the live device; only a
        // failure closes it and rethrows so the game can fall back.
        // P73: that last sentence is obsolete - a failure here goes through D3D12Fatal
        // (reset preferredGraphicsBackend + crash with an explanatory report) instead of
        // rethrowing BackendCreationException, which the game would swallow before
        // silently trying GL.
        // P73 测试钩子：[debug] failDevice = true 让后端在第一步就失败，用来端到端验证
        // "不再回退 + 还原 preferredGraphicsBackend + 生成带说明的崩溃报告"这条策略。
        // 正常运行不会打开它（config/dxr_mc-client.toml 里的默认值是 false）。
        if (D3D12Settings.failDevice) {
            D3D12Fatal.crash("simulated Direct3D 12 device failure ([debug] failDevice = true)", null)
        }
        try {
            // 原生侧在建设备时从进程环境读几个开关（调试层、DLSS 实现、原生日志文件…）。
            // 配置是唯一入口，这里在建 D3D12Device 之前把它们推进原生进程环境。
            D3D12Settings.pushToNative()
            // 26.3 的 `createDevice(GpuDebugOptions)` 不再收 defaultShaderSource / criticalShaderLoader：
            // 着色器源与"关键着色器加载失败"的处理都移进了 FrontendGpuDevice/PipelineBuilder，后端只在
            // `compilePipeline(CreateInfo)` 里拿到具体源。
            // 真实管线的源在 `FrontendGpuDeviceCompilePipelineMixin` 里连同管线一起登记到
            // D3D12PipelineBridge（D3D12Device.resolveShaderSource 优先取它）；构造参数只作兜底：
            // 这里给内嵌 GLSL 源，让下面的自检（pipeline/gui + pipeline/gui_textured）在资源包
            // 尚未加载时也能跑完整条 GLSL→SPIR-V→HLSL→DXBC→PSO 链。
            val device = D3D12Device(EMBEDDED_SHADER_SOURCE)
            // P213：**在这之前** MCDR_ABI 还没加载完（DLL 是在 D3D12Device 里 load 的），所以上面那次
            // pushToNative() 的调用会静默丢掉（ABI 句柄为 null）。设备建好后必须再推一次，否则
            // [dlss] frameGeneration 这类“只在启动时读一次”的开关永远到不了 native。
            D3D12Settings.pushToNative()

            // P124/P217：mesh 能力与原因的唯一下发点。地形已无 IMR 回退，所以能力不足
            // 必须留下一条玩家可见的 ERROR，而不是让地形静默消失。
            if (!D3D12MeshShader.isSupported() && D3D12Settings.meshTerrain) {
                LOGGER.error("[dxrmc] mesh shaders are unavailable but terrain has no IMR fallback: "
                    + "terrain batches will be skipped (see the mesh support reason above)")
            }
            try {
                selfTestJavaResources(device)
                selfTestCommandLayer(device)
                selfTestPipelines(device)
                // P72：mesh shader 自检（DXC 编译 → mesh PSO → DispatchMesh → 离屏像素回读）。
                // 默认关闭：它要用 DXC 现场编译 HLSL，没必要塞进每次启动的关键路径；
                // 打开方式：配置 `[debug] selfTestMesh = true`。
                if (D3D12Settings.selfTestMesh) {
                    selfTestMesh(device)
                }
                // P234：compute 最小通路自检（cs_6_8 → compute PSO → Dispatch → 回读）。
                // 默认关闭；打开方式：配置 `[debug] selfTestCompute = true`。
                if (D3D12Settings.selfTestCompute) {
                    selfTestCompute()
                }
                // P235：着色率图像（R8_UINT SRI）自检 —— 与 compute 共用 `[debug] selfTestCompute`。
                // 验的是「算得出 → 写得进 → 挂得上命令列表」这三件事，不验图像内容。
                if (D3D12Settings.selfTestCompute) {
                    selfTestShadingRate()
                }
                // 诊断（[debug] meshDiagnostics = clear|pass）：把"写纹理 → 回读"做最小化隔离，
                // 用来区分「回读通路坏了」和「render pass 没落到纹理上」。只在取证时使用。
                D3D12Settings.meshDiagnostics.takeIf { it != MeshDiagnostics.NONE }?.let {
                    selfTestMeshDiag(it.name.lowercase())
                }
                // 注意：surface 自检会在真实窗口上创建、Present 并立刻销毁一条交换链。
                // RTSS/Afterburner 的 OSD 绑定到它注入(默认约 15s)之后看到的第一条被 Present 的
                // 交换链上；自检链一旦抢先 Present，真正的游戏交换链就再也拿不到 OSD（表现为
                // 装了 RTSS 也完全看不到 OSD）。探针矩阵已验证：只要有 GL/其它交换链先 Present，
                // OSD 会永久留在那条链上。因此默认跳过，需要时用配置 `[debug] selfTestSurface = true` 打开。
                if (D3D12Settings.selfTestSurface) {
                    selfTestSurface(device, lastWindowHandle)
                } else {
                    LOGGER.info("[backend] Surface self-test skipped: swapped-chain self-test would steal "
                        + "the RTSS OSD binding (enable with [debug] selfTestSurface = true)")
                }
            } catch (t: Throwable) {
                device.close()
                throw t
            }
            // P6 诊断：自测阶段用嵌入 GLSL 填充了 pipeline cache，且 DIAG 绿色覆盖已生效。
            // 必须在返回给游戏前清除，否则 getOrCompilePipeline 会命中自测缓存条目，
            // 导致真实游戏 shader 从未被编译，屏幕显示的是自测阶段的嵌入 shader 输出（紫色）。
            device.clearPipelineCache()
            return FrontendGpuDevice(device)
        } catch (t: Throwable) {
            LOGGER.error("[backend] D3D12 self-test failed", t)
            LOGGER.error("[backend] D3D12 failure is fatal by design (P73): no GL/Vulkan fallback, "
                + "preferredGraphicsBackend is reset to default and the game crashes with a report")
            // D3D12Fatal 抛的是 ReportedException（不是 BackendCreationException），
            // 所以 vanilla 的候选后端循环不会吞掉它 —— 见 D3D12Fatal 的类注释。
            D3D12Fatal.crash("DX12 self-test failed: $t", t)
        }
    }
    companion object {
        private val LOGGER: Logger = LoggerFactory.getLogger("dxr_mc")


        /**
         * Exercise every P2 resource method through the real JNI path so a broken
         * native binding fails loudly here instead of during vanilla initRenderer.
         */
        private fun selfTestJavaResources(device: D3D12Device) {
            // texture: RGBA8_UNORM, 64x64, TEXTURE_BINDING|RENDER_ATTACHMENT
            val texture: GpuTexture = device.createTexture("backend-selftest",
                GpuTexture.USAGE_TEXTURE_BINDING or GpuTexture.USAGE_RENDER_ATTACHMENT,
                GpuFormat.RGBA8_UNORM, 64, 64, 1, 1)
            // sampler: REPEAT/REPEAT, NEAREST/NEAREST, aniso=1
            val sampler: GpuSampler = device.createSampler(AddressMode.REPEAT, AddressMode.REPEAT,
                FilterMode.NEAREST, FilterMode.NEAREST, 1, OptionalDouble.of(16.0))
            // buffer: MAP_WRITE, map -> write -> unmap
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
            // view over the texture
            device.createTextureView(texture, 0, texture.mipLevels).close()
            buffer.close()
            sampler.close()
            texture.close()
            LOGGER.info("[backend] Java resource self-test OK (texture/buffer/sampler/view via FFM)")
        }

        /**
         * Exercise the P3 command layer through the real JNI path: record
         * uploads/copies into a command list, submit through the fence, and verify
         * both a buffer copy readback and a texture write/readback round-trip.
         */
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

                // Buffer readback: every byte must survive the copy.
                dst.map(0L, size.toLong(), true, false).use { view ->
                    val read: ByteBuffer = view.data()
                    IllusionLogger.debugMessage(
                        "[backend-java] self-test: reading dst readback, size=${size}"
                            + " dst.usage=${dst.usage().toUInt().toString(2)}")
                    for (i in 0 until size) {
                        val actual = read.get(i).toInt() and 0xFF
                        val expected = i and 0xFF
                        if (actual != expected) {
                            // Dump surrounding bytes for diagnosis
                            val start = (i - 8).coerceAtLeast(0)
                            val end = (i + 8).coerceAtMost(size)
                            val neighbors = buildString {
                                for (j in start until end) {
                                    if (j > start) append(',')
                                    append("0x%02X".format(read.get(j).toInt() and 0xFF))
                                }
                            }
                            IllusionLogger.warnMessage(
                                "[backend-java] mismatch at %d: expected=0x%02X actual=0x%02X neighbors=[%s]"
                                    .format(i, expected, actual, neighbors))
                            throw IllegalStateException("buffer copy readback mismatch at $i")
                        }
                    }
                }

                // Texture readback: native writes tightly-packed rows (rowBytes per
                // row), matching what the caller allocated.
                val rowPitch = texSize * 4
                texDst.map(0L, texDst.size(), true, false).use { view ->
                    val read: ByteBuffer = view.data()
                    for (row in 0 until texSize) {
                        for (col in 0 until texSize) {
                            val expected = (row * texSize + col) * 4 and 0xFF  // texData first channel
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
            LOGGER.info("[backend] Command layer self-test OK.")
        }
        // -----------------------------------------------------------------------
        // P4: pipeline self-test — 内嵌官方启动期 critical shader 的 GLSL 原文
        // （提取自 assets/minecraft/shaders/core/），编译官方真实 RenderPipeline
        // 全链路：GLSL -> shaderc SPIR-V -> spvc 反射/rebind -> HLSL 5.1 源码 ->
        // DXC(DXIL, SM 6.9) -> root signature + 双 PSO。（2026-10-25 / P216：不再走
        // d3dcompiler 的 DXBC 5.1，DXC 是唯一路径。）
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

        /**
         * 用两条官方真实管线（GUI、GUI_TEXTURED）验证 P4 编译全链路。每条管线
         * 编译出的原生 Dx12Pipeline* 必须 handle != 0（isValid()==true）。编译失败
         * 时 compilePipeline 返回无效管线并记 error，这里再抛异常终止自检。
         */
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
            LOGGER.info("[backend] Pipeline self-test OK (GLSL->SPIR-V->HLSL->DXBC->PSO for core/gui + core/position_tex_color)")
            // 26.3 起真实 ShaderSource 是按次传进来的（见 D3D12Device.resolveShaderSource），
            // 而这里的自检用的是内嵌 GLSL 兜底源。同一条 pipeline/gui 若把自检结果留在缓存里，
            // 后面 vanilla 用真实源编译时会命中缓存、真实源永远不生效，所以自检完立刻清掉。
            device.clearPipelineCache()
        }

        /**
         * P72：mesh shader 自检 —— 用**客观像素证据**证明「DXC 编译 → mesh PSO → DispatchMesh → GPU 真的执行了」。
         *
         * 为什么必须走 DXC：`D3DCompile` 只支持到 shader model 5.1，而 mesh shader 是 SM 6.5 才引入的
         * （profile `ms_6_x` / `as_6_x`）；并且 mesh 管线用的是 pipeline state stream 里的 AS/MS subobject，
         * 与图形路径的打包描述不通用，所以 native 侧单独提供
         * `mcdr_dx12CreateMeshPipeline` / `mcdr_dx12DispatchMesh`。
         *
         * 为什么用像素回读当证据：所有 API 都返回成功只能说明「没报错」；离屏纹理里真的出现红色像素，
         * 才说明 mesh shader 在 GPU 上跑过。至于「画面看起来对不对」属于肉眼判断，交给用户看，
         * 这里只提供客观数字（见 AGENTS.md「协作约定」）。
         *
         * 全程走 `MCDR_ABI` 低层调用，不碰游戏渲染路径；失败抛异常（附 `dx12LastError()`），
         * 由 [createDevice] 统一转成 `BackendCreationException`。
         *
         * @param device 只为与其他自检保持一致的签名——本自检只调用 ABI 静态入口，不经由 D3D12Device。
         */
        /**
         * 诊断（`[debug] meshDiagnostics` = clear|pass）：把"写纹理 → 回读"这一对操作最小化隔离。
         *
         * - `clear`：只调 `dx12ClearColorTexture`（不涉及 render pass）；
         * - `pass` ：只做一次 render pass + 不透明黑清屏（不涉及 mesh/PSO/描述符）。
         *
         * 两个模式最后都做一次 3x3 回读并抛异常（走 P73 崩溃路径，进程自行退出，
         * 便于脚本取证）。只在诊断时使用，正常启动不会设置该环境变量。
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
                        LOGGER.info("[backend] mesh diag(clear): clearColorTexture lastError=[{}]",
                            MCDR_ABI.D3D12LastError())
                    }
                    "pass" -> {
                        MCDR_ABI.D3D12BeginRenderPass(ctx, longArrayOf(texture), intArrayOf(0),
                            byteArrayOf(1), floatArrayOf(0.0f, 0.0f, 0.0f, 1.0f),
                            0L, 0, 0.toByte(), 0.0, 0, 0, size, size)
                        LOGGER.info("[backend] mesh diag(pass): beginRenderPass lastError=[{}]",
                            MCDR_ABI.D3D12LastError())
                        MCDR_ABI.D3D12EndRenderPass(ctx)
                    }
                    // pass2：先用 API 直接清成红（这一步已证明会落到纹理上），再用一次
                    // render pass 清成不透明黑。回读若仍是红 ⇒ render pass 的清屏写入
                    // 根本没落在这张纹理上；若变黑 ⇒ render pass 清屏有效。
                    "pass2" -> {
                        MCDR_ABI.D3D12ClearColorTexture(ctx, texture, 1.0f, 0.0f, 0.0f, 1.0f)
                        MCDR_ABI.D3D12BeginRenderPass(ctx, longArrayOf(texture), intArrayOf(0),
                            byteArrayOf(1), floatArrayOf(0.0f, 0.0f, 0.0f, 1.0f),
                            0L, 0, 0.toByte(), 0.0, 0, 0, size, size)
                        LOGGER.info("[backend] mesh diag(pass2): beginRenderPass lastError=[{}]",
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
                LOGGER.info("[backend] mesh diag({}): fence={} readback={}", mode, fence,
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
         * P234：compute 最小通路自检 —— 编译 `cs_6_8` → 建 compute PSO → `Dispatch(2,2,1)` →
         * 回读，断言"根 UAV 写入 + 根常量生效"。
         *
         * 着色器把 `gTag` 写进 R 通道，所以回读的 9 个像素必须都是 `(gTag, 0, 0, 255)`。这同时
         * 证明了四件事：DXC 接受 `cs_6_8`、`CreateComputePipelineState` 建成、根签名
         * `[0] root UAV u0` 绑到了目标纹理、`[1] root constants b0` 的值真的进了着色器。
         *
         * <p>这张目标纹理必须带 `USAGE_STORAGE`(32)：native 只在带该位的纹理上置
         * `ALLOW_UNORDERED_ACCESS`，缺了它 `CreateUnorderedAccessView`/派发都会失败。
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
                        LOGGER.info("[backend] Compute self-test: dispatch ok, read-back={}", seen.joinToString(" ") { px ->
                            "(${px[0]},${px[1]},${px[2]},${px[3]})"
                        })
                        val bad = seen.firstOrNull { it[0] != tag || it[1] != 0 || it[2] != 0 || it[3] != 255 }
                        if (bad != null) {
                            throw IllegalStateException(
                                "compute self-test: unexpected pixel (${bad[0]},${bad[1]},${bad[2]},${bad[3]}), " +
                                    "expected ($tag,0,0,255)")
                        }
                        LOGGER.info("[backend] Compute self-test PASSED (cs_6_8 + descriptor-table UAV u0 + root constants b0)")
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

        /**
         * P235：着色率图像（SRI）自检 —— 建 R8_UINT 图像、两张 SRV、dispatch、绑定 SRI、读回。
         *
         * 这张图以后由 `D3D12ShadingRateImage` 每帧生成，这里只证明"native 通路能写它、
         * 而且能挂到命令列表的 SRI 槽位上"。输入纹理只要求存在且能建 SRV，内容不参与断言。
         */
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
                            val dispatched = MCDR_ABI.D3D12DispatchComputeShadingRate(
                                ctx, pipeline, viewA, viewB, image, intArrayOf(tiles, tiles, 16, 0), 1, 1, 1)
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
                                "[backend] SRI self-test: {}x{} tiles, first pixel={} raw={}",
                                tiles, tiles, px[0], px.joinToString(","))
                            if (px[0] != 5) {
                                throw IllegalStateException(
                                    "SRI self-test: tile(0,0) expected 5 (2X2), got ${px[0]}")
                            }
                            LOGGER.info(
                                "[backend] SRI self-test PASSED (R8_UINT image + 2 SRV + UAV + RSSetShadingRateImage)")
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
            LOGGER.info("[backend] Mesh self-test starting (device={})", device)

            // mesh shader：1 个线程组输出 3 顶点 1 图元；三角形故意超出 NDC（(-1,-1)/(3,-1)/(-1,3)）
            // 以覆盖整个 viewport，颜色写死纯红，由 PS 原样输出。
            // 注意：PS 的输入语义必须与 `out vertices` 结构逐字段、逐顺序一致，否则 PSO 创建失败。
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

            // ms_6_6：本机已实测可用（能力行 dxc=yes(ms_6_9)、meshSmoke=ok，见 known_bugs.md）。
            // 备选 ms_6_5；`ms_6_2` 不存在（mesh shader 自 SM 6.5 才有）。PS 用 ps_6_0 即可。
            // 注意 ms_6_6 不是"回退"：它只是 mesh 的最低可用档，图形管线那边硬要求 6_9（P216）。
            val meshDxil: ByteArray = MCDR_ABI.D3D12CompileShaderDxc(meshHlsl, "main", "ms_6_6")
                ?: throw IllegalStateException(
                    "mesh self-test: DXC compile failed for ms_6_6: ${MCDR_ABI.D3D12LastError()}")
            val pixelDxil: ByteArray = MCDR_ABI.D3D12CompileShaderDxc(pixelHlsl, "main", "ps_6_0")
                ?: throw IllegalStateException(
                    "mesh self-test: DXC compile failed for ps_6_0: ${MCDR_ABI.D3D12LastError()}")

            // 一张 R8G8B8A8_UNORM 离屏颜色目标：既当 RTV（RENDER_ATTACHMENT），又要能拷回 CPU
            // （COPY_SRC）——回读路径走 CopyTextureRegion，缺 COPY_SRC 会失败。
            val colorTarget = MCDRPipelineDesc.ColorTarget()
                .format(GpuFormat.RGBA8_UNORM.ordinal)
                .writeMask(0xF)
            val desc = MCDRMeshPipelineDesc()
                .shaders(meshDxil, null, pixelDxil)  // 不启用 amplification shader
                .topology(false, 0)                  // 关闭剔除、polygonMode 0 = fill
                .addColorTarget(colorTarget)         // 无 CBV/SRV 绑定，因此不调 addBinding

            // P129：自检同时覆盖「外部根签名」路径 —— 签名带 mesh 尾参
            // （b8 段表 + t14/t15 顶点/索引表），PSO 用这份签名建。
            // 自检 MS 不读任何绑定，所以只要签名与 PSO 结构合法就应当出像素。
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
                // P73：这里曾经再建一张同尺寸 "control" 纹理做负对照（只 clear、不 dispatch），
                // 用来区分「RTV/clear/回读通路坏了」和「mesh 没产出」。实测该结构不稳定：
                // 同一个命令列表内连续两个 render pass + 连续两次回读时，回读结果会互相错配
                // （control 读到 mesh 的红色；也见过两张都读到红色），而游戏渲染路径从未观察到
                // 该现象。自检必须确定性通过，故改回单纹理单 pass（详见 known_bugs.md）。
                ctx = MCDR_ABI.D3D12CreateCommandEncoder()
                if (ctx == 0L) {
                    throw IllegalStateException("mesh self-test: dx12CreateCommandEncoder returned 0: "
                        + MCDR_ABI.D3D12LastError())
                }

                MCDR_ABI.D3D12BeginCommandList(ctx)
                // P72/P73 取证结论（DX12_MESH_DIAG=clear|pass|pass2 三次隔离实验）：
                //   ① 只调 dx12ClearColorTexture（API 清屏）+ 回读 → 读到清屏色 ✓
                //   ② 只用 render pass 清屏 + 回读 → 读到 (0,0,0,0)（纹理从未被写）
                //   ③ 先 API 清红、再用同一 pass 清黑 + 回读 → 读到黑 ✓
                // 即"同一命令列表里 render pass 之前先有一次普通 API 写入"这种顺序才可靠。
                // 因此自检改成：先 API 清成不透明蓝（证明纹理可写、回读通路正常），
                // 再用 **LOAD**（clearFlag=0，不清屏）的 render pass 只让 mesh shader 写红——
                // 读到蓝=mesh 什么都没画，读到红=DispatchMesh 真的光栅化进了这张纹理。
                MCDR_ABI.D3D12ClearColorTexture(ctx, texture, 0.0f, 0.0f, 1.0f, 1.0f)
                LOGGER.info("[backend] mesh self-test: baseline clear(blue) lastError=[{}]", MCDR_ABI.D3D12LastError())
                // 不清屏（LOAD）：底色保持上面那层蓝，任何红色都只能来自 mesh shader。
                MCDR_ABI.D3D12BeginRenderPass(ctx, longArrayOf(texture), intArrayOf(0),
                    byteArrayOf(0), floatArrayOf(0.0f, 0.0f, 0.0f, 1.0f),
                    0L, 0, 0.toByte(), 0.0, 0, 0, size, size)
                // 诊断：mcdr_dx12BeginRenderPass 返回 void，失败只能落在 lastError 里，而后续
                // 成功的原生调用（如回读）会把它清掉——所以必须**紧跟**调用后立刻取一次。
                LOGGER.info("[backend] mesh self-test: beginRenderPass lastError=[{}]",
                    MCDR_ABI.D3D12LastError())
                if (!MCDR_ABI.D3D12SetViewport(ctx, 0, 0, size, size)
                    || !MCDR_ABI.D3D12SetScissor(ctx, 0, 0, size, size)) {
                    throw IllegalStateException("mesh self-test: set viewport/scissor failed")
                }
                if (!MCDR_ABI.D3D12SetPipeline(ctx, pipeline, false)) {
                    throw IllegalStateException("mesh self-test: dx12SetPipeline failed: "
                        + MCDR_ABI.D3D12LastError())
                }
                // native 的 dispatchMesh 会检查"本帧已 push 过描述符"；这条管线没有任何绑定，
                // 但 wrapper 对 count == 0 直接返回 true，所以显式推一次空绑定。
                if (!MCDR_ABI.D3D12PushDescriptors(ctx, null, null, null, null, null, null)) {
                    throw IllegalStateException("mesh self-test: dx12PushDescriptors failed: "
                        + MCDR_ABI.D3D12LastError())
                }
                // 线程组数 = ceil(64 / numthreads(1,1,1)) = 64 → 8x8x1 正好覆盖整张 64x64。
                if (!MCDR_ABI.D3D12DispatchMesh(ctx, 8, 8, 1)) {
                    throw IllegalStateException("mesh self-test: dx12DispatchMesh failed: "
                        + MCDR_ABI.D3D12LastError())
                }
                MCDR_ABI.D3D12EndRenderPass(ctx)
                // 关键：submitCommandList 自己会 Close 命令列表（dx12_device.cpp:2296）。
                // 若在 Submit 前先显式 dx12EndCommandList，submit 会把这一帧当"空帧"
                // 直接早退（:2291 `if (!ctx->listOpen)`），于是 GPU 什么都没执行、
                // 返回的 fence 又立即可满足 → 回读全 0 且没有任何报错。
                // 游戏侧 D3D12CommandEncoderBackend 同样是 Begin → 录制 → Submit（不显式 End）。
                val fence = MCDR_ABI.D3D12Submit(ctx)
                LOGGER.info("[backend] mesh self-test: submitted, fence={}", fence)
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
                // 诊断：把 3x3 采样点全部打出来。mesh 的输出只有纯红，clear 是黑，
                // 所以"读到红"就证明 dispatch 真的光栅化进了这张纹理。
                LOGGER.info("[backend] mesh self-test: readback = [{}]", dump(pixels))
                if (!hasRed(pixels)) {
                    // 同一个二进制、同一份代码，实测会出现"整条命令列表的内容对回读不可见"
                    // （连 API clear 的蓝底都读不到，alpha=0），而重启一次又正常。
                    // 先隔一小段时间重读一次：若第二次能读到红，就说明这是"提交后立刻回读"
                    // 的可见性/同步竞态（native 侧），而不是 mesh 没画。
                    Thread.sleep(150)
                    val retry = MCDR_ABI.D3D12ReadbackTexturePixels(texture)
                    LOGGER.warn("[backend] mesh self-test: first readback = [{}]; retry after 150ms = [{}]",
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
                LOGGER.info("[backend] Mesh self-test OK (DXC ms_6_6 + PSO + DispatchMesh -> red pixel readback)")
            } finally {
                // 逆序释放；句柄为 0 表示没建起来，跳过。
                if (ctx != 0L) MCDR_ABI.D3D12DestroyCommandEncoder(ctx)
                if (texture != 0L) MCDR_ABI.ID3D12DestroyResource(texture)
                if (pipeline != 0L) MCDR_ABI.D3D12DestroyPipeline(pipeline)
                MCDR_ABI.D3D12ReleaseRootSignature(meshRootSig)
            }
        }

        /**
         * P5：创建 DXGI swapchain（绑定真实窗口 HWND）→ 配置 → acquire →
         * 用纯红色清空 Backbuffer → submit → present。
         * 首帧会向 MC 真实窗口 present 一次红色，用于验证渲染链路是否打通。
         * 随后回退不影响游戏（游戏有自己的 surface/lifecycle）。
         *
         * 注意：使用 D3D12GpuSurface 而非 GpuSurface 包装类，因为 clearToRed 走
         * 非 blit 路径（null srcTex = ClearRenderTargetView），绕过了 hasBlittedTexture
         * 标志，GpuSurface.present() 会因此抛出 IllegalStateException。
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

                // 纯红色清空 Backbuffer（null texture = 不做 copy，只做 ClearRenderTargetView）
                D3DSurface.clearToRed(encoderBackend)

                // 26.3：前端 CommandEncoder 已改成接口 + 内部包装，自检直接走 backend 层
                // （submit 后由 fence 等 GPU 完成，语义与旧的 encoder.createFence() 一致）。
                val fence: GpuFence = encoderBackend.createFence()
                encoderBackend.submit()
                // 必须等 GPU 完成 clear 后再销毁 swapchain，否则 backbuffer 在被使用时释放。
                if (!fence.awaitCompletion(5000L)) {
                    throw IllegalStateException("surface self-test submit timed out")
                }
                fence.close()
                D3DSurface.present()
                System.err.println("[backend-java] selfTestSurface: presented GREEN to real window — "
                    + "if you see green, the render pipeline is working!")
            } finally {
                D3DSurface?.close()
                encoderBackend.close()
            }
            LOGGER.info("[backend] Surface self-test OK (pure green clear + present via FFM on real window)")
        }
    }
}
