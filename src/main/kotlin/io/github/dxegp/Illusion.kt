package io.github.dxegp

import com.mojang.logging.LogUtils
import io.github.dxegp.Illusion.Companion.OPTIONS_KEY_PREFERRED_GRAPHICS
import io.github.dxegp.d3d12.backend.D3D12GraphicsApi
import io.github.dxegp.d3d12.debug.D3D12VrsDebugEntry
import io.github.dxegp.d3d12.extern.MCDR_ABI
import io.github.dxegp.d3d12.render.adapter.D3D12AdapterInfo
import io.github.dxegp.d3d12.render.advanced.vrs.D3D12VariableShadingRate
import io.github.dxegp.experimental.raytracing.D3D12RtConfigMigration
import io.github.dxegp.experimental.raytracing.D3D12RtMaterialTable
import io.github.dxegp.misc.IllusionLogger
import io.github.dxegp.misc.fmlelw_control.EarlyWindowJarInstaller
import io.github.dxegp.misc.settings.D3D12Settings
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
        const val CLASSPATH = "io.github.dxegp"
        const val NATIVE_NAME = "MCDR"
        const val MOD_NAME = "Illusion"

        /** `options.txt` key under which vanilla `Options` persists the chosen graphics backend. */
        private const val OPTIONS_KEY_PREFERRED_GRAPHICS = "preferredGraphicsBackend"

        @JvmField
        val LOGGER = LogUtils.getLogger()
    }

    init {
        D3D12RtConfigMigration.migrate(FMLPaths.CONFIGDIR.get().resolve("dxr_mc-client.toml"))
        modContainer.registerConfig(ModConfig.Type.CLIENT, IllusionConfig.SPEC)
        // F3 里的 VRS 行是独立的 debug entry，由 F3+F6 的 DebugOptionsScreen 开关。
        D3D12VrsDebugEntry.register(modEventBus)
        // 退出存档时把 VRS 诊断计数清零（属于一局的统计，不跨存档累加）。
        D3D12VariableShadingRate.register()
        // 材质表在第一个客户端 tick 惰性烘一次；`[rt] backend = AUTO` 时每个 tick 直接返回。
        D3D12RtMaterialTable.register()
        // Construct log level before initialize the logger.
        IllusionLogger.applyDefaults()
        // early-window provider 以 jar-in-jar 形式随模组发布，启动时解到 mods/ 并改好 fml.toml。
        EarlyWindowJarInstaller.ensureInstalled()
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
        LOGGER.info("$MOD_NAME constructing")
        // 一行构建标记：任何一份日志都能自己回答"跑的是不是刚编出来的代码"。
        // To probe exact backend setting before loading the mod, the only way to achieve this is to read it from file.
        val preferredGraphics = preferredGraphicsSettingFromOptionsFile()
        LOGGER.info(
            if (preferredGraphics == D3D12GraphicsApi.SERIALIZED_NAME) {
                "$MODID is currently using Direct3D Feature Level ${startupFeatureLevel()}."
            } else {
                "Preferred graphics setting is ${preferredGraphics ?: "default"}, so $NATIVE_NAME will not be initialized."
            }
        )
    }

    /**
     * Direct3D feature level the game is going to run on, as far as it can be known this early.
     *
     * The live device does not exist yet at mod construction — [D3D12AdapterInfo.current] is only
     * filled once [io.github.dxegp.d3d12.render.adapter.D3D12Device] created it — so this asks the
     * native side to enumerate the adapters instead (DXGI/DXCore walk plus a throw-away probe
     * device per adapter, no persistent device is created). Selection matches the native choice: the
     * first adapter that can create a 12_2 device, otherwise the first D3D12-capable one.
     *
     * Returns `"unknown"` when the native library is unavailable or the query fails: this line is
     * diagnostics, it must never break mod construction.
     */
    private fun startupFeatureLevel(): String {
        D3D12AdapterInfo.current?.let { return@let it.featureLevel }
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
     * `io.github.dxegp.d3d12.render.advanced.vrs.D3D12RenderScale` has to cope with when it asks the
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
            LOGGER.warn("could not read $file ($t); using vanilla default")
            null
        }
    }

    /**
     * Push `[debug] pixCapture` / `[debug] pixTimingCapture` to the native side.
     *
     * This is the one setting that cannot be applied late: PIX's capture layer hooks Direct3D 12
     * from inside its own DLL, so it has to be loaded before the first device exists. The config
     * is read during mod construction (well before the renderer starts), and the options carries
     * `gameRestart()` so changing it asks for a restart instead of silently doing nothing.
     *
     * A self-driven timing capture needs the same layer (it is the ETW half of PIX), so either
     * switch loads it; telling the native side `true` whenever one of them is on keeps the two
     * from having to be enabled together by hand.
     */
    private fun applyGpuCaptureConfig() {
        val enabled = IllusionConfig.PIX_CAPTURE.get() || IllusionConfig.PIX_TIMING_CAPTURE.get()
        val status = MCDR_ABI.D3D12SetPIXCapture(enabled)
        when (status) {
            1 -> LOGGER.info("PIX capture layer loaded")

            0 if enabled -> LOGGER.info("PIX capture requested; layer loads before first device")
            0 -> LOGGER.info("PIX capture off ([debug] pixCapture = false)")
            Int.MIN_VALUE -> LOGGER.info("PIX capture unavailable: no mcdrSetPixCapture export")
            else -> LOGGER.warn("WinPixGpuCapturer.dll not found; set MCDR_PIX_CAPTURER")
        }
    }
}
