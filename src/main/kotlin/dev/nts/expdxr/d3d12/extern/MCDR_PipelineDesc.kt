package dev.nts.expdxr.d3d12.extern

import java.lang.foreign.Arena
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.nio.charset.StandardCharsets

/**
 * Builder for `mcdr_pipeline_desc` — fill the lists with the fluent `with*` helpers, then [pack] it
 * into an arena that outlives the downcall. Mirror layout: [MCDRLayout.PIPELINE_DESC]
 * (docs/misc/ffm-abi.md §2.5).
 *
 * 互操作：`ColorTarget` / `InputElement` / `Binding` 是**嵌套类**（Kotlin 默认即 Java 的 static
 * nested，不能写成 `inner class`），`MAX_*` 用 `const val` 以便 Java 侧静态读取。
 */
class MCDRPipelineDesc {

    companion object {
        /** Upper bounds mirrored from the native validator. */
        const val MAX_COLOR_TARGETS = 8
        const val MAX_INPUT_ELEMENTS = 64
        const val MAX_BINDINGS = 64
        const val MAX_SEMANTIC_NAME_BYTES = 64

        // Field offsets, resolved once from the shared layout; never literals.

        private val OFF_VS_DATA: Long = off(MCDRLayout.PIPELINE_DESC, "vsData")
        private val OFF_VS_LEN: Long = off(MCDRLayout.PIPELINE_DESC, "vsLen")
        private val OFF_PS_DATA: Long = off(MCDRLayout.PIPELINE_DESC, "psData")
        private val OFF_PS_LEN: Long = off(MCDRLayout.PIPELINE_DESC, "psLen")
        private val OFF_COLOR_COUNT: Long = off(MCDRLayout.PIPELINE_DESC, "colorCount")
        private val OFF_COLOR_TARGETS: Long = off(MCDRLayout.PIPELINE_DESC, "colorTargets")
        private val OFF_HAS_DEPTH: Long = off(MCDRLayout.PIPELINE_DESC, "hasDepth")
        private val OFF_DEPTH_STATE: Long = off(MCDRLayout.PIPELINE_DESC, "depth")
        private val OFF_TOPOLOGY: Long = off(MCDRLayout.PIPELINE_DESC, "topology")
        private val OFF_CULL: Long = off(MCDRLayout.PIPELINE_DESC, "cullEnabled")
        private val OFF_POLYGON: Long = off(MCDRLayout.PIPELINE_DESC, "polygonMode")
        private val OFF_ELEM_COUNT: Long = off(MCDRLayout.PIPELINE_DESC, "inputElementCount")
        private val OFF_ELEMENTS: Long = off(MCDRLayout.PIPELINE_DESC, "inputElements")
        private val OFF_BINDING_COUNT: Long = off(MCDRLayout.PIPELINE_DESC, "bindingCount")
        private val OFF_BINDINGS: Long = off(MCDRLayout.PIPELINE_DESC, "bindings")

        private val OFF_CT_FORMAT: Long = off(MCDRLayout.COLOR_TARGET, "format")
        private val OFF_CT_WRITE_MASK: Long = off(MCDRLayout.COLOR_TARGET, "writeMask")
        private val OFF_CT_BLEND_ENABLED: Long = off(MCDRLayout.COLOR_TARGET, "blendEnabled")
        private val OFF_CT_SRC_COLOR: Long = off(MCDRLayout.COLOR_TARGET, "srcColor")
        private val OFF_CT_DST_COLOR: Long = off(MCDRLayout.COLOR_TARGET, "dstColor")
        private val OFF_CT_COLOR_OP: Long = off(MCDRLayout.COLOR_TARGET, "colorOp")
        private val OFF_CT_SRC_ALPHA: Long = off(MCDRLayout.COLOR_TARGET, "srcAlpha")
        private val OFF_CT_DST_ALPHA: Long = off(MCDRLayout.COLOR_TARGET, "dstAlpha")
        private val OFF_CT_ALPHA_OP: Long = off(MCDRLayout.COLOR_TARGET, "alphaOp")

        private val OFF_DS_FORMAT: Long = off(MCDRLayout.DEPTH_STATE, "depthFormat")
        private val OFF_DS_WRITE: Long = off(MCDRLayout.DEPTH_STATE, "depthWrite")
        private val OFF_DS_COMPARE: Long = off(MCDRLayout.DEPTH_STATE, "depthCompareOp")

        private val OFF_IE_LOCATION: Long = off(MCDRLayout.INPUT_ELEMENT, "location")
        private val OFF_IE_BINDING: Long = off(MCDRLayout.INPUT_ELEMENT, "binding")
        private val OFF_IE_FORMAT: Long = off(MCDRLayout.INPUT_ELEMENT, "format")
        private val OFF_IE_OFFSET: Long = off(MCDRLayout.INPUT_ELEMENT, "offset")
        private val OFF_IE_STRIDE: Long = off(MCDRLayout.INPUT_ELEMENT, "stride")
        private val OFF_IE_STEP_RATE: Long = off(MCDRLayout.INPUT_ELEMENT, "stepRate")
        private val OFF_IE_SEMANTIC: Long = off(MCDRLayout.INPUT_ELEMENT, "semanticName")

        private val OFF_PB_TYPE: Long = off(MCDRLayout.PIPELINE_BINDING, "type")
        private val OFF_PB_REG: Long = off(MCDRLayout.PIPELINE_BINDING, "reg")

        private val BYTE: ValueLayout.OfByte = ValueLayout.JAVA_BYTE

        private fun off(layout: MemoryLayout, name: String): Long {
            try {
                return layout.byteOffset(MemoryLayout.PathElement.groupElement(name))
            } catch (e: IllegalArgumentException) {
                throw IllegalStateException("no field ${name} in ${layout}", e)
            }
        }
    }

    /** One render-target slot. `format == -1` marks the slot unused. */
    class ColorTarget {
        var format: Int = -1
        var writeMask: Int = 0
        var blendEnabled: Boolean = false
        var srcColor: Int = 0
        var dstColor: Int = 0
        var colorOp: Int = 0
        var srcAlpha: Int = 0
        var dstAlpha: Int = 0
        var alphaOp: Int = 0

        fun format(value: Int): ColorTarget {
            this.format = value
            return this
        }

        fun writeMask(value: Int): ColorTarget {
            this.writeMask = value
            return this
        }

        /** Sets `blendEnabled`; a deferred pipeline without a blend block means no blend. */
        fun blend(srcColor: Int, dstColor: Int, colorOp: Int,
            srcAlpha: Int, dstAlpha: Int, alphaOp: Int): ColorTarget {
            this.blendEnabled = true
            this.srcColor = srcColor
            this.dstColor = dstColor
            this.colorOp = colorOp
            this.srcAlpha = srcAlpha
            this.dstAlpha = dstAlpha
            this.alphaOp = alphaOp
            return this
        }
    }

    /** One vertex input element; `semanticName` is the HLSL semantic ("POSITION", "TEXCOORD0"). */
    class InputElement(
        var location: Int,
        var binding: Int,
        var format: Int,
        var offset: Int,
        var stride: Int,
        var stepRate: Int,
        /** Empty or null lets the native side fall back to "TEXCOORD". */
        var semanticName: String?
    )

    /** One pipeline binding: `type` 0=CBV, 1=SRV+static sampler, 2=SRV(texel buffer). */
    class Binding(
        var type: Int,
        var reg: Int
    )

    var vsBytes: ByteArray? = null
    var psBytes: ByteArray? = null
    var hasDepth: Boolean = false
    var depthFormat: Int = 0
    var depthWrite: Boolean = false
    var depthCompareOp: Int = 0
    var topology: Int = 0
    var cullEnabled: Boolean = false
    var polygonMode: Int = 0

    val colorTargets: MutableList<ColorTarget> = ArrayList()
    val inputElements: MutableList<InputElement> = ArrayList()
    val bindings: MutableList<Binding> = ArrayList()

    // Fluent builders.

    fun shaders(vertexHlslUtf8: ByteArray?, fragmentHlslUtf8: ByteArray?): MCDRPipelineDesc {
        this.vsBytes = vertexHlslUtf8
        this.psBytes = fragmentHlslUtf8
        return this
    }

    fun shaders(vertexHlsl: String?, fragmentHlsl: String?): MCDRPipelineDesc {
        return shaders(utf8(vertexHlsl), utf8(fragmentHlsl))
    }

    fun depth(format: Int, write: Boolean, compareOp: Int): MCDRPipelineDesc {
        this.hasDepth = true
        this.depthFormat = format
        this.depthWrite = write
        this.depthCompareOp = compareOp
        return this
    }

    fun topology(topologyOrdinal: Int, cull: Boolean, polygonModeOrdinal: Int): MCDRPipelineDesc {
        this.topology = topologyOrdinal
        this.cullEnabled = cull
        this.polygonMode = polygonModeOrdinal
        return this
    }

    fun addColorTarget(target: ColorTarget): MCDRPipelineDesc {
        colorTargets.add(target)
        return this
    }

    fun addInputElement(element: InputElement): MCDRPipelineDesc {
        inputElements.add(element)
        return this
    }

    fun addBinding(type: Int, reg: Int): MCDRPipelineDesc {
        bindings.add(Binding(type, reg))
        return this
    }

    /**
     * Lay the descriptor out in `arena` and return the `mcdr_pipeline_desc` address. Every nested
     * array and C string lives in the same arena, so the result expires with it.
     */
    fun pack(arena: Arena): MemorySegment {
        require(colorTargets.size <= MAX_COLOR_TARGETS) { "too many colour targets: ${colorTargets.size}" }
        require(inputElements.size <= MAX_INPUT_ELEMENTS) { "too many input elements: ${inputElements.size}" }
        require(bindings.size <= MAX_BINDINGS) { "too many bindings: ${bindings.size}" }

        val desc = arena.allocate(MCDRLayout.PIPELINE_DESC)

        val vs = blob(arena, vsBytes)
        val ps = blob(arena, psBytes)
        desc.set(MCDRLayout.PTR, OFF_VS_DATA, vs)
        desc.set(MCDRLayout.I32, OFF_VS_LEN, byteLength(vsBytes))
        desc.set(MCDRLayout.PTR, OFF_PS_DATA, ps)
        desc.set(MCDRLayout.I32, OFF_PS_LEN, byteLength(psBytes))

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

        desc.set(MCDRLayout.I32, OFF_TOPOLOGY, topology)
        desc.set(MCDRLayout.I32, OFF_CULL, if (cullEnabled) 1 else 0)
        desc.set(MCDRLayout.I32, OFF_POLYGON, polygonMode)

        desc.set(MCDRLayout.I32, OFF_ELEM_COUNT, inputElements.size)
        if (inputElements.isNotEmpty()) {
            val array = arena.allocate(MCDRLayout.INPUT_ELEMENT, inputElements.size.toLong())
            val stride = MCDRLayout.INPUT_ELEMENT.byteSize()
            for (i in 0 until inputElements.size) {
                writeInputElement(arena, array, i * stride, inputElements[i])
            }
            desc.set(MCDRLayout.PTR, OFF_ELEMENTS, array)
        }

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

    private fun writeColorTarget(array: MemorySegment, base: Long, t: ColorTarget) {
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

    private fun writeInputElement(arena: Arena, array: MemorySegment, base: Long,
        e: InputElement) {
        array.set(MCDRLayout.I32, base + OFF_IE_LOCATION, e.location)
        array.set(MCDRLayout.I32, base + OFF_IE_BINDING, e.binding)
        array.set(MCDRLayout.I32, base + OFF_IE_FORMAT, e.format)
        array.set(MCDRLayout.I32, base + OFF_IE_OFFSET, e.offset)
        array.set(MCDRLayout.I32, base + OFF_IE_STRIDE, e.stride)
        array.set(MCDRLayout.I32, base + OFF_IE_STEP_RATE, e.stepRate)
        array.set(MCDRLayout.PTR, base + OFF_IE_SEMANTIC, cstring(arena, e.semanticName))
    }

    private fun utf8(s: String?): ByteArray? {
        return s?.toByteArray(StandardCharsets.UTF_8)
    }

    private fun byteLength(data: ByteArray?): Int {
        return data?.size ?: 0
    }

    /**
     * Copy `data` into `arena`; an empty shader still gets a one-byte allocation so the pointer is
     * never NULL (native rejects NULL paired with a non-zero length).
     */
    private fun blob(arena: Arena, data: ByteArray?): MemorySegment {
        if (data == null || data.size == 0) {
            return arena.allocate(1)
        }
        val seg = arena.allocate(data.size.toLong())
        MemorySegment.copy(data, 0, seg, BYTE, 0, data.size)
        return seg
    }

    /** NUL-terminated UTF-8, or NULL when the name is empty (native falls back to TEXCOORD). */
    private fun cstring(arena: Arena, s: String?): MemorySegment {
        if (s == null || s.isEmpty()) {
            return MemorySegment.NULL
        }
        val bytes = s.toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= MAX_SEMANTIC_NAME_BYTES) { "semantic name too long (${bytes.size}): ${s}" }
        val seg = arena.allocate(bytes.size + 1L)
        MemorySegment.copy(bytes, 0, seg, BYTE, 0, bytes.size)
        seg.set(BYTE, bytes.size.toLong(), 0.toByte())
        return seg
    }
}
