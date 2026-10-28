package dev.nts.expdxr.misc

import dev.nts.expdxr.d3d12.render.adapter.D3D12AdapterInfo
import net.minecraft.client.Minecraft
import net.minecraft.client.Options
import net.minecraft.network.chat.Component
import org.slf4j.LoggerFactory

/**
 * P274：D3D12 后端下把原版「改进的透明（OIT）」锁成常开，并在视频设置里给出原因。
 *
 * 为什么锁：mesh 管线引入后我们只保留 OIT 半透明路径，IMR 回退路径已经移除，关掉这个选项之后没有任何
 * 能把半透明地形正确合成的路径 —— 对玩家来说它实际上就是强制开启的。与其让人关掉后看到错误画面，
 * 不如直接把开关锁住并说明原因。
 *
 * 判据：`D3D12AdapterInfo.current != null`，即 D3D12 设备真的建起来了（与原版视频界面替换用的是同一条判据）。
 * 选了 D3D12 却回退到原版后端的场合，这里不锁、也不改值。
 */
object ImprovedTransparencyLock {
    private val logger = LoggerFactory.getLogger("dxrmc/gui")

    /** D3D12 设备是否已经在线（= 当前后端是不是我们）。 */
    @JvmStatic
    fun isLocked(): Boolean = D3D12AdapterInfo.current != null

    /** 这一行被锁时显示的原因，同时充当该行的悬浮提示。 */
    @JvmStatic
    fun lockReason(): Component = Component.translatable("dxr_mc.video.lock.improved_transparency")

    /**
     * 把选项强制成开启；返回这次是否真的改了值。
     *
     * 调用点：视频设置界面打开时，以及每帧一次的 [ensure]。
     */
    @JvmStatic
    fun enforce(options: Options? = Minecraft.getInstance()?.options): Boolean {
        if (!isLocked()) {
            return false
        }
        val target = options ?: return false
        val option = target.improvedTransparency()
        if (option.get()) {
            return false
        }
        option.set(true)
        return true
    }

    /**
     * 每帧纠正（两个字段读取，开销可以忽略）：这样图形预设、外部写回等任何路径把它改回 false 都会在
     * 下一帧被拉回 true，而不是"只在首帧生效"留下一个可以被绕过的锁。
     */
    @JvmStatic
    fun ensure() {
        if (!isLocked()) {
            return
        }
        try {
            if (enforce()) {
                logger.info("improvedTransparency forced on (D3D12 has no IMR transparency fallback)")
            }
        } catch (t: Throwable) {
            // 首帧还没有 level，选项回调里的 allChanged 可能抛异常；下一帧会再试。
            logger.debug("improvedTransparency not forced yet: {}", t.toString())
        }
    }
}