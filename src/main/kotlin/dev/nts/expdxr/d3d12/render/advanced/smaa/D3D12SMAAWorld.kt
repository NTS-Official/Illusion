package dev.nts.expdxr.d3d12.render.advanced.smaa

import dev.nts.expdxr.Illusion
import dev.nts.expdxr.d3d12.render.adapter.D3D12Device
import dev.nts.expdxr.d3d12.render.core.D3D12GpuTexture
import dev.nts.expdxr.d3d12.render.core.D3D12GpuTextureView
import net.minecraft.client.Minecraft

/**
 * P104b：把 SMAA 从「最终 blit」搬到「世界层」的落点（Java 侧由
 * `mixin.render.GameRendererSmaaWorldMixin` 调用）。
 *
 * 背景：P104 第一版把 SMAA 的三阶段插在 `D3D12GpuSurface.blitFromTexture` 的最终 blit
 * 之前 —— 那一刻整帧（世界 + GUI）已经合成完毕，于是界面文字与面板一起被柔化
 * （用户报告的「GUI 发虚」）。这里改在 `GameRenderer.render()` 里「世界画完（含后处理）、
 * GUI 还没画」的那一瞬间，对主渲染目标就地做同样三阶段，因此只有世界层被抗锯齿；
 * 随后 GUI 画在同一条纹理上，保持锐利。
 *
 * 三件事决定了这个位置可行且安全：
 * 1. 主渲染目标就是最终 blit 采样的那张纹理（`D3D12GpuSurface.blitFromTexture` 用的就是它），
 *    所以在它上面做后处理等价于「世界层后处理」。
 * 2. D3D12 后端整帧共用**一个** CommandEncoder（`D3D12Device.createCommandEncoder`），
 *    所以这里录的命令与世界、GUI 的命令在同一条列表上，顺序天然正确。
 * 3. native 侧在同一资源被采样时不能同时把它当 RTV，于是第三趟写进一张同尺寸同格式的
 *    scratch 再 `CopyResource` 拷回，并把主目标留在 RENDER_TARGET 供 GUI 的 render pass 使用。
 *
 * 失败/不适用（没有世界、纹理不是 D3D12 纹理、AA 不是 SMAA、native 报错）都只是静默跳过：
 * 与关掉 AA 的表现完全一致，不会让这一帧渲染不出来。
 */
object D3D12SMAAWorld {

    /** 每个会话只报一次「挂钩生效」（挂点真的被调到），避免每帧刷屏。 */
    @Volatile
    private var loggedHook = false

    /**
     * 每个会话只报一次「native 确认真的做了抗锯齿」——这是**结论性**证据：native 的 P104 行只写
     * stderr / IDE 控制台，这里把它带回 JVM 日志，所以实机复验只看 latest.log 就够。
     */
    @Volatile
    private var loggedApplied = false

    /**
     * 每帧在世界画完、GUI 之前调用一次。任何一步不满足就返回（见类注释）。
     */
    @JvmStatic
    fun applyIfEnabled() {
        val minecraft = Minecraft.getInstance()
        // 标题界面 / 加载界面：没有世界层，这时候做「世界层抗锯齿」只会柔化界面本身。
        if (minecraft.level == null) {
            return
        }
        val view = minecraft.gameRenderer.mainRenderTarget().colorTextureView
        val texture = (view as? D3D12GpuTextureView)?.texture() as? D3D12GpuTexture ?: return
        // 官方 GpuDevice 不暴露 backend，所以取 D3D12Device 登记的共享单例（见那里的注释）。
        val encoder = D3D12Device.sharedCommandEncoder() ?: return
        if (!loggedHook) {
            loggedHook = true
            Illusion.LOGGER.info(
                "world-layer SMAA hook is live (world {}x{}); the GUI draws after it and stays unaffected",
                texture.getWidth(0), texture.getHeight(0))
        }
        // native 的返回值是这一帧到底有没有真的跑那三趟 —— 之前只能靠 stderr 里的 P104 行，
        // 现在它进了 JVM 日志，SMAA「有没有生效」不再需要用户肉眼判断。
        if (encoder.recordSmaaWorldPass(texture) && !loggedApplied) {
            loggedApplied = true
            Illusion.LOGGER.info(
                "native confirms the world-layer SMAA pass ran (world {}x{})",
                texture.getWidth(0), texture.getHeight(0))
        }
    }
}