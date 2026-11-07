package io.github.dxegp.experimental.raytracing

import net.minecraft.util.BitStorage
import net.minecraft.util.SimpleBitStorage
import net.minecraft.util.ZeroBitStorage
import java.lang.reflect.Modifier
import java.util.WeakHashMap
import java.util.stream.LongStream

object D3D12RtPaletteArrays {
    private val dataField = SimpleBitStorage::class.java.getDeclaredField("data").apply { isAccessible = true }
    private class Ownership(var owned: Boolean = false, var escaped: Boolean = false)
    internal enum class Use { ALLOCATED, RETAINED, ESCAPED }
    private val arrays = WeakHashMap<LongArray, Ownership>()
    private val streamArrays = WeakHashMap<LongArray, Boolean>()
    private val access = ThreadLocal<Access>()
    private var publisher: ((LongArray, Use) -> Unit)? = null

    class Access internal constructor(internal val storage: BitStorage, internal val previous: Access?) : AutoCloseable {
        private val thread = Thread.currentThread()
        private var closed = false

        override fun close() {
            if (closed) return
            check(Thread.currentThread() === thread && access.get() === this)
            if (previous == null) access.remove() else access.set(previous)
            closed = true
        }
    }

    @JvmStatic
    fun borrow(storage: BitStorage): Access = Access(storage, access.get()).also(access::set)

    @JvmStatic
    fun constructed(storage: BitStorage, supplied: LongArray?) {
        current(storage)?.let { publish(it, if (supplied == null) Use.ALLOCATED else Use.RETAINED) }
    }

    @JvmStatic
    fun expose(storage: BitStorage) {
        if (access.get()?.storage !== storage) current(storage)?.let { publish(it, Use.ESCAPED) }
    }

    @JvmStatic
    @Synchronized
    fun unpacked(stream: LongStream, bytes: LongArray) {
        val method = stream.javaClass.getMethod("toArray")
        if (stream.javaClass.module === LongStream::class.java.module &&
            method.declaringClass.name == "java.util.stream.LongPipeline" && Modifier.isFinal(method.modifiers)) {
            streamArrays[bytes] = true
        }
    }

    internal fun current(storage: BitStorage): LongArray? =
        if (storage.javaClass == SimpleBitStorage::class.java) dataField.get(storage) as LongArray else null

    @JvmStatic
    @Synchronized
    internal fun available(storage: BitStorage): Boolean = storage.javaClass == ZeroBitStorage::class.java ||
        current(storage)?.let { arrays[it]?.let { ownership -> ownership.owned && !ownership.escaped } } == true

    @JvmStatic
    @Synchronized
    internal fun ownCopy(source: BitStorage, result: BitStorage) {
        val original = current(source) ?: return
        val copied = current(result) ?: return
        if (original !== copied) arrays.getOrPut(copied, ::Ownership).owned = true
    }

    @JvmStatic
    @Synchronized
    internal fun install(next: (LongArray, Use) -> Unit) {
        publisher = next
    }

    private fun publish(bytes: LongArray, use: Use) {
        val currentPublisher = synchronized(D3D12RtPaletteArrays::class.java) {
            publisher ?: run {
                record(bytes, use)
                return
            }
        }
        currentPublisher(bytes, use)
    }

    @JvmStatic
    @Synchronized
    internal fun record(bytes: LongArray, use: Use): Boolean {
        val ownership = arrays.getOrPut(bytes, ::Ownership)
        val before = ownership.owned && !ownership.escaped
        when (use) {
            Use.ALLOCATED -> ownership.owned = true
            Use.RETAINED -> ownership.owned = streamArrays.remove(bytes) == true
            Use.ESCAPED -> ownership.escaped = true
        }
        return before && !(ownership.owned && !ownership.escaped)
    }
}
