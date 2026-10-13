package dev.nts.expdxr

import dev.nts.expdxr.client.debug.D3D12ExitWatchdog
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
        // Gives NeoForge's built-in ConfigurationScreen to this mod, replacing
        // the Fabric build's ModMenu entrypoint + hand-written settings screen.
        container.registerExtensionPoint(
            IConfigScreenFactory::class.java,
            IConfigScreenFactory { modContainer, parent -> ConfigurationScreen(modContainer, parent) }
        )

        Illusion.LOGGER.info(
            "Illusion client setup: DX12 backend is registered via PreferredGraphicsApiMixin; "
                + "creation is driven by the vanilla backend-selection loop"
        )
        D3D12ExitWatchdog.start()
    }
}
