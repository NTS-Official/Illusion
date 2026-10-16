package dev.nts.expdxr.d3d12.extern

import java.lang.foreign.AddressLayout
import java.lang.foreign.GroupLayout
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemoryLayout.PathElement
import java.lang.foreign.ValueLayout
import java.util.*

/**
 * Memory-layout mirrors of the C aggregates declared in `MCDR/include/mcdr_abi.h`.
 *
 * <p>These are the single shared definition the design doc asks for: instead of the old
 * JNI bridge's hand-written little-endian `putInt`/`putU8` packing (which
 * cost a full black screen at P26 through a byte-order slip), both sides now describe the
 * same struct once and the native side verifies every offset and size at load time via
 * `mcdrABILayout()`.
 *
 * <p><b>[MemoryLayout.structLayout] inserts NO padding whatsoever.</b> It concatenates
 * members back to back and only rejects a member whose alignment does not divide the running
 * size. Every pad byte the C compiler would insert must therefore appear here explicitly, as
 * either a named `_padN` member (where the C header names one) or an unnamed
 * `MemoryLayout.paddingLayout(n)` (where the C compiler inserts it silently). Trailing
 * padding counts too: it does not move any field, but it is what makes `byteSize()` a
 * usable array stride.
 *
 * <p>[verify] compares this file against the running DLL. A mismatch means the
 * header and this mirror have drifted apart, and it is reported as a hard failure rather
 * than silently corrupting matrices the way the JNI version could.
 *
 * 互操作：`MCDR_ABI.kt` 用 `import dev.nts.expdxr.d3d12.extern.MCDRLayout.{I32,I64,F32,F64,PTR}`
 * 直接引用下面的标量布局，因此这些 `val` 必须标 `@JvmField`（生成真正的静态字段），
 * 不能写成普通 `val`（那会生成 `MCDRLayout.INSTANCE.getI32()` 形式的访问器，
 * Java 的 static import 解析不到）。
 */
object MCDRLayout {

    // ---- scalar layouts (JAVA_* already carry the native byte order on Windows x64) ----
    @JvmField
    val I32: ValueLayout.OfInt = ValueLayout.JAVA_INT

    @JvmField
    val I64: ValueLayout.OfLong = ValueLayout.JAVA_LONG

    @JvmField
    val F32: ValueLayout.OfFloat = ValueLayout.JAVA_FLOAT

    @JvmField
    val F64: ValueLayout.OfDouble = ValueLayout.JAVA_DOUBLE

    @JvmField
    val I8: ValueLayout.OfByte = ValueLayout.JAVA_BYTE

    @JvmField
    val PTR: AddressLayout = ValueLayout.ADDRESS

    // ------------------------------------------------------------------
    // mcdr_binding_slot — one pipeline binding slot for one core.
    // type: 0 = CBV (root descriptor), 1 = SRV (texture view), 2 = SRV (texel buffer).
    // ------------------------------------------------------------------
    @JvmField
    val BINDING_SLOT: MemoryLayout = MemoryLayout.structLayout(
        I32.withName("type"),
        I32.withName("texelFormat"),
        PTR.withName("buffer"),
        PTR.withName("view"),
        I64.withName("offset"),
        I64.withName("length")
    ).withByteAlignment(8).withName("mcdr_binding_slot")

    // ------------------------------------------------------------------
    // mcdr_partition_draw — one core's geometry + vertex/index binding.
    // Replaces the JNI hot path's 9 parallel arrays.
    // ------------------------------------------------------------------
    @JvmField
    val PARTITION_DRAW: MemoryLayout = MemoryLayout.structLayout(
        PTR.withName("indexBuffer"),
        PTR.withName("vertexBuffer"),
        I64.withName("vertexOffset"),
        I32.withName("indexType"),
        I32.withName("indexCount"),
        I32.withName("firstIndex"),
        I32.withName("baseVertex"),
        I32.withName("vertexSlot"),
        I32.withName("vertexStride"),
        I32.withName("_pad0"),
        MemoryLayout.paddingLayout(4)   // C: sizeof == 56 (align 8), member sum == 52
    ).withByteAlignment(8).withName("mcdr_partition_draw")

    // ------------------------------------------------------------------
    // ------------------------------------------------------------------
    // Named field offsets for the two hot aggregation structs. Resolved from the
    // layouts above — never written as literals. MCDR_ABI's descriptor packers used
    // to say `base + 4` / `base + 8` / `base + 24`, which is exactly the silent-drift
    // class this mirror exists to remove: a field added on the C side would have
    // shifted every later one with no compile error and no exception.
    // ------------------------------------------------------------------

    @JvmStatic
    fun offset(layout: MemoryLayout, field: String): Long {
        try {
            return layout.byteOffset(MemoryLayout.PathElement.groupElement(field))
        } catch (e: IllegalArgumentException) {
            throw IllegalStateException("no field ${field} in ${layout}", e)
        }
    }

    @JvmField val BINDING_SLOT_TYPE: Long = offset(BINDING_SLOT, "type")
    @JvmField val BINDING_SLOT_TEXEL_FORMAT: Long = offset(BINDING_SLOT, "texelFormat")
    @JvmField val BINDING_SLOT_BUFFER: Long = offset(BINDING_SLOT, "buffer")
    @JvmField val BINDING_SLOT_VIEW: Long = offset(BINDING_SLOT, "view")
    @JvmField val BINDING_SLOT_OFFSET: Long = offset(BINDING_SLOT, "offset")
    @JvmField val BINDING_SLOT_LENGTH: Long = offset(BINDING_SLOT, "length")

    @JvmField val PARTITION_DRAW_INDEX_BUFFER: Long = offset(PARTITION_DRAW, "indexBuffer")
    @JvmField val PARTITION_DRAW_VERTEX_BUFFER: Long = offset(PARTITION_DRAW, "vertexBuffer")
    @JvmField val PARTITION_DRAW_VERTEX_OFFSET: Long = offset(PARTITION_DRAW, "vertexOffset")
    @JvmField val PARTITION_DRAW_INDEX_TYPE: Long = offset(PARTITION_DRAW, "indexType")
    @JvmField val PARTITION_DRAW_INDEX_COUNT: Long = offset(PARTITION_DRAW, "indexCount")
    @JvmField val PARTITION_DRAW_FIRST_INDEX: Long = offset(PARTITION_DRAW, "firstIndex")
    @JvmField val PARTITION_DRAW_BASE_VERTEX: Long = offset(PARTITION_DRAW, "baseVertex")
    @JvmField val PARTITION_DRAW_VERTEX_SLOT: Long = offset(PARTITION_DRAW, "vertexSlot")
    @JvmField val PARTITION_DRAW_VERTEX_STRIDE: Long = offset(PARTITION_DRAW, "vertexStride")
    @JvmField val PARTITION_DRAW_PAD0: Long = offset(PARTITION_DRAW, "_pad0")
    // mcdr_color_target_desc — format == -1 means an unused attachment slot.
    // ------------------------------------------------------------------
    @JvmField
    val COLOR_TARGET: MemoryLayout = MemoryLayout.structLayout(
        I32.withName("format"),
        I8.withName("writeMask"),
        I8.withName("blendEnabled"),
        I8.withName("srcColor"),
        I8.withName("dstColor"),
        I8.withName("colorOp"),
        I8.withName("srcAlpha"),
        I8.withName("dstAlpha"),
        I8.withName("alphaOp")
    ).withName("mcdr_color_target_desc")

    @JvmField
    val DEPTH_STATE: MemoryLayout = MemoryLayout.structLayout(
        I32.withName("depthFormat"),
        I8.withName("depthWrite"),
        I8.withName("depthCompareOp"),
        // C: sizeof == 8 (align 4, member sum 6). This 2-byte tail is load-bearing twice
        // over: it is also what keeps `topology` at offset 56 inside PIPELINE_DESC.
        MemoryLayout.paddingLayout(2)
    ).withByteAlignment(4).withName("mcdr_depth_state_desc")

    // ------------------------------------------------------------------
    // mcdr_input_element_desc — semanticName is a NUL-terminated UTF-8 string owned by
    // the caller; NULL / "" makes the native side fall back to "TEXCOORD".
    // ------------------------------------------------------------------
    @JvmField
    val INPUT_ELEMENT: MemoryLayout = MemoryLayout.structLayout(
        I32.withName("location"),
        I32.withName("binding"),
        I32.withName("format"),
        I32.withName("offset"),
        I32.withName("stride"),
        I32.withName("stepRate"),
        I32.withName("_pad0"),
        MemoryLayout.paddingLayout(4),  // C: the pointer lands at 32, not at 28
        PTR.withName("semanticName")
    ).withByteAlignment(8).withName("mcdr_input_element_desc")

    @JvmField
    val PIPELINE_BINDING: MemoryLayout = MemoryLayout.structLayout(
        I8.withName("type"),
        I8.withName("reg")
    ).withName("mcdr_pipeline_binding_desc")

    // ------------------------------------------------------------------
    // mcdr_pipeline_desc — replaces the old packed little-endian ByteBuffer blob.
    // ------------------------------------------------------------------
    @JvmField
    val PIPELINE_DESC: MemoryLayout = MemoryLayout.structLayout(
        PTR.withName("vsData"),
        I32.withName("vsLen"),
        I32.withName("_pad0"),
        PTR.withName("psData"),
        I32.withName("psLen"),
        I32.withName("colorCount"),
        PTR.withName("colorTargets"),
        I32.withName("hasDepth"),
        I32.withName("_pad1"),
        DEPTH_STATE.withName("depth"),
        I32.withName("topology"),
        I32.withName("cullEnabled"),
        I32.withName("polygonMode"),
        I32.withName("inputElementCount"),
        PTR.withName("inputElements"),
        I32.withName("bindingCount"),
        I32.withName("_pad2"),
        PTR.withName("bindings")
    ).withName("mcdr_pipeline_desc")

    // ------------------------------------------------------------------
    // mcdr_mesh_pipeline_desc — P72. asData/msData/psData are DXIL bytecode (not HLSL
    // source): D3DCompile stops at SM 5.1 and cannot produce mesh shaders at all, so the
    // native side compiles them through DXC. msData is required, asData/psData may be null.
    // ------------------------------------------------------------------
    @JvmField
    val MESH_PIPELINE_DESC: MemoryLayout = MemoryLayout.structLayout(
        PTR.withName("asData"),
        I32.withName("asLen"),
        I32.withName("_pad0"),
        PTR.withName("msData"),
        I32.withName("msLen"),
        I32.withName("_pad1"),
        PTR.withName("psData"),
        I32.withName("psLen"),
        I32.withName("colorCount"),
        PTR.withName("colorTargets"),
        I32.withName("hasDepth"),
        I32.withName("_pad2"),
        DEPTH_STATE.withName("depth"),
        I32.withName("cullEnabled"),
        I32.withName("polygonMode"),
        I32.withName("bindingCount"),
        I32.withName("_pad3"),
        PTR.withName("bindings")
    ).withName("mcdr_mesh_pipeline_desc")

    // ------------------------------------------------------------------
    // mcdr_abi_field — the self-check record returned by mcdrABILayout().
    // ------------------------------------------------------------------
    @JvmField
    val ABI_FIELD: MemoryLayout = MemoryLayout.structLayout(
        PTR.withName("name"),
        I64.withName("offset"),
        I64.withName("size")
    ).withName("mcdr_abi_field")

    // ------------------------------------------------------------------
    // The size the C compiler produces for each aggregate above, written out by hand.
    // Asserted while this class initialises, so a drift in the mirror fails immediately,
    // before any native call and even when no DLL is present. (Field offsets cannot be
    // checked this way; MCDR_ABI.verifyABI() compares those against the running DLL.)
    // ------------------------------------------------------------------
    // mcdr_dlss_input — P108. 每帧 DLSS Evaluate 的参数包（C sizeof = 112）。
    // 字段顺序/名字必须与 MCDR\include\mcdr_abi.h 的 typedef 逐字一致；_pad0/_pad1 是两个
    // 隐式对齐填充位置（C 里它们无名，这里给名字只为让 describe() 能枚举到）。
    // ------------------------------------------------------------------
    @JvmField
    val DLSS_INPUT: MemoryLayout = MemoryLayout.structLayout(
        I32.withName("renderWidth"),
        I32.withName("renderHeight"),
        I32.withName("targetWidth"),
        I32.withName("targetHeight"),
        F32.withName("jitterOffsetX"),
        F32.withName("jitterOffsetY"),
        F32.withName("mvScaleX"),
        F32.withName("mvScaleY"),
        F32.withName("preExposure"),
        F32.withName("exposureScale"),
        I32.withName("exposureMode"),
        I32.withName("alphaUpscaling"),
        F32.withName("nearZ"),
        F32.withName("farZ"),
        I32.withName("depthInverted"),
        I32.withName("reset"),
        I32.withName("depthFormatOrdinal"),
        I32.withName("_pad0"),
        PTR.withName("pInColor"),
        PTR.withName("pInDepth"),
        PTR.withName("pInMotionVectors"),
        PTR.withName("pInOutput"),
        I32.withName("_pad1"),
        I32.withName("_pad2")
    ).withName("mcdr_dlss_input")

    private val EXPECTED_C_SIZES: Array<Array<Any>> = arrayOf(
    // ------------------------------------------------------------------
        arrayOf<Any>("mcdr_binding_slot",         BINDING_SLOT,        40L),
        arrayOf<Any>("mcdr_partition_draw",       PARTITION_DRAW,      56L),
        arrayOf<Any>("mcdr_color_target_desc",    COLOR_TARGET,        12L),
        arrayOf<Any>("mcdr_depth_state_desc",     DEPTH_STATE,          8L),
        arrayOf<Any>("mcdr_input_element_desc",   INPUT_ELEMENT,       40L),
        arrayOf<Any>("mcdr_pipeline_binding_desc", PIPELINE_BINDING,    2L),
        arrayOf<Any>("mcdr_pipeline_desc",        PIPELINE_DESC,       96L),
        arrayOf<Any>("mcdr_mesh_pipeline_desc",   MESH_PIPELINE_DESC,  96L),
        arrayOf<Any>("mcdr_dlss_input",          DLSS_INPUT,        112L),
        arrayOf<Any>("mcdr_abi_field",            ABI_FIELD,           24L),
    )

    init {
        for (row in EXPECTED_C_SIZES) {
            val actual = (row[1] as MemoryLayout).byteSize()
            val expected = row[2] as Long
            if (actual != expected) {
                throw IllegalStateException(
                    "MCDR ABI struct size mismatch for ${row[0]}" +
                        ": java=${actual} c=${expected}" +
                        " — fix the mirror in MCDR_Layout.kt against MCDR/include/mcdr_abi.h"
                )
            }
        }
    }

    /** Every mirrored aggregate, keyed by its C type name. */
    private val STRUCTS: MutableMap<String, MemoryLayout> = LinkedHashMap()

    init {
        // Order is irrelevant; the map exists so describe() and verify() enumerate the
        // same set and a newly added struct cannot be silently left unverified.
        STRUCTS["mcdr_binding_slot"] = BINDING_SLOT
        STRUCTS["mcdr_partition_draw"] = PARTITION_DRAW
        STRUCTS["mcdr_color_target_desc"] = COLOR_TARGET
        STRUCTS["mcdr_depth_state_desc"] = DEPTH_STATE
        STRUCTS["mcdr_input_element_desc"] = INPUT_ELEMENT
        STRUCTS["mcdr_pipeline_binding_desc"] = PIPELINE_BINDING
        STRUCTS["mcdr_pipeline_desc"] = PIPELINE_DESC
        STRUCTS["mcdr_mesh_pipeline_desc"] = MESH_PIPELINE_DESC
        STRUCTS["mcdr_dlss_input"] = DLSS_INPUT
    }

    /** Named top-level members of every mirrored struct, as `"Struct.field" -> {offset, size}`. */
    @JvmStatic
    fun describe(): Map<String, LongArray> {
        val out = linkedMapOf<String, LongArray>()
        for (e in STRUCTS.entries) {
            val structName = e.key
            val layout = e.value
            for (member in (layout as GroupLayout).memberLayouts()) {
                val nameOpt: Optional<String> = member.name()
                if (nameOpt.isEmpty) {
                    continue // implicit alignment padding — the C macro does not name it either
                }
                val field = nameOpt.get()
                val offset = layout.byteOffset(PathElement.groupElement(field))
                out["${structName}.${field}"] = longArrayOf(offset, member.byteSize())
            }
        }
        return out
    }

    /**
     * Compare these mirrors against the offsets/sizes the running DLL reports.
     *
     * @param nativeFields what `mcdrABILayout()` published, as `name -> {offset, size}`
     * @throws IllegalStateException listing every disagreement
     */
    @JvmStatic
    fun verify(nativeFields: Map<String, LongArray>) {
        val javaFields = describe()
        val problems = mutableListOf<String>()

        for (e in javaFields.entries) {
            val nativeValue = nativeFields[e.key]
            if (nativeValue == null) {
                problems.add("${e.key}: missing from the native layout report")
                continue
            }
            if (nativeValue[0] != e.value[0]) {
                problems.add("${e.key}: offset java=${e.value[0]} native=${nativeValue[0]}")
            }
            if (nativeValue[1] != e.value[1]) {
                problems.add("${e.key}: size java=${e.value[1]} native=${nativeValue[1]}")
            }
        }
        for (name in nativeFields.keys) {
            if (!javaFields.containsKey(name)) {
                problems.add("${name}: reported by native but absent from the Java mirror")
            }
        }

        if (problems.isNotEmpty()) {
            throw IllegalStateException(
                "MCDR ABI layout mismatch (${problems.size}" +
                    " field(s)); the Kotlin mirror in dev.nts.expdxr.d3d12.extern.MCDRLayout" +
                    " disagrees with MCDR/include/mcdr_abi.h:\n  ${problems.joinToString("\n  ")}"
            )
        }
    }

    /** Struct sizes only, for a compact startup log line. */
    @JvmStatic
    fun describeSizes(): String {
        return STRUCTS.entries.joinToString(", ") { "${it.key}=${it.value.byteSize()}" }
    }
}
