package io.github.dxegp.experimental.raytracing

import io.github.dxegp.d3d12.extern.MCDR_ABI
import io.github.dxegp.misc.IllusionLogger
import java.lang.foreign.Arena

internal object D3D12RtNrd {
    private var inspected = false

    fun inspectOnce() {
        if (inspected) return
        inspected = true
        Arena.ofConfined().use { arena ->
            val buffer = arena.allocate(2048L)
            val result = MCDR_ABI.D3D12NrdInspect(buffer, 2048)
            val lines = buffer.getString(0).lines().filter { it.isNotBlank() }
            if (lines.isEmpty()) {
                if (result == 0) IllusionLogger.infoMessage("nrd: not built in")
                else if (result < 0) IllusionLogger.warnMessage("nrd: inspect unavailable")
            }
            for (line in lines) {
                if (result >= 0) IllusionLogger.infoMessage(line) else IllusionLogger.warnMessage(line)
            }
        }
    }
}
