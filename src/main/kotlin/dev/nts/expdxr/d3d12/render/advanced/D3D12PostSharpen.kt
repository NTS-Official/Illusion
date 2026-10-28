package dev.nts.expdxr.d3d12.render.advanced

import com.mojang.renderpearl.api.GpuFormat
import com.mojang.renderpearl.api.textures.GpuTexture
import org.slf4j.LoggerFactory
import dev.nts.expdxr.misc.D3D12Settings
import dev.nts.expdxr.d3d12.extern.MCDR_ABI
import dev.nts.expdxr.d3d12.render.core.D3D12GpuTexture

/**
 * P121：TAA / DLAA / DLSS 共用的后置锐化（5-tap unsharp，native 侧一条全屏 pass）。
 *
 * 为什么必须自己做：
 * - TAA 的 resolve 以 `lerp(history, current, 0.0625)` 结束（P152 起在 native 的 `kTaaPS` 里），本身就是低通，
 *   而 `dxrMcRenderScale = 100%` 时没有升采样帮忙补细节，画面偏软；
 * - DLSS/DLAA 不能用 SDK 的自带锐化：`MCDR\external\streamline\include\sl_dlss.h:26` 把
 *   `SR_DEPRECATED_SHARPENING` 定义成 "Sharpness is not supported"，NGX 侧我们也是
 *   `InSharpness = 0`。所以锐化只能是我们自己在升采样之后加一趟。
 *
 * 两条硬约束：
 * 1. 锐化只作用在「写进目标」的那一步，绝不写回 TAA 历史 —— 历史里存锐化后的颜色会让
 *    锐化随每帧的 lerp 递归叠加、产生振铃；
 * 2. native 拒绝 `src == dst`（`blitTextureToTexture` / `sharpenTextureToTexture` 都检查），
 *    所以就地锐化必须准备第二张同尺寸纹理。
 *
 * 强度：配置 `[renderer] sharpenAmount` / `[renderer] taaSharpenAmount`（0..1，0 = 完全关闭，调用方回退到 blit）。
 */
object D3D12PostSharpen {
    /** 独立的 logger：`Illusion.LOGGER` 的 INFO 在这套 log4j 配置下不进日志（P121 排查时踩过）。 */
    private val LOGGER = LoggerFactory.getLogger("dxrmc-sharpen")

    /** DLAA / DLSS 的默认强度（用户 m14905 实测 0.9 过头，给 0.67 正合适）。 */
    const val DEFAULT_AMOUNT: Float = 0.67f

    /**
     * TAA 的默认强度。TAA 的 resolve 本身是 `lerp(history, current, 1/16)`，画面比 DLAA 软，
     * 所以同一个 0.67 看起来不够：p108 实测 0.9 略过头，用户 m15310 定为 0.816。
     */
    const val TAA_AMOUNT: Float = 0.816f

    /** DLAA / DLSS 用的强度，见配置 `[renderer] sharpenAmount`（0 = 调用方回退到 blit）。 */
    @JvmField
    val amount: Float = D3D12Settings.sharpenAmount

    /** TAA 用的强度，见配置 `[renderer] taaSharpenAmount`。 */
    @JvmField
    val taaAmount: Float = D3D12Settings.taaSharpenAmount

    /** 中间纹理的用途：既能当渲染目标写，也能被采样（锐化的第二趟读它）。 */
    private val SCRATCH_USAGE =
        GpuTexture.USAGE_RENDER_ATTACHMENT or GpuTexture.USAGE_TEXTURE_BINDING or
            GpuTexture.USAGE_COPY_SRC

    private val SCRATCH_FORMAT = GpuFormat.RGBA8_UNORM

    private var scratch: D3D12GpuTexture? = null
    private var scratchWidth = 0
    private var scratchHeight = 0
    private var announced = false
    /** 是否已经查过 DLL 有没有这条导出（只查一次）。 */
    private var supportChecked = false
    private var supported = false
    /** 失败原因只报一次，避免每帧刷屏。 */
    private var failureLogged = false

    /** 用户是否开了锐化。 */
    fun isEnabled(): Boolean = this.amount > 0.0f

    /** 指定强度那一侧是否开启（TAA 与 DLAA/DLSS 强度不同，判定要按各自的来）。 */
    fun isEnabled(strength: Float): Boolean = strength > 0.0f

    /**
     * 把 [src] 锐化进 [dst]。返回 false = 这帧不锐化（关闭 / 旧 DLL / native 拒绝），
     * 调用方应回退到普通 blit。
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

    /**
     * 一次性确认 `MCDR.dll` 有没有 `mcdr_dx12SharpenTextureToTexture`。
     *
     * 这条导出是 `skipOptional` 绑定的（旧 DLL 没有它时整条链静默退化成直通 blit），
     * 所以「锐化好像没生效」必须能从这里看出原因，而不是靠猜。
     */
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

    /** 失败原因只报一次。 */
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
     * 2 趟锐化用的中间纹理（按需创建、尺寸变化即重建）。
     *
     * 只有不能一趟完成的场景需要它：DLAA/DLSS 的输出直接落在
     * `D3D12GUISplit.targetForFrame()` 里，而同资源不能就地锐化，于是
     * `target -> scratch`（锐化）再 `scratch -> target`（blit）两趟。
     *
     * 只在渲染线程调用。
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

    /** 放掉中间纹理（关闭 DLSS / 换世界 / 退出时调用；不碰 GPU 上还在用的纹理）。 */
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