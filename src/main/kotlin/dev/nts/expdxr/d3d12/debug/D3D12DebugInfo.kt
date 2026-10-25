package dev.nts.expdxr.d3d12.debug

import dev.nts.expdxr.d3d12.render.adapter.D3D12AdapterInfo
import dev.nts.expdxr.d3d12.render.advanced.vrs.D3D12RenderScale
import dev.nts.expdxr.d3d12.render.core.D3D12GpuTexture
import dev.nts.expdxr.d3d12.render.core.D3D12GpuTextureView
import dev.nts.expdxr.d3d12.extern.MCDR_ABI
import dev.nts.expdxr.misc.D3D12Settings
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
    @JvmField val SYSTEM_GROUP = Identifier.withDefaultNamespace("system")

    /**
     * @return the lines to append directly below the vanilla GPU/display block, or an
     *         empty list when DXRMC is not the live render backend (i.e. the game is
     *         running on OpenGL or Vulkan).
     */
    @JvmStatic
    fun extraSystemLines(windowWidth: Int, windowHeight: Int): List<String> {
        val info = D3D12AdapterInfo.current ?: return emptyList<String>()
        val luid = if (info.luid.isEmpty()) "unknown" else info.luid
        val lines = mutableListOf<String>()
        // rendered 是**实际着色**的像素数（不含超采样），output 是最终呈现的像素数（= 窗口）。
        // 两者相等有两种情况，处理方式不同：
        //  1. 主目标的附件确实比窗口小（renderScale / 超采样在跑）→ 附件尺寸就是真值；
        //  2. 附件仍是窗口尺寸，但缩放开关开着（GUI 拆分让主目标保持窗口尺寸，
        //     只有世界层的附件被缩小）→ 用缩放换算结果，否则这里会永远显示成窗口尺寸。
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
        // P237：VRS 那行已搬成独立 debug entry（[D3D12VrsDebugEntry]，F3+F6 里可单独开关），
        // 这里不再重复 —— system_specs 组只放"没有别处可放"的显卡/显存信息。
        return lines
    }

    /**
     * F3「Terrain Rendering:」行的后缀。
     *
     * <p>只有 DXRMC 的 D3D12 后端在跑、且 `[mesh] terrain` 生效时才追加 —— 否则那行
     * 描述的是别的后端/别的路径，加上 `mesh` 反而是假信息。
     */
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