package dev.nts.expdxr.d3d12.render.advanced.taa

import com.mojang.renderpearl.api.GpuFormat
import com.mojang.renderpearl.api.buffers.GpuBuffer
import com.mojang.renderpearl.api.textures.GpuTexture
import dev.nts.expdxr.misc.settings.D3D12Settings
import dev.nts.expdxr.d3d12.extern.MCDR_ABI
import dev.nts.expdxr.d3d12.extern.MCDRPipelineDesc
import dev.nts.expdxr.d3d12.render.adapter.D3D12Device
import dev.nts.expdxr.d3d12.render.core.D3D12GpuSurface
import dev.nts.expdxr.d3d12.render.core.D3D12GpuBuffer
import dev.nts.expdxr.d3d12.render.core.D3D12GpuTexture
import dev.nts.expdxr.d3d12.render.core.D3D12GpuTextureView
import java.nio.ByteOrder
import java.nio.FloatBuffer
import net.minecraft.client.Minecraft
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Full-screen velocity pass: camera reprojection plus per-object correction.
 *
 * Raw ABI pipeline (`MCDRPipelineDesc` takes HLSL directly; SM 6.9 / DXIL required, no SM 5.1
 * fallback). Bindings: entry 0 = CBV `register(b0)`, 256 B; entry 1 = depth SRV + static sampler
 * `register(t1)` / `register(s1)` (native remaps the depth format to `R32_FLOAT` or
 * `R32_FLOAT_X8X24_TYPELESS`); entry 2 = object rows as an `R32_FLOAT` texel buffer `register(t2)`,
 * no sampler.
 *
 * Output: `RG16_FLOAT` ping-pong, xy = screen-space motion in **pixels**, direction "this frame ->
 * previous frame" (DLSS convention; inverting it drags a ghost that never converges). The "previous"
 * half is not consumed yet — it is the history target of the TAA resolve.
 *
 * Per pixel: depth -> world with the *jittered* inverted current view-projection, project with the
 * previous frame's, motion = `(prevUv - uv) * ScreenSize`, then add the jitter and camera-translation
 * terms below. See docs/antialiasing/README.md §3.3.
 */
    /** [abandonedSteps] bit: the velocity textures or the parameter block failed to allocate/upload. */
private const val STEP_RESOURCES: Int = 1

    /** [abandonedSteps] bit: the main-depth SRV view failed to create. */
private const val STEP_DEPTH_VIEW: Int = 2

    /** [abandonedSteps] bit: the velocity pipeline failed to create, bind or draw. */
private const val STEP_PIPELINE: Int = 4

object D3D12Velocity {
    private val LOGGER: Logger = LoggerFactory.getLogger("dxrmc-velocity")

    /** Two half floats of pixel motion (native ordinal 41). */
    val FORMAT: GpuFormat = GpuFormat.RG16_FLOAT

    // RTV (written here) + SRV (read by the TAA resolve) + COPY_SRC (readback diagnostics).
    // Must stay a single expression: K2 rejects a continuation line that starts with an operator.
    private val USAGE: Int = GpuTexture.USAGE_RENDER_ATTACHMENT or GpuTexture.USAGE_TEXTURE_BINDING or GpuTexture.USAGE_COPY_SRC

    /** MC `PrimitiveTopology.TRIANGLES` ordinal (native `toPrimitiveTopology` maps 4 -> TRIANGLELIST). */
    private const val TOPOLOGY_TRIANGLES: Int = 4

    /** Write mask: both RG channels. */
    private const val WRITE_MASK_RG: Int = 0x3

    /** 256 B: D3D12 wants constant-buffer sizes 256-aligned even though only 172 B are used. */
    private const val CBV_SIZE: Long = 256L

    /** Object rows as a texel `Buffer<float>` (native `pushDescriptors` accepts texel buffers only here), 16 floats per row. */
    private val OBJECT_FORMAT: GpuFormat = GpuFormat.R32_FLOAT

    /** Object buffer size: [D3D12ObjectMotion.MAX_OBJECTS] rows of [D3D12ObjectMotion.ROW_FLOATS] floats. */
    private const val OBJECT_BYTES: Long = D3D12ObjectMotion.MAX_OBJECTS.toLong() * D3D12ObjectMotion.ROW_FLOATS * 4L

    /** AABB margin in blocks: swinging limbs briefly leave the box, and without it they drop out. */
    private const val OBJECT_BOX_MARGIN: Float = 0.35f

    /** Per-object motion cap in pixels; a larger delta means the row matched the wrong object (fall back). */
    private const val OBJECT_MAX_MOTION_PIXELS: Float = 128.0f

    /** Camera-translation fallback when there is no previous camera yet. */
    private val CAMERA_DELTA_NONE: FloatArray = floatArrayOf(0.0f, 0.0f, 0.0f)

    /** [recordedFrame] sentinel: nothing recorded yet. */
    private const val FRAME_NONE: Long = -1L

    /** Master switch, seeded from `[debug] temporalChain`; without a resolve it only writes another texture. */
    @Volatile
    var enabled: Boolean = D3D12Settings.temporalChain

    /** Ping-pong textures: `current` is written this frame, `previous` is the last frame's result. */
    private var current: D3D12GpuTexture? = null
    private var previous: D3D12GpuTexture? = null
    private var width: Int = 0
    private var height: Int = 0

    /**
     * SRV views of the two textures. They must swap **together** with [current]/[previous] in
     * [beginFrame], or the resolve samples the wrong frame and the whole screen smears.
     */
    private var currentView: D3D12GpuTextureView? = null
    private var previousView: D3D12GpuTextureView? = null

    private var pipeline: Long = 0L

    /** Pipeline creation error (recorded once: no per-frame retry, no log spam). */
    private var pipelineError: String? = null

    /** Parameter block (own buffer; the label shows up in native diagnostics). */
    private var uniform: D3D12GpuBuffer? = null

    /** Object row buffer (UPLOAD heap, bound as `t2` every frame). */
    private var objects: D3D12GpuBuffer? = null

    /** Reused array so the per-frame row write allocates nothing. */
    private val objectScratch: FloatArray = FloatArray(D3D12ObjectMotion.MAX_OBJECTS * D3D12ObjectMotion.ROW_FLOATS)

    /** Rows actually bound this frame (0 = no per-object correction). */
    private var objectCount: Int = 0

    /** Depth SRV view + the texture it belongs to (recreated when the main target is rebuilt). */
    private var depthView: D3D12GpuTextureView? = null
    private var depthViewFor: Long = 0L

    private var frameIndex: Long = 0L

    /** Last recorded frame. DLSS records early, so the later call from `blitFromTexture` must short-circuit. */
    private var recordedFrame: Long = FRAME_NONE

    /** Reason of the last failure (used when closing itself). */
    private var disabledReason: String? = null

    /**
     * Build steps already given up on: [STEP_RESOURCES], [STEP_DEPTH_VIEW], [STEP_PIPELINE].
     * [forceEnable] clears these bits and [disabledReason] so the next [record] retries — without that
     * one failed allocation would cost the motion vectors forever.
     */
    private var abandonedSteps: Int = 0

    /** Whether recording is allowed; the temporal master switch must be on as well (TAA consumes vectors too). */
    fun isActive(): Boolean = (enabled || D3D12TAA.isSelected()) && D3D12Temporal.isActive()

    /** Latched off: [disable] ran, or a build step was abandoned (DLSS uses this to decide on a retry). */
    @Synchronized
    fun isLatched(): Boolean = disabledReason != null || abandonedSteps != 0

    /** Clears the self-disable latch (leaves [enabled] to the caller) and [reset]s so DLSS can retry; true = a latch was cleared. */
    @Synchronized
    fun forceEnable(): Boolean {
        val hadLatch = disabledReason != null || abandonedSteps != 0
        reset()
        abandonedSteps = 0
        pipelineError = null
        pipeline = 0L
        return hadLatch
    }

    /** This frame's velocity texture handle (0 = not built). */
    @Synchronized
    fun currentHandle(): Long = this.current?.handle() ?: 0L

    /** Diagnostic one-liner for "why is there no motion texture" (the caller rate-limits it). */
    @Synchronized
    fun debugState(): String {
        return "frame=$frameIndex recorded=$recordedFrame current=${current != null}" +
            " previous=${previous != null} abandoned=$abandonedSteps latch=$disabledReason" +
            " active=${isActive()} enabled=$enabled temporal=${D3D12Temporal.isActive()}"
    }

    /** This frame's motion SRV for the TAA resolve; null = no data yet. */
    @Synchronized
    fun currentMotionView(): D3D12GpuTextureView? = if (this.current == null) null else this.currentView

    /** Previous frame's velocity texture handle (0 = no history). */
    @Synchronized
    fun previousHandle(): Long = this.previous?.handle() ?: 0L

    /** Previous frame's motion SRV (the VRS motion test); null = no history, the caller skips this frame. */
    @Synchronized
    fun previousMotionView(): D3D12GpuTextureView? = if (this.previous == null) null else this.previousView

    /** Releases everything (device shutdown / world exit / self-check). */
    @Synchronized
    fun reset() {
        frameIndex = 0L
        recordedFrame = FRAME_NONE
        disabledReason = null
        releaseResources()
    }

    /** Per-frame ping-pong: the texture written this frame becomes "previous". */
    @Synchronized
    fun beginFrame() {
        if (!isActive()) {
            return
        }
        val swap = this.previous
        this.previous = this.current
        this.current = swap
        val swapView = this.previousView
        this.previousView = this.currentView
        this.currentView = swapView
        frameIndex++
    }

    /** Records the pass right before the blit, while the frame is fully drawn and the depth valid; true = really recorded. */
    @Synchronized
    fun record(ctx: Long, device: D3D12Device?): Boolean {
        if (!isActive()) {
            return false
        }
        // While latched: short-circuit. A probe leaves no residue, since a successful step stops creating.
        if (abandonedSteps != 0) {
            return false
        }
        // Already recorded this frame (DLSS' hook runs before the blit): the earlier data is the valid one.
        if (this.recordedFrame == this.frameIndex) {
            return this.current != null
        }
        val minecraft = Minecraft.getInstance()
        // The main target lives on `GameRenderer`, not on `Minecraft`.
        val target = minecraft.gameRenderer.mainRenderTarget()
        val depth = target.depthTexture as? D3D12GpuTexture ?: return false
        if (depth.isClosed()) {
            return false
        }
        val w = depth.getWidth(0)
        val h = depth.getHeight(0)
        if (w <= 0 || h <= 0) {
            return false
        }
        // Camera view matrices come from the terrain `ChunkSection` records (see [D3D12Temporal]).
        val view = D3D12Temporal.currentCameraView()
        val previousView = D3D12Temporal.previousCameraView()
        val curProjection = D3D12Temporal.currentProjection()
        val prevProjection = D3D12Temporal.previousProjection()
        if (view == null || previousView == null || curProjection == null || prevProjection == null) {
            // First frame (no history) or nothing was drawn: skip, the texture keeps its old content.
            return false
        }
        if (device == null) {
            return false
        }
        if (!ensurePipeline(device)) {
            return false
        }
        if (!ensureResources(device, w, h)) {
            return false
        }
        val uniformBuffer = this.uniform ?: return false
        val color = this.current ?: return false
        if (!ensureDepthView(depth)) {
            return false
        }
        val depthSrv = this.depthView ?: return false

        // Use the same jittered matrix the depth was rendered with, else half a pixel of systematic error.
        val jittered = D3D12Temporal.jitterProjection(curProjection,
            D3D12Temporal.clipJitterX(w), D3D12Temporal.clipJitterY(h))
        val currViewProj = D3D12Temporal.multiply(jittered, view)
        val prevViewProj = D3D12Temporal.multiply(prevProjection, previousView)
        val invCurr = invert(currViewProj) ?: return false
        // Object rows: the pass answers "where was this pixel last frame", and the snapshot taken by this
        // frame's extract is exactly that "previous frame" (which is why the roll happens in writeWorldRows).
        val rows = uploadObjects()
        // History holds the *jittered* previous image, so the motion needs -currentJitter + previousJitter;
        // dropping either term leaves half a pixel behind, most visible on distant grazing ground.
        val jitter = D3D12Temporal.jitter()
        val prevJitter = D3D12Temporal.previousJitter()
        // Camera-translation term: the reprojected position is relative to this frame's camera while
        // PrevViewProj carries rotation only, so without it all parallax from moving is lost.
        val camDelta = D3D12Temporal.cameraDelta() ?: CAMERA_DELTA_NONE
        if (!writeParameters(uniformBuffer, prevViewProj, invCurr, w, h, rows,
                jitter[0].toFloat(), jitter[1].toFloat(),
                prevJitter[0].toFloat(), prevJitter[1].toFloat(),
                camDelta[0], camDelta[1], camDelta[2])) {
            return false
        }

        MCDR_ABI.D3D12BeginRenderPass(ctx, longArrayOf(color.handle()), intArrayOf(0),
            byteArrayOf(1), floatArrayOf(0.0f, 0.0f, 0.0f, 0.0f),
            0L, 0, 0.toByte(), 0.0, 0, 0, w, h)
        // Viewport / scissor are command-list state and must be set for every pass.
        MCDR_ABI.D3D12SetViewport(ctx, 0, 0, w, h)
        MCDR_ABI.D3D12SetScissor(ctx, 0, 0, w, h)
        if (!MCDR_ABI.D3D12SetPipeline(ctx, this.pipeline, false)) {
            disable("dx12SetPipeline failed: ${MCDR_ABI.D3D12LastError()}", STEP_PIPELINE)
            MCDR_ABI.D3D12EndRenderPass(ctx)
            return false
        }
        // `t2` = object rows; the array order matches register(t1)/register(t2) through the binding table.
        val objectBuffer = this.objects
        val pushed = MCDR_ABI.D3D12PushDescriptors(ctx,
            intArrayOf(0, 1, 2),
            longArrayOf(uniformBuffer.handle(), 0L, if (objectBuffer == null) 0L else objectBuffer.handle()),
            longArrayOf(0L, 0L, 0L),
            longArrayOf(CBV_SIZE, 0L, OBJECT_BYTES),
            intArrayOf(0, 0, OBJECT_FORMAT.ordinal),
            longArrayOf(0L, depthSrv.handle(), 0L))
        if (!pushed) {
            disable("dx12PushDescriptors failed: ${MCDR_ABI.D3D12LastError()}", STEP_PIPELINE)
            MCDR_ABI.D3D12EndRenderPass(ctx)
            return false
        }
        if (!MCDR_ABI.D3D12Draw(ctx, 3, 1, 0, 0)) {
            disable("dx12Draw failed: ${MCDR_ABI.D3D12LastError()}", STEP_PIPELINE)
        }
        MCDR_ABI.D3D12EndRenderPass(ctx)
        // Remember the recording so the second call in the same frame short-circuits (see [recordedFrame]).
        recordedFrame = frameIndex
        return true
    }

    /** Allocates the ping-pong textures plus their views, the parameter block and the object rows. */
    private fun ensureResources(device: D3D12Device, w: Int, h: Int): Boolean {
        if (this.current != null && this.previous != null && this.width == w && this.height == h) {
            return true
        }
        releaseResources()
        return try {
            this.current = D3D12GpuTexture(USAGE, "DXRMC velocity", FORMAT, w, h, 1, 1)
            this.previous = D3D12GpuTexture(USAGE, "DXRMC velocity (previous)", FORMAT, w, h, 1, 1)
            this.currentView = D3D12GpuTextureView(this.current!!, 0, 1)
            this.previousView = D3D12GpuTextureView(this.previous!!, 0, 1)
            // Declared as returning `GpuBuffer`; cast back to the implementation for the native handle.
            this.uniform = device.createBuffer({ "DXRMC velocity params" },
                GpuBuffer.USAGE_UNIFORM or GpuBuffer.USAGE_MAP_WRITE, CBV_SIZE) as D3D12GpuBuffer
            // `USAGE_UNIFORM_TEXEL_BUFFER` (value 256) marks it as a texel buffer; with MAP_WRITE it lands in the UPLOAD heap.
            this.objects = device.createBuffer({ "DXRMC velocity objects" },
                GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER or GpuBuffer.USAGE_MAP_WRITE,
                OBJECT_BYTES) as D3D12GpuBuffer
            this.width = w
            this.height = h
            // Success: clear the "given up" bit, or it would short-circuit every later frame.
            abandonedSteps = abandonedSteps and STEP_RESOURCES.inv()
            LOGGER.info("velocity: allocated {}x{} {} (ping-pong)", w, h, FORMAT)
            true
        } catch (t: Throwable) {
            releaseResources()
            disable("resource allocation failed: ${t}", STEP_RESOURCES)
            false
        }
    }

    /** Depth SRV (built once per texture; rebuilt automatically after the main target is recreated). */
    private fun ensureDepthView(depth: D3D12GpuTexture): Boolean {
        val handle = depth.handle()
        if (this.depthView != null && this.depthViewFor == handle) {
            return true
        }
        this.depthView?.close()
        this.depthView = null
        this.depthViewFor = 0L
        return try {
            this.depthView = D3D12GpuTextureView(depth, 0, 1)
            this.depthViewFor = handle
            abandonedSteps = abandonedSteps and STEP_DEPTH_VIEW.inv()
            // SLF4J only understands `{}`; `{:#x}` degrades into a placeholder-count warning.
            LOGGER.info("velocity: depth SRV view created for texture {} (fmt={})",
                handle.toULong().toString(16), depth.format)
            true
        } catch (t: Throwable) {
            disable("depth view creation failed: ${t}", STEP_DEPTH_VIEW)
            false
        }
    }

    private fun ensurePipeline(device: D3D12Device): Boolean {
        if (this.pipeline != 0L) {
            return true
        }
        if (this.pipelineError != null) {
            return false
        }
        val desc = MCDRPipelineDesc()
            .shaders(VERTEX_HLSL, PIXEL_HLSL)
            .topology(TOPOLOGY_TRIANGLES, false, 0)
            .addColorTarget(MCDRPipelineDesc.ColorTarget()
                .format(FORMAT.ordinal)
                .writeMask(WRITE_MASK_RG))
            .addBinding(0, 0)   // CBV -> register(b0)
            .addBinding(1, 1)   // SRV + static sampler -> register(t1)/(s1)
            .addBinding(2, 2)   // object rows -> register(t2) (texel buffer, no sampler)
        val handle = MCDR_ABI.D3D12CreateGraphicsPipeline(desc)
        if (handle == 0L) {
            this.pipelineError = MCDR_ABI.D3D12LastError()
            disable("dx12CreateGraphicsPipeline failed: ${this.pipelineError}", STEP_PIPELINE)
            return false
        }
        this.pipeline = handle
        abandonedSteps = abandonedSteps and STEP_PIPELINE.inv()
        LOGGER.info("velocity: pipeline compiled (handle={})", handle.toULong().toString(16))
        return true
    }

    /**
     * Uploads this frame's object rows, returns the count (0 = no per-object correction). A failure only
     * degrades this frame, so it never calls [disable].
     */
    private fun uploadObjects(): Int {
        val buffer = this.objects
        if (buffer == null) {
            this.objectCount = 0
            return 0
        }
        // Rows are world-space: subtract *this* frame's camera (another frame's would bake camera motion in).
        val camera = D3D12Temporal.currentCameraPosition()
        if (camera == null) {
            this.objectCount = 0
            return 0
        }
        val rows = try {
            D3D12ObjectMotion.writeWorldRows(this.objectScratch, camera[0], camera[1], camera[2])
        } catch (t: Throwable) {
            LOGGER.warn("velocity: object row read failed — {}", t.toString())
            0
        }
        this.objectCount = rows
        if (rows <= 0) {
            return 0
        }
        return try {
            buffer.map(0L, OBJECT_BYTES, false, true).use { view ->
                val fb: FloatBuffer = view.data().order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                // Only the real rows are written; HLSL iterates `ObjectParams.x - 1` rows anyway.
                fb.put(this.objectScratch, 0, rows * D3D12ObjectMotion.ROW_FLOATS)
            }
            rows
        } catch (t: Throwable) {
            LOGGER.warn("velocity: object row upload failed — {}", t.toString())
            this.objectCount = 0
            0
        }
    }

    /**
     * Writes the parameter block: floats 0..15 `PrevViewProj`, 16..31 `InvCurrViewProj`, 32 `ScreenSize`,
     * 34 `ObjectParams` (x = rows + 1, y = motion cap in pixels), 36 `JitterPixels`, 38 `PrevJitterPixels`,
     * 40..42 `CameraDelta`; 172 B used, padded to 256. Mapped write, not `writeToBuffer` (it detours
     * through [D3D12Temporal.interceptWrite]).
     */
    private fun writeParameters(buffer: D3D12GpuBuffer, prevViewProj: FloatArray,
        invCurrViewProj: FloatArray, w: Int, h: Int, rows: Int,
        jitterX: Float, jitterY: Float, prevJitterX: Float, prevJitterY: Float,
        camDeltaX: Float, camDeltaY: Float, camDeltaZ: Float): Boolean {
        return try {
            buffer.map(0L, CBV_SIZE, false, true).use { view ->
                val fb: FloatBuffer = view.data().order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                fb.put(prevViewProj, 0, 16)
                fb.put(invCurrViewProj, 0, 16)
                fb.put(w.toFloat())
                fb.put(h.toFloat())
                fb.put((rows + 1).toFloat())
                fb.put(OBJECT_MAX_MOTION_PIXELS)
                fb.put(jitterX)
                fb.put(jitterY)
                fb.put(prevJitterX)
                fb.put(prevJitterY)
                fb.put(camDeltaX)
                fb.put(camDeltaY)
                fb.put(camDeltaZ)
                // Pad to 256 B = 64 floats.
                while (fb.position() < 64) {
                    fb.put(0.0f)
                }
            }
            true
        } catch (t: Throwable) {
            disable("parameter upload failed: ${t}", STEP_RESOURCES)
            false
        }
    }

    /**
     * Column-major 4x4 inverse (double precision, Gauss-Jordan); null when singular or non-finite.
     *
     * Do not swap in JOML: `set()` reads row-major while `get()` writes column-major, so set-then-invert
     * silently yields the transpose and the reprojection turns to garbage.
     */
    private fun invert(matrix: FloatArray): FloatArray? {
        if (matrix.size < 16) {
            return null
        }
        // Augmented matrix: A (as rows) on the left, identity on the right.
        val augmented = Array(4) { r ->
            DoubleArray(8) { c ->
                when {
                    c < 4 -> matrix[c * 4 + r].toDouble()
                    c - 4 == r -> 1.0
                    else -> 0.0
                }
            }
        }
        for (col in 0 until 4) {
            var pivot = col
            var best = kotlin.math.abs(augmented[col][col])
            for (r in col + 1 until 4) {
                val candidate = kotlin.math.abs(augmented[r][col])
                if (candidate > best) {
                    best = candidate
                    pivot = r
                }
            }
            if (!best.isFinite() || best < 1e-20) {
                return null
            }
            if (pivot != col) {
                val swap = augmented[col]
                augmented[col] = augmented[pivot]
                augmented[pivot] = swap
            }
            val diagonal = augmented[col][col]
            for (c in 0 until 8) {
                augmented[col][c] /= diagonal
            }
            for (r in 0 until 4) {
                if (r == col) {
                    continue
                }
                val factor = augmented[r][col]
                if (factor == 0.0) {
                    continue
                }
                for (c in 0 until 8) {
                    augmented[r][c] -= factor * augmented[col][c]
                }
            }
        }
        val out = FloatArray(16)
        for (r in 0 until 4) {
            for (c in 0 until 4) {
                out[c * 4 + r] = augmented[r][c + 4].toFloat()
            }
        }
        return out
    }

    /** Closes for good on error, with a reason. */
    private fun disable(reason: String) {
        disable(reason, 0)
    }

    /**
     * Closes for good, remembers the reason and which build step must not be retried.
     * @param abandonStep 0 = this frame only; anything else is an [abandonedSteps] bit, cleared by [forceEnable].
     */
    private fun disable(reason: String, abandonStep: Int) {
        if (abandonStep != 0) {
            abandonedSteps = abandonedSteps or abandonStep
        }
        if (this.disabledReason == null) {
            this.disabledReason = reason
            LOGGER.warn("velocity: disabling velocity pass — {}", reason)
        }
        this.enabled = false
    }

    private fun releaseResources() {
        this.currentView?.close()
        this.currentView = null
        this.previousView?.close()
        this.previousView = null
        this.current?.close()
        this.current = null
        this.previous?.close()
        this.previous = null
        this.uniform?.close()
        this.uniform = null
        this.objects?.close()
        this.objects = null
        this.objectCount = 0
        this.depthView?.close()
        this.depthView = null
        this.depthViewFor = 0L
        this.width = 0
        this.height = 0
    }

    // ---------------- HLSL: native entry point is always `main`; DXC, SM 6.9 / DXIL only ----------------

    /** Full-screen triangle: `SV_VertexID` 0/1/2 -> uv (0,0)/(2,0)/(0,2); an empty input layout is legal. */
    private val VERTEX_HLSL: String = """
        struct VsOut {
            float4 position : SV_Position;
        };

        VsOut main(uint vertexId : SV_VertexID) {
            VsOut output;
            float2 uv = float2((vertexId << 1) & 2, vertexId & 2);
            output.position = float4(uv.x * 2.0 - 1.0, 1.0 - uv.y * 2.0, 0.0, 1.0);
            return output;
        }
    """.trimIndent()

    /**
     * Depth reprojection -> screen-space motion in pixels, plus the per-object correction.
     *
     * `SV_Position.xy` is the pixel centre, so the uv needs no extra interpolation. Depth 0 or 1 (sky,
     * far plane) reports 0 so nothing is reprojected out of the far plane.
     *
     * Per-object rows carry only the AABB and the two positions: a rigid body translates, so
     * `world + (prev - cur)` is exact — an object-centre screen delta would move static objects.
     */
    private val PIXEL_HLSL: String = """
        cbuffer DXRMVelocity : register(b0, space0) {
            float4x4 PrevViewProj;
            float4x4 InvCurrViewProj;
            float2 ScreenSize;
            // x = 物体行数 + 1（0 表示没有物体，循环一次都不进），y = 位移像素上限。
            float2 ObjectParams;
            // 本帧抖动带来的像素偏移。uv 是抖动后的屏幕位置，PrevViewProj 是未抖动的
            // 上一帧投影，所以位移里混着这份 jitter，必须减掉才是去抖动空间的位移。
            float2 JitterPixels;
            // 上一帧的抖动。历史缓冲是上一帧**抖动后**的画面：同一个表面点在上一帧里
            // 落在 prevUv + 上一帧jitter 的像素上，所以采样点还得再补回这一项。
            float2 PrevJitterPixels;
            // 两帧之间相机平移的增量（本帧 − 上一帧）。深度反投影出的是**相对本帧相机**的
            // 位置，而 PrevViewProj 的视图矩阵只有旋转，所以必须补上这段平移，否则相机自己
            // 走动/侧移造成的视差整块丢失。
            float3 CameraDelta;
            float CameraDeltaPad;
        };

        Texture2D<float> DXRMDepth : register(t1, space0);
        SamplerState DXRMDepthSampler : register(s1, space0);

        // texel buffer：每个 float 一个元素（绑定类型 2 + texelFormat = R32_FLOAT）。
        Buffer<float> DXRMObjects : register(t2, space0);

        static const uint OBJECT_ROW_FLOATS = 16;
        static const uint OBJECT_FIELD_CUR_X = 1;
        static const uint OBJECT_FIELD_PREV_X = 5;
        static const uint OBJECT_FIELD_HALF_X = 8;
        static const uint OBJECT_FIELD_YAW = 11;

        // 盒子上再外扩一点：挥臂/摆腿的顶点会短暂越出 AABB，不外扩就会漏掉肢体
        // （漏掉只是退回相机重投影，不会更差，但能补上就补）。
        static const float OBJECT_BOX_MARGIN = 0.25;

        // 画世界 AABB 的八个角：轴对齐盒 + 绕 Y 轴 yaw，正好八个顶点，用下标位选正负号。
        float3 objectCorner(float3 center, float3 halfExtent, float yaw, uint index) {
            float3 corner = float3((index & 1u) == 0u ? -halfExtent.x : halfExtent.x,
                                   (index & 2u) == 0u ? -halfExtent.y : halfExtent.y,
                                   (index & 4u) == 0u ? -halfExtent.z : halfExtent.z);
            float c = cos(yaw);
            float s = sin(yaw);
            return center + float3(corner.x * c - corner.z * s, corner.y, corner.x * s + corner.z * c);
        }

        // 像素的世界位置是否落在该物体**上一帧**的盒子里。用上一帧的盒子（而不是本帧的）是
        // 有意的：本 pass 读到的深度、反投影出的世界位置描述的都是**本帧**的几何，所以匹配时
        // 问的是"本帧这个位置，上一帧属于哪个物体"。
        bool insideObjectBox(float3 world, uint base) {
            float3 center = float3(DXRMObjects.Load(base + OBJECT_FIELD_CUR_X),
                                   DXRMObjects.Load(base + OBJECT_FIELD_CUR_X + 1u),
                                   DXRMObjects.Load(base + OBJECT_FIELD_CUR_X + 2u));
            float3 halfExtent = float3(DXRMObjects.Load(base + OBJECT_FIELD_HALF_X),
                                       DXRMObjects.Load(base + OBJECT_FIELD_HALF_X + 1u),
                                       DXRMObjects.Load(base + OBJECT_FIELD_HALF_X + 2u))
                                + OBJECT_BOX_MARGIN;
            float yaw = DXRMObjects.Load(base + OBJECT_FIELD_YAW);

            float3 minCorner = float3(1e30, 1e30, 1e30);
            float3 maxCorner = float3(-1e30, -1e30, -1e30);
            for (uint i = 0u; i < 8u; ++i) {
                float3 corner = objectCorner(center, halfExtent, yaw, i);
                minCorner = min(minCorner, corner);
                maxCorner = max(maxCorner, corner);
            }
            return all(world >= minCorner) && all(world <= maxCorner);
        }

        float4 main(float4 position : SV_Position) : SV_Target0 {
            float2 uv = position.xy / ScreenSize;
            float depth = DXRMDepth.SampleLevel(DXRMDepthSampler, uv, 0).r;
            if (depth <= 0.0 || depth >= 1.0) {
                // 天空/远平面（reverse-Z 下 depth==0）没有可靠的对应点，报 0 让 DLSS 自己判断。
                return float4(0.0, 0.0, 0.0, 0.0);
            }
            float4 ndc = float4(uv.x * 2.0 - 1.0, 1.0 - uv.y * 2.0, depth, 1.0);
            float4 world = mul(InvCurrViewProj, ndc);
            if (abs(world.w) < 1e-6) {
                return float4(0.0, 0.0, 0.0, 0.0);
            }
            world /= world.w;
            float4 prevClip = mul(PrevViewProj, float4(world.xyz + CameraDelta, 1.0));
            // 点在上一帧相机背后（w<0）时绝不能再做 xy/w：负的 w 会把屏幕位置镜像到另一边，
            // 得到一个量级看着合理、实际指向别处的位移，DLSS 照着它去历史缓冲取错颜色（沙地
            // 变白、树叶变紫，只在转视角/走动时闪一下）。这种像素没有对应点，报 0 交给 disocclusion。
            // `abs(prevClip.w) < 1e-6` 只挡住数值退化，恰好放过了 w<0 这一整类。
            if (prevClip.w <= 1e-4) {
                return float4(0.0, 0.0, 0.0, 0.0);
            }
            float2 prevNdc = prevClip.xy / prevClip.w;
            float2 prevUv = float2(prevNdc.x * 0.5 + 0.5, 0.5 - prevNdc.y * 0.5);
            // 运动向量方向是「从本帧指向该像素在上一帧的位置」，即 prevPos - curPos；写成
            // `uv - prevUv` 方向整体反了，静止时两边都是 0 看不出来，一动就在运动方向上拖出
            // 永不收敛的残影（机制见 docs/antialiasing/README.md 的速度缓冲一节）。
            // jitter 注入的是列 2（`out[8]`/`out[9]`，即 `m20`/`m21`），它乘 view.z，而透视的
            // `w = -view.z`，所以 `clip.x += j*z` 经透视除法后内容在 NDC 里移动 `-j`，换算到像
            // 素坐标（y 向下）是 `(+j.x, -j.y)` —— y 轴反号。MV 要的是未抖动网格之间的位移，
            // 故下面补 `(+J.x, -J.y)`。
            float2 motion = (prevUv - uv) * ScreenSize + float2(JitterPixels.x, -JitterPixels.y);
            // NaN/inf 兜底：NaN 参与的比较恒为假，所以用"取反的 <=" 写法把它一并挡掉。上一条
            // 守卫之后这里理论上不会触发，留着是因为一旦传出去就是一整块画面的错色。
            if (!(dot(motion, motion) <= 1.0e12)) {
                return float4(0.0, 0.0, 0.0, 0.0);
            }
            // 先到先得 —— 行是按「上一帧采集顺序」排的，一个像素落在多个盒子里时
            // （嵌套的乘客/坐骑、相邻的掉落物）取第一行，够用且稳定。
            uint rowLimit = (uint)ObjectParams.x;
            for (uint row = 0u; row < rowLimit; ++row) {
                uint base = row * OBJECT_ROW_FLOATS;
                if (!insideObjectBox(world, base)) {
                    continue;
                }
                // 这个像素现在看到的是物体的表面点。它上一帧在哪儿？物体是刚体平移，
                // 所以上一帧位置 = 本帧世界位置 + (上一帧物体位置 - 本帧物体位置)。
                // 这是**精确**的对应关系，不需要也不应该去借物体中心的屏幕位移来近似。
                float3 cur = float3(DXRMObjects.Load(base + OBJECT_FIELD_CUR_X),
                                    DXRMObjects.Load(base + OBJECT_FIELD_CUR_X + 1u),
                                    DXRMObjects.Load(base + OBJECT_FIELD_CUR_X + 2u));
                float3 prv = float3(DXRMObjects.Load(base + OBJECT_FIELD_PREV_X),
                                    DXRMObjects.Load(base + OBJECT_FIELD_PREV_X + 1u),
                                    DXRMObjects.Load(base + OBJECT_FIELD_PREV_X + 2u));
                float3 prevWorld = world.xyz + (prv - cur) + CameraDelta;
                float4 prevObjectClip = mul(PrevViewProj, float4(prevWorld, 1.0));
                // 同 main 里的守卫：w<=0 会把位置镜像到画面另一处，得到假位移（见上）。
                if (prevObjectClip.w <= 1e-4) {
                    continue;
                }
                float2 prevObjectNdc = prevObjectClip.xy / prevObjectClip.w;
                float2 prevObjectUv = float2(prevObjectNdc.x * 0.5 + 0.5, 0.5 - prevObjectNdc.y * 0.5);
                // 同上：未抖动网格之间的位移；jitter 在像素坐标里的实际位移是
                // `(+J.x, -J.y)`（y 反号，理由见上面 motion 的注释）。
                float2 objectMotion = (prevObjectUv - uv) * ScreenSize + float2(JitterPixels.x, -JitterPixels.y);
                // 坏匹配（张冠李戴的行）位移会大得离谱，直接丢掉，回退相机重投影。
                if (dot(objectMotion, objectMotion) > ObjectParams.y * ObjectParams.y) {
                    continue;
                }
                return float4(objectMotion, 0.0, 0.0);
            }
            return float4(motion, 0.0, 0.0);
        }
    """.trimIndent()
}
