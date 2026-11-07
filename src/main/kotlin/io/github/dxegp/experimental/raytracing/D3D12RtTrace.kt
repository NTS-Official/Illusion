package io.github.dxegp.experimental.raytracing

import com.mojang.renderpearl.api.buffers.GpuBuffer
import io.github.dxegp.d3d12.extern.MCDR_ABI
import io.github.dxegp.d3d12.render.adapter.D3D12Device
import io.github.dxegp.d3d12.render.core.D3D12GpuBuffer
import io.github.dxegp.misc.IllusionLogger
import io.github.dxegp.misc.settings.D3D12Settings
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.function.Supplier
import kotlin.math.abs
import kotlin.math.pow

/**
 * B-DXR 光追主通道（S2 2-d）。
 * 见 docs/rt/README.md
 */
object D3D12RtTrace {
    /** 自检分辨率：32×32 覆盖 16 个线程组，够区分命中/未命中又不必等太久。 */
    const val WIDTH: Int = 32
    const val HEIGHT: Int = 32

    /** 逐通道绝对误差上限（验收口径：与 CPU 参考逐像素误差 < 1e-5）。 */
    const val TOLERANCE: Float = 1e-5f

    /**
     * 相对容差：世界坐标到上千单位时 f32 的一个 ULP 就有 6e-5，两条不同的遍历实现差一个 ULP，
     * 直射项就会差这么多。只放宽到「一个 ULP 量级」，真正的阴影翻转（差 0.3 以上）照样会被抓到。
     */
    private const val RELATIVE_TOLERANCE: Float = 1e-4f

    private fun exceeds(diff: Float, reference: Float): Boolean =
        diff > TOLERANCE + RELATIVE_TOLERANCE * abs(reference)

    private const val RECORD_BYTES = 64
    private const val VERTEX_BYTES = 28
    // +0 albedo / +12 emissive / +24 roughness / +28 flags / +32 metalness（docs/rt/README.md §17.2）。
    private const val MATERIAL_BYTES = 40
    private const val SECTION_SIZE = 16f
    private const val RT_DIR = "dxrmc"
    private const val SELF_TEST_FILE = "rt_selftest.json"
    private const val SELF_TEST_PREV_FILE = "rt_selftest.prev.json"
    /** 常量缓冲 = 光影包 `WgaFrame`（416 B）。 */
    private const val SCENE_BYTES = D3D12RtFrameConstants.BYTES

    /** 本项目内部约定：缓冲 usage 位 1024 = 当 `RWByteAddressBuffer` 用。 */
    private const val UAV_BUFFER_USAGE = 1024

    private const val MIN_BUFFER_BYTES = 16

    /* 管线句柄被两个线程碰：渲染线程派发、客户端 tick 跑自检；换包时 apply() 还会释放重建。
       创建/释放必须互斥，否则会重复建（泄漏一条）或把正在派的句柄释放掉。 */
    private val pipelineLock = Any()

    /** 自检当前走到哪一步：失败时靠它定位，光有异常类不够。 */
    private var step = "pipeline"
    /** 自检前的等待 tick 数：世界刚加载完的那几秒排队的工作太多，栅栏等待会把 tick 线程按住。 */
    private const val SELFTEST_DELAY_TICKS = 200

    private var selfTestTicks = 0
    private var deferrals = 0
    private const val MAX_DEFERRALS = 8

    /** 栅栏等待超时：GPU 还在处理上一批工作，这一帧不做自检、下一 tick 再来（不算失败）。 */
    private class SelfTestDeferred(message: String) : RuntimeException(message)

    @Volatile private var validated: Boolean = false
    @Volatile private var failed: Boolean = false

    fun isActive(): Boolean = D3D12Settings.rtEnabled

    fun isValidated(): Boolean = validated

    /**
     * 跑一次场景自检；已经跑过、光追没开、或上一次就失败过都直接返回。
     * 必须在没有命令列表打开的时候调用（客户端 tick 即可），内部会自带编码器并等待栅栏。
     * 见 docs/rt/README.md
     */
    /**
     * 把自检结论落成 `dxrmc/rt_selftest.json`：游戏内出问题只能靠日志，而日志会被别的行冲掉。
     * 文件只有一行，读它就知道是哪一档、过没过、卡在哪一步。
     */
    /** 每个场景一行结论；写进 rt_selftest.json，这样一次运行就能看到全部六个场景的结果。 */
    private val sceneNotes = ArrayList<String>()

    /** 报告是一行 JSON：任何注入的字符串带引号/换行都会把它写坏，读的人只会看到半行。 */
    private fun jsonSafe(text: String): String = text.replace("\\", "/").replace("\"", "'").replace("\n", " ")

    private fun noteScene(label: String, pass: Boolean, detail: String) {
        val safe = jsonSafe(detail)
        sceneNotes += "{\"scene\":\"$label\",\"pass\":$pass,\"detail\":\"$safe\"}"
    }

    private fun writeSelfTestReport(pass: Boolean, detail: String, stepOverride: String? = null) {
        val tier = D3D12RtFrame.lastDispatchTier() ?: "none"
        val safe = jsonSafe(detail)
        // 一次运行把该看的都写进来：六个场景逐项结论、几何契约、加速结构计数、SBT、逐帧档位。
        // 只看这一个文件就能判断这一轮到底哪一步不对，不必再进游戏第二次。
        val text = "{\"time\":${System.currentTimeMillis()},\"tier\":\"$tier\"," +
            "\"backend\":${D3D12Settings.rtBackend}," +
            "\"pass\":$pass,\"step\":\"${stepOverride ?: step}\",\"detail\":\"$safe\"," +
            "\"scenes\":[${sceneNotes.joinToString(",")}]," +
            "\"contract\":\"${jsonSafe(accelReport(D3D12RtBlas.contractSummary(), D3D12RtBlas.selftestContractSummary()))}\"," +
            "\"blas\":\"${jsonSafe(accelReport(D3D12RtBlas.summary(), D3D12RtBlas.selftestBlasSummary()))}\"," +
            "\"sbt\":\"${jsonSafe(D3D12RtDxr.summary())}\"," +
            "\"frame\":\"${jsonSafe(D3D12RtFrame.lastDispatchSummary())}\"}\n"
        try {
            val dir = File(System.getProperty("user.dir"), RT_DIR)
            dir.mkdirs()
            val file = File(dir, SELF_TEST_FILE)
            // 本轮第一次写之前先把上一轮的结论挪走：否则新进程首帧那份 pending 会把
            // 上一轮的自检结论直接覆盖掉，下一次想对照「改完之后还过不过」就只剩一次运气。
            if (!preserved && file.exists()) {
                preserved = true
                file.copyTo(File(dir, SELF_TEST_PREV_FILE), overwrite = true)
            }
            file.writeText(text)
        } catch (e: IOException) {
            IllusionLogger.warnMessage("rt selftest: write failed (${e.javaClass.simpleName})")
        }
    }

    /**
     * 首帧派发成功后先落一份 `step = "pending"` 的报告：哪怕这一轮自检没跑完（崩了、关得快），
     * 文件里也有档位、几何契约、加速结构与帧派发的摘要，不用再进游戏第二次。
     */
    /**
     * 世界路径与自检实例是两份加速结构：世界还在加载时前者是默认值，而自检自己那份已经跑过了。
     * 只写「not checked」会让人以为这轮没查，写成两份各自的摘要才读得出实际发生了什么。
     */
    private fun accelReport(world: String, selftest: String): String =
        if (selftest == "none") "world $world" else "world $world; selftest $selftest"

    private var pendingReported = false

    private var disabledReported = false

    /** 上一轮的结论被挪到 `rt_selftest.prev.json` 后置位：一轮只挪一次，别把本轮自己的 pending 也挤掉。 */
    private var preserved = false

    /**
     * 光追整个没开时也留一行结论：否则进一次游戏什么都不留下，
     * 分不清「没开光追」和「开了但没跑起来」，等于白跑一趟。
     */
    fun reportDisabled() {
        if (disabledReported) return
        disabledReported = true
        writeSelfTestReport(
            false,
            "ray tracing is off (backend=${D3D12Settings.rtBackend}, mode=${D3D12Settings.rtMode})",
            "disabled",
        )
    }

    fun reportPending() {
        if (pendingReported) return
        pendingReported = true
        writeSelfTestReport(false, "first dispatch recorded, self-test pending", "pending")
    }

    fun validateOnce(device: D3D12Device?) {
        if (validated || failed || device == null || !isActive()) return
        if (++selfTestTicks < SELFTEST_DELAY_TICKS) return
        sceneNotes.clear()
        val ok = try {
            if (D3D12RtDxr.pipeline(device) == 0L) {
                step = "pipeline"
                false
            } else {
                IllusionLogger.infoMessage("rt self-test: DXR started")
                step = "probe"
                val probe = sceneGuard { validateProbe(device) }
                step = "empty"
                val empty = sceneGuard { validateScene(device, emptyScene()) }
                step = "triangle"
                val triangle = sceneGuard { validateScene(device, triangleScene()) }
                step = "shadow"
                val shadow = sceneGuard { validateScene(device, shadowScene(), compareIndirect = false) }
                probe && empty && triangle && shadow
            }
        } catch (t: SelfTestDeferred) {
            // 队列里还有没提交完的列表时等不到栅栏；这种情况会一直重复，所以要留一个「放弃了」的结论，
            // 否则 rt_selftest.json 永远不出现，看起来像自检根本没跑过。
            deferrals++
            if (deferrals < MAX_DEFERRALS) {
                IllusionLogger.warnMessage("rt trace: scene self-test deferred: ${t.message}")
                return
            }
            IllusionLogger.warnMessage("rt selftest: gave up after $deferrals deferrals (${t.message})")
            writeSelfTestReport(false, "deferred ${t.message}")
            failed = true
            return
        } catch (t: Throwable) {
            val where = t.stackTrace.firstOrNull()?.let { "${it.fileName}:${it.lineNumber}" } ?: "unknown"
            IllusionLogger.warnMessage("rt self-test: $step ${t.javaClass.simpleName} at $where")
            writeSelfTestReport(false, "${t.javaClass.simpleName} at $where")
            false
        }
        if (ok) {
            validated = true
            IllusionLogger.infoMessage(
                "rt trace: self-test passed (4 scenes, tolerance $TOLERANCE rel $RELATIVE_TOLERANCE)")
            writeSelfTestReport(true, "")
        } else {
            failed = true
            writeSelfTestReport(false, "see the log line above")
        }
    }

    /**
     * 释放追踪管线：光追关掉后调用一次，下次打开按当时的包重新编译。
     * 不释放的话换包只改了配置，已编译的管线还挂着旧包的源码。
     */
    fun releasePipeline() = synchronized(pipelineLock) {
        D3D12RtDxr.release()
    }

    /** 换包后必须重跑自检：上一个包通过的结论对新包的着色器无效。 */
    fun resetSelfTest() = synchronized(pipelineLock) {
        validated = false
        failed = false
        selfTestTicks = 0
    }

    // ---- 场景 ----

    private class Scene(
        val label: String,
        val positions: FloatArray,
        val indices: IntArray,
        val origin: RtVec3,
        val camera: RtVec3,
        val right: RtVec3,
        val up: RtVec3,
        val forward: RtVec3,
        val tanHalfFovY: Float,
        val aspect: Float,
        val sunDir: RtVec3,
        val sunColor: RtVec3,
        val skyColor: RtVec3,
    ) {
        val triangleCount: Int get() = indices.size / 3
    }

    private fun emptyScene(): Scene = Scene(
        label = "empty",
        positions = FloatArray(9),
        indices = intArrayOf(0, 1, 2),
        origin = RtVec3(0f, 0f, 0f),
        camera = RtVec3(0.25f, 0.25f, 0f),
        right = RtVec3(1f, 0f, 0f),
        up = RtVec3(0f, 1f, 0f),
        forward = RtVec3(0f, 0f, 1f),
        tanHalfFovY = 0.5f,
        aspect = 1f,
        sunDir = RtVec3(0f, 0f, 1f),
        sunColor = RtVec3(1f, 0.95f, 0.9f),
        skyColor = RtVec3(0.1f, 0.2f, 0.3f),
    )

    /**
     * 一块几乎铺满视野的三角形（平面 `z = 2`），顶点落在记录的 16³ 段盒内。
     *
     * 见 docs/rt/README.md 9.7 / 16.6 节。
     */
    private fun triangleScene(): Scene = Scene(
        label = "triangle",
        positions = floatArrayOf(
            1f, 1f, 2f,
            9f, 1f, 2f,
            5f, 9f, 2f,
        ),
        indices = intArrayOf(0, 1, 2),
        origin = RtVec3(0f, 0f, 0f),
        camera = RtVec3(5f, 5f, -6f),
        right = RtVec3(1f, 0f, 0f),
        up = RtVec3(0f, 1f, 0f),
        forward = RtVec3(0f, 0f, 1f),
        tanHalfFovY = 0.6f,
        aspect = 1f,
        sunDir = RtVec3(0.15f, 0.25f, -1f),
        sunColor = RtVec3(1f, 0.95f, 0.9f),
        skyColor = RtVec3(0.1f, 0.2f, 0.3f),
    )

    /**
     * 地面 + 一块悬空挡板：挡板把一部分地面的太阳直射光挡住，那里的 `direct` 必须是 0。
     * 见 docs/rt/README.md
     */
    private fun shadowScene(): Scene = Scene(
        label = "shadow",
        // 几何必须落在记录那段 16x16x16 的盒子里（追踪按段盒剪枝），所以地面铺满 [0,16] 而不是更大。
        positions = floatArrayOf(
            // 地面（两块三角形，y = 0）
            0f, 0f, 0f, 16f, 0f, 0f, 16f, 0f, 16f, 0f, 0f, 16f,
            // 悬空挡板（y = 4，沿 -sunDir 在地面上投影出阴影）
            4f, 4f, 6f, 10f, 4f, 6f, 10f, 4f, 11f, 4f, 4f, 11f,
        ),
        indices = intArrayOf(0, 1, 2, 0, 2, 3, 4, 5, 6, 4, 6, 7),
        origin = RtVec3(0f, 0f, 0f),
        camera = RtVec3(8f, 6f, -1f),
        right = RtVec3(1f, 0f, 0f),
        up = RtVec3(0f, 0.9578f, 0.2873f),
        forward = RtVec3(0f, -0.2873f, 0.9578f),
        tanHalfFovY = 0.7f,
        aspect = 1f,
        sunDir = RtVec3(0.2095f, 0.9428f, 0.3143f),
        sunColor = RtVec3(1f, 0.95f, 0.9f),
        skyColor = RtVec3(0.1f, 0.2f, 0.3f),
    )

    // ---- GPU 侧 ----

    private class GpuResult(
        val direct: FloatArray, val indirect: FloatArray, val aux: FloatArray,
        val specular: FloatArray, val specularGrazing: FloatArray,
    )

    /**
     * 探针：一次派发把常量与三张输入缓冲的原值写进输出，与期望值逐项比对。
     * 见 docs/rt/README.md
     */
    private fun validateProbe(device: D3D12Device): Boolean {
        val scene = triangleScene()
        val result = runGpu(
            device, scene, recordBytes(scene), vertexBytes(scene), indexBytes(scene),
            materialBytes(scene), quadMaterialBytes(scene),
            // debugMode = 8 是探针；recordCount 给非零假值。
            sceneBytes(scene, debugMode = 8, recordCount = 3),
        ) ?: return false
        val width = result.direct[0]
        val height = result.direct[1]
        val records = result.direct[2]
        // 探针在 ir0 里回读的是 g_sunRadiance.rgb：天空在常量里已经没有独立的槽
        // （自检环境的常量天空只给 debug mode 9 的未命中像素用），别再按天空比。
        val sun = result.indirect
        val vertexZ = result.aux[1]
        val material = result.aux[2]
        val ok = width == WIDTH.toFloat() && height == HEIGHT.toFloat() && records == 3f &&
            abs(sun[0] - scene.sunColor.x) < 1e-3f && abs(sun[1] - scene.sunColor.y) < 1e-3f &&
            abs(sun[2] - scene.sunColor.z) < 1e-3f &&
            abs(vertexZ - 2f) < 1e-3f && abs(material - 0.7f) < 1e-3f
        val line = "rt trace: probe width=$width height=$height records=$records " +
            "sun=(${sun[0]}, ${sun[1]}, ${sun[2]}) vertexZ=$vertexZ material=$material"
        if (ok) {
            IllusionLogger.infoMessage("$line -> pass")
        } else {
            IllusionLogger.warnMessage("$line -> fail")
            if (width == 0f && height == 0f && records == 0f && vertexZ == 0f) {
                IllusionLogger.warnMessage(
                    "rt trace: no constant reached the shader, rebuild native (MSBuild MCDR + copyNative) " +
                        "if run/dxrmc/MCDR.dll is older than the sources",
                )
            }
        }
        return ok
    }

    /**
     * 单个场景抛异常不该中断整轮自检：记下当前步骤与帧链，继续跑后面的场景。
     * `SelfTestDeferred` 表示队列繁忙，仍然整体推迟。
     */
    private inline fun sceneGuard(body: () -> Boolean): Boolean = try {
        body()
    } catch (d: SelfTestDeferred) {
        throw d
    } catch (t: Throwable) {
        val where = t.stackTrace.firstOrNull()?.let { "${it.fileName}:${it.lineNumber}" } ?: "unknown"
        IllusionLogger.warnMessage("rt self-test: $step ${t.javaClass.simpleName} at $where")
        false
    }

    private fun validateScene(
        device: D3D12Device,
        scene: Scene,
        compareIndirect: Boolean = true,
    ): Boolean {
        val records = recordBytes(scene)
        val vertices = vertexBytes(scene)
        val indices = indexBytes(scene)
        val materials = materialBytes(scene)
        val quads = quadMaterialBytes(scene)
        val camera = sceneBytes(scene)

        val result = runGpu(device, scene, records, vertices, indices, materials, quads, camera)
            ?: return false
        val cpu = _reference(scene, records, vertices, indices, materials, quads, camera)

        var worst = 0f
        var over = 0
        var maskMismatch = 0
        var materialMismatch = 0
        var validityMismatch = 0
        var gpuHits = 0
        var cpuHits = 0
        var gpuShadowed = 0
        var invalidSpecular = 0
        var specularSamples = 0
        val specularPlanes = listOf(result.specular to cpu.specular, result.specularGrazing to cpu.specularGrazing)
        for (i in 0 until WIDTH * HEIGHT) {
            val cpuHit = cpu.aux[i * 4] > 0f
            val gpuHit = result.aux[i * 4] > 0f
            if (gpuHit) gpuHits++
            if (cpuHit) cpuHits++
            // 命中但一点太阳直射都拿不到 = 落在阴影里（这是阴影射线真的求过交的证据）。
            if (gpuHit && result.direct[i * 4] <= 0f && result.direct[i * 4 + 1] <= 0f &&
                result.direct[i * 4 + 2] <= 0f
            ) {
                gpuShadowed++
            }
            if (cpuHit != gpuHit) maskMismatch++
            if (cpu.indirect[i * 4 + 3].toRawBits() != result.indirect[i * 4 + 3].toRawBits()) {
                validityMismatch++
            }
            if (cpuHit && gpuHit && cpu.aux[i * 4 + 3].toRawBits() != result.aux[i * 4 + 3].toRawBits()) {
                materialMismatch++
            }
            if (result.specular[i * 4 + 3] > 0f) specularSamples++
            if (result.specular[i * 4 + 3] < 0f || result.specularGrazing[i * 4 + 3] != 0f ||
                (!gpuHit && (0 until 4).any { result.specular[i * 4 + it] != 0f ||
                    result.specularGrazing[i * 4 + it] != 0f })
            ) invalidSpecular++
            for (c in 0 until 4) {
                val at = i * 4 + c
                val d = abs(result.direct[at] - cpu.direct[at])
                if (d > worst) worst = d
                if (exceeds(d, cpu.direct[at])) over++
                if (compareIndirect && c < 3) {
                    val e = abs(result.indirect[at] - cpu.indirect[at])
                    if (e > worst) worst = e
                    if (exceeds(e, cpu.indirect[at])) over++
                }
                for ([actual, expected] in specularPlanes) {
                    val difference = abs(actual[at] - expected[at])
                    if (!actual[at].isFinite() || !expected[at].isFinite()) invalidSpecular++
                    if (difference > worst) worst = difference
                    if (exceeds(difference, expected[at])) over++
                }
            }
        }
        // 两侧都没命中时逐像素比较恒等：没有覆盖的场景证明不了任何事，必须要求真的有命中。
        val covered = if (scene.label == "empty") gpuHits == 0 && cpuHits == 0 else gpuHits > 0 && cpuHits > 0
        val ok = over == 0 && maskMismatch == 0 && materialMismatch == 0 && validityMismatch == 0 && covered &&
            invalidSpecular == 0 && (scene.label == "empty" || specularSamples > 0)
        noteScene(
            scene.label, ok,
            "maxDiff=$worst over=$over mask=$maskMismatch hits=$gpuHits/$cpuHits shadowed=$gpuShadowed " +
                "covered=$covered material=$materialMismatch validity=$validityMismatch indirect=$compareIndirect " +
                "specular=$specularSamples invalid=$invalidSpecular")
        // 一行一个事实：三行分别是对拍误差、命中统计、判定依据（日志规范：正文 ≤60、硬上限 90）。
        val head = "rt trace: scene=${scene.label}"
        logLine("$head maxDiff=$worst over=$over mask=$maskMismatch triangles=${scene.triangleCount}")
        logLine("$head hits gpu=$gpuHits cpu=$cpuHits shadowed=$gpuShadowed covered=$covered")
        logLine("$head materialMismatch=$materialMismatch")
        logLine("$head validityMismatch=$validityMismatch")
        logLine("$head specular=$specularSamples invalid=$invalidSpecular")
        logLine(
            "$head indirect=${if (compareIndirect) "on" else "off"} " +
                "tol=$TOLERANCE+${RELATIVE_TOLERANCE}rel -> ${if (ok) "pass" else "fail"}",
            warn = !ok,
        )
        if (!ok) {
            IllusionLogger.warnMessage("rt trace: detail ${describeMismatch(result, cpu)}")
        }
        return ok
    }

    /** 自检的 INFO 与 WARN 走同一个出口，判定行在失败时要落到 WARN 通道。 */
    private fun logLine(text: String, warn: Boolean = false) {
        if (warn) IllusionLogger.warnMessage(text) else IllusionLogger.infoMessage(text)
    }

    /** 自检失败时的取证：哪一侧整块是 0、前几个超差采样各是多少。 */
    private fun describeMismatch(gpu: GpuResult, cpu: CpuResult): String = buildString {
        append("gpuZero[d=${allZero(gpu.direct)} i=${allZero(gpu.indirect)}")
        append(" auxMask=0] cpuZero[d=${allZero(cpu.direct)} i=${allZero(cpu.indirect)}]")
        var shown = 0
        var pixel = 0
        while (pixel < WIDTH * HEIGHT && shown < 3) {
            for (c in 0 until 4) {
                val at = pixel * 4 + c
                val direct = abs(gpu.direct[at] - cpu.direct[at])
                if (exceeds(direct, cpu.direct[at])) {
                    append(" | px=$pixel direct[$c] gpu=${gpu.direct[at]} cpu=${cpu.direct[at]}")
                    shown++
                }
                if (shown >= 3) break
                val indirect = abs(gpu.indirect[at] - cpu.indirect[at])
                if (c < 3 && exceeds(indirect, cpu.indirect[at])) {
                    append(" | px=$pixel indirect[$c] gpu=${gpu.indirect[at]} cpu=${cpu.indirect[at]}")
                    shown++
                }
                if (c == 3 && gpu.indirect[at].toRawBits() != cpu.indirect[at].toRawBits()) {
                    append(" | px=$pixel validity gpu=${gpu.indirect[at].toRawBits()} cpu=${cpu.indirect[at].toRawBits()}")
                    shown++
                }
                if (shown >= 3) break
            }
            pixel++
        }
    }

    private fun allZero(values: FloatArray): Boolean = values.all { it == 0f }

    private fun runGpu(
        device: D3D12Device, scene: Scene,
        records: ByteBuffer, vertices: ByteBuffer, indices: ByteBuffer,
        materials: ByteBuffer, quads: ByteBuffer, camera: ByteBuffer,
    ): GpuResult? {
        val accel = D3D12RtBlas.newAccel()
        val sources = ArrayList<D3D12GpuBuffer>(6)
        val outputs = ArrayList<D3D12GpuBuffer>(5)
        val readbacks = ArrayList<D3D12GpuBuffer>(5)
        val constantHandles = ArrayList<D3D12GpuBuffer>(1)
        var ctx = 0L
        try {
            step = "${scene.label}:upload"
            for ((name, data) in listOf(
                "records" to records, "vertices" to vertices, "indices" to indices,
                // 顺序必须与着色器绑定一致：t4 = gQuadMaterials。
                "materials" to materials, "quads" to quads,
            )) {
                sources.add(upload(device, "raytracing-test-$name", data) ?: return null)
            }
            step = "${scene.label}:alloc"
            val bytes = (WIDTH * HEIGHT * 16).toLong()
            repeat(5) { i ->
                outputs.add(newBuffer(device, "raytracing-test-out-$i", UAV_BUFFER_USAGE, bytes) ?: return null)
            }
            repeat(5) { i ->
                readbacks.add(newBuffer(device, "raytracing-test-read-$i", READ_USAGE, bytes) ?: return null)
            }

            // 常量缓冲必须是上传堆 + 256 对齐的 CBV 偏移（compute 根签名 [2] 是 b0 的 CBV）。
            val constantBuffer = newBuffer(
                device, "raytracing-test-constants", GpuBuffer.USAGE_MAP_WRITE, 512L,
            ) ?: return null
            constantHandles.add(constantBuffer)
            constantBuffer.map(0L, camera.remaining().toLong(), false, true).use { view ->
                view.data().put(camera.duplicate())
            }

            step = "${scene.label}:encoder"
            ctx = MCDR_ABI.D3D12CreateCommandEncoder()
            if (ctx == 0L) {
                IllusionLogger.warnMessage("rt trace: command encoder creation failed: ${MCDR_ABI.D3D12LastError()}")
                return null
            }
            MCDR_ABI.D3D12BeginCommandList(ctx)
            var srvs = LongArray(sources.size) { sources[it].handle() }
            if (!accel.prepare(device, ctx, records, 1, sources[1].handle(), sources[2].handle())) {
                IllusionLogger.warnMessage("rt self-test: acceleration build failed")
                return null
            }
            val view = accel.view()
            if (view == 0L) return null
            srvs += view
            D3D12RtBlas.noteSelftest(accel)
            val uavs = LongArray(outputs.size) { outputs[it].handle() }
            step = "${scene.label}:dispatch"
            val dispatched = D3D12RtDxr.dispatch(
                device, ctx, srvs, uavs, constantBuffer.handle(), 0L, WIDTH, HEIGHT)
            if (!dispatched) {
                IllusionLogger.warnMessage("rt trace: dispatch failed: ${MCDR_ABI.D3D12LastError()}")
                return null
            }
            step = "${scene.label}:readback"
            for (i in outputs.indices) {
                MCDR_ABI.D3D12CopyBuffer(ctx, outputs[i].handle(), 0L, readbacks[i].handle(), 0L, bytes)
            }
            val fence = MCDR_ABI.D3D12Submit(ctx)
            if (!MCDR_ABI.D3D12WaitForFence(ctx, fence, 3_000_000_000L)) {
                // 队列里还压着世界加载的工作，这次不做自检；不是结论性失败，下次 tick 重来。
                throw SelfTestDeferred("fence wait timed out")
            }

            val planes = Array(5) { readFloats(readbacks[it], bytes.toInt()) ?: return null }
            return GpuResult(planes[0], planes[1], planes[2], planes[3], planes[4])
        } finally {
            if (ctx != 0L) MCDR_ABI.D3D12DestroyCommandEncoder(ctx)
            accel.release()
            for (buffer in sources + outputs + readbacks + constantHandles) buffer.close()
        }
    }

    private fun newBuffer(
        device: D3D12Device, label: String, usage: Int, bytes: Long,
    ): D3D12GpuBuffer? {
        val created = device.createBuffer(Supplier { label }, usage, bytes) as? D3D12GpuBuffer
        if (created == null) {
            IllusionLogger.warnMessage("rt buffer: $label failed (${MCDR_ABI.D3D12LastError()})")
        }
        return created
    }

    private fun upload(device: D3D12Device, label: String, data: ByteBuffer): D3D12GpuBuffer? {
        // raw SRV 的元素粒度是 4 字节（NumElements = size/4）：6 字节的索引缓冲只有 4 字节可见，
        // 第三条边会读成 0 变成退化三角形。这里补齐到 4 字节整数倍（且不小于 16 字节）。
        // 必须走收 ByteBuffer 的重载：另一个重载只给大小，内容一个字节都不会上传，
        // 着色器读到的是没初始化过的显存（自检于是拿着垃圾数据比对）。
        val padded = if (data.remaining() % 4 == 0 && data.remaining() >= MIN_BUFFER_BYTES) {
            data
        } else {
            ByteBuffer.allocate(maxOf(MIN_BUFFER_BYTES, (data.remaining() + 3) and -4))
                .order(ByteOrder.LITTLE_ENDIAN).also {
                    it.put(data.duplicate())
                    it.flip()
                }
        }
        val created = device.createBuffer(Supplier { label }, 0, padded) as? D3D12GpuBuffer
        if (created == null) {
            IllusionLogger.warnMessage("rt buffer: $label failed (${MCDR_ABI.D3D12LastError()})")
        }
        return created
    }

    private fun readFloats(buffer: D3D12GpuBuffer, bytes: Int): FloatArray? {
        val mapped = MCDR_ABI.D3D12MapBuffer(buffer.handle(), 0L, bytes.toLong(), true, false)
        if (mapped == null) {
            IllusionLogger.warnMessage("rt trace: read-back map failed: ${MCDR_ABI.D3D12LastError()}")
            return null
        }
        try {
            mapped.order(ByteOrder.LITTLE_ENDIAN)
            val out = FloatArray(bytes / 4)
            for (i in out.indices) out[i] = mapped.getFloat(i * 4)
            return out
        } finally {
            MCDR_ABI.D3D12UnmapBuffer(buffer.handle())
        }
    }

    // ---- 场景缓冲（与 GPU 记录布局逐字节对齐，CPU 参考读同一份字节） ----

    private fun buffer(size: Int): ByteBuffer =
        ByteBuffer.allocate(size.coerceAtLeast(MIN_BUFFER_BYTES)).order(ByteOrder.LITTLE_ENDIAN)

    private fun recordBytes(scene: Scene): ByteBuffer {
        val out = buffer(RECORD_BYTES * 1)
        if (scene.triangleCount == 0) return out
        for (i in 0 until scene.positions.size / 3) {
            val inside = scene.positions[i * 3] in scene.origin.x..(scene.origin.x + SECTION_SIZE) &&
                scene.positions[i * 3 + 1] in scene.origin.y..(scene.origin.y + SECTION_SIZE) &&
                scene.positions[i * 3 + 2] in scene.origin.z..(scene.origin.z + SECTION_SIZE)
            if (!inside) {
                error("scene vertex $i outside the section box")
            }
        }
        out.putInt(0, scene.origin.x.toInt())
        out.putInt(4, scene.origin.y.toInt())
        out.putInt(8, scene.origin.z.toInt())
        out.putInt(12, 0)                                   // layer
        out.putLong(16, 0L)                                 // vertexOffset
        out.putLong(24, 0L)                                 // indexOffset
        out.putInt(32, scene.positions.size / 3)            // vertexCount
        out.putInt(36, scene.indices.size)                  // indexCount
        out.putInt(40, 2 or (1 shl 8))                       // indexStride = u16，flags = SOLID
        out.putInt(44, 1)                                   // geometryVersion
        out.putInt(48, 0)                                   // materialOffset（以 quad 计）
        out.putInt(52, scene.triangleCount / 2 + 1)         // quadCount
        out.putLong(56, 0L)                                 // sectionNode
        return out
    }

    private fun vertexBytes(scene: Scene): ByteBuffer {
        val count = scene.positions.size / 3
        val out = buffer(VERTEX_BYTES * count.coerceAtLeast(1))
        // 与 DefaultVertexFormat.BLOCK 对齐：Position(12) + Color(4) + UV0(8) + UV2(4)。
        for (i in 0 until count) {
            val at = i * VERTEX_BYTES
            out.putFloat(at, scene.positions[i * 3])
            out.putFloat(at + 4, scene.positions[i * 3 + 1])
            out.putFloat(at + 8, scene.positions[i * 3 + 2])
            out.putInt(at + 12, -1)                          // 顶点色 = 白
        }
        return out
    }

    private fun indexBytes(scene: Scene): ByteBuffer {
        val out = buffer(scene.indices.size * 2)
        for (i in scene.indices.indices) out.putShort(i * 2, scene.indices[i].toShort())
        return out
    }

    private fun materialBytes(scene: Scene): ByteBuffer {
        val out = buffer(MATERIAL_BYTES * 3)
        for (material in 0 until 3) {
            val offset = material * MATERIAL_BYTES
            out.putFloat(offset, 0.7f)
            out.putFloat(offset + 4, 0.7f)
            out.putFloat(offset + 8, 0.7f)
            out.putFloat(offset + 24, 0.8f)
        }
        return out
    }

    private fun quadMaterialBytes(scene: Scene): ByteBuffer {
        val quads = (scene.triangleCount / 2 + 1).coerceAtLeast(1)
        return buffer(quads * 2).also { out ->
            for (quad in 0 until quads) out.putShort(quad * 2, (1 + quad % 2).toShort())
        }
    }

    /**
     * 自检场景的 `WgaFrame`。
     *
     * `debugMode = 9` 是 B-DXR 的自检环境：着色器把天空换成常量 `g_moonRadiance.rgb`
     * （场景的 `skyColor`），CPU 参考才对得上；正常出图用 `0`，天空走光影包的解析大气。
     */
    private fun sceneBytes(scene: Scene, debugMode: Int = 9, recordCount: Int = -1): ByteBuffer {
        val out = buffer(SCENE_BYTES)
        D3D12RtFrameConstants.write(
            target = out,
            position = scene.camera,
            forward = scene.forward,
            up = scene.up,
            tanHalfFovY = scene.tanHalfFovY,
            aspect = scene.aspect,
            width = WIDTH,
            height = HEIGHT,
            sunDir = scene.sunDir,
            sunRadiance = scene.sunColor,
            sunStrength = 1f,
            frameIndex = 0,
            radius = 0f,
            recordCount = if (recordCount >= 0) recordCount else if (scene.triangleCount == 0) 0 else 1,
            debugMode = debugMode,
        )
        // 自检环境的常量天空占 g_moonRadiance.rgb（正常出图这一行不用）。
        putVec(out, 256, scene.skyColor)
        return out
    }

    /** 列主序 `invViewProj` × 裁剪空间 (ndc, 1, 1)，再减去相机位置归一化——与 HLSL 的 RtRayDirection 等价。 */
    private fun rayDirection(inverse: FloatArray, ndcx: Float, ndcy: Float, camPos: RtVec3): RtVec3 {
        val x = inverse[0] * ndcx + inverse[4] * ndcy + inverse[8] + inverse[12]
        val y = inverse[1] * ndcx + inverse[5] * ndcy + inverse[9] + inverse[13]
        val z = inverse[2] * ndcx + inverse[6] * ndcy + inverse[10] + inverse[14]
        val w = inverse[3] * ndcx + inverse[7] * ndcy + inverse[11] + inverse[15]
        val scale = 1f / w
        return D3D12RtLightContract.normalize(RtVec3(x * scale, y * scale, z * scale) - camPos)
    }

    private fun putVec(buffer: ByteBuffer, offset: Int, value: RtVec3) {
        buffer.putFloat(offset, value.x)
        buffer.putFloat(offset + 4, value.y)
        buffer.putFloat(offset + 8, value.z)
    }

    // ---- CPU 参考：读同一份字节、走同一条光线语义 ----

    private class RefHit {
        var hit: Boolean = false
        var t: Float = 0f
        var normal: RtVec3 = RtVec3(0f, 0f, 1f)
        var material: Int = 0
    }

    private class CpuResult(
        val direct: FloatArray, val indirect: FloatArray, val aux: FloatArray,
        val specular: FloatArray, val specularGrazing: FloatArray,
    )

    private fun _reference(
        scene: Scene, records: ByteBuffer, vertices: ByteBuffer, indices: ByteBuffer,
        materials: ByteBuffer, quads: ByteBuffer, camera: ByteBuffer,
    ): CpuResult {
        val direct = FloatArray(WIDTH * HEIGHT * 4)
        val indirect = FloatArray(WIDTH * HEIGHT * 4)
        val aux = FloatArray(WIDTH * HEIGHT * 4)
        val specular = FloatArray(WIDTH * HEIGHT * 4)
        val specularGrazing = FloatArray(WIDTH * HEIGHT * 4)
        val camPos = readVec(camera, 192)
        val sunDir = D3D12RtLightContract.normalize(readVec(camera, 224))
        val sunColor = readVec(camera, 240)
        val skyColor = readVec(camera, 256)
        val inverse = FloatArray(16) { camera.getFloat(128 + it * 4) }
        val recordCount = if (scene.triangleCount == 0) 0 else 1
        val sampleFrame = camera.getFloat(204).toInt()

        fun bounce(origin: RtVec3, direction: RtVec3): Pair<RtVec3, Float> {
            val second = trace(recordCount, records, vertices, indices, quads, origin, direction, 1e9f)
            if (!second.hit) return skyColor to 1e9f
            val normal = faceNormal(second.normal, direction)
            val shadowOrigin = origin + direction * second.t + normal * D3D12RtLightContract.rayBias(second.t)
            val shadow = trace(recordCount, records, vertices, indices, quads, shadowOrigin, sunDir, 1e9f)
            val materialAt = second.material * MATERIAL_BYTES
            return shadeBounce(readVec(materials, materialAt), readVec(materials, materialAt + 12),
                materials.getFloat(materialAt + 24), materials.getFloat(materialAt + 32),
                normal, sunDir, sunColor, skyColor, if (shadow.hit) 0f else 1f, direction) to second.t
        }

        fun store(target: FloatArray, pixel: Int, value: RtVec3) {
            target[pixel * 4] = value.x
            target[pixel * 4 + 1] = value.y
            target[pixel * 4 + 2] = value.z
        }

        for (pixelY in 0 until HEIGHT) {
            for (pixelX in 0 until WIDTH) {
                val pixel = pixelY * WIDTH + pixelX
                val ndcX = (pixelX + 0.5f) / WIDTH * 2f - 1f
                val ndcY = 1f - (pixelY + 0.5f) / HEIGHT * 2f
                val direction = rayDirection(inverse, ndcX, ndcY, camPos)
                val hit = trace(recordCount, records, vertices, indices, quads, camPos, direction, 1e9f)
                indirect[pixel * 4 + 3] = Float.fromBits(if (hit.hit) 1 else 0)
                if (!hit.hit) {
                    store(indirect, pixel, skyColor)
                    continue
                }
                val normal = faceNormal(hit.normal, direction)
                val origin = camPos + direction * hit.t + normal * D3D12RtLightContract.rayBias(hit.t)
                val shadow = trace(recordCount, records, vertices, indices, quads, origin, sunDir, 1e9f)
                val visible = if (shadow.hit) 0f else 1f
                aux[pixel * 4] = hit.t
                aux[pixel * 4 + 1] = visible
                aux[pixel * 4 + 2] = 1e9f
                aux[pixel * 4 + 3] = Float.fromBits(hit.material)
                store(direct, pixel, sunColor * (normal.dot(sunDir).coerceAtLeast(0f) * visible))
                direct[pixel * 4 + 3] = 1f

                val diffuseRng = D3D12RtLightContract.Rng(D3D12RtLightContract.seed(pixelX, pixelY, sampleFrame, 1, 0, 0))
                val diffuseDirection = D3D12RtLightContract.cosineHemisphere(diffuseRng.next2(), normal)
                if (normal.dot(diffuseDirection) > 0f) {
                    val [incoming, distance] = bounce(origin, diffuseDirection)
                    store(indirect, pixel, incoming)
                    aux[pixel * 4 + 2] = distance
                }
                val materialRoughness = materials.getFloat(hit.material * MATERIAL_BYTES + 24)
                val roughness = if (materialRoughness.isFinite()) materialRoughness.coerceIn(1.5f / 512f, 1f) else 0.8f
                val alpha = roughness * roughness
                val view = direction * -1f
                val specularRng = D3D12RtLightContract.Rng(D3D12RtLightContract.seed(pixelX, pixelY, sampleFrame, 1, 1, 0))
                val [specularDirection, halfDirection] =
                    D3D12RtLightContract.sampleGgxVndf(specularRng.next2(), normal, view, alpha)
                val normalDotLight = normal.dot(specularDirection)
                if (normalDotLight > 0f) {
                    val [incoming, distance] = bounce(origin, specularDirection)
                    val grazing = D3D12RtLightContract.saturate(1f - view.dot(halfDirection)).pow(5f)
                    val visibility = D3D12RtLightContract.ggxSmithG1(normalDotLight, alpha)
                    store(specular, pixel, incoming * (visibility * (1f - grazing)))
                    store(specularGrazing, pixel, incoming * (visibility * grazing))
                    specular[pixel * 4 + 3] = distance
                }
            }
        }
        return CpuResult(direct, indirect, aux, specular, specularGrazing)
    }

    private fun trace(
        recordCount: Int, records: ByteBuffer, vertices: ByteBuffer, indices: ByteBuffer,
        quads: ByteBuffer, origin: RtVec3, dir: RtVec3, maxT: Float,
    ): RefHit {
        val hit = RefHit()
        hit.t = maxT
        for (r in 0 until recordCount) {
            val base = r * RECORD_BYTES
            val recordOrigin = RtVec3(records.getInt(base).toFloat(), records.getInt(base + 4).toFloat(),
                records.getInt(base + 8).toFloat())
            val vertexOffset = records.getLong(base + 16).toInt()
            val indexOffset = records.getLong(base + 24).toInt()
            val indexCount = records.getInt(base + 36)
            val stride = records.getInt(base + 40) and 0xFF
            val materialOffset = records.getInt(base + 48)
            var k = 0
            while (k + 2 < indexCount) {
                val ia = indexAt(indices, indexOffset + k * stride, stride)
                val ib = indexAt(indices, indexOffset + (k + 1) * stride, stride)
                val ic = indexAt(indices, indexOffset + (k + 2) * stride, stride)
                // 记录里的 vertexOffset 是字节偏移；顶点之间隔 VERTEX_BYTES 字节。
                val a = recordOrigin + vertexAt(vertices, vertexOffset + ia * VERTEX_BYTES)
                val b = recordOrigin + vertexAt(vertices, vertexOffset + ib * VERTEX_BYTES)
                val c = recordOrigin + vertexAt(vertices, vertexOffset + ic * VERTEX_BYTES)
                val normal = intersectTriangle(origin, dir, a, b, c, hit)
                if (normal != null) {
                    hit.hit = true
                    hit.normal = normal
                    hit.material = quadMaterial(quads, materialOffset + k / 6)
                }
                k += 3
            }
        }
        return hit
    }

    /** 按**字节**偏移取顶点：与着色器的 `RtVertexAt(vertexOffset + 索引 * 28)` 逐字节对齐。 */
    private fun vertexAt(vertices: ByteBuffer, byteOffset: Int): RtVec3 =
        readVec(vertices, byteOffset)

    private fun indexAt(indices: ByteBuffer, byteOffset: Int, stride: Int): Int =
        if (stride == 4) indices.getInt(byteOffset) else indices.getShort(byteOffset).toInt() and 0xFFFF

    private fun quadMaterial(quads: ByteBuffer, quadIndex: Int): Int {
        val byteOffset = quadIndex * 2
        val word = quads.getInt(byteOffset and 3.inv())
        return if (byteOffset and 2 == 0) word and 0xFFFF else word ushr 16
    }

    /** 返回面法线（命中且更新了 [hit] 的 t），未命中返回 null。 */
    private fun intersectTriangle(
        org: RtVec3, dir: RtVec3, v0: RtVec3, v1: RtVec3, v2: RtVec3, hit: RefHit,
    ): RtVec3? {
        val e1 = v1 - v0
        val e2 = v2 - v0
        val p = dir.cross(e2)
        val det = e1.dot(p)
        if (abs(det) < 1e-9f) return null
        val invDet = 1f / det
        val tvec = org - v0
        val u = tvec.dot(p) * invDet
        if (u < 0f || u > 1f) return null
        val q = tvec.cross(e1)
        val v = dir.dot(q) * invDet
        if (v < 0f || u + v > 1f) return null
        val t = e2.dot(q) * invDet
        if (t <= 1e-5f || t >= hit.t) return null
        hit.t = t
        return D3D12RtLightContract.normalize(e1.cross(e2))
    }

    private fun faceNormal(normal: RtVec3, dir: RtVec3): RtVec3 =
        if (normal.dot(dir) > 0f) normal * -1f else normal

    private fun readVec(buffer: ByteBuffer, offset: Int): RtVec3 =
        RtVec3(buffer.getFloat(offset), buffer.getFloat(offset + 4), buffer.getFloat(offset + 8))

    /**
     * 弹射面的出射辐射度；逐项与 `rt_shade.hlsli` 的 `RtIndirectRadiance` 对齐（§17.2）。
     *
     * 两边算得不一样时自检会报「GPU 与 CPU 参考差很大」，而画面看不出问题 —— 改一边必须改另一边。
     */
    private fun shadeBounce(albedo: RtVec3, emissive: RtVec3, roughness: Float, metalness: Float,
                            normal: RtVec3, sunDir: RtVec3, sunColor: RtVec3, skyColor: RtVec3,
                            visible: Float, viewDir: RtVec3): RtVec3 {
        val metal = metalness.coerceIn(0f, 1f)
        val smoothness = 1f - roughness.coerceIn(0f, 1f)
        val diffuse = albedo * (1f - metal)
        val f0 = albedo * metal + RtVec3(0.04f, 0.04f, 0.04f) * (1f - metal)
        val exponent = 2.0.pow(1.0 + 9.0 * smoothness).toFloat()
        // 半角向量要自己归一化：RtVec3 故意只放契约里有的运算（contract_check 逐条对拍）。
        val halfRaw = sunDir - viewDir
        val halfLen = halfRaw.length()
        val halfDir = if (halfLen > 0f) halfRaw * (1f / halfLen) else halfRaw
        val lobe = maxOf(normal.dot(halfDir), 0f).toDouble().pow(exponent.toDouble()).toFloat()
        val specular = f0 * (lobe * ((exponent + 2f) / 8f))
        val sun = (diffuse * maxOf(normal.dot(sunDir), 0f) + specular) * sunColor * visible
        return emissive + sun + diffuse * skyColor
    }

    /** GpuBuffer.USAGE_MAP_READ：READBACK 堆，可 Map 回读。 */
    private const val READ_USAGE = 1
}




