package dev.nts.expdxr

import net.neoforged.neoforge.common.ModConfigSpec

/**
 * Illusion configuration.
 *
 * Ported from the Fabric build's hand-rolled `config/gl4dx12.properties`
 * (`Dx12Config`, key `aa_mode`, default 1) onto NeoForge's [ModConfigSpec], so
 * that FML owns the file and NeoForge's ConfigurationScreen can edit it.
 */
object DxrConfig {
    private val BUILDER = ModConfigSpec.Builder()

    /**
     * Anti-aliasing mode. Persisted only for now — the DX12 backend does not
     * implement AA yet; this is the setting the Fabric build already carried.
     */
    val AA_MODE: ModConfigSpec.IntValue = BUILDER
        .comment(
            "Anti-aliasing mode used by the DirectX 12 backend.",
            "0 = None, 1 = FXAA, 2 = SMAA, 3 = TAA"
        )
        .defineInRange("aaMode", 1, 0, 3)

    val SPEC: ModConfigSpec = BUILDER.build()
}
