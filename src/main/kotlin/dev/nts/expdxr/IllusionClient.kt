package dev.nts.expdxr

import dev.nts.expdxr.Illusion.Companion.MODID
import dev.nts.expdxr.Illusion.Companion.MOD_NAME
import dev.nts.expdxr.d3d12.backend.D3D12PixCaptureController
import dev.nts.expdxr.d3d12.debug.D3D12ExitWatchdog
import dev.nts.expdxr.misc.IllusionLogger
import dev.nts.expdxr.misc.block.client.SkyBlockClientSetup
import dev.nts.expdxr.dxrmc.framegen.FrameGenerationSelfTest
import net.neoforged.api.distmarker.Dist
import net.neoforged.fml.ModContainer
import net.neoforged.fml.common.Mod
import net.neoforged.neoforge.client.gui.ConfigurationScreen
import net.neoforged.neoforge.client.gui.IConfigScreenFactory

/**
 * Client-only half of the mod.
 *
 * Loading this class on a dedicated server is impossible: `@Mod(dist = CLIENT)`
 * makes FML skip it entirely, so the client-only classes it references are
 * never touched server-side.
 */
@Mod(value = MODID, dist = [Dist.CLIENT])
class IllusionClient(container: ModContainer) {
    init {
        // Apply log level in the init stage in order to correctly start the logger.
        IllusionLogger.applyDefaults()
        container.registerExtensionPoint(
            IConfigScreenFactory::class.java,
            IConfigScreenFactory { modContainer, parent -> ConfigurationScreen(modContainer, parent) }
        )

        Illusion.LOGGER.info("[$MODID] $MOD_NAME Client setup: D3D12 backend is registered via PreferredGraphicsApiMixin; creation is driven by the vanilla backend-selection loop")
        D3D12ExitWatchdog.start()
        // PIX timing capture on a key press: the binding has to be registered during mod loading,
        // and the handler only runs on the client bus (this class never loads on a server).
        // `eventBus` is null only for a mod that has no mod-bus events at all, which cannot happen
        // here (the binding is one), but there is nothing to gain from crashing if it is.
        container.eventBus?.let {
            D3D12PixCaptureController.register(it)
            // 天空方块的渲染器、渲染管线与离屏天空捕获（纯客户端，不进服务端）。
            SkyBlockClientSetup.register(it)
            // P300 验收：-Pdxrmc.selfTest=… 时才挂 tick 钩子（不设属性就什么都不做）。
            FrameGenerationSelfTest.install()
        }
    }
}
