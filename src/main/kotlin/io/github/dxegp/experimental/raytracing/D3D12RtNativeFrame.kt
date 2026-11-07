package io.github.dxegp.experimental.raytracing

import io.github.dxegp.RtExposureMode
import io.github.dxegp.RtDenoise
import io.github.dxegp.d3d12.extern.MCDR_ABI
import io.github.dxegp.misc.IllusionLogger
import io.github.dxegp.misc.settings.D3D12Settings
import java.nio.ByteBuffer

internal object D3D12RtNativeFrame {
    private var context = 0L
    private var failed = false
    private var planRevision = 1L

    const val DENOISING_RANGE = 500000f

    fun prepare(): Boolean {
        if (context != 0L) return true
        if (failed) return false
        val raySource = D3D12RtShaderSources.load(D3D12ShaderPacks.sourcePath("rt_dxr_lib.hlsl"))
        val guideSource = D3D12RtShaderSources.load(D3D12ShaderPacks.sourcePath("rt_material_guide.hlsl"))
        val exposureSource = D3D12RtShaderSources.load(D3D12ShaderPacks.sourcePath("rt_exposure.hlsl"))
        val rayDxil = MCDR_ABI.D3D12CompileShaderDxc(raySource, "", "lib_6_8")
        val guideDxil = MCDR_ABI.D3D12CompileShaderDxc(guideSource, "RtMaterialGuide", "cs_6_8")
        val exposureDxil = MCDR_ABI.D3D12CompileShaderDxc(exposureSource, "RtExposure", "cs_6_8")
        val motionDxil = MCDR_ABI.D3D12CompileShaderDxc(D3D12RtMotionShader.build(), D3D12RtMotionShader.ENTRY, "cs_6_8")
        val packDxil = MCDR_ABI.D3D12CompileShaderDxc(D3D12RtNrdPackShader.build(), D3D12RtNrdPackShader.ENTRY, "cs_6_8")
        val signalDxil = MCDR_ABI.D3D12CompileShaderDxc(D3D12RtNrdRadianceShader.build(), D3D12RtNrdRadianceShader.ENTRY, "cs_6_8")
        val remodulateSource = D3D12RtShaderSources.load(D3D12ShaderPacks.sourcePath("rt_nrd_remodulate.hlsl"))
        val remodulateDxil = MCDR_ABI.D3D12CompileShaderDxc(remodulateSource, "RtNrdRemodulate", "cs_6_8")
        val compositeDxil = MCDR_ABI.D3D12CompileShaderDxc(D3D12RtCompositeShader.build(),
            D3D12RtCompositeShader.ENTRY, "cs_6_8")
        if (rayDxil != null && guideDxil != null && exposureDxil != null && motionDxil != null &&
            packDxil != null && signalDxil != null && remodulateDxil != null && compositeDxil != null) {
            context = MCDR_ABI.D3D12CreateRtFrame(rayDxil, guideDxil, exposureDxil,
                motionDxil, packDxil, signalDxil, remodulateDxil, compositeDxil)
        }
        failed = context == 0L
        if (failed) IllusionLogger.warnMessage("rt frame: native context unavailable")
        else IllusionLogger.infoMessage("rt frame: native trace and denoiser inputs ready")
        return !failed
    }

    fun recordTransaction(
        encoder: Long, frameId: Long, worldEpoch: Int, sceneRevision: Long, materialRevision: Long,
        snapshotId: Long, width: Int, height: Int, acceleration: Long, constants: ByteBuffer,
        frameNanos: Long, resetExposure: Boolean,
        coverage: D3D12RtGeometryPool.Coverage,
        plan: D3D12RtPipelinePlan.Plan, history: D3D12RtHistory.Invalidation,
        nrdMatrices: FloatArray, slConstants: FloatArray, source: Long, debugMode: Int,
        jitterDeltaX: Float, jitterDeltaY: Float,
        contentJitterX: Float, contentJitterY: Float,
    ): MCDR_ABI.RtTransactionOutput? {
        if (context == 0L) return null
        val targets = D3D12RtGBuffer.targets() ?: return null
        if (targets.frameNanos != frameNanos || targets.width != width || targets.height != height) return null
        if (coverage.versions.epoch != worldEpoch) return null
        val signalEncoding = when (plan.track) {
            RtDenoise.NRD_SR -> 1
            RtDenoise.RR -> 2
            else -> 0
        }
        val trace = MCDR_ABI.RtFrameRequest(
            frameId, worldEpoch.toLong(), sceneRevision, materialRevision, snapshotId,
            width, height, acceleration, targets.revision, constants, D3D12RtGBuffer.DEPTH_SCALE,
            frameNanos, if (D3D12Settings.rtExposureMode == RtExposureMode.AUTO) 1 else 0,
            D3D12Settings.rtExposureEv, D3D12Settings.rtExposureSpeed, resetExposure,
            signalEncoding, jitterDeltaX, jitterDeltaY, DENOISING_RANGE,
            contentJitterX, contentJitterY,
        )
        return MCDR_ABI.D3D12RtFrameTransaction(encoder, context, trace, coverage.versions.revision,
            coverage.spatial, history.generation, history.frame, planRevision, plan.displayWidth, plan.displayHeight,
            nrdMatrices, slConstants, source, if (plan.superResolution) plan.upscaler.id else 0, debugMode,
            D3D12RtStats.nativeTransactionQueries(), D3D12RtStats.SLOT_TRACE)
    }

    fun resetDenoisers() {
        planRevision += 1L
    }

    fun beginGBuffer(encoder: Long, frameNanos: Long, width: Int, height: Int): MCDR_ABI.RtGBufferOutput? {
        if (!prepare()) return null
        return MCDR_ABI.D3D12RtFrameBeginGBuffer(encoder, context, frameNanos, width, height)
    }

    fun endGBuffer(encoder: Long, revision: Long, success: Boolean): Boolean = context != 0L &&
        MCDR_ABI.D3D12RtFrameEndGBuffer(encoder, context, revision, success)

    fun replayGBuffer(encoder: Long, revision: Long, batches: List<MCDR_ABI.RtGBufferBatch>,
                      projection: FloatArray, cameraOffset: RtVec3): Boolean = context != 0L &&
        MCDR_ABI.D3D12RtFrameReplayGBuffer(encoder, context, revision, batches, projection,
            cameraOffset.x, cameraOffset.y, cameraOffset.z)

    fun replayCapturedGBuffer(encoder: Long, revision: Long, capture: Long,
                             projection: FloatArray, cameraOffset: RtVec3): Boolean = context != 0L &&
        MCDR_ABI.D3D12RtFrameReplayCapturedGBuffer(encoder, context, revision, capture, projection,
            cameraOffset.x, cameraOffset.y, cameraOffset.z)

    fun release() {
        if (context != 0L && !MCDR_ABI.D3D12ReleaseRtFrame(context)) return
        D3D12RtGBuffer.release()
        context = 0L
        failed = false
    }
}
