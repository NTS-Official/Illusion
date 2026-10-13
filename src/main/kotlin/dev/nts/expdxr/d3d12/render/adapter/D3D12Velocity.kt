package dev.nts.expdxr.d3d12.render.adapter

import com.mojang.blaze3d.GpuFormat
import com.mojang.blaze3d.buffers.GpuBuffer
import com.mojang.blaze3d.textures.GpuTexture
import dev.nts.expdxr.d3d12.extern.MCDR_ABI
import dev.nts.expdxr.d3d12.extern.MCDRPipelineDesc
import dev.nts.expdxr.d3d12.render.backend.D3D12GpuSurface
import dev.nts.expdxr.d3d12.render.draw.D3D12GpuBuffer
import dev.nts.expdxr.d3d12.render.draw.D3D12GpuTexture
import dev.nts.expdxr.d3d12.render.draw.D3D12GpuTextureView
import java.nio.ByteOrder
import java.nio.FloatBuffer
import net.minecraft.client.Minecraft
import org.joml.Matrix4f
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * P76a 增量 2：**独立的全屏速度 pass**（用户 m04002 裁决）。
 *
 * 为什么不用增量 1 的 MRT 路线：vanilla 把物体变换**烘进顶点**，`DynamicTransforms.ModelViewMat`
 * 只是相机 model-view，所以"多挂一个 RTV + 2-target 管线变体"拿不到逐物体运动；而独立的
 * 全屏 pass 可以用主深度 + 上一帧 view-projection 直接算出**相机重投影**速度（对静态几何严格
 * 正确，这也是 TAAU 的第一块砖）。增量 1 的 MRT 代码已按裁决移除。
 *
 * 管线走 raw ABI（`MCDRPipelineDesc` 直接吃 HLSL 源码，native 用 `D3DCompile(...,"main",...)`
 * 编译），绑定只有两项：
 *  - entry 0 = CBV（`register(b0)`，root descriptor）→ 256 B 参数块：
 *    `PrevViewProj` + `InvCurrViewProj` + `ScreenSize`；
 *  - entry 1 = SRV + static sampler（`register(t1)` / `register(s1)`）→ 主目标**深度**纹理视图。
 *    native 的 static sampler 是 POINT/WRAP，`createTextureView` 会把深度格式重映射成
 *    `R32_FLOAT`（`D32_FLOAT`）或 `R32_FLOAT_X8X24_TYPELESS`（`D32_FLOAT_S8X24_UINT`）。
 *
 * 输出：`RG16_FLOAT` 乒乓纹理（xy = 屏幕空间运动，单位**像素**，`uv - prevUv` 乘屏幕尺寸）。
 * 乒乓里"上一帧速度"这一半本轮**还不被消费**（那是 TAAU resolve 的历史纹理），保留是为了
 * 让 P77 的 resolve 直接接上。
 *
 * 数学（PS 内，逐像素）：
 *  1. `uv = SV_Position.xy / ScreenSize`（全屏三角形正好覆盖 [0,1]²，uv 即纹素中心）；
 *  2. `ndc = (uv.x*2-1, 1-uv.y*2, depth, 1)`，用**本帧抖动过的**投影的逆矩阵反投影回世界；
 *  3. 用上一帧 view-projection 投影 → `prevUv`；`motion = (uv - prevUv) * ScreenSize`。
 *
 * 明确不做（P76b）：逐物体身份（mixin 抓物体矩阵）→ 走动实体的真速度；本轮所有几何
 * （含移动实体）都按"静态世界"处理，所以移动物会有拖影。天空/清屏值（depth 恰为 0 或 1）
 * 直接输出 0 速度，避免远平面反投影出垃圾。
 *
 * 开关：`-Pdxrmc.temporal.velocity=true`（默认 **false**）。任何失败（建纹理/编译管线/取深度）
 * 都只 warn 一次并自我关闭，绝不影响正常渲染 —— 与 P75 的"时间性数据缺失只能让效果退化"一致。
 */
object D3D12Velocity {
    private val LOGGER: Logger = LoggerFactory.getLogger("dxrmc-velocity")

    /** 开关来源（Gradle 侧转发成 Loom run config 的系统属性，同 `dxrmc.temporal`）。 */
    private const val VELOCITY_PROPERTY: String = "dxrmc.temporal.velocity"

    /** 速度格式：两个 16 位有符号浮点（像素单位够用，且是 TAAU 的常见选择；native ordinal 41）。 */
    val FORMAT: GpuFormat = GpuFormat.RG16_FLOAT

    /*
     * 速度纹理的 usage：RTV（本 pass 写）+ SRV（P77 resolve 读）+ COPY_SRC（回读诊断）。
     *
     * 注：这一行**必须写成一行**。本工具链（Kotlin 2.2 / K2）不接受"续行以运算符/中缀函数
     * 开头"的写法 —— 把 `or` 放在行首会报 `Syntax error: Expecting member declaration.`。
     */
    private val USAGE: Int = GpuTexture.USAGE_RENDER_ATTACHMENT or GpuTexture.USAGE_TEXTURE_BINDING or GpuTexture.USAGE_COPY_SRC

    /** MC `PrimitiveTopology.TRIANGLES` 的 ordinal（native `toPrimitiveTopology` 里 4 → TRIANGLELIST）。 */
    private const val TOPOLOGY_TRIANGLES: Int = 4

    /** 写掩码：RG 两位都写。 */
    private const val WRITE_MASK_RG: Int = 0x3

    /**
     * 参数块大小。D3D12 要求常量缓冲尺寸按 256 B 对齐，所以即使只有 144 B 有效数据
     * （两个 mat4 + vec2）也按 256 申请。
     */
    private const val CBV_SIZE: Long = 256L

    private const val LOG_INTERVAL_MILLIS: Long = 5_000L

    /** 总开关。默认 false：速度附件在没有 resolve 之前只是"多写一张纹理"。 */
    @Volatile
    var enabled: Boolean = "true" == System.getProperty(VELOCITY_PROPERTY)

    /** 乒乓速度纹理（current = 本帧写入，previous = 上一帧结果，供 P77 resolve 当历史用）。 */
    private var current: D3D12GpuTexture? = null
    private var previous: D3D12GpuTexture? = null
    private var width: Int = 0
    private var height: Int = 0

    private var pipeline: Long = 0L

    /** 管线创建失败的原因（只记一次，避免每帧刷屏/重复尝试）。 */
    private var pipelineError: String? = null

    /** 参数块（本模组自建缓冲，label 用于 native 诊断）。 */
    private var uniform: D3D12GpuBuffer? = null

    /** 主深度纹理的 SRV 视图 + 它属于哪张纹理（主目标换尺寸/重建时重取）。 */
    private var depthView: D3D12GpuTextureView? = null
    private var depthViewFor: Long = 0L

    private var frameIndex: Long = 0L
    private var passCount: Long = 0L
    private var skippedCount: Long = 0L
    private var lastLogMillis: Long = 0L

    /** 上一次失败的原因（自我关闭时写日志用）。 */
    private var disabledReason: String? = null

    /** 是否处于"可以录制"的状态（时间性总开关也必须打开）。 */
    fun isActive(): Boolean = enabled && D3D12Temporal.enabled

    /** 本帧速度纹理的 native handle（0 = 还没建）。 */
    @Synchronized
    fun currentHandle(): Long = this.current?.handle() ?: 0L

    /** 上一帧速度纹理的 native handle（0 = 还没有历史）。 */
    @Synchronized
    fun previousHandle(): Long = this.previous?.handle() ?: 0L

    /** 释放全部资源（关设备/退出世界/错误自检时调用）。 */
    @Synchronized
    fun reset() {
        passCount = 0L
        skippedCount = 0L
        frameIndex = 0L
        disabledReason = null
        releaseResources()
    }

    /**
     * 每帧开始时调用（由 [D3D12Temporal.beginFrame] 驱动）：乒乓交换 —— 本帧将要写的那张
     * 变成"上一帧"。
     */
    @Synchronized
    fun beginFrame() {
        if (!enabled) {
            return
        }
        val swap = this.previous
        this.previous = this.current
        this.current = swap
        frameIndex++
    }

    /**
     * 录制速度 pass。由 [D3D12GpuSurface.blitFromTexture] 在 blit 之前调用
     * （那一刻本帧的渲染已全部完成、主深度仍然有效，且命令列表由编码器自己打开）。
     *
     * @return true = 真的录了一次 draw。
     */
    @Synchronized
    fun record(ctx: Long, device: D3D12Device?): Boolean {
        if (!isActive()) {
            return false
        }
        val minecraft = Minecraft.getInstance()
        // 主渲染目标在 `GameRenderer` 上（`GameRenderer.java:680 public RenderTarget mainRenderTarget()`），
        // **不是** `Minecraft` 的成员 —— 反编译源码里 `Minecraft.java:673` 就是这么取的。
        val target = minecraft.gameRenderer.mainRenderTarget()
        val depth = target.depthTexture as? D3D12GpuTexture ?: return false
        if (depth.isClosed()) {
            return false
        }
        val w = depth.getWidth(0)
        val h = depth.getHeight(0)
        if (w <= 0 || h <= 0) {
            return false
        }
        // 本帧的相机视图矩阵，以及上一帧的：来自 terrain 的 ChunkSection 记录
        // （vanilla 把相机 model-view 写进每个区块的 ChunkSection，见类注释）。
        val view = D3D12Temporal.currentCameraView()
        val previousView = D3D12Temporal.previousCameraView()
        val curProjection = D3D12Temporal.currentProjection()
        val prevProjection = D3D12Temporal.previousProjection()
        if (view == null || previousView == null || curProjection == null || prevProjection == null) {
            // 第一帧（还没有历史）或本帧没有任何区块绘制：跳过，速度纹理保持上一帧内容。
            countSkip("no history/view")
            return false
        }
        if (device == null) {
            countSkip("no device")
            return false
        }
        if (!ensurePipeline(device)) {
            return false
        }
        if (!ensureResources(device, w, h)) {
            return false
        }
        val uniformBuffer = this.uniform ?: return false
        val color = this.current ?: return false
        if (!ensureDepthView(depth)) {
            return false
        }
        val depthSrv = this.depthView ?: return false

        // 本帧的投影是**抖动过**的（P75 把 jitter 加进了 [8]/[9]），深度就是用那个投影渲出来的，
        // 所以反投影必须用同一个抖动后的矩阵，否则会有半个像素的系统误差。
        val jittered = D3D12Temporal.jitterProjection(curProjection,
            D3D12Temporal.clipJitterX(w), D3D12Temporal.clipJitterY(h))
        val currViewProj = D3D12Temporal.multiply(jittered, view)
        val prevViewProj = D3D12Temporal.multiply(prevProjection, previousView)
        val invCurr = invert(currViewProj) ?: run {
            countSkip("singular currViewProj")
            return false
        }
        if (!writeParameters(uniformBuffer, prevViewProj, invCurr, w, h)) {
            return false
        }

        MCDR_ABI.dx12BeginRenderPass(ctx, longArrayOf(color.handle()), intArrayOf(0),
            byteArrayOf(1), floatArrayOf(0.0f, 0.0f, 0.0f, 0.0f),
            0L, 0, 0.toByte(), 0.0, 0, 0, w, h)
        // P69 教训：viewport/scissor 是命令列表状态，每个 pass 都必须显式重设。
        MCDR_ABI.dx12SetViewport(ctx, 0, 0, w, h)
        MCDR_ABI.dx12SetScissor(ctx, 0, 0, w, h)
        if (!MCDR_ABI.dx12SetPipeline(ctx, this.pipeline, false)) {
            disable("dx12SetPipeline failed: " + MCDR_ABI.dx12LastError())
            MCDR_ABI.dx12EndRenderPass(ctx)
            return false
        }
        val pushed = MCDR_ABI.dx12PushDescriptors(ctx,
            intArrayOf(0, 1),
            longArrayOf(uniformBuffer.handle(), 0L),
            longArrayOf(0L, 0L),
            longArrayOf(CBV_SIZE, 0L),
            intArrayOf(0, 0),
            longArrayOf(0L, depthSrv.handle()))
        if (!pushed) {
            disable("dx12PushDescriptors failed: " + MCDR_ABI.dx12LastError())
            MCDR_ABI.dx12EndRenderPass(ctx)
            return false
        }
        if (!MCDR_ABI.dx12Draw(ctx, 3, 1, 0, 0)) {
            disable("dx12Draw failed: " + MCDR_ABI.dx12LastError())
        }
        MCDR_ABI.dx12EndRenderPass(ctx)
        passCount++
        logStatus()
        return true
    }

    /**
     * 本帧的相机 model-view 矩阵来源：[D3D12Temporal.currentCameraView] /
     * [D3D12Temporal.previousCameraView]（terrain 的 `ChunkSection` 记录）。
     */
    private fun ensureResources(device: D3D12Device, w: Int, h: Int): Boolean {
        if (this.current != null && this.previous != null && this.width == w && this.height == h) {
            return true
        }
        releaseResources()
        return try {
            this.current = D3D12GpuTexture(USAGE, "DXRMC velocity", FORMAT, w, h, 1, 1)
            this.previous = D3D12GpuTexture(USAGE, "DXRMC velocity (previous)", FORMAT, w, h, 1, 1)
            // `D3D12Device.createBuffer` 覆写声明返回接口类型 `GpuBuffer`（要匹配 GpuDevice 接口），
            // 这里强转回实现类以便 `dx12PushDescriptors` 拿到原生 handle。
            this.uniform = device.createBuffer({ "DXRMC velocity params" },
                GpuBuffer.USAGE_UNIFORM or GpuBuffer.USAGE_MAP_WRITE, CBV_SIZE) as D3D12GpuBuffer
            this.width = w
            this.height = h
            LOGGER.info("[d3d12] velocity: allocated {}x{} {} (ping-pong)", w, h, FORMAT)
            true
        } catch (t: Throwable) {
            releaseResources()
            disable("resource allocation failed: " + t)
            false
        }
    }

    /** 主深度纹理的 SRV 视图（同一张纹理只建一次；主目标重建后自动重取）。 */
    private fun ensureDepthView(depth: D3D12GpuTexture): Boolean {
        val handle = depth.handle()
        if (this.depthView != null && this.depthViewFor == handle) {
            return true
        }
        this.depthView?.close()
        this.depthView = null
        this.depthViewFor = 0L
        return try {
            this.depthView = D3D12GpuTextureView(depth, 0, 1)
            this.depthViewFor = handle
            LOGGER.info("[d3d12] velocity: depth SRV view created for texture {:#x} (fmt={})",
                handle, depth.getFormat())
            true
        } catch (t: Throwable) {
            disable("depth view creation failed: " + t)
            false
        }
    }

    private fun ensurePipeline(device: D3D12Device): Boolean {
        if (this.pipeline != 0L) {
            return true
        }
        if (this.pipelineError != null) {
            return false
        }
        val desc = MCDRPipelineDesc()
            .shaders(VERTEX_HLSL, PIXEL_HLSL)
            .topology(TOPOLOGY_TRIANGLES, false, 0)
            .addColorTarget(MCDRPipelineDesc.ColorTarget()
                .format(FORMAT.ordinal)
                .writeMask(WRITE_MASK_RG))
            .addBinding(0, 0)   // CBV → register(b0)
            .addBinding(1, 1)   // SRV + static sampler → register(t1)/(s1)
        val handle = MCDR_ABI.dx12CreateGraphicsPipeline(desc)
        if (handle == 0L) {
            this.pipelineError = MCDR_ABI.dx12LastError()
            disable("dx12CreateGraphicsPipeline failed: " + this.pipelineError)
            return false
        }
        this.pipeline = handle
        LOGGER.info("[d3d12] velocity: pipeline compiled (handle={:#x})", handle)
        return true
    }

    /**
     * 写参数块。CBV 布局（std140 兼容）：`float4x4 PrevViewProj`(0) + `float4x4 InvCurrViewProj`(64)
     * + `float2 ScreenSize`(128)，共 144 B，剩余补齐到 256 B。
     *
     * 用写映射直接写（usage 含 MAP_WRITE，UPLOAD 堆 CPU 可见），不走 `writeToBuffer`：
     * 那条路会经过 [D3D12Temporal.interceptWrite]（label 不匹配虽会放行，但没必要）。
     */
    private fun writeParameters(buffer: D3D12GpuBuffer, prevViewProj: FloatArray,
        invCurrViewProj: FloatArray, w: Int, h: Int): Boolean {
        return try {
            buffer.map(0L, CBV_SIZE, false, true).use { view ->
                val fb: FloatBuffer = view.data().order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                fb.put(prevViewProj, 0, 16)
                fb.put(invCurrViewProj, 0, 16)
                fb.put(w.toFloat())
                fb.put(h.toFloat())
                while (fb.position() < 64) {
                    fb.put(0.0f)
                }
            }
            true
        } catch (t: Throwable) {
            disable("parameter upload failed: " + t)
            false
        }
    }

    /** 列主序 4×4 求逆（JOML，奇异矩阵返回 null）。 */
    private fun invert(matrix: FloatArray): FloatArray? {
        return try {
            val m = Matrix4f().set(matrix[0], matrix[1], matrix[2], matrix[3],
                matrix[4], matrix[5], matrix[6], matrix[7],
                matrix[8], matrix[9], matrix[10], matrix[11],
                matrix[12], matrix[13], matrix[14], matrix[15]).invert()
            val out = FloatArray(16)
            m.get(out)
            out
        } catch (t: Throwable) {
            null
        }
    }

    private fun countSkip(reason: String) {
        skippedCount++
        val now = System.currentTimeMillis()
        if (now - lastLogMillis >= LOG_INTERVAL_MILLIS) {
            lastLogMillis = now
            LOGGER.info("[d3d12] velocity: skipped ({}) {}", reason, describe())
        }
    }

    private fun logStatus() {
        val now = System.currentTimeMillis()
        if (now - lastLogMillis < LOG_INTERVAL_MILLIS) {
            return
        }
        lastLogMillis = now
        LOGGER.info("[d3d12] velocity: {}", describe())
    }

    /** 一次性关闭（出错时），并给出原因。 */
    private fun disable(reason: String) {
        if (this.disabledReason == null) {
            this.disabledReason = reason
            LOGGER.warn("[d3d12] velocity: disabling velocity pass — {}", reason)
        }
        this.enabled = false
    }

    private fun releaseResources() {
        this.current?.close()
        this.current = null
        this.previous?.close()
        this.previous = null
        this.uniform?.close()
        this.uniform = null
        this.depthView?.close()
        this.depthView = null
        this.depthViewFor = 0L
        this.width = 0
        this.height = 0
    }

    /** 诊断用一行状态。 */
    @Synchronized
    fun describe(): String = ("enabled=" + enabled + " active=" + isActive()
            + " frame=" + frameIndex + " passes=" + passCount + " skipped=" + skippedCount
            + " size=" + width + "x" + height
            + " pipeline=" + (if (pipeline == 0L) "none" else "ok")
            + " depthView=" + (if (depthView == null) "none" else "ok")
            + (if (disabledReason == null) "" else " disabledReason=" + disabledReason))

    // ------------------------------------------------------------------
    // HLSL（native 用 D3DCompile 编译，入口固定 main）
    // ------------------------------------------------------------------

    /**
     * 全屏三角形：`SV_VertexID` 0/1/2 → uv (0,0)/(2,0)/(0,2)，正好覆盖 [0,1]²。
     * 空 input layout 合法（不需要顶点缓冲）。
     */
    private val VERTEX_HLSL: String = """
        struct VsOut {
            float4 position : SV_Position;
        };

        VsOut main(uint vertexId : SV_VertexID) {
            VsOut output;
            float2 uv = float2((vertexId << 1) & 2, vertexId & 2);
            output.position = float4(uv.x * 2.0 - 1.0, 1.0 - uv.y * 2.0, 0.0, 1.0);
            return output;
        }
    """.trimIndent()

    /**
     * 深度反投影 + 两帧重投影 → 屏幕空间运动（像素）。
     *
     * `SV_Position.xy` 是像素中心，除以屏幕尺寸就是该像素的 uv（与深度采样坐标一致），
     * 所以不需要额外插值量。清屏值（depth 恰为 0 或 1）直接输出 0，避免远平面反投影出垃圾。
     */
    private val PIXEL_HLSL: String = """
        cbuffer DXRMVelocity : register(b0, space0) {
            float4x4 PrevViewProj;
            float4x4 InvCurrViewProj;
            float2 ScreenSize;
            float2 _padding;
        };

        Texture2D<float> DXRMDepth : register(t1, space0);
        SamplerState DXRMDepthSampler : register(s1, space0);

        float4 main(float4 position : SV_Position) : SV_Target0 {
            float2 uv = position.xy / ScreenSize;
            float depth = DXRMDepth.SampleLevel(DXRMDepthSampler, uv, 0).r;
            if (depth <= 0.0 || depth >= 1.0) {
                return float4(0.0, 0.0, 0.0, 0.0);
            }
            float4 ndc = float4(uv.x * 2.0 - 1.0, 1.0 - uv.y * 2.0, depth, 1.0);
            float4 world = mul(InvCurrViewProj, ndc);
            if (abs(world.w) < 1e-6) {
                return float4(0.0, 0.0, 0.0, 0.0);
            }
            world /= world.w;
            float4 prevClip = mul(PrevViewProj, world);
            if (abs(prevClip.w) < 1e-6) {
                return float4(0.0, 0.0, 0.0, 0.0);
            }
            float2 prevNdc = prevClip.xy / prevClip.w;
            float2 prevUv = float2(prevNdc.x * 0.5 + 0.5, 0.5 - prevNdc.y * 0.5);
            float2 motion = (uv - prevUv) * ScreenSize;
            return float4(motion, 0.0, 0.0);
        }
    """.trimIndent()
}
