package io.github.dxegp.experimental.raytracing

import io.github.dxegp.d3d12.render.advanced.mesh.D3D12SectionGeometry
import net.neoforged.neoforge.common.world.ModifiableBiomeInfo
import net.minecraft.client.renderer.block.dispatch.BlockStateModel
import net.minecraft.client.renderer.block.dispatch.SingleVariant
import net.minecraft.client.renderer.block.dispatch.WeightedVariants
import net.minecraft.client.renderer.block.dispatch.multipart.MultiPartModel
import net.minecraft.client.renderer.block.BlockStateModelSet
import net.minecraft.client.renderer.block.BlockAndTintGetter
import net.minecraft.client.renderer.block.FluidRenderer
import net.minecraft.client.renderer.chunk.RenderSectionRegion
import net.minecraft.client.renderer.chunk.SectionCopy
import net.minecraft.client.resources.model.SimpleModelWrapper
import net.minecraft.client.resources.model.geometry.BakedQuad
import net.minecraft.client.resources.model.geometry.QuadCollection
import net.minecraft.core.Direction
import net.minecraft.core.BlockPos
import net.minecraft.client.color.block.BlockTintCache
import net.minecraft.world.level.lighting.LayerLightSectionStorage
import net.minecraft.world.level.ChunkPos
import net.minecraft.core.SectionPos
import net.minecraft.util.random.WeightedList
import net.minecraft.world.phys.Vec3
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.SpeleothemBlock
import net.minecraft.world.level.block.SmallDripleafBlock
import net.minecraft.world.level.chunk.PalettedContainer
import net.minecraft.world.level.chunk.LevelChunkSection
import net.minecraft.world.level.material.EmptyFluid
import net.minecraft.world.level.material.FluidState
import java.lang.reflect.Field
import java.util.IdentityHashMap
import kotlin.math.nextDown
import kotlin.math.nextUp

object D3D12RtModelSupport {
    private const val MAX_MODEL_NODES = 4096
    private const val MAX_MODEL_DEPTH = 64
    private const val MAX_QUADS = 65_536
    private val active = ThreadLocal<Session?>()

    private fun field(type: Class<*>, name: String): Field? = runCatching {
        type.getDeclaredField(name).apply { isAccessible = true }
    }.getOrNull()

    private val singlePart = field(SingleVariant::class.java, "model")
    private val weightedModels = field(WeightedVariants::class.java, "list")
    private val multipartShared = field(MultiPartModel::class.java, "shared")
    private val multipartSelectors = multipartShared?.type?.let { field(it, "selectors") }
    private val blockModels = field(BlockStateModelSet::class.java, "modelByState")
    private val regionSections = field(RenderSectionRegion::class.java, "sections")
    private val regionMinimumX = field(RenderSectionRegion::class.java, "minSectionX")
    private val regionMinimumY = field(RenderSectionRegion::class.java, "minSectionY")
    private val regionMinimumZ = field(RenderSectionRegion::class.java, "minSectionZ")
    private val copiedStates = field(SectionCopy::class.java, "section")
    private val copiedDebug = field(SectionCopy::class.java, "debug")
    private val stateOffset = field(BlockBehaviour.BlockStateBase::class.java, "offsetFunction")
    private val propertiesOffset = field(BlockBehaviour.Properties::class.java, "offsetFunction")
    private val horizontalOffset by lazy {
        propertiesOffset?.get(BlockBehaviour.Properties.of().offsetType(BlockBehaviour.OffsetType.XZ))
    }
    private val spatialOffset by lazy {
        propertiesOffset?.get(BlockBehaviour.Properties.of().offsetType(BlockBehaviour.OffsetType.XYZ))
    }

    private fun hasSupportedOffsetLimit(type: Class<*>, name: String): Boolean =
        generateSequence(type) { it.superclass }.firstNotNullOfOrNull { owner ->
            try {
                owner.getDeclaredMethod(name)
            } catch (missing: NoSuchMethodException) {
                null
            }
        }?.declaringClass?.let { owner ->
            owner == BlockBehaviour::class.java ||
                name == "getMaxHorizontalOffset" && owner == SpeleothemBlock::class.java ||
                name == "getMaxVerticalOffset" && owner == SmallDripleafBlock::class.java
        } == true

    private fun hasSupportedOffset(state: BlockState): Boolean {
        if (!state.hasOffsetFunction()) return true
        val offset = stateOffset?.get(state) ?: return false
        val vertical = when {
            offset === horizontalOffset -> false
            offset === spatialOffset -> true
            else -> return false
        }
        val type = state.block.javaClass
        return hasSupportedOffsetLimit(type, "getMaxHorizontalOffset") &&
            (!vertical || hasSupportedOffsetLimit(type, "getMaxVerticalOffset"))
    }

    private fun D3D12RtGeometryPool.OutputBounds.union(other: D3D12RtGeometryPool.OutputBounds) =
        D3D12RtGeometryPool.OutputBounds(minOf(minimumX, other.minimumX), minOf(minimumY, other.minimumY),
            minOf(minimumZ, other.minimumZ), maxOf(maximumX, other.maximumX), maxOf(maximumY, other.maximumY),
            maxOf(maximumZ, other.maximumZ))

    private fun D3D12RtGeometryPool.OutputBounds.translated(originX: Float, originY: Float, originZ: Float) =
        D3D12RtGeometryPool.OutputBounds(minimumX + originX, minimumY + originY, minimumZ + originZ,
            maximumX + originX, maximumY + originY, maximumZ + originZ)

    private fun D3D12RtGeometryPool.OutputBounds.isFinite(): Boolean =
        minimumX.isFinite() && minimumY.isFinite() && minimumZ.isFinite() &&
            maximumX.isFinite() && maximumY.isFinite() && maximumZ.isFinite()

    private val sectionBounds = D3D12RtGeometryPool.OutputBounds(0f, 0f, 0f, 16f, 16f, 16f)

    internal fun universeBounds(state: BlockState, models: BlockStateModelSet,
                                cache: Cache): D3D12RtGeometryPool.OutputBounds? {
        if (state.javaClass != BlockState::class.java || !hasSupportedOffset(state)) return null
        val offset = if (state.hasOffsetFunction()) 0.25f else 0f
        val vertical = if (state.hasOffsetFunction()) 0.2f else 0f
        return placementBounds(cache.candidates(models.get(state)), offset, vertical)
    }

    internal fun missingUniverseBounds(models: BlockStateModelSet, cache: Cache): D3D12RtGeometryPool.OutputBounds? =
        placementBounds(cache.candidates(models.missingModel()), 0.25f, 0.2f)

    private fun placementBounds(candidates: Candidates, offset: Float, vertical: Float): D3D12RtGeometryPool.OutputBounds? {
        if (!candidates.complete) return null
        var bounds = sectionBounds
        for ([_, candidate] in candidates.entries()) {
            val translated = D3D12RtGeometryPool.OutputBounds(
                (candidate.minimumX - offset).nextDown(),
                (candidate.minimumY - vertical).nextDown(),
                (candidate.minimumZ - offset).nextDown(),
                (candidate.maximumX + (15f + offset).nextUp()).nextUp(),
                (candidate.maximumY + 15f).nextUp(),
                (candidate.maximumZ + (15f + offset).nextUp()).nextUp())
            if (!translated.isFinite()) return null
            bounds = bounds.union(translated)
        }
        return bounds
    }

    class Candidates internal constructor(val complete: Boolean,
                                          private val layers: Map<Int, D3D12RtGeometryPool.OutputBounds>) {
        fun bounds(layer: Int): D3D12RtGeometryPool.OutputBounds? = if (complete) layers[layer] else null
        val layerMask: Int get() = layers.keys.fold(0) { mask, layer -> mask or (1 shl layer) }
        internal fun entries() = layers.entries
    }

    class Cache {
        private val models = IdentityHashMap<BlockStateModel, Candidates>()

        @Synchronized
        fun candidates(model: BlockStateModel): Candidates {
            models[model]?.let { return it }
            val visiting = IdentityHashMap<BlockStateModel, Boolean>()
            var nodes = 0
            var quads = 0
            fun collect(current: BlockStateModel, depth: Int): Candidates {
                if (depth > MAX_MODEL_DEPTH || ++nodes > MAX_MODEL_NODES) {
                    return Candidates(false, emptyMap())
                }
                models[current]?.let { return it }
                if (visiting.put(current, true) != null) return Candidates(false, emptyMap())
                val bounds = mutableMapOf<Int, D3D12RtGeometryPool.OutputBounds>()
                var complete = true
                fun include(layer: Int, incoming: D3D12RtGeometryPool.OutputBounds) {
                    bounds[layer] = bounds[layer]?.union(incoming) ?: incoming
                }
                try {
                    when (current.javaClass) {
                        SingleVariant::class.java -> {
                            val part = singlePart?.get(current)
                            if (part !is SimpleModelWrapper || part.quads().javaClass != QuadCollection::class.java) {
                                complete = false
                            } else {
                                val all = IdentityHashMap<BakedQuad, Boolean>()
                                val candidates = part.quads().all.asSequence() + Direction.entries.asSequence()
                                    .flatMap { part.getQuads(it).asSequence() } + part.getQuads(null).asSequence()
                                for (quad in candidates) {
                                    if (all.put(quad, true) != null) continue
                                    if (++quads > MAX_QUADS) {
                                        complete = false
                                        break
                                    }
                                    val layer = D3D12SectionGeometry.Layer.of(quad.materialInfo().layer().name)
                                    if (layer == D3D12SectionGeometry.Layer.OTHER) {
                                        complete = false
                                        break
                                    }
                                    for (vertex in 0 until BakedQuad.VERTEX_COUNT) {
                                        val position = quad.position(vertex)
                                        val point = D3D12RtGeometryPool.OutputBounds(position.x(), position.y(), position.z(),
                                            position.x(), position.y(), position.z())
                                        if (!point.isFinite()) complete = false else include(layer.ordinal, point)
                                    }
                                }
                            }
                        }
                        WeightedVariants::class.java, MultiPartModel::class.java -> {
                            val children = if (current.javaClass == WeightedVariants::class.java) {
                                (weightedModels?.get(current) as? WeightedList<*>)?.unwrap()?.asSequence()?.map { it.value() }
                            } else {
                                multipartShared?.get(current)?.let { shared ->
                                    (multipartSelectors?.get(shared) as? List<*>)?.asSequence()?.map {
                                        (it as? MultiPartModel.Selector<*>)?.model()
                                    }
                                }
                            }
                            if (children == null) {
                                complete = false
                            } else {
                                for (child in children) {
                                    if (child !is BlockStateModel) {
                                        complete = false
                                        break
                                    }
                                    val candidate = collect(child, depth + 1)
                                    if (!candidate.complete) {
                                        complete = false
                                        break
                                    }
                                    for ([layer, incoming] in candidate.entries()) include(layer, incoming)
                                }
                            }
                        }
                        else -> complete = false
                    }
                } catch (failure: Exception) {
                    complete = false
                } finally {
                    visiting.remove(current)
                }
                return Candidates(complete, if (complete) bounds.toMap() else emptyMap()).also { models[current] = it }
            }
            return collect(model, 0)
        }
    }

    class Manifest internal constructor(val scope: D3D12SectionGeometry.CompileScope, val complete: Boolean,
                                        private val layers: Map<Int, D3D12RtGeometryPool.OutputBounds>) {
        fun bounds(layer: Int): D3D12RtGeometryPool.OutputBounds? = if (complete) layers[layer] else null

        internal val sourceBounds: D3D12RtGeometryPool.OutputBounds?
            get() = if (complete) layers.values.fold(sectionBounds) { combined, incoming -> combined.union(incoming) }
                else null

        fun contains(layer: Int, output: D3D12RtGeometryPool.OutputBounds?): Boolean {
            val support = bounds(layer) ?: return false
            return output != null && output.minimumX >= support.minimumX && output.minimumY >= support.minimumY &&
                output.minimumZ >= support.minimumZ && output.maximumX <= support.maximumX &&
                output.maximumY <= support.maximumY && output.maximumZ <= support.maximumZ
        }
    }

    class SourceManifest @JvmOverloads internal constructor(val scope: D3D12SectionGeometry.CompileScope,
                                              val bounds: D3D12RtGeometryPool.OutputBounds?,
                                              internal val fluidSupport: D3D12RtFluidSupport.Proof? = null)

    @JvmStatic
    fun sourceSupport(scope: D3D12SectionGeometry.CompileScope, position: SectionPos,
                      region: RenderSectionRegion, trusted: Boolean, additionalRenderers: Boolean): SourceManifest {
        fun unknown() = SourceManifest(scope, null)
        if (!trusted || additionalRenderers || position.asLong() != scope.sectionNode ||
            region.javaClass != RenderSectionRegion::class.java) return unknown()
        val models = scope.resources.blockModels as? BlockStateModelSet ?: return unknown()
        if (models.javaClass != BlockStateModelSet::class.java) return unknown()
        return try {
            val table = blockModels?.get(models) ?: return unknown()
            if (table.javaClass != IdentityHashMap::class.java && table !is D3D12RtModelTables.Frozen<*, *> ||
                regionMinimumX?.getInt(region) != position.x() - 1 ||
                regionMinimumY?.getInt(region) != position.y() - 1 ||
                regionMinimumZ?.getInt(region) != position.z() - 1) return unknown()
            val copies = regionSections?.get(region) as? Array<*> ?: return unknown()
            val copy = copies.getOrNull(13) as? SectionCopy ?: return unknown()
            if (copies.size != 27 || copy.javaClass != SectionCopy::class.java ||
                copiedDebug?.getBoolean(copy) != false ||
                copiedStates?.get(copy)?.javaClass != PalettedContainer::class.java) return unknown()
            val candidates = IdentityHashMap<BlockState, Candidates>()
            var fluidSupport: D3D12RtFluidSupport.Proof? = null
            var bounds = sectionBounds
            val origin = position.origin()
            val cursor = BlockPos.MutableBlockPos()
            for (localY in 0 until 16) {
                for (localZ in 0 until 16) {
                    for (localX in 0 until 16) {
                        cursor.set(origin.x + localX, origin.y + localY, origin.z + localZ)
                        val state = copy.getBlockState(cursor)
                        if (state.javaClass != BlockState::class.java) return unknown()
                        val fluid = state.fluidState
                        if (fluid.type.javaClass != EmptyFluid::class.java) {
                            val support = fluidSupport ?: D3D12RtFluidSupport.sourceSupport(scope, position, region)
                                ?.also { fluidSupport = it } ?: return unknown()
                            if (support.layer(fluid) == null) return unknown()
                        }
                        val candidate = candidates.getOrPut(state) {
                            if (!hasSupportedOffset(state)) return unknown()
                            scope.resources.modelSupport.candidates(models.get(state))
                        }
                        if (!candidate.complete) return unknown()
                        val offset = if (state.hasOffsetFunction()) state.getOffset(cursor) else Vec3.ZERO
                        val offsetX = localX.toFloat() + offset.x.toFloat()
                        val offsetY = localY.toFloat() + offset.y.toFloat()
                        val offsetZ = localZ.toFloat() + offset.z.toFloat()
                        for ([_, incoming] in candidate.entries()) {
                            val translated = incoming.translated(offsetX, offsetY, offsetZ)
                            if (!translated.isFinite()) return unknown()
                            bounds = bounds.union(translated)
                        }
                    }
                }
            }
            SourceManifest(scope, bounds, fluidSupport)
        } catch (failure: Exception) {
            unknown()
        }
    }

    class Session internal constructor(val scope: D3D12SectionGeometry.CompileScope?, trusted: Boolean,
                                       private val previous: Session?,
                                       private val source: SourceManifest? = null) : AutoCloseable {
        private var complete = trusted && scope != null
        private var closed = false
        private var finished = false
        private val layers = mutableMapOf<Int, D3D12RtGeometryPool.OutputBounds>()
        private val tintReads = IdentityHashMap<BlockTintCache, MutableSet<Long>>()
        private val biomeReads = IdentityHashMap<LevelChunkSection, Boolean>()
        private val biomeInfoReads = IdentityHashMap<ModifiableBiomeInfo, Boolean>()
        private val lightReads = IdentityHashMap<LayerLightSectionStorage<*>, MutableSet<Long>>()
        private var placement: Placement? = null

        init {
            if (!complete) source?.let(D3D12SectionGeometry::revokeSourceSupport)
        }

        fun invalidate() {
            complete = false
            source?.let(D3D12SectionGeometry::revokeSourceSupport)
        }

        internal fun currentScope(): D3D12SectionGeometry.CompileScope? = scope?.takeIf { !closed && !finished }

        fun finish(): Manifest? {
            check(!closed && !finished && active.get() === this && placement == null)
            finished = true
            return scope?.let { Manifest(it, complete, layers.toMap()) }
        }

        internal fun tint(cache: BlockTintCache, position: BlockPos) {
            if (closed || finished || scope == null) return
            val column = ChunkPos.pack(position)
            if (tintReads.getOrPut(cache, ::linkedSetOf).add(column)) D3D12SectionGeometry.recordTintRead(scope, cache, column)
        }

        internal fun biome(section: LevelChunkSection) {
            if (closed || finished || scope == null) return
            if (biomeReads.put(section, true) == null) D3D12SectionGeometry.recordBiomeRead(scope, section)
        }

        internal fun biomeInfo(owner: ModifiableBiomeInfo) {
            if (closed || finished || scope == null) return
            if (biomeInfoReads.put(owner, true) == null) D3D12SectionGeometry.recordBiomeInfoRead(scope, owner)
        }

        internal fun light(storage: LayerLightSectionStorage<*>, position: Long, updating: Boolean) {
            if (closed || finished || scope == null) return
            val node = SectionPos.blockToSection(position)
            if (updating || lightReads.getOrPut(storage, ::linkedSetOf).add(node)) {
                D3D12SectionGeometry.recordLightRead(scope, storage, node, updating)
            }
        }

        internal fun lightLayer(storage: LayerLightSectionStorage<*>, node: Long) {
            if (closed || finished || scope == null) return
            D3D12SectionGeometry.recordLightLayerRead(scope, storage, node)
        }

        internal fun beginModel(model: BlockStateModel, originX: Float, originY: Float, originZ: Float,
                                forceOpaque: Boolean, zeroOffset: Boolean): Placement {
            check(!closed && !finished)
            val candidates = scope?.resources?.modelSupport?.candidates(model)
            if (candidates?.complete != true) invalidate()
            return Placement(this, candidates, originX, originY, originZ, forceOpaque, zeroOffset, placement)
                .also { placement = it }
        }

        internal fun offset(offset: Vec3) {
            placement?.offset(offset)
        }

        internal fun fluid(renderer: FluidRenderer, level: BlockAndTintGetter, position: BlockPos,
                           state: BlockState, fluid: FluidState) {
            check(!closed && !finished)
            val layer = scope?.let { source?.fluidSupport?.outputLayer(it, renderer, level, position, state, fluid) }
            if (layer == null) {
                invalidate()
                return
            }
            layers[layer] = layers[layer]?.union(sectionBounds) ?: sectionBounds
        }

        internal fun finishModel(current: Placement) {
            check(placement === current && !closed && !finished)
            placement = current.previous
            if (!current.complete()) {
                invalidate()
                return
            }
            for ([layer, incoming] in current.layers) {
                layers[layer] = layers[layer]?.union(incoming) ?: incoming
            }
        }

        override fun close() {
            check(!closed && active.get() === this)
            closed = true
            if (previous == null) active.remove() else active.set(previous)
        }
    }

    class Placement internal constructor(private val session: Session, private val candidates: Candidates?,
                                         private val originX: Float, private val originY: Float, private val originZ: Float,
                                         private val forceOpaque: Boolean, zeroOffset: Boolean,
                                         internal val previous: Placement?) : AutoCloseable {
        internal val layers = mutableMapOf<Int, D3D12RtGeometryPool.OutputBounds>()
        private var observed = false
        private var valid = originX.isFinite() && originY.isFinite() && originZ.isFinite()
        private var closed = false

        init {
            if (zeroOffset) offset(Vec3.ZERO)
        }

        internal fun offset(offset: Vec3) {
            observed = true
            val offsetX = originX + offset.x.toFloat()
            val offsetY = originY + offset.y.toFloat()
            val offsetZ = originZ + offset.z.toFloat()
            if (candidates?.complete != true) return
            for ([candidateLayer, bounds] in candidates.entries()) {
                val layer = if (forceOpaque) D3D12SectionGeometry.Layer.SOLID.ordinal else candidateLayer
                val incoming = bounds.translated(offsetX, offsetY, offsetZ)
                if (!incoming.isFinite()) valid = false
                layers[layer] = layers[layer]?.union(incoming) ?: incoming.union(sectionBounds)
            }
        }

        internal fun complete(): Boolean = valid && candidates?.complete == true &&
            (observed || candidates.layerMask == 0)

        override fun close() {
            check(!closed)
            closed = true
            session.finishModel(this)
        }
    }

    @JvmStatic
    fun begin(scope: D3D12SectionGeometry.CompileScope?, trusted: Boolean, additionalRenderers: Boolean): Session =
        Session(scope, trusted && !additionalRenderers, active.get()).also { active.set(it) }

    internal fun begin(scope: D3D12SectionGeometry.CompileScope?, trusted: Boolean,
                       additionalRenderers: Boolean, source: SourceManifest?): Session =
        Session(scope, trusted && !additionalRenderers, active.get(), source).also { active.set(it) }

    @JvmStatic
    fun beginModel(model: BlockStateModel, originX: Float, originY: Float, originZ: Float,
                   forceOpaque: Boolean, zeroOffset: Boolean): Placement? =
        active.get()?.beginModel(model, originX, originY, originZ, forceOpaque, zeroOffset)

    @JvmStatic
    fun recordOffset(offset: Vec3) {
        active.get()?.offset(offset)
    }

    @JvmStatic
    fun recordTintRead(cache: BlockTintCache, position: BlockPos) {
        active.get()?.tint(cache, position)
    }

    internal fun currentScope(): D3D12SectionGeometry.CompileScope? = active.get()?.currentScope()

    @JvmStatic
    fun recordBiomeRead(section: LevelChunkSection) {
        active.get()?.biome(section)
    }

    @JvmStatic
    fun recordBiomeInfoRead(owner: ModifiableBiomeInfo) {
        active.get()?.biomeInfo(owner)
    }

    @JvmStatic
    fun recordLightRead(storage: LayerLightSectionStorage<*>, position: Long, updating: Boolean) {
        active.get()?.light(storage, position, updating)
    }

    @JvmStatic
    fun recordLightLayerRead(storage: LayerLightSectionStorage<*>, node: Long) {
        active.get()?.lightLayer(storage, node)
    }

    @JvmStatic
    fun recordFluid() {
        active.get()?.invalidate()
    }

    @JvmStatic
    fun recordFluid(renderer: FluidRenderer, level: BlockAndTintGetter, position: BlockPos,
                    state: BlockState, fluid: FluidState) {
        active.get()?.fluid(renderer, level, position, state, fluid)
    }
}
