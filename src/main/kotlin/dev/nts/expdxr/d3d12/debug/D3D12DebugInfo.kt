package dev.nts.expdxr.d3d12.debug

import dev.nts.expdxr.d3d12.render.adapter.D3D12AdapterInfo
import dev.nts.expdxr.d3d12.extern.MCDR_ABI
import net.minecraft.resources.Identifier

/**
 * Extra F3 system-specs lines contributed by DXRMC.
 *
 * The vanilla `DebugEntrySystemSpecs.display` already prints the four GPU/display
 * parameters (vendor, device name + type, backend + driver) - those are now real
 * because [dev.nts.expdxr.d3d12.adapter.D3D12Device] builds its [com.mojang.blaze3d.systems.DeviceInfo] from
 * [D3D12AdapterInfo]. This object supplies only what vanilla has nowhere to put: the
 * resolution/VRAM line and the mod's own backend line, which
 * [dev.nts.expdxr.mixin.render.DebugEntrySystemSpecsMixin] appends to the same group.
 */
object D3D12DebugInfo {
    /** The vanilla F3 group every GPU/display line belongs to. */
    @JvmField
    val SYSTEM_GROUP: Identifier = Identifier.withDefaultNamespace("system")

    /**
     * @return the lines to append directly below the vanilla GPU/display block, or an
     *         empty list when DXRMC is not the live render backend (i.e. the game is
     *         running on OpenGL or Vulkan).
     */
    @JvmStatic
    fun extraSystemLines(windowWidth: Int, windowHeight: Int): List<String> {
        val info = D3D12AdapterInfo.current ?: return emptyList<String>()
        val luid = if (info.luid.isEmpty()) "unknown" else info.luid
        val driver = if (info.driverVersion.isEmpty()) "unknown" else info.driverVersion
        val lines = ArrayList<String>(2)
        lines.add("Resolution: " + windowWidth + "x" + windowHeight +
            ", VRAM: " + info.vramMiB() + " MiB dedicated / " + info.sharedMiB() + " MiB shared")
        lines.add("Illusion: " + info.backendName() + " (MCDR ABI v" + MCDR_ABI.MCDR_ABI_VERSION +
            ") - LUID " + luid + " - feature level " + info.featureLevel +
            " - driver " + driver + " - " + info.vendorName())
        return lines
    }
}