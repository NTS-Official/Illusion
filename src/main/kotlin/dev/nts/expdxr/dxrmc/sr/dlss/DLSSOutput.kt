package dev.nts.expdxr.dxrmc.sr.dlss

import com.mojang.renderpearl.api.GpuFormat
import com.mojang.renderpearl.api.textures.GpuTexture
import dev.nts.expdxr.Illusion
import dev.nts.expdxr.d3d12.extern.MCDR_ABI
import dev.nts.expdxr.d3d12.render.core.D3D12GpuTexture

/**
 * 交给 NGX 写的输出纹理**必须由 JVM 建**：native 自建的 `CreateCommittedResource` 纹理存不住写入，
 * 只有模块 `createTexture` 建的能存住。这里建一张带 [D3D12GpuTexture.USAGE_STORAGE] 的纹理，经
 * `mcdr_dx12SetDlssOutput` 交过去，native 会把它直接当 `pInOutput`。
 * 缺 `USAGE_STORAGE`（位 32）时 native 不加 ALLOW_UNORDERED_ACCESS，NGX 就写不进去。见 docs/superresolution/dlss.md §8。
 */
object DLSSOutput {


    /** 必须与 `pInOutput` 同格式（RGBA8_UNORM = DXGI_FORMAT 序数 28），否则 NGX 输入输出格式不匹配。 */
    private val FORMAT: GpuFormat = GpuFormat.RGBA8_UNORM

    /** 当前交给 native 的那张纹理；null = 还没建 / 已释放。 */
    private var texture: D3D12GpuTexture? = null

    private var width: Int = 0
    private var height: Int = 0

    /** 已经交接给 native 的句柄（避免每帧重复下探同一次调用）。 */
    private var handedOverHandle: Long = 0L

    /** 上一次报告的结论；只在变化时打一行，避免每帧刷屏。 */
    private var lastNote: String? = null

    /** 本帧该用的输出纹理（没有就按需建）。**只在渲染线程调用**；`targetWidth/Height` <= 0 返回 null。 */
    @JvmStatic
    fun textureForFrame(targetWidth: Int, targetHeight: Int): D3D12GpuTexture? {
        if (targetWidth <= 0 || targetHeight <= 0) {
            return null
        }
        val existing = this.texture
        if (existing != null && this.width == targetWidth && this.height == targetHeight &&
            !existing.isClosed()
        ) {
            return existing
        }
        // 尺寸变了 / 还没建：彻底放掉旧的那张再建新的。
        release()
        return try {
            val created = D3D12GpuTexture(
                D3D12GpuTexture.USAGE_STORAGE or GpuTexture.USAGE_RENDER_ATTACHMENT or
                    GpuTexture.USAGE_COPY_DST or GpuTexture.USAGE_COPY_SRC,
                "DXRMC DLSS output", FORMAT, targetWidth, targetHeight, 1, 1,
            )
            this.texture = created
            this.width = targetWidth
            this.height = targetHeight
            this.handedOverHandle = 0L
            note(
                "created a JVM-side ${targetWidth}x${targetHeight} DLSS output texture " +
                    "(STORAGE|RENDER_ATTACHMENT|COPY_DST|COPY_SRC) so NGX writes into a " +
                    "module-created resource",
            )
            created
        } catch (t: Throwable) {
            note(
                "could not create the JVM-side DLSS output texture ($t); DLSS keeps using the " +
                    "native private UAV this frame",
                warn = true,
            )
            null
        }
    }

    /**
     * 把 [textureForFrame] 那张交给 native（同一张只交一次）。native 没有这条导出时打一次 INFO 并退回旧路径。
     * @return true = native 接下了（`pInOutput` 会指到它）。
     */
    @JvmStatic
    fun handOver(tex: D3D12GpuTexture): Boolean {
        if (this.handedOverHandle == tex.handle()) {
            return true
        }
        val status = try {
            MCDR_ABI.D3D12SetDlssOutput(tex.handle())
        } catch (t: Throwable) {
            note("mcdr_dx12SetDlssOutput threw ($t); DLSS keeps using the native private UAV",
                warn = true)
            return false
        }
        return when (status) {
            1 -> {
                this.handedOverHandle = tex.handle()
                note(
                    "handed the JVM-created ${this.width}x${this.height} output texture to native " +
                        "as the DLSS output. This handover is experimental (evidence only): the " +
                        "presented target is still the OLD one (`D3D12GUISplit`), because NGX has " +
                        "not been proven to write into a texture we supply — with the handover " +
                        "actually driving the screen, the world went black while the GUI stayed " +
                        "fine (user report 2026-10-17).",
                )
                true
            }
            0 -> {
                note(
                    "native refused the JVM output texture (0: unusable); it keeps using its " +
                        "private UAV this frame",
                    warn = true,
                )
                false
            }
            -1 -> {
                note("mcdr_dx12SetDlssOutput rejected the handle (-1)", warn = true)
                false
            }
            Int.MIN_VALUE -> {
                note(
                    "the loaded MCDR.dll has no mcdr_dx12SetDlssOutput, so DLSS keeps using the " +
                        "native private UAV (the JVM-side texture is created but unused)",
                )
                false
            }
            else -> {
                note("mcdr_dx12SetDlssOutput returned an unexpected value $status", warn = true)
                false
            }
        }
    }

    /**
     * **顺序至关重要**：先 `mcdr_dx12SetDlssOutput(0)` 让 native 松手，再 `close()` 这张纹理。
     * 反过来 native 的 `externalOutput` 会指着已排进延迟销毁队列的资源，下一帧 evaluate 写它就是悬空指针（进程直接消失）。
     */
    @JvmStatic
    fun release() {
        val existing = this.texture
        val wasHandedOver = this.handedOverHandle != 0L
        this.texture = null
        this.width = 0
        this.height = 0
        this.handedOverHandle = 0L
        if (existing == null) {
            return
        }
        // 1) 先让 native 松手（就绪顺序：清 override -> 再销毁资源）。
        if (wasHandedOver) {
            try {
                MCDR_ABI.D3D12SetDlssOutput(0L)
            } catch (t: Throwable) {
                Illusion.LOGGER.warn(
                    "clearing the native DLSS output override before release threw: {}", t)
            }
        }
        // 2) 再销毁这张纹理。
        try {
            existing.close()
        } catch (t: Throwable) {
            Illusion.LOGGER.warn("closing the DLSS output texture threw: {}", t)
        }
        note("released the JVM-side DLSS output texture (native override cleared BEFORE close)")
    }

    /** 当前有没有一张已经交给 native 的纹理（取证用）。 */
    @JvmStatic
    fun isHandedOver(): Boolean = this.handedOverHandle != 0L

    /** 当前纹理尺寸（取证用）；0x0 = 没有。 */
    @JvmStatic
    fun currentSize(): String = if (this.texture == null) "none" else "${this.width}x${this.height}"

    /** 只在结论变化时打一行；[warn] 时用 WARN。 */
    private fun note(message: String, warn: Boolean = false) {
        if (this.lastNote == message) {
            return
        }
        this.lastNote = message
        if (warn) {
            Illusion.LOGGER.warn("{}", message)
        } else {
            Illusion.LOGGER.info("{}", message)
        }
    }
}