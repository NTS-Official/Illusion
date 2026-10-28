package dev.nts.expdxr.d3d12.render.advanced.mesh

import dev.nts.expdxr.d3d12.extern.MCDR_ABI
import java.lang.foreign.Arena
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/** mesh 路径的唯一能力查询出口（不参与绘制）；地形只有 mesh 一条光栅化路径，无 IMR 回退。 */
object D3D12MeshShader {
    private val LOGGER: Logger = LoggerFactory.getLogger("dxr_mc/mesh")

    /** 原因缓冲容量：native 只写入会被截断的 UTF-8 前缀。 */
    private const val REASON_CAPACITY = 512

    private var support: Boolean? = null

    /** FL 12_2 + mesh tier ≥ 1 + DXC 可用；只查一次，结果进程内缓存。 */
    fun isSupported(): Boolean {
        support?.let { return it }
        val supported: Boolean
        val text: String
        Arena.ofConfined().use { arena ->
            val buffer = arena.allocate(REASON_CAPACITY.toLong())
            supported = MCDR_ABI.D3D12QueryMeshSupport(buffer, REASON_CAPACITY) == 1
            text = try {
                buffer.getString(0)
            } catch (t: Throwable) {
                "support query result unreadable: $t"
            }
        }
        support = supported
        LOGGER.info("mesh support={} reason={}", supported, text)
        return supported
    }
}
