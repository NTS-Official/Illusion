package dev.nts.expdxr.d3d12.extern

import java.lang.foreign.Arena
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout

/**
 * Structured mirror of `mcdr_mesh_pipeline_desc` (see docs/misc/ffm-abi.md §1.5).
 *
 * <p>The three blobs are **DXIL bytecode**, not HLSL: D3DCompile stops at SM 5.1, so the native side
 * compiles through DXC. No input layout and no topology — a mesh pipeline has no input assembler,
 * `PrimitiveTopologyType` is fixed to TRIANGLE. Colour-target and binding lists reuse
 * [MCDRPipelineDesc.ColorTarget] / [MCDRPipelineDesc.Binding] unchanged.
 */
class MCDRMeshPipelineDesc {

    companion object {
        /** Upper bounds mirrored from the native validator. */
        const val MAX_COLOR_TARGETS = 8
        const val MAX_BINDINGS = 64

        // Field offsets, resolved once from the shared layout; never literals.

        private val OFF_AS_DATA: Long = offMesh("asData")
        private val OFF_AS_LEN: Long = offMesh("asLen")
        private val OFF_MS_DATA: Long = offMesh("msData")
        private val OFF_MS_LEN: Long = offMesh("msLen")
        private val OFF_PS_DATA: Long = offMesh("psData")
        private val OFF_PS_LEN: Long = offMesh("psLen")
        private val OFF_COLOR_COUNT: Long = offMesh("colorCount")
        private val OFF_COLOR_TARGETS: Long = offMesh("colorTargets")
        private val OFF_HAS_DEPTH: Long = offMesh("hasDepth")
        private val OFF_DEPTH_STATE: Long = offMesh("depth")
        private val OFF_CULL: Long = offMesh("cullEnabled")
        private val OFF_POLYGON: Long = offMesh("polygonMode")
        private val OFF_BINDING_COUNT: Long = offMesh("bindingCount")
        private val OFF_BINDINGS: Long = offMesh("bindings")

        private val OFF_CT_FORMAT: Long = offColor("format")
        private val OFF_CT_WRITE_MASK: Long = offColor("writeMask")
        private val OFF_CT_BLEND_ENABLED: Long = offColor("blendEnabled")
        private val OFF_CT_SRC_COLOR: Long = offColor("srcColor")
        private val OFF_CT_DST_COLOR: Long = offColor("dstColor")
        private val OFF_CT_COLOR_OP: Long = offColor("colorOp")
        private val OFF_CT_SRC_ALPHA: Long = offColor("srcAlpha")
        private val OFF_CT_DST_ALPHA: Long = offColor("dstAlpha")
        private val OFF_CT_ALPHA_OP: Long = offColor("alphaOp")

        private val OFF_DS_FORMAT: Long = offDepth("depthFormat")
        private val OFF_DS_WRITE: Long = offDepth("depthWrite")
        private val OFF_DS_COMPARE: Long = offDepth("depthCompareOp")

        private val OFF_PB_TYPE: Long = offBinding("type")
        private val OFF_PB_REG: Long = offBinding("reg")

        private val BYTE: ValueLayout.OfByte = ValueLayout.JAVA_BYTE

        private fun offIn(layout: MemoryLayout, name: String): Long {
            try {
                return layout.byteOffset(MemoryLayout.PathElement.groupElement(name))
            } catch (e: IllegalArgumentException) {
                throw IllegalStateException("no field ${name} in ${layout}", e)
            }
        }

        private fun offMesh(name: String): Long = offIn(MCDRLayout.MESH_PIPELINE_DESC, name)
        private fun offColor(name: String): Long = offIn(MCDRLayout.COLOR_TARGET, name)
        private fun offDepth(name: String): Long = offIn(MCDRLayout.DEPTH_STATE, name)
        private fun offBinding(name: String): Long = offIn(MCDRLayout.PIPELINE_BINDING, name)
    }

    // Shader bytecode: `msDxil` is required; empty `asDxil` = no amplification stage,
    // empty `psDxil` = no pixel shader (nothing is written to the colour targets).

    var asDxil: ByteArray? = null
    var msDxil: ByteArray? = null
    var psDxil: ByteArray? = null

    var hasDepth: Boolean = false
    var depthFormat: Int = 0
    var depthWrite: Boolean = false
    var depthCompareOp: Int = 0

    var cullEnabled: Boolean = false
    var polygonMode: Int = 0

    val colorTargets: MutableList<MCDRPipelineDesc.ColorTarget> = ArrayList()
    val bindings: MutableList<MCDRPipelineDesc.Binding> = ArrayList()

    // Fluent builders.

    fun shaders(meshDxil: ByteArray?, amplificationDxil: ByteArray?, pixelDxil: ByteArray?): MCDRMeshPipelineDesc {
        this.msDxil = meshDxil
        this.asDxil = amplificationDxil
        this.psDxil = pixelDxil
        return this
    }

    fun depth(format: Int, write: Boolean, compareOp: Int): MCDRMeshPipelineDesc {
        this.hasDepth = true
        this.depthFormat = format
        this.depthWrite = write
        this.depthCompareOp = compareOp
        return this
    }

    fun topology(cull: Boolean, polygonModeOrdinal: Int): MCDRMeshPipelineDesc {
        this.cullEnabled = cull
        this.polygonMode = polygonModeOrdinal
        return this
    }

    fun addColorTarget(target: MCDRPipelineDesc.ColorTarget): MCDRMeshPipelineDesc {
        colorTargets.add(target)
        return this
    }

    fun addBinding(type: Int, reg: Int): MCDRMeshPipelineDesc {
        bindings.add(MCDRPipelineDesc.Binding(type, reg))
        return this
    }

    /**
     * Lay the descriptor out in `arena` and return the `mcdr_mesh_pipeline_desc` address; every
     * nested array lives in the same arena, so the result expires with it.
     */
    fun pack(arena: Arena): MemorySegment {
        require(msDxil?.isNotEmpty() == true) { "mesh shader bytecode (msDxil) is required" }
        require(colorTargets.size <= MAX_COLOR_TARGETS) { "too many colour targets: ${colorTargets.size}" }
        require(bindings.size <= MAX_BINDINGS) { "too many bindings: ${bindings.size}" }

        val desc = arena.allocate(MCDRLayout.MESH_PIPELINE_DESC)

        val asBlob = blob(arena, asDxil)
        val msBlob = blob(arena, msDxil)
        val psBlob = blob(arena, psDxil)
        desc.set(MCDRLayout.PTR, OFF_AS_DATA, asBlob)
        desc.set(MCDRLayout.I32, OFF_AS_LEN, byteLength(asDxil))
        desc.set(MCDRLayout.PTR, OFF_MS_DATA, msBlob)
        desc.set(MCDRLayout.I32, OFF_MS_LEN, byteLength(msDxil))
        desc.set(MCDRLayout.PTR, OFF_PS_DATA, psBlob)
        desc.set(MCDRLayout.I32, OFF_PS_LEN, byteLength(psDxil))

        desc.set(MCDRLayout.I32, OFF_COLOR_COUNT, colorTargets.size)
        if (colorTargets.isNotEmpty()) {
            val array = arena.allocate(MCDRLayout.COLOR_TARGET, colorTargets.size.toLong())
            for (i in 0 until colorTargets.size) {
                writeColorTarget(array, i * MCDRLayout.COLOR_TARGET.byteSize(), colorTargets[i])
            }
            desc.set(MCDRLayout.PTR, OFF_COLOR_TARGETS, array)
        }

        desc.set(MCDRLayout.I32, OFF_HAS_DEPTH, if (hasDepth) 1 else 0)
        if (hasDepth) {
            val d = OFF_DEPTH_STATE
            desc.set(MCDRLayout.I32, d + OFF_DS_FORMAT, depthFormat)
            desc.set(BYTE, d + OFF_DS_WRITE, (if (depthWrite) 1 else 0).toByte())
            desc.set(BYTE, d + OFF_DS_COMPARE, depthCompareOp.toByte())
        }

        desc.set(MCDRLayout.I32, OFF_CULL, if (cullEnabled) 1 else 0)
        desc.set(MCDRLayout.I32, OFF_POLYGON, polygonMode)

        desc.set(MCDRLayout.I32, OFF_BINDING_COUNT, bindings.size)
        if (bindings.isNotEmpty()) {
            val array = arena.allocate(MCDRLayout.PIPELINE_BINDING, bindings.size.toLong())
            val stride = MCDRLayout.PIPELINE_BINDING.byteSize()
            for (i in 0 until bindings.size) {
                val base = i * stride
                array.set(BYTE, base + OFF_PB_TYPE, bindings[i].type.toByte())
                array.set(BYTE, base + OFF_PB_REG, bindings[i].reg.toByte())
            }
            desc.set(MCDRLayout.PTR, OFF_BINDINGS, array)
        }

        return desc
    }

    private fun writeColorTarget(array: MemorySegment, base: Long, t: MCDRPipelineDesc.ColorTarget) {
        array.set(MCDRLayout.I32, base + OFF_CT_FORMAT, t.format)
        array.set(BYTE, base + OFF_CT_WRITE_MASK, t.writeMask.toByte())
        array.set(BYTE, base + OFF_CT_BLEND_ENABLED, (if (t.blendEnabled) 1 else 0).toByte())
        array.set(BYTE, base + OFF_CT_SRC_COLOR, t.srcColor.toByte())
        array.set(BYTE, base + OFF_CT_DST_COLOR, t.dstColor.toByte())
        array.set(BYTE, base + OFF_CT_COLOR_OP, t.colorOp.toByte())
        array.set(BYTE, base + OFF_CT_SRC_ALPHA, t.srcAlpha.toByte())
        array.set(BYTE, base + OFF_CT_DST_ALPHA, t.dstAlpha.toByte())
        array.set(BYTE, base + OFF_CT_ALPHA_OP, t.alphaOp.toByte())
    }

    private fun byteLength(data: ByteArray?): Int {
        return data?.size ?: 0
    }

    /**
     * Copy `data` into `arena`; an absent shader still gets a one-byte allocation so the pointer is
     * never NULL while the length is 0.
     */
    private fun blob(arena: Arena, data: ByteArray?): MemorySegment {
        if (data == null || data.size == 0) {
            return arena.allocate(1)
        }
        val seg = arena.allocate(data.size.toLong())
        MemorySegment.copy(data, 0, seg, BYTE, 0, data.size)
        return seg
    }
}