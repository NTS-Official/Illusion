package dev.nts.expdxr.d3d12.render.advanced.taa

import com.mojang.renderpearl.api.buffers.GpuBuffer
import com.mojang.renderpearl.api.buffers.GpuBufferSlice
import dev.nts.expdxr.misc.settings.D3D12Settings
import dev.nts.expdxr.d3d12.extern.MCDR_ABI
import dev.nts.expdxr.d3d12.render.advanced.vrs.D3D12RenderScale
import dev.nts.expdxr.d3d12.render.core.D3D12GpuBuffer
import dev.nts.expdxr.dxrmc.sr.dlss.DeepLearningSuperSampling
import net.minecraft.client.Minecraft
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Temporal (TAAU) base state: sub-pixel jitter, previous-frame/history management, per-object records.
 *
 * Jitter goes into the **level** projection only, columns [8]/[9] (`clip.w = -view.z`), never into the 3D
 * HUD; history must always keep the **unjittered** matrix or the velocity field is polluted. Halton(2,3)
 * in pixels, `[-0.5, +0.5)`, injected only when something consumes it ([hasJitterConsumer]).
 * See docs/antialiasing/README.md §3.
 */
object D3D12Temporal {
    private val LOGGER: Logger = LoggerFactory.getLogger("dxrmc-temporal")

    /** Samples per jitter cycle (a power of two keeps it aligned with the 8/16-sample TAAU presets). */
    const val DEFAULT_JITTER_SAMPLES: Int = 16

    /** GPU readback of the object/chunk UBOs. **Must stay false**: it turns the whole frame into the clear colour. */
    private const val CAPTURE_UNIFORMS_BY_READBACK: Boolean = false

    /** vanilla `ProjectionMatrixBuffer` label of the level instance; only this one gets the jitter. */
    const val LEVEL_PROJECTION_LABEL: String = "Camera projection matrix UBO level"

    /** vanilla `GlobalSettingsUniform` label: the camera world position source. */
    const val GLOBALS_LABEL: String = "Global Settings UBO"

    /** Master switch (`[debug] temporalChain`); shipped gameplay lights this chain via the TAA/DLSS options. */
    @Volatile
    var enabled: Boolean = D3D12Settings.temporalChain

    /**
     * Whether the temporal data plane must roll; TAA or DLSS/DLAA needs the jitter sequence and the history
     * roll even with the dev switch [enabled] off, or TAA degrades into a static blur.
     */
    @JvmStatic
    fun isActive(): Boolean = enabled || D3D12TAA.isSelected() || DeepLearningSuperSampling.isEnabled()

    /**
     * Whether any temporal resolve really consumes the sub-pixel jitter; injecting it without a consumer
     * makes the whole image wobble. The velocity/history rolls still follow [isActive].
     */
    private fun hasJitterConsumer(): Boolean =
        D3D12TAA.isSelected() ||
            (DeepLearningSuperSampling.isEnabled() && !DeepLearningSuperSampling.isStalled())

    /** Whether the jitter is really written into the projection (the capture path ignores it). */
    @Volatile
    var jitterEnabled: Boolean = D3D12Settings.temporalJitter

    /** Jitter cycle length (clamped to >= 1). */
    @Volatile
    var jitterSamples: Int = DEFAULT_JITTER_SAMPLES
        set(value) {
            field = if (value < 1) 1 else value
        }

    private var frameIndex: Long = 0L

    /** Backend-reported phase length (0 = not applicable); recorded only, see [adoptPhaseCount]. */
    @Volatile
    var negotiatedPhaseCount: Int = 0
        private set

    /** Last reported length, so the conclusion is logged once. */
    private var loggedPhaseCount: Int = -1

    /**
     * Adopts a backend-reported phase length; never touches the injection switch. DLSS keeps 16 (it only
     * needs `jitterOffset` to match); FSR/XeSS would switch to their own sequence.
     */
    fun adoptPhaseCount(backend: Int, reported: Int) {
        if (reported <= 0) {
            return
        }
        negotiatedPhaseCount = reported
        if (reported == jitterSamples) {
            loggedPhaseCount = reported
            return
        }
        if (backend == MCDR_ABI.SR_BACKEND_DLSS) {
            if (loggedPhaseCount != reported) {
                loggedPhaseCount = reported
                LOGGER.info(
                    "jitter phase: backend=DLSS wants {} but the caller-owned sequence stays {} " +
                        "(SL only requires jitterOffset to match what we injected)",
                    reported,
                    jitterSamples,
                )
            }
            return
        }
        LOGGER.info("jitter phase: adopting backend-reported {} (was {})", reported, jitterSamples)
        jitterSamples = reported
        loggedPhaseCount = reported
    }

    /**
     * Frame stamp ([Minecraft.getFrameTimeNs]) of the last level-projection upload; one frame may upload it
     * several times, and advancing per upload would give two jitters within one frame.
     */
    private var uploadFrameNs: Long = Long.MIN_VALUE

    private var jitterX: Double = 0.0
    private var jitterY: Double = 0.0
    private var previousJitterX: Double = 0.0
    private var previousJitterY: Double = 0.0

    /**
     * This frame's sub-pixel offset in pixels, `[-0.5, 0.5)`; the velocity output is already jittered, so the
     * TAA resolve must not subtract it again.
     */
    val currentJitterX: Double
        get() = jitterX

    /** 见 [currentJitterX]。 */
    val currentJitterY: Double
        get() = jitterY

    /** This frame's **unjittered** level projection (column-major, 16 floats, byte-identical to the uniform). */
    private var projection: FloatArray? = null

    /** Previous frame's unjittered level projection. */
    private var previousProjection: FloatArray? = null

    /**
     * This frame's unjittered level projection from vanilla's CPU render state ([captureProjection]); the UBO
     * bytes are no projection matrix (no `m32 = -1`), which kills `w = -view.z` and every motion vector.
     */
    private var stateProjection: FloatArray? = null

    /** [stateProjection] one frame late (rolled in [beginFrame]). */
    private var previousStateProjection: FloatArray? = null

    /** Legacy single-matrix entry; the live wiring uses projection + camera view. */
    private var viewProj: FloatArray? = null
    private var previousViewProj: FloatArray? = null

    /** This frame's per-object model matrices (`DynamicTransforms`, draw order) and the previous frame's. */
    private var modelViews: MutableList<FloatArray> = ArrayList()

    @Volatile
    private var previousModelViews: List<FloatArray> = emptyList()

    /** This frame's chunk uploads (`ChunkSection`, draw order): model matrix + chunk origin. */
    private var chunkSections: MutableList<ChunkSection> = ArrayList()

    @Volatile
    private var previousChunkSections: List<ChunkSection> = emptyList()

    /** Camera world position this frame / previous frame. */
    private var cameraPosition: FloatArray? = null
    private var previousCameraPosition: FloatArray? = null

    /** Camera model-view from vanilla's CPU render state (fed by [captureCameraView]); no GPU readback. */
    private var stateCameraView: FloatArray? = null
    private var previousStateCameraView: FloatArray? = null

    /** Cached Halton(2) / Halton(3) values, generated lazily. */
    private var halton2: DoubleArray = DoubleArray(0)
    private var halton3: DoubleArray = DoubleArray(0)

    /**
     * History generation, bumped by [reset]; consumers must drop their cross-frame history when it changes,
     * or a teleport smears for dozens of frames.
     */
    @Volatile
    var historyGeneration: Long = 0L
        private set

    /** One chunk upload: `modelView` is the camera view for every chunk, x/y/z the chunk origin. */
    class ChunkSection(val modelView: FloatArray, val x: Int, val y: Int, val z: Int)

    @Synchronized
    fun reset() {
        frameIndex = 0L
        uploadFrameNs = Long.MIN_VALUE
        // Bump the generation first: even if a release below throws, consumers already know not to blend.
        historyGeneration++
        jitterX = 0.0
        jitterY = 0.0
        previousJitterX = 0.0
        previousJitterY = 0.0
        projection = null
        previousProjection = null
        viewProj = null
        previousViewProj = null
        modelViews = ArrayList()
        previousModelViews = emptyList()
        chunkSections = ArrayList()
        previousChunkSections = emptyList()
        cameraPosition = null
        previousCameraPosition = null
        D3D12Velocity.reset()
        D3D12TAA.reset()
        D3D12ObjectMotion.reset()
    }

    /**
     * Rolls history and advances the jitter; must run at the frame start, before any uniform upload.
     */
    @Synchronized
    fun beginFrame() {
        // Camera position comes straight from `Camera` at the frame start. Deriving it from the Global
        // Settings UBO gave garbage, and garbage there means a zero motion field and permanent ghosting.
        captureCameraFromCamera()
        previousJitterX = jitterX
        previousJitterY = jitterY
        previousProjection = projection
        previousViewProj = viewProj
        previousModelViews = modelViews
        modelViews = ArrayList(previousModelViews.size + 8)
        previousChunkSections = chunkSections
        chunkSections = ArrayList(previousChunkSections.size + 8)
        // CPU-side camera model-view rolls in the same frame as the rest.
        previousStateCameraView = stateCameraView
        stateCameraView = null
        // Same for the CPU-side level projection.
        previousStateProjection = stateProjection
        stateProjection = null

        val n = jitterSamples
        ensureHalton(n)
        val index = (frameIndex % n).toInt()
        // Halton (0,1) -> centred pixel offset [-0.5, +0.5); without a consumer the jitter must be exactly 0.
        if (hasJitterConsumer() && jitterEnabled) {
            jitterX = halton2[index] - 0.5
            jitterY = halton3[index] - 0.5
        } else {
            jitterX = 0.0
            jitterY = 0.0
        }
        frameIndex++
        // Velocity textures roll on the same beat.
        D3D12Velocity.beginFrame()
        // So does the TAA history ping-pong.
        D3D12TAA.beginFrame()
        // Per-object snapshots: entity positions are final after tick(), the velocity pass records at frame end.
        D3D12ObjectMotion.beginFrame()
        D3D12ObjectMotion.harvestVisible()
    }

    /**
     * Upload hook (once per frame): rolls history, keeps the **unjittered** matrix, returns the jittered copy.
     * @param width/height render-target pixels (<= 0 = no jitter on that axis)
     */
    @Synchronized
    fun onLevelProjectionUpload(pristine: FloatArray, width: Int, height: Int): FloatArray {
        if (pristine.size != 16) {
            return pristine
        }
        // Advance at most once per frame (see [uploadFrameNs]).
        val stamp = Minecraft.getInstance().frameTimeNs
        if (stamp != uploadFrameNs) {
            uploadFrameNs = stamp
            beginFrame()
        }
        // Prefer the CPU render state (authoritative); the UBO bytes only serve as a fallback.
        projection = stateProjection?.copyOf() ?: pristine.copyOf()
        // Only a real temporal consumer gets jittered (SMAA/OFF must stay pristine).
        if (!hasJitterConsumer() || !jitterEnabled) {
            return pristine.copyOf()
        }
        return jitterProjection(pristine, clipJitterX(width), clipJitterY(height))
    }

    /**
     * Adds a clip-space offset to the projection ([8] = m[0][2], [9] = m[1][2]); `clip.w = -view.z`, so `m[0][2] -= c`.
     */
    fun jitterProjection(pristine: FloatArray, clipX: Double, clipY: Double): FloatArray {
        val out = pristine.copyOf()
        out[8] = (out[8] - clipX).toFloat()
        out[9] = (out[9] - clipY).toFloat()
        return out
    }

    /** This frame's jitter in pixels, `[-0.5, +0.5)`; 0 when disabled. */
    @Synchronized
    fun jitter(): DoubleArray = doubleArrayOf(jitterX, jitterY)

    /** Diagnostic: (jx, jy, frameId) of the last projection injection; null when never injected. */
    @Synchronized
    fun injectedJitter(): DoubleArray? {
        if (injectedFrameId == Long.MIN_VALUE) {
            return null
        }
        return doubleArrayOf(injectedJitterX, injectedJitterY, injectedFrameId.toDouble())
    }

    /** Diagnostic: |injected - current| jitter (largest axis); -1 when the frame ids differ. */
    @Synchronized
    fun injectedJitterMismatch(): Double {
        if (injectedFrameId != presentedFrameId) {
            return -1.0
        }
        val dx = kotlin.math.abs(injectedJitterX - jitterX)
        val dy = kotlin.math.abs(injectedJitterY - jitterY)
        return if (dx >= dy) dx else dy
    }

    /**
     * Frame boundary (once per `present()`): bumps the frame identity. A time window misjudges this — it
     * merges uploads at low frame rates and two frames at high ones.
     */
    @Synchronized
    fun onFramePresented() {
        presentedFrameId++
    }

    /**
     * Write hook: the level projection (label [LEVEL_PROJECTION_LABEL], 64 B) is captured unjittered and
     * returned jittered; every other write returns null. Injection must happen here: the buffers are not
     * mappable and vanilla rewrites them every frame.
     */
    @Synchronized
    fun interceptWrite(destination: GpuBufferSlice, data: ByteBuffer): ByteBuffer? {
        if (!isActive()) {
            return null
        }
        val buffer = destination.buffer()
        if (buffer !is D3D12GpuBuffer) {
            return null
        }
        // Injection is the default: TAA and DLSS share one sequence. The old "do not replace while frozen"
        // branch was a misdiagnosis — the real bug was the byte order in [readFloats].
        return when (buffer.label()) {
            LEVEL_PROJECTION_LABEL -> interceptLevelProjectionWrite(destination, data)
            else -> null
        }
    }

    /** Takes the camera position from `Camera`; only [beginFrame] may call it, since it rolls history. */
    private fun captureCameraFromCamera() {
        val pos = try {
            Minecraft.getInstance().gameRenderer.mainCamera().position()
        } catch (t: Throwable) {
            null
        } ?: return
        if (!pos.x.isFinite() || !pos.y.isFinite() || !pos.z.isFinite()) {
            return
        }
        captureCamera(pos.x.toFloat(), pos.y.toFloat(), pos.z.toFloat())
    }

    /** Jitter injection for the level projection (called from [interceptWrite] only). */
    private fun interceptLevelProjectionWrite(destination: GpuBufferSlice, data: ByteBuffer): ByteBuffer? {
        if (data.remaining() != 64) {
            return null
        }
val size = renderSize() ?: return null
        val pristine = readFloats(data, 0, 16) ?: return null
        // Frame identity = present count, not a time window: at low frame rates a second upload in the same
        // frame would roll the jitter again and the raster and the resolve end up disagreeing.
        val jittered: FloatArray = if (lastRollFrameId == presentedFrameId
            && projection != null && lastSizeWidth == size[0] && lastSizeHeight == size[1]) {
            // Repeated write in the same frame: keep the jitter, the first write captured the projection.
            jitterProjection(pristine, clipJitterX(size[0]), clipJitterY(size[1]))
        } else {
            lastRollFrameId = presentedFrameId
            lastSizeWidth = size[0]
            lastSizeHeight = size[1]
            onLevelProjectionUpload(pristine, size[0], size[1])
        }

        // Remember the jitter really fed to the raster, so the DLSS side can compare reported vs rendered.
        injectedJitterX = jitterX
        injectedJitterY = jitterY
        injectedFrameId = presentedFrameId

        // Rewrite the caller's buffer in place (offsets relative to `position`): a fresh buffer breaks the
        // caller's length/lifetime assumptions.
        return try {
            val view = data.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            view.position(data.position())
            view.asFloatBuffer().put(jittered, 0, 16)
            data
        } catch (t: Throwable) {
            // Read-only buffer: fall back to a new buffer, which carries the full 64 B payload.
            val out = ByteBuffer.allocate(data.remaining()).order(ByteOrder.LITTLE_ENDIAN)
            out.asFloatBuffer().put(jittered, 0, 16)
            out
        }
    }

    /**
     * Per-object UBO capture hook (`D3D12RenderPassBackend.setUniform`), keyed by uniform name. Currently
     * inert: [CAPTURE_UNIFORMS_BY_READBACK] stays false, the write-map readback of vanilla's UBO rings
     * (usage 130, no MAP_READ) collapses the frame into the clear colour.
     */
    fun captureUniform(name: String, value: GpuBufferSlice) {
        if (!isActive()) {
            return
        }
        // GPU readback is off (see above): the per-object history has no data yet.
        if (CAPTURE_UNIFORMS_BY_READBACK) {
            captureUniformByReadback(name, value)
        }
    }

    /** Disabled readback path, kept for when native supports MAP_READ. */
    private fun captureUniformByReadback(name: String, value: GpuBufferSlice) {
        try {
            when (name) {
                "DynamicTransforms" -> {
                    if (value.length() < 64L) {
                        return
                    }
                    val modelView = mapFloats(value.buffer(), value.offset(), 16) ?: return
                    recordModelView(modelView)
                }
                "ChunkSection" -> {
                    if (value.length() < 96L) {
                        return
                    }
                    val modelView = mapFloats(value.buffer(), value.offset(), 16) ?: return
                    val origin = mapInts(value.buffer(), value.offset() + 80L, 3) ?: return
                    recordChunkSection(modelView, origin[0], origin[1], origin[2])
                }
            }
        } catch (e: Exception) {
            // Unmappable or closed buffer: skip the capture (it is purely additive data).
        }
    }

    /** Number of presented frames, used as the frame identity (see [onFramePresented]). */
    private var presentedFrameId: Long = 0L

    /** jitter + frame id of the last injection, see [injectedJitter]. */
    private var injectedJitterX: Double = 0.0
    private var injectedJitterY: Double = 0.0
    private var injectedFrameId: Long = Long.MIN_VALUE

    /** Frame identity of the last history roll; repeated uploads in one frame do not roll again. */
    private var lastRollFrameId: Long = -1L

    private var lastSizeWidth: Int = 0
    private var lastSizeHeight: Int = 0

    /**
     * Render-target pixels = window size x `[render] renderScale`; the jitter is in target pixels, so must scale.
     */
    private fun renderSize(): IntArray? {
        return try {
            val window = Minecraft.getInstance().window
            val width = D3D12RenderScale.scaled(window.width)
            val height = D3D12RenderScale.scaled(window.height)
            if (width <= 0 || height <= 0) null else intArrayOf(width, height)
        } catch (t: Throwable) {
            null
        }
    }

    /** Reads `count` little-endian floats at [offset] bytes from the caller's current position. */
    private fun readFloats(data: ByteBuffer, offset: Int, count: Int): FloatArray? {
        val fb = data.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        // The offset is relative to the caller's current position (`uploadStaging` copies position..limit);
        // an absolute offset reads misaligned bytes and the projection comes back as garbage.
        val base = data.position() + offset
        if (offset < 0 || fb.limit() - base < count * 4) {
            return null
        }
        fb.position(base)
        // `slice()` returns a BIG_ENDIAN view (JDK contract): the byte order must be set again after
        // slicing, or every float comes back byte-swapped.
        val slice = fb.slice().order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        if (slice.remaining() < count) {
            return null
        }
        val out = FloatArray(count)
        slice.get(out)
        return out
    }

    /** Same as [readFloats], for ints, with the offset relative to the current position. */
    private fun readInts(data: ByteBuffer, offset: Int, count: Int): IntArray? {
        val fb = data.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val base = data.position() + offset
        if (offset < 0 || fb.limit() - base < count * 4) {
            return null
        }
        fb.position(base)
        val slice = fb.slice().asIntBuffer()
        if (slice.remaining() < count) {
            return null
        }
        val out = IntArray(count)
        slice.get(out)
        return out
    }

    /**
     * Maps a buffer slice and reads floats. vanilla's dynamic UBO rings are usage 130 (MAP_WRITE, no
     * MAP_READ) and native's `mapBuffer` warns on every call — tens of thousands of stderr lines stall the
     * render thread. So check the bit here, map for write and read the CPU-visible UPLOAD memory.
     */
    private fun mapFloats(buffer: GpuBuffer, offset: Long, count: Int): FloatArray? {
        if (!isHostVisible(buffer)) {
            return null
        }
        buffer.map(offset, (count * 4).toLong(), false, true).use { view ->
            val fb = view.data().order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            if (fb.remaining() < count) {
                return null
            }
            val out = FloatArray(count)
            fb.get(out)
            return out
        }
    }

    /** Same as [mapFloats], for ints. */
    private fun mapInts(buffer: GpuBuffer, offset: Long, count: Int): IntArray? {
        if (!isHostVisible(buffer)) {
            return null
        }
        buffer.map(offset, (count * 4).toLong(), false, true).use { view ->
            val ib = view.data().order(ByteOrder.LITTLE_ENDIAN).asIntBuffer()
            if (ib.remaining() < count) {
                return null
            }
            val out = IntArray(count)
            ib.get(out)
            return out
        }
    }

    /** Host-visible = has MAP_WRITE; reading that memory is legal without MAP_READ. */
    private fun isHostVisible(buffer: GpuBuffer): Boolean {
        return try {
            (buffer.usage() and GpuBuffer.USAGE_MAP_WRITE) != 0
        } catch (t: Throwable) {
            false
        }
    }

    /** Previous frame's jitter in pixels. */
    @Synchronized
    fun previousJitter(): DoubleArray = doubleArrayOf(previousJitterX, previousJitterY)

    /** This frame's jitter as a clip-space X offset (NDC [-1,1] spans the whole width). */
    @Synchronized
    fun clipJitterX(width: Int): Double = if (width <= 0) 0.0 else 2.0 * jitterX / width

    /** This frame's jitter as a clip-space Y offset. */
    @Synchronized
    fun clipJitterY(height: Int): Double = if (height <= 0) 0.0 else 2.0 * jitterY / height

    /** Saves the unjittered level projection; the first write of the frame wins. */
    @Synchronized
    fun captureProjection(matrix: FloatArray) {
        if (matrix.size != 16 || stateProjection != null) {
            return
        }
        stateProjection = matrix.copyOf()
    }

    /** This frame's unjittered projection. */
    @Synchronized
    fun currentProjection(): FloatArray? = stateProjection?.copyOf() ?: projection?.copyOf()

    /** Previous frame's unjittered projection; null on the first frame. */
    @Synchronized
    fun previousProjection(): FloatArray? = previousStateProjection?.copyOf() ?: previousProjection?.copyOf()

    /** Legacy entry: saves this frame's unjittered view-projection (16 floats, column-major). */
    @Synchronized
    fun captureViewProj(matrix: FloatArray) {
        if (matrix.size != 16) {
            return
        }
        viewProj = matrix.copyOf()
    }

    /** Previous frame's unjittered view-projection; null on the first frame. */
    @Synchronized
    fun previousViewProj(): FloatArray? = previousViewProj?.copyOf()

    /** This frame's captured view-projection. */
    @Synchronized
    fun currentViewProj(): FloatArray? = viewProj?.copyOf()

    /** Records one per-object model matrix upload (`DynamicTransforms`, in draw order). */
    @Synchronized
    fun recordModelView(matrix: FloatArray) {
        if (matrix.size != 16) {
            return
        }
        modelViews.add(matrix.copyOf())
    }

    /** Previous frame's model matrix at the same draw index; null when out of range (a new object). */
    @Synchronized
    fun previousModelViewAt(index: Int): FloatArray? {
        val list = previousModelViews
        if (index < 0 || index >= list.size) {
            return null
        }
        return list[index].copyOf()
    }

    /** Number of model matrices recorded this frame. */
    @Synchronized
    fun modelViewCount(): Int = modelViews.size

    /** Number of model matrices recorded last frame. */
    @Synchronized
    fun previousModelViewCount(): Int = previousModelViews.size

    /** Records one chunk upload (`ChunkSection`, in draw order). */
    @Synchronized
    fun recordChunkSection(modelView: FloatArray, x: Int, y: Int, z: Int) {
        if (modelView.size != 16) {
            return
        }
        chunkSections.add(ChunkSection(modelView.copyOf(), x, y, z))
    }

    /** Previous frame's chunk record at the same draw index; null when out of range. */
    @Synchronized
    fun previousChunkSectionAt(index: Int): ChunkSection? {
        val list = previousChunkSections
        if (index < 0 || index >= list.size) {
            return null
        }
        val s = list[index]
        return ChunkSection(s.modelView.copyOf(), s.x, s.y, s.z)
    }

    /** Number of chunks recorded this frame. */
    @Synchronized
    fun chunkSectionCount(): Int = chunkSections.size

    /** Number of chunks recorded last frame. */
    @Synchronized
    fun previousChunkSectionCount(): Int = previousChunkSections.size

    /**
     * This frame's camera model-view. vanilla writes the same matrix into every chunk's `ChunkSection`, so
     * any record holds it — chunks first, they draw first.
     */
    @Synchronized
    fun currentCameraView(): FloatArray? {
        // Prefer the CPU render state; the UBO readback is gone.
        stateCameraView?.let { return it.copyOf() }
        if (chunkSections.isNotEmpty()) {
            return chunkSections[0].modelView.copyOf()
        }
        if (modelViews.isNotEmpty()) {
            return modelViews[0].copyOf()
        }
        return null
    }

    /** Previous frame's camera model-view; null on the first frame or when nothing was drawn. */
    @Synchronized
    fun previousCameraView(): FloatArray? {
        previousStateCameraView?.let { return it.copyOf() }
        if (previousChunkSections.isNotEmpty()) {
            return previousChunkSections[0].modelView.copyOf()
        }
        if (previousModelViews.isNotEmpty()) {
            return previousModelViews[0].copyOf()
        }
        return null
    }

    /** Records this frame's camera model-view; the first call of the frame wins, no GPU readback involved. */
    @Synchronized
    fun captureCameraView(matrix: FloatArray) {
        if (matrix.size != 16 || stateCameraView != null) {
            return
        }
        stateCameraView = matrix.copyOf()
    }

    /**
     * Feeds vanilla's CPU render state (`levelRenderState.cameraRenderState`) in — the only authoritative
     * source for this data plane.
     * @return false = the state is not available yet (loading / not in a world), do not use it this frame
     */
    @JvmStatic
    @Synchronized
    fun captureCameraFromState(minecraft: Minecraft): Boolean {
        val state = try {
            minecraft.gameRenderer.gameRenderState().levelRenderState.cameraRenderState
        } catch (t: Throwable) {
            return false
        }
        val view = FloatArray(16)
        state.viewRotationMatrix.get(view)
        captureCameraView(view)
        val projection = FloatArray(16)
        state.projectionMatrix.get(projection)
        captureProjection(projection)
        return true
    }

    /**
     * Camera world position. History rolls here (not in [beginFrame]): the upload order of the camera and the
     * projection is not ours, so "roll when a new value arrives" is order-independent.
     */
    @Synchronized
    fun captureCamera(x: Float, y: Float, z: Float) {
        val current = cameraPosition
        if (current != null && current[0] == x && current[1] == y && current[2] == z) {
            return
        }
        previousCameraPosition = current
        cameraPosition = floatArrayOf(x, y, z)
    }

    /** This frame's camera world position. */
    @Synchronized
    fun currentCameraPosition(): FloatArray? = cameraPosition?.copyOf()

    /** Previous frame's camera world position; null on the first frame. */
    @Synchronized
    fun previousCameraPosition(): FloatArray? = previousCameraPosition?.copyOf()

    /**
     * Camera translation between the frames (`now - prev`) or null. Vertices are camera-relative and
     * [previousCameraView] carries rotation only, so `prevWorld = world + (camNow - camPrev)` applies it.
     */
    @Synchronized
    fun cameraDelta(): FloatArray? {
        val now = cameraPosition ?: return null
        val prev = previousCameraPosition ?: return null
        return floatArrayOf(now[0] - prev[0], now[1] - prev[1], now[2] - prev[2])
    }

    /** View-projection (projection x modelView) of object `index` this frame. */
    @Synchronized
    fun currentViewProjAt(index: Int): FloatArray? {
        val p = projection ?: return null
        if (index < 0 || index >= modelViews.size) {
            return null
        }
        return multiply(p, modelViews[index])
    }

    /** Same for the previous frame; null when the pairing fails. */
    @Synchronized
    fun previousViewProjAt(index: Int): FloatArray? {
        val p = previousProjection ?: return null
        val list = previousModelViews
        if (index < 0 || index >= list.size) {
            return null
        }
        return multiply(p, list[index])
    }

    /** Column-major 4x4 multiply (`result = a * b`). */
    fun multiply(a: FloatArray, b: FloatArray): FloatArray {
        val out = FloatArray(16)
        for (c in 0 until 4) {
            val c4 = c * 4
            for (r in 0 until 4) {
                var sum = 0.0
                for (k in 0 until 4) {
                    sum += a[k * 4 + r].toDouble() * b[c4 + k].toDouble()
                }
                out[c4 + r] = sum.toFloat()
            }
        }
        return out
    }

    /** Halton series: van der Corput radical inverse (bit flip for base 2, the general algorithm for base 3). */
    private fun ensureHalton(n: Int) {
        if (halton2.size >= n && halton3.size >= n) {
            return
        }
        val size = if (n > DEFAULT_JITTER_SAMPLES) n else DEFAULT_JITTER_SAMPLES
        halton2 = DoubleArray(size) { i -> radicalInverse(i + 1L, 2) }
        halton3 = DoubleArray(size) { i -> radicalInverse(i + 1L, 3) }
    }

    private fun radicalInverse(index: Long, base: Int): Double {
        var result = 0.0
        var fraction = 1.0
        var i = index
        while (i > 0L) {
            fraction /= base
            result += fraction * (i % base)
            i /= base
        }
        return result
    }
}