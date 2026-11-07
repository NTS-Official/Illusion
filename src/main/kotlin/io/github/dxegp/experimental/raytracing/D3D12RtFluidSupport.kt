package io.github.dxegp.experimental.raytracing

import io.github.dxegp.d3d12.render.advanced.mesh.D3D12SectionGeometry
import net.minecraft.client.renderer.block.BlockAndTintGetter
import net.minecraft.client.renderer.block.FluidRenderer
import net.minecraft.client.renderer.block.FluidModel
import net.minecraft.client.renderer.block.FluidStateModelSet
import net.minecraft.client.renderer.chunk.RenderSectionRegion
import net.minecraft.client.renderer.chunk.SectionCopy
import net.minecraft.core.BlockPos
import net.minecraft.core.SectionPos
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.PalettedContainer
import net.minecraft.world.level.material.EmptyFluid
import net.minecraft.world.level.material.FluidState
import net.minecraft.world.level.material.Fluids
import java.util.HashMap
import java.util.IdentityHashMap

object D3D12RtFluidSupport {
    private fun field(type: Class<*>, name: String) = runCatching {
        type.getDeclaredField(name).apply { isAccessible = true }
    }.getOrNull()

    private val fluidModels = field(FluidStateModelSet::class.java, "modelByFluid")
    private val rendererModels = field(FluidRenderer::class.java, "fluidModels")
    private val regionSections = field(RenderSectionRegion::class.java, "sections")
    private val regionMinimumX = field(RenderSectionRegion::class.java, "minSectionX")
    private val regionMinimumY = field(RenderSectionRegion::class.java, "minSectionY")
    private val regionMinimumZ = field(RenderSectionRegion::class.java, "minSectionZ")
    private val copiedStates = field(SectionCopy::class.java, "section")
    private val copiedDebug = field(SectionCopy::class.java, "debug")
    private val immutableMapClasses: Set<Class<*>> = setOf(java.util.Map.of<Any, Any>().javaClass,
        java.util.Map.of<Any, Any>(Any(), Any()).javaClass)
    private val ordinaryStates by lazy {
        IdentityHashMap<FluidState, Boolean>().apply {
            for (fluid in listOf(Fluids.WATER, Fluids.FLOWING_WATER, Fluids.LAVA, Fluids.FLOWING_LAVA)) {
                for (state in fluid.stateDefinition.possibleStates) {
                    if (state.type === fluid && state.amount in 1..8 && state.ownHeight.isFinite() &&
                        state.ownHeight in 0f..1f && fluid.isSame(fluid)) put(state, true)
                }
            }
        }
    }

    internal fun supportsUniverse(fluid: FluidState, models: FluidStateModelSet): Boolean {
        if (fluid.type.javaClass == EmptyFluid::class.java) return true
        if (ordinaryStates[fluid] != true) return false
        val model = models.get(fluid)
        return model.customRenderer() == null &&
            D3D12SectionGeometry.Layer.of(model.layer().name) != D3D12SectionGeometry.Layer.OTHER
    }

    class Proof internal constructor(private val scope: D3D12SectionGeometry.CompileScope,
                                     private val region: RenderSectionRegion,
                                     private val models: FluidStateModelSet,
                                     private val table: Any,
                                     private val modelsByState: Map<FluidState, FluidModel>,
                                     private val layers: Map<FluidState, Int>) {
        internal fun layer(fluid: FluidState): Int? = layers[fluid]

        internal fun outputLayer(currentScope: D3D12SectionGeometry.CompileScope, renderer: FluidRenderer,
                                 level: BlockAndTintGetter, position: BlockPos, state: BlockState,
                                 fluid: FluidState): Int? {
            if (currentScope !== scope || level !== region || renderer.javaClass != FluidRenderer::class.java ||
                rendererModels?.get(renderer) !== models ||
                fluidModels?.get(models) !== table ||
                position.x shr 4 != SectionPos.x(scope.sectionNode) ||
                position.y shr 4 != SectionPos.y(scope.sectionNode) ||
                position.z shr 4 != SectionPos.z(scope.sectionNode) ||
                region.getBlockState(position) !== state || state.fluidState !== fluid) return null
            if (models.get(fluid) !== modelsByState[fluid]) return null
            return layer(fluid)
        }
    }

    internal fun sourceSupport(scope: D3D12SectionGeometry.CompileScope, position: SectionPos,
                               region: RenderSectionRegion): Proof? {
        if (position.asLong() != scope.sectionNode || region.javaClass != RenderSectionRegion::class.java) return null
        val models = scope.resources.fluidModels as? FluidStateModelSet ?: return null
        if (models.javaClass != FluidStateModelSet::class.java) return null
        return try {
            val table = fluidModels?.get(models) ?: return null
            if (table !is D3D12RtModelTables.Frozen<*, *> &&
                table.javaClass != IdentityHashMap::class.java && table.javaClass != HashMap::class.java &&
                table.javaClass !in immutableMapClasses) return null
            if (regionMinimumX?.getInt(region) != position.x() - 1 ||
                regionMinimumY?.getInt(region) != position.y() - 1 ||
                regionMinimumZ?.getInt(region) != position.z() - 1) return null
            val copies = regionSections?.get(region) as? Array<*> ?: return null
            val states = copiedStates ?: return null
            if (copies.size != 27 || copies.any { copy ->
                    copy !is SectionCopy || copy.javaClass != SectionCopy::class.java ||
                        copiedDebug?.getBoolean(copy) != false ||
                        states.get(copy)?.let { it.javaClass != PalettedContainer::class.java } == true
                }) return null
            val origin = position.origin()
            val cursor = BlockPos.MutableBlockPos()
            val layers = IdentityHashMap<FluidState, Int>()
            val modelsByState = IdentityHashMap<FluidState, FluidModel>()
            val checked = IdentityHashMap<FluidState, Boolean>()
            for (localY in -1..16) {
                for (localZ in -1..16) {
                    for (localX in -1..16) {
                        cursor.set(origin.x + localX, origin.y + localY, origin.z + localZ)
                        val state = region.getBlockState(cursor)
                        if (state.javaClass != BlockState::class.java) return null
                        val fluid = state.fluidState
                        if (fluid.type.javaClass == EmptyFluid::class.java) continue
                        if (checked.put(fluid, true) == null && ordinaryStates[fluid] != true) return null
                        if (localX in 0 until 16 && localY in 0 until 16 && localZ in 0 until 16 &&
                            !layers.containsKey(fluid)) {
                            val model = models.get(fluid)
                            if (model.customRenderer() != null) return null
                            val layer = D3D12SectionGeometry.Layer.of(model.layer().name)
                            if (layer == D3D12SectionGeometry.Layer.OTHER) return null
                            layers[fluid] = layer.ordinal
                            modelsByState[fluid] = model
                        }
                    }
                }
            }
            Proof(scope, region, models, table, modelsByState, layers)
        } catch (failure: Exception) {
            null
        }
    }
}
