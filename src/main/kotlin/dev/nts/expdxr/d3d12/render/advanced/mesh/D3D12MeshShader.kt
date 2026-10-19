package dev.nts.expdxr.d3d12.render.advanced.mesh

import dev.nts.expdxr.d3d12.extern.MCDR_ABI
import java.lang.foreign.Arena
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * P124（mesh 引入 Step 1）：mesh 路径的**单一状态出口**。
 *
 * <p>这个类在引入计划里原本是空壳（`class D3D12MeshShader {}`）。它现在只负责三件事，
 * 不参与任何绘制：
 * 1. **能力与原因**：`mcdr_dx12QueryMeshSupport` 的唯一调用点（结果缓存一次）。UI 置灰、
 *    诊断日志、后续的路径分流都以它为判据 —— 保证"控件可用"与"真的能画"是同一个答案；
 * 2. **几何路径开关**：`IMR`（现状）/ `MESH_SECTION` / `MESH_MESHLET`。Step 1 阶段只有
 *    `IMR` 会真正生效，另外两档只用于把开关和诊断打通（原始值来自系统属性
 *    `dxrmc.geometryPath`，正式的视频设置项在 Step 3 接）；
 * 3. **调试模式**：`OFF` / `CLUSTER` / `STATS`（原始值来自 `dxrmc.meshDebug`）。
 */
object D3D12MeshShader {
    private val LOGGER: Logger = LoggerFactory.getLogger("dxr_mc/mesh")

    /** 地形几何由谁光栅化。取值与计划里的 `dxrMcGeometryPath` 一一对应。 */
    enum class GeometryPath {
        IMR,
        MESH_SECTION,
        MESH_MESHLET,
        ;

        companion object {
            fun fromOption(raw: String?): GeometryPath {
                if (raw.isNullOrBlank()) {
                    return IMR
                }
                return entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) } ?: IMR
            }
        }
    }

    /** 调试视图：Step 5 才真正使用，这里先把开关形状定下来。 */
    enum class DebugMode {
        OFF,
        CLUSTER,
        STATS,
        ;

        companion object {
            fun fromOption(raw: String?): DebugMode {
                if (raw.isNullOrBlank()) {
                    return OFF
                }
                return entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) } ?: OFF
            }
        }
    }

    /** 原因缓冲的容量：native 只会写入会被截断的 UTF-8 前缀。 */
    private const val REASON_CAPACITY = 512

    private var support: Boolean? = null
    private var reason: String = "not queried"

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
        reason = text
        LOGGER.info("mesh support={} reason={}", supported, text)
        return supported
    }

    /** 最近一次能力查询写出的原因（不支持的判据）。 */
    fun unsupportedReason(): String = reason

    /**
     * 用户请求的几何路径。Step 1 里 `MESH_*` **不会**真的接管地形，读取它的地方必须先用
     * [isSupported] 与后续的"地形适配是否就绪"判断再过一遍。
     */
    fun requestedPath(): GeometryPath =
        GeometryPath.fromOption(System.getProperty("dxrmc.geometryPath"))

    /** 实际生效的路径：不支持 mesh 时永远是 [GeometryPath.IMR]，且不静默降级（调用方可读原因）。 */
    fun effectivePath(): GeometryPath {
        val requested = requestedPath()
        if (requested == GeometryPath.IMR) {
            return GeometryPath.IMR
        }
        return if (isSupported()) requested else GeometryPath.IMR
    }

    fun debugMode(): DebugMode = DebugMode.fromOption(System.getProperty("dxrmc.meshDebug"))
}
