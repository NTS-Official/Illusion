package io.github.dxegp.experimental.raytracing

class D3D12RtCaptureBackoff(private val capacity: Int = 64) {
    private data class Retry(val target: D3D12RtGeometryPool.CaptureTarget,
                             val failures: Int, val nextNanos: Long)

    private val retries = linkedMapOf<Triple<Int, Long, Int>, Retry>()

    init {
        require(capacity > 0)
    }

    @Synchronized
    fun isReady(target: D3D12RtGeometryPool.CaptureTarget, nowNanos: Long): Boolean {
        val retry = retries[Triple(target.epoch, target.sectionNode, target.layer)] ?: return true
        return retry.target != target || nowNanos - retry.nextNanos >= 0
    }

    @Synchronized
    fun defer(target: D3D12RtGeometryPool.CaptureTarget, nowNanos: Long, failed: Boolean = false) {
        val key = Triple(target.epoch, target.sectionNode, target.layer)
        val previous = retries.remove(key)?.takeIf { it.target.sinceNanos == target.sinceNanos }
        val failures = ((previous?.failures ?: 0) + if (failed) 1 else 0).coerceAtMost(7)
        val delay = if (failed) (50_000_000L shl (failures - 1)).coerceAtMost(2_000_000_000L)
            else 100_000_000L
        retries[key] = Retry(target, failures, nowNanos + delay)
        while (retries.size > capacity) retries.remove(retries.keys.first())
    }

    @Synchronized
    fun retainCurrent(epoch: Int) {
        retries.entries.removeAll { it.key.first != epoch || !D3D12RtGeometryPool.isMissing(it.value.target) }
    }

    @Synchronized
    fun clear() = retries.clear()

    @Synchronized
    fun size(): Int = retries.size
}
