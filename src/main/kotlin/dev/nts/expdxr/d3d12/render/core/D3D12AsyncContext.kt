package dev.nts.expdxr.d3d12.render.core

import dev.nts.expdxr.misc.settings.D3D12Settings
import dev.nts.expdxr.d3d12.extern.MCDR_ABI
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * CPU/GPU 异步录制上下文：持有 native 分区描述符分配器与 bundle 池，管 worker 线程池。
 * 描述符按 (frameSlot, worker) 分区、互不相交；native 初始化失败即退回单线程录制。
 */
class D3D12AsyncContext private constructor() : AutoCloseable {
    private val workerCount: Int
    private val descriptorAlloc: Long
    private val bundlePool: Long
    private var workers: ExecutorService? = null
    private var lastResetFrameSlot = -1

    @Volatile
    private var closed = false

    init {
        val requested = D3D12Settings.asyncWorkers
        this.workerCount = requested
        this.descriptorAlloc = MCDR_ABI.D3D12AsyncDescriptorCreate(requested)
        this.bundlePool = MCDR_ABI.D3D12AsyncBundlePoolCreate(requested)
        if (this.descriptorAlloc == 0L || this.bundlePool == 0L) {
            LOGGER.warn("async init failed (descriptor=0x{} bundle=0x{}); "
                    + "falling back to serial recording",
                this.descriptorAlloc.toULong().toString(16), this.bundlePool.toULong().toString(16))
        } else {
            val factory: ThreadFactory = object : ThreadFactory {
                private val seq = AtomicInteger()

                override fun newThread(r: Runnable): Thread {
                    val t = Thread(r, "backend-async-worker-${this.seq.getAndIncrement()}")
                    t.isDaemon = true
                    return t
                }
            }
            this.workers = Executors.newFixedThreadPool(requested, factory)
            LOGGER.info("async ready: {} workers", requested)
        }
    }

    fun available(): Boolean {
        return !this.closed && this.descriptorAlloc != 0L && this.bundlePool != 0L
            && this.workers != null
    }

    fun workerCount(): Int {
        return this.workerCount
    }

    /** worker 线程池；仅在 [available] 为 true 时非 null。 */
    fun workers(): ExecutorService? {
        return this.workers
    }

    fun descriptorAlloc(): Long {
        return this.descriptorAlloc
    }

    fun bundlePool(): Long {
        return this.bundlePool
    }

    /**
     * 当前帧的 drawHeap 段号（进入新帧时 reset 该段游标）；必须在命令列表 begin 之后调用，
     * 因为 native 的 `drawHeapSlotBase` 按同一段号（`fenceValue % 4`）设定。取不到返回 -1 → 退回串行。
     */
    fun frameSlot(ctx: Long): Int {
        val slot = MCDR_ABI.D3D12AsyncCurrentFrameSlot(ctx)
        if (slot < 0) {
            return -1
        }
        if (slot != this.lastResetFrameSlot) {
            MCDR_ABI.D3D12AsyncDescriptorResetFrame(this.descriptorAlloc, slot)
            this.lastResetFrameSlot = slot
        }
        return slot
    }

    /**
     * 当前帧号（= ctx 的 fenceValue），bundle 用它判定是否进入新帧并选 allocator 槽位。
     * 同帧内已 ExecuteBundle 的 bundle 在父命令列表执行完成前不能被 Reset。取不到返回 -1。
     */
    fun frameValue(ctx: Long): Long {
        return MCDR_ABI.D3D12AsyncCurrentFrameValue(ctx)
    }

    override fun close() {
        if (this.closed) {
            return
        }
        this.closed = true
        val pool = this.workers
        if (pool != null) {
            pool.shutdown()
            try {
                if (!pool.awaitTermination(2L, TimeUnit.SECONDS)) {
                    pool.shutdownNow()
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                pool.shutdownNow()
            }
        }
        // 先停 worker 再销毁 native 资源：销毁后 worker 再调 JNI 会拿到悬空指针。
        if (this.bundlePool != 0L) {
            MCDR_ABI.D3D12AsyncBundlePoolDestroy(this.bundlePool)
        }
        if (this.descriptorAlloc != 0L) {
            MCDR_ABI.D3D12AsyncDescriptorDestroy(this.descriptorAlloc)
        }
    }

    companion object {
        private val LOGGER: Logger = LoggerFactory.getLogger("dxrmc")

        /** 全局开关，默认开启，见配置 `[renderer] asyncCommandLists`。 */
        @JvmField
        val ENABLED: Boolean = D3D12Settings.asyncCommandLists

        @Volatile
        private var instance: D3D12AsyncContext? = null

        @JvmStatic
        fun init(): Boolean {
            if (!ENABLED) {
                LOGGER.info("async command lists disabled by config ([renderer] asyncCommandLists = false)")
                return false
            }
            if (instance != null) {
                return true
            }
            val created = D3D12AsyncContext()
            if (!created.available()) {
                created.close()
                return false
            }
            instance = created
            return true
        }

        @JvmStatic
        fun shutdown() {
            val current = instance
            instance = null
            if (current != null) {
                current.close()
            }
        }

        @JvmStatic
        fun get(): D3D12AsyncContext? {
            return instance
        }



        /** 运行时统计：本帧回退到串行路径的批次数（仅诊断用）。 */
        private val fallbackCount = AtomicInteger()

        @JvmStatic
        fun countFallback() {
            fallbackCount.incrementAndGet()
        }

        @JvmStatic
        fun fallbackCount(): Int {
            return fallbackCount.get()
        }
    }
}
