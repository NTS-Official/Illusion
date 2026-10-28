package dev.nts.expdxr.d3d12.render.shader

import com.mojang.renderpearl.api.pipeline.ShaderType
import net.minecraft.resources.Identifier
import net.minecraft.server.packs.resources.ResourceProvider
import org.slf4j.LoggerFactory
import java.io.IOException

/**
 * 回退着色器源：`ShaderManager` 缓存在首次 `apply()` 之前恒空，首个 `LoadingOverlay` 管线会因此拿不到源；
 * 这里用 `preloadUiShader` 捕获的 vanilla `ResourceProvider` 顶上，首次资源重载后主源恢复、不再触发。
 * 见 docs/shaders/README.md §4
 */
object D3D12FallbackShaderSource {
    private val LOGGER = LoggerFactory.getLogger("dxrmc")

    @Volatile
    private var provider: ResourceProvider? = null

    /** 由 GameRendererPreloadUiShaderMixin 在 preloadUiShader 的 HEAD 处注入（唯一调用点）。 */
    @JvmStatic
    fun setProvider(value: ResourceProvider?) {
        if (value != null && this.provider == null) {
            LOGGER.info("captured vanilla shader provider (pre-reload fallback armed)")
        }
        this.provider = value
    }

    /** 按 `type.idConverter().idToFile(id)` 定位 vanilla 包内文件；读不到返回 null，由调用方照旧报错。 */
    @JvmStatic
    fun get(id: Identifier, type: ShaderType): String? {
        val rp = this.provider ?: return null
        val location = type.idConverter().idToFile(id)
        return try {
            rp.openAsReader(location).use { it.readText() }
        } catch (e: IOException) {
            LOGGER.error("Coudln't preload {} shader {}: {}", type, id, e)
            null
        }
    }

    /** include 走 `shaders/include/<path>`（与 `ShaderManager.listAllIncludes` 同一目录）。 */
    @JvmStatic
    fun getInclude(id: Identifier): String? {
        val rp = this.provider ?: return null
        val location = Identifier.fromNamespaceAndPath(id.namespace, "shaders/include/" + id.path)
        return try {
            rp.openAsReader(location).use { it.readText() }
        } catch (e: IOException) {
            LOGGER.debug("Couldn't preload shader include {}: {}", id, e.message)
            null
        }
    }
}
