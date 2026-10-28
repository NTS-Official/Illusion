package dev.nts.expdxr.d3d12.render.advanced.smaa

import dev.nts.expdxr.Illusion
import dev.nts.expdxr.d3d12.render.adapter.D3D12Device
import dev.nts.expdxr.d3d12.render.core.D3D12GpuTexture
import dev.nts.expdxr.d3d12.render.core.D3D12GpuTextureView
import net.minecraft.client.Minecraft

/**
 * 世界层 SMAA 挂点：`GameRendererSmaaWorldMixin` 在「世界画完（含后处理）、GUI 未画」处调用。
 *
 * 主渲染目标就是最终 blit 采样那张纹理，在它上面做后处理等价于「只处理世界层」。
 * 本类**不检查**当前 AA 模式，判断在 native（不是 SMAA 就返回 0），挂点因此恒定。
 * 顺序与失败语义见 docs/antialiasing/README.md §1、§2.6。
 */
object D3D12SMAAWorld {

    /** 每会话只报一次「挂钩生效」，避免每帧刷屏。 */
    @Volatile
    private var loggedHook = false

    /** 每会话只报一次「native 确认真的跑了」：结论性证据，实机复验只看 latest.log。 */
    @Volatile
    private var loggedApplied = false

    /** 每帧在世界画完、GUI 之前调用一次；不满足条件直接返回（静默，等同关掉 AA）。 */
    @JvmStatic
    fun applyIfEnabled() {
        val minecraft = Minecraft.getInstance()
        // 标题 / 加载界面没有世界层，这时做「世界层抗锯齿」只会柔化界面本身。
        if (minecraft.level == null) {
            return
        }
        val view = minecraft.gameRenderer.mainRenderTarget().colorTextureView
        val texture = (view as? D3D12GpuTextureView)?.texture() as? D3D12GpuTexture ?: return
        // GpuDevice 不暴露 backend，只能取 D3D12Device 登记的共享单例。
        val encoder = D3D12Device.sharedCommandEncoder() ?: return
        if (!loggedHook) {
            loggedHook = true
            Illusion.LOGGER.info(
                "world-layer SMAA hook is live (world {}x{}); the GUI draws after it and stays unaffected",
                texture.getWidth(0), texture.getHeight(0))
        }
        if (encoder.recordSmaaWorldPass(texture) && !loggedApplied) {
            loggedApplied = true
            Illusion.LOGGER.info(
                "native confirms the world-layer SMAA pass ran (world {}x{})",
                texture.getWidth(0), texture.getHeight(0))
        }
    }
}