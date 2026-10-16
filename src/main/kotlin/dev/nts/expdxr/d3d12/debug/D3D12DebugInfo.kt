package dev.nts.expdxr.d3d12.debug

import dev.nts.expdxr.d3d12.render.adapter.D3D12AdapterInfo
import dev.nts.expdxr.d3d12.render.core.D3D12GpuTexture
import dev.nts.expdxr.d3d12.render.core.D3D12GpuTextureView
import dev.nts.expdxr.d3d12.extern.MCDR_ABI
import net.minecraft.client.Minecraft
import net.minecraft.resources.Identifier

/**
 * Extra F3 system-specs lines contributed by DXRMC.
 *
 * The vanilla `DebugEntrySystemSpecs.display` already prints the four GPU/display
 * parameters (vendor, device name + type, backend + driver) - those are now real
 * because [dev.nts.expdxr.d3d12.render.adapter.D3D12Device] builds its [com.mojang.blaze3d.systems.DeviceInfo] from
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
        val lines = mutableListOf<String>()
        // 呈现分辨率（actual）与渲染分辨率（rendered）分开报：renderScale 非 100% 或 SMAA/超采样
        // 介入时两者不再相等，只报一个数会让人以为缩放没生效。rendered 直接读主渲染目标纹理的
        // 真实尺寸（与 D3D12GUISplit 用的同一个来源），拿不到就退回窗口尺寸。
        val rendered = mainTargetSize() ?: (windowWidth to windowHeight)
        lines.add("Resolution: actual ${windowWidth}x${windowHeight}" +
            ", rendered ${rendered.first}x${rendered.second}")
        lines.add("VRAM: ${info.vramMiB()} MiB dedicated / ${info.sharedMiB()} MiB shared")
        lines.add("Illusion: ${info.backendName()} (MCDR ABI v${MCDR_ABI.MCDR_ABI_VERSION}" +
            ") - LUID ${luid} - feature level ${info.featureLevel}" +
            " - driver ${driver} - ${info.vendorName()}")
        return lines
    }

    /** 主渲染目标附件的真实像素尺寸；F3 在渲染线程上取，读不到就返回 null。 */
    private fun mainTargetSize(): Pair<Int, Int>? = runCatching {
        val main = Minecraft.getInstance().gameRenderer.mainRenderTarget()
        val texture = (main.colorTextureView as? D3D12GpuTextureView)?.texture() as? D3D12GpuTexture
            ?: return@runCatching null
        texture.getWidth(0) to texture.getHeight(0)
    }.getOrNull()
}