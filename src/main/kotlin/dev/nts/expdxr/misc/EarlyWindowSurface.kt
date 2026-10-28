package dev.nts.expdxr.misc

import org.lwjgl.sdl.SDLError
import org.lwjgl.sdl.SDLRender
import org.slf4j.LoggerFactory

/**
 * P273：退出游戏时不要让早前那张加载屏画面再冒出来。
 *
 * `Minecraft.close()` 的顺序是：`windowSurface.close()`（销毁我们的 D3D12 交换链）→ ... →
 * finally 里才 `window.close()`（`SDL_DestroyWindow`）。在交换链已经停止呈现、SDL 窗口还没销毁的这一小段里，
 * SDL 会把「最后一次 present 的那一帧」重新铺到窗口客户区，也就是早期加载屏（logo + 紫条 + 说明文字）。
 *
 * 所以在 `Minecraft.close()` 一开始就把这个 SDL 窗口刷成黑色：SDL 手里最后一帧变成黑色之后，
 * 销毁过程中的任何重铺都是黑的，而不是加载屏。
 *
 * 窗口句柄由 WindowAdoptCreatedMixin 在接管早期窗口时登记；没走单窗口路径（非 D3D12 后端）时这里什么都不做。
 */
object EarlyWindowSurface {
    private val logger = LoggerFactory.getLogger("dxrmc/earlywindow")

    /** 被接管的早期 SDL 窗口句柄，0 表示没有。 */
    private var window: Long = 0L

    /** 由 WindowAdoptCreatedMixin 在 Window.createWindow 接管成功时调用。 */
    @JvmStatic
    fun remember(windowHandle: Long) {
        window = windowHandle
    }

    /**
     * 把被接管的 SDL 窗口刷黑（一次性）。在 Minecraft.close() 的 HEAD 调用：此时交换链还活着、
     * 窗口也还没销毁，SDL 的 software renderer 仍然可用。
     */
    @JvmStatic
    fun paintBlack() {
        val handle = window
        window = 0L
        if (handle == 0L) {
            return
        }
        val renderer = SDLRender.SDL_CreateRenderer(handle, "software")
        if (renderer == 0L) {
            logger.warn("cannot clear the adopted window before shutdown: {}", SDLError.SDL_GetError())
            return
        }
        try {
            SDLRender.SDL_SetRenderDrawColor(renderer, 0.toByte(), 0.toByte(), 0.toByte(), 255.toByte())
            SDLRender.SDL_RenderClear(renderer)
            SDLRender.SDL_RenderPresent(renderer)
        } finally {
            SDLRender.SDL_DestroyRenderer(renderer)
        }
    }
}