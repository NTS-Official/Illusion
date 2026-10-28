package dev.nts.expdxr.d3d12.render.advanced

import com.mojang.renderpearl.api.GpuFormat
import com.mojang.renderpearl.api.textures.GpuTexture
import org.slf4j.LoggerFactory
import dev.nts.expdxr.misc.settings.D3D12Settings
import dev.nts.expdxr.d3d12.extern.MCDR_ABI
import dev.nts.expdxr.d3d12.render.core.D3D12GpuTexture

/**
 * TAA / DLAA / DLSS 共用的后置锐化：5-tap unsharp，native 一条全屏 pass。
 *
 * 绝不写回 TAA 历史（否则锐化逐帧递归叠加、振铃）；native 拒绝 `src == dst`，
 * 就地锐化必须走第二张同尺寸纹理。机制见 docs/antialiasing/README.md §7。
 */
object D3D12PostSharpen {
    /** 独立 logger：`Illusion.LOGGER` 的 INFO 在这套 log4j 配置下不进日志。 */
    private val LOGGER = LoggerFactory.getLogger("dxrmc-sharpen")

    /** DLAA / DLSS 的默认强度（0.9 过头）。 */
    const val DEFAULT_AMOUNT: Float = 0.67f

    /** TAA 默认强度：resolve 本身低通，比 DLAA 软，同一个 0.67 不够。 */
    const val TAA_AMOUNT: Float = 0.816f

    /** DLAA / DLSS 强度，来自配置 `[renderer] sharpenAmount`。 */
    @JvmField
    val amount: Float = D3D12Settings.sharpenAmount

    /** TAA 强度，来自配置 `[renderer] taaSharpenAmount`。 */
    @JvmField
    val taaAmount: Float = D3D12Settings.taaSharpenAmount

    /** 中间纹理既能当 RTV 写、也能被采样（第二趟读它）。 */
    private val SCRATCH_USAGE =
        GpuTexture.USAGE_RENDER_ATTACHMENT or GpuTexture.USAGE_TEXTURE_BINDING or
            GpuTexture.USAGE_COPY_SRC

    private val SCRATCH_FORMAT = GpuFormat.RGBA8_UNORM

    private var scratch: D3D12GpuTexture? = null
    private var scratchWidth = 0
    private var scratchHeight = 0
    private var announced = false
    private var supportChecked = false
    private var supported = false
    private var failureLogged = false

    fun isEnabled(): Boolean = this.amount > 0.0f

    fun isEnabled(strength: Float): Boolean = strength > 0.0f

    /**
     * 把 [src] 锐化进 [dst]；false = 这帧不锐化，调用方回退普通 blit。
     */
    @JvmStatic
    fun record(ctx: Long, src: Long, dst: Long, strength: Float = this.amount): Boolean {
        if (!isEnabled(strength) || src == 0L || dst == 0L || src == dst) {
            noteFailureOnce("guard: enabled=${isEnabled(strength)} src=$src dst=$dst")
            return false
        }
        if (!probeSupport()) {
            return false
        }
        val ok = try {
            MCDR_ABI.dx12SharpenTextureToTexture(ctx, src, dst, strength)
        } catch (t: Throwable) {
            noteFailureOnce("the call threw: $t")
            return false
        }
        if (!ok) {
            noteFailureOnce("native refused it: " + MCDR_ABI.D3D12LastError())
            return false
        }
        if (!announced) {
            announced = true
            LOGGER.info(
                "post-sharpen active (5-tap unsharp, amount={})",
                amount,
            )
        }
        return true
    }

    /** 一次性探测导出；`skipOptional` 绑定，旧 DLL 没有它时整链静默退化为直通 blit。 */
    private fun probeSupport(): Boolean {
        if (supportChecked) {
            return supported
        }
        supportChecked = true
        supported = MCDR_ABI.supportsSharpenTextureToTexture()
        if (supported) {
            LOGGER.info(
                "MCDR.dll exports the post-sharpen pass (amount={})",
                amount,
            )
        } else {
            LOGGER.warn(
                "this MCDR.dll has no mcdr_dx12SharpenTextureToTexture; " +
                    "sharpening stays off (falling back to the plain blit)",
            )
        }
        return supported
    }

    private fun noteFailureOnce(reason: String) {
        if (failureLogged) {
            return
        }
        failureLogged = true
        LOGGER.warn(
            "post-sharpen unavailable ({}); falling back to the plain blit",
            reason,
        )
    }

    /**
     * 2 趟锐化的中间纹理，按需创建、尺寸变化重建。只在渲染线程调用。
     *
     * 只有 DLAA / DLSS 用得到：输出直接落在目标上，而同资源不能就地锐化。
     */
    @JvmStatic
    fun scratchForFrame(width: Int, height: Int): D3D12GpuTexture? {
        if (width <= 0 || height <= 0) {
            return null
        }
        val existing = this.scratch
        if (existing != null && this.scratchWidth == width && this.scratchHeight == height &&
            !existing.isClosed()
        ) {
            return existing
        }
        release()
        return try {
            val created = D3D12GpuTexture(
                SCRATCH_USAGE, "DXRMC sharpen scratch", SCRATCH_FORMAT, width, height, 1, 1,
            )
            this.scratch = created
            this.scratchWidth = width
            this.scratchHeight = height
            created
        } catch (t: Throwable) {
            LOGGER.warn(
                "could not create the {}x{} sharpen scratch ({}) ; DLAA/DLSS " +
                    "will not be sharpened",
                width, height, t,
            )
            null
        }
    }

    /** 放掉中间纹理（不碰 GPU 上还在用的纹理）。 */
    @JvmStatic
    fun release() {
        val existing = this.scratch ?: return
        this.scratch = null
        this.scratchWidth = 0
        this.scratchHeight = 0
        if (!existing.isClosed()) {
            existing.close()
        }
    }
}