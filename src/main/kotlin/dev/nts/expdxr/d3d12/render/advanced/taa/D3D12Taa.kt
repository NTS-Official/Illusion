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
 * TAA 的 JVM 侧薄壳：resolve 与历史纹理都在 native（`DeviceContext::applyTaaToTexture`），
 * 这里每帧只算 jitter / 上一帧 jitter / 历史代数 / 锐化量。
 *
 * 挂点：世界层画完、GUI 未画。运动矢量方向 = `prevPos - curPos`（像素单位）。
 * 见 docs/antialiasing/README.md §1、§3.2。
 */
object D3D12TAA {

    private val LOGGER = LoggerFactory.getLogger("dxrmc-taa")

    /** 本帧权重 `out = mix(history, current, BLEND)`；1/16 即 16 帧时间常数。 */
    private const val BLEND = 0.0625f

    private const val FRAME_NONE = -1L

    // 状态（GPU 资源全在 native，这里只剩每帧记账）

    /** `beginFrame()` 推进的帧号，与 [recordedFrame] 配对，保证每帧只录一次。 */
    private var frameIndex = 0L
    private var recordedFrame = FRAME_NONE

    /** 上次交给 native 的 [D3D12Temporal.historyGeneration]；变了就要求 native 丢弃历史。 */
    private var historyGeneration = -1L

    /** 非空表示自我关闭（只记第一次的原因，避免每帧刷屏/重复尝试）。 */
    private var disabledReason: String? = null

    // 对外入口

    @JvmStatic
    fun isSelected(): Boolean = D3D12AntiAliasing.current() == D3D12AntiAliasing.TAA

    /** 是否真的在跑（选项 + 没有自我关闭）。 */
    @JvmStatic
    fun isActive(): Boolean = isSelected() && disabledReason == null

    /**
     * 世界层画完、GUI 未画时调用一次；任何一步不满足都安静跳过（等同没有 TAA）。
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
     * 每帧一次时域采集（main pass RETURN，必须在 `clearDepthTexture` 之前）；DLSS 开着时让路。
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

    // 录制

    /**
     * 把本帧的 TAA 交给 native（命令列表必须已打开）。`reset` = 只写当前帧、不与历史混合；
     * `taaAmount > 0` 时回填那一趟走 5-tap unsharp。true = 这一帧真的做了 TAA。
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
        // 上一帧 jitter 也要给：historyUv 用 (PrevJitter - Jitter) 补偿。
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
     * 作废跨帧状态：不释放 GPU 资源（历史归 native），代数清零让下一帧 [record] 带 reset。
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
        LOGGER.error("TAA disabled: {}", reason)
    }
}
