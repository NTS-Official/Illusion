package dev.nts.expdxr.d3d12.render.advanced.vrs

import com.mojang.renderpearl.api.GpuFormat
import com.mojang.renderpearl.api.textures.GpuTexture
import dev.nts.expdxr.IllusionConfig
import dev.nts.expdxr.d3d12.extern.MCDR_ABI
import dev.nts.expdxr.d3d12.render.advanced.taa.D3D12Velocity
import dev.nts.expdxr.d3d12.render.core.D3D12GpuTexture
import net.minecraft.client.Minecraft
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Tier 2 的屏幕空间着色率图像（SRI）：每帧开画前跑一次 compute，读上一帧深度与运动矢量，按 16×16 tile 判定
 * 是否降采样并写入 `R8_UINT` 着率图，地形批次绑到命令列表（`RSSetShadingRateImage`）。任何一步失败只记一次
 * 日志并永久自关。见 docs/mesh&vrs/README.md §3.3。
 */
object D3D12ShadingRateImage {
    private val LOGGER: Logger = LoggerFactory.getLogger("dxrmc-vrs-sri")

    /** 运行时 tile 尺寸（NVIDIA 为 16×16；能力查询里拿到的就是这个值）。 */
    private const val TILE: Int = 16

    /** 深度快照 usage：SRV + COPY_DST + COPY_SRC，必须写成一行（工具链不接受以 `or` 开头的续行）。 */
    private val DEPTH_USAGE: Int = GpuTexture.USAGE_TEXTURE_BINDING or GpuTexture.USAGE_COPY_DST or GpuTexture.USAGE_COPY_SRC

    /** 深度边沿阈值（千分比）：四角深度相对差超过它即判为几何边沿。 */
    private const val EDGE_PERMILLE: Int = 50

    /** 运动矢量阈值（像素）：超过它即判为"正在动"，不让它变粗。 */
    private const val MOTION_PIXELS: Int = 8

    /**
     * 着色率图像（两张 ping-pong）：compute 不能同时读写同一张纹理，每帧写 A 读 B；`t2` 只能绑视图句柄。
     */
    private val images: LongArray = LongArray(2)
    private val views: LongArray = LongArray(2)

    /** 本帧写入下标；[lastWritten] 是上一帧写完那张（= 本帧的 `t2`，也是要绑的那张）。 */
    private var writeIndex: Int = 0
    private var lastWritten: Int = -1

    /** 上一帧的 SRI 是否仍有效（强制 1X1 的帧必须清掉，否则下一帧 `min(x, 0)` 再糊一帧）。 */
    private var historyValid: Boolean = false
    private var pipeline: Long = 0L
    private var depthTex: Long = 0L
    private var depthView: Long = 0L
    private var encoderCtx: Long = 0L
    private var tilesX: Int = 0
    private var tilesY: Int = 0
    private var pixelW: Int = 0
    private var pixelH: Int = 0

    private var ready: Boolean = false

    /** 一次性失败闩锁：置上之后永久退回逐图元档位。 */
    private var failed: Boolean = false
    private var lastNote: String? = null
    private var frames: Long = 0L

    /** 上一帧的相机位置/朝向；`hasLastCamera == false` 时（首帧、世界切换后）不做跳变判定。 */
    private var lastCamX: Double = 0.0
    private var lastCamY: Double = 0.0
    private var lastCamZ: Double = 0.0
    private var lastYaw: Float = 0.0f
    private var lastPitch: Float = 0.0f
    private var hasLastCamera: Boolean = false

    /** 本帧强制全 1X1：相机跳变或世界切换后的那一帧。 */
    private var forceFine: Boolean = false

    /** 一帧内相机位移超过它（方块）就判为跳变 —— `/tp`、重生、传送门都远超这个量级。 */
    private const val CAMERA_JUMP_BLOCKS: Double = 8.0

    /** 一帧内视角转角超过它（度）也算跳变（相机路径跳帧、视角瞬间切换）。 */
    private const val CAMERA_JUMP_DEGREES: Float = 30.0f

    @JvmStatic
    fun frames(): Long = frames

    /** 本帧是否可用（`prepareFrame` 成功过、且没有失败闩锁）。 */
    @JvmStatic
    fun isReady(): Boolean = ready && !failed && sriEnabled()

    @JvmStatic
    fun tileSize(): String = if (tilesX <= 0) "none" else tilesX.toString() + "x" + tilesY + "@" + pixelW + "x" + pixelH

    /** 每帧开画前调用（挂点：`FrameGraphBuilderProfMixin` 的 `execute` HEAD）：此刻主深度是上一帧最后一笔。 */
    @JvmStatic
    @Synchronized
    fun prepareFrame() {
        ready = false
        if (failed) {
            return
        }
        if (!D3D12VariableShadingRate.isEnabled()) {
            note("AUTO is off or this device refused it; SRI stays idle")
            return
        }
        if (!sriEnabled()) {
            note("`[vrs] sri` is off; falling back to the per-primitive distance bands")
            return
        }
        val target = Minecraft.getInstance().gameRenderer.mainRenderTarget()
        val depth = target.depthTexture as? D3D12GpuTexture
        if (depth == null || depth.isClosed()) {
            note("the main render target has no D3D12 depth texture; SRI stays off this frame")
            return
        }
        val w = depth.getWidth(0)
        val h = depth.getHeight(0)
        if (w <= 0 || h <= 0) {
            return
        }
        // 相机跳变（/tp、重生、传送门）时上一帧的深度与速度已指向别处，本帧只能全 1X1，否则"传送后整帧糊一下"。
        if (detectCameraJump()) {
            forceFine = true
        }
        // 速度通道没开（抗锯齿与 DLSS 都关）不是错误：退化成只看深度 —— 深度视图当第二张 SRV，运动阈值
        // 推到 Int.MAX_VALUE 使"在动就 1X1"永不触发。视图要等 `ensureResources` 建出来（首帧是 0）。
        val motion = D3D12Velocity.previousMotionView()
        if (motion == null) {
            note("no previous motion vectors (velocity pass off); SRI runs depth-only")
        }
        val motionPixels = if (motion == null) Int.MAX_VALUE else motionPixels()
        if (!ensureResources(w, h) || !ensurePipeline() || !ensureEncoder()) {
            return
        }
        val motionHandle = motion?.handle() ?: depthView
        try {
            // 上一帧 submit 之后这条列表就关掉了 —— 每帧都要重新开一条。
            MCDR_ABI.D3D12BeginCommandList(encoderCtx)
            MCDR_ABI.D3D12CopyTextureToTexture(encoderCtx, depth.handle(), depthTex,
                0, 0, 0, 0, 0, 0, w, h)
            val clampBit = if (historyValid && temporalClampEnabled()) 2 else 0
            val dispatched = MCDR_ABI.D3D12DispatchComputeShadingRate(encoderCtx, pipeline,
                depthView, motionHandle, views[1 - writeIndex], images[writeIndex],
                intArrayOf(tilesX, tilesY, TILE, edgePermille(), w, h, motionPixels,
                    (if (forceFine) 1 else 0) or clampBit),
                (tilesX + 7) / 8, (tilesY + 7) / 8, 1)
            if (!dispatched) {
                fail("dx12DispatchComputeShadingRate failed: " + MCDR_ABI.D3D12LastError())
                return
            }
            MCDR_ABI.D3D12Submit(encoderCtx)
            // 强制 1X1 的那帧写的是全 0，历史必须随之作废。
            lastWritten = writeIndex
            writeIndex = 1 - writeIndex
            historyValid = !forceFine
            ready = true
            frames++
            if (forceFine) {
                note("camera jumped or the world changed; this frame's image is all 1X1 (§8.4)")
                forceFine = false
            }
            if (frames == 1L) {
                LOGGER.info("SRI: first frame generated ({}x{} tiles for {}x{})", tilesX, tilesY, w, h)
            }
        } catch (t: Throwable) {
            fail("SRI prepare threw: " + t)
        }
    }

    /**
     * 世界 / 维度切换时把下一张图像强制成全 1X1；只置标志由下一帧 [prepareFrame] 消费（调用方不一定在渲染线程上）。
     */
    @JvmStatic
    fun requestForceFine(reason: String) {
        forceFine = true
        hasLastCamera = false
        // 历史马上不作数：下一帧写全 0，再下一帧若还拿它当 t2 会把整屏 `min(rate, 0)` 压成 1X1（白糊两帧）。
        historyValid = false
        note("$reason; the next generated image is all 1X1")
    }

    /** 比较本帧与上一帧的相机判断跳变；没有上一帧时不判，那由 [requestForceFine] 负责。 */
    private fun detectCameraJump(): Boolean {
        val camera = Minecraft.getInstance().gameRenderer.mainCamera()
        val pos = camera.position()
        val yaw = camera.yRot()
        val pitch = camera.xRot()
        val dx = pos.x - lastCamX
        val dy = pos.y - lastCamY
        val dz = pos.z - lastCamZ
        val moved = dx * dx + dy * dy + dz * dz
        val turned = yawDelta(yaw, lastYaw).coerceAtLeast(kotlin.math.abs(pitch - lastPitch))
        val jumped = hasLastCamera &&
            (moved > CAMERA_JUMP_BLOCKS * CAMERA_JUMP_BLOCKS || turned > CAMERA_JUMP_DEGREES)
        lastCamX = pos.x
        lastCamY = pos.y
        lastCamZ = pos.z
        lastYaw = yaw
        lastPitch = pitch
        hasLastCamera = true
        return jumped
    }

    /** 偏航角差（度），按最短方向折算到 0..180 —— 跨越 ±360 时不会误判成跳变。 */
    private fun yawDelta(current: Float, previous: Float): Float {
        var d = (current - previous) % 360.0f
        if (d > 180.0f) {
            d -= 360.0f
        } else if (d < -180.0f) {
            d += 360.0f
        }
        return kotlin.math.abs(d)
    }

    /**
     * 把着率图像绑到当前命令列表；返回 true 时必须配对调用 [unbind]。
     */
    @JvmStatic
    @Synchronized
    fun bind(ctx: Long): Boolean {
        val img = if (lastWritten >= 0) images[lastWritten] else 0L
        if (!ready || img == 0L) {
            return false
        }
        val bound = MCDR_ABI.D3D12SetShadingRateImage(ctx, img)
        if (!bound) {
            note("dx12SetShadingRateImage(bind) refused: " + MCDR_ABI.D3D12LastError())
        }
        return bound
    }

    /** 解绑（native 会把图像转回 COMMON，不留下 `SHADING_RATE_SOURCE` 残状态）。 */
    @JvmStatic
    @Synchronized
    fun unbind(ctx: Long) {
        if (lastWritten >= 0 && images[lastWritten] != 0L) {
            MCDR_ABI.D3D12SetShadingRateImage(ctx, 0L)
        }
    }

    /** 释放全部资源（关设备/退出世界时调用）。 */
    @JvmStatic
    @Synchronized
    fun reset() {
        releaseResources()
        ready = false
        frames = 0L
        hasLastCamera = false
        forceFine = false
        historyValid = false
        lastWritten = -1
        writeIndex = 0
    }

    /** 只把帧计数清零，**不碰 GPU 资源**：钩子不一定在渲染线程上，不能像 [reset] 那样销毁纹理。 */
    @JvmStatic
    fun resetCounters() {
        frames = 0L
    }

    private fun ensureResources(w: Int, h: Int): Boolean {
        val tx = (w + TILE - 1) / TILE
        val ty = (h + TILE - 1) / TILE
        if (images[0] != 0L && tx == tilesX && ty == tilesY) {
            return true
        }
        // 只重建跟尺寸绑定的那两张图与快照：PSO 与编码器跟分辨率无关，重编译一次 DXIL 没必要。
        releaseImage()
        for (i in 0..1) {
            val img = MCDR_ABI.D3D12CreateShadingRateImage(tx, ty)
            if (img == 0L) {
                fail("dx12CreateShadingRateImage($tx,$ty) failed: " + MCDR_ABI.D3D12LastError())
                return false
            }
            // t2 只能绑视图句柄，所以每张图各建一张 SRV（usage 38 里的 TEXTURE_BINDING 就是给它用的）。
            val view = MCDR_ABI.ID3D12CreateTextureView(img, 0, 1)
            if (view == 0L) {
                MCDR_ABI.ID3D12DestroyResource(img)
                fail("shading rate image view creation failed: " + MCDR_ABI.D3D12LastError())
                return false
            }
            images[i] = img
            views[i] = view
        }
        // 快照必须与主深度**同格式**（D32_FLOAT）：CopyTextureToTexture 不做格式转换，`R32_FLOAT` 会报 "format mismatch"。
        val tex = MCDR_ABI.ID3D12CreateTexture(DEPTH_USAGE, GpuFormat.D32_FLOAT.ordinal, w, h, 1, 1)
        if (tex == 0L) {
            releaseImage()
            fail("depth snapshot texture creation failed: " + MCDR_ABI.D3D12LastError())
            return false
        }
        val view = MCDR_ABI.ID3D12CreateTextureView(tex, 0, 1)
        if (view == 0L) {
            MCDR_ABI.ID3D12DestroyResource(tex)
            releaseImage()
            fail("depth snapshot view creation failed: " + MCDR_ABI.D3D12LastError())
            return false
        }
        depthTex = tex
        depthView = view
        tilesX = tx
        tilesY = ty
        pixelW = w
        pixelH = h
        writeIndex = 0
        lastWritten = -1
        historyValid = false
        return true
    }

    private fun ensurePipeline(): Boolean {
        if (pipeline != 0L) {
            return true
        }
        val hlsl = """
            Texture2D<float> gDepth : register(t0);
            Texture2D<float2> gMotion : register(t1);
            // t2 = 上一帧的着色率图像（时间钳制）：两帧之间只允许变细，否则边缘 1X1/2X2 来回跳会成"呼吸"。
            Texture2D<uint> gPrev : register(t2);
            RWTexture2D<uint> gOut : register(u0);
            cbuffer Params : register(b0) {
                uint gTilesX; uint gTilesY; uint gTile; uint gEdgePermille;
                uint gWidth; uint gHeight; uint gMotionPixels; uint gPad;
            };

            float loadDepth(int2 p) { return gDepth.Load(int3(p, 0)); }
            float2 loadMotion(int2 p) { return gMotion.Load(int3(p, 0)); }

            [numthreads(8, 8, 1)]
            void main(uint3 id : SV_DispatchThreadID) {
                if (id.x >= gTilesX || id.y >= gTilesY) return;

                int2 p0 = int2(id.x * gTile, id.y * gTile);
                int2 p1 = int2(min((int)(gWidth - 1u), p0.x + (int)gTile - 1),
                               min((int)(gHeight - 1u), p0.y + (int)gTile - 1));

                float d00 = loadDepth(p0);
                float d10 = loadDepth(int2(p1.x, p0.y));
                float d01 = loadDepth(int2(p0.x, p1.y));
                float d11 = loadDepth(p1);

                float dmin = min(min(d00, d10), min(d01, d11));
                float dmax = max(max(d00, d10), max(d01, d11));

                float2 m00 = loadMotion(p0);
                float2 m10 = loadMotion(int2(p1.x, p0.y));
                float2 m01 = loadMotion(int2(p0.x, p1.y));
                float2 m11 = loadMotion(p1);
                float mx = max(max(abs(m00.x), abs(m10.x)), max(abs(m01.x), abs(m11.x)));
                float my = max(max(abs(m00.y), abs(m10.y)), max(abs(m01.y), abs(m11.y)));
                float motion = max(mx, my);

                float rel = (dmax - dmin) / max(dmin, 1.0e-4);
                bool extreme = (dmin <= 1.0e-5) || (dmax >= 0.99999) || !(dmax == dmax);
                bool edge = rel > ((float)gEdgePermille * 0.001);
                bool moving = motion > (float)gMotionPixels;

                uint rate = 5u;
                // gPad bit0 = 强制全 1X1（相机跳变 / 世界切换那帧）：上一帧数据已不在同一个地方。
                if ((gPad & 1u) != 0u) {
                    gOut[id.xy] = 0u;
                    return;
                }
                if (extreme || edge || moving) rate = 0u;
                // gPad bit1 = 时间钳制：枚举值即面积序，min 就是"取更细那档"；历史无效时调用方关掉这一位。
                if ((gPad & 2u) != 0u) {
                    rate = min(rate, gPrev.Load(int3(id.xy, 0)));
                }
                gOut[id.xy] = rate;
            }
        """.trimIndent()
        val dxil = MCDR_ABI.D3D12CompileShaderDxc(hlsl, "main", "cs_6_8")
        if (dxil == null) {
            fail("SRI DXC compile failed: " + MCDR_ABI.D3D12LastError())
            return false
        }
        val pso = MCDR_ABI.D3D12CreateComputePipelineShadingRate(dxil)
        if (pso == 0L) {
            fail("dx12CreateComputePipelineShadingRate failed: " + MCDR_ABI.D3D12LastError())
            return false
        }
        pipeline = pso
        LOGGER.info("SRI: compute pipeline ready ({} bytes of DXIL)", dxil.size)
        return true
    }

    private fun ensureEncoder(): Boolean {
        if (encoderCtx != 0L) {
            return true
        }
        val ctx = MCDR_ABI.D3D12CreateCommandEncoder()
        if (ctx == 0L) {
            fail("dx12CreateCommandEncoder failed: " + MCDR_ABI.D3D12LastError())
            return false
        }
        encoderCtx = ctx
        return true
    }

    /** 只放跟分辨率绑定的资源（两张图像 + 深度快照），PSO 与编码器留着。 */
    private fun releaseImage() {
        if (depthView != 0L) {
            MCDR_ABI.ID3D12DestroyResource(depthView)
            depthView = 0L
        }
        if (depthTex != 0L) {
            MCDR_ABI.ID3D12DestroyResource(depthTex)
            depthTex = 0L
        }
        for (i in 0..1) {
            if (views[i] != 0L) {
                MCDR_ABI.ID3D12DestroyResource(views[i])
                views[i] = 0L
            }
            if (images[i] != 0L) {
                MCDR_ABI.ID3D12DestroyResource(images[i])
                images[i] = 0L
            }
        }
        lastWritten = -1
        writeIndex = 0
        historyValid = false
        tilesX = 0
        tilesY = 0
    }

    private fun releaseResources() {
        if (encoderCtx != 0L) {
            MCDR_ABI.D3D12DestroyCommandEncoder(encoderCtx)
            encoderCtx = 0L
        }
        releaseImage()
        if (pipeline != 0L) {
            MCDR_ABI.ID3D12DestroyResource(pipeline)
            pipeline = 0L
        }
    }

    private fun note(message: String) {
        if (lastNote == message) {
            return
        }
        lastNote = message
        LOGGER.info("SRI: {}", message)
    }

    /** `[vrs]` 的开关每帧现读 —— 改 TOML 下一帧生效，不需要重启。 */
    private fun sriEnabled(): Boolean {
        if (!IllusionConfig.SPEC.isLoaded) return true
        return IllusionConfig.VRS_SRI.get()
    }

    private fun edgePermille(): Int {
        if (!IllusionConfig.SPEC.isLoaded) return EDGE_PERMILLE
        return IllusionConfig.VRS_SRI_EDGE_PERMILLE.get().coerceIn(0, 1000)
    }

    private fun motionPixels(): Int {
        if (!IllusionConfig.SPEC.isLoaded) return MOTION_PIXELS
        return IllusionConfig.VRS_SRI_MOTION_PIXELS.get().coerceIn(0, 256)
    }

    /** `[vrs] sriTemporalClamp` —— 每帧现读；关掉等于回到无历史的逐帧行为。 */
    private fun temporalClampEnabled(): Boolean {
        if (!IllusionConfig.SPEC.isLoaded) return true
        return IllusionConfig.VRS_SRI_TEMPORAL_CLAMP.get()
    }

    /** F3 诊断用：这一帧的钳制位到底有没有生效（配置开 + 历史有效）。 */
    @JvmStatic
    fun temporalClampActive(): Boolean = temporalClampEnabled() && historyValid

    private fun fail(message: String) {
        failed = true
        ready = false
        LOGGER.warn("SRI disabled for this session: {}", message)
    }
}


