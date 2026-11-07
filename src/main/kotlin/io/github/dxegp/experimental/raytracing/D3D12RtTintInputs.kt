package io.github.dxegp.experimental.raytracing

import io.github.dxegp.d3d12.render.advanced.mesh.D3D12SectionGeometry
import net.minecraft.client.color.block.BlockTintCache
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.renderer.BiomeColors
import net.minecraft.core.BlockPos
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.ColorResolver
import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference
import java.util.BitSet
import java.util.function.IntSupplier
import java.util.function.ToIntFunction

object D3D12RtTintInputs {
    private const val MAX_LAYERS = 16384
    private const val MAX_CACHES = 512
    private const val MAX_COLUMNS = 4096
    private class Key(value: Any, queue: ReferenceQueue<Any>? = null) : WeakReference<Any>(value, queue) {
        private val identityHash = System.identityHashCode(value)
        override fun hashCode(): Int = identityHash
        override fun equals(other: Any?): Boolean = this === other ||
            other is Key && get()?.let { it === other.get() } == true
    }

    internal class Column(val tracked: Boolean = true, var version: Long = 0L, var active: Int = 0)
    private class ColumnKey(value: Column, val column: Long, queue: ReferenceQueue<Column>) : WeakReference<Column>(value, queue)
    internal class Input(val tracked: Boolean = true, var version: Long = 0L, var active: Int = 0) {
        var colorTables = 0
        var sourceUsed = false
        var owner: Owner? = null
        private val released = ReferenceQueue<Column>()
        private val columns = hashMapOf<Long, ColumnKey>()

        fun column(column: Long): Column {
            while (true) {
                val key = released.poll() as? ColumnKey ?: break
                if (columns[key.column] === key) columns.remove(key.column)
            }
            columns[column]?.get()?.let { return it }
            if (columns.size >= MAX_COLUMNS) return Column(false)
            return Column().also { columns[column] = ColumnKey(it, column, released) }
        }
    }

    internal class Owner(world: ClientLevel, source: ToIntFunction<BlockPos>, val resolver: ColorResolver) {
        val world = WeakReference(world)
        val source = WeakReference(source)
    }

    private val cacheSource = BlockTintCache::class.java.getDeclaredField("source").apply { isAccessible = true }

    internal fun fixedResolver(resolver: ColorResolver?): Boolean = resolver === BiomeColors.GRASS_COLOR_RESOLVER ||
        resolver === BiomeColors.FOLIAGE_COLOR_RESOLVER || resolver === BiomeColors.DRY_FOLIAGE_COLOR_RESOLVER ||
        resolver === BiomeColors.WATER_COLOR_RESOLVER

    internal fun ownSource(world: ClientLevel, cache: BlockTintCache, source: ToIntFunction<BlockPos>?, resolver: ColorResolver?) {
        val input = input(cache)
        if (!input.tracked || input.sourceUsed || input.owner != null || world.javaClass != ClientLevel::class.java ||
            cache.javaClass != BlockTintCache::class.java || source == null || cacheSource.get(cache) !== source ||
            !fixedResolver(resolver)) return
        input.owner = Owner(world, source, checkNotNull(resolver))
    }

    internal fun sourceCurrent(cache: BlockTintCache, world: Any?, resolver: ColorResolver? = null): Boolean {
        val owner = inputs[Key(cache)]?.owner ?: return false
        val source = owner.source.get() ?: return false
        return world != null && owner.world.get() === world && cache.javaClass == BlockTintCache::class.java &&
            cacheSource.get(cache) === source && (resolver == null || owner.resolver === resolver)
    }

    private class Layer(val input: Input, val column: Column, val world: WeakReference<Any>?,
                        val epoch: Int, val version: Long, val columnVersion: Long) {
        val slots = BitSet(256)
        val unknownContents = BitSet(256)
    }

    class Read internal constructor(internal val cache: BlockTintCache, internal val columnKey: Long,
                                    internal val index: Int, internal val input: Input, internal val column: Column,
                                    internal val world: Any?, internal val epoch: Int,
                                    internal val scope: D3D12SectionGeometry.CompileScope?) : AutoCloseable {
        internal val version = input.version
        internal val columnVersion = column.version
        internal var available = cache.javaClass == BlockTintCache::class.java &&
            input.tracked && column.tracked && input.active == 0 && column.active == 0
        internal var contentAvailable = sourceCurrent(cache, world) && D3D12RtColorTables.available(input.colorTables)
        internal var layer: IntArray? = null
        internal var consumed = false
        internal var completed = false
        internal var closed = false
        internal val previous = reads.get()

        init { reads.set(this) }

        fun complete() { completed = true }

        override fun close() {
            if (closed) return
            check(reads.get() === this)
            try { D3D12SectionGeometry.finishTintCacheRead(this) }
            finally {
                closed = true
                if (previous == null) reads.remove() else reads.set(previous)
            }
        }
    }

    internal class Proof(read: Read) {
        val cache = WeakReference(read.cache)
        val input = read.input
        val column = read.column
        val columnKey = read.columnKey
        val index = read.index
        val version = read.version
        val columnVersion = read.columnVersion
        val world = WeakReference(read.world)
        val epoch = read.epoch
    }

    internal fun proof(read: Read, world: Any?, epoch: Int): Proof? =
        if (read.completed && read.consumed && read.contentAvailable && current(read, world, epoch) &&
            sourceCurrent(read.cache, world)) Proof(read) else null

    internal fun current(proof: Proof, world: Any?, epoch: Int): Boolean = proof.world.get() === world &&
        proof.epoch == epoch && proof.input.active == 0 && proof.column.active == 0 &&
        proof.input.version == proof.version && proof.column.version == proof.columnVersion &&
        proof.cache.get()?.let { sourceCurrent(it, world) } == true && D3D12RtColorTables.available(proof.input.colorTables)

    internal fun resolver(proof: Proof): ColorResolver? = proof.input.owner?.resolver

    internal class Invalidation(val input: Input, val columns: List<Column>?)
    private val released = ReferenceQueue<Any>()
    private val inputs = hashMapOf<Key, Input>()
    private val layers = hashMapOf<Key, Layer>()
    private val reads = ThreadLocal<Read?>()

    private fun reap() {
        while (true) {
            val key = released.poll() ?: return
            inputs.remove(key)
            layers.remove(key)
        }
    }

    private fun input(cache: BlockTintCache): Input {
        reap()
        inputs[Key(cache)]?.let { return it }
        if (inputs.size >= MAX_CACHES) return Input(false)
        return Input().also { inputs[Key(cache, released)] = it }
    }

    @JvmStatic
    fun reading(cache: BlockTintCache, position: BlockPos): Read = D3D12SectionGeometry.beginTintCacheRead(cache,
        ChunkPos.pack(position), (position.z and 15) shl 4 or (position.x and 15))

    internal fun beginRead(cache: BlockTintCache, column: Long, index: Int, world: Any?, epoch: Int): Read =
        input(cache).let { input ->
            input.sourceUsed = true
            Read(cache, column, index, input, input.column(column), world, epoch,
            D3D12RtModelSupport.currentScope()) }.also { read ->
            var outer = read.previous
            while (outer != null) {
                outer.input.colorTables = outer.input.colorTables or read.input.colorTables
                outer.contentAvailable = outer.contentAvailable && read.contentAvailable
                outer = outer.previous
            }
        }

    internal fun readColorTable(index: Int, available: Boolean): List<Read> = buildList {
        var read = reads.get()
        while (read != null) {
            read.input.colorTables = read.input.colorTables or (1 shl index)
            read.contentAvailable = read.contentAvailable && available
            add(read)
            read = read.previous
        }
    }

    internal fun hasRead(): Boolean = reads.get() != null

    internal fun markContentUnknown(): List<Read> = buildList {
        var read = reads.get()
        while (read != null) {
            read.contentAvailable = false
            add(read)
            read = read.previous
        }
    }

    internal fun colorTableCaches(index: Int): List<BlockTintCache> {
        reap()
        return inputs.entries.mapNotNull { [key, input] ->
            (key.get() as? BlockTintCache)?.takeIf { input.colorTables and (1 shl index) != 0 }
        }
    }

    internal fun beginInvalidation(cache: BlockTintCache, column: Long?): Invalidation {
        val input = input(cache)
        val columns = column?.let {
            buildList {
                for (offsetZ in -1..1) for (offsetX in -1..1) add(input.column(ChunkPos.pack(
                    ChunkPos.getX(column) + offsetX, ChunkPos.getZ(column) + offsetZ)))
            }
        }
        if (columns == null) {
            input.version++
            input.active++
        } else for (current in columns) {
            current.version++
            current.active++
        }
        return Invalidation(input, columns)
    }

    internal fun finishInvalidation(invalidation: Invalidation) {
        val columns = invalidation.columns
        if (columns == null) {
            invalidation.input.active--
            invalidation.input.version++
        } else for (column in columns) {
            column.active--
            column.version++
        }
    }

    internal fun current(read: Read, world: Any?, epoch: Int): Boolean = read.available && read.world === world &&
        read.epoch == epoch && read.input.active == 0 && read.column.active == 0 &&
        read.input.version == read.version && read.column.version == read.columnVersion

    private fun matches(layer: Layer, read: Read): Boolean = layer.input === read.input && layer.column === read.column &&
        layer.world?.get() === read.world && layer.epoch == read.epoch && layer.version == read.version && layer.columnVersion == read.columnVersion

    internal fun discardUnknown(array: IntArray, index: Int, world: Any?, epoch: Int) {
        val layer = layers[Key(array)]
        if (layer != null && layer.world?.get() === world && layer.epoch == epoch && layer.input.active == 0 && layer.column.active == 0 &&
            layer.version == layer.input.version && layer.columnVersion == layer.column.version && layer.slots[index]) return
        layer?.slots?.clear(index)
        array[index] = -1
    }

    internal fun prepareRead(array: IntArray, index: Int, world: Any?, epoch: Int) {
        discardUnknown(array, index, world, epoch)
    }

    @JvmStatic
    fun readSlot(array: IntArray, index: Int, operation: IntSupplier): Int =
        D3D12SectionGeometry.readTintCacheSlot(array, index, operation)

    internal fun readSlot(array: IntArray, index: Int, value: Int, world: Any?, epoch: Int): Read? {
        val read = reads.get() ?: return null
        read.layer = array
        val layer = layers[Key(array)]
        val valid = read.index == index && current(read, world, epoch) &&
            (value == -1 || layer?.let { matches(it, read) && it.slots[index] } == true)
        read.available = read.available && valid
        if (value != -1 && layer?.unknownContents?.get(index) == true) read.contentAvailable = false
        read.consumed = value != -1
        if (!valid) discardUnknown(array, index, world, epoch)
        return read
    }

    @JvmStatic
    fun writeSlot(array: IntArray, index: Int, value: Int, operation: Runnable) {
        D3D12SectionGeometry.writeTintCacheSlot(array, index, value, operation)
    }

    internal fun beforeWrite(array: IntArray, index: Int): Read? {
        layers[Key(array)]?.let { layer ->
            layer.slots.clear(index)
            layer.unknownContents.clear(index)
        }
        return reads.get()
    }

    internal fun afterWrite(read: Read?, array: IntArray, index: Int, world: Any?, epoch: Int) {
        if (read == null) return
        reap()
        val valid = read.layer === array && read.index == index && current(read, world, epoch)
        read.available = read.available && valid
        read.consumed = true
        if (!valid) {
            discardUnknown(array, index, world, epoch)
            return
        }
        val key = Key(array)
        var layer = layers[key]
        if (layer == null || !matches(layer, read)) {
            if (layer == null && layers.size >= MAX_LAYERS) {
                read.available = false
                return
            }
            layer = Layer(read.input, read.column, world?.let(::WeakReference), epoch, read.version, read.columnVersion)
            layers[Key(array, released)] = layer
        }
        layer.slots.set(index)
        layer.unknownContents.set(index, !read.contentAvailable)
    }

    internal fun failedWrite(read: Read?) { read?.available = false }
}
