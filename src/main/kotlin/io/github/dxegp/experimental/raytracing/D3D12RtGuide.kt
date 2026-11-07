package io.github.dxegp.experimental.raytracing

import io.github.dxegp.misc.IllusionLogger

/**
 * 引导数据的单一定义（S4 4-b）：NRD、DLSS-D、FFX 都从这一份清单取输入，任何一家要新字段就加在这里，
 * 不许各自再写一份。清单本身不分配资源，只把已经在跑的那几张附件按固定顺序列出来。
 *
 * 顺序就是 SRV 槽位顺序：消费方按 [srvHandles] 拿到一组句柄直接绑，不要自己拼。
 * 见 docs/rt/README.md §19.7
 */
internal object D3D12RtGuide {

    /** 一项引导数据：[source] 只有 `gbuffer` 与 `motion` 两种，前者按 G-Buffer 附件取。 */
    class Slot(val name: String, val format: String, val source: String)

    val SLOTS: List<Slot> = listOf(
        Slot("albedo", "RGBA8", "gbuffer"),
        Slot("specular", "RGBA8", "gbuffer"),
        Slot("normal-roughness", "RGBA16F", "gbuffer"),
        Slot("linear-depth", "RGBA16F", "gbuffer"),
        Slot("motion", "RGBA16F", "motion"),
    )

    private var reported = false

    /**
     * 五个槽位的句柄，顺序与 [SLOTS] 一致。
     * 缺任何一项就返回 null：少绑一个槽位时原生会按数量校验拒掉整次派发，调用方应当整条跳过而不是绑半套。
     */
    fun srvHandles(motion: Long): LongArray? {
        if (motion == 0L) return null
        return longArrayOf(
            D3D12RtGBuffer.texture(D3D12RtGBuffer.ALBEDO)?.handle ?: return null,
            D3D12RtGBuffer.texture(D3D12RtGBuffer.SPECULAR)?.handle ?: return null,
            D3D12RtGBuffer.texture(D3D12RtGBuffer.NORMAL_ROUGHNESS)?.handle ?: return null,
            D3D12RtGBuffer.texture(D3D12RtGBuffer.LINEAR_DEPTH)?.handle ?: return null,
            motion,
        )
    }

    /** 一次进程一行：报告五个槽位是否齐、哪一项还是占位值。 */
    fun noteOnce(motion: Long) {
        if (reported) return
        reported = true
        val handles = srvHandles(motion)
        if (handles == null) {
            IllusionLogger.infoMessage("rt guide: incomplete, motion or gbuffer missing")
            return
        }
        IllusionLogger.infoMessage("rt guide: ${handles.size} slot(s), material factors ready")
    }
}
