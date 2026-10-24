package dev.nts.expdxr.misc.block.client

import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.renderpearl.api.pipeline.ColorTargetState
import com.mojang.renderpearl.api.pipeline.DepthStencilState
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology
import com.mojang.renderpearl.api.pipeline.RenderPipeline
import dev.nts.expdxr.Illusion.Companion.MODID
import net.minecraft.client.renderer.BindGroupLayouts
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.rendertype.RenderSetup
import net.minecraft.client.renderer.rendertype.RenderType
import net.minecraft.resources.Identifier

/**
 * 天空方块专用的渲染管线与 [RenderType]。
 *
 * 布局刻意镜像原版 `RenderPipelines.CELESTIAL`（Globals / Projection / DynamicTransforms / Sampler0）：
 * 这四条是原版天空、星星、日月自己用的绑定组，既保证 `bindDefaultUniforms` 会填好投影矩阵，
 * 也让 D3D12 后端能走它已经验证过的同一条 `D3D12RenderPassBackend` 路径 —— 三个后端共用一套代码，
 * 没有任何后端判定。
 *
 * 顶点格式是 `POSITION_TEX`：顶点位置在 location 0，采样缩放（`(1/宽, 1/高)`）在 location 1。
 * 采样坐标不是由顶点给出的纹理坐标算出来的，而是片元着色器里用 `gl_FragCoord` 推出来的（见
 * `assets/dxr_mc/shaders/core/sky_block.fsh`），这是三个后端行序唯一自洽的做法。
 */
object SkyBlockPipeline {
    /** 顶点/片元着色器共用同一个 id：`assets/dxr_mc/shaders/core/sky_block.{vsh,fsh}`。 */
    val SHADER_ID: Identifier = Identifier.fromNamespaceAndPath(MODID, "core/sky_block")

    private val PIPELINE_ID: Identifier = Identifier.fromNamespaceAndPath(MODID, "pipeline/sky_block")

    val PIPELINE: RenderPipeline = RenderPipeline.builder(RenderPipelines.GLOBALS_SNIPPET)
        .withLocation(PIPELINE_ID)
        .withVertexShader(SHADER_ID)
        .withFragmentShader(SHADER_ID)
        .withBindGroupLayout(BindGroupLayouts.PROJECTION)
        .withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
        .withBindGroupLayout(BindGroupLayouts.SAMPLER0)
        .withVertexBinding(0, DefaultVertexFormat.POSITION_TEX)
        .withPrimitiveTopology(PrimitiveTopology.QUADS)
        .withColorTargetState(ColorTargetState.DEFAULT)
        .withDepthStencilState(DepthStencilState.DEFAULT)
        // 关掉背面剔除：贴图是不透明的，多画背面不会改变画面，却能让一个顶点绕序错误不至于
        // 变成「方块完全看不见」这种难以定位的问题（原版 end gateway 也是双面）。
        .withCull(false)
        .build()

    private var blockRenderType: RenderType? = null

    /**
     * 天空方块用的 RenderType。
     *
     * 每次 `prepare()` 都会重新从 `TextureManager` 解析 `Sampler0`，所以只要那个贴图对象是
     * [SkyBlockCapture] 的动态贴图，窗口改尺寸后新的离屏纹理视图会自动生效，不需要重建 RenderType。
     */
    fun renderType(): RenderType = blockRenderType ?: RenderType.create(
        "dxr_mc:sky_block",
        RenderSetup.builder(PIPELINE)
            .withTexture("Sampler0", SkyBlockCapture.TEXTURE_ID)
            .createRenderSetup()
    ).also { blockRenderType = it }
}
