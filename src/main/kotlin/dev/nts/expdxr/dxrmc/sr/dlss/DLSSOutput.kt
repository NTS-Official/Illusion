package dev.nts.expdxr.dxrmc.sr.dlss

import com.mojang.renderpearl.api.GpuFormat
import com.mojang.renderpearl.api.textures.GpuTexture
import dev.nts.expdxr.Illusion
import dev.nts.expdxr.d3d12.extern.MCDR_ABI
import dev.nts.expdxr.d3d12.render.core.D3D12GpuTexture

/**
 * P108z（用户 m11203 / 父 agent 的控制组结论）：**交给 NGX 写的输出纹理必须由 JVM 建**。
 *
 * <p>native 侧实测的硬结论是："native 自建的 `CreateCommittedResource` 纹理存不住写入，模块
 * `createTexture` 建的可以"。DLSS 的输出因此不能再用 native 私有的那张 UAV
 * （`MCDR\src\advanced\d3d12_dlss.cpp` 里的 `s.dlssOutput`），必须由这里建一张带
 * [D3D12GpuTexture.USAGE_STORAGE] 的纹理、经 `mcdr_dx12SetDlssOutput` 交给 native —— native 会把
 * `pInOutput` 直接指到它（`d3d12_dlss.cpp:1786-1832` 的 `s.externalOutput` 分支），于是 NGX 写进去的
 * 是模块建的资源，而不是它自己建的。
 *
 * <p>为什么必须 `USAGE_STORAGE`：native 的 `textureFlags()`（`MCDR\src\core\d3d12_device.cpp:1876-1892`）
 * 只有看到 `usage & 32` 才加 `D3D12_RESOURCE_FLAG_ALLOW_UNORDERED_ACCESS`，而 NGX 只肯写进带
 * ALLOW_UNORDERED_ACCESS 的资源。位值 32 是原生 `GpuTexture` 四个常量之后的追加语义位，与
 * `D3D12Velocity` 用 16 之后的位置同一个约定（见 [D3D12GpuTexture.USAGE_STORAGE]）。
 *
 * <p>尺寸与生命周期：**target（窗口）尺寸**，与 `pInOutput` 那一档一致；窗口尺寸一变就重建；
 * 重建/退出时 `close()` 必须彻底（`D3D12GpuTexture.close()` → `ID3D12DestroyResource`，native 侧按
 * queue fence 延迟释放，所以这里立即 close 不会打断在飞命令列表）。
 *
 * <p>失败一律只降级、不打断渲染：设备还没建、尺寸非法、native 没有这条导出（[Int.MIN_VALUE]）
 * 都只是这一帧不交接，native 继续走它自己的私有 UAV 路径（`P108: DLSS private UAV output created`
 * 那条日志），行为与本改动之前完全一样。
 */
object DLSSOutput {

    private const val PREFIX: String = "P108z:"

    /**
     * `format` 参数 = native `dx12CreateTexture` 的枚举序数。历史日志里
     * `P108x: JVM target (pInOutput) fmt=28` 就是它（28 = DXGI_FORMAT_R8G8B8A8_UNORM），
     * 与 `pInOutput` 那一档一致 —— 不要改成别的格式，否则 NGX 的输入输出格式不匹配。
     */
    private val FORMAT: GpuFormat = GpuFormat.RGBA8_UNORM

    /** 当前交给 native 的那张纹理；null = 还没建 / 已释放。 */
    private var texture: D3D12GpuTexture? = null

    private var width: Int = 0
    private var height: Int = 0

    /** 已经交接给 native 的句柄（避免每帧重复下探同一次调用）。 */
    private var handedOverHandle: Long = 0L

    /** 上一次报告的结论；只在变化时打一行，避免每帧刷屏。 */
    private var lastNote: String? = null

    /**
     * 本帧该用的输出纹理（没有就按需建）。**只在渲染线程调用。**
     *
     * @param targetWidth / targetHeight 窗口（= `pInOutput`）尺寸；<=0 时返回 null。
     */
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
     * 把 [textureForFrame] 那张交给 native（幂等：同一张只交一次）。native 没有
     * `mcdr_dx12SetDlssOutput` 时只打一次 INFO 并静默退回旧路径。
     *
     * @return true = native 接下了这张（`pInOutput` 会指到它）。
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
     * 释放当前纹理（窗口尺寸变化、DLSS 关掉、设备销毁）。
     *
     * <p><b>顺序至关重要（user m11757 崩溃的根因）</b>：必须**先把 native 的 override 清掉**，
     * 再 `close()` 这张纹理。原实现是反的（先 `close()` 再 `dx12SetDlssOutput(0)`），于是中间
     * 存在一个窗口：native 的 `s.externalOutput` 仍然指着**已经被 `ID3D12DestroyResource` 排进
     * 延迟销毁队列**的那张资源，而下一帧的 DLSS evaluate 可能就拿着这个悬空指针去写 ——
     * 实测症状是"拖渲染缩放（触发 resize → 这里 release → 重建）后进程直接消失、没有任何
     * Java 异常"，与设备级故障（device removed / GPU hang）一致。
     *
     * <p>同一条推论也解释了为什么"只在 handover 模式"崩：只有那时 native 才持有我们的资源指针。
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
                    "[dxrmc] $PREFIX clearing the native DLSS output override before release threw: {}", t)
            }
        }
        // 2) 再销毁这张纹理。
        try {
            existing.close()
        } catch (t: Throwable) {
            Illusion.LOGGER.warn("[dxrmc] $PREFIX closing the DLSS output texture threw: {}", t)
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
            Illusion.LOGGER.warn("[dxrmc] $PREFIX {}", message)
        } else {
            Illusion.LOGGER.info("[dxrmc] $PREFIX {}", message)
        }
    }
}