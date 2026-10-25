package dev.nts.expdxr.d3d12.render.advanced.mesh

import dev.nts.expdxr.d3d12.extern.MCDR_ABI
import java.lang.foreign.Arena
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * P124/P217：mesh 路径的**能力查询出口**（不参与绘制）。
 *
 * <p>`mcdr_dx12QueryMeshSupport` 的唯一调用点，结果缓存一次 —— 设备能力在进程生命周期内
 * 不变，重复查询只会多一次 FFM 调用。地形现在只有 mesh 一条光栅化路径（IMR 回退已按用户
 * 要求删除），所以本对象只回答"这台机器能不能建 mesh 管线"，不再承担路径分流。
 */
object D3D12MeshShader {
    private val LOGGER: Logger = LoggerFactory.getLogger("dxr_mc/mesh")

    /** 原因缓冲的容量：native 只会写入会被截断的 UTF-8 前缀。 */
    private const val REASON_CAPACITY = 512

    private var support: Boolean? = null

    /**
     * 是否支持 mesh 管线（FL 12_2 + mesh tier ≥ 1 + DXC 可用）。结果只查一次：
     * 设备能力在进程生命周期内不变，重复查询只会多一次 FFM 调用。
     */
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
