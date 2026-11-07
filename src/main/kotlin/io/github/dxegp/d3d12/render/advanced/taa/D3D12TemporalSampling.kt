package io.github.dxegp.d3d12.render.advanced.taa

object D3D12TemporalSampling {
    data class Sample(val x: Double, val y: Double, val enabled: Boolean)

    fun sample(frameIndex: Long, phaseCount: Int, enabled: Boolean): Sample {
        require(frameIndex >= 0L && phaseCount > 0)
        if (!enabled) return Sample(0.0, 0.0, false)
        val index = frameIndex % phaseCount + 1L
        return Sample(radicalInverse(index, 2) - 0.5, radicalInverse(index, 3) - 0.5, true)
    }

    fun jitterProjection(pristine: FloatArray, clipX: Double, clipY: Double): FloatArray {
        require(pristine.size == 16 && clipX.isFinite() && clipY.isFinite())
        val result = pristine.copyOf()
        for (column in 0 until 4) {
            val offset = column * 4
            result[offset] = (pristine[offset] + clipX * pristine[offset + 3]).toFloat()
            result[offset + 1] = (pristine[offset + 1] + clipY * pristine[offset + 3]).toFloat()
        }
        return result
    }

    private fun radicalInverse(index: Long, base: Int): Double {
        var result = 0.0
        var fraction = 1.0
        var remaining = index
        while (remaining > 0L) {
            fraction /= base
            result += fraction * (remaining % base)
            remaining /= base
        }
        return result
    }
}
