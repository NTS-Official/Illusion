package dev.nts.expdxr

import net.neoforged.neoforge.common.ModConfigSpec

/**
 * Illusion configuration.
 *
 * The port's original entry here was the anti-aliasing mode, carried over from
 * the Fabric build's hand-rolled `config/gl4dx12.properties` (`Dx12Config`, key
 * `aa_mode`). That setting is an ordinary video setting now: it is exposed on
 * vanilla's video-settings screen and persisted in `options.txt` (see
 * [dev.nts.expdxr.d3d12.render.backend.D3D12AntiAliasing]), so this spec
 * currently carries no entries. It stays registered so the mod keeps its own
 * config file, its config screen, and a home for future options.
 */
object IllusionConfig {
    private val BUILDER = ModConfigSpec.Builder()

    val SPEC: ModConfigSpec = BUILDER.build()
}
