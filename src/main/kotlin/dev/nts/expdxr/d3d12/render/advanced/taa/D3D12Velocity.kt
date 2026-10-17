package dev.nts.expdxr.d3d12.render.advanced.taa

import com.mojang.blaze3d.GpuFormat
import com.mojang.blaze3d.buffers.GpuBuffer
import com.mojang.blaze3d.textures.GpuTexture
import dev.nts.expdxr.d3d12.extern.MCDR_ABI
import dev.nts.expdxr.d3d12.extern.MCDRPipelineDesc
import dev.nts.expdxr.d3d12.render.adapter.D3D12Device
import dev.nts.expdxr.d3d12.render.core.D3D12GpuSurface
import dev.nts.expdxr.d3d12.render.core.D3D12GpuBuffer
import dev.nts.expdxr.d3d12.render.core.D3D12GpuTexture
import dev.nts.expdxr.d3d12.render.core.D3D12GpuTextureView
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
 * 管线走 raw ABI（`MCDRPipelineDesc` 直接吃 HLSL 源码，native 侧入口固定 main；P84 起优先走
 * DXC + SM 6.x（DXIL，vs_6_8/ps_6_8），DXC 不可用/编译失败时退回 `D3DCompile(...,"main",...)` 的 SM 5.1），
 * 绑定共三项：
 *  - entry 0 = CBV（`register(b0)`，root descriptor）→ 256 B 参数块：
 *    `PrevViewProj` + `InvCurrViewProj` + `ScreenSize` + `ObjectParams`；
 *  - entry 1 = SRV + static sampler（`register(t1)` / `register(s1)`）→ 主目标**深度**纹理视图。
 *    native 的 static sampler 是 POINT/WRAP，`createTextureView` 会把深度格式重映射成
 *    `R32_FLOAT`（`D32_FLOAT`）或 `R32_FLOAT_X8X24_TYPELESS`（`D32_FLOAT_S8X24_UINT`）；
 *  - entry 2 = SRV（`register(t2)`，无采样器）→ P76b 的逐物体行缓冲（R32_FLOAT texel buffer）。
 *
 * 输出：`RG16_FLOAT` 乒乓纹理（xy = 屏幕空间运动，单位**像素**，`(prevUv - uv) * ScreenSize`，
 * 方向是「本帧 → 上一帧」，与 DLSS 编程指南一致；旧写法反了，见 P76h）。
 * 乒乓里"上一帧速度"这一半本轮**还不被消费**（那是 TAAU resolve 的历史纹理），保留是为了
 * 让 P77 的 resolve 直接接上。
 *
 * 数学（PS 内，逐像素）：
 *  1. `uv = SV_Position.xy / ScreenSize`（全屏三角形正好覆盖 [0,1]²，uv 即纹素中心）；
 *  2. `ndc = (uv.x*2-1, 1-uv.y*2, depth, 1)`，用**本帧抖动过的**投影的逆矩阵反投影回世界；
 *  3. 用上一帧 view-projection 投影 → prevUv；motion = (prevUv - uv) * ScreenSize（方向是「本帧→上一帧」，见 P76h）；
 *  4. （P76b）世界位置落在某个上一帧物体 AABB 内 ⇒ 用该物体的屏幕位移覆盖第 3 步的结果。
 *
 * P76b 增量：在上面这条**相机重投影**之上叠加**逐物体修正**。相机重投影对静态几何严格正确，
 * 但它把移动实体也当成静止世界，于是走动中的猪/玩家/掉落物会拖影。修正数据由
 * [D3D12ObjectMotion] 在帧起用公共 API 采集（**无 mixin、无 AT**），以「行 = 本帧每个物体的
 * 物体的世界 AABB + 它相对再上一帧的屏幕位移」的形式经 `t2` 的结构化缓冲送进来：像素的世界
 * 位置落在某个物体的上一帧 AABB 内，就用**该物体的屏幕位移**替换相机重投影的结果。
 *
 * AABB 打不准的地方（会退化成相机重投影，即"跟世界一起动"，不会比 P76a 更差）：
 * 旋转的实体（猪头转向、玩家挥臂）—— 顶点里烘的是本帧位姿，AABB 只覆盖**包围盒**，旋转本身
 * 不进位移；多部件实体（末影龙各部件、披风）按 UUID 去重成一行，取最接近提交位姿的那份。
 * 天空/清屏值（depth 恰为 0 或 1）直接输出 0 速度，避免远平面反投影出垃圾。
 *
 * 开关：`-Pdxrmc.temporal.velocity=true`（默认 **false**）。任何失败（建纹理/编译管线/取深度）
 * 都只 warn 一次并自我关闭，绝不影响正常渲染 —— 与 P75 的"时间性数据缺失只能让效果退化"一致。
 */
/**
 * [D3D12Velocity.abandonedSteps] 的位：速度纹理/参数块分配或参数写入失败过。
 *
 * 参数写入失败也归这一位：两者都只依赖"上传堆里的参数块"这一份资源，一起重试才对得上
 * （管线与深度视图的成败与它们无关）。
 *
 * 放在文件顶层而不是对象内部：`companion object` 不允许出现在 `object` 里（K2 直接报
 * `Modifier 'companion' is not applicable inside 'standalone object'`）。
 */
private const val STEP_RESOURCES: Int = 1

/** [D3D12Velocity.abandonedSteps] 的位：主深度 SRV 视图创建失败过。 */
private const val STEP_DEPTH_VIEW: Int = 2

/** [D3D12Velocity.abandonedSteps] 的位：速度管线创建/绑定/绘制失败过。 */
private const val STEP_PIPELINE: Int = 4

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

    /**
     * P76b 的逐物体缓冲：`Buffer<float>`（**texel buffer**，不是 StructuredBuffer）。
     *
     * native 的 `pushDescriptors` 只认 texel buffer（绑定类型 2 + `texelFormat`，见
     * `mcdr_abi.h:123-130`），格式用 `GpuFormat.R32_FLOAT`，于是 HLSL 侧声明成
     * `Buffer<float>` 与之对应。行是**紧凑 16 float**（不补齐到 float4 边界），
     * 字段下标见 [D3D12ObjectMotion.FIELD_ID] 一族。
     */
    private val OBJECT_FORMAT: GpuFormat = GpuFormat.R32_FLOAT

    /** 物体缓冲的字节数（[D3D12ObjectMotion.MAX_OBJECTS] 行 × 每行 16 float）。 */
    private const val OBJECT_BYTES: Long = D3D12ObjectMotion.MAX_OBJECTS.toLong() * D3D12ObjectMotion.ROW_FLOATS * 4L

    /** MaxLOD 级别的 AABB 外扩（方块单位）：挥臂/摆腿的顶点会短暂越界，不扩会漏掉肢体。 */
    private const val OBJECT_BOX_MARGIN: Float = 0.35f

    /**
     * 逐物体位移的屏幕像素上限；超过即丢弃这次匹配（回退相机重投影）。
     *
     * 用来挡住"上一帧 AABB 张冠李戴"的坏匹配：一旦匹配错行，位移会是成百上千像素。
     */
    private const val OBJECT_MAX_MOTION_PIXELS: Float = 128.0f

    /** 第一帧（还没有上一帧相机）时的平移增量回退值。 */
    private val CAMERA_DELTA_NONE: FloatArray = floatArrayOf(0.0f, 0.0f, 0.0f)

    private const val LOG_INTERVAL_MILLIS: Long = 5_000L

    /** [recordedFrame] 的初值："这一帧还没录过"。 */
    private const val FRAME_NONE: Long = -1L

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

    /** P76b 的逐物体行缓冲（UPLOAD 堆，每帧按 `t2` 绑定；native 侧当 R32_FLOAT texel buffer 建 SRV）。 */
    private var objects: D3D12GpuBuffer? = null

    /** 复用同一份数组喂 [D3D12ObjectMotion.writeWorldRows]，避免每帧 16 KB 垃圾。 */
    private val objectScratch: FloatArray = FloatArray(D3D12ObjectMotion.MAX_OBJECTS * D3D12ObjectMotion.ROW_FLOATS)

    /** 本帧真正绑定的物体行数（0 = 这一帧没有逐物体修正）。 */
    private var objectCount: Int = 0

    /** 主深度纹理的 SRV 视图 + 它属于哪张纹理（主目标换尺寸/重建时重取）。 */
    private var depthView: D3D12GpuTextureView? = null
    private var depthViewFor: Long = 0L

    private var frameIndex: Long = 0L
    private var passCount: Long = 0L
    private var skippedCount: Long = 0L
    private var lastLogMillis: Long = 0L

    /**
     * 本帧已经录过速度 pass 的帧号（[beginFrame] 每帧 +1，初值 [FRAME_NONE]）。
     *
     * P108：DLSS 需要在**世界层画完、主目标深度被清空之前**就拿到这一帧的运动矢量，所以在那个
     * 挂点上先调一次 [record]；而 `blitFromTexture` 里原来的那次调用会在同一帧再进来一次 ——
     * 那时深度已经被 vanilla 清空（结果全是 0），而且乒乓纹理同一帧写两遍没有意义。有了这个闸门，
     * 第二次调用直接返回，不白跑一次全屏 pass。
     */
    private var recordedFrame: Long = FRAME_NONE

    /** 上一次失败的原因（自我关闭时写日志用）。 */
    private var disabledReason: String? = null

    /**
     * 已经因故放弃的构建步骤（[disable] 时置位，见 [isLatched]）。
     *
     * 位含义：[STEP_RESOURCES] = 速度乒乓纹理/参数块、[STEP_DEPTH_VIEW] = 主深度 SRV、
     * [STEP_PIPELINE] = 速度管线。[forceEnable] 会把这些位连同 [disabledReason] 一起清掉，
     * 于是下一次 [record] 会**重新尝试**一遍 —— 这是"自我关闭必须可被撤销"的关键：
     * 只看 [enabled] 是撤不回来的，一次建纹理失败会永久没有运动矢量。
     */
    private var abandonedSteps: Int = 0

    /** 是否处于"可以录制"的状态（时间性总开关也必须打开）。 */
    fun isActive(): Boolean = enabled && D3D12Temporal.enabled

    /**
     * 是否处于**自我关闭**状态：曾经被 [disable] 关掉过（[disabledReason] 非空），或某个构建步骤
     * 已放弃（[abandonedSteps] 非零，例如管线编译失败过）。DLSS 用它判断"要不要重试一次"。
     */
    @Synchronized
    fun isLatched(): Boolean = disabledReason != null || abandonedSteps != 0

    /**
     * P108：把自我关闭的闩锁整个清掉（**不改 [enabled]**，由调用方决定开关），并做一次
     * [reset]（释放速度纹理/参数块/深度视图与所有累积状态）。
     *
     * 需求来源（用户 m11208）：DLSS 打开就得有运动矢量，而这里任何一个失败都会写日志后永久
     * 关闭（[disable]）。只把 `enabled` 置回 true 是不够的 —— [disabledReason] 会让
     * [record] 直接短路、[pipelineError]/[abandonedSteps] 会让构建步骤永不重试，于是 DLSS
     * 拿到的永远是空句柄（native 在 `d3d12_dlss.cpp:886` 直接返回 0，DLSS 一帧都不评价）。
     *
     * @return true = 本次清掉了一个真实存在过的闩锁（调用方据此决定要不要打日志）。
     */
    @Synchronized
    fun forceEnable(): Boolean {
        val hadLatch = disabledReason != null || abandonedSteps != 0
        reset()
        abandonedSteps = 0
        pipelineError = null
        pipeline = 0L
        return hadLatch
    }

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
        recordedFrame = FRAME_NONE
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
     * @return true = 真的录了一次 core。
     */
    @Synchronized
    fun record(ctx: Long, device: D3D12Device?): Boolean {
        if (!isActive()) {
            return false
        }
        // P108：自我关闭过就一直短路，直到 [forceEnable] 把闩锁清掉（交给 DLSS 的重试时机）。
        // 探针不会在闩锁上残留：管线/纹理/深度视图一旦成功就不再走创建路径。
        if (abandonedSteps != 0) {
            return false
        }
        // P108：同一帧已经录过就什么都不做（DLSS 的挂点比 blit 早，先录的那一次才是有效数据）。
        if (this.recordedFrame == this.frameIndex) {
            return this.current != null
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
        // P76b：把物体快照写进行缓冲。速度 pass 在本帧渲染完成之后录制、像素来自本帧，而它要回答的
        // 是"这个像素上一帧在哪" —— extract 阶段跑在 [D3D12Temporal.beginFrame] 之后，所以
        // 本帧 extract 采到的那份快照**就是**该配对的「上一帧」，滚动点因此放在
        // [D3D12ObjectMotion.writeWorldRows] 里而不是 beginFrame 里。
        val rows = uploadObjects()
        // P76a 的 jitter 补偿：历史缓冲里存的是上一帧**抖动后**的画面。当前像素 uv 带着本帧
        // 的 jitter，而 PrevViewProj 是未抖动的上一帧投影，所以「同一表面点」在历史里的位置是
        // prevUv + 上一帧jitter。于是 mv = (uv - prevUv) - 本帧jitter + 上一帧jitter。
        // 少任何一项都会残留半个像素，远处掠射的地面最先糊成拖影。
        val jitter = D3D12Temporal.jitter()
        val prevJitter = D3D12Temporal.previousJitter()
        // P76a：相机**平移**的视差补偿。深度反投影出的是「相对本帧相机」的世界位置，而
        // PrevViewProj 里的视图矩阵只有旋转（terrain 顶点本来就是相机相对坐标），少了这一项
        // 就等于假设相机没平移过，相机自身走动/侧移造成的视差整块丢失。
        val camDelta = D3D12Temporal.cameraDelta() ?: CAMERA_DELTA_NONE
        if (!writeParameters(uniformBuffer, prevViewProj, invCurr, w, h, rows,
                jitter[0].toFloat(), jitter[1].toFloat(),
                prevJitter[0].toFloat(), prevJitter[1].toFloat(),
                camDelta[0], camDelta[1], camDelta[2])) {
            return false
        }

        MCDR_ABI.D3D12BeginRenderPass(ctx, longArrayOf(color.handle()), intArrayOf(0),
            byteArrayOf(1), floatArrayOf(0.0f, 0.0f, 0.0f, 0.0f),
            0L, 0, 0.toByte(), 0.0, 0, 0, w, h)
        // P69 教训：viewport/scissor 是命令列表状态，每个 pass 都必须显式重设。
        MCDR_ABI.D3D12SetViewport(ctx, 0, 0, w, h)
        MCDR_ABI.D3D12SetScissor(ctx, 0, 0, w, h)
        if (!MCDR_ABI.D3D12SetPipeline(ctx, this.pipeline, false)) {
            disable("dx12SetPipeline failed: ${MCDR_ABI.D3D12LastError()}", STEP_PIPELINE)
            MCDR_ABI.D3D12EndRenderPass(ctx)
            return false
        }
        // P76b：`t2` 是逐物体行缓冲。绑定数组里它排在 `t1` 之后，与 native 里
        // `srvRanges` 的登记顺序（`type != 0` 按 `desc.bindings` 次序）一一对应，
        // 而各 range 的 `BaseShaderRegister` 来自绑定表自己写的 reg —— 所以顺序与
        // `register(t1)`/`register(t2)` 对得上，不会错位。
        val objectBuffer = this.objects
        val pushed = MCDR_ABI.D3D12PushDescriptors(ctx,
            intArrayOf(0, 1, 2),
            longArrayOf(uniformBuffer.handle(), 0L, if (objectBuffer == null) 0L else objectBuffer.handle()),
            longArrayOf(0L, 0L, 0L),
            longArrayOf(CBV_SIZE, 0L, OBJECT_BYTES),
            intArrayOf(0, 0, OBJECT_FORMAT.ordinal),
            longArrayOf(0L, depthSrv.handle(), 0L))
        if (!pushed) {
            disable("dx12PushDescriptors failed: ${MCDR_ABI.D3D12LastError()}", STEP_PIPELINE)
            MCDR_ABI.D3D12EndRenderPass(ctx)
            return false
        }
        if (!MCDR_ABI.D3D12Draw(ctx, 3, 1, 0, 0)) {
            disable("dx12Draw failed: ${MCDR_ABI.D3D12LastError()}", STEP_PIPELINE)
        }
        MCDR_ABI.D3D12EndRenderPass(ctx)
        passCount++
        // P108：记住这一帧已经录过，供同一帧稍后的那次调用短路（见 [recordedFrame]）。
        recordedFrame = frameIndex
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
            // P76b：逐物体行缓冲。`USAGE_UNIFORM_TEXEL_BUFFER` 是"这个缓冲会被当 texel buffer 采"
            // 的语义（GpuBuffer 里值 256）；带上 MAP_WRITE 后落到 UPLOAD 堆，CPU 可直写。
            this.objects = device.createBuffer({ "DXRMC velocity objects" },
                GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER or GpuBuffer.USAGE_MAP_WRITE,
                OBJECT_BYTES) as D3D12GpuBuffer
            this.width = w
            this.height = h
            // 这一步成功了：清掉"已放弃"标记，否则它会把后续帧全部短路掉。
            abandonedSteps = abandonedSteps and STEP_RESOURCES.inv()
            LOGGER.info("[backend] velocity: allocated {}x{} {} (ping-pong)", w, h, FORMAT)
            true
        } catch (t: Throwable) {
            releaseResources()
            disable("resource allocation failed: ${t}", STEP_RESOURCES)
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
            abandonedSteps = abandonedSteps and STEP_DEPTH_VIEW.inv()
            // 注意：SLF4J 只认 `{}`，`{:#x}` 会退化成"占位符数量不匹配"告警并把原样文本打出来。
            LOGGER.info("[backend] velocity: depth SRV view created for texture {} (fmt={})",
                handle.toULong().toString(16), depth.format)
            true
        } catch (t: Throwable) {
            disable("depth view creation failed: ${t}", STEP_DEPTH_VIEW)
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
            .addBinding(2, 2)   // P76b 物体行缓冲 → register(t2)（texel buffer，无采样器）
        val handle = MCDR_ABI.D3D12CreateGraphicsPipeline(desc)
        if (handle == 0L) {
            this.pipelineError = MCDR_ABI.D3D12LastError()
            disable("dx12CreateGraphicsPipeline failed: ${this.pipelineError}", STEP_PIPELINE)
            return false
        }
        this.pipeline = handle
        abandonedSteps = abandonedSteps and STEP_PIPELINE.inv()
        LOGGER.info("[backend] velocity: pipeline compiled (handle={})", handle.toULong().toString(16))
        return true
    }

    /**
     * P76b：把上一帧的物体快照写进行缓冲，返回写入的行数（0 = 这一帧没有逐物体修正）。
     *
     * 写失败只是**降级**（这一帧退回纯相机重投影），不算放弃：所以这里不像 [writeParameters]
     * 那样调 [disable]，只把行数归零并每 [LOG_INTERVAL_MILLIS] 报一次。
     */
    private fun uploadObjects(): Int {
        val buffer = this.objects
        if (buffer == null) {
            this.objectCount = 0
            return 0
        }
        // 「路径 B」的快照是**世界坐标**，这里减掉本帧相机的位置换成相机相对坐标。
        // 减本帧相机（而不是上一帧）：静态物体两帧世界坐标相同 ⇒ 差分为 0，与
        // 「静态相机 + 静态几何 ⇒ 运动矢量为 0」的约定一致；减错就会把整段相机位移
        // 当成物体速度注入（[D3D12ObjectMotion] 类注释里记的那个陷阱）。
        val camera = D3D12Temporal.currentCameraPosition()
        if (camera == null) {
            this.objectCount = 0
            return 0
        }
        val rows = try {
            D3D12ObjectMotion.writeWorldRows(this.objectScratch, camera[0], camera[1], camera[2])
        } catch (t: Throwable) {
            LOGGER.warn("[backend] velocity: object row read failed — {}", t.toString())
            0
        }
        this.objectCount = rows
        if (rows <= 0) {
            return 0
        }
        return try {
            buffer.map(0L, OBJECT_BYTES, false, true).use { view ->
                val fb: FloatBuffer = view.data().order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                // 只写真正有数据的行；其余保持上一次内容，反正 HLSL 只遍历 ObjectParams.x - 1 行。
                fb.put(this.objectScratch, 0, rows * D3D12ObjectMotion.ROW_FLOATS)
            }
            rows
        } catch (t: Throwable) {
            LOGGER.warn("[backend] velocity: object row upload failed — {}", t.toString())
            this.objectCount = 0
            0
        }
    }

    /**
     * 写参数块。CBV 布局（std140 兼容）：`float4x4 PrevViewProj`(0) + `float4x4 InvCurrViewProj`(64)
     * + `float2 ScreenSize`(128, float 下标 32/33) + `float2 ObjectParams`(136, float 下标 34/35,
     * x = 行数 + 1、y = 位移像素上限) + `float2 JitterPixels`(144, float 下标 36/37, 本帧抖动
     * 的像素偏移) + `float2 PrevJitterPixels`(152, float 下标 38/39, 上一帧抖动的像素偏移)
     * + `float3 CameraDelta`(160, float 下标 40/41/42, 两帧之间相机平移的增量)，
     * 着色器用这三项把运动矢量换到「以历史缓冲为参照」的空间，共 172 B，剩余补齐到 256 B。
     *
     * `ObjectParams.x` 存的是**行数 + 1**：HLSL 里直接拿它当循环上界，省掉一个分支，
     * 而且 0 表示"没有物体"时循环体一次都不进。
     *
     * 用写映射直接写（usage 含 MAP_WRITE，UPLOAD 堆 CPU 可见），不走 `writeToBuffer`：
     * 那条路会经过 [D3D12Temporal.interceptWrite]（label 不匹配虽会放行，但没必要）。
     */
    private fun writeParameters(buffer: D3D12GpuBuffer, prevViewProj: FloatArray,
        invCurrViewProj: FloatArray, w: Int, h: Int, rows: Int,
        jitterX: Float, jitterY: Float, prevJitterX: Float, prevJitterY: Float,
        camDeltaX: Float, camDeltaY: Float, camDeltaZ: Float): Boolean {
        return try {
            buffer.map(0L, CBV_SIZE, false, true).use { view ->
                val fb: FloatBuffer = view.data().order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                fb.put(prevViewProj, 0, 16)
                fb.put(invCurrViewProj, 0, 16)
                fb.put(w.toFloat())
                fb.put(h.toFloat())
                fb.put((rows + 1).toFloat())
                fb.put(OBJECT_MAX_MOTION_PIXELS)
                fb.put(jitterX)
                fb.put(jitterY)
                fb.put(prevJitterX)
                fb.put(prevJitterY)
                fb.put(camDeltaX)
                fb.put(camDeltaY)
                fb.put(camDeltaZ)
                // 补齐到 256 B（= 64 个 float）。CBV_SIZE 是 256，所以上界是 float 下标 64。
                while (fb.position() < 64) {
                    fb.put(0.0f)
                }
            }
            true
        } catch (t: Throwable) {
            disable("parameter upload failed: ${t}", STEP_RESOURCES)
            false
        }
    }

    /**
     * 列主序 4×4 求逆（双精度 + 高斯-约当；奇异或含非有限值时返回 null）。
     *
     * 为什么不再用 JOML 的 `Matrix4f().set(...).invert()`：`set(16 个 float)` 的参数按**行主序**
     * 解释，而 `get(float[])` 按**列主序**写出 —— 一进一出正好把结果转置，拿到的其实是
     * 「逆矩阵的转置」。反投影用错矩阵会让世界位置整片失真；实测那条路在这台机器上还会整片
     * NaN（诊断 `icRow3=[NaN,NaN,NaN,NaN]`），于是每个像素都在 NaN 兜底处返回 0，
     * 运动矢量恒为 0、拖影永不衰减。这里按列主序自己算，语义没有第二种解释。
     */
    private fun invert(matrix: FloatArray): FloatArray? {
        if (matrix.size < 16) {
            return null
        }
        // 增广矩阵：左边 4 列是 A（按行主序排好），右边 4 列是单位阵。
        val augmented = Array(4) { r ->
            DoubleArray(8) { c ->
                when {
                    c < 4 -> matrix[c * 4 + r].toDouble()
                    c - 4 == r -> 1.0
                    else -> 0.0
                }
            }
        }
        for (col in 0 until 4) {
            var pivot = col
            var best = kotlin.math.abs(augmented[col][col])
            for (r in col + 1 until 4) {
                val candidate = kotlin.math.abs(augmented[r][col])
                if (candidate > best) {
                    best = candidate
                    pivot = r
                }
            }
            if (!best.isFinite() || best < 1e-20) {
                return null
            }
            if (pivot != col) {
                val swap = augmented[col]
                augmented[col] = augmented[pivot]
                augmented[pivot] = swap
            }
            val diagonal = augmented[col][col]
            for (c in 0 until 8) {
                augmented[col][c] /= diagonal
            }
            for (r in 0 until 4) {
                if (r == col) {
                    continue
                }
                val factor = augmented[r][col]
                if (factor == 0.0) {
                    continue
                }
                for (c in 0 until 8) {
                    augmented[r][c] -= factor * augmented[col][c]
                }
            }
        }
        val out = FloatArray(16)
        for (r in 0 until 4) {
            for (c in 0 until 4) {
                out[c * 4 + r] = augmented[r][c + 4].toFloat()
            }
        }
        return out
    }

    private fun countSkip(reason: String) {
        skippedCount++
        val now = System.currentTimeMillis()
        if (now - lastLogMillis >= LOG_INTERVAL_MILLIS) {
            lastLogMillis = now
            LOGGER.info("[backend] velocity: skipped ({}) {}", reason, describe())
        }
    }

    private fun logStatus() {
        val now = System.currentTimeMillis()
        if (now - lastLogMillis < LOG_INTERVAL_MILLIS) {
            return
        }
        lastLogMillis = now
        // P76b：逐物体快照的采集计数一起打出来，这样「物体身份/端点有没有采到」可以独立于
        // GPU 侧的绑定先验证（见 known_bugs 里「先看日志再看画面」的约定）。
        LOGGER.info("[backend] velocity: {} | {}", describe(), D3D12ObjectMotion.describe())
    }

    /** 一次性关闭（出错时），并给出原因。 */
    private fun disable(reason: String) {
        disable(reason, 0)
    }

    /**
     * 一次性关闭（出错时）：记下原因（只报一次）并**记下是哪一步放弃的**（[abandonedSteps]）。
     *
     * @param abandonStep 该次失败让哪一个构建步骤变成"不再重试"（0 = 只是本帧失败，不算放弃）。
     *   位含义见 [abandonedSteps]；P108 的 [forceEnable] 会把它们清掉以便重试一次。
     */
    private fun disable(reason: String, abandonStep: Int) {
        if (abandonStep != 0) {
            abandonedSteps = abandonedSteps or abandonStep
        }
        if (this.disabledReason == null) {
            this.disabledReason = reason
            LOGGER.warn("[backend] velocity: disabling velocity pass — {}", reason)
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
        this.objects?.close()
        this.objects = null
        this.objectCount = 0
        this.depthView?.close()
        this.depthView = null
        this.depthViewFor = 0L
        this.width = 0
        this.height = 0
    }

    /** 诊断用一行状态。 */
    @Synchronized
    fun describe(): String = ("enabled=${enabled} active=${isActive()}"
            + " frame=${frameIndex} passes=${passCount} skipped=${skippedCount}"
            + " size=${width}x${height}"
            + " pipeline=${if (pipeline == 0L) "none" else "ok"}"
            + " depthView=${if (depthView == null) "none" else "ok"}"
            + " objects=${objectCount}"
            + (if (disabledReason == null) "" else " disabledReason=${disabledReason}"))

    // ------------------------------------------------------------------
    // HLSL（native 侧入口固定 main；P84 起优先 DXC + SM 6.x/DXIL，退回 D3DCompile SM 5.1）
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
     * 深度反投影 + 两帧重投影 → 屏幕空间运动（像素），再叠加 P76b 的逐物体修正。
     *
     * `SV_Position.xy` 是像素中心，除以屏幕尺寸就是该像素的 uv（与深度采样坐标一致），
     * 所以不需要额外插值量。清屏值（depth 恰为 0 或 1）直接输出 0，避免远平面反投影出垃圾。
     *
     * 逐物体修正（P76b）：物体行里的世界 AABB 与「本帧/上一帧位置」都由 CPU 算好
     * （见 [D3D12ObjectMotion]），GPU 侧只做两件事：① 判断这个像素现在被哪个物体的盒子挡住；
     * ② 刚体平移下把该表面点搬回上一帧位置再投影。
     *
     * 为什么不借「物体中心的屏幕位移」来近似：那样得到的不是这个表面点的位移，只是表面点相对
     * 物体中心的偏移，静止物体也会算出非零值。`world + (prev - cur)` 才是精确的对应关系，
     * 且与相机重投影共用同一个 PrevViewProj（同样不含本帧 jitter），与 P76a 的约定一致。
     * 两条路径都还要再加 `CameraDelta` —— 世界位置是「相对本帧相机」的，PrevViewProj 却没有平移。
     */
    private val PIXEL_HLSL: String = """
        cbuffer DXRMVelocity : register(b0, space0) {
            float4x4 PrevViewProj;
            float4x4 InvCurrViewProj;
            float2 ScreenSize;
            // x = 物体行数 + 1（0 表示没有物体，循环一次都不进），y = 位移像素上限。
            float2 ObjectParams;
            // 本帧抖动带来的像素偏移。uv 是抖动后的屏幕位置，PrevViewProj 是未抖动的
            // 上一帧投影，所以位移里混着这份 jitter，必须减掉才是去抖动空间的位移。
            float2 JitterPixels;
            // 上一帧的抖动。历史缓冲是上一帧**抖动后**的画面：同一个表面点在上一帧里
            // 落在 prevUv + 上一帧jitter 的像素上，所以采样点还得再补回这一项。
            float2 PrevJitterPixels;
            // 两帧之间相机平移的增量（本帧 − 上一帧）。深度反投影出的是**相对本帧相机**的
            // 位置，而 PrevViewProj 的视图矩阵只有旋转，所以必须补上这段平移，否则相机自己
            // 走动/侧移造成的视差整块丢失。
            float3 CameraDelta;
            float CameraDeltaPad;
        };

        Texture2D<float> DXRMDepth : register(t1, space0);
        SamplerState DXRMDepthSampler : register(s1, space0);

        // texel buffer：每个 float 一个元素（绑定类型 2 + texelFormat = R32_FLOAT）。
        Buffer<float> DXRMObjects : register(t2, space0);

        static const uint OBJECT_ROW_FLOATS = 16;
        static const uint OBJECT_FIELD_CUR_X = 1;
        static const uint OBJECT_FIELD_PREV_X = 5;
        static const uint OBJECT_FIELD_HALF_X = 8;
        static const uint OBJECT_FIELD_YAW = 11;

        // 盒子上再外扩一点：挥臂/摆腿的顶点会短暂越出 AABB，不外扩就会漏掉肢体
        // （漏掉只是退回相机重投影，不会更差，但能补上就补）。
        static const float OBJECT_BOX_MARGIN = 0.25;

        // 画世界 AABB 的八个角：轴对齐盒 + 绕 Y 轴 yaw，正好八个顶点，用下标位选正负号。
        float3 objectCorner(float3 center, float3 halfExtent, float yaw, uint index) {
            float3 corner = float3((index & 1u) == 0u ? -halfExtent.x : halfExtent.x,
                                   (index & 2u) == 0u ? -halfExtent.y : halfExtent.y,
                                   (index & 4u) == 0u ? -halfExtent.z : halfExtent.z);
            float c = cos(yaw);
            float s = sin(yaw);
            return center + float3(corner.x * c - corner.z * s, corner.y, corner.x * s + corner.z * c);
        }

        // 像素的世界位置是否落在该物体**上一帧**的盒子里。用上一帧的盒子（而不是本帧的）是
        // 有意的：本 pass 读到的深度、反投影出的世界位置描述的都是**本帧**的几何，所以匹配时
        // 问的是"本帧这个位置，上一帧属于哪个物体"。
        bool insideObjectBox(float3 world, uint base) {
            float3 center = float3(DXRMObjects.Load(base + OBJECT_FIELD_CUR_X),
                                   DXRMObjects.Load(base + OBJECT_FIELD_CUR_X + 1u),
                                   DXRMObjects.Load(base + OBJECT_FIELD_CUR_X + 2u));
            float3 halfExtent = float3(DXRMObjects.Load(base + OBJECT_FIELD_HALF_X),
                                       DXRMObjects.Load(base + OBJECT_FIELD_HALF_X + 1u),
                                       DXRMObjects.Load(base + OBJECT_FIELD_HALF_X + 2u))
                                + OBJECT_BOX_MARGIN;
            float yaw = DXRMObjects.Load(base + OBJECT_FIELD_YAW);

            float3 minCorner = float3(1e30, 1e30, 1e30);
            float3 maxCorner = float3(-1e30, -1e30, -1e30);
            for (uint i = 0u; i < 8u; ++i) {
                float3 corner = objectCorner(center, halfExtent, yaw, i);
                minCorner = min(minCorner, corner);
                maxCorner = max(maxCorner, corner);
            }
            return all(world >= minCorner) && all(world <= maxCorner);
        }

        float4 main(float4 position : SV_Position) : SV_Target0 {
            float2 uv = position.xy / ScreenSize;
            float depth = DXRMDepth.SampleLevel(DXRMDepthSampler, uv, 0).r;
            if (depth <= 0.0 || depth >= 1.0) {
                // 天空/远平面（reverse-Z 下 depth==0）没有可靠的对应点，报 0 让 DLSS 自己判断。
                return float4(0.0, 0.0, 0.0, 0.0);
            }
            float4 ndc = float4(uv.x * 2.0 - 1.0, 1.0 - uv.y * 2.0, depth, 1.0);
            float4 world = mul(InvCurrViewProj, ndc);
            if (abs(world.w) < 1e-6) {
                return float4(0.0, 0.0, 0.0, 0.0);
            }
            world /= world.w;
            float4 prevClip = mul(PrevViewProj, float4(world.xyz + CameraDelta, 1.0));
            // P76d（用户 m08236）：点在**上一帧相机背后**（w<0）时绝不能再做 xy/w —— 负的 w 会把
            // 屏幕位置**镜像**到另一边，得到一个"量级看着合理、实际指向画面另一处"的位移，DLSS
            // 就照着它去历史缓冲里取别处的颜色。这不是残影而是**整片表面瞬间颜色错乱**：用户实测
            // 沙地变白（取到了天空）、树叶变紫，且只在转视角/走动时出现一瞬。
            // 这种像素没有可用的对应点，报 0 交给 DLSS 自己的 disocclusion 处理。
            // 旧写法是 `abs(prevClip.w) < 1e-6`，只挡住了数值退化、恰好放过了 w<0 这一整类。
            if (prevClip.w <= 1e-4) {
                return float4(0.0, 0.0, 0.0, 0.0);
            }
            float2 prevNdc = prevClip.xy / prevClip.w;
            float2 prevUv = float2(prevNdc.x * 0.5 + 0.5, 0.5 - prevNdc.y * 0.5);
            // P76h（DLSS 编程指南）：运动向量的方向是「从**本帧**指向该像素在**上一帧**的位置」，
            // 即 `prevPos - curPos`。旧写法是 `uv - prevUv`，方向整体反了 —— 静止时两边都是 0
            // 所以看不出来，一旦相机移动 DLSS 就照着**反向**位移去历史缓冲取样，于是运动方向
            // 上拖出一条永不收敛的残影（用户 m10388：静止好了，走动/转视角的拖影还在）。
            float2 motion = (prevUv - uv) * ScreenSize + PrevJitterPixels;
            // NaN/inf 兜底：NaN 参与的比较恒为假，所以用"取反的 <=" 写法把它一并挡掉。上一条
            // 守卫之后这里理论上不会触发，留着是因为一旦传出去就是一整块画面的错色。
            if (!(dot(motion, motion) <= 1.0e12)) {
                return float4(0.0, 0.0, 0.0, 0.0);
            }
            // P76b：先到先得 —— 行是按「上一帧采集顺序」排的，一个像素落在多个盒子里时
            // （嵌套的乘客/坐骑、相邻的掉落物）取第一行，够用且稳定。
            uint rowLimit = (uint)ObjectParams.x;
            for (uint row = 0u; row < rowLimit; ++row) {
                uint base = row * OBJECT_ROW_FLOATS;
                if (!insideObjectBox(world, base)) {
                    continue;
                }
                // 这个像素现在看到的是物体的表面点。它上一帧在哪儿？物体是刚体平移，
                // 所以上一帧位置 = 本帧世界位置 + (上一帧物体位置 - 本帧物体位置)。
                // 这是**精确**的对应关系，不需要也不应该去借物体中心的屏幕位移来近似。
                float3 cur = float3(DXRMObjects.Load(base + OBJECT_FIELD_CUR_X),
                                    DXRMObjects.Load(base + OBJECT_FIELD_CUR_X + 1u),
                                    DXRMObjects.Load(base + OBJECT_FIELD_CUR_X + 2u));
                float3 prv = float3(DXRMObjects.Load(base + OBJECT_FIELD_PREV_X),
                                    DXRMObjects.Load(base + OBJECT_FIELD_PREV_X + 1u),
                                    DXRMObjects.Load(base + OBJECT_FIELD_PREV_X + 2u));
                float3 prevWorld = world.xyz + (prv - cur) + CameraDelta;
                float4 prevObjectClip = mul(PrevViewProj, float4(prevWorld, 1.0));
                // 同 main 里的守卫：w<=0 会把位置镜像到画面另一处，得到假位移（见上）。
                if (prevObjectClip.w <= 1e-4) {
                    continue;
                }
                float2 prevObjectNdc = prevObjectClip.xy / prevObjectClip.w;
                float2 prevObjectUv = float2(prevObjectNdc.x * 0.5 + 0.5, 0.5 - prevObjectNdc.y * 0.5);
                float2 objectMotion = (prevObjectUv - uv) * ScreenSize;
                // 坏匹配（张冠李戴的行）位移会大得离谱，直接丢掉，回退相机重投影。
                if (dot(objectMotion, objectMotion) > ObjectParams.y * ObjectParams.y) {
                    continue;
                }
                return float4(objectMotion + PrevJitterPixels, 0.0, 0.0);
            }
            return float4(motion, 0.0, 0.0);
        }
    """.trimIndent()
}
