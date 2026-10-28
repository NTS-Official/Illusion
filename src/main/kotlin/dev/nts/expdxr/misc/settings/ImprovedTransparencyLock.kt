package dev.nts.expdxr.misc.settings

import dev.nts.expdxr.d3d12.render.adapter.D3D12AdapterInfo
import net.minecraft.client.Minecraft
import net.minecraft.client.Options
import net.minecraft.network.chat.Component
import org.slf4j.LoggerFactory

/**
 * D3D12 后端下把原版「改进的透明（OIT）」强制常开：mesh 管线只保留了 OIT 半透明路径，关掉之后
 * 没有任何能把半透明地形正确合成的路径（见 docs/misc/settingscreen/README.md §8.8）。
 *
 * 判据是 `D3D12AdapterInfo.current != null`（设备真的建起来了）；回退到原版后端时不锁、不改值。
 */
object ImprovedTransparencyLock {
    private val logger = LoggerFactory.getLogger("dxrmc/gui")

    @JvmStatic
    fun isLocked(): Boolean = D3D12AdapterInfo.current != null

    @JvmStatic
    fun lockReason(): Component = Component.translatable("dxr_mc.video.lock.improved_transparency")

    /** 强制开启；返回这次是否真的改了值。 */
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

    /** 必须每帧纠正：只在首帧设一次，会留下一个被图形预设、外部写回等路径绕过的锁。 */
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
            // 首帧还没有 level，选项回调里的 allChanged 可能抛异常；降级成 debug，下一帧再试。
            logger.debug("improvedTransparency not forced yet: {}", t.toString())
        }
    }
}