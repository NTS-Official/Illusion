package io.github.dxegp.experimental.raytracing

import net.minecraft.client.Minecraft
import net.minecraft.client.color.block.BlockTintSource
import net.minecraft.client.color.block.BlockTintSources
import net.minecraft.client.renderer.BiomeColors
import net.minecraft.client.renderer.LevelRenderer
import net.minecraft.client.renderer.chunk.SectionCompiler
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher
import java.util.Collections

object D3D12RtCompilerInputs {
    class Descriptor internal constructor(val compiler: SectionCompiler, val blockModels: Any?,
                                          val fluidModels: Any?, val blockColors: Any?)

    class FrozenTintSources internal constructor(sources: List<BlockTintSource>) : AbstractList<BlockTintSource>() {
        private val values = Collections.unmodifiableList(ArrayList(sources))
        override val size: Int get() = values.size
        override fun get(index: Int): BlockTintSource = values[index]
    }

    private fun field(type: Class<*>, name: String) = runCatching {
        type.getDeclaredField(name).apply { isAccessible = true }
    }.getOrNull()

    private val dispatcherCompiler = field(SectionRenderDispatcher::class.java, "sectionCompiler")
    private val dispatcherClosed = field(SectionRenderDispatcher::class.java, "closed")
    private val compilerBlocks = field(SectionCompiler::class.java, "blockModelSet")
    private val compilerFluids = field(SectionCompiler::class.java, "fluidModelSet")
    private val compilerColors = field(SectionCompiler::class.java, "blockColors")
    private val immutableTintListTypes = setOf("java.util.Collections\$EmptyList", "java.util.Collections\$SingletonList",
        "java.util.ImmutableCollections\$List12", "java.util.ImmutableCollections\$ListN", "kotlin.collections.EmptyList")
    private val tintSourceTypes by lazy {
        listOf(BlockTintSources.constant(0), BlockTintSources.constant(0, 0),
            BlockTintSources.doubleTallGrass(), BlockTintSources.grass(), BlockTintSources.grassBlock(),
            BlockTintSources.sugarCane(), BlockTintSources.foliage(), BlockTintSources.dryFoliage(),
            BlockTintSources.water(), BlockTintSources.waterParticles(), BlockTintSources.redstone(),
            BlockTintSources.stem()).mapTo(mutableSetOf()) { it.javaClass }
    }

    internal fun supportsTintSource(source: BlockTintSource?): Boolean = source?.javaClass in tintSourceTypes

    private val doubleGrassTintType by lazy { BlockTintSources.doubleTallGrass().javaClass }
    private val biomeTintSourceTypes by lazy {
        mapOf(doubleGrassTintType to BiomeColors.GRASS_COLOR_RESOLVER,
            BlockTintSources.grass().javaClass to BiomeColors.GRASS_COLOR_RESOLVER,
            BlockTintSources.grassBlock().javaClass to BiomeColors.GRASS_COLOR_RESOLVER,
            BlockTintSources.sugarCane().javaClass to BiomeColors.GRASS_COLOR_RESOLVER,
            BlockTintSources.foliage().javaClass to BiomeColors.FOLIAGE_COLOR_RESOLVER,
            BlockTintSources.dryFoliage().javaClass to BiomeColors.DRY_FOLIAGE_COLOR_RESOLVER,
            BlockTintSources.water().javaClass to BiomeColors.WATER_COLOR_RESOLVER)
    }

    internal fun tintResolver(source: BlockTintSource) = biomeTintSourceTypes[source.javaClass]

    internal fun lowerTintPosition(source: BlockTintSource): Boolean =
        source.javaClass == doubleGrassTintType

    internal fun supportsTintSlot(sources: List<BlockTintSource>?, index: Int): Boolean {
        if (sources == null || index < 0) return false
        if (sources !is FrozenTintSources && sources.javaClass.name !in immutableTintListTypes) return false
        if (index >= sources.size) return true
        return (sources as List<*>)[index].let { it == null || it is BlockTintSource && supportsTintSource(it) }
    }

    internal fun activeDispatcher(): SectionRenderDispatcher? = runCatching {
        val client: Minecraft? = Minecraft.getInstance()
        val renderer = client?.levelRenderer ?: return null
        if (renderer.javaClass != LevelRenderer::class.java) return null
        val dispatcher = renderer.sectionRenderDispatcher() ?: return null
        dispatcher.takeIf {
            it.javaClass == SectionRenderDispatcher::class.java && dispatcherClosed?.getBoolean(it) == false
        }
    }.getOrNull()

    internal fun current(): Descriptor? = runCatching {
        val dispatcher = activeDispatcher() ?: return null
        val compiler = dispatcherCompiler?.get(dispatcher) as? SectionCompiler ?: return null
        describe(compiler)
    }.getOrNull()

    internal fun describe(compiler: SectionCompiler): Descriptor? = runCatching {
        if (compilerBlocks == null || compilerFluids == null || compilerColors == null) return null
        Descriptor(compiler, compilerBlocks.get(compiler), compilerFluids.get(compiler), compilerColors.get(compiler))
    }.getOrNull()

    @JvmStatic
    fun freezeTintSources(sources: List<BlockTintSource>): List<BlockTintSource> {
        if (sources is FrozenTintSources) return sources
        return when (sources.javaClass.name) {
            "java.util.ArrayList", "java.util.Arrays\$ArrayList", "java.util.Collections\$EmptyList",
            "java.util.Collections\$SingletonList", "java.util.ImmutableCollections\$List12",
            "java.util.ImmutableCollections\$ListN", "kotlin.collections.EmptyList" -> FrozenTintSources(sources)
            else -> sources
        }
    }
}
