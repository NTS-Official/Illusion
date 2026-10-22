package dev.nts.expdxr.d3d12.render.advanced.mesh

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * P137：**区块 uniform 的 CPU 侧快照**。
 *
 * <p>mesh 段派发要求把整批区块的 96 字节 `ChunkSection`（mat4 modelView / visibility /
 * atlas / 区块原点）连续摆进一个 ByteAddressBuffer（t16），但 vanilla 只按"每段一个
 * GpuBufferSlice"给我们：真正写数据的是 `DynamicUniformStorage.writeUniform(s)`，它调
 * `GpuBuffer.map(offset, length, read=false, write=true)` 后逐条 `uniform.write(byteBuffer)`。
 *
 * <p>**为什么不在绘制时回读**：P108d 记过，事后对这条 vanilla 环做"写映射读回"会把整帧
 * 打成清屏色（GPU 与 CPU 同时盯着同一块 UPLOAD 内存）。所以这里换一条完全 CPU 侧的路线 ——
 * 在 `D3D12GpuBuffer.map` 返回的视图**卸载之前**，把映射区原样抄一份留档；绘制时按
 * (buffer, offset) 直接查这份内存储备。全程不碰显存，也不需要新的 ABI。
 *
 * <p>键是"映射区起始偏移"，不是"第 i 个 96 字节块"：`blockSize` 是
 * `roundToward(96, uniformOffsetAlignment)`（本机 256），一条映射里各段的实际偏移由
 * vanilla 决定，所以查表时按"包含"关系找块内偏移。ring buffer 跨帧复用同一偏移，
 * 重复记录时直接覆盖（同偏移的后一次写入才是本帧数据）。
 */
object D3D12MeshSectionSnapshot {
    /** `ChunkSection` 的 std140 大小（DynamicUniforms.CHUNK_SECTION_UBO_SIZE）。 */
    const val SECTION_BYTES: Int = 96

    /** 留档上限：一帧几个映射区（约 350 段 × 256 B ≈ 90 KB）远小于它，超了整表清空重记。 */
    private const val MAX_TOTAL_BYTES: Long = 8L * 1024L * 1024L

    private class Blob(val offset: Long, val bytes: ByteArray, val stamp: Long)

    private val blobs = HashMap<Long, MutableList<Blob>>()

    /**
     * P141 续八：上一次查到的 (buffer → 档列表) 缓存。
     *
     * <p>地形批里所有段的 `ChunkSection` 都来自同一个 ring 缓冲，`lookupInto` 每帧被调
     * ~2000 次，如果每次都走 `HashMap<Long, _>` 就是 2000 次 `Long` 装箱 + 哈希。
     * 列表对象本身是复用的（[capture] 只增删元素，不换对象），所以缓存引用是安全的；
     * 唯一要小心的是 [clearLocked] 整表清空时把它一起作废。
     */
    private var lastBuffer = 0L
    private var lastList: MutableList<Blob>? = null

    private var totalBytes = 0L
    private var hits = 0L
    private var misses = 0L
    private var stale = 0L
    private var counter = 0L
    private var lastDispatched = 0L

    /**
     * 在 `Map` 的视图卸载前抄下 [offset, offset+length) 的原始字节。
     *
     * @param buffer native 缓冲句柄（`Dx12Object*`），与 `GpuBufferSlice.buffer()` 的身份一致
     * @param data   native 返回的直连 ByteBuffer（capacity = length）
     */
    fun capture(buffer: Long, offset: Long, length: Long, data: ByteBuffer) {
        if (buffer == 0L || length < SECTION_BYTES.toLong()) {
            return
        }
        val capacity = minOf(data.capacity().toLong(), length)
        if (capacity < SECTION_BYTES) {
            return
        }
        synchronized(this) {
            if (totalBytes + capacity > MAX_TOTAL_BYTES) {
                clearLocked()
            }
            val view = data.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            val bytes = ByteArray(capacity.toInt())
            for (i in bytes.indices) {
                bytes[i] = view.get(i)
            }
            val list = blobs.getOrPut(buffer) { ArrayList(4) }
            // 缓存里可能记着"这个 buffer 还没有档"（null）：第一次捕获后要补上，
            // 否则后续 lookupInto 会一直拿着 null 直接判 miss。
            if (buffer == this.lastBuffer) {
                this.lastList = list
            }
            // ring buffer 跨帧复用同一偏移：同偏移的旧档必须换掉，并把它的字节数还回去，
            // 否则 totalBytes 只增不减，几十帧后就会误触上限把刚写的档一起清掉。
            val iterator = list.iterator()
            while (iterator.hasNext()) {
                val old = iterator.next()
                if (old.offset == offset) {
                    totalBytes -= old.bytes.size.toLong()
                    iterator.remove()
                }
            }
            list.add(Blob(offset, bytes, ++counter))
            totalBytes += capacity
        }
    }

    /**
     * 把 [buffer, offset) 起的 96 字节**直接抄进** [dst] 的 [dstOffset]，成功返回 true。
     *
     * <p>P141 续八：原先的 `lookup` 每段要分配两个 96 字节数组（`copyOfRange` + `copyOf`）、
     * 再往一张 `lastGood` 哈希表里塞一份 —— 那张表早已没有任何读取点（它的跨帧兜底会把
     * 别处的 96 B 当成这一段的，P141 实测导致"重复层"，所以被弃用）。1231 段/帧就是 2400
     * 多次分配 + 1200 次 map 写，纯粹是 GC 压力；调用方反正只关心"这 96 B 的字节"，直接
     * 落到整批缓冲里最省。命中时只读档案，不改任何状态。
     *
     * <p>[stale] 现在统计"比上次 [markDispatched] 还旧的档"（诊断用），与旧 `minStamp`
     * 语义不同 —— 旧参数已删除：vanilla 的 `DynamicUniformStorage.writeUniform` 对内容未变的
     * uniform 有 `lastUniform.equals` 短路，静态帧拿不到"新"档，拒掉旧档会退化成整帧 IMR。
     */
    fun lookupInto(buffer: Long, offset: Long, dst: ByteArray, dstOffset: Int): Boolean {
        synchronized(this) {
            val list = if (buffer == this.lastBuffer) {
                this.lastList
            } else {
                val found = blobs[buffer]
                this.lastBuffer = buffer
                this.lastList = found
                found
            }
            if (list != null) {
                // P141 续八：倒序扫描用 while，`list.indices.reversed()` 每次都会新建一个
                // IntProgression（每帧 2000 次分配），这里只是从尾往头走。
                var i = list.size - 1
                while (i >= 0) {
                    val blob = list[i]
                    val delta = offset - blob.offset
                    if (delta >= 0 && delta + SECTION_BYTES <= blob.bytes.size) {
                        // 只要这个 (buffer, offset) 上**最近一次**捕获是这段字节，它就是当前内容：
                        // 每次 map/unmap 都会被 [capture] 记下来，所以"最近一次写入"一定是我们看到的这档。
                        // 曾经用 lastGood 顶替"本帧没重新写"的情况，但 lastGood 只按 (buffer, offset)
                        // 索引：ring 里同一个 offset 换给另一个段时，会把**上一个段**的 96 B 拿来用，
                        // 于是几何被套上别处的变换 → 画面里出现位移的"重复层"（P141 后续实测）。
                        if (blob.stamp <= this.lastDispatched) {
                            stale++
                        }
                        hits++
                        blob.bytes.copyInto(dst, dstOffset, delta.toInt(),
                            delta.toInt() + SECTION_BYTES)
                        return true
                    }
                    i--
                }
            }
            misses++
            return false
        }
    }

    /** 上次派发那一刻的捕获序号：诊断里用来数"比它旧"的命中有多少。 */
    val lastDispatchStamp: Long
        get() = synchronized(this) { lastDispatched }

    /** 一次 mesh 派发成功发出后调用，把"必须是本帧新档"的基准推到当前位置。 */
    fun markDispatched() {
        synchronized(this) {
            lastDispatched = counter
        }
    }

    /** 命中/未命中统计（诊断用）。 */
    fun stats(): String {
        synchronized(this) {
            return "sectionSnapshot hits=$hits misses=$misses stale=$stale blobs=${blobs.values.sumOf { it.size }} bytes=$totalBytes"
        }
    }

    fun clear() {
        synchronized(this) {
            clearLocked()
        }
    }

    private fun clearLocked() {
        blobs.clear()
        // 缓存指向刚被清空的列表，必须一起作废，否则会继续命中已被丢弃的档。
        this.lastBuffer = 0L
        this.lastList = null
        totalBytes = 0L
    }
}

