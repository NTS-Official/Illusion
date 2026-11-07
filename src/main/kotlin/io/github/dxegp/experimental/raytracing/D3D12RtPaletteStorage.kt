package io.github.dxegp.experimental.raytracing

import net.minecraft.util.BitStorage
import net.minecraft.util.SimpleBitStorage
import net.minecraft.util.ZeroBitStorage
import net.minecraft.world.level.chunk.PalettedContainer
import net.minecraft.world.level.chunk.PalettedContainerRO
import net.minecraft.world.level.chunk.Strategy
import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference
import java.util.WeakHashMap

object D3D12RtPaletteStorage {
    private val dataField = PalettedContainer::class.java.getDeclaredField("data").apply { isAccessible = true }
    private val storageField = dataField.type.getDeclaredField("storage").apply { isAccessible = true }
    private val inputs = WeakHashMap<BitStorage, Input>()
    private val arrayInputs = WeakHashMap<LongArray, Input>()
    private class PackedKey(packed: PalettedContainerRO.PackedData<*>, queue: ReferenceQueue<PalettedContainerRO.PackedData<*>>? = null) :
        WeakReference<PalettedContainerRO.PackedData<*>>(packed, queue) {
        private val identityHash = System.identityHashCode(packed)
        override fun hashCode(): Int = identityHash
        override fun equals(other: Any?): Boolean = this === other ||
            other is PackedKey && get()?.let { it === other.get() } == true
    }
    private val releasedPacked = ReferenceQueue<PalettedContainerRO.PackedData<*>>()
    private val failedPacked = hashSetOf<PackedKey>()

    internal class Input(var version: Long = 0L, var active: Int = 0, var failureRevision: Long = 0L,
                         var failed: Boolean = false) {
        val available: Boolean get() = active == 0 && !failed
    }

    internal class Mutation(val storage: BitStorage?, val array: LongArray?, val input: Input, val version: Long,
                            val failureRevision: Long, val recover: Boolean)

    class Copy internal constructor(internal val storage: BitStorage, internal val input: Input,
                                    internal val version: Long, internal val available: Boolean)

    class Pack internal constructor(internal val palette: PalettedContainer<*>, internal val data: Any,
                                    internal val copy: Copy, internal val entries: D3D12RtPaletteEntries.Copy?,
                                    internal val registry: D3D12RtIdMaps.Copy?)

    class DataCopy internal constructor(internal val storage: Copy?, internal val entries: D3D12RtPaletteEntries.Copy,
                                        internal val registry: D3D12RtIdMaps.Copy? = null, internal val materialized: Boolean = false)

    internal fun current(palette: PalettedContainer<*>): BitStorage? =
        if (palette.javaClass == PalettedContainer::class.java) storageField.get(dataField.get(palette)) as? BitStorage else null

    internal fun dataStorage(data: Any): BitStorage? =
        if (data.javaClass == dataField.type) storageField.get(data) as? BitStorage else null

    private fun supported(storage: BitStorage): Boolean = storage.javaClass == SimpleBitStorage::class.java ||
        storage.javaClass == ZeroBitStorage::class.java

    internal fun available(storage: BitStorage?): Boolean = storage != null && supported(storage) &&
        D3D12RtPaletteArrays.available(storage) && input(storage).available

    private fun input(storage: BitStorage): Input = D3D12RtPaletteArrays.current(storage)?.let {
        arrayInputs.getOrPut(it, ::Input)
    } ?: inputs.getOrPut(storage, ::Input)

    internal fun begin(storage: BitStorage, recover: Boolean): Mutation {
        return begin(storage, D3D12RtPaletteArrays.current(storage), input(storage), recover)
    }

    internal fun beginArray(array: LongArray, recover: Boolean): Mutation =
        begin(null, array, arrayInputs.getOrPut(array, ::Input), recover)

    private fun begin(storage: BitStorage?, array: LongArray?, input: Input, recover: Boolean): Mutation {
        val canRecover = recover && input.active == 0
        input.active++
        input.version++
        return Mutation(storage, array, input, input.version, input.failureRevision, canRecover)
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

    internal fun fail(storage: BitStorage) {
        val input = input(storage)
        input.failureRevision++
        input.version++
        input.failed = true
    }

    internal fun beginCopy(storage: BitStorage): Copy {
        val input = input(storage)
        return Copy(storage, input, input.version, available(storage))
    }

    internal fun finishCopy(copy: Copy, result: BitStorage) {
        D3D12RtPaletteArrays.ownCopy(copy.storage, result)
        finishCopyInto(copy, result)
    }

    internal fun finishCopyInto(copy: Copy, result: BitStorage) {
        if (!supported(result) || !matches(copy)) fail(result)
    }

    private fun matches(copy: Copy): Boolean = copy.available && input(copy.storage) === copy.input &&
        available(copy.storage) && copy.input.version == copy.version

    internal fun beginPack(palette: PalettedContainer<*>, strategy: Strategy<*>? = null): Pack? = current(palette)?.let {
        Pack(palette, dataField.get(palette), beginCopy(it),
            D3D12RtPaletteEntries.current(palette)?.let(D3D12RtPaletteEntries::beginCopy),
            strategy?.let { source -> D3D12RtIdMaps.beginCopy(D3D12RtIdMaps.strategyRegistry(source)) })
    }

    internal fun finishPack(pack: Pack?, result: PalettedContainerRO.PackedData<*>) {
        reapPacked()
        val sameData = pack != null && dataField.get(pack.palette) === pack.data
        if (pack == null || !sameData || !matches(pack.copy)) failedPacked.add(PackedKey(result, releasedPacked))
        D3D12RtPaletteEntries.finishPack(pack?.entries,
            sameData && pack.registry?.let(D3D12RtIdMaps::matches) != false, result)
    }

    internal fun finishUnpack(packed: PalettedContainerRO.PackedData<*>, palette: PalettedContainer<*>) {
        reapPacked()
        if (PackedKey(packed) in failedPacked) current(palette)?.let(::fail)
        D3D12RtPaletteEntries.finishUnpack(packed, palette)
    }

    private fun reapPacked() {
        while (true) failedPacked.remove(releasedPacked.poll() ?: return)
    }
}
