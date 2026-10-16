package dev.nts.expdxr

import dev.nts.expdxr.Illusion.Companion.MODID
import dev.nts.expdxr.Illusion.Companion.MOD_NAME
import dev.nts.expdxr.d3d12.debug.D3D12ExitWatchdog
import dev.nts.expdxr.utils.IllusionLogger
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
    }
}
