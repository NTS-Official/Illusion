package dev.nts.expdxr.dx12

import com.mojang.blaze3d.GLFWErrorCapture
import com.mojang.blaze3d.GpuFormat
import com.mojang.blaze3d.buffers.GpuBuffer
import com.mojang.blaze3d.buffers.GpuFence
import com.mojang.blaze3d.pipeline.CompiledRenderPipeline
import com.mojang.blaze3d.shaders.GpuDebugOptions
import com.mojang.blaze3d.shaders.ShaderSource
import com.mojang.blaze3d.shaders.ShaderType
import com.mojang.blaze3d.systems.BackendCreationException
import com.mojang.blaze3d.systems.CommandEncoder
import com.mojang.blaze3d.systems.GpuBackend
import com.mojang.blaze3d.systems.GpuDevice
import com.mojang.blaze3d.systems.GpuSurface
import com.mojang.blaze3d.systems.SurfaceException
import com.mojang.blaze3d.textures.AddressMode
import com.mojang.blaze3d.textures.FilterMode
import com.mojang.blaze3d.textures.GpuSampler
import com.mojang.blaze3d.textures.GpuTexture
import java.nio.ByteBuffer
import java.util.Locale
import java.util.OptionalDouble
import net.minecraft.client.renderer.RenderPipelines
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Direct3D 12 [GpuBackend] implementation.
 *
 * Mirrors `com.mojang.blaze3d.vulkan.VulkanBackend`'s contract:
 * - [setWindowHints] runs inside GLFW's error scope before the window is created (no hints needed for DX12).
 * - [handleWindowCreationErrors] is only called when `glfwCreateWindow` returns 0.
 * - [createDevice] is called once the window exists; it runs the full D3D12 self-test chain and, when it
 *   passes, returns a live [Dx12Device] that drives the vanilla render flow (P6+).
 */
class Dx12Backend : GpuBackend {
    override fun getName(): String = "DX12"

    override fun setWindowHints() {
        // DX12 needs no GLFW window hints (Vulkan sets NO_API; GL sets its context hints).
    }

    @Throws(BackendCreationException::class)
    override fun handleWindowCreationErrors(error: GLFWErrorCapture.Error) {
        if (error != null) {
            throw BackendCreationException(
                String.format(Locale.ROOT, "GLFW_ERROR: 0x%X", error.error),
                BackendCreationException.Reason.GLFW_ERROR)
        }
        throw BackendCreationException(
            "Failed to create window for DX12", BackendCreationException.Reason.GLFW_ERROR)
    }

    @Throws(BackendCreationException::class)
    override fun createDevice(window: Long, defaultShaderSource: ShaderSource,
        debugOptions: GpuDebugOptions, criticalShaderLoader: Runnable): GpuDevice {
        // P6: createDevice returns a real, live GpuDevice. The game then drives
        // the full vanilla render flow through our backend (RenderSystem
        // initRenderer, surface, per-frame render passes, present) with no
        // GL/Vulkan fallback. Self-tests run against the live device; only a
        // failure closes it and rethrows so the game can fall back.
        try {
            val device = Dx12Device(defaultShaderSource)
            try {
                selfTestJavaResources(device)
                selfTestCommandLayer(device)
                selfTestPipelines(device)
                selfTestSurface(device, window)
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
            LOGGER.error("[dx12] D3D12 self-test failed", t)
            throw BackendCreationException(
                "DX12 self-test failed: " + t, BackendCreationException.Reason.OTHER)
        }
    }
    companion object {
        private val LOGGER: Logger = LoggerFactory.getLogger("gl4dx12")

        /**
         * Exercise every P2 resource method through the real JNI path so a broken
         * native binding fails loudly here instead of during vanilla initRenderer.
         */
        private fun selfTestJavaResources(device: Dx12Device) {
            // texture: RGBA8_UNORM, 64x64, TEXTURE_BINDING|RENDER_ATTACHMENT
            val texture: GpuTexture = device.createTexture("dx12-selftest",
                GpuTexture.USAGE_TEXTURE_BINDING or GpuTexture.USAGE_RENDER_ATTACHMENT,
                GpuFormat.RGBA8_UNORM, 64, 64, 1, 1)
            // sampler: REPEAT/REPEAT, NEAREST/NEAREST, aniso=1
            val sampler: GpuSampler = device.createSampler(AddressMode.REPEAT, AddressMode.REPEAT,
                FilterMode.NEAREST, FilterMode.NEAREST, 1, OptionalDouble.of(16.0))
            // buffer: MAP_WRITE, map -> write -> unmap
            val buffer: GpuBuffer = device.createBuffer({ "dx12-selftest" },
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
            LOGGER.info("[dx12] Java resource self-test OK (texture/buffer/sampler/view via FFM)")
        }

        /**
         * Exercise the P3 command layer through the real JNI path: record
         * uploads/copies into a command list, submit through the fence, and verify
         * both a buffer copy readback and a texture write/readback round-trip.
         */
        private fun selfTestCommandLayer(device: Dx12Device) {
            val size = 256
            val src: GpuBuffer = device.createBuffer({ "dx12-selftest-src" },
                GpuBuffer.USAGE_COPY_DST or GpuBuffer.USAGE_COPY_SRC, size.toLong())
            val dst: GpuBuffer = device.createBuffer({ "dx12-selftest-dst" },
                GpuBuffer.USAGE_MAP_READ, size.toLong())
            val data: ByteBuffer = ByteBuffer.allocate(size)
            for (i in 0 until size) {
                data.put(i, (i and 0xFF).toByte())
            }

            val texSize = 8
            val tex: GpuTexture = device.createTexture("dx12-selftest-tex",
                GpuTexture.USAGE_TEXTURE_BINDING, GpuFormat.RGBA8_UNORM, texSize, texSize, 1, 1)
            val texDst: GpuBuffer = device.createBuffer({ "dx12-selftest-texdst" },
                GpuBuffer.USAGE_MAP_READ, texSize.toLong() * texSize * 4)
            val texData: ByteBuffer = ByteBuffer.allocate(texSize * texSize * 4)
            for (i in 0 until texData.capacity()) {
                texData.put(i, (i and 0xFF).toByte())
            }

            val encoder: Dx12CommandEncoderBackend = Dx12CommandEncoderBackend()
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
                    System.err.println("[dx12-java] self-test: reading dst readback, size=" + size
                        + " dst.usage=" + Integer.toBinaryString(dst.usage()))
                    System.err.flush()
                    for (i in 0 until size) {
                        val actual = read.get(i).toInt() and 0xFF
                        val expected = i and 0xFF
                        if (actual != expected) {
                            // Dump surrounding bytes for diagnosis
                            System.err.printf(
                                "[dx12-java] mismatch at %d: expected=0x%02X actual=0x%02X",
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
            LOGGER.info("[dx12] Command layer self-test OK (submit/fence/copy/readback via FFM)")
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
        private fun selfTestPipelines(device: Dx12Device) {
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
            LOGGER.info("[dx12] Pipeline self-test OK (GLSL->SPIR-V->HLSL->DXBC->PSO for core/gui + core/position_tex_color)")
        }
        /**
         * P5：创建 DXGI swapchain（绑定真实窗口 HWND）→ 配置 → acquire →
         * 用纯红色清空 Backbuffer → submit → present。
         * 首帧会向 MC 真实窗口 present 一次红色，用于验证渲染链路是否打通。
         * 随后回退不影响游戏（游戏有自己的 surface/lifecycle）。
         *
         * 注意：使用 Dx12GpuSurface 而非 GpuSurface 包装类，因为 clearToRed 走
         * 非 blit 路径（null srcTex = ClearRenderTargetView），绕过了 hasBlittedTexture
         * 标志，GpuSurface.present() 会因此抛出 IllegalStateException。
         */
        @Throws(SurfaceException::class)
        private fun selfTestSurface(device: Dx12Device, window: Long) {
            val encoderBackend: Dx12CommandEncoderBackend = Dx12CommandEncoderBackend()
            val encoder: CommandEncoder = CommandEncoder(null, device, encoderBackend)
            var dxSurface: Dx12GpuSurface? = null
            try {
                dxSurface = device.createSurface(window) as Dx12GpuSurface
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
                System.err.println("[dx12-java] selfTestSurface: presented GREEN to real window — "
                    + "if you see green, the render pipeline is working!")
            } finally {
                if (dxSurface != null) {
                    dxSurface.close()
                }
                encoderBackend.close()
            }
            LOGGER.info("[dx12] Surface self-test OK (pure green clear + present via FFM on real window)")
        }
    }
}
