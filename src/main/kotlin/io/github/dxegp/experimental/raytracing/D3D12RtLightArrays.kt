package io.github.dxegp.experimental.raytracing

import net.minecraft.world.level.chunk.DataLayer
import java.util.WeakHashMap
import java.util.function.Supplier

object D3D12RtLightArrays {
    private val dataField = DataLayer::class.java.getDeclaredField("data").apply { isAccessible = true }
    private class Ownership(var owned: Boolean = true, var escaped: Boolean = false)
    internal enum class Use { CONSTRUCTED, BORROWED, ESCAPED }
    private val arrays = WeakHashMap<ByteArray, Ownership>()
    private val externalReads = WeakHashMap<DataLayer, Int>()
    private val access = ThreadLocal<Access>()
    private var publisher: ((DataLayer, ByteArray, Use) -> Unit)? = null
    private var reader: ((DataLayer, Supplier<ByteArray>) -> ByteArray)? = null

    class Access internal constructor(internal val layer: DataLayer, internal val previous: Access?) : AutoCloseable {
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
    fun borrow(layer: DataLayer): Access = Access(layer, access.get()).also(access::set)

    @JvmStatic
    fun constructed(layer: DataLayer, bytes: ByteArray) = publish(layer, bytes, Use.CONSTRUCTED)

    @JvmStatic
    fun read(layer: DataLayer, original: Supplier<ByteArray>): ByteArray {
        if (access.get()?.layer === layer) return expose(layer, original.get())
        val currentReader = synchronized(D3D12RtLightArrays::class.java) {
            externalReads[layer] = (externalReads[layer] ?: 0) + 1
            reader
        }
        val exposed = Supplier { expose(layer, original.get()) }
        try {
            return currentReader?.invoke(layer, exposed) ?: exposed.get()
        } catch (problem: Throwable) {
            try {
                current(layer)?.let { publish(layer, it, Use.ESCAPED) }
            } catch (publication: Throwable) {
                problem.addSuppressed(publication)
            }
            throw problem
        } finally {
            synchronized(D3D12RtLightArrays::class.java) {
                val remaining = checkNotNull(externalReads[layer]) - 1
                if (remaining == 0) externalReads.remove(layer) else externalReads[layer] = remaining
            }
        }
    }

    @JvmStatic
    fun expose(layer: DataLayer, bytes: ByteArray): ByteArray {
        publish(layer, bytes, if (access.get()?.layer === layer) Use.BORROWED else Use.ESCAPED)
        return bytes
    }

    @JvmStatic
    @Synchronized
    fun own(layer: DataLayer, bytes: ByteArray) {
        check(current(layer) === bytes)
        val ownership = arrays.getOrPut(bytes, ::Ownership)
        ownership.owned = true
    }

    internal fun current(layer: DataLayer): ByteArray? = dataField.get(layer) as? ByteArray

    @JvmStatic
    @Synchronized
    internal fun available(layer: DataLayer): Boolean = layer !in externalReads &&
        (current(layer)?.let { bytes -> arrays[bytes]?.let { it.owned && !it.escaped } ?: true } ?: true)

    @JvmStatic
    @Synchronized
    internal fun install(next: (DataLayer, ByteArray, Use) -> Unit, read: (DataLayer, Supplier<ByteArray>) -> ByteArray) {
        publisher = next
        reader = read
    }

    private fun publish(layer: DataLayer, bytes: ByteArray, use: Use) {
        val currentPublisher = synchronized(D3D12RtLightArrays::class.java) {
            publisher ?: run {
                record(bytes, use)
                return
            }
        }
        currentPublisher(layer, bytes, use)
    }

    @JvmStatic
    @Synchronized
    internal fun record(bytes: ByteArray, use: Use): Boolean {
        if (use == Use.BORROWED) return false
        val ownership = arrays.getOrPut(bytes, ::Ownership)
        val before = ownership.owned && !ownership.escaped
        if (use == Use.CONSTRUCTED) ownership.owned = false else ownership.escaped = true
        return before && !(ownership.owned && !ownership.escaped)
    }
}
