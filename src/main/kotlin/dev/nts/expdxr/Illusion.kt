package dev.nts.expdxr

import com.mojang.logging.LogUtils
import net.neoforged.bus.api.IEventBus
import net.neoforged.fml.ModContainer
import net.neoforged.fml.common.Mod
import net.neoforged.fml.config.ModConfig
import org.slf4j.Logger

/**
 * Illusion — a DirectX 12 Ultimate rendering backend for Minecraft, embedded
 * into the vanilla GPU-backend architecture (modelled on the official Vulkan
 * backend).
 *
 * The backend is installed by [dev.nts.expdxr.mixin.options.PreferredGraphicsApiMixin],
 * which rewrites `PreferredGraphicsApi.getBackendsToTry()` so that Minecraft's
 * own backend-selection loop constructs [dev.nts.expdxr.d3d12.backend.D3D12Backend]. All
 * renderer work therefore happens inside the vanilla backend lifecycle, and
 * this class only has to register the mod's configuration.
 *
 * FML requires the mod class to expose **exactly one public constructor**; the
 * parameter types below are resolved by exact type lookup (IEventBus,
 * ModContainer), so do not add default values or @JvmOverloads here — both
 * synthesize extra public constructors and FML will reject the mod.
 */
@Mod(Illusion.MODID)
class Illusion(modEventBus: IEventBus, modContainer: ModContainer) {
    companion object {
        const val MODID: String = "dxr_mc"

        @JvmField
        val LOGGER: Logger = LogUtils.getLogger()
    }

    init {
        // The whole backend is client-only, so all of our configuration is
        // client-scoped (config/dxr_mc-client.toml).
        modContainer.registerConfig(
            ModConfig.Type.CLIENT,
            IllusionConfig.SPEC)
        LOGGER.info("Illusion constructing (DirectX 12 Ultimate backend)")
    }
}
