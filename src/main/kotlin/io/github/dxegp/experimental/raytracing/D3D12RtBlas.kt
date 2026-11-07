package io.github.dxegp.experimental.raytracing

import com.mojang.renderpearl.api.buffers.GpuBuffer
import io.github.dxegp.d3d12.extern.MCDR_ABI
import io.github.dxegp.d3d12.render.adapter.D3D12Device
import io.github.dxegp.d3d12.render.core.D3D12GpuBuffer
import io.github.dxegp.misc.IllusionLogger
import io.github.dxegp.misc.settings.D3D12Settings
import net.minecraft.core.SectionPos
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.function.Supplier

/**
 * 一个镜像切片的每条段记录对应一条 BLAS。
 * 见 docs/rt/README.md §13。
 *
 * 两条不能违反的规则：BLAS 的顶点 / 索引必须指向**镜像缓冲**（默认堆），否则原生侧直接拒绝；
 * 实例的 3×4 矩阵只写平移 —— 镜像顶点是段内局部坐标，世界位置取自记录的 origin。
 * 实例的 `InstanceID` 写**记录下标**，着色器命中后用它在 `gRecords` 里取同一份材质与顶点。
 *
 * 这是实例而不是单例：自检要一份只含一颗三角形的独立加速结构，与渲染线程那份互不干扰。
 */
internal class D3D12RtAccel {
    private companion object {
        /** 镜像记录宽度，与 `D3D12RtGeometryPool.mirrorNear` 写出的 64 B 记录一一对应。 */
        const val RECORD_BYTES = 64

        /** `D3D12_RAYTRACING_INSTANCE_DESC` 固定 64 B。 */
        const val INSTANCE_BYTES = 64

        const val VERTEX_STRIDE = 28L
        const val UAV_USAGE = 1024
        const val MIN_BYTES = 256L

        /**
         * 实例标志：关掉三角形剔除（`D3D12_RAYTRACING_INSTANCE_FLAG_TRIANGLE_CULL_DISABLE`）。
         * 镜像三角形的绕序与原版面朝向并不保证一致，开着剔除时射线会被整片背面剔掉——
         * `HIT_DISTANCE` 与 `SHADOW` 全黑、而 `COVERAGE` 正常，见 docs/rt/README.md §13.3。
         */
        const val INSTANCE_FLAGS = 0x1

        /** 主光线只看静态不透明几何；动态 / 半透明留到 S4。 */
        const val INSTANCE_MASK = 0x01

        /** 同一条 BLAS 连续 refit 的上限，超过就整条重建（refit 会让遍历变慢）。 */
        const val REFIT_LIMIT = 2

        /** 段从镜像里消失多少帧后回收它的 BLAS；留一段缓冲，来回走不会被反复重建。 */
        const val EVICT_FRAMES = 180L

        /** SBT 命中组记录的下标：与 `D3D12RtDxr` 写 SBT 的顺序一致（opaque 在前、cutout 在后）。 */
        const val HIT_GROUP_OPAQUE = 0

        const val HIT_GROUP_CUTOUT = 1
        const val HIT_GROUP_INVALID = 2
    }

    private class BlasStorage(var result: Long, var resultBytes: Long, var compact: Long) {
        private var references = 1

        @Synchronized
        fun retain(): BlasStorage {
            check(references > 0)
            references++
            return this
        }

        @Synchronized
        fun isShared(): Boolean = references > 1

        @Synchronized
        fun release(): LongArray {
            check(references > 0)
            references--
            return if (references == 0) longArrayOf(result, compact) else longArrayOf()
        }
    }

    private class Entry(
        var version: Int,
        result: Long,
        resultBytes: Long,
        compact: Long,
        var vertexCount: Int,
        var indexCount: Int,
        var indexStride: Int,
        var vertexBuffer: Long = 0L,
        var indexBuffer: Long = 0L,
    ) {
        private var storage = BlasStorage(result, resultBytes, compact)
        var result: Long
            get() = storage.result
            set(value) { storage.result = value }
        var resultBytes: Long
            get() = storage.resultBytes
            set(value) { storage.resultBytes = value }
        var compact: Long
            get() = storage.compact
            set(value) { storage.compact = value }
        var built = false
        var refits = 0
        /** 本条的 scratch 需求：只有创建/重建时问一次，别每帧对每条几何再问一遍。 */
        var scratchBytes = 0L
        var compactValid = false

        /** 最后一次出现在镜像里的帧号；太久不出现就回收（走远了不该继续占显存）。 */
        var lastSeen = 0L

        /** 交给 TLAS 的地址：压缩过就用紧凑版，否则用原始结果。 */
        fun address(): Long = if (compactValid && compact != 0L) compact else result

        fun matches(version: Int, vertexCount: Int, indexCount: Int, indexStride: Int): Boolean =
            built && this.version == version && this.vertexCount == vertexCount &&
                this.indexCount == indexCount && this.indexStride == indexStride && refits < REFIT_LIMIT

        fun share(): Entry = Entry(version, 0L, 0L, 0L, vertexCount, indexCount, indexStride,
            vertexBuffer, indexBuffer).also {
            it.storage = storage.retain()
            it.built = built
            it.refits = refits
            it.scratchBytes = scratchBytes
            it.compactValid = compactValid
        }

        fun isShared(): Boolean = storage.isShared()

        fun releaseHandles(): LongArray {
            val handles = storage.release()
            storage = BlasStorage(0L, 0L, 0L)
            compactValid = false
            built = false
            return handles
        }

        fun close() {
            for (handle in releaseHandles()) {
                if (handle != 0L) MCDR_ABI.ID3D12DestroyResource(handle)
            }
        }
    }

    private val entries = HashMap<Long, Entry>()

    /** `as: prepare` 心跳的上次打印时刻：按帧打在高帧率下会变成刷屏（每 120 帧 ≈ 0.3 秒）。 */
    private var prepareLogNanos = 0L
    /* prepare 在渲染线程、release 可能在换包/关光追的另一个线程：互斥，避免释放正在写的加速结构。 */
    private val lock = Any()
    /** BLAS 的 scratch 必须是普通 UAV 缓冲（初始 COMMON）：拿加速结构状态的缓冲当 scratch，驱动会判非法并扛不住整帧。 */
    private var scratchBuffer: D3D12GpuBuffer? = null
    private var scratchBytes = 0L
    private var instanceBuffer: D3D12GpuBuffer? = null
    private var instanceCapacity = 0
    private var tlas = 0L
    private var tlasBytes = 0L
    private var tlasScratchBuffer: D3D12GpuBuffer? = null
    private var tlasScratchBytes = 0L
    private var tlasView = 0L
    private var ready = false
    private var invalidationProxy = 0L
    private var invalidationProxyBytes = 0L
    private var invalidationGeometry: D3D12GpuBuffer? = null
    private var invalidationUpload: D3D12GpuBuffer? = null

    /** 只在第一次写实例表时打一行，免得每帧刷屏。 */
    private var reportedInstances = false
    private var liveInstances = 0

    /** 上一次打日志时的实例数：只在变化时打一行，加载期最多几十行。 */
    private var lastReportedInstances = -1
    private var frameId = 0L
    private var infoLogged = false
    private var warned = false
    private var contractChecked = false

    /**
     * 换下来但可能还被在途帧引用的资源。立刻释放会让 GPU 读到已经回收的显存——症状是设备移除
     * （`0x887A0005`）而不是报错。世界加载时 TLAS 每帧重建一次，这条路径每天都走。
     */
    private class Retired(val resource: Long, val buffer: D3D12GpuBuffer?, val published: Long)

    private val retired = ArrayDeque<Retired>()
    private var prepareCount = 0L

    /** 换下来的资源压几帧再释放：引擎允许 4 帧在途（`kFramesInFlight`），压 8 帧才跨得过去。 */
    private val retirePrepares = 8L

    /** 一帧最多新建/重建多少条 BLAS：几百条塞进一帧会让 GPU 卡住，随后被 TDR 判死。 */
    private val buildsPerFrame = 16

    /** 一帧建到这个数就打一行峰值，用来判断卡死的那一帧是不是全花在建 BLAS 上。 */
    private val buildBurstLog = 8

    /** 给着色器的 `RaytracingAccelerationStructure` SRV；0 = 这帧没有 TLAS。 */
    fun view(): Long = if (ready) tlasView else 0L

    /** 这帧 TLAS 里的实例数（成功构建时等于镜像记录数）。 */
    fun instances(): Int = liveInstances

    /** 实例表（64 B/条）的 GPU 地址；调试图的线框直接读它，见 §14.6。 */
    fun instanceTable(): Long = instanceBuffer?.handle() ?: 0L

    /** 加速结构自己占的显存（结果 + 紧凑 + scratch + 实例表），只用于统计。 */
    /** 最近一次几何契约检查的结论（给自检报告用，不进日志）。 */
    private var lastContract = "not checked"

    /** 最近一次 prepare 的计数摘要（给自检报告用）。 */
    private var lastSummary = "not prepared"
    fun contractSummary(): String = lastContract

    fun summary(): String = lastSummary

    fun vramBytes(): Long = vramBytes(HashSet())

    fun vramBytes(countedResources: MutableSet<Long>): Long = synchronized(lock) {
        var total = scratchBytes + tlasBytes + tlasScratchBytes +
            instanceCapacity.toLong() * INSTANCE_BYTES
        for (entry in entries.values) {
            if (entry.result != 0L && countedResources.add(entry.result)) total += entry.resultBytes
            if (entry.compact != 0L && countedResources.add(entry.compact)) total += entry.resultBytes
        }
        return total + invalidationProxyBytes + (if (invalidationGeometry != null) 2L * MIN_BYTES else 0L)
    }

    fun fork(records: ByteBuffer, recordCount: Int): D3D12RtAccel = synchronized(lock) {
        val candidate = D3D12RtAccel()
        val view = records.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        if (recordCount < 0 || recordCount.toLong() * RECORD_BYTES > view.capacity()) return candidate
        repeat(recordCount) { recordIndex ->
            val base = recordIndex * RECORD_BYTES
            val key = (view.getLong(base + 56) shl 4) or (view.getInt(base + 12).toLong() and 0xFL)
            val entry = entries[key] ?: return@repeat
            if (entry.matches(view.getInt(base + 44), view.getInt(base + 32),
                    view.getInt(base + 36), view.getInt(base + 40) and 0xFF) && key !in candidate.entries) {
                candidate.entries[key] = entry.share()
            }
        }
        candidate
    }

    private fun retireEntry(entry: Entry) {
        for (handle in entry.releaseHandles()) retire(handle, null)
    }

    fun invalidateGeometry() = synchronized(lock) {
        releaseInvalidationProxy()
        ready = false
        for (entry in entries.values) {
            retireEntry(entry)
        }
        entries.clear()
        retire(tlasView, null)
        retire(tlas, null)
        retire(0L, instanceBuffer)
        tlasView = 0L
        tlas = 0L
        tlasBytes = 0L
        liveInstances = 0
        instanceBuffer = null
        instanceCapacity = 0
        contractChecked = false
        lastReportedInstances = -1
    }

    /** 光追关掉时调用：整套释放，下次打开重头建。 */
    fun release() = synchronized(lock) {
        releaseInvalidationProxy()
        flushRetired(force = true)
        for (entry in entries.values) {
            entry.close()
        }
        entries.clear()
        for (handle in longArrayOf(tlas, tlasView)) {
            if (handle != 0L) MCDR_ABI.ID3D12DestroyResource(handle)
        }
        for (buffer in listOfNotNull(scratchBuffer, tlasScratchBuffer)) {
            try {
                buffer.close()
            } catch (_: Throwable) {
                // 设备已经没了的时候释放失败没有意义，计数照样清零。
            }
        }
        scratchBuffer = null
        scratchBytes = 0L
        tlas = 0L
        tlasBytes = 0L
        tlasScratchBuffer = null
        tlasScratchBytes = 0L
        tlasView = 0L
        liveInstances = 0
        instanceBuffer?.let { buffer ->
            try {
                buffer.close()
            } catch (_: Throwable) {
                // 释放失败没有补救动作，下一次打开光追会重新建。
            }
        }
        instanceBuffer = null
        instanceCapacity = 0
        prepareCount = 0L
    }

    /** 换下来的资源先压着，等 [RETIRE_PREPARES] 帧再释放（在途帧可能还在读）。 */
    private fun retire(resource: Long, buffer: D3D12GpuBuffer?) {
        if (resource == 0L && buffer == null) return
        retired.addLast(Retired(resource, buffer, prepareCount))
        flushRetired(force = false)
    }

    fun advanceFrame() = synchronized(lock) {
        prepareCount++
        flushRetired(force = false)
    }

    private fun flushRetired(force: Boolean) {
        while (retired.isNotEmpty()) {
            val item = retired.first()
            if (!force && prepareCount - item.published < retirePrepares) return
            retired.removeFirst()
            if (item.resource != 0L) MCDR_ABI.ID3D12DestroyResource(item.resource)
            item.buffer?.let { buffer ->
                try {
                    buffer.close()
                } catch (_: Throwable) {
                    // 设备已经没了的时候释放失败没有意义。
                }
            }
        }
    }

    fun checkContractOnce(view: ByteBuffer, recordCount: Int, vertexBytes: Long, indexBytes: Long): Unit =
        synchronized(lock) {
            if (contractChecked) return
            contractChecked = true
            checkContract(view, recordCount, vertexBytes, indexBytes)
        }

    fun prepare(
        device: D3D12Device,
        ctx: Long,
        records: ByteBuffer,
        recordCount: Int,
        vertexBuffer: Long,
        indexBuffer: Long,
        vertexBytes: Long = 0L,
        indexBytes: Long = 0L,
    ): Boolean = synchronized(lock) {
        ready = false
        liveInstances = 0
        if (recordCount <= 0) return false
        val view = records.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        if (recordCount.toLong() * RECORD_BYTES > view.capacity()) return false
        // 一帧一次：延迟回收靠这个计数判断「换下来的资源已经过了几帧」。
        prepareCount++
        // G3 的第一条验收（几何契约完整性）：一次就够，记录变化本身由 geometryVersion 兜住。
        if (!contractChecked) {
            contractChecked = true
            checkContract(view, recordCount, vertexBytes, indexBytes)
        }
        frameId++
        val started = System.nanoTime()
        var builds = 0
        /* refit 计数恒为 0：任何重新上传都会 bump `geometryVersion`，走不到 `PERFORM_UPDATE`（§13.1）。 */
        val refits = 0
        var compacts = 0
        // 本帧已经录了几条 build：第二条起要先插 UAV 屏障（共用同一块 scratch）。
        var builtThisFrame = 0
        var maxScratch = 0L
        // 单条 BLAS 录制的峰值耗时：§3.6 的成本预算按"每段"给，只有峰值能说明最贵的那一段。
        var peakBuildMicros = 0L

        // 1) 上一帧建好的先压缩：紧凑拷贝要等那次提交执行完，所以一律晚一帧。
        for (entry in entries.values) {
            if (entry.built && !entry.isShared() && !entry.compactValid && entry.compact != 0L &&
                entry.result != 0L) {
                if (MCDR_ABI.D3D12CopyAccelStruct(ctx, entry.compact, entry.result)) {
                    entry.compactValid = true
                    compacts++
                } else {
                    fail("compact failed: ${MCDR_ABI.D3D12LastError()}")
                }
            }
        }

        // 2) 逐条段记录：版本 / 计数变了才动 BLAS。
        val addresses = LongArray(recordCount)
        val origins = IntArray(recordCount * 3)
        // 每条记录的 SBT 命中组下标：镂空层走 HITCUTOUT，其余走 HITOPAQUE（见 D3D12RtDxr）。
        val hitGroupOf = IntArray(recordCount) { HIT_GROUP_OPAQUE }
        val live = ArrayList<Long>(recordCount)
        for (i in 0 until recordCount) {
            val base = i * RECORD_BYTES
            val originX = view.getInt(base)
            val originY = view.getInt(base + 4)
            val originZ = view.getInt(base + 8)
            val layer = view.getInt(base + 12)
            val vertexOffset = view.getLong(base + 16)
            val indexOffset = view.getLong(base + 24)
            val vertexCount = view.getInt(base + 32)
            val indexCount = view.getInt(base + 36)
            val strideAndFlags = view.getInt(base + 40)
            val version = view.getInt(base + 44)
            val node = view.getLong(base + 56)
            if (vertexCount <= 0 || indexCount <= 0) continue
            val indexStride = strideAndFlags and 0xFF
            // 记录里那 8 位是**层标志位**（SOLID=1、CUTOUT=2、TRANSLUCENT=4），不是"是否做 alpha 测试"。
            // 只有后两层需要非不透明几何：把 SOLID 也标成 GEOMETRY_FLAG_NONE，每次命中都会走候选路径，
            // 白白放弃驱动的"不透明三角形"快路（而且 DXR 侧没有 any-hit 时行为由实现决定）。
            val layerFlags = (strideAndFlags ushr 8) and 0xFF
            val useAlphaTest = (layerFlags and 0x6) != 0
            hitGroupOf[i] = if (useAlphaTest) HIT_GROUP_CUTOUT else HIT_GROUP_OPAQUE
            val key = (node shl 4) or (layer.toLong() and 0xFL)
            val geometry = MCDR_ABI.RtGeometry(
                vertexBuffer = vertexBuffer,
                vertexStride = VERTEX_STRIDE,
                vertexCount = vertexCount.toLong(),
                vertexOffset = vertexOffset,
                indexBuffer = indexBuffer,
                indexCount = indexCount.toLong(),
                indexOffset = indexOffset,
                indexStride = indexStride,
                useAlphaTest = useAlphaTest,
            )
            var entry = entries[key]
            if (entry != null && entry.isShared() &&
                !entry.matches(version, vertexCount, indexCount, indexStride)) {
                retireEntry(entry)
                entries.remove(key)
                entry = null
            }
            entry?.lastSeen = frameId
            // 新段也要受每帧上限约束：prebuild 查询会跟驱动打交道，几百次连着问同样会卡住这一帧。
            if (entry == null && builds >= buildsPerFrame) continue
            var rebuild = false
            if (entry == null) {
                val prebuild = MCDR_ABI.D3D12GetBlasPrebuildInfo(listOf(geometry))
                if (prebuild == null) {
                    fail("blas prebuild failed: ${MCDR_ABI.D3D12LastError()}")
                    continue
                }
                val resultBytes = prebuild[0].coerceAtLeast(MIN_BYTES)
                val result = MCDR_ABI.D3D12CreateAccelStructBuffer(resultBytes)
                if (result == 0L) {
                    fail("blas buffer failed: ${MCDR_ABI.D3D12LastError()}")
                    continue
                }
                // 紧凑副本目前一条三角形都遍历不到（`HIT_DISTANCE` 全黑而 `COVERAGE` 正常），
                // 在查清原因之前不分配：`address()` 因此始终交原始 BLAS（见 docs/rt/README.md §13.2）。
                val compact = 0L
                entry = Entry(version, result, resultBytes, compact, vertexCount, indexCount, indexStride)
                entry.lastSeen = frameId
                entries[key] = entry
                rebuild = true
                entry.scratchBytes = prebuild[1]
                entry.vertexBuffer = vertexBuffer
                entry.indexBuffer = indexBuffer
            } else if (!entry.matches(version, vertexCount, indexCount, indexStride)) {
                rebuild = true
                entry.built = false
                entry.compactValid = false
                if (builds >= buildsPerFrame) continue
                val prebuild = MCDR_ABI.D3D12GetBlasPrebuildInfo(listOf(geometry)) ?: continue
                entry.scratchBytes = prebuild[1]
                val requiredBytes = prebuild[0].coerceAtLeast(MIN_BYTES)
                if (entry.resultBytes < requiredBytes) {
                    val replacement = MCDR_ABI.D3D12CreateAccelStructBuffer(requiredBytes)
                    if (replacement == 0L) continue
                    retire(entry.result, null)
                    retire(entry.compact, null)
                    entry.result = replacement
                    entry.resultBytes = requiredBytes
                    entry.compact = 0L
                }
            }
            if (entry.result == 0L) continue
            // 版本、拓扑、索引宽度都没变：这条 BLAS 仍然有效，直接复用。
            // 以前这里会对"没变的段"也录一次 PERFORM_UPDATE，稳态下 384 段每帧白跑一遍
            // （按 §3.6 实测的每段 ~10 µs 算就是 4 ms 左右）；池子只要重新上传就会 bump
            // geometryVersion，所以"没变"就是真的没变。
            if (!rebuild && entry.built) {
                addresses[i] = entry.address()
                origins[i * 3] = originX
                origins[i * 3 + 1] = originY
                origins[i * 3 + 2] = originZ
                live.add(entry.address())
                continue
            }
            // 一帧建的上限：刚进世界时几百段全新建，塞进一帧会让 GPU 几秒交不出帧、被 TDR 判死
            // （实测 1 → 144 段那一帧之后 waitFence 就 TIMEOUT）。没轮到的段下一帧继续，这一帧只是缺席。
            if (builds >= buildsPerFrame) {
                continue
            }
            maxScratch = maxOf(maxScratch, entry.scratchBytes)
            val chunk = ensureScratch(device, maxScratch)
            if (chunk == 0L) {
                fail("blas scratch failed: ${MCDR_ABI.D3D12LastError()}")
                continue
            }
            // 走到这里的都是重建：没变的段在上面那条复用分支里已经 continue 了。
            // 计划里的 refit（PERFORM_UPDATE）暂时用不上 —— 池子对任何重新上传都会 bump
            // geometryVersion，分不清"只是顶点移动"和"拓扑也变了"，所以只保留整条重建这一条路。
            // 见 docs/rt/README.md §13.1
            val update = false
            // 多条 build 共用一个 scratch：D3D12 要求两次使用之间插 UAV 屏障，缺了会读到上一段还在写的
            // scratch（表现是随机的挂死/TDR）。见 docs/rt/README.md §13.6
            if (builtThisFrame > 0) {
                MCDR_ABI.D3D12Barrier(ctx, listOf(
                    MCDR_ABI.BarrierDesc(chunk, 0, 0, MCDR_ABI.BarrierKind.UAV),
                ))
            }
            val buildStart = System.nanoTime()
            if (!MCDR_ABI.D3D12BuildBlas(ctx, entry.result, chunk, listOf(geometry), update)) {
                fail("blas build failed: ${MCDR_ABI.D3D12LastError()}")
                continue
            }
            builtThisFrame++
            peakBuildMicros = maxOf(peakBuildMicros, (System.nanoTime() - buildStart) / 1000L)
            entry.built = true
            entry.version = version
            entry.vertexBuffer = vertexBuffer
            entry.indexBuffer = indexBuffer
            entry.vertexCount = vertexCount
            entry.indexCount = indexCount
            entry.indexStride = indexStride
            entry.refits = 0
            // 重建会让旧紧凑版失效，下一帧重新压一次。
            entry.compactValid = false
            builds++
            addresses[i] = entry.address()
            origins[i * 3] = originX
            origins[i * 3 + 1] = originY
            origins[i * 3 + 2] = originZ
            live.add(entry.address())
        }
        if (live.isEmpty()) return false
        // 走远的段回收：BLAS 是按段常驻的，不收回合越走越多。
        if (frameId % 60L == 0L) {
            val iterator = entries.iterator()
            while (iterator.hasNext()) {
                val item = iterator.next()
                if (frameId - item.value.lastSeen > EVICT_FRAMES) {
                    retireEntry(item.value)
                    iterator.remove()
                }
            }
        }

        // 3) TLAS：实例表在记录顺序上紧凑排列，只写平移。
        //    从这里到结尾的耗时单独计，BLAS 与 TLAS 的成本不能混在一个数里（§13.4）。
        val tlasStart = System.nanoTime()
        if (frameId % 120L == 0L && System.nanoTime() - prepareLogNanos >= 5_000_000_000L) {
            prepareLogNanos = System.nanoTime()
            // 每帧发出去的实例表只有 live 条：live 变小说明有段没建出来（射线会漏掉那一片），
            // 所以要连「还没建成的条数」一起报，只报 live 看不出是池子少了还是建不出来。
            val pending = entries.values.count { !it.built }
            IllusionLogger.infoMessage(
                "as: prepare live=${live.size} entries=${entries.size} pending=$pending builds=$builds"
            )
        }
        val count = live.size
        val table = ensureInstances(device, count) ?: return false
        writeInstances(table, count, addresses, origins, hitGroupOf)
        val tlasInfo = MCDR_ABI.D3D12GetTlasPrebuildInfo(count)
        if (tlasInfo == null) {
            fail("tlas prebuild failed: ${MCDR_ABI.D3D12LastError()}")
            return false
        }
        if (!ensureTlas(device, tlasInfo[0].coerceAtLeast(MIN_BYTES), tlasInfo[1].coerceAtLeast(MIN_BYTES))) {
            return false
        }
        if (!MCDR_ABI.D3D12BuildTlas(ctx, tlas, tlasScratchBuffer?.handle() ?: 0L, table.handle(), count)) {
            fail("tlas build failed: ${MCDR_ABI.D3D12LastError()}")
            return false
        }
        if (tlasView == 0L) {
            tlasView = MCDR_ABI.D3D12CreateAccelStructView(tlas)
            if (tlasView == 0L) {
                fail("tlas view failed: ${MCDR_ABI.D3D12LastError()}")
                return false
            }
        }
        liveInstances = count
        ready = count == recordCount
        // 实例数一变，TLAS 与实例表都换了缓冲：这一行是「设备移除」时唯一能定位到规模的那条痕迹。
        if (count != lastReportedInstances) {
            lastReportedInstances = count
            // 实例原点是「段原点 + 顶点局部坐标」这条约定的唯一可信来源：全 0 说明记录布局读错，
            // 所有段会叠到世界原点附近，射线自然一条都打不到。这里连第一个实例一起打出来。
            val ox = origins[0]
            val oy = origins[1]
            val oz = origins[2]
            IllusionLogger.infoMessage("as: tlas inst=$count bytes=$tlasBytes first=($ox,$oy,$oz)")
        }
        val totalMicros = (System.nanoTime() - started) / 1000L
        D3D12RtStats.onBlasBuilt(builds, refits)
        D3D12RtStats.onBlasCompacted(compacts, vramBytes(), scratchBytes)
        D3D12RtStats.onBlasEncoded((tlasStart - started) / 1000L, peakBuildMicros)
        D3D12RtStats.onTlasBuilt(count, (System.nanoTime() - tlasStart) / 1000L)
        lastSummary = "inst=$count blas=${entries.size} b=$builds r=$refits c=$compacts " +
            "ms=${totalMicros / 1000} vram=${vramBytes() / (1024 * 1024)}MB"
        if (!infoLogged) {
            infoLogged = true
            IllusionLogger.infoMessage(
                "as: inst=$count blas=${entries.size} b=$builds r=$refits " +
                    "c=$compacts ms=${totalMicros / 1000}"
            )
        }
        // 建得多说明这一帧在赶世界加载：留一行峰值，卡死时能看出是不是全花在建 BLAS 上。
        if (builds >= buildBurstLog) {
            IllusionLogger.infoMessage(
                "as: blas burst b=$builds of ${entries.size} peak=${peakBuildMicros}us ms=${totalMicros / 1000}"
            )
        }
        return ready
    }

    fun filterSections(
        device: D3D12Device,
        ctx: Long,
        records: ByteBuffer,
        recordCount: Int,
        invalidSections: Set<Long>,
    ): Boolean = synchronized(lock) {
        ready = false
        val proxy = if (invalidSections.isEmpty()) 0L else ensureInvalidationProxy(device, ctx)
        if (invalidSections.isNotEmpty() && proxy == 0L) return false
        val view = records.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val addresses = LongArray(recordCount + invalidSections.size)
        val origins = IntArray(addresses.size * 3)
        val hitGroups = IntArray(addresses.size)
        for (index in 0 until recordCount) {
            val base = index * RECORD_BYTES
            val node = view.getLong(base + 56)
            if (node in invalidSections) continue
            val layer = view.getInt(base + 12)
            val entry = entries[(node shl 4) or (layer.toLong() and 0xFL)] ?: return false
            if (!entry.built || entry.version != view.getInt(base + 44)) return false
            addresses[index] = entry.address()
            origins[index * 3] = view.getInt(base)
            origins[index * 3 + 1] = view.getInt(base + 4)
            origins[index * 3 + 2] = view.getInt(base + 8)
            hitGroups[index] = if ((view.getInt(base + 40) ushr 8) and 0x6 != 0) {
                HIT_GROUP_CUTOUT
            } else HIT_GROUP_OPAQUE
        }
        for ([index, node] in invalidSections.sorted().withIndex()) {
            val slot = recordCount + index
            val section = SectionPos.of(node)
            addresses[slot] = proxy
            origins[slot * 3] = section.x() * 16
            origins[slot * 3 + 1] = section.y() * 16
            origins[slot * 3 + 2] = section.z() * 16
            hitGroups[slot] = HIT_GROUP_INVALID
        }
        val count = addresses.count { it != 0L }
        if (count == 0) return false
        val table = ensureInstances(device, count) ?: return false
        writeInstances(table, count, addresses, origins, hitGroups)
        val prebuild = MCDR_ABI.D3D12GetTlasPrebuildInfo(count) ?: return false
        if (!ensureTlas(device, prebuild[0].coerceAtLeast(MIN_BYTES), prebuild[1].coerceAtLeast(MIN_BYTES))) {
            return false
        }
        if (!MCDR_ABI.D3D12BuildTlas(ctx, tlas, tlasScratchBuffer?.handle() ?: 0L, table.handle(), count)) {
            return false
        }
        if (tlasView == 0L) tlasView = MCDR_ABI.D3D12CreateAccelStructView(tlas)
        liveInstances = count
        ready = tlasView != 0L
        return ready
    }

    private fun ensureInvalidationProxy(device: D3D12Device, ctx: Long): Long {
        if (invalidationProxy != 0L) return invalidationProxy
        releaseInvalidationProxy()
        val data = ByteBuffer.allocate(MIN_BYTES.toInt()).order(ByteOrder.LITTLE_ENDIAN)
        repeat(8) { corner ->
            data.putFloat(if (corner and 1 == 0) 0f else 16f)
            data.putFloat(if (corner and 2 == 0) 0f else 16f)
            data.putFloat(if (corner and 4 == 0) 0f else 16f)
        }
        for (index in intArrayOf(0, 2, 3, 0, 3, 1, 4, 5, 7, 4, 7, 6,
                0, 1, 5, 0, 5, 4, 2, 6, 7, 2, 7, 3, 0, 4, 6, 0, 6, 2, 1, 3, 7, 1, 7, 5)) {
            data.putInt(index)
        }
        val source = device.createBuffer(Supplier { "raytracing-invalid-upload" }, GpuBuffer.USAGE_MAP_WRITE,
            MIN_BYTES) as? D3D12GpuBuffer ?: return 0L
        invalidationUpload = source
        val target = device.createBuffer(Supplier { "raytracing-invalid-geometry" }, GpuBuffer.USAGE_COPY_DST,
            MIN_BYTES) as? D3D12GpuBuffer ?: return 0L.also { releaseInvalidationProxy() }
        invalidationGeometry = target
        source.map(0L, MIN_BYTES, false, true).use { it.data().put(data.array()) }
        MCDR_ABI.D3D12CopyBuffer(ctx, source.handle(), 0L, target.handle(), 0L, MIN_BYTES)
        val geometry = MCDR_ABI.RtGeometry(
            vertexBuffer = target.handle(), vertexStride = 12L, vertexCount = 8L, vertexOffset = 0L,
            indexBuffer = target.handle(), indexOffset = 96L, indexCount = 36L, indexStride = 4,
        )
        val prebuild = MCDR_ABI.D3D12GetBlasPrebuildInfo(listOf(geometry)) ?: return 0L.also {
            releaseInvalidationProxy()
        }
        invalidationProxyBytes = prebuild[0].coerceAtLeast(MIN_BYTES)
        invalidationProxy = MCDR_ABI.D3D12CreateAccelStructBuffer(invalidationProxyBytes)
        val scratch = ensureScratch(device, prebuild[1])
        if (invalidationProxy == 0L || scratch == 0L ||
            !MCDR_ABI.D3D12BuildBlas(ctx, invalidationProxy, scratch, listOf(geometry), false)) {
            releaseInvalidationProxy()
        }
        return invalidationProxy
    }

    private fun releaseInvalidationProxy() {
        if (invalidationProxy != 0L) MCDR_ABI.ID3D12DestroyResource(invalidationProxy)
        invalidationGeometry?.close()
        invalidationUpload?.close()
        invalidationProxy = 0L
        invalidationProxyBytes = 0L
        invalidationGeometry = null
        invalidationUpload = null
    }

    /** 3×4 行主序平移矩阵 + 实例号/掩码 + 命中组下标 + BLAS 地址，每条 64 B。 */
    private fun writeInstances(
        buffer: D3D12GpuBuffer,
        count: Int,
        addresses: LongArray,
        origins: IntArray,
        hitGroupOf: IntArray,
    ) {
        val bytes = count.toLong() * INSTANCE_BYTES
        buffer.map(0L, bytes, false, true).use { slice ->
            val out = slice.data()
            var written = 0
            for (i in addresses.indices) {
                val address = addresses[i]
                if (address == 0L) continue
                val base = written * INSTANCE_BYTES
                val origin = i * 3
                val row0 = origins[origin]
                val row1 = origins[origin + 1]
                val row2 = origins[origin + 2]

                out.putFloat(base, 1.0f)
                out.putFloat(base + 4, 0.0f)
                out.putFloat(base + 8, 0.0f)
                out.putFloat(base + 12, row0.toFloat())
                out.putFloat(base + 16, 0.0f)
                out.putFloat(base + 20, 1.0f)
                out.putFloat(base + 24, 0.0f)
                out.putFloat(base + 28, row1.toFloat())
                out.putFloat(base + 32, 0.0f)
                out.putFloat(base + 36, 0.0f)
                out.putFloat(base + 40, 1.0f)
                out.putFloat(base + 44, row2.toFloat())
                out.putInt(base + 48, (i and 0xFFFFFF) or (INSTANCE_MASK shl 24))
                // 高位 24 位是贡献下标：SBT 里 opaque 记录在 0、cutout 在 1，写反了镂空层会被当不透明。
                out.putInt(base + 52, (INSTANCE_FLAGS shl 24) or (hitGroupOf[i] and 0xFFFFFF))
                // 这里要的是 BLAS 的显存地址，不是对象句柄：写句柄等于把堆指针当显存地址，实例全部指向不存在的地方。
                val gpuAddress = MCDR_ABI.D3D12GetBufferAddress(address)
                if (gpuAddress == 0L) {
                    warn("no gpu address for a blas")
                    continue
                }
                out.putLong(base + 56, gpuAddress)
                written++
            }
        }
    }

    /**
     * BLAS 的 scratch。**不能用加速结构缓冲**：它创建时就在 `RAYTRACING_ACCELERATION_STRUCTURE` 状态，
     * 而 scratch 必须处于 `UNORDERED_ACCESS`；状态不对时驱动不报错，只是把整帧判死（设备移除）。
     * 这里用普通 UAV 缓冲（初始 `COMMON`，首次写入隐式提升到 `UNORDERED_ACCESS`）。
     */
    private fun ensureScratch(device: D3D12Device, bytes: Long): Long {
        // 一次建几百段时这条会被逐条调用：按倍数增长，否则每遇到更大的段就重分配一块，
        // 单帧几十次分配把显存顶满（症状同样是设备移除）。
        val wanted = maxOf(bytes, scratchBytes * 2, MIN_BYTES)
        val current = scratchBuffer
        if (current != null && scratchBytes >= bytes) return current.handle()
        val created = try {
            device.createBuffer(Supplier { "raytracing-blas-scratch" }, UAV_USAGE, wanted) as? D3D12GpuBuffer
        } catch (t: Throwable) {
            fail("blas scratch failed: ${t.message}")
            return 0L
        }
        // 分配失败时原生返回 null：`as` 会抛一个没有 message 的 NPE，必须在这里挡住并说清是哪个缓冲。
        if (created == null) {
            fail("blas scratch allocate failed (${MCDR_ABI.D3D12LastError()})")
            return 0L
        }
        current?.let { buffer ->
            // 延迟释放：在途帧的 BLAS 构建可能还在用这块 scratch。
            retire(0L, buffer)
        }
        scratchBuffer = created
        scratchBytes = wanted
        return created.handle()
    }

    private fun ensureTlas(device: D3D12Device, resultBytes: Long, scratchSize: Long): Boolean {
        if (tlas != 0L && tlasBytes >= resultBytes && tlasScratchBytes >= scratchSize) return true
        val result = MCDR_ABI.D3D12CreateAccelStructBuffer(resultBytes)
        if (result == 0L) {
            fail("tlas buffer failed: ${MCDR_ABI.D3D12LastError()}")
            return false
        }
        // TLAS 的 scratch 同样是普通 UAV 缓冲，理由见 ensureScratch。
        val buffer = try {
            device.createBuffer(Supplier { "raytracing-tlas-scratch" }, UAV_USAGE, scratchSize) as? D3D12GpuBuffer
        } catch (t: Throwable) {
            MCDR_ABI.ID3D12DestroyResource(result)
            fail("tlas scratch failed: ${t.message}")
            return false
        }
        if (tlas != 0L) retire(tlas, null)
        tlasScratchBuffer?.let { old ->
            // 同 ensureScratch：这块 scratch 也可能还被在途帧用着。
            retire(0L, old)
        }
        // TLAS 换了缓冲，旧视图指向已销毁的资源：必须重建，否则着色器拿到悬空地址。
        if (tlasView != 0L) {
            retire(tlasView, null)
            tlasView = 0L
        }
        tlas = result
        tlasBytes = resultBytes
        tlasScratchBuffer = buffer
        tlasScratchBytes = scratchSize
        return true
    }

    private fun ensureInstances(device: D3D12Device, count: Int): D3D12GpuBuffer? {
        val current = instanceBuffer
        if (current != null && instanceCapacity >= count) return current
        val capacity = maxOf(count, instanceCapacity * 2, 64)
        val created = try {
            // 上传堆：CPU 每帧直写，GPU 侧天然就是 InstanceDescs 需要的可读状态。
            device.createBuffer(
                Supplier { "raytracing-tlas-instances" },
                GpuBuffer.USAGE_MAP_WRITE,
                capacity.toLong() * INSTANCE_BYTES,
            ) as? D3D12GpuBuffer
        } catch (t: Throwable) {
            fail("instance buffer failed: ${t.message}")
            return current
        }
        current?.let { buffer ->
            // 实例表也是上传堆：换大了不能立刻关，在途帧的 TLAS 构建还在读它。
            retire(0L, buffer)
        }
        instanceBuffer = created
        instanceCapacity = capacity
        return created
    }

    /**
     * 几何契约检查（G3 的第一条验收）：镜像记录的字段必须自洽，否则 BLAS 会按错的偏移读顶点/索引，
     * 命中结果只是"看起来没问题"的垃圾。只跑一次——记录内容的变化由 `geometryVersion` 兜住。
     */
    private fun checkContract(view: ByteBuffer, recordCount: Int, vertexBytes: Long, indexBytes: Long) {
        var empty = 0
        var badIndices = 0
        var badStride = 0
        var badVersion = 0
        var unaligned = 0
        var outOfRange = 0
        for (i in 0 until recordCount) {
            val base = i * RECORD_BYTES
            val vertexOffset = view.getLong(base + 16)
            val indexOffset = view.getLong(base + 24)
            val vertexCount = view.getInt(base + 32)
            val indexCount = view.getInt(base + 36)
            val strideAndFlags = view.getInt(base + 40)
            val version = view.getInt(base + 44)
            if (vertexCount <= 0 || indexCount <= 0) {
                empty++
                continue
            }
            val indexStride = strideAndFlags and 0xFF
            if (indexCount % 3 != 0) badIndices++
            if (indexStride != 2 && indexStride != 4) badStride++
            if (version <= 0) badVersion++
            // 原生侧按 4 字节对齐取半字（见 docs/rt/README.md 的索引读取约束），偏移不对就是错的三角形。
            if (vertexOffset and 3L != 0L || indexOffset and 3L != 0L) unaligned++
            if (vertexBytes > 0L && vertexOffset + vertexCount.toLong() * VERTEX_STRIDE > vertexBytes) {
                outOfRange++
            }
            if (indexBytes > 0L && indexOffset + indexCount.toLong() * indexStride > indexBytes) {
                outOfRange++
            }
        }
        IllusionLogger.infoMessage(
            "rt geometry: contract records=$recordCount empty=$empty indices=$badIndices stride=$badStride " +
                "version=$badVersion unaligned=$unaligned outOfRange=$outOfRange",
        )
        lastContract = "records=$recordCount empty=$empty indices=$badIndices stride=$badStride " +
            "version=$badVersion unaligned=$unaligned outOfRange=$outOfRange"
        val problems = empty + badIndices + badStride + badVersion + unaligned + outOfRange
        if (problems > 0) {
            warn("geometry contract: $problems record(s) violate the mirror layout")
        }
    }

    private fun warn(reason: String) {
        IllusionLogger.warnMessage("as: $reason")
    }

    private fun fail(reason: String) {
        if (!warned) {
            warned = true
            IllusionLogger.warnMessage("as: $reason")
        }
    }
}

/**
 * 渲染线程用的那一份加速结构（跟随镜像几何，全局唯一）。
 * 自检需要独立的一份时用 [newAccel]，两者各建各的 TLAS。
 */
internal object D3D12RtBlas {
    private var scene = 0L
    private var sceneOutput: MCDR_ABI.RtSceneOutput? = null
    private var prepareError: String? = null

    fun view(): Long = sceneOutput?.active?.takeIf { it.ready }?.acceleration ?: 0L

    fun identity(): Long = sceneOutput?.accelerationId ?: 0L

    fun instances(): Int = sceneOutput?.active?.instanceCount ?: 0

    fun instanceTable(): Long = sceneOutput?.active?.instances ?: 0L

    fun vramBytes(): Long = sceneOutput?.byteCount ?: 0L

    fun contractSummary(): String = sceneOutput?.takeIf { it.active.ready }?.let {
        "records=${it.active.blasCount} native=accepted"
    } ?: "not checked"

    fun summary(): String = sceneOutput?.active?.takeIf { it.ready }?.let {
        "inst=${it.instanceCount} blas=${it.blasCount} b=${it.buildCount} " +
            "ms=${(it.blasMicros + it.tlasMicros) / 1000} vram=${vramBytes() / (1024 * 1024)}MB"
    } ?: "not prepared"

    fun activeMirrorId(): Long = sceneOutput?.snapshotId ?: 0L

    fun sceneRevision(): Long = sceneOutput?.sceneRevision ?: 0L

    fun diagnosticState(): MCDR_ABI.RtSceneOutput? = sceneOutput

    fun sceneStats(): MCDR_ABI.RtSceneStats? = if (scene == 0L) null else MCDR_ABI.D3D12GetRtSceneStats(scene)

    fun workBudget(): MCDR_ABI.RtSceneWorkBudget? = if (scene == 0L) null else MCDR_ABI.D3D12GetRtSceneWorkBudget(scene)

    fun activeGeometryRevision(): Long = sceneOutput?.geometryRevision ?: Long.MIN_VALUE

    fun hasPendingMirror(identity: Long): Boolean = sceneOutput?.pendingSnapshotId == identity

    fun release() {
        if (scene != 0L) MCDR_ABI.D3D12ReleaseRtScene(scene)
        scene = 0L
        sceneOutput = null
        prepareError = null
    }

    fun invalidateGeometry() = release()

    fun wasGeometryRecordingCancelled(): Boolean =
        prepareError?.endsWith("rt scene: geometry recording cancelled") == true

    fun prepare(
        ctx: Long,
        geometryEpoch: Int,
        mirrorId: Long,
        versions: ByteBuffer,
        liveRevision: Long,
        frameId: Long,
    ): Boolean = prepareScene(ctx, geometryEpoch, mirrorId, versions, liveRevision, frameId, null, null)

    fun prepareCoverage(ctx: Long, geometryEpoch: Int, mirrorId: Long, versions: ByteBuffer,
                        liveRevision: Long, frameId: Long, targets: ByteBuffer, outputs: ByteBuffer): Boolean =
        prepareScene(ctx, geometryEpoch, mirrorId, versions, liveRevision, frameId, targets, outputs)

    private fun prepareScene(ctx: Long, geometryEpoch: Int, mirrorId: Long, versions: ByteBuffer,
                             liveRevision: Long, frameId: Long, targets: ByteBuffer?, outputs: ByteBuffer?,
    ): Boolean {
        prepareError = null
        if (scene == 0L) scene = MCDR_ABI.D3D12CreateRtScene()
        if (scene == 0L) return false
        val budget = D3D12Settings.rtBuildBudget
        if (!MCDR_ABI.D3D12SetRtSceneWorkBudget(scene, budget.cpuMicros.toLong(), budget.gpuMicros.toLong(),
                budget.triangles.toLong())) {
            prepareError = MCDR_ABI.D3D12LastError()
            sceneOutput = null
            return false
        }
        val result = if (targets == null) {
            MCDR_ABI.D3D12PrepareRtScene(ctx, scene, geometryEpoch.toLong(), mirrorId, liveRevision, versions, frameId)
        } else {
            MCDR_ABI.D3D12PrepareRtSceneTargets(ctx, scene, geometryEpoch.toLong(), mirrorId,
                liveRevision, versions, frameId, targets, outputs)
        }
        sceneOutput = result
        if (result == null) {
            prepareError = MCDR_ABI.D3D12LastError()
            return false
        }
        D3D12RtStats.onBlasBuilt(result.candidate.buildCount, 0)
        D3D12RtStats.onBlasCompacted(0, result.byteCount, result.candidate.scratchBytes)
        D3D12RtStats.onBlasEncoded(result.candidate.blasMicros, result.candidate.peakBuildMicros)
        if (result.active.ready) D3D12RtStats.onTlasBuilt(result.active.instanceCount, result.active.tlasMicros)
        return result.snapshotId == mirrorId && result.active.ready
    }

    /** 自检实例的几何契约：世界路径没跑过时，报告里的契约数据只能来自这一份。 */
    private var selftestContract = "none"

    /** 自检实例的加速结构计数；与上面分开存，报告里两列才不会写成一模一样的一段。 */
    private var selftestBlas = "none"

    fun selftestContractSummary(): String = selftestContract

    fun selftestBlasSummary(): String = selftestBlas

    fun noteSelftest(accel: D3D12RtAccel) {
        selftestContract = accel.contractSummary()
        selftestBlas = accel.summary()
    }

    /** 自检用的独立实例，用完必须 [D3D12RtAccel.release]。 */
    fun newAccel(): D3D12RtAccel = D3D12RtAccel()
}
