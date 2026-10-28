package dev.nts.expdxr.d3d12.debug

import dev.nts.expdxr.d3d12.render.adapter.D3D12AdapterInfo
import dev.nts.expdxr.d3d12.render.advanced.vrs.D3D12RenderScale
import dev.nts.expdxr.d3d12.render.core.D3D12GpuTexture
import dev.nts.expdxr.d3d12.render.core.D3D12GpuTextureView
import dev.nts.expdxr.d3d12.extern.MCDR_ABI
import dev.nts.expdxr.misc.settings.D3D12Settings
import net.minecraft.client.Minecraft
import net.minecraft.resources.Identifier

/**
 * vanilla `system_specs` 组里只补 DXRMC 独有的行（分辨率/显存/后端），由
 * `DebugEntrySystemSpecsMixin` 追加到同一组。VRS 行不在这里（见 [D3D12VrsDebugEntry]）。
 * 行的含义见 docs/misc/debug-and-crash.md §6。
 */
object D3D12DebugInfo {
    @JvmField val SYSTEM_GROUP = Identifier.withDefaultNamespace("system")

    /** @return 追加到 vanilla GPU 块之后的行；DXRMC 不是当前后端（GL/Vulkan）时返回空表。 */
    @JvmStatic
    fun extraSystemLines(windowWidth: Int, windowHeight: Int): List<String> {
        val info = D3D12AdapterInfo.current ?: return emptyList<String>()
        val luid = if (info.luid.isEmpty()) "unknown" else info.luid
        val lines = mutableListOf<String>()
        // rendered = 实际着色的像素数（不含超采样），output = 最终呈现的像素数。
        // 附件确实比窗口小 → 附件尺寸是真值；否则用缩放换算（GUI 拆分时主目标仍是窗口尺寸）。
        val attachment = mainTargetSize()
        val scaledWidth = D3D12RenderScale.scaled(windowWidth)
        val scaledHeight = D3D12RenderScale.scaled(windowHeight)
        val attachmentsAreScaled = attachment != null &&
            (attachment.first != windowWidth || attachment.second != windowHeight)
        val renderedWidth = if (attachmentsAreScaled) attachment.first else scaledWidth
        val renderedHeight = if (attachmentsAreScaled) attachment.second else scaledHeight
        lines.add("Resolution: output ${windowWidth}x${windowHeight}, rendered ${renderedWidth}x${renderedHeight}")
        lines.add("VRAM: ${info.vramMiB()} MiB dedicated / ${info.sharedMiB()} MiB shared")
        lines.add("Illusion: ${info.backendName()} (MCDR ABI v${MCDR_ABI.MCDR_ABI_VERSION}) - LUID: $luid")
        // VRS 行已搬到 [D3D12VrsDebugEntry]，别再加回这里。
        return lines
    }

    /** `Terrain Rendering:` 的后缀；只有 D3D12 后端在跑且 `[mesh] terrain` 生效时才追加，否则是假信息。 */
    @JvmStatic
    fun terrainLine(line: String): String {
        if (!line.startsWith("Terrain Rendering: ")) return line
        if (D3D12AdapterInfo.current == null || !D3D12Settings.meshTerrain) return line
        return "$line with mesh (Powered by Illusion)"
    }

    /** 主渲染目标附件的真实像素尺寸；F3 在渲染线程上取，读不到就返回 null。 */
    private fun mainTargetSize(): Pair<Int, Int>? = runCatching {
        val main = Minecraft.getInstance().gameRenderer.mainRenderTarget()
        val texture = (main.colorTextureView as? D3D12GpuTextureView)?.texture() as? D3D12GpuTexture
            ?: return@runCatching null
        texture.getWidth(0) to texture.getHeight(0)
    }.getOrNull()
}