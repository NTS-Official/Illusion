package io.github.dxegp.experimental.raytracing

import io.github.dxegp.d3d12.render.advanced.mesh.D3D12SectionGeometry
import it.unimi.dsi.fastutil.ints.IntOpenHashSet
import it.unimi.dsi.fastutil.objects.Reference2IntMap
import net.minecraft.core.DefaultedMappedRegistry
import net.minecraft.core.Holder
import net.minecraft.core.IdMap
import net.minecraft.core.IdMapper
import net.minecraft.core.MappedRegistry
import net.minecraft.util.BitStorage
import net.minecraft.world.level.chunk.GlobalPalette
import net.minecraft.world.level.chunk.Palette
import net.minecraft.world.level.chunk.Strategy
import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference
import java.util.function.IntSupplier
import java.util.function.Supplier
import java.util.IdentityHashMap

object D3D12RtIdMaps {
    private fun field(type: Class<*>, name: String) = type.getDeclaredField(name).apply { isAccessible = true }
    private val mapperValues = field(IdMapper::class.java, "idToT")
    private val mapperIds = field(IdMapper::class.java, "tToId")
    private val registryValues = field(MappedRegistry::class.java, "byId")
    private val registryIds = field(MappedRegistry::class.java, "toId")
    private val defaultValue = field(DefaultedMappedRegistry::class.java, "defaultValue")
    private val holderValue = field(Holder.Reference::class.java, "value")
    private val globalRegistry = field(GlobalPalette::class.java, "registry")
    private val strategyRegistry = field(Strategy::class.java, "globalMap")
    private val holderMapType = Class.forName("net.minecraft.core.Registry\$1", false, javaClass.classLoader)
    private val holderMapOwner = field(holderMapType, "this\$0")
    private val clearableType = Class.forName(
        "net.neoforged.neoforge.registries.NeoForgeRegistryCallbacks\$BlockCallbacks\$ClearableObjectIntIdentityMap",
        false, javaClass.classLoader)
    private val strategyTypes = setOf(Class.forName("net.minecraft.world.level.chunk.Strategy\$1", false, javaClass.classLoader),
        Class.forName("net.minecraft.world.level.chunk.Strategy\$2", false, javaClass.classLoader))
    private class Key(value: Any, queue: ReferenceQueue<Any>? = null) : WeakReference<Any>(value, queue) {
        private val identityHash = System.identityHashCode(value)
        override fun hashCode(): Int = identityHash
        override fun equals(other: Any?): Boolean = this === other ||
            other is Key && get()?.let { it === other.get() } == true
    }

    private val released = ReferenceQueue<Any>()
    private val inputs = hashMapOf<Key, Input>()
    private class StorageUse(val input: D3D12RtPaletteStorage.Input, val version: Long, val ids: IntArray)
    private val storageUses = hashMapOf<Key, StorageUse>()
    private val writes = ThreadLocal<Write?>()

    internal class Input(var version: Long = 0L, var failureRevision: Long = 0L, var failed: Boolean = false) {
        val active = linkedSetOf<Mutation>()
    }

    internal class Mutation(val owner: Any, val input: Input, val version: Long, val failureRevision: Long,
                            val recover: Boolean, var full: Boolean = false) {
        val ids = IntOpenHashSet()
    }

    class Copy internal constructor(internal val owner: Any?, internal val input: Input?, internal val version: Long,
                                    internal val available: Boolean)

    class Write internal constructor(internal val owner: Any, internal val recover: Boolean) : AutoCloseable {
        private val previous = writes.get()
        internal var publication: D3D12SectionGeometry.IdMapPublication? = null

        init { writes.set(this) }

        fun complete() { publication?.complete() }

        override fun close() {
            try { publication?.close() }
            finally { if (previous == null) writes.remove() else writes.set(previous) }
        }
    }

    @JvmStatic
    fun writing(owner: Any, recover: Boolean): Write = Write(owner, recover)

    internal fun canonical(owner: Any): Boolean = owner.javaClass == IdMapper::class.java ||
        owner.javaClass == clearableType || owner.javaClass == MappedRegistry::class.java ||
        owner.javaClass == DefaultedMappedRegistry::class.java || owner.javaClass == Holder.Reference::class.java

    internal fun owner(map: IdMap<*>): Any? {
        val candidate = if (map.javaClass == holderMapType) holderMapOwner.get(map) else map
        return candidate?.takeIf { canonical(it) && it !is Holder.Reference<*> }
    }

    internal fun registry(entries: Palette<*>): IdMap<*>? =
        if (entries.javaClass == GlobalPalette::class.java) globalRegistry.get(entries) as IdMap<*> else null

    internal fun strategyRegistry(strategy: Strategy<*>): IdMap<*>? =
        if (strategy.javaClass in strategyTypes) strategyRegistry.get(strategy) as IdMap<*> else null

    private fun input(owner: Any): Input {
        reap()
        return inputs.getOrPut(Key(owner, released), ::Input)
    }

    private fun reap() {
        while (true) {
            val key = released.poll() ?: return
            inputs.remove(key)
            storageUses.remove(key)
        }
    }

    internal fun begin(owner: Any, recover: Boolean): Mutation {
        val input = input(owner)
        val canRecover = recover && input.active.isEmpty()
        input.version++
        return Mutation(owner, input, input.version, input.failureRevision, canRecover).also { input.active.add(it) }
    }

    internal fun finish(mutation: Mutation, completed: Boolean) {
        val input = mutation.input
        check(mutation in input.active)
        if (!completed) {
            input.failureRevision++
            input.failed = true
        } else if (mutation.recover && input.active.size == 1 && input.version == mutation.version &&
            input.failureRevision == mutation.failureRevision) {
            input.failed = false
        }
        input.active.remove(mutation)
        input.version++
    }

    internal fun beginCopy(map: IdMap<*>?): Copy {
        val owner = map?.let(::owner)
        val input = owner?.let(::input)
        return Copy(owner, input, input?.version ?: 0L, input != null && !input.failed && input.active.isEmpty())
    }

    internal fun matches(copy: Copy): Boolean = copy.available && copy.owner != null &&
        input(copy.owner) === copy.input && copy.input.let { !it.failed && it.active.isEmpty() && it.version == copy.version }

    private fun current(owner: Any): Write = checkNotNull(writes.get()).also { check(it.owner === owner) }

    internal fun publication(owner: Any): D3D12SectionGeometry.IdMapPublication {
        val write = current(owner)
        return write.publication ?: D3D12SectionGeometry.beginIdMapPublication(owner, write.recover).also { write.publication = it }
    }

    @Suppress("UNCHECKED_CAST")
    private fun values(owner: Any): List<Any?> =
        (if (owner is IdMapper<*>) mapperValues else registryValues).get(owner) as List<Any?>

    internal fun slot(owner: Any, id: Int): Any? = values(owner).let { if (id in it.indices) it[id] else null }

    @Suppress("UNCHECKED_CAST")
    private fun ids(owner: Any): Reference2IntMap<Any?> =
        (if (owner is IdMapper<*>) mapperIds else registryIds).get(owner) as Reference2IntMap<Any?>

    internal fun sameReverse(owner: Any, value: Any?, id: Int): Boolean = ids(owner).let {
        it.containsKey(value) && it.getInt(value) == id }

    internal fun size(owner: Any): Int = values(owner).size

    internal fun empty(owner: Any): Boolean = values(owner).isEmpty() && ids(owner).isEmpty()

    internal fun failed(owner: Any): Boolean = input(owner).failed

    internal fun sameDefault(owner: Any, value: Any?): Boolean = defaultValue.get(owner) === value

    internal fun sameHolder(holder: Holder.Reference<*>, value: Any?): Boolean = holderValue.get(holder) === value

    @JvmStatic
    fun writeSlot(owner: Any, id: Int, value: Any?, operation: Supplier<Any?>): Any? =
        if (canonical(owner)) D3D12SectionGeometry.publishIdMapSlot(owner, id, value, operation) else operation.get()

    @JvmStatic
    fun writeReverse(owner: Any, value: Any?, id: Int, operation: IntSupplier): Int =
        if (canonical(owner)) D3D12SectionGeometry.publishIdMapReverse(owner, value, id, operation) else operation.asInt

    @JvmStatic
    fun append(owner: Any, value: Any?, operation: Supplier<Boolean>): Boolean =
        if (canonical(owner)) D3D12SectionGeometry.publishIdMapAppend(owner, value, operation) else operation.get()

    @JvmStatic
    fun clear(owner: Any, operation: Runnable) {
        if (canonical(owner)) D3D12SectionGeometry.publishIdMapClear(owner, operation) else operation.run()
    }

    @JvmStatic
    fun writeDefault(owner: Any, value: Any?, operation: Runnable) {
        if (canonical(owner)) D3D12SectionGeometry.publishIdMapDefault(owner, value, operation) else operation.run()
    }

    @JvmStatic
    fun writeHolder(holder: Holder.Reference<*>, value: Any?, operation: Runnable) {
        if (canonical(holder)) D3D12SectionGeometry.publishPaletteHolder(holder, value, operation) else operation.run()
    }

    @JvmStatic
    fun <Value : Any> iterator(owner: MappedRegistry<Value>, original: MutableIterator<Value>): MutableIterator<Value> {
        if (!canonical(owner)) return original
        return object : MutableIterator<Value> {
            private var removable = false
            override fun hasNext(): Boolean = original.hasNext()
            override fun next(): Value = original.next().also { removable = true }
            override fun remove() {
                if (!removable) {
                    original.remove()
                    return
                }
                writing(owner, false).use { write ->
                    D3D12SectionGeometry.beginIdMapRemoval(owner)
                    original.remove()
                    removable = false
                    write.complete()
                }
            }
        }
    }

    private fun storageIds(storage: BitStorage): IntArray {
        reap()
        val copy = D3D12RtPaletteStorage.beginCopy(storage)
        storageUses[Key(storage)]?.takeIf { it.input === copy.input && it.version == copy.version }?.let { return it.ids }
        val ids = IntOpenHashSet()
        storage.getAll { value -> ids.add(value) }
        return ids.toIntArray().also { storageUses[Key(storage, released)] = StorageUse(copy.input, copy.version, it) }
    }

    private fun rawValue(entries: Palette<*>, id: Int): Any? {
        val map = registry(entries) ?: return entries.valueFor(id)
        val owner = map.let(::owner) ?: return null
        val value = slot(owner, id)
        return value ?: if (map === owner && owner.javaClass == DefaultedMappedRegistry::class.java) defaultValue.get(owner) else null
    }

    private fun availableHolder(value: Any?): Boolean = when {
        value == null -> false
        value.javaClass == Holder.Direct::class.java ->
            D3D12RtBiomeInputs.owner((value as Holder.Direct<*>).value())?.let(D3D12RtBiomeInputs::available) != false
        value.javaClass == Holder.Reference::class.java -> holderValue.get(value) != null && input(value).let {
            !it.failed && it.active.isEmpty() } &&
            D3D12RtBiomeInputs.owner(holderValue.get(value))?.let(D3D12RtBiomeInputs::available) != false
        else -> false
    }

    internal fun dependencies(entries: Palette<*>?, storage: BitStorage?, biome: Boolean): List<Any> {
        if (entries == null || !D3D12RtPaletteEntries.canonical(entries)) return emptyList()
        val map = registry(entries)
        val owner = map?.let(::owner)
        val dependencies = IdentityHashMap<Any, Boolean>()
        if (owner != null) dependencies[owner] = true
        if (storage != null && D3D12RtPaletteStorage.available(storage) && (biome || map != null && owner != null)) {
            try {
                for (id in storageIds(storage)) {
                    val value = rawValue(entries, id) ?: continue
                    if (value.javaClass == Holder.Reference::class.java) dependencies[value] = true
                    if (biome) {
                        val biomeValue = when {
                            value.javaClass == Holder.Direct::class.java -> (value as Holder.Direct<*>).value()
                            value.javaClass == Holder.Reference::class.java -> holderValue.get(value)
                            else -> null
                        }
                        D3D12RtBiomeInputs.owner(biomeValue)?.let { dependencies[it] = true }
                    }
                }
            } catch (_: RuntimeException) {
                return dependencies.keys.toList()
            }
        }
        return dependencies.keys.toList()
    }

    internal fun available(entries: Palette<*>, storage: BitStorage, biome: Boolean): Boolean {
        val map = registry(entries)
        val owner = map?.let(::owner)
        if (map != null && owner == null) return false
        val input = owner?.let(::input)
        if (input?.failed == true || input?.active?.any { it.full } == true) return false
        if (map == null && !biome) return true
        return try {
            storageIds(storage).all { id ->
                if (input?.active?.any { id in it.ids } == true) false
                else {
                    val value = rawValue(entries, id)
                    value != null && when {
                        biome -> availableHolder(value)
                        value.javaClass == Holder.Reference::class.java -> availableHolder(value)
                        else -> true
                    }
                }
            }
        } catch (_: RuntimeException) {
            false
        }
    }

    internal fun affected(mutation: Mutation, entries: Palette<*>, storage: BitStorage?): Boolean {
        if (storage == null || !D3D12RtPaletteStorage.available(storage) || !D3D12RtPaletteEntries.canonical(entries)) return false
        return try {
            if (mutation.owner is Holder.Reference<*>) {
                storageIds(storage).any { rawValue(entries, it) === mutation.owner }
            } else {
                registry(entries)?.let(::owner) === mutation.owner &&
                    (mutation.full || mutation.input.failed || storageIds(storage).any { it in mutation.ids })
            }
        } catch (_: RuntimeException) {
            registry(entries)?.let(::owner) === mutation.owner || mutation.owner is Holder.Reference<*>
        }
    }
}
