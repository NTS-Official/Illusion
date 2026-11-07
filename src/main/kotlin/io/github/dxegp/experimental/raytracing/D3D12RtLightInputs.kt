package io.github.dxegp.experimental.raytracing

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import it.unimi.dsi.fastutil.longs.Long2ObjectMap
import it.unimi.dsi.fastutil.longs.Long2ObjectMaps
import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import it.unimi.dsi.fastutil.longs.LongSet
import net.minecraft.client.multiplayer.ClientChunkCache
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.SectionPos
import net.minecraft.world.level.LightLayer
import net.minecraft.world.level.chunk.DataLayer
import net.minecraft.world.level.lighting.BlockLightEngine
import net.minecraft.world.level.lighting.BlockLightSectionStorage
import net.minecraft.world.level.lighting.DataLayerStorageMap
import net.minecraft.world.level.lighting.LayerLightSectionStorage
import net.minecraft.world.level.lighting.LevelLightEngine
import net.minecraft.world.level.lighting.LightEngine
import net.minecraft.world.level.lighting.SkyLightEngine
import net.minecraft.world.level.lighting.SkyLightSectionStorage
import java.util.IdentityHashMap
import java.util.WeakHashMap

object D3D12RtLightInputs {
    private fun field(type: Class<*>, name: String) = runCatching {
        type.getDeclaredField(name).apply { isAccessible = true }
    }.getOrNull()

    private val levelSource = field(ClientLevel::class.java, "chunkSource")
    private val sourceEngine = field(ClientChunkCache::class.java, "lightEngine")
    private val engineStorage = field(LightEngine::class.java, "storage")
    private val visibleMap = field(LayerLightSectionStorage::class.java, "visibleSectionData")
    private val updatingMap = field(LayerLightSectionStorage::class.java, "updatingSectionData")
    private val queuedSections = field(LayerLightSectionStorage::class.java, "queuedSections")
    private val changedSections = field(LayerLightSectionStorage::class.java, "changedSections")
    private val mapLayers = field(DataLayerStorageMap::class.java, "map")
    private val skyMapType = SkyLightSectionStorage::class.java.declaredClasses.singleOrNull { it.simpleName == "SkyDataLayerStorageMap" }
    private val blockMapType = BlockLightSectionStorage::class.java.declaredClasses.singleOrNull { it.simpleName == "BlockDataLayerStorageMap" }
    private val skyLowest = skyMapType?.let { field(it, "currentLowestY") }
    private val skyTops = skyMapType?.let { field(it, "topSections") }
    private var activeDescriptor: Descriptor? = null
    private val visibleMaps = IdentityHashMap<LayerLightSectionStorage<*>, DataLayerStorageMap<*>>()
    private val visibleNodes = IdentityHashMap<LayerLightSectionStorage<*>, Long2ObjectOpenHashMap<DataLayer>>()
    private val visibleLayers = IdentityHashMap<DataLayer, Int>()
    private val layerInputs = WeakHashMap<DataLayer, Input>()
    private val mapInputs = WeakHashMap<DataLayerStorageMap<*>, MapInput>()
    private val queueInputs = WeakHashMap<LayerLightSectionStorage<*>, QueueInput>()
    private val queueType = Long2ObjectMaps.synchronize(Long2ObjectOpenHashMap<DataLayer>()).javaClass

    internal class Input(var active: Int = 0, var version: Long = 0L,
                         var failureRevision: Long = 0L, var failed: Boolean = false) {
        val available: Boolean get() = active == 0 && !failed
    }

    internal class Descriptor(val source: ClientChunkCache, val engine: LevelLightEngine,
                              val block: LayerLightSectionStorage<*>?, val sky: LayerLightSectionStorage<*>?)

    internal class MapInput {
        val nodes = Long2ObjectOpenHashMap<Input>()
        val changed = LongOpenHashSet()
        val failedNodes = LongOpenHashSet()
        val metadata = Input()
        var owned = false
        var active = 0
        var version = 0L
        var failedCopy = false
        val readable: Boolean get() = owned && active == 0 && !failedCopy && metadata.available
    }

    internal class MapMutation(val map: DataLayerStorageMap<*>, val node: Long, val mapping: MapInput,
                               val input: Input, val failureRevision: Long, val metadata: Boolean = false)

    internal class QueueInput(val table: Long2ObjectMap<DataLayer>) {
        val nodes = Long2ObjectOpenHashMap<Input>()
    }

    internal class QueueMutation(val storage: LayerLightSectionStorage<*>, val node: Long, val input: Input,
                                 val failureRevision: Long)

    internal class MapCopy(val map: DataLayerStorageMap<*>, val mapping: MapInput, val version: Long, val available: Boolean,
                           val failedNodes: LongArray, val layers: Any, val tops: Any?)

    internal class NodeRead(val node: Long, val input: Input, val version: Long) {
        val available: Boolean get() = input.available && input.version == version
    }

    internal class Read(val layer: DataLayer?, val flat: Boolean = false, private val mapping: MapInput? = null,
                        private val readable: Boolean = true, private val nodes: List<NodeRead> = emptyList(),
                        private val metadata: Input? = null) {
        private val input = layer?.takeIf { it.javaClass == DataLayer::class.java }?.let { layerInputs.getOrPut(it, ::Input) }
        private val version = input?.version
        private val metadataVersion = metadata?.version
        val available: Boolean get() = readable && mapping?.owned != false && mapping?.failedCopy != true && nodes.all { it.available } &&
            (metadata == null || metadata.available && metadata.version == metadataVersion) &&
            (layer == null || input?.let {
                it.available && it.version == version && D3D12RtLightArrays.available(layer)
            } == true)
        fun matches(other: Read): Boolean = layer === other.layer && flat == other.flat && available && other.available
        fun dependsOn(node: Long): Boolean = nodes.any { it.node == node }
    }

    internal class RawRead(val storage: LayerLightSectionStorage<*>, val slot: NodeRead, private val queue: QueueInput,
                           val read: Read, val queued: Boolean) {
        val available: Boolean get() = queueInputs[storage] === queue && queuedSections?.get(storage) === queue.table &&
            slot.available && read.available
        fun rebind(map: DataLayerStorageMap<*>?): RawRead = if (queued) this else
            RawRead(storage, slot, queue, read(map, slot.node, false), false)
    }

    internal class Snapshot(private val descriptor: Descriptor, private val nodes: List<Long>,
                            private var block: List<Read>, private var sky: List<Read>,
                            private var verifiedBlock: DataLayerStorageMap<*>?,
                            private var verifiedSky: DataLayerStorageMap<*>?) {
        private var actualBlock = linkedMapOf<Long, Read>()
        private var actualSky = linkedMapOf<Long, Read>()
        private var rawBlock = linkedMapOf<Long, RawRead>()
        private var rawSky = linkedMapOf<Long, RawRead>()
        val rawNodes: List<Pair<LayerLightSectionStorage<*>, Long>> get() =
            (rawBlock.values + rawSky.values).map { it.storage to it.slot.node }
        val layers: List<DataLayer> get() = buildList {
            val seen = IdentityHashMap<DataLayer, Boolean>()
            for (read in block + sky + actualBlock.values + actualSky.values + (rawBlock.values + rawSky.values).map { it.read }) {
                read.layer?.let { if (seen.put(it, true) == null) add(it) }
            }
        }

        fun matches(world: Any): Boolean = runCatching {
            if (levelSource?.get(world) !== descriptor.source || sourceEngine?.get(descriptor.source) !== descriptor.engine ||
                storage(descriptor.engine, LightLayer.BLOCK) !== descriptor.block || storage(descriptor.engine, LightLayer.SKY) !== descriptor.sky) {
                return false
            }
            val blockMap = map(descriptor.block)
            val skyMap = map(descriptor.sky)
            if (block.any { !it.available } || sky.any { !it.available } ||
                actualBlock.values.any { !it.available } || actualSky.values.any { !it.available } ||
                rawBlock.values.any { !it.available } || rawSky.values.any { !it.available }) return false
            if (blockMap === verifiedBlock && skyMap === verifiedSky) return true
            val nextBlock = nodes.map { read(blockMap, it, false) }
            val nextSky = nodes.map { read(skyMap, it, true) }
            val nextActualBlock = actualBlock.mapValuesTo(linkedMapOf()) { [node, _] -> read(blockMap, node, false) }
            val nextActualSky = actualSky.mapValuesTo(linkedMapOf()) { [node, _] -> read(skyMap, node, true) }
            val nextRawBlock = rawBlock.mapValuesTo(linkedMapOf()) { [_, value] -> value.rebind(blockMap) }
            val nextRawSky = rawSky.mapValuesTo(linkedMapOf()) { [_, value] -> value.rebind(skyMap) }
            if (!block.indices.all { block[it].matches(nextBlock[it]) } || !sky.indices.all { sky[it].matches(nextSky[it]) } ||
                !actualBlock.all { [node, value] -> value.matches(checkNotNull(nextActualBlock[node])) } ||
                !actualSky.all { [node, value] -> value.matches(checkNotNull(nextActualSky[node])) } ||
                !rawBlock.all { [node, value] -> value.read.matches(checkNotNull(nextRawBlock[node]).read) } ||
                !rawSky.all { [node, value] -> value.read.matches(checkNotNull(nextRawSky[node]).read) }) return false
            block = nextBlock
            sky = nextSky
            actualBlock = nextActualBlock
            actualSky = nextActualSky
            rawBlock = nextRawBlock
            rawSky = nextRawSky
            verifiedBlock = blockMap
            verifiedSky = skyMap
            true
        }.getOrDefault(false)

        fun affected(publication: Publication): Boolean = when (publication.storage) {
            descriptor.block -> !matchesReads(block, publication.next, false) || !matchesActual(actualBlock, publication.next, false) ||
                !matchesRaw(rawBlock, publication.next)
            descriptor.sky -> !matchesReads(sky, publication.next, true) || !matchesActual(actualSky, publication.next, true) ||
                !matchesRaw(rawSky, publication.next)
            else -> true
        }

        fun affected(mutation: MapMutation): Boolean = when (mutation.map) {
            map(descriptor.block) -> !mutation.metadata && (mutation.map !== verifiedBlock ||
                block.any { it.dependsOn(mutation.node) } || actualBlock.values.any { it.dependsOn(mutation.node) } ||
                rawBlock.values.any { !it.queued && it.read.dependsOn(mutation.node) })
            map(descriptor.sky) -> mutation.metadata || mutation.map !== verifiedSky ||
                sky.any { it.dependsOn(mutation.node) } || actualSky.values.any { it.dependsOn(mutation.node) } ||
                rawSky.values.any { !it.queued && it.read.dependsOn(mutation.node) }
            else -> false
        }

        fun record(storage: LayerLightSectionStorage<*>, node: Long, updating: Boolean): Read? {
            if (updating) return null
            val isSky = when (storage) {
                descriptor.block -> false
                descriptor.sky -> true
                else -> return null
            }
            val index = nodes.indexOf(node)
            if (index >= 0) return if (isSky) sky[index] else block[index]
            val reads = if (isSky) actualSky else actualBlock
            return reads.getOrPut(node) { read(map(storage), node, isSky) }
        }

        fun recordLayer(storage: LayerLightSectionStorage<*>, node: Long): RawRead? {
            val reads = when (storage) {
                descriptor.block -> rawBlock
                descriptor.sky -> rawSky
                else -> return null
            }
            reads[node]?.let { return it }
            val queue = queueInputs[storage] ?: return null
            if (queuedSections?.get(storage) !== queue.table) return null
            val input = queue.nodes.getOrPut(node, ::Input)
            if (!input.available) return null
            val queued = queue.table.get(node)
            val value = if (queued == null) read(map(storage), node, false) else Read(queued)
            return RawRead(storage, NodeRead(node, input, input.version), queue, value, queued != null).also { reads[node] = it }
        }

        private fun matchesReads(expected: List<Read>, current: DataLayerStorageMap<*>?, sky: Boolean): Boolean =
            nodes.indices.all { expected[it].matches(read(current, nodes[it], sky)) }

        private fun matchesActual(expected: Map<Long, Read>, current: DataLayerStorageMap<*>?, sky: Boolean): Boolean =
            expected.all { [node, value] -> value.matches(read(current, node, sky)) }

        private fun matchesRaw(expected: Map<Long, RawRead>, current: DataLayerStorageMap<*>?): Boolean =
            expected.values.all { it.available && (it.queued || it.read.matches(read(current, it.slot.node, false))) }
    }

    internal class Publication(val storage: LayerLightSectionStorage<*>, val previous: DataLayerStorageMap<*>,
                               val next: DataLayerStorageMap<*>, val nodes: LongArray,
                               val columns: Set<Long>, val allColumns: Boolean)

    internal fun observeLevel(world: Any?) {
        activeDescriptor = null
        visibleMaps.clear()
        visibleNodes.clear()
        visibleLayers.clear()
        synchronize(world)
    }

    private fun synchronize(world: Any?): Descriptor? {
        val descriptor = describe(world) ?: return null
        val previous = activeDescriptor
        if (previous?.source !== descriptor.source || previous.engine !== descriptor.engine ||
            previous.block !== descriptor.block || previous.sky !== descriptor.sky) {
            visibleMaps.clear()
            visibleNodes.clear()
            visibleLayers.clear()
            activeDescriptor = descriptor
        }
        for (storage in listOfNotNull(descriptor.block, descriptor.sky)) {
            val current = checkNotNull(map(storage))
            if (visibleMaps[storage] !== current && (mapInputs[current]?.active ?: 0) == 0) replaceVisibleMap(storage, current)
        }
        return descriptor
    }

    private fun layers(map: DataLayerStorageMap<*>): Long2ObjectOpenHashMap<*> =
        checkNotNull(mapLayers?.get(map)) as Long2ObjectOpenHashMap<*>

    private fun addVisibleLayer(layer: DataLayer) {
        visibleLayers[layer] = (visibleLayers[layer] ?: 0) + 1
        if (layer.javaClass == DataLayer::class.java && layerInputs[layer] == null) layerInputs[layer] = Input()
    }

    private fun removeVisibleLayer(layer: DataLayer) {
        val remaining = checkNotNull(visibleLayers[layer]) - 1
        if (remaining == 0) visibleLayers.remove(layer) else visibleLayers[layer] = remaining
    }

    private fun replaceVisibleMap(storage: LayerLightSectionStorage<*>, next: DataLayerStorageMap<*>) {
        visibleNodes[storage]?.let { for (layer in it.values) removeVisibleLayer(layer) }
        val registered = Long2ObjectOpenHashMap<DataLayer>()
        if (mapInputs[next]?.owned == true) {
            for ([node, layer] in layers(next)) {
                registered[node] = layer as DataLayer
                addVisibleLayer(layer)
            }
        }
        visibleNodes[storage] = registered
        visibleMaps[storage] = next
    }

    internal fun ownMap(map: DataLayerStorageMap<*>, table: Long2ObjectOpenHashMap<DataLayer>, tops: Long2IntOpenHashMap?) {
        if (map.javaClass != if (tops == null) blockMapType else skyMapType) return
        if (table.javaClass != Long2ObjectOpenHashMap::class.java || table !== layers(map)) return
        if (tops != null && (tops.javaClass != Long2IntOpenHashMap::class.java || skyTops?.get(map) !== tops)) return
        mapInputs.getOrPut(map, ::MapInput).owned = true
    }

    internal fun ownQueue(storage: LayerLightSectionStorage<*>, queue: Long2ObjectMap<DataLayer>) {
        if (storage.javaClass !in listOf(BlockLightSectionStorage::class.java, SkyLightSectionStorage::class.java) ||
            queue.javaClass != queueType || queuedSections?.get(storage) !== queue) return
        if (queueInputs[storage] == null) queueInputs[storage] = QueueInput(queue)
    }

    internal fun beginQueueMutation(storage: LayerLightSectionStorage<*>, node: Long): QueueMutation? {
        val queue = queueInputs[storage] ?: return null
        if (queuedSections?.get(storage) !== queue.table) return null
        val input = queue.nodes.getOrPut(node, ::Input)
        input.active++
        input.version++
        return QueueMutation(storage, node, input, input.failureRevision)
    }

    internal fun finishQueueMutation(mutation: QueueMutation, completed: Boolean) {
        val input = mutation.input
        if (!completed) {
            input.failed = true
            input.failureRevision++
        } else if (input.failureRevision == mutation.failureRevision) {
            input.failed = false
        }
        input.active--
    }

    internal fun beginSkyMetadata(world: Any?, storage: SkyLightSectionStorage, node: Long): MapMutation =
        beginMapMutation(world, checkNotNull(updatingMap?.get(storage) as? DataLayerStorageMap<*>), node, true)

    internal fun beginMapMutation(world: Any?, map: DataLayerStorageMap<*>, node: Long, metadata: Boolean = false): MapMutation {
        synchronize(world)
        val mapping = mapInputs.getOrPut(map, ::MapInput)
        val input = if (metadata) mapping.metadata else mapping.nodes.getOrPut(node, ::Input)
        mapping.active++
        mapping.version++
        if (!metadata) mapping.changed.add(node)
        input.active++
        input.version++
        return MapMutation(map, node, mapping, input, input.failureRevision, metadata)
    }

    internal fun finishMapMutation(world: Any?, mutation: MapMutation, completed: Boolean, recoverInput: Boolean) {
        val input = mutation.input
        if (!completed) {
            input.failed = true
            input.failureRevision++
        } else if (recoverInput && input.failureRevision == mutation.failureRevision) {
            input.failed = false
        }
        if (!mutation.metadata) {
            if (input.failed) mutation.mapping.failedNodes.add(mutation.node) else mutation.mapping.failedNodes.remove(mutation.node)
        }
        input.active--
        val mapping = mutation.mapping
        mapping.active--
        if (mapping.active != 0) return
        for ([storage, visible] in visibleMaps) {
            if (!mapping.owned || visible !== mutation.map) continue
            val registered = checkNotNull(visibleNodes[storage])
            for (node in mapping.changed) {
                val previous = registered[node]
                val next = layers(visible).get(node) as? DataLayer
                if (previous === next) continue
                previous?.let(::removeVisibleLayer)
                if (next == null) registered.remove(node) else {
                    registered[node] = next
                    addVisibleLayer(next)
                }
            }
        }
        mapping.changed.clear()
        synchronize(world)
    }

    internal fun beginMapCopy(map: DataLayerStorageMap<*>): MapCopy {
        val mapping = mapInputs.getOrPut(map, ::MapInput)
        return MapCopy(map, mapping, mapping.version, mapping.readable, mapping.failedNodes.toLongArray(), layers(map),
            if (map.javaClass == skyMapType) skyTops?.get(map) else null)
    }

    internal fun finishMapCopy(copy: MapCopy, result: DataLayerStorageMap<*>) {
        val mapping = mapInputs.getOrPut(result, ::MapInput)
        val resultLayers = layers(result)
        val resultTops = if (result.javaClass == skyMapType) skyTops?.get(result) else null
        mapping.owned = result.javaClass == copy.map.javaClass && result.javaClass in listOf(blockMapType, skyMapType) &&
            resultLayers.javaClass == Long2ObjectOpenHashMap::class.java && resultLayers !== copy.layers &&
            (resultTops == null && copy.tops == null || resultTops?.javaClass == Long2IntOpenHashMap::class.java && resultTops !== copy.tops)
        if (!copy.available || !copy.mapping.readable || copy.mapping.version != copy.version) mapping.failedCopy = true
        for (node in copy.failedNodes) {
            mapping.nodes.getOrPut(node, ::Input).failed = true
            mapping.failedNodes.add(node)
        }
    }

    internal fun input(layer: DataLayer): Input? =
        layer.takeIf { it.javaClass == DataLayer::class.java }?.let { layerInputs.getOrPut(it, ::Input) }

    internal fun copyInput(layer: DataLayer): Input = layerInputs.getOrPut(layer, ::Input)

    internal fun invalidateArray(layer: DataLayer) {
        layerInputs[layer]?.let { it.version++ }
    }

    internal fun copiedInput(layer: DataLayer, available: Boolean) {
        val input = layerInputs.getOrPut(layer, ::Input)
        if (!available) {
            input.version++
            input.failureRevision++
            input.failed = true
        }
    }

    internal fun finishPublication(publication: Publication) {
        val current = checkNotNull(map(publication.storage))
        if (current === visibleMaps[publication.storage]) return
        if ((mapInputs[current]?.active ?: 0) != 0) return
        if (mapInputs[current]?.owned != true) {
            replaceVisibleMap(publication.storage, current)
            return
        }
        if (current !== publication.next || visibleMaps[publication.storage] !== publication.previous) {
            replaceVisibleMap(publication.storage, current)
            return
        }
        val registered = checkNotNull(visibleNodes[publication.storage])
        for (node in publication.nodes) {
            val previous = registered[node]
            val next = layers(current).get(node) as? DataLayer
            if (previous === next) continue
            previous?.let(::removeVisibleLayer)
            next?.let(::addVisibleLayer)
            if (next == null) registered.remove(node) else registered[node] = next
        }
        visibleNodes[publication.storage] = registered
        visibleMaps[publication.storage] = current
    }

    private fun storage(engine: LevelLightEngine, layer: LightLayer): LayerLightSectionStorage<*>? {
        val listener = engine.getLayerListener(layer)
        if (listener === net.minecraft.world.level.lighting.LayerLightEventListener.DummyLightLayerEventListener.INSTANCE) return null
        val expected = if (layer == LightLayer.BLOCK) BlockLightEngine::class.java else SkyLightEngine::class.java
        require(listener.javaClass == expected)
        return (engineStorage?.get(listener) as? LayerLightSectionStorage<*>)?.also {
            require(it.javaClass == if (layer == LightLayer.BLOCK) BlockLightSectionStorage::class.java else SkyLightSectionStorage::class.java)
        } ?: error("Missing light storage")
    }

    private fun describe(world: Any?): Descriptor? = runCatching {
        if (world?.javaClass != ClientLevel::class.java) return null
        val source = levelSource?.get(world) as? ClientChunkCache ?: return null
        if (source.javaClass != ClientChunkCache::class.java) return null
        val engine = sourceEngine?.get(source) as? LevelLightEngine ?: return null
        if (engine.javaClass != LevelLightEngine::class.java) return null
        Descriptor(source, engine, storage(engine, LightLayer.BLOCK), storage(engine, LightLayer.SKY))
    }.getOrNull()

    private fun map(storage: LayerLightSectionStorage<*>?): DataLayerStorageMap<*>? =
        storage?.let { checkNotNull(visibleMap?.get(it) as? DataLayerStorageMap<*>) }

    private fun read(map: DataLayerStorageMap<*>?, node: Long, sky: Boolean): Read {
        if (map == null) return Read(null)
        val mapping = mapInputs.getOrPut(map, ::MapInput)
        if (!mapping.readable) return Read(null, mapping = mapping, readable = false)
        val reads = mutableListOf<NodeRead>()
        fun lookup(key: Long): DataLayer? {
            val input = mapping.nodes.getOrPut(key, ::Input)
            reads.add(NodeRead(key, input, input.version))
            return map.getLayer(key)
        }
        if (!sky) return Read(lookup(node), mapping = mapping, nodes = reads)
        require(map.javaClass == skyMapType)
        val lowest = checkNotNull(skyLowest).getInt(map)
        val tops = checkNotNull(skyTops?.get(map) as? Long2IntOpenHashMap)
        val top = tops.get(SectionPos.getZeroNode(node))
        var sectionY = SectionPos.y(node)
        if (top == lowest || sectionY >= top) return Read(null, mapping = mapping, metadata = mapping.metadata)
        lookup(node)?.let { return Read(it, mapping = mapping, nodes = reads, metadata = mapping.metadata) }
        while (++sectionY < top) {
            lookup(SectionPos.asLong(SectionPos.x(node), sectionY, SectionPos.z(node)))?.let {
                return Read(it, true, mapping, nodes = reads, metadata = mapping.metadata)
            }
        }
        return Read(null, mapping = mapping, nodes = reads, metadata = mapping.metadata)
    }

    internal fun capture(world: Any, nodes: List<Long>): Snapshot? = runCatching {
        val descriptor = synchronize(world) ?: return null
        val block = map(descriptor.block)
        val sky = map(descriptor.sky)
        Snapshot(descriptor, nodes, nodes.map { read(block, it, false) }, nodes.map { read(sky, it, true) }, block, sky)
    }.getOrNull()

    internal fun publication(world: Any?, storage: LayerLightSectionStorage<*>, next: DataLayerStorageMap<*>): Publication? =
        runCatching {
            val descriptor = synchronize(world) ?: return null
            if (storage !== descriptor.block && storage !== descriptor.sky) return null
            val previous = checkNotNull(map(storage))
            if (next.javaClass != previous.javaClass) return null
            val changed = checkNotNull(changedSections?.get(storage) as? LongSet)
            val nodes = changed.toLongArray()
            val columns = nodes.mapTo(linkedSetOf(), SectionPos::getZeroNode)
            val allColumns = storage === descriptor.sky &&
                (checkNotNull(skyLowest).getInt(previous) != skyLowest.getInt(next) ||
                    (checkNotNull(skyTops?.get(previous) as? Long2IntOpenHashMap)).defaultReturnValue() !=
                    (checkNotNull(skyTops.get(next) as? Long2IntOpenHashMap)).defaultReturnValue())
            Publication(storage, previous, next, nodes, columns, allColumns)
        }.getOrNull()
}
