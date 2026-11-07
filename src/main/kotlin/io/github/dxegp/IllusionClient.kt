package io.github.dxegp

import io.github.dxegp.d3d12.backend.D3D12PixCaptureController
import io.github.dxegp.d3d12.debug.D3D12ExitWatchdog
import io.github.dxegp.d3d12.render.advanced.mesh.D3D12SectionGeometry
import io.github.dxegp.experimental.framegen.FrameGenerationSelfTest
import io.github.dxegp.misc.IllusionLogger
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
@Mod(value = Illusion.MODID, dist = [Dist.CLIENT])
class IllusionClient(container: ModContainer) {
    init {
        // Apply log level in the init stage in order to correctly start the logger.
        IllusionLogger.applyDefaults()
        container.registerExtensionPoint(
            IConfigScreenFactory::class.java,
            IConfigScreenFactory { modContainer, parent -> ConfigurationScreen(modContainer, parent) }
        )

        Illusion.LOGGER.info("${Illusion.MOD_NAME} client ready; D3D12 backend registered")
        D3D12ExitWatchdog.start()
        // PIX timing capture on a key press: the binding has to be registered during mod loading,
        // and the handler only runs on the client bus (this class never loads on a server).
        // `eventBus` is null only for a mod that has no mod-bus events at all, which cannot happen
        // here (the binding is one), but there is nothing to gain from crashing if it is.
        container.eventBus?.let {
            D3D12PixCaptureController.register(it)
            D3D12SectionGeometry.registerModelReloads(it)
            // 仅当传了 -Pdxrmc.selfTest=… 才挂 tick 钩子（不设属性就什么都不做）。
            FrameGenerationSelfTest.install()
        }
    }
}
