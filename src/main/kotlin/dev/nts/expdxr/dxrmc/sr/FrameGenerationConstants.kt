package dev.nts.expdxr.dxrmc.sr

import dev.nts.expdxr.d3d12.extern.MCDRLayout
import dev.nts.expdxr.d3d12.extern.MCDR_ABI
import dev.nts.expdxr.d3d12.render.advanced.taa.D3D12Temporal
import java.lang.foreign.Arena
import java.lang.foreign.ValueLayout
import dev.nts.expdxr.misc.IllusionLogger

/**
 * 帧生成自己的帧常量（view / projection / jitter / mvScale / resetHistory）。独立于 SR 后端：这些是**插帧**的输入，
 * 而 DLSS 的 constants 属于 Streamline 私有状态。native 侧 `mcdr_dx12FgSetConstants` 只保存，由 provider 取走。
 * 见 docs/superresolution/README.md §7。
 */
internal object FrameGenerationConstants {

    /** 每个真正要出帧的渲染帧调一次；`jitter` 传**实际加到投影上**的那一份。 */
    fun push(jitterX: Float, jitterY: Float, reset: Boolean) {
        val proj = D3D12Temporal.currentProjection() ?: return
        val view = D3D12Temporal.currentCameraView() ?: return
        val camPos = D3D12Temporal.currentCameraPosition()
        // 世界层是相机相对空间：`view` 只有旋转。`clipToPrevClip` 必须含相机平移，否则「静止正常、一走动就拖影」。
        val worldView = worldSpaceView(view, camPos?.get(0) ?: 0.0f, camPos?.get(1) ?: 0.0f,
            camPos?.get(2) ?: 0.0f)
        try {
            Arena.ofConfined().use { arena ->
                val fc = arena.allocate(MCDRLayout.FG_CONSTANTS)
                val f32 = ValueLayout.JAVA_FLOAT
                val viewRow = rowMajor(worldView)
                val projRow = rowMajor(proj)
                for (n in 0 until 16) {
                    fc.set(f32, MCDRLayout.FG_CONSTANTS_VIEW + n * 4L, viewRow[n])
                    fc.set(f32, MCDRLayout.FG_CONSTANTS_PROJECTION + n * 4L, projRow[n])
                }
                fc.set(f32, MCDRLayout.FG_CONSTANTS_JITTER_X, jitterX)
                fc.set(f32, MCDRLayout.FG_CONSTANTS_JITTER_Y, jitterY)
                // MV 是渲染像素空间（见 D3D12Velocity），缩放 1.0 与 SR 侧同一约定。
                fc.set(f32, MCDRLayout.FG_CONSTANTS_MV_SCALE_X, 1.0f)
                fc.set(f32, MCDRLayout.FG_CONSTANTS_MV_SCALE_Y, 1.0f)
                fc.set(ValueLayout.JAVA_INT, MCDRLayout.FG_CONSTANTS_RESET_HISTORY, if (reset) 1 else 0)
                fc.set(f32, MCDRLayout.FG_CONSTANTS_FRAME_RENDER_TIME, 0.0f)
                MCDR_ABI.D3D12FgSetConstants(fc)
            }
        } catch (t: Throwable) {
            IllusionLogger.warnMessage("push frame generation constants failed: $t")
        }
    }

    /** 把相机平移烘进视图矩阵：`V_world = V_rot * T(-camPos)`。 */
    private fun worldSpaceView(rotation: FloatArray, px: Float, py: Float, pz: Float): FloatArray {
        if (rotation.size < 16) {
            return rotation
        }
        val out = rotation.copyOf()
        for (row in 0 until 3) {
            val rx = rotation[row]
            val ry = rotation[4 + row]
            val rz = rotation[8 + row]
            out[12 + row] = -(rx * px + ry * py + rz * pz)
        }
        return out
    }

    /** 列主序 → 行主序（XeSS-FG 的契约是行主序）。 */
    private fun rowMajor(src: FloatArray): FloatArray {
        val out = FloatArray(16)
        for (r in 0 until 4) {
            for (c in 0 until 4) {
                out[r * 4 + c] = src[c * 4 + r]
            }
        }
        return out
    }
}