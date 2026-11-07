package io.github.dxegp.experimental.raytracing

import io.github.dxegp.RtDebugView
import io.github.dxegp.d3d12.backend.D3D12CommandEncoderBackend
import io.github.dxegp.d3d12.extern.MCDR_ABI
import io.github.dxegp.d3d12.render.adapter.D3D12Device
import io.github.dxegp.d3d12.render.core.D3D12GpuTexture
import io.github.dxegp.misc.IllusionLogger
import io.github.dxegp.misc.settings.D3D12Settings

object D3D12RtGBuffer {
    const val ATTACHMENTS = 5
    const val ALBEDO = 0
    const val SPECULAR = 1
    const val NORMAL_ROUGHNESS = 2
    const val EMISSIVE = 3
    const val LINEAR_DEPTH = 4
    const val DEFAULT_ROUGHNESS = 0.8f
    const val DEPTH_SCALE = 64.0f

    class Image(val handle: Long, val width: Int, val height: Int)

    class Targets(
        val revision: Long,
        val resourceRevision: Long,
        val frameNanos: Long,
        val width: Int,
        val height: Int,
        private val textures: List<Image>,
        val depth: Long,
        val byteCount: Long,
        val resourceIdentity: Any,
    ) {
        fun texture(index: Int): Image = textures[index]
        fun handle(index: Int): Long = textures[index].handle
        fun contains(image: Image): Boolean = textures.any { it === image }
    }

    @Volatile
    private var targets: Targets? = null
    private var pending: Targets? = null
    private var resources: Targets? = null

    @Volatile
    private var device: D3D12Device? = null
    private var failures = 0

    internal fun deviceForSelfTest(): D3D12Device? = device
    fun isActive(): Boolean = D3D12Settings.rtEnabled
    fun targets(): Targets? = targets
    fun texture(index: Int): Image? = targets?.texture(index)
    fun byteCount(): Long = resources?.byteCount ?: 0L
    fun isCurrent(image: Image): Boolean = targets?.contains(image) == true

    fun begin(device: D3D12Device?, encoder: Long, frameNanos: Long, width: Int, height: Int): Targets? {
        if (pending != null || device == null || !isActive() || width <= 0 || height <= 0) return null
        targets = null
        this.device = device
        val native = D3D12RtNativeFrame.beginGBuffer(encoder, frameNanos, width, height) ?: run {
            failures += 1
            if (failures <= 4 || failures and (failures - 1) == 0) {
                IllusionLogger.warnMessage("rt gbuffer: replay unavailable ($failures)")
            }
            return null
        }
        val previous = resources
        val reuse = previous?.resourceRevision == native.resourceRevision && previous.width == width &&
            previous.height == height && native.colors.indices.all { previous.handle(it) == native.colors[it] }
        val images = native.colors.map { handle -> Image(handle, width, height) }
        val created = Targets(native.revision, native.resourceRevision, native.frameNanos,
            width, height, images, native.depth, native.byteCount,
            if (reuse) requireNotNull(previous).resourceIdentity else Any())
        resources = created
        pending = created
        if (!reuse) IllusionLogger.infoMessage("rt gbuffer: targets ${width}x$height")
        return created
    }

    fun end(encoder: Long, replay: Targets, success: Boolean): Boolean {
        if (pending !== replay) return false
        val ended = D3D12RtNativeFrame.endGBuffer(encoder, replay.revision, success)
        if (ended) pending = null
        targets = replay.takeIf { ended && success }
        return ended
    }

    fun replay(encoder: Long, revision: Long, batches: List<MCDR_ABI.RtGBufferBatch>,
               projection: FloatArray, cameraOffset: RtVec3): Boolean {
        if (pending?.revision != revision) return false
        val recorded = D3D12RtNativeFrame.replayGBuffer(encoder, revision, batches, projection, cameraOffset)
        if (!recorded) {
            failures += 1
            if (failures <= 4 || failures and (failures - 1) == 0) {
                IllusionLogger.warnMessage("rt gbuffer: batch submission failed ($failures)")
            }
        }
        return recorded
    }

    fun replayCaptured(encoder: Long, revision: Long, capture: Long,
                       projection: FloatArray, cameraOffset: RtVec3): Boolean {
        if (pending?.revision != revision) return false
        val recorded = D3D12RtNativeFrame.replayCapturedGBuffer(encoder, revision, capture, projection, cameraOffset)
        if (!recorded) {
            failures += 1
            if (failures <= 4 || failures and (failures - 1) == 0) {
                IllusionLogger.warnMessage("rt gbuffer: captured replay failed ($failures)")
            }
        }
        return recorded
    }

    fun debugTexture(): Image? = when (D3D12Settings.rtDebugView) {
        RtDebugView.ALBEDO.ordinal -> texture(ALBEDO)
        RtDebugView.DEPTH.ordinal -> texture(LINEAR_DEPTH)
        RtDebugView.NORMAL.ordinal -> texture(NORMAL_ROUGHNESS)
        else -> null
    }

    fun blit(encoder: D3D12CommandEncoderBackend, source: Image, destination: D3D12GpuTexture): Boolean {
        if (!isCurrent(source)) return false
        encoder.ensureListOpen()
        return MCDR_ABI.dx12BlitTextureToTexture(encoder.nativeHandle(), source.handle, destination.handle())
    }

    fun tick() {
        if (!isActive()) release()
    }

    fun release() {
        targets = null
        pending = null
        resources = null
    }
}
