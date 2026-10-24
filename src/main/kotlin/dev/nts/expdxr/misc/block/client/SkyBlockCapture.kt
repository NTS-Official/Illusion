package dev.nts.expdxr.misc.block.client

import com.mojang.blaze3d.pipeline.TextureTarget
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.renderpearl.api.GpuFormat
import com.mojang.renderpearl.api.textures.FilterMode
import com.mojang.renderpearl.api.textures.GpuSampler
import com.mojang.renderpearl.api.textures.GpuTexture
import com.mojang.renderpearl.api.textures.GpuTextureView
import dev.nts.expdxr.Illusion.Companion.MODID
import java.util.Optional
import java.util.OptionalDouble
import net.minecraft.client.CloudStatus
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.state.level.LevelRenderState
import net.minecraft.client.renderer.texture.AbstractTexture
import net.minecraft.resources.Identifier
import net.minecraft.util.ARGB
import org.joml.Vector4f

/**
 * 天空离屏捕获：把这一帧已经画进主渲染目标的天空拷到一张自己的纹理上。
 *
 * 为什么不是「再调一次 `SkyRenderer.render`」：原版 [net.minecraft.client.renderer.SkyRenderer] 的渲染目标是
 * 构造参数写死的，要画到第二张目标就得再造一个 SkyRenderer（重复建星星/日月缓冲区、重复实现一遍
 * 天空的绘制顺序与雾参数），而 `copyTextureToTexture` 在 GL、Vulkan、D3D12 三个后端上都已经实现，
 * 一次区域拷贝既便宜又和渲染顺序无关。
 *
 * 时序：拷贝发生在 [net.neoforged.neoforge.client.event.RenderLevelStageEvent.AfterSky]，也就是
 * `LevelRenderer` 的天空 pass 已经执行完、主 pass 还没开始的时刻（原版在那里不提供活动 RenderPass，
 * 所以这里只记录一张拷贝指令，不自己开 pass）。是否要拷由方块实体的提交决定（见
 * [SkyBlockEntityRenderer.submit]）——世界中没有天空方块时，一帧一点额外开销都没有。
 *
 * 云层要自己补画：26.3 的天空 pass 里只有天球、太阳、月亮、星星，云是在主 pass 才画的
 * （`LevelRenderer` 第 738 行 `cloudRenderer.render(...)`），而拷贝必须发生在主 pass 之前，
 * 所以拷到的图里一定没有云。补画用的还是原版那一个 `CloudRenderer`（`LevelRenderer.cloudRenderer()`
 * 是公开的），它的云计算网格在这一帧的 `prepareTranslucents()` 里早就准备好了 —— 那个方法在帧图
 * 构建阶段（`addMainPass` 里）就执行，早于 `frame.execute`。
 *
 * 尺寸必须取**纹理自己的**尺寸，不能用 `RenderTarget.width/height`：那是「想要的尺寸」，本项目
 * 的渲染缩放（`renderScale`）在 `RenderTargetCreateBuffersScaleMixin` 里把真正创建的纹理缩小了，
 * 两者在开了缩放的机器上差得很远（例如 2560x1334 的窗口配 1484x773 的纹理）。
 * 格式也必须一致，D3D12 后端的 `copyTextureToTexture` 对格式不一致会直接报错。
 */
object SkyBlockCapture {
    /** 动态天空贴图在 `TextureManager` 里的 id，`RenderSetup.withTexture("Sampler0", ...)` 用的就是它。 */
    val TEXTURE_ID: Identifier = Identifier.fromNamespaceAndPath(MODID, "sky_block_capture")

    private var target: TextureTarget? = null
    private var installed = false

    /** 本帧有没有天空方块请求过拷贝。由 `submit` 置位，`captureIfRequested` 消费。 */
    private var requested = false

    /**
     * 当前是不是在界面（GUI/HUD）阶段。
     *
     * 采样坐标用 `gl_FragCoord × pixelScale`，分母必须是**当前绘制目标**的像素尺寸：
     * - 世界阶段：绘制目标是渲染缩放后的世界目标，也就是离屏天空自己的尺寸；
     * - 界面阶段：绘制目标是窗口尺寸（本项目开了渲染缩放时界面是单独一张全尺寸目标，
     *   见 `D3D12GUISplit` / `GuiRendererTargetMixin`），比世界目标大。
     * 两个尺寸不同，所以必须知道现在在哪个阶段。这个判断不碰任何图形后端，三个后端完全一样。
     */
    private var guiPhase = false

    /** 一帧的世界渲染开始（`RenderFrameEvent.Pre`）。 */
    fun beginWorldPhase() {
        guiPhase = false
    }

    /** 世界渲染结束、界面开始（`RenderLevelStageEvent.AfterLevel`）。 */
    fun beginGuiPhase() {
        guiPhase = true
    }

    /** 采样换算的水平分量：`gl_FragCoord.x` 乘上它就是 0..1 的 U。 */
    fun projectedScaleX(): Float = 1.0f / projectedSize(Minecraft.getInstance().window.width, captureWidth())

    /** 采样换算的垂直分量：`gl_FragCoord.y` 乘上它就是 0..1 的 V。 */
    fun projectedScaleY(): Float = 1.0f / projectedSize(Minecraft.getInstance().window.height, captureHeight())

    private fun projectedSize(windowSize: Int, captureSize: Int): Int {
        val size = if (guiPhase && windowSize > 0) windowSize else captureSize
        return size.coerceAtLeast(1)
    }

    /**
     * 帧外维护：创建或按主渲染目标的**纹理**尺寸重建离屏目标。由客户端 tick 调用。
     *
     * 只能放在帧外：`RenderTarget.resize` / `destroyBuffers` 会关掉旧的 GPU 纹理，如果在
     * `FramePass` 执行期间做这件事，正在进行的 pass 就会引用已经销毁的纹理。
     *
     * 深度附件是给云层用的：`RenderPipelines.CLOUDS` 带着 `DepthStencilState.DEFAULT`，
     * 没有深度附件的 pass 画不了云。
     */
    fun maintain() {
        val minecraft = Minecraft.getInstance()
        if (minecraft.level == null) {
            return
        }
        val source = minecraft.gameRenderer.mainRenderTarget().colorTexture ?: return
        val width = source.getWidth(0)
        val height = source.getHeight(0)
        if (width <= 0 || height <= 0) {
            return
        }
        val colorFormat = source.format
        val current = target
        if (current != null && current.width == width && current.height == height) {
            installTexture(minecraft)
            return
        }
        current?.destroyBuffers()
        val created = TextureTarget("dxr_mc sky block", width, height, colorFormat, GpuFormat.D32_FLOAT)
        target = created
        val colorTexture = created.colorTexture
        if (colorTexture != null) {
            // 首帧拷贝之前先清成不透明黑，避免采样到未初始化显存。
            RenderSystem.getDevice().createCommandEncoder()
                .clearColorTexture(colorTexture, Vector4f(0.0f, 0.0f, 0.0f, 1.0f))
        }
        installTexture(minecraft)
    }

    /** 离屏目标和动态贴图都就绪了吗。没就绪时渲染器整体跳过提交。 */
    fun isReady(): Boolean = target?.colorTextureView != null

    /** 离屏目标的宽度（采样坐标的换算基准）。没就绪时返回 0。 */
    fun captureWidth(): Int = target?.width ?: 0

    /** 离屏目标的高度（采样坐标的换算基准）。没就绪时返回 0。 */
    fun captureHeight(): Int = target?.height ?: 0

    /** 有天空方块要画时由渲染器调用。 */
    fun requestCapture() {
        requested = true
    }

    /**
     * 把主渲染目标里的天空拷到离屏目标，再把云层补画上去。由 `RenderLevelStageEvent.AfterSky` 调用。
     *
     * 这里刻意不用 `RenderTarget.copyColorFrom`：它按 `RenderTarget` 自己的 `width/height` 取整个
     * 矩形，而那个尺寸在开了渲染缩放时和纹理实际尺寸不一致（会触发 `IllegalArgumentException:
     * Source texture (...) is not large enough ...`）。直接用编码器按两张纹理的真实尺寸取交集拷贝。
     */
    fun captureIfRequested(levelRenderState: LevelRenderState) {
        if (!requested) {
            return
        }
        requested = false
        val created = target ?: return
        val source = Minecraft.getInstance().gameRenderer.mainRenderTarget().colorTexture ?: return
        val destination = created.colorTexture ?: return
        if (source.format != destination.format) {
            return
        }
        val width = minOf(source.getWidth(0), destination.getWidth(0))
        val height = minOf(source.getHeight(0), destination.getHeight(0))
        if (width <= 0 || height <= 0) {
            return
        }
        RenderSystem.getDevice().createCommandEncoder()
            .copyTextureToTexture(source, destination, 0, 0, 0, 0, 0, width, height)
        renderClouds(created, levelRenderState)
    }

    internal fun colorTexture(): GpuTexture? = target?.colorTexture

    internal fun colorTextureView(): GpuTextureView? = target?.colorTextureView

    /**
     * 把这一帧的云补画进离屏目标。
     *
     * 用原版那一个 `CloudRenderer`，所以云的形状、颜色、移动、`cloudStatus`（快速/高品质）都和主画面
     * 完全一致；只是深度缓冲里没有地形，云不会被山挡住 —— 这正是「天窗」想要的效果。
     * 关掉云（`CloudStatus.OFF`）、云的 alpha 为 0、或者别的模组接管了云渲染时不画，避免和它们打架。
     */
    private fun renderClouds(created: TextureTarget, levelRenderState: LevelRenderState) {
        val minecraft = Minecraft.getInstance()
        val cloudStatus = minecraft.options.cloudStatus
        if (cloudStatus == CloudStatus.OFF || ARGB.alpha(levelRenderState.cloudColor) <= 0) {
            return
        }
        if (levelRenderState.customCloudsRenderer != null) {
            return
        }
        val colorView = created.colorTextureView ?: return
        val depthTexture = created.depthTexture ?: return
        val depthView = created.depthTextureView ?: return
        val encoder = RenderSystem.getDevice().createCommandEncoder()
        // 深度每帧清回远端：云的深度写入不能跨帧累积，否则上一帧的云会挡住这一帧的云。
        encoder.clearDepthTexture(depthTexture, 0.0)
        // 颜色不清（Optional.empty()）：保留刚拷进来的天空，云只是叠上去。
        val pass = encoder.createRenderPass(
            { "dxr_mc sky block clouds" },
            colorView,
            Optional.empty(),
            depthView,
            OptionalDouble.empty(),
        )
        try {
            minecraft.levelRenderer.cloudRenderer().render(cloudStatus, pass)
        } finally {
            // RenderPass 是 UncheckedAutoCloseable，不是 AutoCloseable，Kotlin 的 use { } 用不了。
            pass.close()
        }
    }

    private fun installTexture(minecraft: Minecraft) {
        if (installed) {
            return
        }
        minecraft.textureManager.register(TEXTURE_ID, SkyBlockCaptureTexture())
        installed = true
    }
}

/**
 * 转发到 [SkyBlockCapture] 当前离屏目标的动态贴图。
 *
 * `AbstractTexture` 的 `getTexture()` / `getTextureView()` 不是 final，覆写它们就能让
 * `RenderSetup.prepareTextures`（每帧、每次 `RenderType.prepare()` 都会重新解析）永远拿到最新的
 * 纹理视图 —— 窗口改尺寸后离屏目标会被重建，但 TextureManager 里注册的对象始终是这一个。
 */
private class SkyBlockCaptureTexture : AbstractTexture() {
    /**
     * 边缘夹取，而且是线性过滤。
     *
     * 夹取：采样坐标会正好取到 0 和 1（方块面顶到屏幕边缘时），`AbstractTexture` 默认的 REPEAT
     * 会在边界绕回另一侧，出现一条接缝。
     * 线性：世界里是 1:1 采样（和邻点法等价），界面里是放大采样，线性过滤不会出块状马赛克。
     */
    private val clampSampler: GpuSampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR)

    override fun getTexture(): GpuTexture = SkyBlockCapture.colorTexture()
        ?: throw IllegalStateException("sky block capture texture is not initialised yet")

    override fun getTextureView(): GpuTextureView = SkyBlockCapture.colorTextureView()
        ?: throw IllegalStateException("sky block capture texture view is not initialised yet")

    override fun getSampler(): GpuSampler = clampSampler
}
