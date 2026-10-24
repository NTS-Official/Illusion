package dev.nts.expdxr.d3d12.render.core

import dev.nts.expdxr.misc.D3D12Settings
import dev.nts.expdxr.d3d12.extern.MCDR_ABI
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * P33：CPU/GPU 异步渲染上下文（Java 侧持有者）。
 *
 * <p>持有 native 侧的分区描述符分配器与 Bundle 录制器池，并维护执行 bundle
 * 录制的 worker 线程池。绘制热路径（{@link D3D12RenderPassBackend#drawMultipleIndexed}）
 * 在批量足够大时，把同一 pass + 同一 PSO + 同一 scissor 下的 core 段切给多个
 * worker，各自录制成 D3D12 BUNDLE，再由主列表按原顺序 {@code ExecuteBundle} 回放。
 *
 * <p>描述符不共享：native 把 shader-visible drawHeap 按 {@code (frameSlot, worker)}
 * 切成互不相交的区域（见 native {@code PartitionedDescriptorAllocator}），worker 只写
 * 自己的区域，因此并发写描述符不存在数据竞争。
 *
 * <p>回退开关：{@code -Ddx12.async=0} 关闭并行录制；{@code -Ddx12.async.workers=N}
 * 指定 worker 数（默认 4）。任何 native 初始化失败都会自动退回单线程录制。
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
            LOGGER.warn("[backend] P33 async init failed (descriptor=0x{} bundle=0x{}); "
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
            LOGGER.info("[backend] P33 async ready: {} workers", requested)
        }
    }

    fun available(): Boolean {
        return !this.closed && this.descriptorAlloc != 0L && this.bundlePool != 0L
            && this.workers != null
    }

    fun workerCount(): Int {
        return this.workerCount
    }

    /** worker 线程池；仅在 {@link #available()} 为 true 时非 null。 */
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
     * 返回当前帧的 drawHeap 段号，并在进入新帧时清零该段的 worker 分配游标。
     *
     * <p>必须在命令列表已 begin 之后调用——native 侧 {@code drawHeapSlotBase} 已按
     * 同一段号（{@code fenceValue % 4}）设定，worker 区域因此与主列表写入位置对齐。
     * 段号在 4 帧内轮转一轮，复用时其 GPU 工作必然已完成，reset 安全。
     *
     * @return 段号（0..3）；取不到时返回 -1（调用方退回串行）
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
     * 当前帧的原始帧号（= ctx 的 fenceValue）。bundle 录制器用它判定「是否进入新的
     * 一帧」，从而在同一帧内为每一批 drawMultipleIndexed 选一个独立的 allocator 槽位；
     * 同一帧内已 ExecuteBundle 的 bundle 在父命令列表执行完成前不能被 Reset。
     *
     * @return 帧号；取不到时返回 -1（调用方退回串行）
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

        /** 全局开关：默认开启，见配置 `[renderer] asyncCommandLists`。 */
        @JvmField
        val ENABLED: Boolean = D3D12Settings.asyncCommandLists

        @Volatile
        private var instance: D3D12AsyncContext? = null

        /** 由 {@link D3D12Device} 构造时调用；返回 false 表示不可用（自动退回串行录制）。 */
        @JvmStatic
        fun init(): Boolean {
            if (!ENABLED) {
                LOGGER.info("[backend] P33 async command lists disabled by config ([renderer] asyncCommandLists = false)")
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

        /** 由 {@link D3D12Device#close()} 调用。 */
        @JvmStatic
        fun shutdown() {
            val current = instance
            instance = null
            if (current != null) {
                current.close()
            }
        }

        /** 当前上下文；未启用或初始化失败时返回 null。 */
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
