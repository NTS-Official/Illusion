package io.github.dxegp.experimental.raytracing

import com.mojang.renderpearl.api.buffers.GpuBuffer
import com.mojang.renderpearl.api.GpuFormat
import com.mojang.renderpearl.api.textures.GpuTexture
import io.github.dxegp.RtBackend
import io.github.dxegp.RtDenoise
import io.github.dxegp.d3d12.backend.D3D12CommandEncoderBackend
import io.github.dxegp.d3d12.extern.MCDR_ABI
import io.github.dxegp.d3d12.render.adapter.D3D12Device
import io.github.dxegp.d3d12.render.core.D3D12GpuBuffer
import io.github.dxegp.d3d12.render.core.D3D12GpuTexture
import io.github.dxegp.d3d12.render.advanced.mesh.D3D12SectionGeometry
import io.github.dxegp.d3d12.render.advanced.taa.D3D12Temporal
import io.github.dxegp.d3d12.render.advanced.vrs.D3D12RenderScale
import io.github.dxegp.experimental.framegen.FrameGeneration
import io.github.dxegp.misc.IllusionLogger
import io.github.dxegp.misc.settings.D3D12Settings
import net.minecraft.client.Minecraft
import org.joml.Matrix4f
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.function.Supplier
import kotlin.math.abs
import java.util.concurrent.atomic.AtomicLong

/**
 * 逐帧光追派发：把几何池与材质表上传成 GPU 缓冲，跑一次 B-DXR 遍历，再合光成一张 HDR 结果。
 * 池每写一段、材质表每重烘一次才会重传，其余帧只重传 112 B 的相机常量。
 * 见 docs/rt/README.md
 */
object D3D12RtFrame {
    /** 常量缓冲就是 `WgaFrame`（b0，416 B），与 WorkGraph 后端共用布局。 */
    const val SCENE_BYTES = D3D12RtFrameConstants.BYTES

    /** 本项目内部约定：缓冲型 UAV 必须带这一位，native 才给它 `ALLOW_UNORDERED_ACCESS`。 */

    /** `rt-hdr` 的每像素字节数（`RGBA16_FLOAT`）。 */
    private const val HDR_BYTES = 8L

    /** 与两个着色器的 `[numthreads(8, 8, 1)]` 一致。 */
    private const val THREADS = 8

    /** BLAS 线框档的线程组宽度与「每个实例 12 条棱」，必须与 rt_debug_blas.hlsl 一致。 */
    private const val BLAS_THREADS = 64
    private const val BLAS_EDGES_PER_BOX = 12

    /** 相机常量的三份轮转 UPLOAD 缓冲：单份会在 GPU 还在读的时候被下一帧改写。 */
    private const val SCENE_RING = 3

    /** 常量缓冲的槽跨度：`WgaFrame` 416 B，CBV 偏移必须是 256 的倍数，取 512。 */
    private const val FRAME_SLOT_BYTES = 512

    /** 镜像里最多放多少段：再多就是给 shader 的循环加无用功，追踪也够不到。 */
    private const val MIRROR_RECORDS = 384

    /** 镜像三块缓冲合计的字节上限。世界加载时每帧都有新段进来，不设上限就会每帧传几百 MB。 */
    private const val MIRROR_BYTES = 128L * 1024 * 1024
    private val MIRROR_CAPACITIES = longArrayOf(256L shl 20, 64L shl 20, 8L shl 20)
    private const val HOT_MIRROR_LIMIT = 8
    private const val HOT_MIRROR_BYTES = 128L * 1024 * 1024

    /** 相机位移超过这么多方块就立刻重建，不等节流。 */
    private const val MIRROR_SLACK = 24f

    // 合光输出必须同时带 RENDER_ATTACHMENT：上屏走 blitTextureToTexture，它收尾把源切回
    // RENDER_TARGET，只带 STORAGE 的资源切到这个状态是非法的（Close 直接 E_INVALIDARG）。
    private const val OUTPUT_USAGE = D3D12GpuTexture.USAGE_STORAGE or GpuTexture.USAGE_RENDER_ATTACHMENT


    /** 几何池段数低于它就认为「世界是在光追关闭时载入的」，触发一次原版重建。 */

    /** tick 在客户端 tick 事件里跑（每秒 20 次）：60 次 = 3 秒还没有任何几何就报一次。 */
    private const val STARVED_TICKS = 60

    /** 派发心跳间隔（毫秒）：按时间而不是按帧，否则高帧率下每秒刷出好几行 INFO。 */
    private const val HEARTBEAT_MILLIS = 5000L

    /** 加速结构与追踪两段的 CPU 编码时长超过这个值就打一行分解（微秒）。 */
    private const val SLOW_FRAME_MICROS = 200_000L

    /** `renderScale` 缩到 25% 时目标高度只剩 40 像素，再低就没有降噪的余地了。 */

    /**
     * 只扫相机这么远的段（方块单位）。0 表示不裁，自检场景用 0；真实世界必须裁，否则求交次数爆炸。
     * 见 docs/rt/README.md
     */
    private const val TRACE_RADIUS = 96.0f

    /** 一帧的相机与光照参数；坐标是世界坐标（方块单位）。 */
    class Camera(
        val position: RtVec3,
        val right: RtVec3,
        val up: RtVec3,
        val forward: RtVec3,
        val tanHalfFovY: Float,
        val aspect: Float,
        val sunDir: RtVec3,
        val sunColor: RtVec3,
        val transform: D3D12RtCamera.Transform,
        val farPlane: Float,
        val worldIdentity: Any,
        val worldTime: Long,
        val frameNanos: Long,
        val cameraIdentity: Any? = null,
        val cameraMode: Any? = null,
        val fovSetting: Int? = null,
        )

    private class Radiance(
        val width: Int,
        val height: Int,
        val direct: Long,
        val indirect: Long,
        val aux: Long,
        val specular: Long,
        val specularGrazing: Long,
        val exposure: Long,
        val motion: Long,
        val packedNormalRoughness: Long,
        val viewZ: Long,
        val bytes: Long,
        val diffuseSignal: Long,
        val specularSignal: Long,
        val specularHitDistance: Long,
        val frameId: Long,
    )

    /** 一帧的 `WgaFrame`：上传堆缓冲，按 `FRAME_SLOT_BYTES` 分槽轮转（CBV 偏移必须 256 对齐）。 */
    private class Scene(val buffer: D3D12GpuBuffer) {
        fun close() {
            try { buffer.close() } catch (_: Throwable) { }
        }
    }

    private class Mirrors(
        val native: MCDR_ABI.RtGeometryOutput,
        val geometrySnapshot: D3D12RtGeometryPool.MirrorSlice,
        val worldIdentity: Any,
        val geometryEpoch: Int,
        var key: Long,
        val geometryRevision: Long,
        val materialRevision: Long,
        val materialHistoryRevision: Long,
        val recordCount: Int,
        val recordBytes: ByteBuffer,
        /** 顶点 / 索引缓冲的字节数：几何契约检查要拿它判记录里的偏移有没有越界。 */
        val vertexBytes: Long,
        val indexBytes: Long,
        val materialBytes: Long,
        val recordingIdentity: Any,
    ) {
        var committed = false
        val contentBytes: Map<Long, Int> = buildMap {
            for (entry in geometrySnapshot.contents) {
                for (content in listOf(entry.vertices, entry.indices, entry.quads)) {
                    put(content.identity, content.byteCount)
                }
            }
        }
        val identity: Long get() = native.snapshotId
        val vertices: Long get() = native.vertices
        val indices: Long get() = native.indices
        val materials: Long get() = native.materials
        val bytes: Long get() = native.byteCount

        fun close() {
            MCDR_ABI.D3D12ReleaseRtGeometrySnapshot(identity)
        }
    }

    @Volatile
    private var radiance: Radiance? = null

    @Volatile
    private var mirrors: Mirrors? = null

    @Volatile
    private var publishedMirrors: Mirrors? = null

    private val hotMirrors = linkedMapOf<Long, Mirrors>()

    /** 这一帧的辐亮度缓冲是否真的被派发写过；没写成的话合光会把过期内容铺满屏幕。 */
    private var frameValid = false

    /** 已经为补齐几何池触发过一次原版重建。 */
    private var observedGeometryEpoch = Int.MIN_VALUE
    private var reconciled = false

    private var capturedCamera: Camera? = null
    private var capturedCameraStamp = Long.MIN_VALUE

    private var accelLogged = false
    private var wgWarned = false
    private var debugPipeline = 0L
    private var blasPipeline = 0L
    @Volatile
    private var output: D3D12GpuTexture? = null

    @Volatile
    private var outputWidth = 0

    @Volatile
    private var outputHeight = 0

    @Volatile
    private var sceneIndex = 0

    @Volatile
    private var dispatches = 0L
    private var frameCounter = 0L
    /** 心跳节流：上次报时的时刻与当时的规模，规模变了要立刻报出来。 */
    private var heartbeatNanos = 0L
    private var heartbeatRecords = -1
    private var heartbeatTier: String? = null
    /** 连续多少个 tick 几何池还是空的（见 [STARVED_TICKS]）。 */
    private var starvedTicks = 0
    private var starvedReported = false

    /**
     * 上一帧的世界→裁剪矩阵（列入 [D3D12RtFrameConstants.write] 的 `g_prevViewProj`）。
     * 运动矢量、重投影、TAA 全读这一份：没人写它时 `g_prevViewProj` 恒等于当前帧，位移永远是 0，
     * 而且不会报任何错。见 docs/rt/README.md §19。
     */
    private val previousViewProjection = FloatArray(16)
    private val previousView = FloatArray(16)
    private val nrdMatrices = FloatArray(64)
    private val previousJitter = FloatArray(2)
    private val pendingViewProjection = FloatArray(16)
    private val pendingView = FloatArray(16)
    private val pendingJitter = FloatArray(2)
    private var pendingPreviousReady = false
    private var pendingSubmission: Any? = null
    private var pendingCompositeReady = false
    private data class PreparedFrame(
        val device: D3D12Device, val recordingIdentity: Any, val camera: Camera,
        val plan: D3D12RtPipelinePlan.Plan, val gbufferRevision: Long,
    )
    private var preparedFrame: PreparedFrame? = null
    @Volatile
    private var temporalResolveReady = false
    /** DLSS-D 的 `slSetConstants` 载荷：四个行主序矩阵 + 近/远/竖直视场/宽高比。 */
    private val slConstants = FloatArray(D3D12RtFrameConstants.SL_CONSTANTS_FLOATS)
    private val history = D3D12RtHistory()
    private var historyInvalidation = D3D12RtHistory.Invalidation(0, 0, 0)
    private var previousPlan: D3D12RtPipelinePlan.Plan? = null

    /** 合光只报一次成功行（真值表之外的形状变化靠它确认）。 */
    private val compositeRan = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 本帧 NRD 写好的辐亮度；为 null 时合光直接读三块缓冲。 */
    private var denoisedRadiance: Long? = null
    private var denoisedSpecular: Long? = null
    private var denoiseBytes = 0L
    private var upscaleBytes = 0L
    private var compositeBytes = 0L
    private var compositeImage: CompositeImage? = null
    private var denoisedIsReblur = false

    private var previousValid = false

    /** 光追链异常计数：只报前四次（见 reportFailure）。 */
    private val throwables = AtomicLong()
    private const val MAX_THROWABLES = 4L
    private var mirrorX = 0f
    private var mirrorY = 0f
    private var mirrorZ = 0f

    @Volatile
    private var failures = 0
    private var revDropped = 0
    private var scene: Scene? = null
    private var frameCbv = 0L
    private var frameCbvOffset = 0L
    private val frameConstants = ByteBuffer.allocateDirect(SCENE_BYTES).order(ByteOrder.LITTLE_ENDIAN)

    fun isActive(): Boolean = D3D12Settings.rtEnabled

    fun hasTemporalResolve(): Boolean {
        if (!D3D12Settings.rtEnabled) return false
        val window = Minecraft.getInstance().window
        if (window.width <= 0 || window.height <= 0) return false
        val plan = D3D12RtPipelinePlan.configured(window.width, window.height)
        return plan.enabled && plan.denoise
    }

    fun summary(): String = "rt frame: dispatches=${dispatches} records=${mirrors?.recordCount ?: 0} " +
        "size=${outputWidth}x${outputHeight}"

    /** 光追关掉后把 GPU 资源还回去；与材质表共用同一个 tick。 */
    fun tick() {
        D3D12RenderScale.reconcileRayTracing()
        observeWorld(Minecraft.getInstance().level)
        // 老配置里 mode 可能停在 OFF（只有 backend 被写过）：包是这两个键的来源，进入世界时对一次账。
        if (!reconciled) {
            reconciled = true
            if (D3D12ShaderPacks.reconcile()) {
                D3D12Settings.reload()
            }
        }
        if (!isActive()) {
            D3D12RtGeometryRecovery.clear()
            D3D12RtTrace.reportDisabled()
            D3D12RtStats.release()
            if (radiance != null || mirrors != null || output != null) {
                release()
            }
            return
        }
        // 光追开着却一段几何都没有：整条链没启动（最常见的是 G-Buffer 变体没建起来）。
        // 只在已经进入世界、且空转够久时报一次——菜单里 tick 照跑，那时本来就没有几何。
        if (D3D12RtGeometryPool.recordCount() > 0 || Minecraft.getInstance().level == null) {
            starvedTicks = 0
        } else if (!starvedReported && ++starvedTicks >= STARVED_TICKS) {
            starvedReported = true
            IllusionLogger.warnMessage(
                "rt: no geometry after ${STARVED_TICKS / 20}s, gbuffer replay never ran")
        }
    }

    fun release() = synchronized(frameLock) {
        D3D12RtSignalStats.release()
        temporalResolveReady = false
        scene?.close()
        scene = null
        val retainedMirrors = (listOfNotNull(mirrors, publishedMirrors) + hotMirrors.values).distinct()
        val currentOutput = output
        radiance = null
        mirrors = null
        publishedMirrors = null
        hotMirrors.clear()
        output = null
        frameValid = false
        previousPlan = null
        previousValid = false
        pendingPreviousReady = false
        pendingSubmission = null
        pendingCompositeReady = false
        preparedFrame = null
        capturedCamera = null
        capturedCameraStamp = Long.MIN_VALUE
        history.clear()
        outputWidth = 0
        outputHeight = 0
        // 加速结构引用镜像缓冲的 GPU 地址，必须在镜像被销毁之前释放。
        D3D12RtBlas.release()
        D3D12RtNativeFrame.release()
        frameCbv = 0L
        frameCbvOffset = 0L
        retainedMirrors.forEach(Mirrors::close)
        currentOutput?.close()
        denoisedRadiance = null
        denoisedSpecular = null
        denoiseBytes = 0L
        upscaleBytes = 0L
        compositeBytes = 0L
        compositeImage = null
        // 三个都是 compute 管线（D3D12CreateComputePipeline*），原生侧是 Dx12Object*，不是 Dx12Pipeline*：
        // 用 D3D12DestroyPipeline 释放等于按错的类型 delete，析构里读到别的对象的字段 → 直接 AV 崩进程。
        // 图形/mesh 管线（Dx12Pipeline*）才走 D3D12DestroyPipeline。
        if (debugPipeline != 0L) {
            MCDR_ABI.ID3D12DestroyResource(debugPipeline)
            debugPipeline = 0L
        }
        if (blasPipeline != 0L) {
            MCDR_ABI.ID3D12DestroyResource(blasPipeline)
            blasPipeline = 0L
        }
        // 追踪管线也一起还回去：换包之后重新打开光追就会按新包的源码重编。
        D3D12RtTrace.releasePipeline()
    }

    /** 一帧的相机；读不到（还没初始化）时返回 null，调用方跳过这次派发。 */
    fun currentCamera(): Camera? =
        capturedCamera.takeIf { capturedCameraStamp == D3D12Temporal.frameTimestampNanos }

    fun invalidateOutput() = synchronized(frameLock) {
        frameValid = false
        preparedFrame = null
        compositeImage = null
    }

    fun captureProjection(data: ByteBuffer) {
        invalidateOutput()
        val client = Minecraft.getInstance()
        capturedCamera = null
        capturedCameraStamp = D3D12Temporal.frameTimestampNanos
        if (data.remaining() != 64) return
        val world = client.level ?: return
        val level = client.gameRenderer.gameRenderState().levelRenderState
        val state = level.cameraRenderState
        if (!state.initialized) return
        val projection = FloatArray(16)
        data.duplicate().order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(projection)
        if (projection.any { !it.isFinite() }) return
        val transform = D3D12RtCamera.resolve(
            RtVec3(state.pos.x.toFloat(), state.pos.y.toFloat(), state.pos.z.toFloat()),
            state.viewRotationMatrix,
            state.projectionMatrix,
            Matrix4f().set(projection),
        )
        capturedCamera = Camera(
            position = transform.position,
            right = transform.right,
            up = transform.up,
            forward = transform.forward,
            tanHalfFovY = 1f / state.projectionMatrix.m11(),
            aspect = state.projectionMatrix.m11() / state.projectionMatrix.m00(),
            sunDir = D3D12RtCamera.sunDirection(level.skyRenderState.sunAngle),
            sunColor = SUN_COLOR,
            transform = transform,
            farPlane = state.depthFar,
            worldIdentity = world,
            worldTime = world.defaultClockTime,
            frameNanos = capturedCameraStamp,
            cameraIdentity = client.cameraEntity,
            cameraMode = client.options.cameraType,
            fovSetting = client.options.fov().get(),
        )
    }

    /**
     * 主光线与 diffuse/specular 弹射，写进五张 raw 缓冲。返回 false 表示未派发。
     * 见 docs/rt/README.md
     */
    /** 我们持有的显存峰值（六类基线之一）：镜像、相机常量、辐亮度、G-Buffer、HDR 输出。 */
    private fun vramBytes(): Long {
        var bytes = 0L
        bytes += MCDR_ABI.D3D12GetRtGeometryMemory()?.first() ?: (mirrors?.bytes ?: 0L)
        if (scene != null) {
            bytes += FRAME_SLOT_BYTES.toLong() * SCENE_RING
        }
        radiance?.let { bytes += it.bytes }
        bytes += D3D12RtGBuffer.byteCount()
        output?.let { bytes += it.getWidth(0).toLong() * it.getHeight(0) * HDR_BYTES }
        bytes += upscaleBytes
        bytes += compositeBytes
        bytes += denoiseBytes
        return bytes
    }

    private fun updatePlan(plan: D3D12RtPipelinePlan.Plan) {
        if (previousPlan == plan) return
        D3D12RtNativeFrame.resetDenoisers()
        previousPlan = plan
    }

    /** 光追派发：CPU 编码耗时与派发次数进基线统计，真正的实现在 [dispatchImpl]。 */
    internal fun prepareFrame(
        device: D3D12Device?, encoder: D3D12CommandEncoderBackend, camera: Camera,
        plan: D3D12RtPipelinePlan.Plan,
    ) = synchronized(frameLock) {
        if (device == null) return@synchronized
        val replay = D3D12RtGBuffer.targets() ?: return@synchronized
        if (replay.frameNanos != camera.frameNanos || replay.width != plan.gbufferWidth ||
            replay.height != plan.gbufferHeight) return@synchronized
        val transform = camera.transform
        val frozenCamera = Camera(camera.position, camera.right, camera.up, camera.forward, camera.tanHalfFovY,
            camera.aspect, camera.sunDir, camera.sunColor, D3D12RtCamera.Transform(transform.position,
                transform.offset, transform.right, transform.up, transform.forward,
                transform.view.copyOf(), transform.projection.copyOf()), camera.farPlane,
            camera.worldIdentity, camera.worldTime, camera.frameNanos,
            camera.cameraIdentity, camera.cameraMode, camera.fovSetting)
        preparedFrame = PreparedFrame(device, encoder.recordingIdentity, frozenCamera, plan, replay.revision)
    }

    private fun dispatch(

        device: D3D12Device?,
        encoder: D3D12CommandEncoderBackend?,
        camera: Camera?,
        plan: D3D12RtPipelinePlan.Plan,
        source: D3D12GpuTexture,
        debugMode: Int,
    ): Boolean {
        val started = System.nanoTime()
        try {
            return synchronized(frameLock) {
                var dispatched = false
                try {
                    dispatchImpl(device, encoder, camera, plan, source, debugMode).also { dispatched = it }
                } finally {
                    if (!dispatched) {
                        frameValid = false
                        temporalResolveReady = false
                        cancelPendingSubmission()
                    }
                }
            }
        } finally {
            D3D12RtStats.onCpuEncode(System.nanoTime() - started)
            if (frameValid) {
                D3D12RtStats.onDispatches(1)
                D3D12RtStats.onVram(vramBytes())
            }
        }
    }

    private fun dispatchImpl(
        device: D3D12Device?,
        encoder: D3D12CommandEncoderBackend?,
        camera: Camera?,
        plan: D3D12RtPipelinePlan.Plan,
        source: D3D12GpuTexture,
        debugMode: Int,
    ): Boolean {
        frameValid = false
        denoisedRadiance = null
        val width = plan.displayWidth
        val height = plan.displayHeight
        // 这几种提前返回以前是静默的：屏幕停在原版画面上、日志一个字都没有，
        // 和「光追根本没开」分不出来。每种原因各报一次。
        if (device == null) return dispatchSkip("no device")
        if (encoder == null) return dispatchSkip("no encoder")
        if (camera == null) return dispatchSkip("no camera")
        if (width <= 0 || height <= 0) return dispatchSkip("empty target ${width}x$height")
        if (!wgWarned && D3D12Settings.rtBackend == RtBackend.WG.ordinal) {
            wgWarned = true
            IllusionLogger.warnMessage("rt: WG unavailable, using DXR")
        }
        if (!D3D12RtNativeFrame.prepare()) return dispatchSkip("DXR unavailable")
        val mirror = mirrorsFor(device, encoder, camera) ?: return false
        val traceWidth = plan.traceWidth
        val traceHeight = plan.traceHeight
        encoder.ensureListOpen()
        updatePlan(plan)
        val frameGeneration = FrameGeneration.activeBackend().toLong() shl 32 or
            (FrameGeneration.mode().toLong() shl 16) or FrameGeneration.frames().toLong()
        val jitter = D3D12Temporal.frameJitter(camera.frameNanos)
        val accelStart = System.nanoTime()
        val coverage = D3D12RtGeometryPool.coverageNear(mirrorX, mirrorY, mirrorZ, TRACE_RADIUS + MIRROR_SLACK)
        val versions = coverage.versions
        if (versions.epoch != mirror.geometryEpoch) return dispatchSkip("geometry epoch changed")
        val sceneFrameId = D3D12Temporal.frameIdentity + 1L
        val accelerationReady = D3D12RtBlas.prepareCoverage(
            encoder.nativeHandle(), mirror.geometryEpoch, mirror.identity, versions.records, versions.revision,
            sceneFrameId, coverage.nodes, versions.outputs,
        )
        if (!accelerationReady && D3D12RtBlas.wasGeometryRecordingCancelled()) {
            discardMirrors()
            return dispatchSkip("geometry recording cancelled")
        }
        val activeMirror = when {
            D3D12RtBlas.activeMirrorId() == mirror.identity -> {
                val old = publishedMirrors
                publishedMirrors = mirror
                retireMirrors(old)
                mirror
            }
            publishedMirrors?.identity == D3D12RtBlas.activeMirrorId() -> publishedMirrors
            else -> null
        } ?: return dispatchSkip("acceleration structure pending")
        val accelMicros = (System.nanoTime() - accelStart) / 1000L
        val accelView = D3D12RtBlas.view()
        if (accelView == 0L) return dispatchSkip("acceleration structure unavailable")
        pendingSubmission = encoder.recordingIdentity
        pendingCompositeReady = false
        historyInvalidation = history.begin(D3D12RtHistory.Snapshot(
            plan, camera.worldIdentity, device, D3D12RtGBuffer.targets()?.resourceIdentity,
            activeMirror.geometryEpoch, D3D12RtBlas.sceneRevision(), activeMirror.materialHistoryRevision,
            RtBackend.DXR.ordinal,
            frameGeneration, camera.worldTime, camera.frameNanos, D3D12Temporal.frameIdentity,
            camera.position, camera.forward, camera.up,
            camera.transform.projection,
            jitter.enabled,
            camera.cameraIdentity, camera.cameraMode, camera.fovSetting,
        ))
        if (historyInvalidation.resetsHistory) {
            previousValid = false
            if (D3D12Settings.rtInvalidationLog) {
                IllusionLogger.infoMessage("rt history: reset=0x${historyInvalidation.reasons.toString(16)} " +
                    "frame=${historyInvalidation.frame}")
                history.cameraCutDetail?.let { IllusionLogger.infoMessage("rt camera: reset=$it") }
            }
        }
        if (!writeScene(camera, traceWidth, traceHeight, activeMirror.recordCount)) return false
        if (!accelLogged) {
            accelLogged = true
            IllusionLogger.infoMessage("rt: DXR active (${D3D12RtBlas.instances()} instances)")
        }
        val exposureReset = historyInvalidation.reasons and
            (D3D12RtHistory.InvalidationReason.GEOMETRY.mask or
                D3D12RtHistory.InvalidationReason.MATERIAL.mask).inv() != 0
        val transaction = D3D12RtNativeFrame.recordTransaction(
            encoder.nativeHandle(), frameCounter, activeMirror.geometryEpoch, D3D12RtBlas.sceneRevision(),
            activeMirror.materialRevision, activeMirror.identity, traceWidth, traceHeight, D3D12RtBlas.identity(), frameConstants,
            camera.frameNanos, exposureReset, coverage,
            plan, historyInvalidation, nrdMatrices, slConstants, source.handle(), debugMode,
            slConstants[68] - slConstants[70], slConstants[69] - slConstants[71],
            slConstants[68], slConstants[69],
        )
        val traceMicros = (System.nanoTime() - accelStart) / 1000L - accelMicros
        // 这一帧慢到不像话时留一行分解：卡死前最后一条「慢帧」直接指出钱花在哪一段。
        if (accelMicros + traceMicros > SLOW_FRAME_MICROS) {
            IllusionLogger.infoMessage(
                "rt frame: slow accel=${accelMicros / 1000}ms trace=${traceMicros / 1000}ms " +
                    "records=${activeMirror.recordCount} inst=${D3D12RtBlas.instances()}"
            )
        }
        if (transaction == null || transaction.trace.frameId != frameCounter ||
            transaction.trace.snapshotId != activeMirror.identity || transaction.composite.frameId != frameCounter ||
            transaction.composite.snapshotId != activeMirror.identity || transaction.composite.diffuse == 0L ||
            transaction.composite.dispatchCount != 1) {
            fail("native frame rejected")
            return false
        }
        val recorded = transaction.trace
        val frame = Radiance(traceWidth, traceHeight, recorded.direct, recorded.indirect, recorded.aux,
            recorded.specular, recorded.specularGrazing, recorded.exposure,
            recorded.motion, recorded.packedNormalRoughness, recorded.viewZ, recorded.byteCount,
            recorded.diffuseSignal, recorded.specularSignal, recorded.specularHitDistance, recorded.frameId)
        radiance = frame
        frameCbv = recorded.constants
        frameCbvOffset = 0L
        D3D12RtStats.onDispatches((if (plan.denoise) 5 else 2) + transaction.denoise.dispatchCount +
            transaction.upscale.dispatchCount + transaction.composite.dispatchCount)
        val tier = "dxr"
        D3D12RtStats.setTier(tier)
        D3D12RtStats.onTraceDispatched(traceWidth, traceHeight, activeMirror.recordCount)
        D3D12RtStats.acceptNativeTimestamps(transaction.timestampMask)
        val reconstruction = transaction.upscale.takeIf { it.diffuse != 0L } ?: transaction.denoise
        denoiseBytes = transaction.denoise.byteCount
        upscaleBytes = transaction.upscale.byteCount
        denoisedRadiance = reconstruction.diffuse.takeIf { it != 0L }
        denoisedSpecular = reconstruction.specular.takeIf { it != 0L }
        denoisedIsReblur = reconstruction.encoding == 1
        if (plan.denoise && denoisedRadiance == null) evaluationFailure()
        if (denoisedRadiance != null) D3D12RtGuide.noteOnce(recorded.motion)
        temporalResolveReady = denoisedRadiance != null && !denoisedIsReblur &&
            traceWidth == plan.renderWidth && traceHeight == plan.renderHeight &&
            (plan.track == RtDenoise.RR || plan.superResolution)
        compositeBytes = transaction.composite.byteCount
        compositeImage = CompositeImage(transaction.composite.diffuse, source.getWidth(0), source.getHeight(0), frame.frameId)
        pendingCompositeReady = true
        frameValid = true
        if (compositeRan.compareAndSet(false, true)) {
            val flag = if (transaction.composite.encoding != 0) 1 else 0
            IllusionLogger.infoMessage("rt composite: ${source.getWidth(0)}x${source.getHeight(0)} mode=$debugMode denoise=$flag")
        }
        if (D3D12Settings.rtInvalidationLog) {
            val albedo = D3D12RtGBuffer.texture(D3D12RtGBuffer.ALBEDO)?.handle ?: 0L
            val encoding = if (denoisedRadiance == null) 0 else if (denoisedIsReblur) 1 else 2
            D3D12RtSignalStats.record(encoder.nativeHandle(), D3D12RtSignalStats.Frame(
                frame.frameId, activeMirror.identity, mirror.identity, activeMirror.geometryEpoch,
                D3D12RtBlas.sceneRevision(), versions.revision, historyInvalidation.frame,
                historyInvalidation.reasons, plan.track.ordinal, encoding, plan.upscaler.id,
                temporalResolveReady, camera.position.x, camera.position.y, camera.position.z,
                D3D12RtBlas.diagnosticState(), sceneFrameId,
            ), traceWidth, traceHeight, frame.direct, frame.indirect, albedo)
        } else {
            D3D12RtSignalStats.release()
        }
        val count = dispatches + 1
        dispatches = count
        // 真值表按显示分辨率算，表变了才打一行（见 docs/rt/README.md §18）。
        D3D12RtPipelinePlan.note(plan)
        D3D12RtNrd.inspectOnce()
        lastTier = tier
        lastDispatch = "tier=$tier records=${activeMirror.recordCount} " +
            "inst=${D3D12RtBlas.instances()} trace=${traceWidth}x$traceHeight"
        if (count == 1L) {
            // 放在 lastDispatch 之后：这类报告的意义就是把「这一帧到底走了哪条路」带出去。
            D3D12RtTrace.reportPending()
        }
        // 规模变了或间隔到了才报：高帧率下按帧报会把 INFO 刷成每帧一行。
        // 设备移除时最后一条心跳就是卡住那一刻的规模（记录数、三角数、追踪尺寸）。
        val now = System.nanoTime()
        val scaleChanged = activeMirror.recordCount != heartbeatRecords || tier != heartbeatTier
        val cooled = now - heartbeatNanos >= HEARTBEAT_MILLIS * 1_000_000L
        if (count == 1L || cooled || (scaleChanged && now - heartbeatNanos >= 1_000_000_000L)) {
            heartbeatNanos = now
            heartbeatRecords = activeMirror.recordCount
            heartbeatTier = tier
            IllusionLogger.infoMessage("rt frame: dispatch ${traceWidth}x$traceHeight " +
                "of ${width}x$height records=${activeMirror.recordCount} tier=$tier " +
                "inst=${D3D12RtBlas.instances()} " +
                "cam=${camera.position.x.toInt()},${camera.position.y.toInt()},${camera.position.z.toInt()} " +
                "triangles=${D3D12RtGeometryPool.triangleCount()} radius=$TRACE_RADIUS")
        }
        return true
    }

    /**
     * 合光：`albedo * (direct + indirect) + emissive` 写进一张 HDR 纹理并返回它，供调用方 blit 上屏。
     * 见 docs/rt/README.md
     */
    /* 资源在 tick（客户端线程）里释放、在 dispatch/composite（渲染线程）里使用：两边必须互斥，
       否则关光追的一瞬间会把正在写的目标释放掉（设备移除）。 */
    private val frameLock = Any()

    /** 合光没跑成的原因各报一次：静默返回会让「光追看不见」没有任何线索。 */
    private val dispatchSkips = java.util.Collections.synchronizedSet(HashSet<String>())

    /** 最近一次派发用的档位与实例数：自检报告里带出去，省得再跑一次看日志。 */
    private var lastDispatch = "none"

    /** 最近一次真正派发走的档：报告里的 `tier` 要用它，而不是「配置里写着什么」。 */
    private var lastTier: String? = null

    fun lastDispatchTier(): String? = lastTier

    fun lastDispatchSummary(): String = lastDispatch

    private fun dispatchSkip(reason: String): Boolean {
        if (dispatchSkips.add(reason)) IllusionLogger.warnMessage("rt dispatch: skipped, $reason")
        // 这一帧没有产出：合光必须停手，否则会把上一张旧图按 1:1 铺到主目标上。
        frameValid = false
        return false
    }

    private val compositeSkips = java.util.Collections.synchronizedSet(HashSet<String>())

    data class CompositeImage(val handle: Long, val width: Int, val height: Int, val frameId: Long)

    private fun compositeSkip(reason: String): CompositeImage? {
        if (compositeSkips.add(reason)) {
            IllusionLogger.warnMessage("rt composite: skipped, $reason")
        }
        return null
    }

    @JvmOverloads
    fun composite(
        encoder: D3D12CommandEncoderBackend?,
        source: D3D12GpuTexture?,
        debugMode: Int = 0,
    ): CompositeImage? = synchronized(frameLock) {
        compositeImage = null
        if (encoder == null) return compositeSkip("no encoder")
        if (source == null) return compositeSkip("missing source")
        val prepared = preparedFrame ?: return compositeSkip("no prepared frame")
        val width = source.getWidth(0)
        val height = source.getHeight(0)
        if (width <= 0 || height <= 0) return compositeSkip("empty target")
        val replay = D3D12RtGBuffer.targets()
        if (prepared.recordingIdentity !== encoder.recordingIdentity || replay?.revision != prepared.gbufferRevision ||
            replay.frameNanos != prepared.camera.frameNanos ||
            width != prepared.plan.displayWidth || height != prepared.plan.displayHeight) {
            preparedFrame = null
            return compositeSkip("prepared frame mismatch")
        }
        encoder.ensureListOpen()
        preparedFrame = null
        if (!dispatch(prepared.device, encoder, prepared.camera, prepared.plan, source, debugMode))
            return compositeSkip("native transaction rejected")
        return compositeImage
    }

    fun blitComposite(encoder: D3D12CommandEncoderBackend, source: CompositeImage,
                      destination: D3D12GpuTexture): Boolean = synchronized(frameLock) {
        if (!frameValid || pendingSubmission !== encoder.recordingIdentity ||
            compositeImage != source || radiance?.frameId != source.frameId ||
            source.width != destination.getWidth(0) || source.height != destination.getHeight(0)) return false
        encoder.ensureListOpen()
        return MCDR_ABI.dx12BlitTextureToTexture(encoder.nativeHandle(), source.handle, destination.handle())
    }

    /**
     * 调试图：把 [source] 的第 [channel] 个通道拉成灰度写进一张 HDR 目标，供 `debugView` 直接铺屏。
     * 粗糙度这类单通道数据不能直接 blit（blit 只采三通道），走这里过一遍 compute。
     * 见 docs/rt/README.md
     */
    fun debugChannel(
        encoder: D3D12CommandEncoderBackend?,
        source: D3D12RtGBuffer.Image?,
        channel: Int,
        width: Int,
        height: Int,
    ): D3D12GpuTexture? = synchronized(frameLock) {
        // `channel` 0..3 = 取单个通道铺灰；4 = 法线档（0.5 + 0.5 * rgb），见 D3D12RtDebugShader。
        if (encoder == null || source == null || !D3D12RtGBuffer.isCurrent(source) || channel !in 0..4) return null
        val started = System.nanoTime()
        val device = encoder.device ?: return null
        if (width <= 0 || height <= 0) return null
        val pipeline = ensureDebugPipeline() ?: return null
        val target = ensureOutput(device, width, height) ?: return null
        encoder.ensureListOpen()
        // 常量槽里 g_misc.y 每帧都被追踪写回 0，这里必须自己打上通道号，否则永远按通道 0 取。
        patchDebugMode(device, channel)
        val debugSrvs = longArrayOf(source.handle)
        val debugUavs = longArrayOf(target.handle())
        val constants = intArrayOf(0)
        val ok = MCDR_ABI.D3D12DispatchComputeEx(
            encoder.nativeHandle(), pipeline, debugSrvs, debugUavs, constants, frameCbv, frameCbvOffset,
            (width + THREADS - 1) / THREADS, (height + THREADS - 1) / THREADS, 1,
        )
        if (!ok) {
            fail("debug view failed: ${MCDR_ABI.D3D12LastError()}")
            return null
        }
        D3D12RtStats.onCpuEncode(System.nanoTime() - started)
        D3D12RtStats.onDispatches(1)
        return target
    }

    /**
     * BLAS 实例线框（`debugView = BLAS`）：把 TLAS 里每个实例的段盒画成彩色棱线，直接叠在 [target] 上。
     *
     * 只画线不拷底图，所以目标就是合成结果那张纹理；前一段派发也写过它，
     * 派发前必须插一条 UAV 屏障，否则线框会读到旧像素。见 docs/rt/README.md §14.6
     */
    fun debugBlas(
        encoder: D3D12CommandEncoderBackend?,
        target: CompositeImage?,
    ): CompositeImage? = synchronized(frameLock) {
        if (encoder == null || target == null || frameCbv == 0L) return null
        if (!frameValid || pendingSubmission !== encoder.recordingIdentity ||
            compositeImage != target || target.frameId != radiance?.frameId) return null
        val instances = D3D12RtBlas.instances()
        val table = D3D12RtBlas.instanceTable()
        if (instances <= 0 || table == 0L) return null
        val started = System.nanoTime()
        val pipeline = ensureBlasPipeline() ?: return null
        encoder.ensureListOpen()
        MCDR_ABI.D3D12Barrier(
            encoder.nativeHandle(),
            listOf(MCDR_ABI.BarrierDesc(target.handle, 0, 0, MCDR_ABI.BarrierKind.UAV)),
        )
        val threads = instances * BLAS_EDGES_PER_BOX
        val blasSrvs = longArrayOf(table)
        val blasUavs = longArrayOf(target.handle)
        val ok = MCDR_ABI.D3D12DispatchComputeEx(
            encoder.nativeHandle(), pipeline, blasSrvs, blasUavs, intArrayOf(instances),
            frameCbv, frameCbvOffset, (threads + BLAS_THREADS - 1) / BLAS_THREADS, 1, 1,
        )
        if (!ok) {
            fail("blas view failed: ${MCDR_ABI.D3D12LastError()}")
            return null
        }
        D3D12RtStats.onCpuEncode(System.nanoTime() - started)
        D3D12RtStats.onDispatches(1)
        return target
    }

    /** 把 `g_misc.y` 打成 [mode]，其余常量不动（见 D3D12RtFrameConstants.patchDebugMode）。 */
    private fun patchDebugMode(device: D3D12Device, mode: Int) {
        val current = sceneFor(device) ?: return
        try {
            val offset = ((sceneIndex + 1) % SCENE_RING * FRAME_SLOT_BYTES).toLong()
            sceneIndex = (sceneIndex + 1) % SCENE_RING
            current.buffer.map(offset, SCENE_BYTES.toLong(), false, true).use { view ->
                view.data().put(frameConstants.duplicate())
                D3D12RtFrameConstants.patchDebugMode(view.data(), mode)
            }
            frameCbv = current.buffer.handle()
            frameCbvOffset = offset
        } catch (t: Throwable) {
            fail("debug mode patch failed: ${t.message}")
        }
    }

    private fun ensureDebugPipeline(): Long? {
        val cached = debugPipeline
        if (cached != 0L) return cached
        val dxil = MCDR_ABI.D3D12CompileShaderDxc(
            D3D12RtDebugShader.build(), D3D12RtDebugShader.ENTRY, "cs_6_8")
        if (dxil == null) {
            fail("debug view compile failed: ${MCDR_ABI.D3D12LastError()}")
            return null
        }
        val handle = MCDR_ABI.D3D12CreateComputePipelineEx(
            dxil, D3D12RtDebugShader.SRV_COUNT, D3D12RtDebugShader.UAV_COUNT)
        if (handle == 0L) {
            fail("debug view pipeline failed: ${MCDR_ABI.D3D12LastError()}")
            return null
        }
        debugPipeline = handle
        return handle
    }

    private fun ensureBlasPipeline(): Long? {
        val cached = blasPipeline
        if (cached != 0L) return cached
        val dxil = MCDR_ABI.D3D12CompileShaderDxc(
            D3D12RtDebugShader.buildBlas(), D3D12RtDebugShader.BLAS_ENTRY, "cs_6_8")
        if (dxil == null) {
            fail("blas view compile failed: ${MCDR_ABI.D3D12LastError()}")
            return null
        }
        val handle = MCDR_ABI.D3D12CreateComputePipelineEx(
            dxil, D3D12RtDebugShader.BLAS_SRV_COUNT, D3D12RtDebugShader.BLAS_UAV_COUNT)
        if (handle == 0L) {
            fail("blas view pipeline failed: ${MCDR_ABI.D3D12LastError()}")
            return null
        }
        blasPipeline = handle
        return handle
    }

    private fun ensureOutput(device: D3D12Device, width: Int, height: Int): D3D12GpuTexture? {
        val current = output
        if (current != null && outputWidth == width && outputHeight == height) return current
        val created = try {
            device.createTexture("raytracing-hdr", OUTPUT_USAGE, GpuFormat.RGBA16_FLOAT, width, height, 1, 1)
                as? D3D12GpuTexture
                ?: throw IllegalStateException("raytracing-hdr allocate failed (${MCDR_ABI.D3D12LastError()})")
        } catch (t: Throwable) {
            fail("hdr allocate failed: ${t.message}")
            return null
        }
        output = created
        outputWidth = width
        outputHeight = height
        current?.close()
        IllusionLogger.infoMessage("rt: hdr ${width}x$height")
        return created
    }

    private fun mirrorsFor(device: D3D12Device, encoder: D3D12CommandEncoderBackend, camera: Camera): Mirrors? {
        frameCounter++
        val geometryEpoch = observeWorld(camera.worldIdentity)
        val geometryRevision = D3D12RtGeometryPool.revision()
        val materialRevision = D3D12RtMaterialTable.revision()
        val materialHistoryRevision = D3D12RtMaterialTable.historyRevision()
        val revisionKey = geometryRevision * 1_000_003L + materialRevision
        val current = mirrors?.takeIf {
            it.worldIdentity === camera.worldIdentity && it.geometryEpoch == geometryEpoch
        }
        if (current == null && mirrors != null) {
            discardMirrors()
        }
        val moved = abs(camera.position.x - mirrorX) + abs(camera.position.y - mirrorY) +
            abs(camera.position.z - mirrorZ) > MIRROR_SLACK
        if (current != null && current.key == revisionKey && !moved) return current
        // 只镜像相机附近的一小片：整池上传在世界加载时会每帧传几百 MB，直接把画面卡死。
        val packingStartedAt = System.nanoTime()
        val slice = D3D12RtGeometryPool.mirrorNear(
            camera.position.x, camera.position.y, camera.position.z,
            TRACE_RADIUS + MIRROR_SLACK, MIRROR_RECORDS, MIRROR_BYTES,
        ) ?: run {
            discardMirrors()
            return null
        }
        val packingNanos = System.nanoTime() - packingStartedAt
        if (slice.geometryEpoch != geometryEpoch) return null
        if (current != null && current.geometrySnapshot === slice &&
            current.materialRevision == materialRevision) {
            current.key = revisionKey
            mirrorX = camera.position.x
            mirrorY = camera.position.y
            mirrorZ = camera.position.z
            return current
        }
        val materials = if (current?.materialRevision == materialRevision) null else D3D12RtMaterialTable.entryBytes()
        if (materials != null && materials.remaining() <= 0) return null
        val key = revisionKey
        val startedAt = System.nanoTime()
        val recordBytes = slice.records.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        encoder.ensureListOpen()
        val reusedContent = buildSet {
            if (current != null) {
                for (source in listOfNotNull(current, publishedMirrors) + hotMirrors.values) {
                    if (source.worldIdentity === camera.worldIdentity && source.geometryEpoch == geometryEpoch &&
                        source.native.vertices == current.native.vertices &&
                        source.native.indices == current.native.indices && source.native.quads == current.native.quads &&
                        (source.committed || source.recordingIdentity === encoder.recordingIdentity)) {
                        addAll(source.contentBytes.keys)
                    }
                }
            }
        }
        val contents = slice.contents.map { entry ->
            listOf(entry.vertices, entry.indices, entry.quads).map { content ->
                MCDR_ABI.RtGeometryContent(content.identity, content.byteCount.toLong(),
                    content.bytes.takeUnless { content.identity in reusedContent })
            }
        }
        val created = try {
            val native = MCDR_ABI.D3D12CreateRtGeometryDelta(encoder.nativeHandle(),
                slice.geometryEpoch.toLong(), slice.geometryRevision, materialRevision,
                current?.identity ?: 0L, current?.takeIf { materials == null }?.identity ?: 0L,
                slice.records, contents, materials, MIRROR_CAPACITIES)
                ?: throw IllegalStateException(MCDR_ABI.D3D12LastError())
            Mirrors(
                native = native,
                geometrySnapshot = slice,
                worldIdentity = camera.worldIdentity,
                geometryEpoch = slice.geometryEpoch,
                key = key,
                geometryRevision = slice.geometryRevision,
                materialRevision = materialRevision,
                materialHistoryRevision = materialHistoryRevision,
                recordCount = slice.recordCount,
                recordBytes = recordBytes,
                vertexBytes = slice.vertexBytes.toLong(),
                indexBytes = slice.indexBytes.toLong(),
                materialBytes = materials?.remaining()?.toLong() ?: checkNotNull(current).materialBytes,
                recordingIdentity = encoder.recordingIdentity,
            )
        } catch (t: Throwable) {
            if (t.message?.endsWith("rt geometry: arena capacity exhausted") == true ||
                t.message?.endsWith("rt geometry: memory budget exhausted") == true ||
                t.message?.endsWith("rt geometry: upload budget exhausted") == true) {
                clearHotMirrors()
                if (current != null && current.materialRevision == materialRevision) return current
            }
            if (t.message?.endsWith("rt geometry: reuse recording cancelled") == true) {
                discardMirrors()
            }
            fail("mirror upload failed: ${t.message}")
            return null
        }
        val elapsedNanos = System.nanoTime() - startedAt
        val previous = mirrors
        mirrors = created
        mirrorX = camera.position.x
        mirrorY = camera.position.y
        mirrorZ = camera.position.z
        retireMirrors(previous)
        revDropped++
        val line = "RT mirror: frame=${D3D12Temporal.frameIdentity + 1L} records=${slice.recordCount} " +
            "pack=${packingNanos / 1_000_000} ms upload=${elapsedNanos / 1_000_000} ms"
        if (revDropped == 1) IllusionLogger.infoMessage(line) else IllusionLogger.debugMessage(line)
        val stagedBytes = contents.flatten().sumOf { it.bytes?.takeUnless(ByteBuffer::isDirect)?.remaining()?.toLong() ?: 0L }
        IllusionLogger.debugMessage("RT upload: payload=${created.native.uploadedBytes} staged=$stagedBytes")
        return created
    }

    private fun retireMirrors(previous: Mirrors?) {
        if (previous == null || previous === mirrors || previous === publishedMirrors) return
        if (!previous.committed) {
            previous.close()
            return
        }
        hotMirrors[previous.identity] = previous
        while (hotMirrors.size > HOT_MIRROR_LIMIT || hotMirrorBytes() > HOT_MIRROR_BYTES) {
            val oldest = hotMirrors.entries.first()
            hotMirrors.remove(oldest.key)
            oldest.value.close()
        }
    }

    private fun hotMirrorBytes(): Long {
        val contents = mutableMapOf<Long, Int>()
        val materials = mutableMapOf<Long, Long>()
        var records = 0L
        for (mirror in hotMirrors.values) {
            contents.putAll(mirror.contentBytes)
            materials[mirror.native.materials] = mirror.materialBytes
            records += mirror.recordBytes.remaining() * 3L
        }
        return records + contents.values.sumOf(Int::toLong) + materials.values.sum()
    }

    private fun clearHotMirrors() {
        val retained = hotMirrors.values.toList()
        hotMirrors.clear()
        retained.forEach(Mirrors::close)
    }

    private fun observeWorld(worldIdentity: Any?): Int = synchronized(frameLock) {
        D3D12SectionGeometry.observeLevel(worldIdentity)
        val epoch = D3D12SectionGeometry.epoch()
        if (observedGeometryEpoch != epoch) {
            D3D12RtGeometryRecovery.clear()
            observedGeometryEpoch = epoch
            starvedTicks = 0
            starvedReported = false
            frameValid = false
            discardMirrors()
            history.invalidate(D3D12RtHistory.InvalidationReason.WORLD)
        }
        epoch
    }

    private fun discardMirrors() {
        temporalResolveReady = false
        D3D12RtBlas.invalidateGeometry()
        val retained = (listOfNotNull(mirrors, publishedMirrors) + hotMirrors.values).distinct()
        mirrors = null
        publishedMirrors = null
        hotMirrors.clear()
        retained.forEach(Mirrors::close)
        previousValid = false
    }

    private fun sceneFor(device: D3D12Device): Scene? {
        scene?.let { return it }
        return try {
            val created = Scene(
                buffer = device.createBuffer(
                    Supplier { "raytracing-frame-constants" }, GpuBuffer.USAGE_MAP_WRITE,
                    (FRAME_SLOT_BYTES * SCENE_RING).toLong(),
                ) as? D3D12GpuBuffer
                    ?: throw IllegalStateException("raytracing-frame-constants allocate failed (${MCDR_ABI.D3D12LastError()})"),
            )
            scene = created
            created
        } catch (t: Throwable) {
            fail("scene buffer failed: ${t.message}")
            null
        }
    }

    private fun evaluationFailure(): Boolean {
        denoisedRadiance = null
        denoisedSpecular = null
        denoisedIsReblur = false
        history.invalidate(D3D12RtHistory.InvalidationReason.EVALUATION_FAILURE)
        return false
    }

    private fun writeScene(
        camera: Camera,
        traceWidth: Int,
        traceHeight: Int,
        recordCount: Int,
    ): Boolean {
        val jitter = D3D12Temporal.frameJitter(camera.frameNanos)
        val painted = try {
            D3D12RtFrameConstants.write(
                target = frameConstants,
                position = camera.position,
                forward = camera.forward,
                up = camera.up,
                tanHalfFovY = camera.tanHalfFovY,
                aspect = camera.aspect,
                width = traceWidth,
                height = traceHeight,
                sunDir = camera.sunDir,
                sunRadiance = camera.sunColor,
                sunStrength = SUN_STRENGTH,
                frameIndex = frameCounter.toInt(),
                radius = TRACE_RADIUS,
                recordCount = recordCount,
                debugMode = 0,
                previousViewProjection = if (previousValid) previousViewProjection else null,
                previousViewMatrix = if (previousValid) previousView else null,
                outViewProjection = pendingViewProjection,
                outNrdMatrices = nrdMatrices,
                outSlConstants = slConstants,
                viewMatrix = camera.transform.view,
                projectionMatrix = camera.transform.projection,
                farPlane = camera.farPlane,
                jitterX = jitter.x.toFloat(),
                jitterY = jitter.y.toFloat(),
                previousJitterX = if (previousValid) previousJitter[0] else jitter.x.toFloat(),
                previousJitterY = if (previousValid) previousJitter[1] else jitter.y.toFloat(),
            )
            true
        } catch (t: Throwable) {
            fail("scene map failed: ${t.message}")
            false
        }
        if (!painted) return false
        nrdMatrices.copyInto(pendingView, 0, 32, 48)
        pendingJitter[0] = jitter.x.toFloat()
        pendingJitter[1] = jitter.y.toFloat()
        pendingPreviousReady = true
        return true
    }

    internal fun commitPendingSubmission(recordingIdentity: Any) = synchronized(frameLock) {
        if (preparedFrame?.recordingIdentity === recordingIdentity) preparedFrame = null
        for (mirror in listOfNotNull(mirrors, publishedMirrors)) {
            if (mirror.recordingIdentity === recordingIdentity) mirror.committed = true
        }
        if (pendingSubmission !== recordingIdentity) return@synchronized
        if (!pendingCompositeReady) {
            cancelPendingSubmission(recordingIdentity)
            return@synchronized
        }
        pendingSubmission = null
        pendingCompositeReady = false
        history.commit()
        if (!pendingPreviousReady) return@synchronized
        pendingViewProjection.copyInto(previousViewProjection)
        pendingView.copyInto(previousView)
        pendingJitter.copyInto(previousJitter)
        previousValid = true
        pendingPreviousReady = false
    }

    internal fun cancelPendingSubmission(recordingIdentity: Any? = pendingSubmission) = synchronized(frameLock) {
        if (recordingIdentity == null || preparedFrame?.recordingIdentity === recordingIdentity) preparedFrame = null
        if (pendingSubmission !== recordingIdentity) return@synchronized
        pendingSubmission = null
        pendingCompositeReady = false
        history.cancel()
        frameValid = false
        temporalResolveReady = false
        compositeImage = null
        pendingPreviousReady = false
        previousValid = false
    }

    /**
     * 光追链上的异常出口：只记前四次，不向渲染线程抛。
     * 见 docs/rt/README.md
     */
    @JvmStatic
    fun reportFailure(t: Throwable) {
        if (throwables.incrementAndGet() > MAX_THROWABLES) return
        IllusionLogger.warnMessage("rt: ${t.javaClass.simpleName}: ${t.message}")
    }

    private fun fail(reason: String) {
        val count = failures + 1
        failures = count
        if (count <= 4) {
            IllusionLogger.warnMessage("rt frame: $reason")
        }
    }

    private val SUN_COLOR = RtVec3(1f, 0.96f, 0.9f)

    /** 太阳辐亮度强度（进 `g_sunRadiance.w`）：与天空走同一套 HDR 出口，1.0 时接近原版观感。 */
    private const val SUN_STRENGTH = 1f
}




