package dev.nts.expdxr.dx12

import com.mojang.blaze3d.shaders.ShaderType
import net.minecraft.resources.Identifier
import net.minecraft.server.packs.resources.ResourceProvider
import org.slf4j.LoggerFactory
import java.io.IOException

/**
 * 回退着色器源（P30）。
 *
 * 背景：`Minecraft.<init>` 建 GpuDevice 时传入的 `defaultShaderSource` 是实时 lambda
 * `(id, type) -> shaderManager.getShader(id, type)`，而 `ShaderManager.compilationCache`
 * 在首次 `apply()` 走到第 201 行之前一直是 `Configs.EMPTY`
 * （ShaderManager.java:80），因此对任何 id 都返回 null。
 *
 * 与此同时 `LoadingOverlay.render` **无条件**绘制 mojang_logo
 * （LoadingOverlay.java:117-118），该管线既不在 `Minecraft.loadCriticalShaders()`
 * 预编译的三条之内（Minecraft.java:801-803 → GameRenderer.java:305-308 只预热
 * GUI / GUI_TEXTURED / TRACY_BLIT），也不在首次重载的静态管线循环里（那时还没跑），
 * 于是它成为第一条真正走 `getOrCompilePipeline` → 空源 的管线，编译必然失败。
 *
 * 原版 GL 后端不会因此崩溃——`GlRenderPass.setPipeline`（GlRenderPass.java:84-90）
 * 根本不校验 isValid；而 Vulkan 侧 `VulkanRenderPass.setPipeline`
 * （VulkanRenderPass.java:149-153）会抛 `IllegalStateException`，D3D12 移植沿用了
 * 这一严格语义，所以这个原版竞态在 D3D12 上直接变成致命错误。
 *
 * 修复：捕获 `GameRenderer.preloadUiShader(ResourceProvider)` 收到的 provider
 * ——那正是 `Minecraft.java:802` 传进来的 `vanillaPackResources.asProvider()`，
 * 已经能成功解析 `minecraft:core/position_tex_color`（runClient 日志里
 * gui / gui_textured 两次 COMPILE OK 就是用它编译的）——作为主源失败时的回退。
 * 首次资源重载完成后主源恢复可用，回退自然不再被触发。
 */
object Dx12FallbackShaderSource {
    private val LOGGER = LoggerFactory.getLogger("gl4dx12")

    @Volatile
    private var provider: ResourceProvider? = null

    /** 由 GameRendererPreloadUiShaderMixin 在 preloadUiShader 的 HEAD 处注入。 */
    @JvmStatic
    fun setProvider(value: ResourceProvider?) {
        if (value != null && this.provider == null) {
            LOGGER.info("[dx12-java] captured vanilla shader provider (pre-reload fallback armed)")
        }
        this.provider = value
    }

    /**
     * 与 `GameRenderer.preloadUiShader` 内部的 lambda 等价：按
     * `type.idConverter().idToFile(id)` 定位 vanilla 包内文件并读原始文本。
     * 未捕获到 provider、或该文件不存在时返回 null，由调用方照旧报错。
     */
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
}