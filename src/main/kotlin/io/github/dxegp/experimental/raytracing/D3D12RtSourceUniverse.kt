package io.github.dxegp.experimental.raytracing

import io.github.dxegp.d3d12.render.advanced.mesh.D3D12SectionGeometry
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.LevelRenderer
import net.minecraft.client.renderer.block.BlockStateModelSet
import net.minecraft.client.renderer.block.FluidStateModelSet
import net.minecraft.client.renderer.chunk.SectionCompiler
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher
import net.minecraft.core.DefaultedMappedRegistry
import net.minecraft.core.MappedRegistry
import net.minecraft.core.IdMapper
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState
import java.util.IdentityHashMap
import net.neoforged.bus.EventBus
import net.neoforged.bus.ListenerList
import net.neoforged.neoforge.client.event.AddSectionGeometryEvent
import net.neoforged.neoforge.common.NeoForge

class D3D12RtSourceUniverse private constructor(private val candidate: Snapshot,
                                               private val registriesCurrent: () -> Boolean = { false },
                                               private val compilerCurrent: () -> Boolean = { false }) {
    class Snapshot internal constructor(val bounds: D3D12RtGeometryPool.OutputBounds?, val reason: String,
                                         val blockStates: Int = 0, val fluidStates: Int = 0)

    fun snapshot(): Snapshot = try {
        when {
            candidate.bounds == null -> candidate
            !registriesCurrent() -> changedRegistry
            !compilerCurrent() -> unavailableCompiler
            !hasNoAdditionalRenderers() -> additionalRenderers
            else -> candidate
        }
    } catch (_: Exception) {
        unavailable
    }

    companion object {
        internal val unavailable = Snapshot(null, "unavailable")
        private val changedRegistry = Snapshot(null, "registry changed")
        private val additionalRenderers = Snapshot(null, "additional renderers")
        private val unavailableCompiler = Snapshot(null, "compiler unavailable")
        internal val unknown = D3D12RtSourceUniverse(unavailable)
        private fun field(type: Class<*>, name: String) = runCatching {
            type.getDeclaredField(name).apply { isAccessible = true }
        }.getOrNull()

        private val blockTable = field(BlockStateModelSet::class.java, "modelByState")
        private val fluidTable = field(FluidStateModelSet::class.java, "modelByFluid")
        private val frozen = field(MappedRegistry::class.java, "frozen")
        private val dispatcherCompiler = field(SectionRenderDispatcher::class.java, "sectionCompiler")
        private val dispatcherClosed = field(SectionRenderDispatcher::class.java, "closed")
        private val compilerBlocks = field(SectionCompiler::class.java, "blockModelSet")
        private val compilerFluids = field(SectionCompiler::class.java, "fluidModelSet")
        private val listenerList = runCatching {
            EventBus::class.java.getDeclaredMethod("getListenerList", Class::class.java).apply { isAccessible = true }
        }.getOrNull()

        private fun hasNoAdditionalRenderers(): Boolean {
            val bus = NeoForge.EVENT_BUS
            if (bus.javaClass != EventBus::class.java) return false
            val listeners = listenerList?.invoke(bus, AddSectionGeometryEvent::class.java) as? ListenerList ?: return false
            return listeners.javaClass == ListenerList::class.java && listeners.listeners.isEmpty()
        }

        private fun hasCurrentCompiler(models: BlockStateModelSet, fluids: FluidStateModelSet): Boolean {
            val client: Minecraft? = Minecraft.getInstance()
            val renderer = client?.levelRenderer ?: return false
            if (renderer.javaClass != LevelRenderer::class.java) return false
            val dispatcher = renderer.sectionRenderDispatcher() ?: return false
            if (dispatcher.javaClass != SectionRenderDispatcher::class.java ||
                dispatcherClosed?.getBoolean(dispatcher) != false) return false
            val compiler = dispatcherCompiler?.get(dispatcher) as? SectionCompiler ?: return false
            return compiler.javaClass == SectionCompiler::class.java &&
                compilerBlocks?.get(compiler) === models && compilerFluids?.get(compiler) === fluids
        }

        private fun <Value> sameIdentities(current: Iterable<Value>, original: List<Value>): Boolean {
            val iterator = current.iterator()
            for (value in original) {
                if (!iterator.hasNext() || iterator.next() !== value) return false
            }
            return !iterator.hasNext()
        }

        internal fun create(resources: D3D12SectionGeometry.ModelResources): D3D12RtSourceUniverse {
            val models = resources.blockModels as? BlockStateModelSet ?: return unknown
            val fluids = resources.fluidModels as? FluidStateModelSet ?: return unknown
            if (models.javaClass != BlockStateModelSet::class.java ||
                fluids.javaClass != FluidStateModelSet::class.java) return unknown
            return try {
                if (blockTable?.get(models) !is D3D12RtModelTables.Frozen<*, *> ||
                    fluidTable?.get(fluids) !is D3D12RtModelTables.Frozen<*, *>) return unknown
                val blockRegistry = BuiltInRegistries.BLOCK
                val fluidRegistry = BuiltInRegistries.FLUID
                if (blockRegistry.javaClass != DefaultedMappedRegistry::class.java ||
                    fluidRegistry.javaClass != DefaultedMappedRegistry::class.java ||
                    frozen?.getBoolean(blockRegistry) != true || frozen.getBoolean(fluidRegistry) != true) return unknown
                val blocks = blockRegistry.toList()
                val fluidTypes = fluidRegistry.toList()
                val stateRegistry = Block.BLOCK_STATE_REGISTRY
                if (stateRegistry.javaClass.getMethod("iterator").declaringClass != IdMapper::class.java ||
                    stateRegistry.javaClass.getMethod("size").declaringClass != IdMapper::class.java) return unknown
                val mappedStates = stateRegistry.toList()
                val states = IdentityHashMap<BlockState, Boolean>()
                for (state in mappedStates) states[state] = true
                for (block in blocks) for (state in block.stateDefinition.possibleStates) states[state] = true
                var bounds = D3D12RtModelSupport.missingUniverseBounds(models, resources.modelSupport)
                    ?: return D3D12RtSourceUniverse(Snapshot(null, "unsupported missing model"))
                var blockStates = 0
                var fluidStates = 0
                for (state in states.keys) {
                    val incoming = D3D12RtModelSupport.universeBounds(state, models, resources.modelSupport)
                        ?: return D3D12RtSourceUniverse(Snapshot(null, "unsupported block model"))
                    if (!D3D12RtFluidSupport.supportsUniverse(state.fluidState, fluids)) {
                        return D3D12RtSourceUniverse(Snapshot(null, "unsupported block fluid"))
                    }
                    bounds = D3D12RtGeometryPool.OutputBounds(minOf(bounds.minimumX, incoming.minimumX),
                        minOf(bounds.minimumY, incoming.minimumY), minOf(bounds.minimumZ, incoming.minimumZ),
                        maxOf(bounds.maximumX, incoming.maximumX), maxOf(bounds.maximumY, incoming.maximumY),
                        maxOf(bounds.maximumZ, incoming.maximumZ))
                    blockStates++
                }
                for (fluid in fluidTypes) {
                    for (state in fluid.stateDefinition.possibleStates) {
                        if (!D3D12RtFluidSupport.supportsUniverse(state, fluids)) {
                            return D3D12RtSourceUniverse(Snapshot(null, "unsupported fluid model"))
                        }
                        fluidStates++
                    }
                }
                D3D12RtSourceUniverse(Snapshot(bounds, "bounded", blockStates, fluidStates), {
                    frozen.getBoolean(blockRegistry) && frozen.getBoolean(fluidRegistry) &&
                        blockRegistry.size() == blocks.size && fluidRegistry.size() == fluidTypes.size &&
                        stateRegistry.size() == mappedStates.size && sameIdentities(blockRegistry, blocks) &&
                        sameIdentities(fluidRegistry, fluidTypes) && sameIdentities(stateRegistry, mappedStates)
                }, { hasCurrentCompiler(models, fluids) })
            } catch (_: Exception) {
                unknown
            }
        }
    }
}
