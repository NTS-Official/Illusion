package io.github.dxegp.d3d12.extern

import java.lang.foreign.AddressLayout
import java.lang.foreign.GroupLayout
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemoryLayout.PathElement
import java.lang.foreign.ValueLayout
import java.util.*

/**
 * Mirror of the C aggregates declared in `MCDR/include/mcdr_abi.h`; see docs/misc/ffm-abi.md §1.2.
 *
 * <p><b>[MemoryLayout.structLayout] inserts NO padding.</b> Every pad byte the C compiler would
 * insert must appear here explicitly — named `_padN` where the header names one, else
 * `MemoryLayout.paddingLayout(n)`. Trailing padding counts too: it is what makes `byteSize()`
 * a usable array stride.
 *
 * 互操作：标量布局与偏移常量必须 `@JvmField`（普通 `val` 生成 `MCDR.INSTANCE.getXxx()` 访问器，
 * Java 侧的 static import 解析不到）。
 */
object MCDRLayout {
    const val SDK_RESOURCE_STATE_VALUE_COUNT = 8
    const val NRD_JITTER_FLOAT_COUNT = 4
    const val RT_EXPOSURE_FLOAT_COUNT = 2

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

    @JvmField
    val QUERY_QUEUE: FunctionDescriptor = FunctionDescriptor.of(I32, PTR, PTR, I32, I32)

    @JvmField
    val RT_FRAME_SET_SPATIAL: FunctionDescriptor = FunctionDescriptor.of(I32, PTR, I64, I64, I64, I64, PTR, I64)
    val RT_FRAME_TRANSACTION: FunctionDescriptor = FunctionDescriptor.of(I32, PTR, I64, PTR, PTR)

    @JvmField
    val QUERY_POLL: FunctionDescriptor = FunctionDescriptor.of(I32, PTR, I32, I32, PTR)
    val RT_SCENE_STATS: FunctionDescriptor = FunctionDescriptor.of(I32, I64, PTR, I32)
    val RT_ACCELERATION_BUDGET: FunctionDescriptor = FunctionDescriptor.of(I32, PTR, I32)
    val RT_SCENE_WORK_SET: FunctionDescriptor = FunctionDescriptor.of(I32, I64, I64, I64, I64)
    val RT_SCENE_WORK_GET: FunctionDescriptor = FunctionDescriptor.of(I32, I64, PTR, I32)

    // mcdr_binding_slot: type 0 = CBV (root descriptor), 1 = SRV (texture view), 2 = SRV (texel buffer).
    @JvmField
    val BINDING_SLOT: MemoryLayout = MemoryLayout.structLayout(
        I32.withName("type"),
        I32.withName("texelFormat"),
        PTR.withName("buffer"),
        PTR.withName("view"),
        I64.withName("offset"),
        I64.withName("length")
    ).withByteAlignment(8).withName("mcdr_binding_slot")

    // mcdr_partition_draw — one core's geometry + vertex/index binding.
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

    // Named field offsets, resolved from the layouts above — never literals: a field added on the
    // C side would silently shift every later one, with no compile error.

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
    // mcdr_color_target_desc — format == -1 marks an unused attachment slot.
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
        // C: sizeof == 8 (align 4, member sum 6); these 2 bytes are also what keeps
        // PIPELINE_DESC.topology at offset 56.
        MemoryLayout.paddingLayout(2)
    ).withByteAlignment(4).withName("mcdr_depth_state_desc")

    // mcdr_input_element_desc — semanticName is caller-owned NUL-terminated UTF-8; NULL / ""
    // makes the native side fall back to "TEXCOORD".
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

    // mcdr_pipeline_desc — field order must match the C typedef byte for byte.
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

    // mcdr_mesh_pipeline_desc — asData/msData/psData hold DXIL bytecode, not HLSL source:
    // D3DCompile stops at SM 5.1. msData is required, asData/psData may be null.
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

    // mcdr_abi_field — one record of the offset/size report returned by mcdrABILayout().
    @JvmField
    val ABI_FIELD: MemoryLayout = MemoryLayout.structLayout(
        PTR.withName("name"),
        I64.withName("offset"),
        I64.withName("size")
    ).withName("mcdr_abi_field")

    // The size MSVC x64 produces for each aggregate below, asserted while this class initialises:
    // a drifted mirror fails here, before any native call and even with no DLL present.
    //
    // mcdr_dlss_input — 每帧 DLSS Evaluate 的参数包（C sizeof = 112）。字段顺序/名字必须与
    // mcdr_abi.h 的 typedef 逐字一致；_pad0/_pad1 在 C 里无名，命名只为让 describe() 能枚举到。
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

    // Streamline 的 sl::Constants 数据源：只收 viewProj / prevViewProj，其余矩阵由 native 派生。
    @JvmField
    val DLSS_CONSTANTS: MemoryLayout = MemoryLayout.structLayout(
        MemoryLayout.sequenceLayout(16L, F32).withName("viewProjection"),
        MemoryLayout.sequenceLayout(16L, F32).withName("prevViewProjection"),
        F32.withName("jitterOffsetX"),
        F32.withName("jitterOffsetY"),
        F32.withName("mvecScaleX"),
        F32.withName("mvecScaleY"),
        F32.withName("cameraPosX"), F32.withName("cameraPosY"), F32.withName("cameraPosZ"),
        F32.withName("_pad0"),
        F32.withName("cameraUpX"), F32.withName("cameraUpY"), F32.withName("cameraUpZ"),
        F32.withName("_pad1"),
        F32.withName("cameraRightX"), F32.withName("cameraRightY"), F32.withName("cameraRightZ"),
        F32.withName("_pad2"),
        F32.withName("cameraFwdX"), F32.withName("cameraFwdY"), F32.withName("cameraFwdZ"),
        F32.withName("_pad3"),
        F32.withName("cameraNear"),
        F32.withName("cameraFar"),
        F32.withName("cameraFovRadians"),
        F32.withName("cameraAspectRatio"),
        I32.withName("depthInverted"),
        I32.withName("cameraMotionIncluded"),
        I32.withName("motionVectors3D"),
        I32.withName("reset"),
        I32.withName("renderingGameFrames"),
        I32.withName("motionVectorsJittered"),
        // 投影矩阵本身（列主序、无 jitter）：cameraViewToClip 要的是这张，不是 VP。
        MemoryLayout.sequenceLayout(16L, F32).withName("projection")
    ).withName("mcdr_dlss_constants")

    // mcdr_sr_caps — 字段名/顺序必须与 mcdr_abi.h 的 typedef 逐字一致（见 docs/misc/ffm-abi.md §1.2）。
    // `note` 是 `char note[28]`：必须写成**一个有名字段**，拆成 28 个 I8 会让 describe() 的名字对不上。
    @JvmField
    val SR_CAPS: MemoryLayout = MemoryLayout.structLayout(
        I32.withName("backend"),
        I32.withName("available"),
        I32.withName("coupledDenoise"),
        I32.withName("needsExposure"),
        I32.withName("takesMasks"),
        I32.withName("externalHeap"),
        I32.withName("jitterPhaseCount"),
        I32.withName("minRenderWidth"),
        I32.withName("minRenderHeight"),
        I32.withName("maxRenderWidth"),
        I32.withName("maxRenderHeight"),
        I32.withName("nativeAA"),
        // C 里叫 reserved（必须为 0）；留着是为了将来加 flags 时不改 sizeof。
        I32.withName("reserved"),
        MemoryLayout.sequenceLayout(28L, I8).withName("note")
    ).withName("mcdr_sr_caps")

    // mcdr_rt_caps — 光追 / Work Graph 能力面（C sizeof = 80、align = 4）。
    // 14 个 int32 + char[24]；两个 queried 位区分「问了但为 0」与「没问成」，别把 0 当不支持。
    @JvmField
    val RT_CAPS: MemoryLayout = MemoryLayout.structLayout(
        I32.withName("raytracingTier"),
        I32.withName("meshShaderTier"),
        I32.withName("workGraphsTier"),
        I32.withName("shadingRateTier"),
        I32.withName("shaderModel"),
        I32.withName("samplerFeedbackTier"),
        I32.withName("options21Queried"),
        I32.withName("options22Queried"),
        I32.withName("serActuallyReorders"),
        I32.withName("enhancedBarriers"),
        I32.withName("dxcAvailable"),
        I32.withName("max1DDispatchSize"),
        I32.withName("max1DDispatchMeshSize"),
        I32.withName("reserved"),
        MemoryLayout.sequenceLayout(24L, I8).withName("note")
    ).withName("mcdr_rt_caps")

    // mcdr_rt_track_stats — 影子状态跟踪器计数（C sizeof = 92、align = 4）。
    @JvmField
    val RT_TRACK_STATS: MemoryLayout = MemoryLayout.structLayout(
        I32.withName("transitions"),
        I32.withName("mismatches"),
        I32.withName("promotions"),
        I32.withName("widened"),
        I32.withName("firstUse"),
        I32.withName("uavBarriers"),
        I32.withName("tracked"),
        MemoryLayout.sequenceLayout(64L, I8).withName("note")
    ).withName("mcdr_rt_track_stats")

    // mcdr_barrier_desc — 一次屏障提交（C sizeof = 40、align = 8，尾部 4 字节填充）。
    @JvmField
    val BARRIER_DESC: MemoryLayout = MemoryLayout.structLayout(
        PTR.withName("resource"),
        PTR.withName("resourceAfter"),
        I32.withName("kind"),
        I32.withName("flags"),
        I32.withName("before"),
        I32.withName("after"),
        I32.withName("subresource"),
        MemoryLayout.paddingLayout(4L)
    ).withName("mcdr_barrier_desc")

    @JvmField val BARRIER_DESC_SIZE: Long = BARRIER_DESC.byteSize()

    // mcdr_sr_dispatch — 每帧载荷（C sizeof = 136 / align 8）；六个 void* 落在 72..119，无隐式填充。
    @JvmField
    val SR_DISPATCH: MemoryLayout = MemoryLayout.structLayout(
        I32.withName("renderWidth"),
        I32.withName("renderHeight"),
        I32.withName("displayWidth"),
        I32.withName("displayHeight"),
        F32.withName("jitterOffsetX"),
        F32.withName("jitterOffsetY"),
        F32.withName("motionVectorScaleX"),
        F32.withName("motionVectorScaleY"),
        F32.withName("preExposure"),
        F32.withName("frameTimeDeltaMs"),
        F32.withName("cameraNear"),
        F32.withName("cameraFar"),
        F32.withName("cameraVerticalFov"),
        F32.withName("sharpness"),
        I32.withName("reset"),
        I32.withName("hdrInput"),
        I32.withName("backend"),
        I32.withName("flags"),
        PTR.withName("pColorInput"),
        PTR.withName("pColorOutput"),
        PTR.withName("pDepth"),
        PTR.withName("pMotionVectors"),
        PTR.withName("pReactiveMask"),
        PTR.withName("pExposure"),
        I32.withName("depthFormatOrdinal"),
        F32.withName("cameraAspectRatio"),
        F32.withName("viewSpaceToMetersFactor"),
        I32.withName("_pad2")
    ).withName("mcdr_sr_dispatch")

    /** `mcdr_sr_caps` 的字段偏移。 */
    @JvmField val SR_CAPS_BACKEND: Long = offset(SR_CAPS, "backend")
    @JvmField val SR_CAPS_AVAILABLE: Long = offset(SR_CAPS, "available")
    @JvmField val SR_CAPS_COUPLED_DENOISE: Long = offset(SR_CAPS, "coupledDenoise")
    @JvmField val SR_CAPS_NEEDS_EXPOSURE: Long = offset(SR_CAPS, "needsExposure")
    @JvmField val SR_CAPS_TAKES_MASKS: Long = offset(SR_CAPS, "takesMasks")
    @JvmField val SR_CAPS_EXTERNAL_HEAP: Long = offset(SR_CAPS, "externalHeap")
    @JvmField val SR_CAPS_JITTER_PHASE_COUNT: Long = offset(SR_CAPS, "jitterPhaseCount")
    @JvmField val SR_CAPS_MAX_RENDER_WIDTH: Long = offset(SR_CAPS, "maxRenderWidth")
    @JvmField val SR_CAPS_MAX_RENDER_HEIGHT: Long = offset(SR_CAPS, "maxRenderHeight")
    @JvmField val SR_CAPS_NATIVE_AA: Long = offset(SR_CAPS, "nativeAA")
    @JvmField val SR_CAPS_NOTE: Long = offset(SR_CAPS, "note")

    @JvmField val RT_CAPS_RAYTRACING_TIER: Long = offset(RT_CAPS, "raytracingTier")
    @JvmField val RT_CAPS_MESH_SHADER_TIER: Long = offset(RT_CAPS, "meshShaderTier")
    @JvmField val RT_CAPS_WORK_GRAPHS_TIER: Long = offset(RT_CAPS, "workGraphsTier")
    @JvmField val RT_CAPS_SHADING_RATE_TIER: Long = offset(RT_CAPS, "shadingRateTier")
    @JvmField val RT_CAPS_SHADER_MODEL: Long = offset(RT_CAPS, "shaderModel")
    @JvmField val RT_CAPS_SAMPLER_FEEDBACK_TIER: Long = offset(RT_CAPS, "samplerFeedbackTier")
    @JvmField val RT_CAPS_OPTIONS21_QUERIED: Long = offset(RT_CAPS, "options21Queried")
    @JvmField val RT_CAPS_OPTIONS22_QUERIED: Long = offset(RT_CAPS, "options22Queried")
    @JvmField val RT_CAPS_SER_ACTUALLY_REORDERS: Long = offset(RT_CAPS, "serActuallyReorders")
    @JvmField val RT_CAPS_ENHANCED_BARRIERS: Long = offset(RT_CAPS, "enhancedBarriers")
    @JvmField val RT_CAPS_DXC_AVAILABLE: Long = offset(RT_CAPS, "dxcAvailable")
    @JvmField val RT_CAPS_MAX_1D_DISPATCH: Long = offset(RT_CAPS, "max1DDispatchSize")
    @JvmField val RT_CAPS_MAX_1D_DISPATCH_MESH: Long = offset(RT_CAPS, "max1DDispatchMeshSize")
    @JvmField val RT_CAPS_NOTE: Long = offset(RT_CAPS, "note")

    // mcdr_fg_caps — 字段名/顺序必须与 typedef 逐字一致，verify() 双向比对 offset + size。
    // `note`（char[28]）与 `latencyProvider` 都必须是有名字段：describe() 只枚举有名字的成员。
    @JvmField
    val FG_CAPS: MemoryLayout = MemoryLayout.structLayout(
        I32.withName("backend"),
        I32.withName("available"),
        I32.withName("runtimeLoaded"),
        I32.withName("deviceSupported"),
        I32.withName("chainCompatible"),
        I32.withName("needsReflex"),
        I32.withName("needsUiResource"),
        I32.withName("needsHudless"),
        I32.withName("transitionsInputs"),
        I32.withName("maxGeneratedFrames"),
        I32.withName("chainOwner"),
        // C 里叫 reserved（必须为 0）；留着是为了将来加 flags 时不改 sizeof。
        I32.withName("reserved"),
        MemoryLayout.sequenceLayout(28L, I8).withName("note"),
        // 它占了原来那格尾填充，所以 sizeof 仍是 80 —— 换名字时别顺手加字段，会破 C 侧的断言。
        I32.withName("latencyProvider")
    ).withName("mcdr_fg_caps")

    @JvmField
    /**
     * mcdr_fg_constants（152 B / align 4）：view[16]、projection[16]、jitterX/Y、mvScaleX/Y、
     * resetHistory（int32）、frameRenderTime（float）。ABI 里它排在 `mcdr_fg_dispatch` 之前。
     */

    val FG_CONSTANTS: MemoryLayout = MemoryLayout.structLayout(
        MemoryLayout.sequenceLayout(16L, F32).withName("view"),
        MemoryLayout.sequenceLayout(16L, F32).withName("projection"),
        F32.withName("jitterX"),
        F32.withName("jitterY"),
        F32.withName("mvScaleX"),
        F32.withName("mvScaleY"),
        I32.withName("resetHistory"),
        F32.withName("frameRenderTime"),
    ).withName("mcdr_fg_constants")

    const val FG_CONSTANTS_VIEW: Long = 0L
    const val FG_CONSTANTS_PROJECTION: Long = 64L
    const val FG_CONSTANTS_JITTER_X: Long = 128L
    const val FG_CONSTANTS_JITTER_Y: Long = 132L
    const val FG_CONSTANTS_MV_SCALE_X: Long = 136L
    const val FG_CONSTANTS_MV_SCALE_Y: Long = 140L
    const val FG_CONSTANTS_RESET_HISTORY: Long = 144L
    const val FG_CONSTANTS_FRAME_RENDER_TIME: Long = 148L
    val FG_DISPATCH: MemoryLayout = MemoryLayout.structLayout(
        I32.withName("displayWidth"),
        I32.withName("displayHeight"),
        I32.withName("backend"),
        I32.withName("mode"),
        I32.withName("numFrames"),
        I32.withName("reset"),
        I64.withName("appFrameId"),
        I64.withName("backendFrameId"),
        I64.withName("presentId"),
        PTR.withName("pBackbuffer"),
        PTR.withName("pHudlessColor"),
        PTR.withName("pDepth"),
        PTR.withName("pMotionVectors"),
        PTR.withName("pUiColor"),
        MemoryLayout.sequenceLayout(2L, I32).withName("_pad2")
    ).withName("mcdr_fg_dispatch")

    /** `mcdr_fg_caps` 的字段偏移。 */
    @JvmField val FG_CAPS_BACKEND: Long = offset(FG_CAPS, "backend")
    @JvmField val FG_CAPS_AVAILABLE: Long = offset(FG_CAPS, "available")
    @JvmField val FG_CAPS_RUNTIME_LOADED: Long = offset(FG_CAPS, "runtimeLoaded")
    @JvmField val FG_CAPS_DEVICE_SUPPORTED: Long = offset(FG_CAPS, "deviceSupported")
    @JvmField val FG_CAPS_CHAIN_COMPATIBLE: Long = offset(FG_CAPS, "chainCompatible")
    @JvmField val FG_CAPS_NEEDS_REFLEX: Long = offset(FG_CAPS, "needsReflex")
    @JvmField val FG_CAPS_NEEDS_UI_RESOURCE: Long = offset(FG_CAPS, "needsUiResource")
    @JvmField val FG_CAPS_NEEDS_HUDLESS: Long = offset(FG_CAPS, "needsHudless")
    @JvmField val FG_CAPS_TRANSITIONS_INPUTS: Long = offset(FG_CAPS, "transitionsInputs")
    @JvmField val FG_CAPS_MAX_GENERATED_FRAMES: Long = offset(FG_CAPS, "maxGeneratedFrames")
    @JvmField val FG_CAPS_CHAIN_OWNER: Long = offset(FG_CAPS, "chainOwner")
    @JvmField val FG_CAPS_NOTE: Long = offset(FG_CAPS, "note")
    @JvmField val FG_CAPS_LATENCY_PROVIDER: Long = offset(FG_CAPS, "latencyProvider")

    // mcdr_fg_dispatch（C sizeof = 96 / align 8）：三个 int64 从 24 起，0..23 无隐式填充；
    // 末尾 `int32_t _pad2[2]` 是**两个** int32，漏一个 java 侧算出 92 而 C 是 96。
    // 以下为写偏移（接 Present 之后才会被真正写满）。
    @JvmField val FG_DISPATCH_DISPLAY_WIDTH: Long = offset(FG_DISPATCH, "displayWidth")
    @JvmField val FG_DISPATCH_DISPLAY_HEIGHT: Long = offset(FG_DISPATCH, "displayHeight")
    @JvmField val FG_DISPATCH_BACKEND: Long = offset(FG_DISPATCH, "backend")
    @JvmField val FG_DISPATCH_MODE: Long = offset(FG_DISPATCH, "mode")
    @JvmField val FG_DISPATCH_NUM_FRAMES: Long = offset(FG_DISPATCH, "numFrames")
    @JvmField val FG_DISPATCH_RESET: Long = offset(FG_DISPATCH, "reset")
    @JvmField val FG_DISPATCH_APP_FRAME_ID: Long = offset(FG_DISPATCH, "appFrameId")
    @JvmField val FG_DISPATCH_BACKEND_FRAME_ID: Long = offset(FG_DISPATCH, "backendFrameId")
    @JvmField val FG_DISPATCH_PRESENT_ID: Long = offset(FG_DISPATCH, "presentId")
    @JvmField val FG_DISPATCH_P_BACKBUFFER: Long = offset(FG_DISPATCH, "pBackbuffer")
    @JvmField val FG_DISPATCH_P_HUDLESS_COLOR: Long = offset(FG_DISPATCH, "pHudlessColor")
    @JvmField val FG_DISPATCH_P_DEPTH: Long = offset(FG_DISPATCH, "pDepth")
    @JvmField val FG_DISPATCH_P_MOTION_VECTORS: Long = offset(FG_DISPATCH, "pMotionVectors")
    @JvmField val FG_DISPATCH_P_UI_COLOR: Long = offset(FG_DISPATCH, "pUiColor")

    // mcdr_rt_geometry — 一条 BLAS 几何（C sizeof = 64、align = 8）。两个指针都是缓冲句柄；
    // 偏移是**字节**偏移（顶点为 StartVertexLocation * vertexStride）。
    @JvmField
    val RT_GEOMETRY: MemoryLayout = MemoryLayout.structLayout(
        PTR.withName("vertexBuffer"),
        I64.withName("vertexStride"),
        I64.withName("vertexCount"),
        I64.withName("vertexOffset"),
        PTR.withName("indexBuffer"),
        I64.withName("indexCount"),
        I64.withName("indexOffset"),
        I32.withName("flags"),
        I32.withName("indexStride")
    ).withName("mcdr_rt_geometry")

    @JvmField val RT_GEOMETRY_VERTEX_BUFFER: Long = offset(RT_GEOMETRY, "vertexBuffer")
    @JvmField val RT_GEOMETRY_VERTEX_STRIDE: Long = offset(RT_GEOMETRY, "vertexStride")
    @JvmField val RT_GEOMETRY_VERTEX_COUNT: Long = offset(RT_GEOMETRY, "vertexCount")
    @JvmField val RT_GEOMETRY_VERTEX_OFFSET: Long = offset(RT_GEOMETRY, "vertexOffset")
    @JvmField val RT_GEOMETRY_INDEX_BUFFER: Long = offset(RT_GEOMETRY, "indexBuffer")
    @JvmField val RT_GEOMETRY_INDEX_COUNT: Long = offset(RT_GEOMETRY, "indexCount")
    @JvmField val RT_GEOMETRY_INDEX_OFFSET: Long = offset(RT_GEOMETRY, "indexOffset")
    @JvmField val RT_GEOMETRY_FLAGS: Long = offset(RT_GEOMETRY, "flags")
    @JvmField val RT_GEOMETRY_INDEX_STRIDE: Long = offset(RT_GEOMETRY, "indexStride")

    // mcdr_rt_hit_group — 一个 hit group 的四个名字（C sizeof = 32、align = 8）。四条都是 UTF-8
    // 字符串指针，只在建状态对象那一次调用里被读；没有 closestHit / anyHit / intersection 时写 0。
    @JvmField
    val RT_HIT_GROUP: MemoryLayout = MemoryLayout.structLayout(
        PTR.withName("name"),
        PTR.withName("closestHit"),
        PTR.withName("anyHit"),
        PTR.withName("intersection")
    ).withName("mcdr_rt_hit_group")

    @JvmField val RT_HIT_GROUP_NAME: Long = offset(RT_HIT_GROUP, "name")
    @JvmField val RT_HIT_GROUP_CLOSEST_HIT: Long = offset(RT_HIT_GROUP, "closestHit")
    @JvmField val RT_HIT_GROUP_ANY_HIT: Long = offset(RT_HIT_GROUP, "anyHit")
    @JvmField val RT_HIT_GROUP_INTERSECTION: Long = offset(RT_HIT_GROUP, "intersection")

    @JvmField
    val RT_FRAME_PROGRAM: MemoryLayout = MemoryLayout.structLayout(
        PTR.withName("rayDxil"), I64.withName("rayBytes"),
        PTR.withName("guideDxil"), I64.withName("guideBytes"),
        PTR.withName("exposureDxil"), I64.withName("exposureBytes"),
        PTR.withName("motionDxil"), I64.withName("motionBytes"),
        PTR.withName("packDxil"), I64.withName("packBytes"),
        PTR.withName("signalDxil"), I64.withName("signalBytes"),
        PTR.withName("remodulateDxil"), I64.withName("remodulateBytes"),
        PTR.withName("compositeDxil"), I64.withName("compositeBytes"),
    ).withName("mcdr_rt_frame_program")

    @JvmField
    val RT_GEOMETRY_DESC: MemoryLayout = MemoryLayout.structLayout(
        I64.withName("worldEpoch"), I64.withName("geometryRevision"), I64.withName("materialRevision"),
        I64.withName("reuseGeometry"), I64.withName("reuseMaterials"),
        PTR.withName("records"), I64.withName("recordBytes"),
        PTR.withName("vertices"), I64.withName("vertexBytes"),
        PTR.withName("indices"), I64.withName("indexBytes"),
        PTR.withName("materials"), I64.withName("materialBytes"),
        PTR.withName("quads"), I64.withName("quadBytes"),
    ).withName("mcdr_rt_geometry_desc")

    @JvmField
    val RT_GEOMETRY_OUTPUT: MemoryLayout = MemoryLayout.structLayout(
        I64.withName("snapshotId"), PTR.withName("records"), PTR.withName("vertices"),
        PTR.withName("indices"), PTR.withName("materials"), PTR.withName("quads"), I64.withName("byteCount"),
    ).withName("mcdr_rt_geometry_output")

    @JvmField
    val RT_GEOMETRY_CONTENT: MemoryLayout = MemoryLayout.structLayout(
        I64.withName("vertexIdentity"), I64.withName("indexIdentity"), I64.withName("quadIdentity"),
        PTR.withName("vertices"), I64.withName("vertexBytes"),
        PTR.withName("indices"), I64.withName("indexBytes"),
        PTR.withName("quads"), I64.withName("quadBytes"),
    ).withName("mcdr_rt_geometry_content")

    @JvmField
    val RT_GEOMETRY_DELTA_DESC: MemoryLayout = MemoryLayout.structLayout(
        I64.withName("worldEpoch"), I64.withName("geometryRevision"), I64.withName("materialRevision"),
        I64.withName("baseSnapshot"), I64.withName("reuseMaterials"),
        PTR.withName("records"), I64.withName("recordBytes"), PTR.withName("contents"),
        I32.withName("contentCount"), I32.withName("reserved"),
        PTR.withName("materials"), I64.withName("materialBytes"),
        I64.withName("vertexCapacity"), I64.withName("indexCapacity"), I64.withName("quadCapacity"),
    ).withName("mcdr_rt_geometry_delta_desc")

    @JvmField
    val RT_GEOMETRY_DELTA_OUTPUT: MemoryLayout = MemoryLayout.structLayout(
        I64.withName("snapshotId"), PTR.withName("records"), PTR.withName("vertices"),
        PTR.withName("indices"), PTR.withName("materials"), PTR.withName("quads"), I64.withName("byteCount"),
        I64.withName("uploadedBytes"), I64.withName("uploadCapacity"),
    ).withName("mcdr_rt_geometry_delta_output")

    @JvmField
    val RT_GEOMETRY_MEMORY: MemoryLayout = MemoryLayout.structLayout(
        I64.withName("gpuBytes"), I64.withName("uploadBytes"),
    ).withName("mcdr_rt_geometry_memory")

    @JvmField
    val RT_ACCELERATION_OUTPUT: MemoryLayout = MemoryLayout.structLayout(
        I64.withName("snapshotId"), PTR.withName("acceleration"), PTR.withName("instances"), I64.withName("byteCount"),
        I32.withName("instanceCount"), I32.withName("blasCount"), I32.withName("buildCount"), I32.withName("ready"),
        I64.withName("blasMicros"), I64.withName("peakBuildMicros"), I64.withName("tlasMicros"), I64.withName("scratchBytes"),
    ).withName("mcdr_rt_acceleration_output")

    @JvmField
    val RT_SECTION_VERSION: MemoryLayout = MemoryLayout.structLayout(
        I64.withName("node"), I32.withName("layer"), I32.withName("version"),
    ).withName("mcdr_rt_section_version")

    @JvmField
    val RT_OUTPUT_SUPPORT: MemoryLayout = MemoryLayout.structLayout(
        I64.withName("node"), I32.withName("layer"), I32.withName("version"),
        I32.withName("flags"), I32.withName("reserved"),
        F32.withName("minimumX"), F32.withName("minimumY"), F32.withName("minimumZ"),
        F32.withName("maximumX"), F32.withName("maximumY"), F32.withName("maximumZ"),
    ).withName("mcdr_rt_output_support")

    @JvmField
    val RT_SCENE_DESC: MemoryLayout = MemoryLayout.structLayout(
        I64.withName("worldEpoch"), I64.withName("snapshotId"), I64.withName("liveRevision"),
        PTR.withName("sections"), I32.withName("sectionCount"), I32.withName("reserved"),
        I64.withName("frameId"),
        PTR.withName("outputs"), I32.withName("outputCount"), I32.withName("outputMode"),
    ).withName("mcdr_rt_scene_desc")

    @JvmField
    val RT_SCENE_TARGETS_CALL: FunctionDescriptor = FunctionDescriptor.of(I32, PTR, I64, PTR, PTR, I32, PTR)

    @JvmField
    val RT_SCENE_OUTPUT: MemoryLayout = MemoryLayout.structLayout(
        I64.withName("snapshotId"), I64.withName("accelerationId"),
        I64.withName("pendingSnapshotId"), I64.withName("pendingAccelerationId"),
        I64.withName("sceneRevision"), I64.withName("geometryRevision"),
        I64.withName("materialRevision"), I64.withName("byteCount"),
        RT_ACCELERATION_OUTPUT.withName("active"), RT_ACCELERATION_OUTPUT.withName("candidate"),
        I64.withName("coverageFlags"),
    ).withName("mcdr_rt_scene_output")

    @JvmField
    val RT_FRAME_DESC: MemoryLayout = MemoryLayout.structLayout(
        I64.withName("frameId"), I64.withName("worldEpoch"), I64.withName("sceneRevision"),
        I64.withName("materialRevision"), I64.withName("snapshotId"),
        I32.withName("width"), I32.withName("height"),
        I64.withName("accelerationId"),
        I64.withName("gbufferRevision"), PTR.withName("constants"),
        I32.withName("constantBytes"), F32.withName("depthScale"),
        I64.withName("frameNanos"), I32.withName("exposureMode"), F32.withName("exposureEv"),
        F32.withName("exposureSpeed"), I32.withName("resetExposure"),
        I32.withName("signalEncoding"), F32.withName("jitterDeltaX"),
        F32.withName("jitterDeltaY"), F32.withName("denoisingRange"),
        F32.withName("contentJitterX"), F32.withName("contentJitterY"),
    ).withName("mcdr_rt_frame_desc")

    @JvmField
    val RT_FRAME_OUTPUT: MemoryLayout = MemoryLayout.structLayout(
        I64.withName("frameId"), I64.withName("snapshotId"),
        PTR.withName("direct"), PTR.withName("indirect"), PTR.withName("aux"),
        PTR.withName("specular"), PTR.withName("specularGrazing"), PTR.withName("constants"),
        I64.withName("byteCount"),
        PTR.withName("exposure"),
        PTR.withName("motion"), PTR.withName("packedNormalRoughness"), PTR.withName("viewZ"),
        PTR.withName("diffuseSignal"), PTR.withName("specularSignal"), PTR.withName("specularHitDistance"),
    ).withName("mcdr_rt_frame_output")

    @JvmField
    val RT_GBUFFER_PROGRAM: MemoryLayout = MemoryLayout.structLayout(
        PTR.withName("msData"), I64.withName("msLen"),
        PTR.withName("psData"), I64.withName("psLen"),
        PTR.withName("bindings"), I32.withName("bindingCount"),
        I32.withName("cullEnabled"), I32.withName("polygonMode"), I32.withName("_pad0"),
    ).withName("mcdr_rt_gbuffer_program")

    @JvmField
    val RT_GBUFFER_BATCH: MemoryLayout = MemoryLayout.structLayout(
        I64.withName("program"), PTR.withName("vertices"), I64.withName("vertexOffset"),
        PTR.withName("sections"), I64.withName("sectionsOffset"), I64.withName("sectionsLength"),
        PTR.withName("meshlets"), I64.withName("meshletsOffset"), I64.withName("meshletsLength"),
        I32.withName("meshletCount"), I32.withName("bindingCount"), PTR.withName("bindings"),
        I32.withName("projectionBinding"), I32.withName("_pad0"),
    ).withName("mcdr_rt_gbuffer_batch")

    @JvmField
    val RT_GBUFFER_REPLAY: MemoryLayout = MemoryLayout.structLayout(
        I64.withName("revision"), PTR.withName("batches"),
        I32.withName("batchCount"), I32.withName("projectionFloatCount"), PTR.withName("projection"),
        F32.withName("cameraOffsetX"), F32.withName("cameraOffsetY"), F32.withName("cameraOffsetZ"),
        I32.withName("_pad0"),
    ).withName("mcdr_rt_gbuffer_replay")

    @JvmField
    val RT_GBUFFER_OUTPUT: MemoryLayout = MemoryLayout.structLayout(
        I64.withName("revision"), I64.withName("resourceRevision"), I64.withName("frameNanos"),
        I32.withName("width"), I32.withName("height"),
        PTR.withName("albedo"), PTR.withName("specular"), PTR.withName("normalRoughness"),
        PTR.withName("emissive"), PTR.withName("linearDepth"), PTR.withName("depth"),
        I64.withName("byteCount"),
    ).withName("mcdr_rt_gbuffer_output")

    @JvmField
    val RT_DENOISE_DESC: MemoryLayout = MemoryLayout.structLayout(
        I64.withName("frameId"), I64.withName("historyGeneration"),
        I64.withName("historyFrame"), I64.withName("planRevision"),
        I32.withName("outputWidth"), I32.withName("outputHeight"),
        PTR.withName("nrdMatrices"), I32.withName("nrdFloatCount"), I32.withName("slFloatCount"),
        PTR.withName("slConstants"),
    ).withName("mcdr_rt_denoise_desc")

    @JvmField
    val RT_DENOISE_OUTPUT: MemoryLayout = MemoryLayout.structLayout(
        I64.withName("frameId"), I64.withName("snapshotId"),
        PTR.withName("diffuse"), PTR.withName("specular"), I64.withName("byteCount"),
        I32.withName("encoding"), I32.withName("dispatchCount"),
    ).withName("mcdr_rt_denoise_output")

    @JvmField
    val RT_TRANSACTION_DESC: MemoryLayout = MemoryLayout.structLayout(
        RT_FRAME_DESC.withName("trace"), RT_DENOISE_DESC.withName("denoise"),
        I64.withName("liveRevision"), PTR.withName("spatial"), I64.withName("spatialBytes"),
        PTR.withName("source"), I32.withName("upscaler"), I32.withName("debugMode"),
        PTR.withName("timestamps"), I32.withName("firstTimestamp"), I32.withName("reserved"),
    ).withName("mcdr_rt_transaction_desc")

    @JvmField
    val RT_TRANSACTION_OUTPUT: MemoryLayout = MemoryLayout.structLayout(
        RT_FRAME_OUTPUT.withName("trace"), RT_DENOISE_OUTPUT.withName("denoise"),
        RT_DENOISE_OUTPUT.withName("upscale"), RT_DENOISE_OUTPUT.withName("composite"),
        I32.withName("fallbackFlags"), I32.withName("recorded"),
        I32.withName("timestampMask"), I32.withName("reserved"),
    ).withName("mcdr_rt_transaction_output")

    private val EXPECTED_C_SIZES: Array<Array<Any>> = arrayOf(
        arrayOf<Any>("mcdr_rt_transaction_desc", RT_TRANSACTION_DESC, 248L),
        arrayOf<Any>("mcdr_rt_transaction_output", RT_TRANSACTION_OUTPUT, 288L),
        arrayOf<Any>("mcdr_binding_slot",         BINDING_SLOT,        40L),
        arrayOf<Any>("mcdr_partition_draw",       PARTITION_DRAW,      56L),
        arrayOf<Any>("mcdr_color_target_desc",    COLOR_TARGET,        12L),
        arrayOf<Any>("mcdr_depth_state_desc",     DEPTH_STATE,          8L),
        arrayOf<Any>("mcdr_input_element_desc",   INPUT_ELEMENT,       40L),
        arrayOf<Any>("mcdr_pipeline_binding_desc", PIPELINE_BINDING,    2L),
        arrayOf<Any>("mcdr_pipeline_desc",        PIPELINE_DESC,       96L),
        arrayOf<Any>("mcdr_mesh_pipeline_desc",   MESH_PIPELINE_DESC,  96L),
        arrayOf<Any>("mcdr_dlss_input",          DLSS_INPUT,        112L),
        arrayOf<Any>("mcdr_dlss_constants",      DLSS_CONSTANTS,    312L),
        // SR 抽象层：数字是 MSVC x64 实际 sizeof（改结构时两边一起改）。
        arrayOf<Any>("mcdr_sr_caps",             SR_CAPS,            80L),
        arrayOf<Any>("mcdr_sr_dispatch",         SR_DISPATCH,       136L),
        // FG 抽象层：数字是 MSVC x64 实际 sizeof（改结构时两边一起改）。
        arrayOf<Any>("mcdr_fg_caps",             FG_CAPS,            80L),
        arrayOf<Any>("mcdr_fg_dispatch",         FG_DISPATCH,        96L),
        arrayOf<Any>("mcdr_fg_constants",       FG_CONSTANTS,      152L),
        arrayOf<Any>("mcdr_abi_field",            ABI_FIELD,           24L),
        arrayOf<Any>("mcdr_rt_caps",              RT_CAPS,             80L),
        arrayOf<Any>("mcdr_rt_track_stats",       RT_TRACK_STATS,      92L),
        arrayOf<Any>("mcdr_rt_hit_group",         RT_HIT_GROUP,        32L),
        arrayOf<Any>("mcdr_rt_frame_program",     RT_FRAME_PROGRAM,   128L),
        arrayOf<Any>("mcdr_rt_geometry_desc",     RT_GEOMETRY_DESC,   120L),
        arrayOf<Any>("mcdr_rt_geometry_output",   RT_GEOMETRY_OUTPUT, 56L),
        arrayOf<Any>("mcdr_rt_geometry_content", RT_GEOMETRY_CONTENT, 72L),
        arrayOf<Any>("mcdr_rt_geometry_delta_desc", RT_GEOMETRY_DELTA_DESC, 112L),
        arrayOf<Any>("mcdr_rt_geometry_delta_output", RT_GEOMETRY_DELTA_OUTPUT, 72L),
        arrayOf<Any>("mcdr_rt_geometry_memory", RT_GEOMETRY_MEMORY, 16L),
        arrayOf<Any>("mcdr_rt_acceleration_output", RT_ACCELERATION_OUTPUT, 80L),
        arrayOf<Any>("mcdr_rt_section_version",    RT_SECTION_VERSION, 16L),
        arrayOf<Any>("mcdr_rt_output_support",     RT_OUTPUT_SUPPORT, 48L),
        arrayOf<Any>("mcdr_rt_scene_desc",         RT_SCENE_DESC, 64L),
        arrayOf<Any>("mcdr_rt_scene_output",       RT_SCENE_OUTPUT, 232L),
        arrayOf<Any>("mcdr_rt_frame_desc",        RT_FRAME_DESC,      128L),
        arrayOf<Any>("mcdr_rt_frame_output",      RT_FRAME_OUTPUT,    128L),
        arrayOf<Any>("mcdr_rt_gbuffer_output",    RT_GBUFFER_OUTPUT,   88L),
        arrayOf<Any>("mcdr_rt_gbuffer_program",   RT_GBUFFER_PROGRAM,  56L),
        arrayOf<Any>("mcdr_rt_gbuffer_batch",     RT_GBUFFER_BATCH,    96L),
        arrayOf<Any>("mcdr_rt_gbuffer_replay",    RT_GBUFFER_REPLAY,   48L),
        arrayOf<Any>("mcdr_rt_denoise_desc",      RT_DENOISE_DESC,     64L),
        arrayOf<Any>("mcdr_rt_denoise_output",    RT_DENOISE_OUTPUT,   48L),
        arrayOf<Any>("mcdr_rt_geometry",          RT_GEOMETRY,         64L),
        arrayOf<Any>("mcdr_barrier_desc",         BARRIER_DESC,        40L),
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
        // describe() and verify() both enumerate this map, so a registered struct is always checked.
        STRUCTS["mcdr_binding_slot"] = BINDING_SLOT
        STRUCTS["mcdr_rt_caps"] = RT_CAPS
        STRUCTS["mcdr_rt_track_stats"] = RT_TRACK_STATS
        STRUCTS["mcdr_rt_hit_group"] = RT_HIT_GROUP
        STRUCTS["mcdr_rt_frame_program"] = RT_FRAME_PROGRAM
        STRUCTS["mcdr_rt_frame_desc"] = RT_FRAME_DESC
        STRUCTS["mcdr_rt_geometry_desc"] = RT_GEOMETRY_DESC
        STRUCTS["mcdr_rt_geometry_output"] = RT_GEOMETRY_OUTPUT
        STRUCTS["mcdr_rt_geometry_content"] = RT_GEOMETRY_CONTENT
        STRUCTS["mcdr_rt_geometry_delta_desc"] = RT_GEOMETRY_DELTA_DESC
        STRUCTS["mcdr_rt_geometry_delta_output"] = RT_GEOMETRY_DELTA_OUTPUT
        STRUCTS["mcdr_rt_geometry_memory"] = RT_GEOMETRY_MEMORY
        STRUCTS["mcdr_rt_acceleration_output"] = RT_ACCELERATION_OUTPUT
        STRUCTS["mcdr_rt_section_version"] = RT_SECTION_VERSION
        STRUCTS["mcdr_rt_output_support"] = RT_OUTPUT_SUPPORT
        STRUCTS["mcdr_rt_scene_desc"] = RT_SCENE_DESC
        STRUCTS["mcdr_rt_scene_output"] = RT_SCENE_OUTPUT
        STRUCTS["mcdr_rt_frame_output"] = RT_FRAME_OUTPUT
        STRUCTS["mcdr_rt_gbuffer_output"] = RT_GBUFFER_OUTPUT
        STRUCTS["mcdr_rt_gbuffer_program"] = RT_GBUFFER_PROGRAM
        STRUCTS["mcdr_rt_gbuffer_batch"] = RT_GBUFFER_BATCH
        STRUCTS["mcdr_rt_gbuffer_replay"] = RT_GBUFFER_REPLAY
        STRUCTS["mcdr_rt_denoise_desc"] = RT_DENOISE_DESC
        STRUCTS["mcdr_rt_denoise_output"] = RT_DENOISE_OUTPUT
        STRUCTS["mcdr_rt_transaction_desc"] = RT_TRANSACTION_DESC
        STRUCTS["mcdr_rt_transaction_output"] = RT_TRANSACTION_OUTPUT
        STRUCTS["mcdr_rt_geometry"] = RT_GEOMETRY
        STRUCTS["mcdr_barrier_desc"] = BARRIER_DESC
        STRUCTS["mcdr_partition_draw"] = PARTITION_DRAW
        STRUCTS["mcdr_color_target_desc"] = COLOR_TARGET
        STRUCTS["mcdr_depth_state_desc"] = DEPTH_STATE
        STRUCTS["mcdr_input_element_desc"] = INPUT_ELEMENT
        STRUCTS["mcdr_pipeline_binding_desc"] = PIPELINE_BINDING
        STRUCTS["mcdr_pipeline_desc"] = PIPELINE_DESC
        STRUCTS["mcdr_mesh_pipeline_desc"] = MESH_PIPELINE_DESC
        STRUCTS["mcdr_dlss_input"] = DLSS_INPUT
        // SR 的两张表必须登记：native 的 layoutTable() 有它们的字段，漏登记会让 verify() 报
        // "reported by native but absent from the Java mirror" 并 abort。
        STRUCTS["mcdr_sr_caps"] = SR_CAPS
        STRUCTS["mcdr_sr_dispatch"] = SR_DISPATCH
        // FG 的两张表同理必须登记（native 的 layoutTable() 有它们的字段）。
        STRUCTS["mcdr_fg_caps"] = FG_CAPS
        STRUCTS["mcdr_fg_dispatch"] = FG_DISPATCH
        STRUCTS["mcdr_fg_constants"] = FG_CONSTANTS

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
     * Compare these mirrors against the offsets/sizes the running DLL reports; throws with every
     * disagreement listed, not just the first.
     *
     * @param nativeFields what `mcdrABILayout()` published, as `name -> {offset, size}`
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
                    " field(s)); the Kotlin mirror in io.github.dxegp.d3d12.extern.MCDRLayout" +
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
