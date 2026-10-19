package dev.nts.expdxr

import com.mojang.logging.LogUtils
import dev.nts.expdxr.Illusion.Companion.OPTIONS_KEY_PREFERRED_GRAPHICS
import dev.nts.expdxr.d3d12.D3D12Settings
import dev.nts.expdxr.d3d12.backend.D3D12GraphicsApi
import dev.nts.expdxr.d3d12.extern.MCDR_ABI
import dev.nts.expdxr.d3d12.render.adapter.D3D12AdapterInfo
import dev.nts.expdxr.utils.IllusionLogger
import net.neoforged.bus.api.IEventBus
import net.neoforged.fml.ModContainer
import net.neoforged.fml.common.Mod
import net.neoforged.fml.config.ModConfig
import net.neoforged.fml.event.config.ModConfigEvent
import net.neoforged.fml.loading.FMLPaths

@Mod(Illusion.MODID)
class Illusion(modEventBus: IEventBus, modContainer: ModContainer) {
    companion object {
        const val MODID = "dxr_mc"
        const val CLASSPATH = "dev.nts.expdxr"
        const val NATIVE_NAME = "MCDR"
        const val MOD_NAME = "Illusion"

        /** `options.txt` key under which vanilla `Options` persists the chosen graphics backend. */
        private const val OPTIONS_KEY_PREFERRED_GRAPHICS = "preferredGraphicsBackend"

        @JvmField
        val LOGGER = LogUtils.getLogger()
    }

    init {
        modContainer.registerConfig(ModConfig.Type.CLIENT, IllusionConfig.SPEC)
        // Construct log level before initialize the logger.
        IllusionLogger.applyDefaults()
        modEventBus.addListener<ModConfigEvent.Loading> {
            IllusionLogger.applyFromConfig()
            D3D12Settings.reload()
            D3D12Settings.pushToNative()
            applyGpuCaptureConfig()
        }
        modEventBus.addListener<ModConfigEvent.Reloading> {
            IllusionLogger.applyFromConfig()
            D3D12Settings.reload()
            D3D12Settings.pushToNative()
            applyGpuCaptureConfig()
        }
        LOGGER.info("[$MODID] $MOD_NAME is constructing...")
        // To probe exact backend setting before loading the mod, the only way to achieve this is to read it from file.
        val preferredGraphics = preferredGraphicsSettingFromOptionsFile()
        LOGGER.info(
            if (preferredGraphics == D3D12GraphicsApi.SERIALIZED_NAME) {
                "[$NATIVE_NAME] $MODID is currently using Direct3D Feature Level ${startupFeatureLevel()}."
            } else {
                "[$MODID] Preferred graphics setting is ${preferredGraphics ?: "default"}, so $NATIVE_NAME will not be initialized."
            }
        )
    }

    /**
     * Direct3D feature level the game is going to run on, as far as it can be known this early.
     *
     * The live device does not exist yet at mod construction — [D3D12AdapterInfo.current] is only
     * filled once [dev.nts.expdxr.d3d12.render.adapter.D3D12Device] created it — so this asks the
     * native side to enumerate the adapters instead (DXGI/DXCore walk plus a throw-away probe
     * device per adapter, no persistent device is created). Selection matches native P66: the
     * first adapter that can create a 12_2 device, otherwise the first D3D12-capable one.
     *
     * Returns `"unknown"` when the native library is unavailable or the query fails: this line is
     * diagnostics, it must never break mod construction.
     */
    private fun startupFeatureLevel(): String {
        D3D12AdapterInfo.current?.let { return it.featureLevel }
        if (!MCDR_ABI.isAvailable()) {
            return "unknown"
        }
        return runCatching {
            val adapters = D3D12AdapterInfo.parseList(MCDR_ABI.ID3D12EnumerateAdapters())
            (adapters.firstOrNull { it.featureLevel == "12_2" } ?: adapters.firstOrNull())?.featureLevel
        }.getOrNull() ?: "unknown"
    }

    /**
     * The graphics backend the player selected, as saved in `<game dir>/options.txt`.
     *
     * Mod construction runs before the client exists, so there is no `Options` object to ask —
     * `Minecraft.getInstance()` throws at this point (that is the same situation
     * `dev.nts.expdxr.d3d12.render.advanced.vrs.D3D12RenderScale` has to cope with when it asks the
     * client to rebuild the main render target) — and the
     * appended `PreferredGraphicsApi` Direct3D 12 constant is not installed yet either, because its
     * `<clinit>` only runs once `Options` touches the enum. Vanilla persists the choice under the
     * [OPTIONS_KEY_PREFERRED_GRAPHICS] key, written through the enum codec and therefore **quoted**
     * (`preferredGraphicsBackend:"d3d12"`), so the file is the only honest source this early.
     *
     * Returns `null` when the key is absent — a fresh install, where the vanilla default applies and
     * Direct3D 12 is by definition not selected.
     */
    private fun preferredGraphicsSettingFromOptionsFile(): String? {
        val file = FMLPaths.GAMEDIR.get().resolve("options.txt").toFile()
        if (!file.isFile) {
            return null
        }
        return try {
            file.useLines { lines ->
                lines.firstOrNull { it.startsWith("$OPTIONS_KEY_PREFERRED_GRAPHICS:") }
                    ?.substringAfter(':')
                    ?.trim()
                    ?.removeSurrounding("\"")
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
            }
        } catch (t: Throwable) {
            LOGGER.warn("[$MODID] could not read $file ($t); assuming the vanilla default graphics setting")
            null
        }
    }

    /**
     * Push `[debug] pixCapture` to the native side.
     *
     * This is the one setting that cannot be applied late: PIX's capture layer hooks Direct3D 12
     * from inside its own DLL, so it has to be loaded before the first device exists. The config
     * is read during mod construction (well before the renderer starts), and the options carries
     * `gameRestart()` so changing it asks for a restart instead of silently doing nothing.
     *
     * Loading the library also happens here on the JVM side (see [MCDR_ABI.D3D12SetPIXCapture]),
     * which is exactly the early moment that makes the capture layer effective.
     */
    private fun applyGpuCaptureConfig() {
        val enabled = IllusionConfig.PIX_CAPTURE.get()
        val status = MCDR_ABI.D3D12SetPIXCapture(enabled)
        when (status) {
            1 -> LOGGER.info("[$MODID] PIX GPU capture layer loaded (PIX must be running to save a capture).")

            0 if enabled -> LOGGER.info("[$MODID] PIX GPU capture requested; the layer loads before the first device.")
            0 -> LOGGER.info("[$MODID] PIX GPU capture disabled ([debug] pixCapture = false).")
            Int.MIN_VALUE -> LOGGER.info("[$MODID] PIX GPU capture unavailable: the loaded $NATIVE_NAME.dll has no mcdrSetPixCapture.")
            else -> LOGGER.warn("[$MODID] PIX GPU capture is on but WinPixGpuCapturer.dll could not be loaded; see the [$NATIVE_NAME] ERROR line for the paths that were searched, or set MCDR_PIX_CAPTURER.")
        }
    }
}
