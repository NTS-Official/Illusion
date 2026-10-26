package dev.nts.expdxr.d3d12.render.advanced.taa

import dev.nts.expdxr.d3d12.extern.MCDR_ABI
import dev.nts.expdxr.d3d12.render.adapter.D3D12Device
import dev.nts.expdxr.d3d12.render.advanced.D3D12AntiAliasing
import dev.nts.expdxr.d3d12.render.advanced.D3D12PostSharpen
import dev.nts.expdxr.d3d12.render.core.D3D12GpuTexture
import dev.nts.expdxr.dxrmc.sr.dlss.DeepLearningSuperSampling
import net.minecraft.client.Minecraft
import org.slf4j.LoggerFactory

/**
 * P77 → P152：TAA（时域抗锯齿）的 JVM 侧入口。
 *
 * 这条链路原先整条都录在 JVM 侧（历史纹理、参数块、PSO、HLSL 全在这里），P152 起 **resolve 本体
 * 搬进 native**（`MCDR\src\core\d3d12_device.cpp` 的 `applyTaaToTexture`）：
 *
 * - 历史纹理的乒乓、RTV 堆、根签名与 PSO、参数块、两趟 pass（resolve → 回填主目标）都由
 *   `DeviceContext` 持有，跨帧存活；
 * - 本类只负责**每帧算出 native 需要的那几个值**（jitter、历史代数是否变了、后置锐化量），
 *   然后把它们连同主目标与运动矢量的句柄交给 [MCDR_ABI.dx12ApplyTaaToTexture]。
 *
 * 这样做的直接好处是历史纹理不再走 `D3D12GpuTexture` 的创建/销毁路径：那条路径在传送、改窗口
 * 尺寸时会于**上一帧的命令列表还在飞**的时候销毁资源（见旧版 `reset()` 的注释，实测能把设备打
 * 掉），而 native 的重建发生在录制新一帧的时候，天然安全。
 *
 * ## 调用时机
 * 世界层画完、GUI 还没画的那一刻（`GameRendererSmaaWorldMixin` → [applyIfEnabled]）。这与 SMAA
 * 的策略一致：抗锯齿只作用在世界层，UI 文字与面板保持锐利。
 *
 * ## 运动矢量的方向约定
 * [D3D12Velocity] 产出的 mv 是 **`prevPos - curPos`**（像素单位，NVIDIA DLSS 指南的约定），所以
 * 历史位置 = `本像素 + mv`（native 的 PS 里就是 `historyUv = (coord + 0.5 + mv) * RcpScreenSize`）。
 * 方向反了会让 TAA 沿运动方向拖一条永不收敛的残影。
 */
object D3D12TAA {

    private val LOGGER = LoggerFactory.getLogger("dxrmc-taa")

    /**
     * 本帧权重：`out = mix(history, current, BLEND)`。
     *
     * 1/16 即 16 帧的时间常数。偏大（1/8）收敛快但降噪弱、动态场景更糊；偏小（1/32）更平滑
     * 但运动边缘容易残留。0.0625 是常见起点，实机看效果再调。
     */
    private const val BLEND = 0.0625f

    private const val FRAME_NONE = -1L

    // ------------------------------------------------------------------
    // 状态（GPU 资源全在 native，这里只剩每帧的记账）
    // ------------------------------------------------------------------

    /** `beginFrame()` 推进的帧号，与 [recordedFrame] 配对，保证每帧只录一次。 */
    private var frameIndex = 0L
    private var recordedFrame = FRAME_NONE

    /** 上次交给 native 的 [D3D12Temporal.historyGeneration]；变了就要求 native 丢弃历史。 */
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
        val texture = (view as? dev.nts.expdxr.d3d12.render.core.D3D12GpuTextureView)?.texture() as? D3D12GpuTexture
            ?: return
        val encoder = D3D12Device.sharedCommandEncoder() ?: return
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
     * 把本帧的 TAA 交给 native（列表必须已经打开，见
     * `D3D12CommandEncoderBackend.recordTaaPass`）。
     *
     * native 拿到的 [reset] 为 true 时只把当前帧写进历史、不与上一帧混合（首帧、传送、换世界、
     * 改分辨率这些历史毫无意义的时刻）；`sharpenAmount > 0` 时回填那一趟走 5-tap unsharp
     * （[D3D12PostSharpen.taaAmount]），否则直通 blit。
     *
     * @return true = 这一帧真的做了 TAA（历史已更新、主目标已被回填）。
     */
    fun record(ctx: Long, device: D3D12Device?, scene: D3D12GpuTexture): Boolean {
        if (disabledReason != null || !isSelected()) {
            return false
        }
        if (this.recordedFrame == this.frameIndex) {
            return true
        }
        if (device == null) {
            return false
        }
        if (!MCDR_ABI.supportsTaaToTexture()) {
            disable("loaded MCDR.dll has no mcdr_dx12ApplyTaaToTexture export")
            return false
        }
        val w = scene.getWidth(0)
        val h = scene.getHeight(0)
        if (w <= 0 || h <= 0) {
            return false
        }
        val motion = D3D12Velocity.currentMotionView()
        if (motion == null) {
            return false
        }

        // 代数变化（传送 / 换世界 / 切分辨率 / 关设备）=> 上一帧画面与这一帧没有任何关系。
        val generation = D3D12Temporal.historyGeneration
        val reset = this.historyGeneration != generation
        this.historyGeneration = generation

        // 本帧投影实际用了多大的亚像素偏移；历史取样必须按它换回未抖动网格。
        val jitterX = D3D12Temporal.currentJitterX.toFloat()
        val jitterY = D3D12Temporal.currentJitterY.toFloat()
        // P134：上一帧 jitter 也要一起给 —— MV 现在是真实运动，historyUv 用 (PrevJitter - Jitter) 补偿。
        val prevJitterPair = D3D12Temporal.previousJitter()

        val result = MCDR_ABI.dx12ApplyTaaToTexture(
            ctx = ctx,
            scene = scene.handle(),
            motion = motion.handle(),
            jitterX = jitterX,
            jitterY = jitterY,
            prevJitterX = prevJitterPair[0].toFloat(),
            prevJitterY = prevJitterPair[1].toFloat(),
            blend = BLEND,
            reset = reset,
            sharpenAmount = D3D12PostSharpen.taaAmount,
        )
        // 无论 native 做没做成都算"这一帧已处理过"，避免同一帧重复提交。
        this.recordedFrame = this.frameIndex
        if (result < 0) {
            disable("native applyTaaToTexture failed: ${MCDR_ABI.D3D12LastError()}")
            return false
        }
        return result > 0
    }

    /** 每帧开始时调用（由 `D3D12Temporal.beginFrame` 驱动）：推进帧号，与 [recordedFrame] 配对。 */
    @Synchronized
    fun beginFrame() {
        this.frameIndex++
    }

    /**
     * 作废跨帧状态（退出世界 / 传送 / 换分辨率 / 关设备时调用）。
     *
     * 不再需要在这里释放任何 GPU 资源：历史纹理归 native 的 `DeviceContext` 所有，重建发生在
     * 录制新一帧的时候（尺寸对不上时由 `taaEnsureHistory` 负责），因此不会出现"上一帧命令列表还
     * 在飞、JVM 侧就把纹理 close 掉"的设备崩溃（旧版 `reset()` 的注释记录过那次实测）。
     *
     * 代数清零的效果是下一帧 [record] 会带 `reset = true`，让 native 只写当前帧、不与旧历史混合。
     */
    @Synchronized
    fun reset() {
        frameIndex = 0L
        recordedFrame = FRAME_NONE
        historyGeneration = -1L
        disabledReason = null
    }

    private fun disable(reason: String) {
        if (this.disabledReason != null) {
            return
        }
        this.disabledReason = reason
        LOGGER.error("[backend] TAA 已关闭：{}", reason)
    }
}
