package dev.nts.expdxr.d3d12.render.advanced.mesh

import dev.nts.expdxr.d3d12.extern.MCDR_ABI

/**
 * P124（mesh 引入 Step 1 / 计划 §4.1）：**独立的 mesh 管线缓存**。
 *
 * <p>为什么不能复用 `D3D12Device.pipelineCache`：那张表以 vanilla `RenderPipeline` 为键，
 * 而 mesh 管线（MS/AS 子对象、无 IA）根本不可能由 `RenderPipeline` 表达。若不建这张表，
 * 生产路径每次进入都要重新 `CreatePipelineState`，等于每帧卡一下。
 *
 * <p>键覆盖：逻辑用途、目标格式、深度/剔除/混合、flipY，以及**着色器代次**
 * （资源重载或地形适配器重新生成时递增，使旧管线整体失效）。DXIL 层的磁盘缓存由 native 的
 * `compileHlslToDxil` 负责，这里只缓存 PSO 本身。
 *
 * <p>所有权：管线对象由设备持有，`releaseAll()` 必须在设备销毁**之前**调用
 * （`D3D12Device.clearPipelineCache()` 是既有入口）。
 */
class D3D12MeshPipelineCache {
    /** 逻辑用途。同一用途的 PSO 参数由调用方固定，避免"每帧算出不同键"这种缓存毒药。 */
    enum class Kind {
        TERRAIN_SOLID,
        TERRAIN_CUTOUT,
        BADGE,
        DEBUG,
    }

    /**
     * 值语义的缓存键（`HashMap` 必须按 `equals`/`hashCode` 查找 —— 用 `IdentityHashMap`
     * 会让每次新建的键永远 miss，重演 `pipelineCacheFlipY` 记录过的那个坑）。
     */
    data class Key(
        val kind: Kind,
        val colorFormat: Int,
        val hasDepth: Boolean,
        val depthWrite: Boolean,
        val cull: Boolean,
        val polygonMode: Int,
        val blendEnabled: Boolean,
        val flipY: Boolean,
        val shaderGeneration: Int,
    )

    private val pipelines: MutableMap<Key, Long> = HashMap()
    private var shaderGeneration: Int = 0

    /** 命中/未命中计数，用于"第二次进世界没有新建 PSO"的验收（计划 §4.1）。 */
    var hits: Int = 0
        private set
    var misses: Int = 0
        private set

    /** 当前着色器代次；调用方建键时带上它。 */
    fun generation(): Int = shaderGeneration

    /** 构造一个属于当前代次的键。 */
    fun key(kind: Kind, colorFormat: Int, hasDepth: Boolean, depthWrite: Boolean, cull: Boolean,
            polygonMode: Int, blendEnabled: Boolean, flipY: Boolean): Key =
        Key(kind, colorFormat, hasDepth, depthWrite, cull, polygonMode, blendEnabled, flipY, shaderGeneration)

    /**
     * 取缓存，未命中时调用 [create]。`create` 返回 0 表示创建失败，**不会**写入缓存
     * （失败结果不缓存，下一次仍会重试）。
     */
    fun getOrCreate(key: Key, create: () -> Long): Long {
        pipelines[key]?.let { cached ->
            hits++
            return cached
        }
        val created = create()
        if (created == 0L) {
            return 0L
        }
        misses++
        pipelines[key] = created
        return created
    }

    /** 着色器/资源代次变化：旧管线全部失效。 */
    fun invalidateShaders() {
        shaderGeneration++
        releaseAll()
    }

    /** 释放全部管线（设备销毁前必须调用；重复调用安全）。 */
    fun releaseAll() {
        pipelines.values.forEach { pipeline -> MCDR_ABI.D3D12DestroyPipeline(pipeline) }
        pipelines.clear()
    }

    fun summary(): String =
        "mesh PSO cache: ${pipelines.size} live, hits=$hits, misses=$misses, gen=$shaderGeneration"
}