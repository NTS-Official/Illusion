package dev.nts.expdxr.d3d12.render.advanced.taa

import com.mojang.blaze3d.GpuFormat
import com.mojang.blaze3d.buffers.GpuBuffer
import com.mojang.blaze3d.textures.GpuTexture
import dev.nts.expdxr.d3d12.extern.MCDR_ABI
import dev.nts.expdxr.d3d12.extern.MCDRPipelineDesc
import dev.nts.expdxr.d3d12.render.adapter.D3D12Device
import dev.nts.expdxr.d3d12.render.advanced.D3D12AntiAliasing
import dev.nts.expdxr.d3d12.render.core.D3D12GpuBuffer
import dev.nts.expdxr.d3d12.render.core.D3D12GpuTexture
import dev.nts.expdxr.d3d12.render.core.D3D12GpuTextureView
import dev.nts.expdxr.dxrmc.dlss.DeepLearningSuperSampling
import net.minecraft.client.Minecraft
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.nio.ByteOrder

/**
 * P77：**真正的 TAA**（时域抗锯齿）的 resolve pass。
 *
 * 此前抗锯齿里的 TAA 只是占位：native 收到模式 2 会 WARN 并降级成 OFF（`d3d12_device.cpp` 的
 * `setAntiAliasing`），所以选它等于没开。这里把它补成一条完整的时域链路，全部在 JVM 侧录制，
 * 与 [D3D12Velocity]（运动矢量）、[D3D12Temporal]（jitter / 相机矩阵）共用同一份时间性数据面。
 *
 * ## 每帧做两件事
 * 1. **resolve**：读本帧世界层颜色（`t1`）、上一帧的累积结果（`t2`）、本帧运动矢量（`t3`），
 *    在 YCoCg 空间对历史做 3x3 邻域钳制（variance clipping）后按 [BLEND] 混合，写进历史纹理
 *    （`RGBA16_FLOAT`，乒乓）。
 * 2. **回填**：把刚写好的历史纹理整屏 blit 回主渲染目标。主目标是 RGBA8，历史是 RGBA16F，
 *    格式不同所以不能 `CopyTextureRegion`，必须走 native 的 `dx12BlitTextureToTexture`
 *    （通用整屏三角形 + 采样，允许两边格式/尺寸不同，见 `MCDR\src\core\d3d12_surface.cpp`
 *    的 `blitTextureToTexture`）。
 *
 * 两趟之后主渲染目标里就是"抗锯齿后的世界层"，随后的 GUI 绘制、GUI 分离、DLSS、最终 blit
 * 全都不需要改动 —— 这与 SMAA 在世界层做后处理的策略一致（见
 * [dev.nts.expdxr.d3d12.render.advanced.smaa.D3D12SMAAWorld]）。
 *
 * ## 为什么历史是 RGBA16F 而不是跟主目标同格式
 * 累积是 `out = mix(history, current, 0.0625)`，历史每帧只被写一次。8 位历史会把每帧的 1/16
 * 增量就地量化掉，几十帧后画面出现"时间性抖动/带状"；16F 历史没有这个问题。代价是回填必须
 * 多一趟 blit（见上），换来的是可用的画面。
 *
 * ## 运动矢量的方向约定
 * [D3D12Velocity] 产出的 mv 是 **`prevPos - curPos`**（像素单位，NVIDIA DLSS 指南的约定，
 * P76h 修的就是这个方向），因此历史位置 = `本像素 - mv`。方向反了会让 TAA 沿运动方向拖一条
 * 永不收敛的残影。
 */
object D3D12TAA {

    private val LOGGER = LoggerFactory.getLogger("dxrmc-taa")

    /** 历史纹理格式：16 位浮点（理由见类注释）。 */
    private val FORMAT = GpuFormat.RGBA16_FLOAT

    /** 历史纹理既要当渲染目标又要当 SRV，回填时还会被 blit 采。 */
    private val USAGE = GpuTexture.USAGE_RENDER_ATTACHMENT or GpuTexture.USAGE_TEXTURE_BINDING or GpuTexture.USAGE_COPY_SRC

    /** `MCDRPipelineDesc.topology` 的三角形列表值（与 velocity 同一个常量）。 */
    private const val TOPOLOGY_TRIANGLES = 4

    private const val WRITE_MASK_RGBA = 0xF

    /** 参数块大小：native 侧 CBV 的固定粒度是 256 B（与 velocity 一致）。 */
    private const val CBV_SIZE = 256L

    /**
     * 本帧权重：`out = mix(history, current, BLEND)`。
     *
     * 1/16 即 16 帧的时间常数。偏大（1/8）收敛快但降噪弱、动态场景更糊；偏小（1/32）更平滑
     * 但运动边缘容易残留。0.0625 是常见起点，实机看效果再调。
     */
    private const val BLEND = 0.0625f

    private const val LOG_INTERVAL_MILLIS = 5_000L
    private const val FRAME_NONE = -1L

    // ------------------------------------------------------------------
    // 状态
    // ------------------------------------------------------------------

    private var history: Array<D3D12GpuTexture?> = arrayOfNulls(2)
    private var historyViews: Array<D3D12GpuTextureView?> = arrayOfNulls(2)

    /** 本帧读哪张历史 / 写哪张历史（写完之后交换）。 */
    private var readIndex = 0
    private var writeIndex = 1

    private var width = 0
    private var height = 0

    private var pipeline = 0L
    private var pipelineError: String? = null
    private var uniform: D3D12GpuBuffer? = null

    /** 主渲染目标颜色纹理的 SRV 视图 + 它属于哪张纹理（主目标重建后重取）。 */
    private var sceneView: D3D12GpuTextureView? = null
    private var sceneViewFor = 0L

    private var frameIndex = 0L
    private var recordedFrame = FRAME_NONE
    private var passCount = 0L
    private var skippedCount = 0L
    private var lastLogMillis = 0L
    private var loggedHook = false

    /** 诊断：参数块与各纹理的实际尺寸只打一次（尺寸对不上就是"整屏一片渐变"的直接原因）。 */
    private var loggedParams = false

    /**
     * 历史是否可用（代数对上、尺寸对上、至少写过一帧）。false 时本帧 resolve 只把当前帧
     * 原样写进历史（`ResetHistory = 1`），从下一帧起才开始混合。
     */
    private var historyValid = false

    /** 建起历史时 [D3D12Temporal.historyGeneration] 的值；变了就丢弃历史。 */
    private var historyGeneration = -1L

    /** 非空表示自我关闭（只记第一次的原因，避免每帧刷屏/重复尝试）。 */
    private var disabledReason: String? = null

    // ------------------------------------------------------------------
    // 对外入口
    // ------------------------------------------------------------------

    /** 玩家是否选了 TAA（本类的总开关）。 */
    @JvmStatic
    fun isSelected(): Boolean = D3D12AntiAliasing.current() == D3D12AntiAliasing.TAA

    /** 是否真的在跑（选项 + 没有自我关闭）。 */
    @JvmStatic
    fun isActive(): Boolean = isSelected() && disabledReason == null

    /**
     * 世界层画完、GUI 还没画的那一刻调用一次（`GameRendererSmaaWorldMixin`）。
     *
     * 任何一步不满足（没进世界、没选 TAA、本帧还没有运动矢量、拿不到编码器）都只是安静跳过：
     * 表现就等于"没有 TAA"，不会让这一帧渲染不出来。
     */
    @JvmStatic
    fun applyIfEnabled() {
        if (!isSelected() || disabledReason != null) {
            return
        }
        val minecraft = Minecraft.getInstance()
        if (minecraft.level == null) {
            return
        }
        // 运动矢量是这条链路的命脉：没有它就无法把历史搬到现在的位置，宁可不做 TAA。
        if (!D3D12Velocity.isActive() || D3D12Velocity.currentHandle() == 0L) {
            return
        }
        val view = minecraft.gameRenderer.mainRenderTarget().colorTextureView
        val texture = (view as? D3D12GpuTextureView)?.texture() as? D3D12GpuTexture ?: return
        val encoder = D3D12Device.sharedCommandEncoder() ?: return
        if (!loggedHook) {
            loggedHook = true
            LOGGER.info("[dxrmc] P77: TAA resolve 挂钩生效（世界 {}x{}，历史格式 {}）",
                texture.getWidth(0), texture.getHeight(0), FORMAT)
        }
        encoder.recordTaaPass(texture)
    }

    /**
     * 每帧一次时域数据采集（`LevelRendererMainPassDepthMixin` 在帧图 main pass 的 RETURN 调用）。
     *
     * 那一刻世界几何刚画完、任何一次 `clearDepthTexture` 之前，深度里正是本帧的世界。DLSS 关闭
     * 时 [DeepLearningSuperSampling.captureMotionVectors] 第一行就返回，所以"只开 TAA"的玩家没有
     * 任何人录运动矢量 —— 这里补上：相机矩阵从 CPU 渲染状态取（UBO 字节实测是垃圾），再录一次
     * 速度 pass。DLSS 开着时它自己会录，直接让路。
     */
    @JvmStatic
    fun captureTemporalData() {
        if (!isSelected() || disabledReason != null) {
            return
        }
        if (DeepLearningSuperSampling.isEnabled()) {
            return
        }
        val minecraft = Minecraft.getInstance()
        if (minecraft.level == null) {
            return
        }
        D3D12Temporal.captureCameraFromState(minecraft)
        val encoder = D3D12Device.sharedCommandEncoder() ?: return
        encoder.recordVelocityPass()
    }

    // ------------------------------------------------------------------
    // 录制
    // ------------------------------------------------------------------

    /**
     * 把 resolve + 回填录进当前命令列表（列表必须已经打开，见
     * `D3D12CommandEncoderBackend.recordTaaPass`）。
     *
     * @return true = 这一帧真的录了（历史已更新、主目标已被回填）。
     */
    fun record(ctx: Long, device: D3D12Device?, scene: D3D12GpuTexture): Boolean {
        if (disabledReason != null || !isSelected()) {
            return false
        }
        if (this.recordedFrame == this.frameIndex) {
            return true
        }
        if (device == null) {
            countSkip("no device")
            return false
        }
        val w = scene.getWidth(0)
        val h = scene.getHeight(0)
        if (w <= 0 || h <= 0) {
            countSkip("bad world size")
            return false
        }
        val motion = D3D12Velocity.currentMotionView() ?: run {
            countSkip("no motion view")
            return false
        }
        if (!ensurePipeline(device)) {
            return false
        }
        if (!ensureResources(device, w, h)) {
            return false
        }
        val params = this.uniform ?: return false
        val read = this.historyViews[readIndex] ?: return false
        val write = this.history[writeIndex] ?: return false
        if (!ensureSceneView(scene)) {
            return false
        }
        val sceneSrv = this.sceneView ?: return false

        // 代数变化（传送 / 换世界 / 切分辨率 / 关设备）=> 上一帧画面与这一帧没有任何关系。
        val generation = D3D12Temporal.historyGeneration
        if (this.historyGeneration != generation) {
            this.historyGeneration = generation
            this.historyValid = false
        }
        val reset = !this.historyValid
        // 本帧投影实际用了多大的亚像素偏移；历史取样必须按它换回未抖动网格（见 PIXEL_HLSL 里的 historyUv）。
        val jitterX = D3D12Temporal.currentJitterX.toFloat()
        val jitterY = D3D12Temporal.currentJitterY.toFloat()
        if (!writeParameters(params, w, h, reset, jitterX, jitterY)) {
            return false
        }
        if (!loggedParams) {
            loggedParams = true
            val readTex = this.history[readIndex]
            LOGGER.info("[backend] TAA: 参数与尺寸 scene={}x{} history(写)={}x{} history(读)={}x{}"
                + " params={}x{} blend={} reset={}",
                scene.getWidth(0), scene.getHeight(0),
                write.getWidth(0), write.getHeight(0),
                readTex?.getWidth(0) ?: -1, readTex?.getHeight(0) ?: -1,
                w, h, BLEND, reset)
        }

        // ---- 第一趟：resolve 进历史纹理 ----
        MCDR_ABI.D3D12BeginRenderPass(ctx, longArrayOf(write.handle()), intArrayOf(0),
            byteArrayOf(0), floatArrayOf(0.0f, 0.0f, 0.0f, 0.0f),
            0L, 0, 0.toByte(), 0.0, 0, 0, w, h)
        // P69 教训：viewport/scissor 是命令列表状态，每个 pass 都必须显式重设。
        MCDR_ABI.D3D12SetViewport(ctx, 0, 0, w, h)
        MCDR_ABI.D3D12SetScissor(ctx, 0, 0, w, h)
        if (!MCDR_ABI.D3D12SetPipeline(ctx, this.pipeline, false)) {
            disable("dx12SetPipeline failed: ${MCDR_ABI.D3D12LastError()}")
            MCDR_ABI.D3D12EndRenderPass(ctx)
            return false
        }
        // 绑定次序 = `addBinding(type, reg)` 的登记次序；type 只区分 0=CBV / 1=SRV 纹理。
        val pushed = MCDR_ABI.D3D12PushDescriptors(ctx,
            intArrayOf(0, 1, 1, 1),
            longArrayOf(params.handle(), 0L, 0L, 0L),
            longArrayOf(0L, 0L, 0L, 0L),
            longArrayOf(CBV_SIZE, 0L, 0L, 0L),
            intArrayOf(0, 0, 0, 0),
            longArrayOf(0L, sceneSrv.handle(), read.handle(), motion.handle()))
        if (!pushed) {
            disable("dx12PushDescriptors failed: ${MCDR_ABI.D3D12LastError()}")
            MCDR_ABI.D3D12EndRenderPass(ctx)
            return false
        }
        if (!MCDR_ABI.D3D12Draw(ctx, 3, 1, 0, 0)) {
            disable("dx12Draw failed: ${MCDR_ABI.D3D12LastError()}")
        }
        MCDR_ABI.D3D12EndRenderPass(ctx)

        // ---- 第二趟：把历史整屏回填到主渲染目标（RGBA16F -> RGBA8，必须走采样 blit）----
        // 不成功也不致命：主目标保持本帧原样（等于没开 TAA），但历史照旧累积。
        if (!MCDR_ABI.dx12BlitTextureToTexture(ctx, write.handle(), scene.handle())) {
            countSkip("blit back failed")
        }

        this.readIndex = this.writeIndex
        this.writeIndex = 1 - this.readIndex
        this.historyValid = true
        this.recordedFrame = this.frameIndex
        this.passCount++
        logStatus()
        return true
    }

    /** 每帧开始时调用（由 `D3D12Temporal.beginFrame` 驱动）：推进帧号，与 [recordedFrame] 配对。 */
    @Synchronized
    fun beginFrame() {
        this.frameIndex++
    }

    /**
     * 作废跨帧状态（退出世界 / 传送 / 换分辨率 / 关设备时调用）。
     *
     * **不要在这里释放 GPU 资源**：调用点是 `D3D12Temporal.reset()`，发生在世界加载、传送、
     * 窗口改尺寸这些时刻 —— 上一帧的命令列表很可能还在 GPU 上跑。`D3D12GpuTexture.close()`
     * 最终是 `mcdr_dx12DestroyResource`（`D3D12GpuTexture.kt:72-80`），立刻销毁在飞行中的资源
     * 会直接把设备打掉（实测：pass 70 调一次 `reset()`，客户端在两帧内无异常退出，日志只剩
     * `Stopping!`）。纹理/视图照旧留着，靠 `historyValid=false` 让像素着色器走
     * `ResetHistory>0.5 ⇒ resolved = current.rgb` 的重建分支；尺寸真的变了由
     * `ensureResources(device, w, h)` 的尺寸比较负责重建。
     */
    @Synchronized
    fun reset() {
        frameIndex = 0L
        recordedFrame = FRAME_NONE
        passCount = 0L
        skippedCount = 0L
        historyValid = false
        historyGeneration = -1L
        disabledReason = null
        pipelineError = null
        this.loggedHook = false
        this.loggedParams = false
    }

    /** 诊断用一行状态。 */
    @Synchronized
    fun describe(): String = ("selected=${isSelected()} active=${isActive()}"
        + " frame=${frameIndex} passes=${passCount} skipped=${skippedCount}"
        + " size=${width}x${height} history=${if (historyValid) "valid" else "reset"}"
        + " pipeline=${if (pipeline == 0L) "none" else "ok"}"
        + (if (disabledReason == null) "" else " disabledReason=${disabledReason}"))

    // ------------------------------------------------------------------
    // 资源
    // ------------------------------------------------------------------

    private fun ensureResources(device: D3D12Device, w: Int, h: Int): Boolean {
        if (this.history[0] != null && this.history[1] != null && this.width == w && this.height == h) {
            return true
        }
        releaseHistory()
        return try {
            this.history[0] = D3D12GpuTexture(USAGE, "DXRMC TAA history", FORMAT, w, h, 1, 1)
            this.history[1] = D3D12GpuTexture(USAGE, "DXRMC TAA history (previous)", FORMAT, w, h, 1, 1)
            this.historyViews[0] = D3D12GpuTextureView(this.history[0]!!, 0, 1)
            this.historyViews[1] = D3D12GpuTextureView(this.history[1]!!, 0, 1)
            this.uniform = device.createBuffer({ "DXRMC TAA params" },
                GpuBuffer.USAGE_UNIFORM or GpuBuffer.USAGE_MAP_WRITE, CBV_SIZE) as D3D12GpuBuffer
            this.width = w
            this.height = h
            this.readIndex = 0
            this.writeIndex = 1
            this.historyValid = false
            LOGGER.info("[backend] TAA: 历史缓冲已分配 {}x{} {}（乒乓）", w, h, FORMAT)
            true
        } catch (t: Throwable) {
            releaseHistory()
            disable("resource allocation failed: $t")
            false
        }
    }

    private fun releaseHistory() {
        for (i in 0 until 2) {
            this.historyViews[i]?.close()
            this.historyViews[i] = null
            this.history[i]?.close()
            this.history[i] = null
        }
        this.uniform?.close()
        this.uniform = null
        this.width = 0
        this.height = 0
        this.historyValid = false
    }

    private fun releaseResources() {
        releaseHistory()
        this.sceneView?.close()
        this.sceneView = null
        this.sceneViewFor = 0L
    }

    /** 主渲染目标颜色纹理的 SRV 视图（同一张纹理只建一次；主目标重建后重取）。 */
    private fun ensureSceneView(scene: D3D12GpuTexture): Boolean {
        val handle = scene.handle()
        if (this.sceneView != null && this.sceneViewFor == handle) {
            return true
        }
        this.sceneView?.close()
        this.sceneView = null
        this.sceneViewFor = 0L
        return try {
            this.sceneView = D3D12GpuTextureView(scene, 0, 1)
            this.sceneViewFor = handle
            LOGGER.info("[backend] TAA: 世界层 SRV 视图已建（texture {}，fmt={}）",
                handle.toULong().toString(16), scene.format)
            true
        } catch (t: Throwable) {
            disable("scene view creation failed: $t")
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
                .writeMask(WRITE_MASK_RGBA))
            .addBinding(0, 0)   // CBV -> register(b0)
            .addBinding(1, 1)   // 本帧世界层颜色 -> register(t1)
            .addBinding(1, 2)   // 上一帧累积结果 -> register(t2)
            .addBinding(1, 3)   // 本帧运动矢量 -> register(t3)
        val handle = MCDR_ABI.D3D12CreateGraphicsPipeline(desc)
        if (handle == 0L) {
            this.pipelineError = MCDR_ABI.D3D12LastError()
            disable("dx12CreateGraphicsPipeline failed: ${this.pipelineError}")
            return false
        }
        this.pipeline = handle
        LOGGER.info("[backend] TAA: 管线已编译（handle={}）", handle.toULong().toString(16))
        return true
    }

    /**
     * 写参数块（`Params` cbuffer，8 个 float）。
     *
     * 用写映射直接写（usage 含 MAP_WRITE，UPLOAD 堆 CPU 可见），不走 `writeToBuffer`：
     * 那条路会经过 `D3D12Temporal.interceptWrite`（投影 jitter 的注入点），没必要。
     */
    private fun writeParameters(
        buffer: D3D12GpuBuffer,
        w: Int,
        h: Int,
        reset: Boolean,
        jitterX: Float,
        jitterY: Float,
    ): Boolean {
        return try {
            buffer.map(0L, CBV_SIZE, false, true).use { view ->
                val fb = view.data().order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                fb.put(w.toFloat())
                fb.put(h.toFloat())
                fb.put(1.0f / w.toFloat())
                fb.put(1.0f / h.toFloat())
                fb.put(BLEND)
                fb.put(if (reset) 1.0f else 0.0f)
                fb.put(jitterX)   // Jitter：本帧投影的亚像素偏移（像素单位）
                fb.put(jitterY)
                // 补齐到 256 B（= 64 个 float），与 velocity 的参数块粒度一致。
                while (fb.position() < 64) {
                    fb.put(0.0f)
                }
            }
            true
        } catch (t: Throwable) {
            disable("parameter upload failed: $t")
            false
        }
    }

    // ------------------------------------------------------------------
    // 记账
    // ------------------------------------------------------------------

    private fun countSkip(reason: String) {
        this.skippedCount++
        val now = System.currentTimeMillis()
        if (now - this.lastLogMillis >= LOG_INTERVAL_MILLIS) {
            this.lastLogMillis = now
            LOGGER.info("[backend] TAA: {}（{}）", reason, describe())
        }
    }

    private fun logStatus() {
        val now = System.currentTimeMillis()
        if (now - this.lastLogMillis < LOG_INTERVAL_MILLIS) {
            return
        }
        this.lastLogMillis = now
        LOGGER.info("[backend] TAA: {}", describe())
    }

    private fun disable(reason: String) {
        if (this.disabledReason != null) {
            return
        }
        this.disabledReason = reason
        LOGGER.error("[backend] TAA 已关闭：{}", reason)
    }

    // ------------------------------------------------------------------
    // HLSL（native 侧入口固定 main；优先 DXC + SM 6.x/DXIL，退回 D3DCompile SM 5.1）
    // ------------------------------------------------------------------

    /** 整屏三角形：不占顶点缓冲，靠 `SV_VertexID` 生成（与 velocity 的顶点着色器同构）。 */
    private val VERTEX_HLSL: String = """
        struct VSOut {
            float4 position : SV_Position;
        };

        VSOut main(uint id : SV_VertexID) {
            VSOut o;
            float2 uv = float2((id << 1) & 2, id & 2);
            o.position = float4(uv * float2(2.0, -2.0) + float2(-1.0, 1.0), 0.0, 1.0);
            return o;
        }
    """.trimIndent()

    /**
     * 时域 resolve 的像素着色器。
     *
     * **为什么全部用 `Load` 而不是 `Sample`**：`MCDRPipelineDesc.addBinding(type=1, reg)` 建的静态
     * 采样器是 `MIN_MAG_MIP_POINT` + `WRAP`（`MCDR\src\core\d3d12_device.cpp:4433-4442`，WRAP 是
     * 天气粒子必须的）。拿它去采历史和运动矢量会同时踩两个坑：点采样丢掉亚像素精度（历史抖动），
     * UV 出界时环绕取样会从屏幕另一侧拖入内容。手算双线性 + 坐标钳制把这两件事都变成显式行为。
     */
    private val PIXEL_HLSL: String = """
        cbuffer Params : register(b0, space0) {
            float2 ScreenSize;
            float2 RcpScreenSize;
            float Blend;
            float ResetHistory;
            float2 Jitter;
        };

        Texture2D<float4> Scene : register(t1, space0);
        Texture2D<float4> HistoryBuffer : register(t2, space0);
        Texture2D<float2> Motion : register(t3, space0);

        float3 toYCoCg(float3 c) {
            return float3(dot(c, float3(0.25, 0.5, 0.25)),
                          dot(c, float3(0.5, 0.0, -0.5)),
                          dot(c, float3(-0.25, 0.5, -0.25)));
        }

        float3 fromYCoCg(float3 y) {
            float t = y.x - y.z;
            return float3(t + y.y, y.x + y.z, t - y.y);
        }

        float3 fetchClamped(Texture2D<float4> tex, int2 coord, int2 maxCoord) {
            return tex.Load(int3(clamp(coord, int2(0, 0), maxCoord), 0)).rgb;
        }

        float4 main(float4 position : SV_Position) : SV_Target0 {
            int2 size = int2(ScreenSize + 0.5);
            int2 maxCoord = size - 1;
            int2 coord = clamp(int2(position.xy), int2(0, 0), maxCoord);
            float4 current = Scene.Load(int3(coord, 0));

            // mv 的约定是 prevPos - curPos（见 D3D12Velocity 的 `(prevUv - uv) * ScreenSize`），
            // 所以本像素的内容在上一帧的位置 = 本像素 + mv。
            float2 mv = Motion.Load(int3(coord, 0)).xy;
            // `mv` 是**抖动空间**位移：D3D12Velocity 的 `currViewProj` 带本帧 jitter、着色器再加
            // `PrevJitterPixels`，所以它已经是 `prevJitPx - curJitPx`（两个渲染位置都在抖动网格上）。
            // 因此本像素（抖动位置）对应的历史位置就是 `coord + mv`，**不能再减一次 Jitter** ——
            // 旧写法 `- Jitter` 等于每帧按 jitter 序列错开采样历史，静止相机也会把画面摊成低频渐变。
            float2 historyUv = (float2(coord) + 0.5 + mv) * RcpScreenSize;

            // 3x3 邻域的 YCoCg 统计（variance clipping 的包围盒）。
            float3 sum = 0.0;
            float3 sumSq = 0.0;
            [unroll] for (int dy = -1; dy <= 1; ++dy) {
                [unroll] for (int dx = -1; dx <= 1; ++dx) {
                    float3 y = toYCoCg(Scene.Load(int3(clamp(coord + int2(dx, dy),
                        int2(0, 0), maxCoord), 0)).rgb);
                    sum += y;
                    sumSq += y * y;
                }
            }
            float3 mean = sum / 9.0;
            float3 variance = max(sumSq / 9.0 - mean * mean, 0.0);
            float3 lo = mean - 1.5 * sqrt(variance);
            float3 hi = mean + 1.5 * sqrt(variance);

            // 历史：手算双线性（理由见上面的着色器注释）。
            float2 texel = historyUv * ScreenSize - 0.5;
            int2 baseCoord = int2(floor(texel));
            float2 f = texel - float2(baseCoord);
            float3 h00 = fetchClamped(HistoryBuffer, baseCoord, maxCoord);
            float3 h10 = fetchClamped(HistoryBuffer, baseCoord + int2(1, 0), maxCoord);
            float3 h01 = fetchClamped(HistoryBuffer, baseCoord + int2(0, 1), maxCoord);
            float3 h11 = fetchClamped(HistoryBuffer, baseCoord + int2(1, 1), maxCoord);
            float3 history = lerp(lerp(h00, h10, f.x), lerp(h01, h11, f.x), f.y);

            float3 clamped = clamp(toYCoCg(history), lo, hi);
            float3 resolved = lerp(fromYCoCg(clamped), current.rgb, Blend);

            // 历史不可用/不可信时直接取本帧：首帧与代数变化（ResetHistory）、位移荒谬或非有限
            // （镜头瞬移、NaN）、历史 UV 出界（新露出的区域）。
            float motionLength = length(mv);
            bool outside = (historyUv.x < 0.0) || (historyUv.y < 0.0)
                || (historyUv.x > 1.0) || (historyUv.y > 1.0);
            bool absurd = !(motionLength <= 0.25 * max(ScreenSize.x, ScreenSize.y));
            if (ResetHistory > 0.5 || outside || absurd) {
                resolved = current.rgb;
            }
            return float4(resolved, current.a);
        }
    """.trimIndent()
}
