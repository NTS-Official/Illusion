package io.github.dxegp.experimental.raytracing

import io.github.dxegp.d3d12.render.advanced.mesh.D3D12SectionGeometry
import net.minecraft.world.level.chunk.GlobalPalette
import net.minecraft.world.level.chunk.HashMapPalette
import net.minecraft.world.level.chunk.LinearPalette
import net.minecraft.world.level.chunk.Palette
import net.minecraft.world.level.chunk.PalettedContainer
import net.minecraft.world.level.chunk.PalettedContainerRO
import net.minecraft.world.level.chunk.SingleValuePalette
import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference

object D3D12RtPaletteEntries {
    private val dataField = PalettedContainer::class.java.getDeclaredField("data").apply { isAccessible = true }
    private val entriesField = dataField.type.getDeclaredField("palette").apply { isAccessible = true }
    private val singleValue = SingleValuePalette::class.java.getDeclaredField("value").apply { isAccessible = true }
    private class Key(value: Any, queue: ReferenceQueue<Any>? = null) : WeakReference<Any>(value, queue) {
        private val identityHash = System.identityHashCode(value)
        override fun hashCode(): Int = identityHash
        override fun equals(other: Any?): Boolean = this === other ||
            other is Key && get()?.let { it === other.get() } == true
    }

    private val released = ReferenceQueue<Any>()
    private val inputs = hashMapOf<Key, Input>()
    private val failedPacked = hashSetOf<Key>()
    private val supportedTypes = setOf(SingleValuePalette::class.java, LinearPalette::class.java,
        HashMapPalette::class.java, GlobalPalette::class.java)
    internal class Input(var version: Long = 0L, var active: Int = 0, var failureRevision: Long = 0L,
                         var failed: Boolean = false) {
        val available: Boolean get() = active == 0 && !failed
    }

    internal class Mutation(val entries: Palette<*>, val input: Input, val version: Long,
                            val failureRevision: Long, val recover: Boolean)

    class Copy internal constructor(internal val entries: Palette<*>, internal val input: Input,
                                    internal val version: Long, internal val available: Boolean,
                                    internal val registry: D3D12RtIdMaps.Copy?)

    private val insertions = ThreadLocal<Insertion?>()

    class Insertion internal constructor(internal val entries: Palette<*>) : AutoCloseable {
        private val previous = insertions.get()
        internal var publication: D3D12SectionGeometry.PaletteEntryPublication? = null

        init { insertions.set(this) }

        fun complete() { publication?.complete() }

        override fun close() {
            try { publication?.close() }
            finally { if (previous == null) insertions.remove() else insertions.set(previous) }
        }
    }

    @JvmStatic
    fun insertion(entries: Palette<*>): Insertion = Insertion(entries)

    @JvmStatic
    fun writeInsertion(entries: Palette<*>) {
        val insertion = checkNotNull(insertions.get())
        check(insertion.entries === entries && insertion.publication == null)
        insertion.publication = D3D12SectionGeometry.beginPaletteEntryAppend(entries)
    }

    internal fun current(palette: PalettedContainer<*>): Palette<*>? =
        if (palette.javaClass == PalettedContainer::class.java) dataEntries(dataField.get(palette)) else null

    internal fun dataEntries(data: Any): Palette<*>? =
        if (data.javaClass == dataField.type) entriesField.get(data) as? Palette<*> else null

    internal fun available(entries: Palette<*>?): Boolean = entries != null && supported(entries) && input(entries).available

    internal fun canonical(entries: Palette<*>): Boolean = entries.javaClass in supportedTypes

    private fun supported(entries: Palette<*>): Boolean = canonical(entries) &&
        (entries.javaClass != GlobalPalette::class.java ||
            D3D12RtIdMaps.registry(entries)?.let(D3D12RtIdMaps::owner) != null)

    internal fun sameSingleValue(entries: SingleValuePalette<*>, value: Any?): Boolean = singleValue.get(entries) === value

    private fun input(entries: Palette<*>): Input {
        reap()
        return inputs.getOrPut(Key(entries, released), ::Input)
    }

    internal fun begin(entries: Palette<*>, recover: Boolean): Mutation {
        val input = input(entries)
        val canRecover = recover && input.active == 0
        input.active++
        input.version++
        return Mutation(entries, input, input.version, input.failureRevision, canRecover)
    }

    internal fun finish(mutation: Mutation, completed: Boolean) {
        val input = mutation.input
        check(input.active > 0)
        if (!completed) {
            input.failureRevision++
            input.failed = true
        } else if (mutation.recover && input.active == 1 && input.version == mutation.version &&
            input.failureRevision == mutation.failureRevision) {
            input.failed = false
        }
        input.active--
        input.version++
    }

    private fun fail(entries: Palette<*>) {
        val input = input(entries)
        input.version++
        input.failureRevision++
        input.failed = true
    }

    internal fun beginCopy(entries: Palette<*>): Copy {
        val input = input(entries)
        return Copy(entries, input, input.version, available(entries),
            D3D12RtIdMaps.registry(entries)?.let(D3D12RtIdMaps::beginCopy))
    }

    internal fun finishCopy(copy: Copy, result: Palette<*>) {
        if (result === copy.entries || result.javaClass == GlobalPalette::class.java) return
        if (!supported(result) || !matches(copy)) fail(result)
    }

    internal fun matches(copy: Copy): Boolean = copy.available && input(copy.entries) === copy.input &&
        available(copy.entries) && copy.input.version == copy.version && copy.registry?.let(D3D12RtIdMaps::matches) != false

    internal fun finishPack(copy: Copy?, sameData: Boolean, result: PalettedContainerRO.PackedData<*>) {
        reap()
        if (copy == null || !sameData || !matches(copy)) failedPacked.add(Key(result, released))
    }

    internal fun finishUnpack(packed: PalettedContainerRO.PackedData<*>, palette: PalettedContainer<*>) {
        reap()
        if (Key(packed) in failedPacked) current(palette)?.let { entries ->
            if (entries.javaClass == GlobalPalette::class.java) D3D12RtPaletteStorage.current(palette)?.let(D3D12RtPaletteStorage::fail)
            else fail(entries)
        }
    }

    private fun reap() {
        while (true) {
            val key = released.poll() ?: return
            inputs.remove(key)
            failedPacked.remove(key)
        }
    }
}
