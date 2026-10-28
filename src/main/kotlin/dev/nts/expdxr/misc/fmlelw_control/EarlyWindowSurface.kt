package dev.nts.expdxr.misc.fmlelw_control

import org.lwjgl.sdl.SDLError
import org.lwjgl.sdl.SDLRender
import org.slf4j.LoggerFactory

/**
 * 退出时把加载屏画面涂黑。
 *
 * 必须挂在 `Minecraft.close()` 的 HEAD：`finally` 里才 `SDL_DestroyWindow`，这中间 SDL 会重铺最后一次
 * present 的那一帧（也就是加载屏）。句柄一次性，取出即清零。
 * 见 docs/misc/mixins-and-earlywindow.md §5.7
 */
object EarlyWindowSurface {
    private val logger = LoggerFactory.getLogger("dxrmc/earlywindow")

    /** 被接管的早期 SDL 窗口句柄，0 表示没有。 */
    private var window: Long = 0L

    /** 由 WindowAdoptCreatedMixin 在接管成功时调用；0 表示没走单窗口路径。 */
    @JvmStatic
    fun remember(windowHandle: Long) {
        window = windowHandle
    }

    /** 一次性：取出后立刻清零，重复调用什么都不做。 */
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