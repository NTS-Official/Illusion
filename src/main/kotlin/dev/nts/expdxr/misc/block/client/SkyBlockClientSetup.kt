package dev.nts.expdxr.misc.block.client

import dev.nts.expdxr.Illusion.Companion.MODID
import dev.nts.expdxr.misc.block.SkyBlockEntity
import dev.nts.expdxr.misc.block.SkyBlocks
import net.minecraft.resources.Identifier
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.client.event.EntityRenderersEvent
import net.neoforged.neoforge.client.event.RegisterRenderPipelinesEvent
import net.neoforged.neoforge.client.event.RegisterSpecialModelRendererEvent
import net.neoforged.neoforge.client.event.RenderFrameEvent
import net.neoforged.neoforge.client.event.RenderLevelStageEvent
import net.neoforged.neoforge.common.NeoForge

/**
 * 天空方块在客户端的注册。
 *
 * 全部走原版/NeoForge 的公开扩展点（`RegisterRenderPipelinesEvent`、`EntityRenderersEvent.RegisterRenderers`、
 * `RegisterSpecialModelRendererEvent`、`RenderLevelStageEvent`），没有任何一处判断当前是哪个图形后端 ——
 * 同一份代码在 D3D12、OpenGL、Vulkan 三个后端上跑的是同一条提交与绘制路径。
 */
object SkyBlockClientSetup {
    /** 特殊物品模型的 id，对应 `items/sky_block.json` 里的 `"type": "dxr_mc:sky_block"`。 */
    private val SPECIAL_MODEL_ID: Identifier = Identifier.fromNamespaceAndPath(MODID, "sky_block")

    fun register(modEventBus: IEventBus) {
        modEventBus.addListener<RegisterRenderPipelinesEvent> { event ->
            // 可选注册：天空方块是装饰性内容，着色器/构建产物出问题时不该让整局崩掉。
            // 必需管线的失败会抛 `Failed to load required shader programs` 直接停掉渲染线程（黑屏）。
            event.registerOptionalPipeline(SkyBlockPipeline.PIPELINE)
        }
        modEventBus.addListener<EntityRenderersEvent.RegisterRenderers> { event ->
            event.registerBlockEntityRenderer<SkyBlockEntity, SkyBlockRenderState>(
                SkyBlocks.SKY_BLOCK_ENTITY.get()
            ) { SkyBlockEntityRenderer() }
        }
        // 物品外观（手持 / 物品栏 / 掉落物 / 展示框）走特殊物品模型，和方块实体共用同一个 RenderType。
        modEventBus.addListener<RegisterSpecialModelRendererEvent> { event ->
            event.register(SPECIAL_MODEL_ID, SkyBlockSpecialRenderer.Unbaked.MAP_CODEC)
        }
        NeoForge.EVENT_BUS.addListener<RenderLevelStageEvent.AfterSky> { event ->
            SkyBlockCapture.captureIfRequested(event.levelRenderState)
        }
        // 界面阶段的目标尺寸和世界阶段不同，采样倍率要跟着换（见 SkyBlockCapture.projectedScaleX）。
        NeoForge.EVENT_BUS.addListener<RenderFrameEvent.Pre> { SkyBlockCapture.beginWorldPhase() }
        NeoForge.EVENT_BUS.addListener<RenderLevelStageEvent.AfterLevel> { SkyBlockCapture.beginGuiPhase() }
        NeoForge.EVENT_BUS.addListener<ClientTickEvent.Post> { SkyBlockCapture.maintain() }
    }
}
