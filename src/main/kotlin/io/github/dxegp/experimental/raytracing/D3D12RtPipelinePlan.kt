package io.github.dxegp.experimental.raytracing

import io.github.dxegp.RtDenoise
import io.github.dxegp.SrBackend
import io.github.dxegp.experimental.superresolution.SuperResolution
import io.github.dxegp.misc.IllusionLogger
import io.github.dxegp.misc.settings.D3D12Settings

/**
 * 光追管线的唯一真值表：分辨率、要不要降噪、要不要超分，全从 [configured] 一处算出来。
 *
 * 谁要分辨率就调 [configured]，不要各自拿显示分辨率再乘一次——分母对不齐的表现是
 * 「历史读错区、画面边缘糊成一圈」，不报错、也不在日志里。见 docs/rt/README.md §18。
 */
internal object D3D12RtPipelinePlan {
    private const val MIN_SIDE = 16

    /** 一帧的形状：谁在什么分辨率上跑、后面还要不要再过一道降噪与超分。 */
    data class Plan(
        val enabled: Boolean,
        val track: RtDenoise,
        val upscaler: SrBackend,
        val displayWidth: Int,
        val displayHeight: Int,
        val renderWidth: Int,
        val renderHeight: Int,
        val gbufferWidth: Int,
        val gbufferHeight: Int,
        val traceWidth: Int,
        val traceHeight: Int,
        val denoise: Boolean,
        val superResolution: Boolean,
    )

    private var lastLine: String? = null

    /** 按当前配置与显示分辨率算出这一帧的形状；分辨率下限 [MIN_SIDE]，避免缩到 0 让派发退化。 */
    fun configured(displayWidth: Int, displayHeight: Int): Plan {
        return calculate(
            displayWidth, displayHeight, D3D12Settings.rtDenoise, D3D12Settings.rtRenderScale,
            D3D12Settings.rtEnabled,
            SuperResolution.backend(),
        )
    }

    fun calculate(
        displayWidth: Int,
        displayHeight: Int,
        track: RtDenoise,
        percent: Int,
        enabled: Boolean,
        upscaler: SrBackend,
    ): Plan {
        require(displayWidth > 0 && displayHeight > 0)
        val scale = percent.coerceIn(25, 100)
        val renderScale = if (track == RtDenoise.OFF) 100 else scale
        val renderWidth = side(displayWidth, renderScale)
        val renderHeight = side(displayHeight, renderScale)
        val denoise = enabled && (track == RtDenoise.RR || track == RtDenoise.NRD_SR)
        val superResolution = denoise && upscaler != SrBackend.NONE &&
            (renderWidth != displayWidth || renderHeight != displayHeight)
        val gbufferWidth = if (enabled) renderWidth else 0
        val gbufferHeight = if (enabled) renderHeight else 0
        val traceWidth = if (enabled) renderWidth else 0
        val traceHeight = if (enabled) renderHeight else 0
        return Plan(
            enabled = enabled,
            track = track,
            upscaler = upscaler,
            displayWidth = displayWidth,
            displayHeight = displayHeight,
            renderWidth = renderWidth,
            renderHeight = renderHeight,
            gbufferWidth = gbufferWidth,
            gbufferHeight = gbufferHeight,
            traceWidth = traceWidth,
            traceHeight = traceHeight,
            denoise = denoise,
            superResolution = superResolution,
        )
    }

    /** 表变了才打一行：分辨率、轨道、G-Buffer 尺寸都能一眼对上。 */
    fun note(plan: Plan) {
        val line = describe(plan)
        if (line == lastLine) return
        lastLine = line
        IllusionLogger.infoMessage(line)
    }

    fun describe(plan: Plan): String = "rt: denoise=${plan.track.name.lowercase()} " +
        "render=${plan.renderWidth}x${plan.renderHeight} " +
        "gbuffer=${plan.gbufferWidth}x${plan.gbufferHeight} superresolution=${plan.upscaler.name.lowercase()}"

    private fun side(display: Int, percent: Int): Int {
        val scaled = display * percent / 100
        return scaled.coerceAtLeast(minOf(MIN_SIDE, display))
    }
}
