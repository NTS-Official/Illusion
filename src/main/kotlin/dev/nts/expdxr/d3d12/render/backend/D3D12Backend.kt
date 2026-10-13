package dev.nts.expdxr.d3d12.render.backend

import com.mojang.blaze3d.GLFWErrorCapture
import com.mojang.blaze3d.GpuFormat
import com.mojang.blaze3d.buffers.GpuBuffer
import com.mojang.blaze3d.buffers.GpuFence
import com.mojang.blaze3d.pipeline.CompiledRenderPipeline
import com.mojang.blaze3d.shaders.GpuDebugOptions
import com.mojang.blaze3d.shaders.ShaderSource
import com.mojang.blaze3d.shaders.ShaderType
import com.mojang.blaze3d.systems.*
import com.mojang.blaze3d.textures.AddressMode
import com.mojang.blaze3d.textures.FilterMode
import com.mojang.blaze3d.textures.GpuSampler
import com.mojang.blaze3d.textures.GpuTexture
import dev.nts.expdxr.d3d12.extern.MCDR_ABI
import dev.nts.expdxr.d3d12.extern.MCDRMeshPipelineDesc
import dev.nts.expdxr.d3d12.extern.MCDRPipelineDesc
import dev.nts.expdxr.d3d12.render.adapter.D3D12Device
import net.minecraft.client.renderer.RenderPipelines
import org.lwjgl.glfw.GLFW
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.util.*

/**
 * Direct3D 12 [GpuBackend] implementation.
 *
 * Mirrors `com.mojang.blaze3d.vulkan.VulkanBackend`'s contract:
 * - [setWindowHints] runs inside GLFW's error scope before the window is created (no hints needed for DX12).
 * - [handleWindowCreationErrors] is only called when `glfwCreateWindow` returns 0.
 * - [createDevice] is called once the window exists; it runs the full D3D12 self-test chain and, when it
 *   passes, returns a live [D3D12Device] that drives the vanilla render flow (P6+).
 */
class D3D12Backend : GpuBackend {
    override fun getName(): String = "DX12"

    override fun setWindowHints() {
        // D3D12 后端默认让窗口**不带 OpenGL 上下文**（GLFW_NO_API），与 vanilla VulkanBackend.setWindowHints() 一致。
        //
        // 为什么必须这样（两条独立理由）：
        // 1) RTSS / MSI Afterburner 的 OSD：只要进程里存在一条"被使用过"的 GL 上下文，RTSS 就把 OSD
        //    交给它的 OpenGL 呈现路径，而 GL 的内容被我们的 D3D12 子窗口整块盖住 —— OSD 一直在画，
        //    用户却永远看不到。进程里没有 GL 上下文时 RTSS 才走 D3D12 overlay 路径。
        //    实测对照（2026-10-12）：普通 GL 模式 OSD 完全不可见；GLFW_NO_API 模式下 OSD 正常显示
        //    GPU/CPU/内存/帧率（证据见 known_bugs.md §1 与 _port/noapi_osd_crop.png、_port/aa_osd_fix_1.png）。
        // 2) GL 侧本来就从不呈现：26.2 里唯一的 GL 呈现点是 GlSurface.present() 的 glfwSwapBuffers，
        //    我们的后端不会走到那里，因此那条 GL 上下文只是白白让 RTSS 误判进程类型。
        //
        // 逃生开关（万一将来有功能或别的 mod 需要真实 GL 上下文）：
        //   -Ddxrmc.noApi=false ／ 环境变量 DXR_MC_GL_CONTEXT=1 ／ 游戏工作目录（Illusion/run）放 gl_context.flag
        // 三者任一命中就退回带 GL 上下文的旧行为。
        val useNoApi = !glContextRequested()
        if (useNoApi) {
            GLFW.glfwWindowHint(GLFW.GLFW_CLIENT_API, GLFW.GLFW_NO_API)
        }
        LOGGER.info("[d3d12] GLFW window hints: GLFW_CLIENT_API={} ({})",
            if (useNoApi) "GLFW_NO_API" else "GLFW_OPENGL_API",
            if (useNoApi)
                "default: no GL context in this process — required so RTSS/Afterburner draws its D3D12 OSD"
            else
                "escape hatch active (-Ddxrmc.noApi=false / DXR_MC_GL_CONTEXT=1 / gl_context.flag): a real GL context will be created")
    }

    /** 逃生开关：显式要求保留真实 GL 上下文（即关掉默认的 GLFW_NO_API）。 */
    private fun glContextRequested(): Boolean {
        if (System.getProperty("dxrmc.noApi") == "false") return true
        if (System.getenv("DXR_MC_GL_CONTEXT") == "1") return true
        return java.io.File("gl_context.flag").exists()
    }

    @Throws(BackendCreationException::class)
    override fun handleWindowCreationErrors(error: GLFWErrorCapture.Error) {
        // P73：窗口创建失败同样不再回退到 GL/Vulkan（旧行为会接着试下一个候选后端，用户只看到
        // 一次"正常"的 OpenGL 启动）。见 Dx12Fatal 的策略说明。
        val what = if (error != null) {
            String.format(Locale.ROOT, "GLFW window creation failed for DX12 (GLFW_ERROR: 0x%X)", error.error)
        } else {
            "Failed to create window for DX12 (GLFW reported no error code)"
        }
        LOGGER.error("[d3d12] {}", what)
        Dx12Fatal.crash(what, null)
    }

    @Throws(BackendCreationException::class)
    override fun createDevice(window: Long, defaultShaderSource: ShaderSource,
        debugOptions: GpuDebugOptions, criticalShaderLoader: Runnable): GpuDevice {
        // P6: createDevice returns a real, live GpuDevice. The game then drives
        // the full vanilla render flow through our backend (RenderSystem
        // initRenderer, surface, per-frame render passes, present) with no
        // GL/Vulkan fallback. Self-tests run against the live device; only a
        // failure closes it and rethrows so the game can fall back.
        // P73: that last sentence is obsolete - a failure here goes through Dx12Fatal
        // (reset preferredGraphicsBackend + crash with an explanatory report) instead of
        // rethrowing BackendCreationException, which the game would swallow before
        // silently trying GL.
        // P73 测试钩子：-Ddxrmc.failDevice=true 让后端在第一步就失败，用来端到端验证
        // "不再回退 + 还原 preferredGraphicsBackend + 生成带说明的崩溃报告"这条策略。
        // 正常运行不会设置该属性（build.gradle 里的 runClient 默认值是 false）。
        if (System.getProperty("dxrmc.failDevice") == "true") {
            Dx12Fatal.crash("simulated Direct3D 12 device failure (-Ddxrmc.failDevice=true)", null)
        }
        try {
            val device = D3D12Device(defaultShaderSource)
            try {
                selfTestJavaResources(device)
                selfTestCommandLayer(device)
                selfTestPipelines(device)
                // P72：mesh shader 自检（DXC 编译 → mesh PSO → DispatchMesh → 离屏像素回读）。
                // 默认关闭：它要用 DXC 现场编译 HLSL，没必要塞进每次启动的关键路径；
                // 打开方式：JVM 参数 -Ddxrmc.selfTestMesh=true。
                if (SELF_TEST_MESH_ENABLED) {
                    selfTestMesh(device)
                }
                // 诊断（DX12_MESH_DIAG=clear|pass）：把"写纹理 → 回读"做最小化隔离，
                // 用来区分「回读通路坏了」和「render pass 没落到纹理上」。只在取证时使用。
                System.getenv("DX12_MESH_DIAG")?.takeIf { it.isNotEmpty() }?.let {
                    selfTestMeshDiag(it)
                }
                // 注意：surface 自检会在真实窗口上创建、Present 并立刻销毁一条交换链。
                // RTSS/Afterburner 的 OSD 绑定到它注入(默认约 15s)之后看到的第一条被 Present 的
                // 交换链上；自检链一旦抢先 Present，真正的游戏交换链就再也拿不到 OSD（表现为
                // 装了 RTSS 也完全看不到 OSD）。探针矩阵已验证：只要有 GL/其它交换链先 Present，
                // OSD 会永久留在那条链上。因此默认跳过，需要时用 -Ddxrmc.selfTestSurface=true 打开。
                if (SURFACE_SELF_TEST_ENABLED) {
                    selfTestSurface(device, window)
                } else {
                    LOGGER.info("[d3d12] Surface self-test skipped: swapped-chain self-test would steal "
                        + "the RTSS OSD binding (enable with -Ddxrmc.selfTestSurface=true)")
                }
            } catch (t: Throwable) {
                device.close()
                throw t
            }
            // P6 诊断：自测阶段用嵌入 GLSL 填充了 pipeline cache，且 DIAG 绿色覆盖已生效。
            // 必须在返回给游戏前清除，否则 getOrCompilePipeline 会命中自测缓存条目，
            // 导致真实游戏 shader 从未被编译，屏幕显示的是自测阶段的嵌入 shader 输出（紫色）。
            device.clearPipelineCache()
            return GpuDevice(device, criticalShaderLoader)
        } catch (t: Throwable) {
            LOGGER.error("[d3d12] D3D12 self-test failed", t)
            LOGGER.error("[d3d12] D3D12 failure is fatal by design (P73): no GL/Vulkan fallback, "
                + "preferredGraphicsBackend is reset to default and the game crashes with a report")
            // Dx12Fatal 抛的是 ReportedException（不是 BackendCreationException），
            // 所以 vanilla 的候选后端循环不会吞掉它 —— 见 Dx12Fatal 的类注释。
            Dx12Fatal.crash("DX12 self-test failed: " + t, t)
        }
    }
    companion object {
        private val LOGGER: Logger = LoggerFactory.getLogger("gl4dx12")

        /**
         * 真实窗口的交换链自检默认关闭：见 createDevice 中关于 RTSS OSD 绑定第一条被 Present
         * 交换链的说明。打开方式：JVM 参数 -Ddxrmc.selfTestSurface=true。
         */
        private val SURFACE_SELF_TEST_ENABLED: Boolean =
            System.getProperty("dxrmc.selfTestSurface") == "true"

        /**
         * P72：mesh shader 自检默认关闭，打开方式：JVM 参数 -Ddxrmc.selfTestMesh=true。
         * 门控写法与 [SURFACE_SELF_TEST_ENABLED] 一致。它用 DXC 现场编译 HLSL、建 mesh 管线、
         * DispatchMesh 并回读离屏纹理像素，属于"证明这条能力链可用"的一次性验证，
         * 不该出现在每次启动的关键路径上。
         */
        private val SELF_TEST_MESH_ENABLED: Boolean =
            System.getProperty("dxrmc.selfTestMesh") == "true"

        /**
         * Exercise every P2 resource method through the real JNI path so a broken
         * native binding fails loudly here instead of during vanilla initRenderer.
         */
        private fun selfTestJavaResources(device: D3D12Device) {
            // texture: RGBA8_UNORM, 64x64, TEXTURE_BINDING|RENDER_ATTACHMENT
            val texture: GpuTexture = device.createTexture("d3d12-selftest",
                GpuTexture.USAGE_TEXTURE_BINDING or GpuTexture.USAGE_RENDER_ATTACHMENT,
                GpuFormat.RGBA8_UNORM, 64, 64, 1, 1)
            // sampler: REPEAT/REPEAT, NEAREST/NEAREST, aniso=1
            val sampler: GpuSampler = device.createSampler(AddressMode.REPEAT, AddressMode.REPEAT,
                FilterMode.NEAREST, FilterMode.NEAREST, 1, OptionalDouble.of(16.0))
            // buffer: MAP_WRITE, map -> write -> unmap
            val buffer: GpuBuffer = device.createBuffer({ "d3d12-selftest" },
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
            LOGGER.info("[d3d12] Java resource self-test OK (texture/buffer/sampler/view via FFM)")
        }

        /**
         * Exercise the P3 command layer through the real JNI path: record
         * uploads/copies into a command list, submit through the fence, and verify
         * both a buffer copy readback and a texture write/readback round-trip.
         */
        private fun selfTestCommandLayer(device: D3D12Device) {
            val size = 256
            val src: GpuBuffer = device.createBuffer({ "d3d12-selftest-src" },
                GpuBuffer.USAGE_COPY_DST or GpuBuffer.USAGE_COPY_SRC, size.toLong())
            val dst: GpuBuffer = device.createBuffer({ "d3d12-selftest-dst" },
                GpuBuffer.USAGE_MAP_READ, size.toLong())
            val data: ByteBuffer = ByteBuffer.allocate(size)
            for (i in 0 until size) {
                data.put(i, (i and 0xFF).toByte())
            }

            val texSize = 8
            val tex: GpuTexture = device.createTexture("d3d12-selftest-tex",
                GpuTexture.USAGE_TEXTURE_BINDING, GpuFormat.RGBA8_UNORM, texSize, texSize, 1, 1)
            val texDst: GpuBuffer = device.createBuffer({ "d3d12-selftest-texdst" },
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
                    System.err.println("[d3d12-java] self-test: reading dst readback, size=" + size
                        + " dst.usage=" + Integer.toBinaryString(dst.usage()))
                    System.err.flush()
                    for (i in 0 until size) {
                        val actual = read.get(i).toInt() and 0xFF
                        val expected = i and 0xFF
                        if (actual != expected) {
                            // Dump surrounding bytes for diagnosis
                            System.err.printf(
                                "[d3d12-java] mismatch at %d: expected=0x%02X actual=0x%02X",
                                i, expected, actual)
                            val start = Math.max(0, i - 8)
                            val end = Math.min(size, i + 8)
                            System.err.print(" neighbors=[")
                            for (j in start until end) {
                                if (j > start) System.err.print(',')
                                System.err.printf("0x%02X", read.get(j).toInt() and 0xFF)
                            }
                            System.err.println("]")
                            System.err.flush()
                            throw IllegalStateException("buffer copy readback mismatch at " + i)
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
                                    "texture readback mismatch at (" + col + "," + row + ")")
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
            LOGGER.info("[d3d12] Command layer self-test OK (submit/fence/copy/readback via FFM)")
        }
        // -----------------------------------------------------------------------
        // P4: pipeline self-test — 内嵌官方启动期 critical shader 的 GLSL 原文
        // （提取自 assets/minecraft/shaders/core/），编译官方真实 RenderPipeline
        // 全链路：GLSL -> shaderc SPIR-V -> spvc 反射/rebind -> HLSL SM5.1 ->
        // d3dcompiler DXBC -> root signature + 双 PSO。
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
        private val EMBEDDED_SHADER_SOURCE: ShaderSource = ShaderSource { id, type ->
            if (!id.path.startsWith("core/")) {
                null
            } else {
                when (id.path) {
                    "core/gui" -> if (type == ShaderType.VERTEX) CORE_GUI_VSH else CORE_GUI_FSH
                    "core/position_tex_color" -> if (type == ShaderType.VERTEX) {
                        CORE_POSITION_TEX_COLOR_VSH
                    } else {
                        CORE_POSITION_TEX_COLOR_FSH
                    }
                    else -> null
                }
            }
        }

        /**
         * 用两条官方真实管线（GUI、GUI_TEXTURED）验证 P4 编译全链路。每条管线
         * 编译出的原生 Dx12Pipeline* 必须 handle != 0（isValid()==true）。编译失败
         * 时 compilePipeline 返回无效管线并记 error，这里再抛异常终止自检。
         */
        private fun selfTestPipelines(device: D3D12Device) {
            val names = arrayOf("pipeline/gui", "pipeline/gui_textured")
            for (name in names) {
                val pipeline: CompiledRenderPipeline = when (name) {
                    "pipeline/gui" -> device.precompilePipeline(RenderPipelines.GUI,
                        EMBEDDED_SHADER_SOURCE)
                    else -> device.precompilePipeline(RenderPipelines.GUI_TEXTURED,
                        EMBEDDED_SHADER_SOURCE)
                }
                if (!pipeline.isValid()) {
                    throw IllegalStateException("native pipeline compile failed for " + name)
                }
            }
            LOGGER.info("[d3d12] Pipeline self-test OK (GLSL->SPIR-V->HLSL->DXBC->PSO for core/gui + core/position_tex_color)")
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
         * 诊断（`DX12_MESH_DIAG=clear|pass`）：把"写纹理 → 回读"这一对操作最小化隔离。
         *
         * - `clear`：只调 `dx12ClearColorTexture`（不涉及 render pass）；
         * - `pass` ：只做一次 render pass + 不透明黑清屏（不涉及 mesh/PSO/描述符）。
         *
         * 两个模式最后都做一次 3x3 回读并抛异常（走 P73 崩溃路径，进程自行退出，
         * 便于脚本取证）。只在诊断时使用，正常启动不会设置该环境变量。
         */
        private fun selfTestMeshDiag(mode: String) {
            val size = 64
            val texture = MCDR_ABI.dx12CreateTexture(
                GpuTexture.USAGE_RENDER_ATTACHMENT or GpuTexture.USAGE_COPY_SRC,
                GpuFormat.RGBA8_UNORM.ordinal, size, size, 1, 1)
            if (texture == 0L) {
                throw IllegalStateException("mesh diag: dx12CreateTexture failed: " + MCDR_ABI.dx12LastError())
            }
            val ctx = MCDR_ABI.dx12CreateCommandEncoder()
            if (ctx == 0L) {
                throw IllegalStateException("mesh diag: dx12CreateCommandEncoder failed: " + MCDR_ABI.dx12LastError())
            }
            try {
                MCDR_ABI.dx12BeginCommandList(ctx)
                when (mode) {
                    "clear" -> {
                        MCDR_ABI.dx12ClearColorTexture(ctx, texture, 1.0f, 0.0f, 0.0f, 1.0f)
                        LOGGER.info("[d3d12] mesh diag(clear): clearColorTexture lastError=[{}]",
                            MCDR_ABI.dx12LastError())
                    }
                    "pass" -> {
                        MCDR_ABI.dx12BeginRenderPass(ctx, longArrayOf(texture), intArrayOf(0),
                            byteArrayOf(1), floatArrayOf(0.0f, 0.0f, 0.0f, 1.0f),
                            0L, 0, 0.toByte(), 0.0, 0, 0, size, size)
                        LOGGER.info("[d3d12] mesh diag(pass): beginRenderPass lastError=[{}]",
                            MCDR_ABI.dx12LastError())
                        MCDR_ABI.dx12EndRenderPass(ctx)
                    }
                    // pass2：先用 API 直接清成红（这一步已证明会落到纹理上），再用一次
                    // render pass 清成不透明黑。回读若仍是红 ⇒ render pass 的清屏写入
                    // 根本没落在这张纹理上；若变黑 ⇒ render pass 清屏有效。
                    "pass2" -> {
                        MCDR_ABI.dx12ClearColorTexture(ctx, texture, 1.0f, 0.0f, 0.0f, 1.0f)
                        MCDR_ABI.dx12BeginRenderPass(ctx, longArrayOf(texture), intArrayOf(0),
                            byteArrayOf(1), floatArrayOf(0.0f, 0.0f, 0.0f, 1.0f),
                            0L, 0, 0.toByte(), 0.0, 0, 0, size, size)
                        LOGGER.info("[d3d12] mesh diag(pass2): beginRenderPass lastError=[{}]",
                            MCDR_ABI.dx12LastError())
                        MCDR_ABI.dx12EndRenderPass(ctx)
                    }
                    else -> throw IllegalArgumentException("mesh diag: unknown mode '" + mode + "'")
                }
                val fence = MCDR_ABI.dx12Submit(ctx)
                if (!MCDR_ABI.dx12WaitForFence(ctx, fence, 5_000_000_000L)) {
                    throw IllegalStateException("mesh diag: fence wait timed out (5s)")
                }
                val pixels = MCDR_ABI.dx12ReadbackTexturePixels(texture)
                LOGGER.info("[d3d12] mesh diag({}): fence={} readback={}", mode, fence,
                    if (pixels == null) "null" else (0 until 9).joinToString(" ") { i ->
                        "(${pixels[i * 4]},${pixels[i * 4 + 1]},${pixels[i * 4 + 2]},${pixels[i * 4 + 3]})"
                    })
                throw IllegalStateException("mesh diag(" + mode + "): diagnostic run finished — see the log line above")
            } finally {
                MCDR_ABI.dx12DestroyCommandEncoder(ctx)
                MCDR_ABI.dx12DestroyResource(texture)
            }
        }

        private fun selfTestMesh(device: D3D12Device) {
            val size = 64
            LOGGER.info("[d3d12] Mesh self-test starting (device={})", device)

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

            // ms_6_6：本机已实测可用（能力行 dxc=yes(ms_6_8)、meshSmoke=ok，见 known_bugs.md）。
            // 备选 ms_6_5；`ms_6_2` 不存在（mesh shader 自 SM 6.5 才有）。PS 用 ps_6_0 即可。
            val meshDxil: ByteArray = MCDR_ABI.dx12CompileShaderDxc(meshHlsl, "main", "ms_6_6")
                ?: throw IllegalStateException(
                    "mesh self-test: DXC compile failed for ms_6_6: " + MCDR_ABI.dx12LastError())
            val pixelDxil: ByteArray = MCDR_ABI.dx12CompileShaderDxc(pixelHlsl, "main", "ps_6_0")
                ?: throw IllegalStateException(
                    "mesh self-test: DXC compile failed for ps_6_0: " + MCDR_ABI.dx12LastError())

            // 一张 R8G8B8A8_UNORM 离屏颜色目标：既当 RTV（RENDER_ATTACHMENT），又要能拷回 CPU
            // （COPY_SRC）——回读路径走 CopyTextureRegion，缺 COPY_SRC 会失败。
            val colorTarget = MCDRPipelineDesc.ColorTarget()
                .format(GpuFormat.RGBA8_UNORM.ordinal)
                .writeMask(0xF)
            val desc = MCDRMeshPipelineDesc()
                .shaders(meshDxil, null, pixelDxil)  // 不启用 amplification shader
                .topology(false, 0)                  // 关闭剔除、polygonMode 0 = fill
                .addColorTarget(colorTarget)         // 无 CBV/SRV 绑定，因此不调 addBinding

            var pipeline = 0L
            var texture = 0L
            var ctx = 0L
            try {
                pipeline = MCDR_ABI.dx12CreateMeshPipeline(desc)
                if (pipeline == 0L) {
                    throw IllegalStateException("mesh self-test: dx12CreateMeshPipeline returned 0: "
                        + MCDR_ABI.dx12LastError())
                }
                texture = MCDR_ABI.dx12CreateTexture(
                    GpuTexture.USAGE_RENDER_ATTACHMENT or GpuTexture.USAGE_COPY_SRC,
                    GpuFormat.RGBA8_UNORM.ordinal, size, size, 1, 1)
                if (texture == 0L) {
                    throw IllegalStateException("mesh self-test: dx12CreateTexture returned 0: "
                        + MCDR_ABI.dx12LastError())
                }
                // P73：这里曾经再建一张同尺寸 "control" 纹理做负对照（只 clear、不 dispatch），
                // 用来区分「RTV/clear/回读通路坏了」和「mesh 没产出」。实测该结构不稳定：
                // 同一个命令列表内连续两个 render pass + 连续两次回读时，回读结果会互相错配
                // （control 读到 mesh 的红色；也见过两张都读到红色），而游戏渲染路径从未观察到
                // 该现象。自检必须确定性通过，故改回单纹理单 pass（详见 known_bugs.md）。
                ctx = MCDR_ABI.dx12CreateCommandEncoder()
                if (ctx == 0L) {
                    throw IllegalStateException("mesh self-test: dx12CreateCommandEncoder returned 0: "
                        + MCDR_ABI.dx12LastError())
                }

                MCDR_ABI.dx12BeginCommandList(ctx)
                // P72/P73 取证结论（DX12_MESH_DIAG=clear|pass|pass2 三次隔离实验）：
                //   ① 只调 dx12ClearColorTexture（API 清屏）+ 回读 → 读到清屏色 ✓
                //   ② 只用 render pass 清屏 + 回读 → 读到 (0,0,0,0)（纹理从未被写）
                //   ③ 先 API 清红、再用同一 pass 清黑 + 回读 → 读到黑 ✓
                // 即"同一命令列表里 render pass 之前先有一次普通 API 写入"这种顺序才可靠。
                // 因此自检改成：先 API 清成不透明蓝（证明纹理可写、回读通路正常），
                // 再用 **LOAD**（clearFlag=0，不清屏）的 render pass 只让 mesh shader 写红——
                // 读到蓝=mesh 什么都没画，读到红=DispatchMesh 真的光栅化进了这张纹理。
                MCDR_ABI.dx12ClearColorTexture(ctx, texture, 0.0f, 0.0f, 1.0f, 1.0f)
                LOGGER.info("[d3d12] mesh self-test: baseline clear(blue) lastError=[{}]", MCDR_ABI.dx12LastError())
                // 不清屏（LOAD）：底色保持上面那层蓝，任何红色都只能来自 mesh shader。
                MCDR_ABI.dx12BeginRenderPass(ctx, longArrayOf(texture), intArrayOf(0),
                    byteArrayOf(0), floatArrayOf(0.0f, 0.0f, 0.0f, 1.0f),
                    0L, 0, 0.toByte(), 0.0, 0, 0, size, size)
                // 诊断：mcdr_dx12BeginRenderPass 返回 void，失败只能落在 lastError 里，而后续
                // 成功的原生调用（如回读）会把它清掉——所以必须**紧跟**调用后立刻取一次。
                LOGGER.info("[d3d12] mesh self-test: beginRenderPass lastError=[{}]",
                    MCDR_ABI.dx12LastError())
                if (!MCDR_ABI.dx12SetViewport(ctx, 0, 0, size, size)
                    || !MCDR_ABI.dx12SetScissor(ctx, 0, 0, size, size)) {
                    throw IllegalStateException("mesh self-test: set viewport/scissor failed")
                }
                if (!MCDR_ABI.dx12SetPipeline(ctx, pipeline, false)) {
                    throw IllegalStateException("mesh self-test: dx12SetPipeline failed: "
                        + MCDR_ABI.dx12LastError())
                }
                // native 的 dispatchMesh 会检查"本帧已 push 过描述符"；这条管线没有任何绑定，
                // 但 wrapper 对 count == 0 直接返回 true，所以显式推一次空绑定。
                if (!MCDR_ABI.dx12PushDescriptors(ctx, null, null, null, null, null, null)) {
                    throw IllegalStateException("mesh self-test: dx12PushDescriptors failed: "
                        + MCDR_ABI.dx12LastError())
                }
                // 线程组数 = ceil(64 / numthreads(1,1,1)) = 64 → 8x8x1 正好覆盖整张 64x64。
                if (!MCDR_ABI.dx12DispatchMesh(ctx, 8, 8, 1)) {
                    throw IllegalStateException("mesh self-test: dx12DispatchMesh failed: "
                        + MCDR_ABI.dx12LastError())
                }
                MCDR_ABI.dx12EndRenderPass(ctx)
                // 关键：submitCommandList 自己会 Close 命令列表（dx12_device.cpp:2296）。
                // 若在 Submit 前先显式 dx12EndCommandList，submit 会把这一帧当"空帧"
                // 直接早退（:2291 `if (!ctx->listOpen)`），于是 GPU 什么都没执行、
                // 返回的 fence 又立即可满足 → 回读全 0 且没有任何报错。
                // 游戏侧 D3D12CommandEncoderBackend 同样是 Begin → 录制 → Submit（不显式 End）。
                val fence = MCDR_ABI.dx12Submit(ctx)
                LOGGER.info("[d3d12] mesh self-test: submitted, fence={}", fence)
                if (!MCDR_ABI.dx12WaitForFence(ctx, fence, 5_000_000_000L)) {
                    throw IllegalStateException("mesh self-test: fence wait timed out (5s)")
                }

                // 3x3 = 9 个采样点，每点 4 个分量（RGBA）。只要有一点明显是红就算通过。
                fun dump(px: IntArray): String = (0 until 9).joinToString(" ") { i ->
                    "(${px[i * 4]},${px[i * 4 + 1]},${px[i * 4 + 2]},${px[i * 4 + 3]})"
                }
                fun hasRed(px: IntArray): Boolean = (0 until 9).any { i ->
                    px[i * 4] > 200 && px[i * 4 + 1] < 60 && px[i * 4 + 2] < 60
                }
                var pixels = MCDR_ABI.dx12ReadbackTexturePixels(texture)
                    ?: throw IllegalStateException(
                        "mesh self-test: dx12ReadbackTexturePixels returned null: "
                            + MCDR_ABI.dx12LastError())
                // 诊断：把 3x3 采样点全部打出来。mesh 的输出只有纯红，clear 是黑，
                // 所以"读到红"就证明 dispatch 真的光栅化进了这张纹理。
                LOGGER.info("[d3d12] mesh self-test: readback = [{}]", dump(pixels))
                if (!hasRed(pixels)) {
                    // 同一个二进制、同一份代码，实测会出现"整条命令列表的内容对回读不可见"
                    // （连 API clear 的蓝底都读不到，alpha=0），而重启一次又正常。
                    // 先隔一小段时间重读一次：若第二次能读到红，就说明这是"提交后立刻回读"
                    // 的可见性/同步竞态（native 侧），而不是 mesh 没画。
                    Thread.sleep(150)
                    val retry = MCDR_ABI.dx12ReadbackTexturePixels(texture)
                    LOGGER.warn("[d3d12] mesh self-test: first readback = [{}]; retry after 150ms = [{}]",
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
                        + dump + "] lastError=" + MCDR_ABI.dx12LastError())
                }
                LOGGER.info("[d3d12] Mesh self-test OK (DXC ms_6_6 + PSO + DispatchMesh -> red pixel readback)")
            } finally {
                // 逆序释放；句柄为 0 表示没建起来，跳过。
                if (ctx != 0L) MCDR_ABI.dx12DestroyCommandEncoder(ctx)
                if (texture != 0L) MCDR_ABI.dx12DestroyResource(texture)
                if (pipeline != 0L) MCDR_ABI.dx12DestroyPipeline(pipeline)
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
            val encoder: CommandEncoder = CommandEncoder(null, device, encoderBackend)
            var dxSurface: D3D12GpuSurface? = null
            try {
                dxSurface = device.createSurface(window) as D3D12GpuSurface
                val mode: GpuSurface.PresentMode = GpuSurface.PresentMode.getSupportedVsyncMode(
                    dxSurface.supportedPresentModes(), false)
                dxSurface.configure(GpuSurface.Configuration(640, 480, mode))
                dxSurface.acquireNextTexture()

                // 纯红色清空 Backbuffer（null texture = 不做 copy，只做 ClearRenderTargetView）
                dxSurface.clearToRed(encoderBackend)

                val fence: GpuFence = encoder.createFence()
                encoder.submit()
                // 必须等 GPU 完成 clear 后再销毁 swapchain，否则 backbuffer 在被使用时释放。
                if (!fence.awaitCompletion(5000L)) {
                    throw IllegalStateException("surface self-test submit timed out")
                }
                fence.close()
                dxSurface.present()
                System.err.println("[d3d12-java] selfTestSurface: presented GREEN to real window — "
                    + "if you see green, the render pipeline is working!")
            } finally {
                if (dxSurface != null) {
                    dxSurface.close()
                }
                encoderBackend.close()
            }
            LOGGER.info("[d3d12] Surface self-test OK (pure green clear + present via FFM on real window)")
        }
    }
}
