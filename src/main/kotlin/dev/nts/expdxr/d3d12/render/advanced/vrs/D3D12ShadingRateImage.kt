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
 * P235：Tier 2 的屏幕空间着色率图像（SRI）。
 *
 * 每帧在**开画之前**跑一次 compute：读上一帧的深度与运动矢量，按 16×16 tile 判定
 * "这一块像素该不该降采样"，把结果写进一张 `R8_UINT` 的着率图像；地形绘制时把它绑到
 * 命令列表上（`RSSetShadingRateImage`），由运行时的三级组合（基率 / 逐图元 / 图像，
 * 全部取 `MIN`）取**最细**的那一票。
 *
 * 为什么用上一帧的数据：本帧的深度要到主 pass 才写出来，而着率图像必须在主 pass 开画前
 * 就绑好。上一帧的深度与它自己的运动矢量是同一时刻的，正好成对；相机在一帧内的相对
 * 变化（重投影误差）由"运动矢量过大就退回 1X1"这条规则吃掉。
 *
 * 判定规则（任一命中即 1X1，否则 2X2）：
 * - 深度落在两极（天空 `≈1`、近平面 `≈0`）—— 天空没有着色细节，但它的深度梯度会
 *   在轮廓处制造大面积误判，宁可保守；
 * - tile 内四角深度相对差 > `edgePermille/1000`（默认 0.05，即 5%）—— 剪影/棱边；
 * - tile 内四角运动矢量的绝对值 > `motionPx`（默认 8 像素）—— 快速运动或遮挡变化。
 *
 * 失败策略与 [D3D12Velocity] 一致：任何一步失败只记一次日志并**永久自关**，画面退回
 * P233 的逐图元档位，绝不影响正常渲染。
 */
object D3D12ShadingRateImage {
    private val LOGGER: Logger = LoggerFactory.getLogger("dxrmc-vrs-sri")

    /** 运行时 tile 尺寸（NVIDIA 为 16×16；能力查询里拿到的就是这个值）。 */
    private const val TILE: Int = 16

    /**
     * 深度快照的 usage：SRV（compute 读）+ COPY_DST（从主深度拷进来）+ COPY_SRC（诊断回读）。
     *
     * 注：必须写成一行 —— 本工具链不接受"续行以 `or` 开头"的写法（见 `D3D12Velocity.USAGE`）。
     */
    private val DEPTH_USAGE: Int = GpuTexture.USAGE_TEXTURE_BINDING or GpuTexture.USAGE_COPY_DST or GpuTexture.USAGE_COPY_SRC

    /** 深度边沿阈值（千分比）：四角深度相对差超过它即判为几何边沿。 */
    private const val EDGE_PERMILLE: Int = 50

    /** 运动矢量阈值（像素）：超过它即判为"正在动"，不让它变粗。 */
    private const val MOTION_PIXELS: Int = 8

    private var image: Long = 0L
    private var pipeline: Long = 0L
    private var depthTex: Long = 0L
    private var depthView: Long = 0L
    private var encoderCtx: Long = 0L
    private var tilesX: Int = 0
    private var tilesY: Int = 0
    private var pixelW: Int = 0
    private var pixelH: Int = 0

    /** 本帧的着率图像是否已经生成好、可以绑定。 */
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

    /** 本帧强制全 1X1（§8.4 第 2/3 条）：相机跳变或世界切换后的那一帧。 */
    private var forceFine: Boolean = false

    /** 一帧内相机位移超过它（方块）就判为跳变 —— `/tp`、重生、传送门都远超这个量级。 */
    private const val CAMERA_JUMP_BLOCKS: Double = 8.0

    /** 一帧内视角转角超过它（度）也算跳变（相机路径跳帧、视角瞬间切换）。 */
    private const val CAMERA_JUMP_DEGREES: Float = 30.0f

    /** 已生成好的帧数（诊断用）。 */
    @JvmStatic
    fun frames(): Long = frames

    /** 本帧是否可用（`prepareFrame` 成功过、且没有失败闩锁）。 */
    @JvmStatic
    fun isReady(): Boolean = ready && !failed && sriEnabled()

    /** 着色率图像的尺寸描述，供 F3 诊断行；`none` = 还没生成过。 */
    @JvmStatic
    fun tileSize(): String = if (tilesX <= 0) "none" else tilesX.toString() + "x" + tilesY + "@" + pixelW + "x" + pixelH

    /**
     * 每帧开画前调用（挂点：`FrameGraphBuilderProfMixin` 的 `execute` HEAD）。
     *
     * 此刻本帧的 clear 还没跑，主深度里仍是上一帧最后一笔 —— 正好是我们要的输入。
     */
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
        // §8.4 第 2 条：相机跳变（/tp、重生、传送门、相机路径跳帧）时上一帧的深度与速度
        // 已经指向别处，这一帧的 SRI 只能是全 1X1 —— 否则会看到"传送后整帧糊一下"。
        if (detectCameraJump()) {
            forceFine = true
        }
        // 速度通道没开（抗锯齿与 DLSS 都关时根本没有速度 pass）不是错误：退化成"只看深度"，
        // 把深度视图当第二张 SRV 传进去，并把运动阈值推到 Int.MAX_VALUE，使"在动就 1X1"永不触发。
        // 注意：这张视图要等 `ensureResources` 建出来，所以不能在它之前取句柄（首帧会是 0）。
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
            // 上一帧 submit 之后这条列表就关掉了 —— 每帧要先重新开一条（与 backend 的帧循环同构）。
            MCDR_ABI.D3D12BeginCommandList(encoderCtx)
            MCDR_ABI.D3D12CopyTextureToTexture(encoderCtx, depth.handle(), depthTex,
                0, 0, 0, 0, 0, 0, w, h)
            val dispatched = MCDR_ABI.D3D12DispatchComputeShadingRate(encoderCtx, pipeline,
                depthView, motionHandle, image,
                intArrayOf(tilesX, tilesY, TILE, edgePermille(), w, h, motionPixels,
                    if (forceFine) 1 else 0),
                (tilesX + 7) / 8, (tilesY + 7) / 8, 1)
            if (!dispatched) {
                fail("dx12DispatchComputeShadingRate failed: " + MCDR_ABI.D3D12LastError())
                return
            }
            MCDR_ABI.D3D12Submit(encoderCtx)
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
     * §8.4 第 3 条：世界切换 / 维度切换时，把下一张图像强制成全 1X1。
     *
     * <p>不在这里 release 资源 —— 调用方（`ClientPlayerNetworkEvent.LoggingOut/LoggingIn`）
     * 不一定在渲染线程上，销毁纹理会是 D.10 那类悬空指针；只置一个标志，由下一帧的
     * [prepareFrame] 在渲染线程上消费。
     */
    @JvmStatic
    fun requestForceFine(reason: String) {
        forceFine = true
        hasLastCamera = false
        note("$reason; the next generated image is all 1X1")
    }

    /**
     * §8.4 第 2 条：比较这一帧与上一帧的相机，判断是否发生"跳变"。
     *
     * <p>没有上一帧（首帧、世界切换后）时不判跳变 —— 那种情况由 [requestForceFine] 负责，
     * 否则每次进世界都会白糊一帧。
     */
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
     * 把着率图像绑到当前命令列表（由 [D3D12VariableShadingRate.scope] 在进入地形批次时调用）。
     *
     * 返回 true 表示真的绑上了 —— 调用方必须配对调用 [unbind]。
     */
    @JvmStatic
    @Synchronized
    fun bind(ctx: Long): Boolean {
        if (!ready || image == 0L) {
            return false
        }
        val bound = MCDR_ABI.D3D12SetShadingRateImage(ctx, image)
        if (!bound) {
            note("dx12SetShadingRateImage(bind) refused: " + MCDR_ABI.D3D12LastError())
        }
        return bound
    }

    /** 解绑（native 会把图像转回 COMMON，不留下 `SHADING_RATE_SOURCE` 残状态）。 */
    @JvmStatic
    @Synchronized
    fun unbind(ctx: Long) {
        if (image != 0L) {
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
    }

    /**
     * 只把帧计数清零，**不碰 GPU 资源**。
     *
     * <p>离开存档时由 [D3D12VariableShadingRate.resetCounters] 调用（那个钩子不一定在渲染线程上，
     * 所以这里不能像 [reset] 那样去销毁纹理）。下一帧 `prepareFrame` 会照常重新计数。
     */
    @JvmStatic
    fun resetCounters() {
        frames = 0L
    }

    private fun ensureResources(w: Int, h: Int): Boolean {
        val tx = (w + TILE - 1) / TILE
        val ty = (h + TILE - 1) / TILE
        if (image != 0L && tx == tilesX && ty == tilesY) {
            return true
        }
        // 只重建跟尺寸绑定的那张图与快照：PSO 与编码器跟分辨率无关，重编译一次 DXIL 没必要。
        releaseImage()
        val img = MCDR_ABI.D3D12CreateShadingRateImage(tx, ty)
        if (img == 0L) {
            fail("dx12CreateShadingRateImage($tx,$ty) failed: " + MCDR_ABI.D3D12LastError())
            return false
        }
        // 快照必须与主深度**同格式**（D32_FLOAT）：native 的 CopyTextureToTexture 不做格式转换，
        // 实测 `R32_FLOAT` 目标会直接报 "format mismatch"。深度格式的 SRV 在 HLSL 侧照样声明成
        // `Texture2D<float>` + `Load`，语义就是我们需要的线性深度。
        val tex = MCDR_ABI.ID3D12CreateTexture(DEPTH_USAGE, GpuFormat.D32_FLOAT.ordinal, w, h, 1, 1)
        if (tex == 0L) {
            MCDR_ABI.ID3D12DestroyResource(img)
            fail("depth snapshot texture creation failed: " + MCDR_ABI.D3D12LastError())
            return false
        }
        val view = MCDR_ABI.ID3D12CreateTextureView(tex, 0, 1)
        if (view == 0L) {
            MCDR_ABI.ID3D12DestroyResource(tex)
            MCDR_ABI.ID3D12DestroyResource(img)
            fail("depth snapshot view creation failed: " + MCDR_ABI.D3D12LastError())
            return false
        }
        image = img
        depthTex = tex
        depthView = view
        tilesX = tx
        tilesY = ty
        pixelW = w
        pixelH = h
        return true
    }

    private fun ensurePipeline(): Boolean {
        if (pipeline != 0L) {
            return true
        }
        val hlsl = """
            Texture2D<float> gDepth : register(t0);
            Texture2D<float2> gMotion : register(t1);
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
                // gPad bit0 = 强制全 1X1（相机跳变 / 世界切换那一帧，§8.4 第 2、3 条）：
                // 上一帧的深度与速度已经不在同一个地方，任何粗化都会让"传送后整帧糊一下"出现。
                if ((gPad & 1u) != 0u) {
                    gOut[id.xy] = 0u;
                    return;
                }
                if (extreme || edge || moving) rate = 0u;
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

    /** 只放跟分辨率绑定的资源（图像 + 深度快照），PSO 与编码器留着。 */
    private fun releaseImage() {
        if (depthView != 0L) {
            MCDR_ABI.ID3D12DestroyResource(depthView)
            depthView = 0L
        }
        if (depthTex != 0L) {
            MCDR_ABI.ID3D12DestroyResource(depthTex)
            depthTex = 0L
        }
        if (image != 0L) {
            MCDR_ABI.ID3D12DestroyResource(image)
            image = 0L
        }
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

    /**
     * P237：`[vrs]` 的三个开关每帧现读 —— 改 `run\config\dxr_mc-client.toml` 下一帧生效，
     * 不需要重启（与 `msBand()` 读距离带的方式一致）。配置尚未加载时用默认值。
     */
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

    private fun fail(message: String) {
        failed = true
        ready = false
        LOGGER.warn("SRI disabled for this session: {}", message)
    }
}


