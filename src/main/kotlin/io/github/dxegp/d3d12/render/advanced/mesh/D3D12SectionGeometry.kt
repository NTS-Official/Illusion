package io.github.dxegp.d3d12.render.advanced.mesh

import io.github.dxegp.experimental.raytracing.D3D12RtGeometryPool
import io.github.dxegp.experimental.raytracing.D3D12RtMaterialTable
import io.github.dxegp.experimental.raytracing.D3D12RtModelSupport
import io.github.dxegp.experimental.raytracing.D3D12RtSourceUniverse
import io.github.dxegp.experimental.raytracing.D3D12RtCompilerInputs
import io.github.dxegp.experimental.raytracing.D3D12RtBlockExtensions
import io.github.dxegp.experimental.raytracing.D3D12RtBlockTintInputs
import io.github.dxegp.experimental.raytracing.D3D12RtLightInputs
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import it.unimi.dsi.fastutil.longs.Long2ObjectMap
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap
import net.minecraft.world.level.lighting.SkyLightSectionStorage
import io.github.dxegp.experimental.raytracing.D3D12RtLightArrays
import io.github.dxegp.experimental.raytracing.D3D12RtSectionArrays
import io.github.dxegp.experimental.raytracing.D3D12RtPaletteStorage
import io.github.dxegp.experimental.raytracing.D3D12RtPaletteArrays
import io.github.dxegp.experimental.raytracing.D3D12RtPaletteEntries
import io.github.dxegp.experimental.raytracing.D3D12RtIdMaps
import io.github.dxegp.experimental.raytracing.D3D12RtBiomeInputs
import io.github.dxegp.experimental.raytracing.D3D12RtTintInputs
import io.github.dxegp.experimental.raytracing.D3D12RtColorTables
import net.neoforged.neoforge.common.world.ModifiableBiomeInfo
import net.minecraft.core.Holder
import net.minecraft.client.Minecraft
import net.minecraft.client.OptionInstance
import net.minecraft.client.color.block.BlockColors
import net.minecraft.client.color.block.BlockTintSource
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.Block
import net.neoforged.neoforge.client.extensions.common.IClientBlockExtensions
import net.minecraft.client.color.block.BlockTintCache
import net.minecraft.core.BlockPos
import net.minecraft.client.multiplayer.ClientChunkCache
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.renderer.chunk.SectionCompiler
import net.minecraft.client.renderer.block.BlockAndTintGetter
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher
import net.minecraft.client.renderer.chunk.ChunkSectionLayer
import net.minecraft.client.renderer.chunk.CompiledSectionMesh
import net.minecraft.client.renderer.chunk.SectionMesh
import net.minecraft.core.SectionPos
import net.minecraft.util.BitStorage
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.ColorResolver
import net.minecraft.world.level.biome.BiomeManager
import net.minecraft.world.level.biome.BiomeResolver
import net.minecraft.world.level.chunk.LevelChunk
import net.minecraft.world.level.chunk.LevelChunkSection
import net.minecraft.world.level.chunk.PalettedContainer
import net.minecraft.world.level.chunk.Palette
import net.minecraft.world.level.chunk.SingleValuePalette
import net.minecraft.world.level.chunk.PalettedContainerRO
import net.minecraft.world.level.chunk.DataLayer
import net.minecraft.world.level.lighting.DataLayerStorageMap
import net.minecraft.world.level.lighting.LayerLightSectionStorage
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.client.event.ModelEvent
import io.github.dxegp.misc.IllusionLogger
import io.github.dxegp.misc.settings.D3D12Settings
import org.slf4j.LoggerFactory
import java.lang.ref.Reference
import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.IdentityHashMap
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicIntegerArray
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReferenceArray
import java.util.function.BooleanSupplier
import java.util.function.IntSupplier
import java.util.function.Supplier
import kotlin.math.abs

/**
 * 段几何注册表：段上传时采集 CPU 侧元信息，不缓存 GPU 句柄（Uber 缓冲扩容/整理会让句柄失效）。
 * `levelIdentity` 变化（切世界/重连）时整体清空并递增 epoch。见 docs/mesh&vrs/README.md §2.1。
 */
object D3D12SectionGeometry {
    private val LOGGER = LoggerFactory.getLogger("dxr_mc/mesh")

    /** 单层（SOLID/CUTOUT/TRANSLUCENT）的上传规模。 */
    /**
     * 这一层的索引从哪来。原版对**非**自定义索引的层用一张共享的顺序索引缓冲
     * （`RenderSystem.sharedSequentialQuad`，模式 `(i,i+1,i+2, i+2,i+3,i)`），只有需要排序的
     * 半透明层才带自己的一片索引（`RenderSectionBufferSlice.indexBuffer`）。
     * 对 BLAS 来说两者都是"可用的索引"，区别只在能不能直接复用原版那块缓冲。
     */
    enum class IndexSource {
        /** 没有自己的索引：用原版共享的顺序 quad 缓冲（`RenderSystem.getSequentialBuffer(QUADS)`）。 */
        SEQUENTIAL_QUADS,

        /** 自带索引切片（排序后的半透明等），格式见 [LayerUpload.indexStrideBytes]。 */
        CUSTOM_SLICE,
    }

    data class LayerUpload(
        val layer: String,
        val indexCount: Int,
        val vertexBytes: Int,
        val indexBytes: Int,
        val indexSource: IndexSource = IndexSource.SEQUENTIAL_QUADS,
        /** 自带索引时的元素字节数（2 = R16，4 = R32）；共享路径在分配前不确定，记 0。 */
        val indexStrideBytes: Int = 0,
        /** 该段的 meshlet 表（32 B/条）；`[mesh] terrain` 打开时每次上传都切，否则只做 shadow 统计。 */
        val meshlets: List<D3D12MeshletBuilder.Meshlet> = emptyList(),
    ) {
        val triangles: Int get() = indexCount / 3
        val meshletBytes: Int get() = meshlets.size * D3D12MeshletBuilder.MESHLET_DESC_BYTES
        private val meshletTemplate = ByteBuffer.allocate(meshletBytes).order(ByteOrder.LITTLE_ENDIAN).apply {
            for (meshlet in meshlets) {
                meshlet.pack(this, 0, meshlet.firstQuad * D3D12MeshletBuilder.VERTICES_PER_QUAD)
            }
        }.array()

        fun appendMeshlets(destination: ByteBuffer, sectionId: Int) {
            val start = destination.position()
            destination.put(meshletTemplate)
            for (offset in meshletTemplate.indices step D3D12MeshletBuilder.MESHLET_DESC_BYTES) {
                destination.putInt(start + offset + 8, sectionId)
            }
        }
    }

    data class SectionGeometry(
        val key: Long,
        val epoch: Int,
        val generation: Long,
        val geometryVersion: Int,
        val capturedAtNanos: Long,
        val layers: Map<String, LayerUpload>,
    ) {
        val triangles: Int get() = layers.values.sumOf { it.triangles }
    }

    private val sections = ConcurrentHashMap<Long, SectionGeometry>()
    private data class PendingLayer(
        val vertex: ByteBuffer?,
        val index: ByteBuffer?,
        val upload: LayerUpload,
    ) {
        val hasPayload: Boolean
            get() {
                val vertexBytes = vertex?.remaining() ?: 0
                if (vertexBytes == 0 || vertexBytes % (D3D12MeshletBuilder.VERTEX_STRIDE * QUAD_VERTICES) != 0 ||
                    vertexBytes.toLong() / D3D12MeshletBuilder.VERTEX_STRIDE / QUAD_VERTICES * QUAD_INDICES !=
                    upload.indexCount.toLong()) return false
                return upload.indexSource == IndexSource.SEQUENTIAL_QUADS ||
                    index != null && (upload.indexStrideBytes == 2 || upload.indexStrideBytes == 4) &&
                    index.remaining().toLong() == upload.indexCount.toLong() * upload.indexStrideBytes
            }
    }

    private data class PendingUpload(
        val sectionNode: Long,
        val epoch: Int,
        val layers: MutableMap<String, PendingLayer> = linkedMapOf(),
    )

    private val pendingUploads = IdentityHashMap<Any, PendingUpload>()
    private class MeshKey(mesh: Any, queue: ReferenceQueue<Any>? = null) : WeakReference<Any>(mesh, queue) {
        private val identityHash = System.identityHashCode(mesh)
        override fun hashCode(): Int = identityHash
        override fun equals(other: Any?): Boolean = this === other ||
            other is MeshKey && get()?.let { it === other.get() } == true
    }

    private val releasedMeshes = ReferenceQueue<Any>()
    private val meshUploads = hashMapOf<MeshKey, MutableMap<String, LayerUpload>>()

    private fun reapMeshUploads() {
        while (true) meshUploads.remove(releasedMeshes.poll() ?: return)
    }
    @Volatile
    private var epoch: Int = 0
    private var generation: Long = 0
    private var levelIdentity: Any? = null
    private class BlockSource(val node: Long, val chunk: LevelChunk, val section: LevelChunkSection, val index: Int) {
        val palette = section.states
        var storage: BitStorage? = null
        var entries: Palette<*>? = null
        var mappingOwners: List<Any> = emptyList()
    }
    private class BiomeSource(val node: Long, val chunk: LevelChunk, val section: LevelChunkSection, val index: Int) {
        var palette: PalettedContainer<*>? = null
        var storage: BitStorage? = null
        var entries: Palette<*>? = null
        var mappingOwners: List<Any> = emptyList()
    }
    private data class LoadedChunk(val identity: Any, val nodes: LongArray, val blockSources: List<BlockSource>,
                                   val biomeSources: List<BiomeSource>)
    private val loadedChunks = mutableMapOf<Long, LoadedChunk>()
    private val blockPaletteSources = IdentityHashMap<PalettedContainer<*>, MutableSet<BlockSource>>()
    private val blockSectionSources = IdentityHashMap<LevelChunkSection, MutableSet<BlockSource>>()
    private val biomePaletteSources = IdentityHashMap<PalettedContainer<*>, MutableSet<BiomeSource>>()
    private val biomeSectionSources = IdentityHashMap<LevelChunkSection, MutableSet<BiomeSource>>()
    private val blockStorageSources = IdentityHashMap<BitStorage, MutableSet<BlockSource>>()
    private val biomeStorageSources = IdentityHashMap<BitStorage, MutableSet<BiomeSource>>()
    private val blockEntrySources = IdentityHashMap<Palette<*>, MutableSet<BlockSource>>()
    private val biomeEntrySources = IdentityHashMap<Palette<*>, MutableSet<BiomeSource>>()
    private val blockMappingSources = IdentityHashMap<Any, MutableSet<BlockSource>>()
    private val biomeMappingSources = IdentityHashMap<Any, MutableSet<BiomeSource>>()
    private val paletteArraySources = IdentityHashMap<LongArray, MutableSet<BitStorage>>()
    private val unavailableBlockStorage = mutableSetOf<Long>()
    private val unavailableBiomeStorage = mutableSetOf<Long>()
    class ModelResources internal constructor(val blockModels: Any?, val fluidModels: Any?) {
        internal val modelSupport = D3D12RtModelSupport.Cache()
        internal val sourceUniverse by lazy { D3D12RtSourceUniverse.create(this) }
    }
    class CompileScope internal constructor(val sectionNode: Long, val worldIdentity: Any,
                                            val epoch: Int, val modelRevision: Long, val resources: ModelResources,
                                            private val inputs: Map<Long, Long>, internal val compilerRevision: Long,
                                            internal val compilerIdentity: SectionCompiler?,
                                            internal val lightInputs: D3D12RtLightInputs.Snapshot?) {
        internal val tintInputs = IdentityHashMap<BlockTintCache, MutableSet<Long>>()
        internal val biomeNodes = linkedSetOf<Long>()
        internal val biomeInfoOwners = IdentityHashMap<ModifiableBiomeInfo, Boolean>()
        internal var colorTables = 0
        internal val blockExtensions = IdentityHashMap<Block, D3D12RtBlockExtensions.Input>()
        internal val lightColumns = inputs.keys.mapTo(linkedSetOf(), SectionPos::getZeroNode)
        internal fun matchesInputs(versions: Map<Long, Long>): Boolean =
            inputs.all { [node, version] -> (versions[node] ?: 0L) == version }
    }
    private val compileScopes = mutableMapOf<Long, CompileScope>()
    private val lightReaders = mutableMapOf<Long, MutableSet<Long>>()
    private val lightLayerReaders = IdentityHashMap<DataLayer, MutableSet<Long>>()
    private val lightQueueReaders = IdentityHashMap<LayerLightSectionStorage<*>, MutableMap<Long, MutableSet<Long>>>()
    private val lightArrayReaders = IdentityHashMap<ByteArray, MutableSet<DataLayer>>()
    private val lightLayerArrays = IdentityHashMap<DataLayer, ByteArray>()
    private val tintReaders = IdentityHashMap<BlockTintCache, MutableMap<Long, MutableSet<CompileScope>>>()
    private val biomeReaders = mutableMapOf<Long, MutableSet<CompileScope>>()
    private val biomeInfoReaders = IdentityHashMap<ModifiableBiomeInfo, MutableSet<CompileScope>>()
    private val colorTableReaders = Array(D3D12RtColorTables.COUNT) { linkedSetOf<CompileScope>() }
    private val blockExtensionReaders = IdentityHashMap<Block, MutableSet<CompileScope>>()
    private val biomePublications = mutableMapOf<Long, Int>()
    private class PublicationInput(var active: Int = 0, var failureRevision: Long = 0L, var failed: Boolean = false)
    private val biomeInputs = mutableMapOf<Long, PublicationInput>()
    private val chunkInputs = mutableMapOf<Long, PublicationInput>()
    private val sectionArrayInputs = mutableSetOf<Long>()
    private val blockStateInputs = mutableMapOf<Long, PublicationInput>()
    private val blockSectionInputs = mutableMapOf<Long, PublicationInput>()
    private val biomePaletteInputs = mutableMapOf<Long, PublicationInput>()
    private val biomeSectionInputs = mutableMapOf<Long, PublicationInput>()
    internal data class BlockInputRevision(val node: Long, val state: Long, val section: Long)
    internal data class BiomeInputRevision(val node: Long, val palette: Long, val section: Long?)
    private val levelTintCaches = ClientLevel::class.java.getDeclaredField("tintCaches").apply { isAccessible = true }
    private val chunkStorage = ClientChunkCache::class.java.getDeclaredField("storage").apply { isAccessible = true }
    private val chunkInRange = chunkStorage.type.getDeclaredMethod("inRange", Int::class.javaPrimitiveType,
        Int::class.javaPrimitiveType).apply { isAccessible = true }
    private val chunkSlots = chunkStorage.type.getDeclaredField("chunks").apply { isAccessible = true }
    private val chunkRadius = chunkStorage.type.getDeclaredField("chunkRadius").apply { isAccessible = true }
    private val chunkCenterX = chunkStorage.type.getDeclaredField("viewCenterX").apply { isAccessible = true }
    private val chunkCenterZ = chunkStorage.type.getDeclaredField("viewCenterZ").apply { isAccessible = true }
    private val inputVersions = mutableMapOf<Long, Long>()
    private var modelResources = ModelResources(null, null)
    private var compilerIdentity: SectionCompiler? = null
    private var compilerRevision = 0L
    @get:JvmStatic
    @Volatile
    var modelRevision = 0L
        private set
    @get:JvmStatic
    @Volatile
    var contentRevision = 0L
        private set

    /** 采集计数与成本。 */
    private val uploads = AtomicLong()
    private val capturedTriangles = AtomicLong()
    private val emptyUploads = AtomicLong()
    private val vertexBytesTotal = AtomicLong()
    private val indexBytesTotal = AtomicLong()
    private val customIndexLayers = AtomicLong()
    private val captureNanosTotal = AtomicLong()

    /** shadow 统计只切前 [MESHLET_SHADOW_UPLOADS] 次上传，默认路径零成本。 */
    private const val MESHLET_SHADOW_UPLOADS = 64
    private val meshletUploads = AtomicLong()
    private val meshletCount = AtomicLong()
    private val meshletPure = AtomicLong()
    private val meshletQuads = AtomicLong()
    private val meshletDirections = AtomicIntegerArray(7)

    fun sectionCount(): Int = sections.size

    /** 第一条契约违例的文字（日志里只留一条，避免刷屏）。 */
    private var firstViolation: String? = null

    /**
     * 只检查"不依赖导出面就能判"的三条：顶点必须是 28 B 的整数倍、索引数与索引字节要自洽、
     * 走共享顺序 quad 缓冲的层索引数必须是 6 的倍数（一个 quad 6 个索引）。
     * 依赖体素导出的检查（逐步方块的 kind 与面数）要等 S2，见 §3.6。
     */
    private fun checkContract(
        layer: String,
        indexCount: Int,
        vertexBytes: Int,
        indexBytes: Int,
        source: IndexSource,
        indexStrideBytes: Int,
    ) {
        if (vertexBytes % D3D12MeshletBuilder.VERTEX_STRIDE != 0) {
            violation("layer=$layer vertexBytes=$vertexBytes 不是 ${D3D12MeshletBuilder.VERTEX_STRIDE} 的整数倍")
        }
        if (indexCount > 0 && source == IndexSource.SEQUENTIAL_QUADS && indexCount % QUAD_INDICES != 0) {
            violation("layer=$layer indexCount=$indexCount 不是 $QUAD_INDICES 的整数倍（共享 quad 缓冲）")
        }
        if (source == IndexSource.CUSTOM_SLICE && indexCount > 0) {
            if (indexBytes % indexCount != 0) {
                violation("layer=$layer indexBytes=$indexBytes 不能被 indexCount=$indexCount 整除")
            }
            if (indexStrideBytes != 2 && indexStrideBytes != 4) {
                violation("layer=$layer indexStrideBytes=$indexStrideBytes 既不是 R16 也不是 R32")
            }
        }
    }

    private fun violation(message: String) {
        violations.incrementAndGet()
        if (firstViolation == null) {
            firstViolation = message
        }
    }
    fun epoch(): Int = epoch

    fun registerModelReloads(eventBus: IEventBus) {
        eventBus.addListener(ModelEvent.BakingCompleted::class.java) { event ->
            observeModelResources(event.modelManager.blockStateModelSet, event.modelManager.fluidStateModelSet)
        }
    }

    @JvmStatic
    @Synchronized
    fun observeModelResources(blockModels: Any, fluidModels: Any?): Boolean {
        if (modelResources.blockModels === blockModels && modelResources.fluidModels === fluidModels) return false
        val nodes = buildSet {
            for (chunk in loadedChunks.values) addAll(chunk.nodes.asList())
            addAll(compileScopes.keys)
            addAll(sections.keys)
            addAll(pendingUploads.values.map { it.sectionNode })
        }.toLongArray()
        val resources = ModelResources(blockModels, fluidModels)
        D3D12RtGeometryPool.invalidateModelResources(nodes, resources.sourceUniverse) {
            modelResources = resources
            modelRevision++
            contentRevision++
            compileScopes.clear()
            lightReaders.clear()
            lightLayerReaders.clear()
            lightQueueReaders.clear()
            lightArrayReaders.clear()
            lightLayerArrays.clear()
            tintReaders.clear()
            biomeReaders.clear()
            biomeInfoReaders.clear()
            colorTableReaders.forEach { it.clear() }
            blockExtensionReaders.clear()
            pendingUploads.clear()
            sections.clear()
            D3D12RtMaterialTable.clear()
        }
        return true
    }

    @JvmStatic
    @Synchronized
    fun observeLevel(level: Any?): Boolean {
        if (levelIdentity === level) return false
        sections.clear()
        compileScopes.clear()
        lightReaders.clear()
        lightLayerReaders.clear()
        lightQueueReaders.clear()
        lightArrayReaders.clear()
        lightLayerArrays.clear()
        tintReaders.clear()
        biomeReaders.clear()
        biomeInfoReaders.clear()
        colorTableReaders.forEach { it.clear() }
        blockExtensionReaders.clear()
        biomePublications.clear()
        biomeInputs.clear()
        chunkInputs.clear()
        sectionArrayInputs.clear()
        blockStateInputs.clear()
        blockSectionInputs.clear()
        blockPaletteSources.clear()
        blockSectionSources.clear()
        biomePaletteSources.clear()
        biomeSectionSources.clear()
        blockStorageSources.clear()
        biomeStorageSources.clear()
        blockEntrySources.clear()
        biomeEntrySources.clear()
        blockMappingSources.clear()
        biomeMappingSources.clear()
        paletteArraySources.clear()
        unavailableBlockStorage.clear()
        unavailableBiomeStorage.clear()
        biomePaletteInputs.clear()
        biomeSectionInputs.clear()
        inputVersions.clear()
        loadedChunks.clear()
        contentRevision++
        pendingUploads.clear()
        meshUploads.clear()
        reapMeshUploads()
        levelIdentity = level
        D3D12RtLightArrays.install(::publishLightArray, ::readLightArray)
        D3D12RtSectionArrays.install(::publishSectionArray)
        D3D12RtPaletteArrays.install(::publishPaletteArray)
        D3D12RtLightInputs.observeLevel(level)
        compilerIdentity = D3D12RtCompilerInputs.current()?.compiler
        compilerRevision++
        epoch++
        D3D12RtGeometryPool.clear(epoch)
        D3D12RtMaterialTable.clear()
        return true
    }

    @JvmStatic
    @Synchronized
    fun compileScope(sectionNode: Long, levelIdentity: Any?): CompileScope? {
        if (levelIdentity == null || this.levelIdentity !== levelIdentity ||
            D3D12RtGeometryPool.isSectionUnloaded(sectionNode) ||
            hasUnavailableBlockInput(sectionNode) ||
            hasUnavailableChunkInput(ChunkPos.pack(SectionPos.x(sectionNode), SectionPos.z(sectionNode)))) return null
        observeCurrentCompiler()
        compileScopes[sectionNode]?.takeIf(::isCurrent)?.let { return it }
        removeCompileScope(sectionNode)
        val nodes = regionInputs(sectionNode)
        val inputs = nodes.associateWith { inputVersions[it] ?: 0L }
        val lightInputs = D3D12RtLightInputs.capture(levelIdentity, nodes)
        if (lightInputs?.matches(levelIdentity) == false) return null
        for (node in nodes) lightReaders.getOrPut(SectionPos.getZeroNode(node), ::linkedSetOf).add(sectionNode)
        for (layer in lightInputs?.layers.orEmpty()) {
            lightLayerReaders.getOrPut(layer, ::linkedSetOf).add(sectionNode)
            D3D12RtLightArrays.current(layer)?.let { linkLightArray(layer, it) }
        }
        return CompileScope(sectionNode, levelIdentity, epoch, modelRevision, modelResources, inputs,
            compilerRevision, compilerIdentity, lightInputs)
            .also { compileScopes[sectionNode] = it }
    }

    @JvmStatic
    @Synchronized
    fun observeCurrentCompiler(): Boolean {
        val compiler = D3D12RtCompilerInputs.current()?.compiler
        if (compilerIdentity === compiler) return false
        invalidateCompilerInputs(compiler) {}
        return true
    }

    @JvmStatic
    @Synchronized
    fun publishCompiler(dispatcher: SectionRenderDispatcher, compiler: SectionCompiler, publish: Runnable) {
        if (D3D12RtCompilerInputs.activeDispatcher() !== dispatcher ||
            compilerIdentity === compiler && D3D12RtCompilerInputs.current()?.compiler === compiler) {
            publish.run()
            return
        }
        invalidateCompilerInputs(compiler) { publish.run() }
    }

    @JvmStatic
    @Synchronized
    fun publishTintSources(colors: BlockColors, publish: Runnable) {
        val inputs = D3D12RtCompilerInputs.current()
        if (inputs?.blockColors !== colors) {
            publish.run()
            return
        }
        invalidateCompilerInputs(inputs.compiler) { publish.run() }
    }

    @JvmStatic
    fun recordBlockTintSource(colors: BlockColors, source: BlockTintSource?) {
        if (D3D12RtModelSupport.currentScope() == null && !D3D12RtTintInputs.hasRead()) return
        recordBlockTintQualification(colors, D3D12RtCompilerInputs.supportsTintSource(source))
    }

    @JvmStatic
    @Synchronized
    fun beginBlockTintSource(source: BlockTintSource, state: BlockState, level: BlockAndTintGetter,
                             position: BlockPos): D3D12RtBlockTintInputs.Calculation {
        if (D3D12RtTintInputs.hasRead()) rejectBiomeInput()
        return D3D12RtBlockTintInputs.begin(source, state, level, position, levelIdentity, epoch)
    }

    @JvmStatic
    @Synchronized
    fun finishBlockTintSource(calculation: D3D12RtBlockTintInputs.Calculation, completed: Boolean): D3D12RtBlockTintInputs.Input? =
        D3D12RtBlockTintInputs.finish(calculation, completed, levelIdentity, epoch)

    @JvmStatic
    fun recordBlockTintInput(state: BlockState, sources: List<BlockTintSource>?, index: Int,
                             level: BlockAndTintGetter, position: BlockPos, input: D3D12RtBlockTintInputs.Input?) {
        if (D3D12RtModelSupport.currentScope() == null && !D3D12RtTintInputs.hasRead()) return
        synchronized(D3D12SectionGeometry::class.java) {
            if (!D3D12RtCompilerInputs.supportsTintSlot(sources, index)) {
                rejectBiomeInput()
                return
            }
            val source = sources?.getOrNull(index) ?: return
            if (D3D12RtTintInputs.hasRead() || input?.current(source, state, level, position, levelIdentity, epoch) != true) {
                rejectBiomeInput()
                return
            }
            val tint = input.tint ?: return
            val cache = tint.cache.get()
            if (cache == null) {
                rejectBiomeInput()
                return
            }
            D3D12RtModelSupport.currentScope()?.let { recordTintRead(it, cache, tint.columnKey) }
        }
    }

    @JvmStatic
    fun recordBlockTintSlot(colors: BlockColors, state: BlockState,
                            sources: List<BlockTintSource>?,
                            index: Int, dynamicKnown: Boolean) {
        if (D3D12RtModelSupport.currentScope() == null && !D3D12RtTintInputs.hasRead()) return
        val known = dynamicKnown && colors.javaClass == BlockColors::class.java &&
            sources === colors.getTintSources(state) && D3D12RtCompilerInputs.supportsTintSlot(sources, index)
        recordBlockTintQualification(colors, known)
    }

    private fun recordBlockTintQualification(colors: BlockColors, known: Boolean) {
        synchronized(D3D12SectionGeometry::class.java) {
            if (!known || colors.javaClass != BlockColors::class.java ||
                D3D12RtCompilerInputs.current()?.blockColors !== colors) rejectBiomeInput()
        }
    }

    @JvmStatic
    @Synchronized
    fun recordBlockTintExtension(colors: BlockColors, state: BlockState,
                                 extension: IClientBlockExtensions?): D3D12RtBlockExtensions.Input? =
        D3D12RtBlockExtensions.capture(state.block, extension).also {
            recordBlockTintExtensionInput(colors, state, it)
        }

    @JvmStatic
    fun recordBlockTintExtensionInput(colors: BlockColors, state: BlockState, input: D3D12RtBlockExtensions.Input?) {
        if (D3D12RtModelSupport.currentScope() == null && !D3D12RtTintInputs.hasRead()) return
        synchronized(D3D12SectionGeometry::class.java) {
            if (D3D12RtTintInputs.hasRead() || colors.javaClass != BlockColors::class.java ||
                D3D12RtCompilerInputs.current()?.blockColors !== colors || !D3D12RtBlockExtensions.current(input, state.block)) {
                rejectBiomeInput()
                return
            }
            D3D12RtModelSupport.currentScope()?.let { scope ->
                if (compileScopes[scope.sectionNode] === scope) {
                    scope.blockExtensions[state.block] = checkNotNull(input)
                    blockExtensionReaders.getOrPut(state.block, ::linkedSetOf).add(scope)
                }
            }
        }
    }

    @JvmStatic
    @Synchronized
    fun publishBlockExtension(block: Block, publish: Runnable) {
        val affected = blockExtensionReaders[block].orEmpty().filter { compileScopes[it.sectionNode] === it }
            .map { it.sectionNode }.distinct().toLongArray()
        revokeCompiledInputs(affected, publish)
    }

    @JvmStatic
    @Synchronized
    fun publishBiomeBlend(option: OptionInstance<*>, value: Any?, publish: Runnable) {
        val level = levelIdentity as? ClientLevel
        if (level == null || level.javaClass != ClientLevel::class.java ||
            Minecraft.getInstance()?.options?.biomeBlendRadius() !== option || option.get() == value) {
            publish.run()
            return
        }
        invalidateLevelTintCaches(level)
        try {
            publish.run()
        } finally {
            invalidateLevelTintCaches(level)
        }
    }

    private fun invalidateLevelTintCaches(level: ClientLevel) {
        val caches = levelTintCaches.get(level) as Map<*, *>
        for (cache in caches.values) {
            if (cache is BlockTintCache && cache.javaClass == BlockTintCache::class.java) cache.invalidateAll()
        }
    }

    @JvmStatic
    @Synchronized
    fun recordTintRead(scope: CompileScope, cache: BlockTintCache, column: Long) {
        if (!isCurrent(scope)) return
        if (!D3D12RtTintInputs.sourceCurrent(cache, levelIdentity) || column in biomePublications ||
            hasUnavailablePublicationInput(biomeInputs, column) || hasUnavailableChunkInput(column) ||
            hasUnavailableBlockTintInput(column) || hasUnavailableBiomeContentInput(column)) {
            revokeCompiledInputs(longArrayOf(scope.sectionNode), Runnable {})
            return
        }
        if (!scope.tintInputs.getOrPut(cache, ::linkedSetOf).add(column)) return
        tintReaders.getOrPut(cache, ::linkedMapOf).getOrPut(column, ::linkedSetOf).add(scope)
    }

    @JvmStatic
    @Synchronized
    fun recordBiomeRead(scope: CompileScope, section: LevelChunkSection) {
        if (!isCurrent(scope)) return
        val sources = biomeSectionSources[section]?.filter(::isCurrentBiomeSource).orEmpty()
        if (sources.isEmpty() || sources.any { source ->
                val column = ChunkPos.pack(SectionPos.x(source.node), SectionPos.z(source.node))
                source.palette == null || section.biomes !== source.palette ||
                    hasUnavailableChunkInput(column) || hasUnavailablePublicationInput(biomeInputs, column) ||
                    hasUnavailablePublicationInput(biomePaletteInputs, source.node) ||
                    hasUnavailablePublicationInput(biomeSectionInputs, source.node) || source.node in unavailableBiomeStorage
            }) {
            revokeCompiledInputs(longArrayOf(scope.sectionNode), Runnable {})
            return
        }
        for (source in sources) if (scope.biomeNodes.add(source.node)) {
            biomeReaders.getOrPut(source.node, ::linkedSetOf).add(scope)
        }
    }

    @JvmStatic
    @Synchronized
    fun recordBiomeInfoRead(scope: CompileScope, owner: ModifiableBiomeInfo) {
        if (!isCurrent(scope)) return
        if (!D3D12RtBiomeInputs.available(owner)) {
            revokeCompiledInputs(longArrayOf(scope.sectionNode), Runnable {})
            return
        }
        if (scope.biomeInfoOwners.put(owner, true) == null) biomeInfoReaders.getOrPut(owner, ::linkedSetOf).add(scope)
    }

    @JvmStatic
    @Synchronized
    fun publishBiomeInfo(owner: ModifiableBiomeInfo, value: ModifiableBiomeInfo.BiomeInfo?, operation: Runnable) {
        val changed = !D3D12RtBiomeInputs.same(owner, value) || D3D12RtBiomeInputs.failed(owner)
        val mutation = D3D12RtBiomeInputs.begin(owner)
        var completed = false
        try {
            refreshBiomeInfoSources(owner)
            if (changed) invalidateBiomeInfo(owner)
            operation.run()
            completed = true
        } finally {
            D3D12RtBiomeInputs.finish(mutation, completed)
            refreshBiomeInfoSources(owner)
            if (changed || !completed) invalidateBiomeInfo(owner)
        }
    }

    private fun biomeInfoSources(owner: ModifiableBiomeInfo): List<BiomeSource> =
        biomeMappingSources[owner]?.filter { isCurrentBiomeSource(it) && it.section.biomes === it.palette }.orEmpty()

    private fun refreshBiomeInfoSources(owner: ModifiableBiomeInfo) {
        for (source in biomeInfoSources(owner)) updateBiomeDataAvailability(source)
    }

    private fun invalidateBiomeInfo(owner: ModifiableBiomeInfo) {
        val affected = biomeInfoReaders[owner].orEmpty().filter { compileScopes[it.sectionNode] === it }
            .map { it.sectionNode }.distinct().toLongArray()
        revokeCompiledInputs(affected, Runnable {})
        invalidatePaletteSources(emptyList(), biomeInfoSources(owner))
    }

    @JvmStatic
    @Synchronized
    fun recordLightRead(scope: CompileScope, storage: LayerLightSectionStorage<*>, node: Long, updating: Boolean) {
        if (!isCurrent(scope)) return
        val read = scope.lightInputs?.record(storage, node, updating)
        if (read?.available != true) {
            revokeCompiledInputs(longArrayOf(scope.sectionNode), Runnable {})
            return
        }
        val column = SectionPos.getZeroNode(node)
        if (scope.lightColumns.add(column)) lightReaders.getOrPut(column, ::linkedSetOf).add(scope.sectionNode)
        read.layer?.let { layer ->
            lightLayerReaders.getOrPut(layer, ::linkedSetOf).add(scope.sectionNode)
            D3D12RtLightArrays.current(layer)?.let { linkLightArray(layer, it) }
        }
    }

    @JvmStatic
    @Synchronized
    fun recordLightLayerRead(scope: CompileScope, storage: LayerLightSectionStorage<*>, node: Long) {
        if (!isCurrent(scope)) {
            if (compileScopes[scope.sectionNode] === scope) revokeCompiledInputs(longArrayOf(scope.sectionNode), Runnable {})
            return
        }
        val read = scope.lightInputs?.recordLayer(storage, node)
        if (read?.available != true) {
            revokeCompiledInputs(longArrayOf(scope.sectionNode), Runnable {})
            return
        }
        lightQueueReaders.getOrPut(storage, ::linkedMapOf).getOrPut(node, ::linkedSetOf).add(scope.sectionNode)
        val column = SectionPos.getZeroNode(node)
        if (scope.lightColumns.add(column)) lightReaders.getOrPut(column, ::linkedSetOf).add(scope.sectionNode)
        read.read.layer?.let { layer ->
            lightLayerReaders.getOrPut(layer, ::linkedSetOf).add(scope.sectionNode)
            D3D12RtLightArrays.current(layer)?.let { linkLightArray(layer, it) }
        }
    }

    class BiomePublication internal constructor(internal val level: ClientLevel, internal val epoch: Int,
                                              internal val chunkX: Int, internal val chunkZ: Int,
                                              internal val columns: List<Long>, internal val column: Long,
                                              internal val failureRevision: Long, internal val recoverInput: Boolean,
                                              internal val inputs: List<BiomeInputRevision>) : AutoCloseable {
        internal var completed = false
        internal var closed = false

        fun complete() = completeBiomePublication(this)

        override fun close() = finishBiomePublication(this)
    }

    @JvmStatic
    @Synchronized
    fun beginBiomePublication(level: ClientLevel, source: ClientChunkCache,
                              chunkX: Int, chunkZ: Int): BiomePublication? =
        beginBiomeInputPublication(level, source, chunkX, chunkZ, true)

    private fun beginBiomeInputPublication(level: ClientLevel, source: ClientChunkCache,
                                          chunkX: Int, chunkZ: Int, recoverInput: Boolean): BiomePublication? {
        if (levelIdentity !== level || level.chunkSource !== source ||
            level.javaClass != ClientLevel::class.java || source.javaClass != ClientChunkCache::class.java) return null
        val columns = buildList(9) {
            for (deltaZ in -1..1) {
                for (deltaX in -1..1) add(ChunkPos.pack(chunkX + deltaX, chunkZ + deltaZ))
            }
        }
        val column = ChunkPos.pack(chunkX, chunkZ)
        val sectionCount = level.sectionsCount
        require(sectionCount in 1..256)
        val nodes = LongArray(sectionCount) { SectionPos.asLong(chunkX, level.minSectionY + it, chunkZ) }
        val failureRevision = beginPublicationInput(biomeInputs, column)
        val inputs = beginBiomeContentInputs(nodes, false)
        val publication = BiomePublication(level, epoch, chunkX, chunkZ, columns, column, failureRevision, recoverInput, inputs)
        for (column in columns) biomePublications[column] = (biomePublications[column] ?: 0) + 1
        try {
            invalidateBiomeTintCaches(publication)
        } catch (problem: Throwable) {
            releasePublicationInput(biomeInputs, column)
            releaseBiomeContentInputs(inputs)
            releaseBiomePublication(publication)
            throw problem
        }
        return publication
    }

    @JvmStatic
    @Synchronized
    private fun completeBiomePublication(publication: BiomePublication) {
        check(!publication.closed)
        publication.completed = true
    }

    @JvmStatic
    @Synchronized
    private fun finishBiomePublication(publication: BiomePublication) {
        if (publication.closed) return
        if (publication.level !== levelIdentity || publication.epoch != epoch) {
            publication.closed = true
            return
        }
        var invalidated = false
        try {
            invalidateBiomeTintCaches(publication)
            invalidated = true
        } finally {
            if (publication.level === levelIdentity && publication.epoch == epoch) {
                finishPublicationInput(biomeInputs, publication.column, publication.failureRevision,
                    publication.completed && invalidated, publication.recoverInput)
                finishBiomeContentInputs(publication.inputs, publication.completed && invalidated, publication.recoverInput)
                releaseBiomePublication(publication)
            } else {
                publication.closed = true
            }
        }
    }

    private fun invalidateBiomeTintCaches(publication: BiomePublication) {
        invalidateTintCaches(publication.level, publication.chunkX, publication.chunkZ)
    }

    private fun invalidateTintCaches(level: ClientLevel, chunkX: Int, chunkZ: Int) {
        val affected = (loadedChunks[SectionPos.asLong(chunkX, 0, chunkZ)]?.nodes ?: longArrayOf())
            .flatMap { biomeReaders[it].orEmpty() }.filter { compileScopes[it.sectionNode] === it }
            .map { it.sectionNode }.distinct().toLongArray()
        revokeCompiledInputs(affected, Runnable {})
        val caches = levelTintCaches.get(level) as Map<*, *>
        for (cache in caches.values) {
            if (cache is BlockTintCache && cache.javaClass == BlockTintCache::class.java) {
                cache.invalidateForChunk(chunkX, chunkZ)
            }
        }
    }

    private fun releaseBiomePublication(publication: BiomePublication) {
        for (column in publication.columns) {
            val remaining = checkNotNull(biomePublications[column]) - 1
            if (remaining == 0) biomePublications.remove(column) else biomePublications[column] = remaining
        }
        publication.closed = true
    }

    private fun hasUnavailableBiomeContentInput(column: Long): Boolean =
        sequenceOf(biomePaletteInputs.keys, biomeSectionInputs.keys, unavailableBiomeStorage).flatten().any { node ->
            abs(SectionPos.x(node) - ChunkPos.getX(column)) <= 1 && abs(SectionPos.z(node) - ChunkPos.getZ(column)) <= 1
        }

    private fun beginBiomeContentInputs(nodes: LongArray, paletteOnly: Boolean): List<BiomeInputRevision> =
        nodes.map { node -> BiomeInputRevision(node, beginPublicationInput(biomePaletteInputs, node),
            if (paletteOnly) null else beginPublicationInput(biomeSectionInputs, node)) }

    private fun releaseBiomeContentInputs(inputs: List<BiomeInputRevision>) {
        for (input in inputs) {
            releasePublicationInput(biomePaletteInputs, input.node)
            if (input.section != null) releasePublicationInput(biomeSectionInputs, input.node)
        }
    }

    private fun finishBiomeContentInputs(inputs: List<BiomeInputRevision>, completed: Boolean, recoverInput: Boolean) {
        for (input in inputs) {
            finishPublicationInput(biomePaletteInputs, input.node, input.palette, completed, recoverInput)
            if (input.section != null) finishPublicationInput(biomeSectionInputs, input.node, input.section, completed, recoverInput)
        }
    }

    class BiomeContentPublication internal constructor(internal val level: ClientLevel, internal val epoch: Int,
                                                       internal val inputs: List<BiomeInputRevision>,
                                                       internal val revoked: LongArray,
                                                       internal val recoverInput: Boolean) : AutoCloseable {
        internal var completed = false
        internal var closed = false

        fun complete() = completeBiomeContentPublication(this)

        override fun close() = finishBiomeContentPublication(this)
    }

    @JvmStatic
    @Synchronized
    fun beginBiomePalettePublication(palette: PalettedContainer<*>, recoverInput: Boolean): BiomeContentPublication? =
        beginBiomeContentPublication(biomePaletteSources[palette], true, recoverInput)

    @JvmStatic
    @Synchronized
    fun beginBiomeSectionPublication(section: LevelChunkSection, recoverInput: Boolean): BiomeContentPublication? =
        beginBiomeContentPublication(biomeSectionSources[section], false, recoverInput)

    private fun isCurrentBiomeSource(source: BiomeSource): Boolean =
        loadedChunks[SectionPos.getZeroNode(source.node)]?.identity === source.chunk &&
            D3D12RtSectionArrays.section(source.chunk, source.index) === source.section

    private fun beginBiomeContentPublication(sources: Set<BiomeSource>?, paletteOnly: Boolean,
                                             recoverInput: Boolean): BiomeContentPublication? {
        val level = levelIdentity as? ClientLevel ?: return null
        if (level.javaClass != ClientLevel::class.java || level.chunkSource.javaClass != ClientChunkCache::class.java ||
            sources.isNullOrEmpty()) return null
        val nodes = sources.filter { isCurrentBiomeSource(it) && (!paletteOnly || it.section.biomes === it.palette) }
            .map { it.node }.distinct().toLongArray()
        if (nodes.isEmpty()) return null
        val revoked = nodes.filter { node ->
            (biomePaletteInputs[node]?.active ?: 0) == 0 && (biomeSectionInputs[node]?.active ?: 0) == 0 &&
                (biomeInputs[ChunkPos.pack(SectionPos.x(node), SectionPos.z(node))]?.active ?: 0) == 0
        }.toLongArray()
        val publication = BiomeContentPublication(level, epoch, beginBiomeContentInputs(nodes, paletteOnly), revoked, recoverInput)
        try {
            invalidateBiomeContent(publication)
        } catch (problem: Throwable) {
            releaseBiomeContentInputs(publication.inputs)
            publication.closed = true
            throw problem
        }
        return publication
    }

    private fun invalidateBiomeContent(publication: BiomeContentPublication) {
        for (column in publication.revoked.map { SectionPos.getZeroNode(it) }.distinct()) {
            invalidateTintCaches(publication.level, SectionPos.x(column), SectionPos.z(column))
        }
    }

    @JvmStatic
    @Synchronized
    private fun completeBiomeContentPublication(publication: BiomeContentPublication) {
        check(!publication.closed)
        publication.completed = true
    }

    @JvmStatic
    @Synchronized
    private fun finishBiomeContentPublication(publication: BiomeContentPublication) {
        if (publication.closed) return
        if (publication.level !== levelIdentity || publication.epoch != epoch) {
            publication.closed = true
            return
        }
        var invalidated = false
        try {
            invalidateBiomeContent(publication)
            invalidated = true
        } finally {
            if (publication.level === levelIdentity && publication.epoch == epoch) {
                finishBiomeContentInputs(publication.inputs, publication.completed && invalidated, publication.recoverInput)
            }
            publication.closed = true
        }
    }

    @JvmStatic
    @Synchronized
    fun publishBiomePalette(section: LevelChunkSection, operation: Runnable) {
        val sources = biomeSectionSources[section]?.toList().orEmpty()
        try {
            operation.run()
        } finally {
            for (source in sources) if (isCurrentBiomeSource(source)) refreshBiomePaletteSource(source)
        }
    }

    class ChunkPublication internal constructor(internal val biome: BiomePublication,
                                              internal val inputs: LongArray, internal val column: Long,
                                              internal val failureRevision: Long,
                                              internal val blockInputs: List<BlockInputRevision>) : AutoCloseable {
        internal var completed = false
        internal var closed = false

        fun complete() = completeChunkPublication(this)

        override fun close() = finishChunkPublication(this)
    }

    @JvmStatic
    @Synchronized
    fun beginChunkPublication(level: ClientLevel, source: ClientChunkCache,
                              chunkX: Int, chunkZ: Int): ChunkPublication? {
        if (levelIdentity !== level || level.chunkSource !== source || level.javaClass != ClientLevel::class.java ||
            source.javaClass != ClientChunkCache::class.java ||
            chunkInRange.invoke(chunkStorage.get(source), chunkX, chunkZ) != true) return null
        return beginChunkInputPublication(level, source, chunkX, chunkZ, true)
    }

    private fun beginChunkInputPublication(level: ClientLevel, source: ClientChunkCache,
                                          chunkX: Int, chunkZ: Int, recoverInput: Boolean): ChunkPublication? {
        if (levelIdentity !== level || level.chunkSource !== source || level.javaClass != ClientLevel::class.java ||
            source.javaClass != ClientChunkCache::class.java) return null
        val sectionCount = level.sectionsCount
        require(sectionCount in 1..256)
        val nodes = LongArray(sectionCount) { SectionPos.asLong(chunkX, level.minSectionY + it, chunkZ) }
        val retired = loadedChunks[SectionPos.asLong(chunkX, 0, chunkZ)]?.nodes ?: longArrayOf()
        val biome = checkNotNull(beginBiomeInputPublication(level, source, chunkX, chunkZ, recoverInput))
        val column = ChunkPos.pack(chunkX, chunkZ)
        val failureRevision = beginPublicationInput(chunkInputs, column)
        val blockInputs = nodes.map { BlockInputRevision(it, beginPublicationInput(blockStateInputs, it),
            beginPublicationInput(blockSectionInputs, it)) }
        val publication = ChunkPublication(biome, (nodes.asList() + retired.asList()).distinct().toLongArray(),
            column, failureRevision, blockInputs)
        try {
            invalidateChunkInputs(publication)
        } catch (problem: Throwable) {
            releasePublicationInput(chunkInputs, column)
            for (input in blockInputs) {
                releasePublicationInput(blockStateInputs, input.node)
                releasePublicationInput(blockSectionInputs, input.node)
            }
            releasePublicationInput(biomeInputs, biome.column)
            releaseBiomeContentInputs(biome.inputs)
            releaseBiomePublication(biome)
            publication.closed = true
            throw problem
        }
        return publication
    }

    @JvmStatic
    @Synchronized
    private fun completeChunkPublication(publication: ChunkPublication) {
        check(!publication.closed)
        publication.completed = true
        publication.biome.complete()
    }

    @JvmStatic
    @Synchronized
    private fun finishChunkPublication(publication: ChunkPublication) {
        if (publication.closed) return
        if (publication.biome.level !== levelIdentity || publication.biome.epoch != epoch) {
            publication.biome.close()
            publication.closed = true
            return
        }
        var invalidated = false
        try {
            invalidateChunkInputs(publication)
            invalidated = true
        } finally {
            try {
                publication.biome.close()
            } finally {
                if (publication.biome.level === levelIdentity && publication.biome.epoch == epoch) {
                    finishPublicationInput(chunkInputs, publication.column, publication.failureRevision,
                        publication.completed && invalidated, publication.biome.recoverInput)
                    for (input in publication.blockInputs) {
                        finishPublicationInput(blockStateInputs, input.node, input.state,
                            publication.completed && invalidated, publication.biome.recoverInput)
                        finishPublicationInput(blockSectionInputs, input.node, input.section,
                            publication.completed && invalidated, publication.biome.recoverInput)
                    }
                }
                publication.closed = true
            }
        }
    }

    private fun invalidateChunkInputs(publication: ChunkPublication) {
        val dependent = dependentSections(publication.inputs)
        val affected = (publication.inputs.asList() + dependent.asList()).distinct().toLongArray()
        D3D12RtGeometryPool.invalidateSections(affected) { revokeSections(publication.inputs, dependent) }
    }

    private fun beginPublicationInput(inputs: MutableMap<Long, PublicationInput>, column: Long): Long {
        val input = inputs.getOrPut(column, ::PublicationInput)
        input.active++
        return input.failureRevision
    }

    private fun finishPublicationInput(inputs: MutableMap<Long, PublicationInput>, column: Long,
                                       failureRevision: Long, completed: Boolean, recoverInput: Boolean) {
        val input = checkNotNull(inputs[column])
        if (!completed) {
            input.failureRevision++
            input.failed = true
        } else if (recoverInput && input.failureRevision == failureRevision) {
            input.failed = false
        }
        releasePublicationInput(inputs, column)
    }

    private fun releasePublicationInput(inputs: MutableMap<Long, PublicationInput>, column: Long) {
        val input = checkNotNull(inputs[column])
        check(input.active > 0)
        input.active--
        if (input.active == 0 && !input.failed) inputs.remove(column)
    }

    private fun hasUnavailableChunkInput(column: Long): Boolean = column in sectionArrayInputs ||
        hasUnavailablePublicationInput(chunkInputs, column)

    private fun hasUnavailableBlockInput(sectionNode: Long): Boolean =
        (blockStateInputs.isNotEmpty() || blockSectionInputs.isNotEmpty() || sectionArrayInputs.isNotEmpty() ||
            unavailableBlockStorage.isNotEmpty()) &&
            regionInputs(sectionNode).any { it in blockStateInputs || it in blockSectionInputs ||
                it in unavailableBlockStorage || ChunkPos.pack(SectionPos.x(it), SectionPos.z(it)) in sectionArrayInputs }

    private fun hasUnavailableBlockTintInput(column: Long): Boolean =
        hasUnavailableSectionArrayNeighbor(column) ||
            sequenceOf(blockStateInputs.keys, blockSectionInputs.keys, unavailableBlockStorage).flatten().any { node ->
            abs(SectionPos.x(node) - ChunkPos.getX(column)) <= 1 && abs(SectionPos.z(node) - ChunkPos.getZ(column)) <= 1
        }

    private fun hasUnavailableSectionArrayNeighbor(column: Long): Boolean = sectionArrayInputs.isNotEmpty() &&
        (-1..1).any { offsetX -> (-1..1).any { offsetZ ->
            ChunkPos.pack(ChunkPos.getX(column) + offsetX, ChunkPos.getZ(column) + offsetZ) in sectionArrayInputs
        } }

    @JvmStatic
    @Synchronized
    private fun publishSectionArray(chunk: LevelChunk, array: Array<LevelChunkSection>) {
        if (!D3D12RtSectionArrays.record(array) || chunk.level !== levelIdentity) return
        val position = chunk.pos
        val loaded = loadedChunks[SectionPos.asLong(position.x(), 0, position.z())]
            ?.takeIf { it.identity === chunk } ?: return
        sectionArrayInputs.add(ChunkPos.pack(position.x(), position.z()))
        val dependent = dependentSections(loaded.nodes)
        val affected = (loaded.nodes.asList() + dependent.asList()).distinct().toLongArray()
        D3D12RtGeometryPool.invalidateSections(affected) { revokeSections(loaded.nodes, dependent) }
        invalidateTintCaches(chunk.level as ClientLevel, position.x(), position.z())
    }

    class BlockPublication internal constructor(internal val level: ClientLevel, internal val epoch: Int,
                                              internal val nodes: LongArray, internal val revisions: LongArray,
                                              internal val revoked: LongArray, internal val palette: Boolean,
                                              internal val recoverInput: Boolean) : AutoCloseable {
        internal var completed = false
        internal var closed = false

        fun complete() = completeBlockPublication(this)

        override fun close() = finishBlockPublication(this)
    }

    @JvmStatic
    @Synchronized
    fun beginBlockPalettePublication(palette: PalettedContainer<*>, recoverInput: Boolean): BlockPublication? =
        beginBlockInputPublication(blockPaletteSources[palette], true, recoverInput)

    @JvmStatic
    @Synchronized
    fun beginBlockSectionPublication(section: LevelChunkSection, recoverInput: Boolean): BlockPublication? =
        beginBlockInputPublication(blockSectionSources[section], false, recoverInput)

    class PaletteStoragePublication internal constructor(internal val mutation: D3D12RtPaletteStorage.Mutation) : AutoCloseable {
        internal var completed = false
        internal var closed = false

        fun complete() = completePaletteStoragePublication(this)

        override fun close() = finishPaletteStoragePublication(this)
    }

    class IdMapPublication internal constructor(internal val mutation: D3D12RtIdMaps.Mutation) : AutoCloseable {
        internal var completed = false
        internal var closed = false

        fun complete() = completeIdMapPublication(this)

        override fun close() = finishIdMapPublication(this)
    }

    @JvmStatic
    @Synchronized
    fun beginIdMapPublication(owner: Any, recover: Boolean): IdMapPublication =
        IdMapPublication(D3D12RtIdMaps.begin(owner, recover))

    private fun idMapSources(mutation: D3D12RtIdMaps.Mutation): Pair<List<BlockSource>, List<BiomeSource>> =
        blockMappingSources[mutation.owner]?.filter { source -> isCurrentBlockSource(source) &&
            source.entries?.let { D3D12RtIdMaps.affected(mutation, it, source.storage) } == true }.orEmpty() to
            biomeMappingSources[mutation.owner]?.filter { source -> isCurrentBiomeSource(source) &&
                source.section.biomes === source.palette && source.entries?.let {
                    D3D12RtIdMaps.affected(mutation, it, source.storage) } == true }.orEmpty()

    private fun invalidateIdMap(mutation: D3D12RtIdMaps.Mutation) {
        val [blocks, biomes] = idMapSources(mutation)
        for (source in blocks) updateBlockDataAvailability(source)
        for (source in biomes) updateBiomeDataAvailability(source)
        invalidatePaletteSources(blocks, biomes)
    }

    private fun touchIdMap(owner: Any, id: Int? = null, full: Boolean = false): IdMapPublication {
        val publication = D3D12RtIdMaps.publication(owner)
        if (id != null) publication.mutation.ids.add(id)
        publication.mutation.full = publication.mutation.full || full
        invalidateIdMap(publication.mutation)
        return publication
    }

    @JvmStatic
    @Synchronized
    fun publishIdMapSlot(owner: Any, id: Int, value: Any?, operation: Supplier<Any?>): Any? {
        if (D3D12RtIdMaps.slot(owner, id) !== value) touchIdMap(owner, id)
        return operation.get()
    }

    @JvmStatic
    @Synchronized
    fun publishIdMapReverse(owner: Any, value: Any?, id: Int, operation: IntSupplier): Int {
        if (!D3D12RtIdMaps.sameReverse(owner, value, id)) D3D12RtIdMaps.publication(owner)
        return operation.asInt
    }

    @JvmStatic
    @Synchronized
    fun publishIdMapAppend(owner: Any, value: Any?, operation: Supplier<Boolean>): Boolean {
        if (value != null) touchIdMap(owner, D3D12RtIdMaps.size(owner)) else D3D12RtIdMaps.publication(owner)
        return operation.get()
    }

    @JvmStatic
    @Synchronized
    fun publishIdMapClear(owner: Any, operation: Runnable) {
        if (!D3D12RtIdMaps.empty(owner) || D3D12RtIdMaps.failed(owner)) touchIdMap(owner, full = true)
        operation.run()
    }

    @JvmStatic
    @Synchronized
    fun publishIdMapDefault(owner: Any, value: Any?, operation: Runnable) {
        if (!D3D12RtIdMaps.sameDefault(owner, value)) D3D12RtIdMaps.writing(owner, false).use { write ->
            touchIdMap(owner, full = true)
            operation.run()
            write.complete()
        } else operation.run()
    }

    @JvmStatic
    @Synchronized
    fun publishPaletteHolder(holder: Holder.Reference<*>, value: Any?, operation: Runnable) {
        if (!D3D12RtIdMaps.sameHolder(holder, value) || D3D12RtIdMaps.failed(holder)) D3D12RtIdMaps.writing(holder, true).use { write ->
            touchIdMap(holder, full = true)
            operation.run()
            write.complete()
        } else operation.run()
    }

    @JvmStatic
    @Synchronized
    fun beginIdMapRemoval(owner: Any) { touchIdMap(owner, full = true) }

    @JvmStatic
    @Synchronized
    private fun completeIdMapPublication(publication: IdMapPublication) {
        check(!publication.closed)
        publication.completed = true
    }

    @JvmStatic
    @Synchronized
    private fun finishIdMapPublication(publication: IdMapPublication) {
        if (publication.closed) return
        var invalidated = false
        try {
            if (!publication.completed) publication.mutation.full = true
            invalidateIdMap(publication.mutation)
            invalidated = true
        } finally {
            D3D12RtIdMaps.finish(publication.mutation, publication.completed && invalidated)
            val [blocks, biomes] = idMapSources(publication.mutation)
            for (source in blocks) updateBlockDataAvailability(source)
            for (source in biomes) updateBiomeDataAvailability(source)
            publication.closed = true
        }
    }

    class PaletteEntryPublication internal constructor(internal val mutation: D3D12RtPaletteEntries.Mutation,
                                                       internal val registry: D3D12RtIdMaps.Copy? = null) : AutoCloseable {
        internal var completed = false
        internal var closed = false

        fun complete() = completePaletteEntryPublication(this)

        override fun close() = finishPaletteEntryPublication(this)
    }

    @JvmStatic
    @Synchronized
    fun publishSinglePaletteEntry(entries: SingleValuePalette<*>, value: Any?, operation: Runnable) {
        if (D3D12RtPaletteEntries.sameSingleValue(entries, value)) operation.run()
        else beginPaletteEntryMutation(entries, false).use { publication ->
            operation.run()
            publication.complete()
        }
    }

    @JvmStatic
    @Synchronized
    fun beginPaletteEntryAppend(entries: Palette<*>): PaletteEntryPublication = beginPaletteEntryMutation(entries, false)

    @JvmStatic
    @Synchronized
    fun beginPaletteEntryRead(entries: Palette<*>): PaletteEntryPublication = beginPaletteEntryMutation(entries, true)

    @JvmStatic
    @Synchronized
    fun beginPaletteEntryDecode(entries: Palette<*>, registry: net.minecraft.core.IdMap<*>): PaletteEntryPublication =
        beginPaletteEntryMutation(entries, true, D3D12RtIdMaps.beginCopy(registry))

    private fun beginPaletteEntryMutation(entries: Palette<*>, recover: Boolean,
                                          registry: D3D12RtIdMaps.Copy? = null): PaletteEntryPublication {
        val publication = PaletteEntryPublication(D3D12RtPaletteEntries.begin(entries, recover), registry)
        updateEntryAvailability(entries)
        try {
            invalidatePaletteEntries(entries)
        } catch (problem: Throwable) {
            D3D12RtPaletteEntries.finish(publication.mutation, false)
            updateEntryAvailability(entries)
            publication.closed = true
            throw problem
        }
        return publication
    }

    @JvmStatic
    @Synchronized
    private fun completePaletteEntryPublication(publication: PaletteEntryPublication) {
        check(!publication.closed)
        publication.completed = publication.registry?.let(D3D12RtIdMaps::matches) != false
    }

    @JvmStatic
    @Synchronized
    private fun finishPaletteEntryPublication(publication: PaletteEntryPublication) {
        if (publication.closed) return
        var invalidated = false
        val entries = publication.mutation.entries
        try {
            invalidatePaletteEntries(entries)
            invalidated = true
        } finally {
            D3D12RtPaletteEntries.finish(publication.mutation, publication.completed && invalidated)
            updateEntryAvailability(entries)
            publication.closed = true
        }
    }

    @JvmStatic
    @Synchronized
    fun beginPaletteEntryCopy(entries: Palette<*>): D3D12RtPaletteEntries.Copy = D3D12RtPaletteEntries.beginCopy(entries)

    @JvmStatic
    @Synchronized
    fun finishPaletteEntryCopy(copy: D3D12RtPaletteEntries.Copy, result: Palette<*>) {
        D3D12RtPaletteEntries.finishCopy(copy, result)
        updateEntryAvailability(result)
        if (!D3D12RtPaletteEntries.available(result)) invalidatePaletteEntries(result)
    }

    @JvmStatic
    @Synchronized
    fun beginPaletteStorageWrite(storage: BitStorage, recoverInput: Boolean): PaletteStoragePublication {
        return beginPaletteStorageMutation(D3D12RtPaletteStorage.begin(storage, recoverInput))
    }

    @JvmStatic
    @Synchronized
    fun beginPaletteArrayWrite(array: LongArray, recoverInput: Boolean): PaletteStoragePublication =
        beginPaletteStorageMutation(D3D12RtPaletteStorage.beginArray(array, recoverInput))

    private fun mutationStorages(mutation: D3D12RtPaletteStorage.Mutation): List<BitStorage> =
        mutation.array?.let { paletteArraySources[it]?.toList().orEmpty() } ?: listOfNotNull(mutation.storage)

    private fun beginPaletteStorageMutation(mutation: D3D12RtPaletteStorage.Mutation): PaletteStoragePublication {
        val publication = PaletteStoragePublication(mutation)
        for (storage in mutationStorages(mutation)) updateStorageAvailability(storage)
        try {
            for (storage in mutationStorages(mutation)) invalidatePaletteStorage(storage)
        } catch (problem: Throwable) {
            D3D12RtPaletteStorage.finish(publication.mutation, false)
            for (storage in mutationStorages(mutation)) updateStorageAvailability(storage)
            publication.closed = true
            throw problem
        }
        return publication
    }

    @JvmStatic
    @Synchronized
    private fun completePaletteStoragePublication(publication: PaletteStoragePublication) {
        check(!publication.closed)
        publication.completed = true
    }

    @JvmStatic
    @Synchronized
    private fun finishPaletteStoragePublication(publication: PaletteStoragePublication) {
        if (publication.closed) return
        var invalidated = false
        try {
            for (storage in mutationStorages(publication.mutation)) invalidatePaletteStorage(storage)
            invalidated = true
        } finally {
            D3D12RtPaletteStorage.finish(publication.mutation, publication.completed && invalidated)
            for (storage in mutationStorages(publication.mutation)) updateStorageAvailability(storage)
            publication.closed = true
        }
    }

    @JvmStatic
    @Synchronized
    fun beginPaletteStorageCopy(storage: BitStorage): D3D12RtPaletteStorage.Copy = D3D12RtPaletteStorage.beginCopy(storage)

    @JvmStatic
    @Synchronized
    fun beginPalettePack(palette: PalettedContainer<*>): D3D12RtPaletteStorage.Pack? = D3D12RtPaletteStorage.beginPack(palette)

    @JvmStatic
    @Synchronized
    fun beginPaletteEncode(palette: PalettedContainer<*>, strategy: net.minecraft.world.level.chunk.Strategy<*>):
        D3D12RtPaletteStorage.Pack? = D3D12RtPaletteStorage.beginPack(palette, strategy)

    @JvmStatic
    @Synchronized
    fun beginPaletteUnpack(strategy: net.minecraft.world.level.chunk.Strategy<*>): D3D12RtIdMaps.Copy =
        D3D12RtIdMaps.beginCopy(D3D12RtIdMaps.strategyRegistry(strategy))

    @JvmStatic
    @Synchronized
    fun finishPalettePack(pack: D3D12RtPaletteStorage.Pack?, result: PalettedContainerRO.PackedData<*>) =
        D3D12RtPaletteStorage.finishPack(pack, result)

    @JvmStatic
    @Synchronized
    fun finishPaletteUnpack(packed: PalettedContainerRO.PackedData<*>, palette: PalettedContainer<*>) {
        D3D12RtPaletteStorage.finishUnpack(packed, palette)
        D3D12RtPaletteStorage.current(palette)?.let(::updateStorageAvailability)
        D3D12RtPaletteEntries.current(palette)?.let(::updateEntryAvailability)
    }

    @JvmStatic
    @Synchronized
    fun finishPaletteDecode(copy: D3D12RtIdMaps.Copy, packed: PalettedContainerRO.PackedData<*>, palette: PalettedContainer<*>) {
        finishPaletteUnpack(packed, palette)
        if (!D3D12RtIdMaps.matches(copy)) D3D12RtPaletteStorage.current(palette)?.let {
            D3D12RtPaletteStorage.fail(it)
            updateStorageAvailability(it)
            invalidatePaletteStorage(it)
        }
    }

    @JvmStatic
    @Synchronized
    fun beginPaletteDataCopy(data: Any, entries: Palette<*>, storage: BitStorage): D3D12RtPaletteStorage.DataCopy =
        D3D12RtPaletteStorage.DataCopy(if (D3D12RtPaletteStorage.dataStorage(data) === storage) null else
            D3D12RtPaletteStorage.beginCopy(storage), D3D12RtPaletteEntries.beginCopy(entries),
            D3D12RtPaletteEntries.dataEntries(data)?.let(D3D12RtIdMaps::registry)?.let(D3D12RtIdMaps::beginCopy), true)

    @JvmStatic
    @Synchronized
    fun finishPaletteDataCopy(copy: D3D12RtPaletteStorage.DataCopy, data: Any) {
        if (copy.storage != null) D3D12RtPaletteStorage.dataStorage(data)?.let {
            D3D12RtPaletteStorage.finishCopyInto(copy.storage, it)
            updateStorageAvailability(it)
        }
        D3D12RtPaletteEntries.dataEntries(data)?.let {
            D3D12RtPaletteEntries.finishCopy(copy.entries, it)
            updateEntryAvailability(it)
            if (!D3D12RtPaletteEntries.available(it)) invalidatePaletteEntries(it)
        }
        if (copy.registry?.let(D3D12RtIdMaps::matches) == false ||
            copy.materialized && D3D12RtPaletteEntries.dataEntries(data)?.javaClass ==
                net.minecraft.world.level.chunk.GlobalPalette::class.java &&
                !D3D12RtPaletteEntries.matches(copy.entries)) D3D12RtPaletteStorage.dataStorage(data)?.let {
            D3D12RtPaletteStorage.fail(it)
            updateStorageAvailability(it)
            invalidatePaletteStorage(it)
        }
    }

    @JvmStatic
    @Synchronized
    fun beginPaletteDataSnapshot(data: Any): D3D12RtPaletteStorage.DataCopy = D3D12RtPaletteStorage.DataCopy(
        D3D12RtPaletteStorage.beginCopy(checkNotNull(D3D12RtPaletteStorage.dataStorage(data))),
        D3D12RtPaletteEntries.beginCopy(checkNotNull(D3D12RtPaletteEntries.dataEntries(data))))

    @JvmStatic
    @Synchronized
    fun finishPaletteStorageCopy(copy: D3D12RtPaletteStorage.Copy, result: BitStorage) {
        D3D12RtPaletteStorage.finishCopy(copy, result)
        updateStorageAvailability(result)
    }

    @JvmStatic
    @Synchronized
    fun publishPaletteStorage(palette: PalettedContainer<*>, operation: Runnable) {
        val blocks = blockPaletteSources[palette]?.filter(::isCurrentBlockSource).orEmpty()
        val biomes = biomePaletteSources[palette]?.filter { isCurrentBiomeSource(it) && it.section.biomes === palette }.orEmpty()
        invalidatePaletteSources(blocks, biomes)
        try {
            operation.run()
        } catch (problem: Throwable) {
            D3D12RtPaletteStorage.current(palette)?.let(D3D12RtPaletteStorage::fail)
            throw problem
        } finally {
            for (source in blocks) if (isCurrentBlockSource(source)) refreshBlockStorageSource(source)
            for (source in biomes) if (isCurrentBiomeSource(source) && source.section.biomes === palette) refreshBiomeStorageSource(source)
        }
    }

    private fun isCurrentBlockSource(source: BlockSource): Boolean =
        loadedChunks[SectionPos.getZeroNode(source.node)]?.identity === source.chunk &&
            D3D12RtSectionArrays.section(source.chunk, source.index) === source.section && source.section.states === source.palette

    private fun invalidatePaletteStorage(storage: BitStorage) {
        val blocks = blockStorageSources[storage]?.filter { isCurrentBlockSource(it) &&
            D3D12RtPaletteStorage.current(it.palette) === storage }.orEmpty()
        val biomes = biomeStorageSources[storage]?.filter { isCurrentBiomeSource(it) &&
            it.section.biomes === it.palette && it.palette?.let(D3D12RtPaletteStorage::current) === storage }.orEmpty()
        invalidatePaletteSources(blocks, biomes)
    }

    private fun invalidatePaletteEntries(entries: Palette<*>) {
        val blocks = blockEntrySources[entries]?.filter { isCurrentBlockSource(it) &&
            D3D12RtPaletteEntries.current(it.palette) === entries }.orEmpty()
        val biomes = biomeEntrySources[entries]?.filter { isCurrentBiomeSource(it) && it.section.biomes === it.palette &&
            it.palette?.let(D3D12RtPaletteEntries::current) === entries }.orEmpty()
        invalidatePaletteSources(blocks, biomes)
    }

    private fun invalidatePaletteSources(blocks: List<BlockSource>, biomes: List<BiomeSource>) {
        val level = (levelIdentity as? ClientLevel)?.takeIf { it.javaClass == ClientLevel::class.java } ?: return
        val nodes = blocks.filter { (blockStateInputs[it.node]?.active ?: 0) == 0 &&
            (blockSectionInputs[it.node]?.active ?: 0) == 0 &&
            (chunkInputs[ChunkPos.pack(SectionPos.x(it.node), SectionPos.z(it.node))]?.active ?: 0) == 0 }
            .map { it.node }.distinct().toLongArray()
        if (nodes.isNotEmpty()) {
            val dependent = dependentSections(nodes)
            val affected = (nodes.asList() + dependent.asList()).distinct().toLongArray()
            D3D12RtGeometryPool.invalidateSections(affected) { revokeSections(nodes, dependent) }
        }
        val biomeNodes = biomes.filter { (biomePaletteInputs[it.node]?.active ?: 0) == 0 &&
            (biomeSectionInputs[it.node]?.active ?: 0) == 0 &&
            (biomeInputs[ChunkPos.pack(SectionPos.x(it.node), SectionPos.z(it.node))]?.active ?: 0) == 0 }.map { it.node }
        for (column in (nodes.asList() + biomeNodes).map(SectionPos::getZeroNode).distinct()) {
            invalidateTintCaches(level, SectionPos.x(column), SectionPos.z(column))
        }
    }

    private fun updateStorageAvailability(storage: BitStorage) {
        blockStorageSources[storage]?.forEach(::updateBlockDataAvailability)
        biomeStorageSources[storage]?.forEach(::updateBiomeDataAvailability)
    }

    private fun updateEntryAvailability(entries: Palette<*>) {
        blockEntrySources[entries]?.forEach(::updateBlockDataAvailability)
        biomeEntrySources[entries]?.forEach(::updateBiomeDataAvailability)
    }

    private fun updateBlockDataAvailability(source: BlockSource) {
        refreshBlockMappingSources(source)
        if (D3D12RtPaletteStorage.available(source.storage) && D3D12RtPaletteEntries.available(source.entries) &&
            D3D12RtIdMaps.available(checkNotNull(source.entries), checkNotNull(source.storage), false))
            unavailableBlockStorage.remove(source.node) else unavailableBlockStorage.add(source.node)
    }

    private fun updateBiomeDataAvailability(source: BiomeSource) {
        refreshBiomeMappingSources(source)
        if (D3D12RtPaletteStorage.available(source.storage) && D3D12RtPaletteEntries.available(source.entries) &&
            D3D12RtIdMaps.available(checkNotNull(source.entries), checkNotNull(source.storage), true))
            unavailableBiomeStorage.remove(source.node) else unavailableBiomeStorage.add(source.node)
    }

    private fun refreshBlockMappingSources(source: BlockSource) {
        val owners = D3D12RtIdMaps.dependencies(source.entries, source.storage, false)
        for (owner in source.mappingOwners) if (owners.none { it === owner }) blockMappingSources[owner]?.let {
            it.remove(source)
            if (it.isEmpty()) blockMappingSources.remove(owner)
        }
        for (owner in owners) blockMappingSources.getOrPut(owner, ::linkedSetOf).add(source)
        source.mappingOwners = owners
    }

    private fun refreshBiomeMappingSources(source: BiomeSource) {
        val owners = D3D12RtIdMaps.dependencies(source.entries, source.storage, true)
        for (owner in source.mappingOwners) if (owners.none { it === owner }) biomeMappingSources[owner]?.let {
            it.remove(source)
            if (it.isEmpty()) biomeMappingSources.remove(owner)
        }
        for (owner in owners) biomeMappingSources.getOrPut(owner, ::linkedSetOf).add(source)
        source.mappingOwners = owners
    }

    @JvmStatic
    @Synchronized
    private fun publishPaletteArray(array: LongArray, use: D3D12RtPaletteArrays.Use) {
        if (!D3D12RtPaletteArrays.record(array, use)) return
        for (storage in paletteArraySources[array]?.toList().orEmpty()) {
            updateStorageAvailability(storage)
            invalidatePaletteStorage(storage)
        }
    }

    private fun linkPaletteArray(storage: BitStorage) {
        D3D12RtPaletteArrays.current(storage)?.let { paletteArraySources.getOrPut(it, ::linkedSetOf).add(storage) }
    }

    private fun unlinkPaletteArray(storage: BitStorage) {
        if (!blockStorageSources[storage].isNullOrEmpty() || !biomeStorageSources[storage].isNullOrEmpty()) return
        D3D12RtPaletteArrays.current(storage)?.let { array ->
            paletteArraySources[array]?.let { sources ->
                sources.remove(storage)
                if (sources.isEmpty()) paletteArraySources.remove(array)
            }
        }
    }

    private fun beginBlockInputPublication(sources: Set<BlockSource>?, palette: Boolean, recoverInput: Boolean): BlockPublication? {
        val level = levelIdentity as? ClientLevel ?: return null
        if (level.javaClass != ClientLevel::class.java || level.chunkSource.javaClass != ClientChunkCache::class.java ||
            sources.isNullOrEmpty()) return null
        val nodes = sources.filter { source ->
            loadedChunks[SectionPos.getZeroNode(source.node)]?.identity === source.chunk &&
                D3D12RtSectionArrays.section(source.chunk, source.index) === source.section && source.section.states === source.palette
        }.map { it.node }.distinct().toLongArray()
        if (nodes.isEmpty()) return null
        val revoked = nodes.filter { node ->
            (blockStateInputs[node]?.active ?: 0) == 0 && (blockSectionInputs[node]?.active ?: 0) == 0 &&
                (chunkInputs[ChunkPos.pack(SectionPos.x(node), SectionPos.z(node))]?.active ?: 0) == 0
        }.toLongArray()
        val inputs = if (palette) blockStateInputs else blockSectionInputs
        val revisions = LongArray(nodes.size) { beginPublicationInput(inputs, nodes[it]) }
        val publication = BlockPublication(level, epoch, nodes, revisions, revoked, palette, recoverInput)
        try {
            invalidateBlockInputs(publication)
        } catch (problem: Throwable) {
            for (node in nodes) releasePublicationInput(inputs, node)
            publication.closed = true
            throw problem
        }
        return publication
    }

    private fun invalidateBlockInputs(publication: BlockPublication) {
        if (publication.revoked.isEmpty()) return
        val dependent = dependentSections(publication.revoked)
        val affected = (publication.revoked.asList() + dependent.asList()).distinct().toLongArray()
        D3D12RtGeometryPool.invalidateSections(affected) { revokeSections(publication.revoked, dependent) }
        for (column in publication.revoked.map { SectionPos.getZeroNode(it) }.distinct()) {
            invalidateTintCaches(publication.level, SectionPos.x(column), SectionPos.z(column))
        }
    }

    @JvmStatic
    @Synchronized
    private fun completeBlockPublication(publication: BlockPublication) {
        check(!publication.closed)
        publication.completed = true
    }

    @JvmStatic
    @Synchronized
    private fun finishBlockPublication(publication: BlockPublication) {
        if (publication.closed) return
        if (publication.level !== levelIdentity || publication.epoch != epoch) {
            publication.closed = true
            return
        }
        var invalidated = false
        try {
            invalidateBlockInputs(publication)
            invalidated = true
        } finally {
            if (publication.level === levelIdentity && publication.epoch == epoch) {
                val inputs = if (publication.palette) blockStateInputs else blockSectionInputs
                for ([index, node] in publication.nodes.withIndex()) finishPublicationInput(inputs, node,
                    publication.revisions[index], publication.completed && invalidated, publication.recoverInput)
            }
            publication.closed = true
        }
    }

    private fun hasUnavailablePublicationInput(inputs: Map<Long, PublicationInput>, column: Long): Boolean {
        if (inputs.isEmpty()) return false
        for (deltaZ in -1..1) {
            for (deltaX in -1..1) {
                val source = ChunkPos.pack(ChunkPos.getX(column) + deltaX, ChunkPos.getZ(column) + deltaZ)
                if (source in inputs) return true
            }
        }
        return false
    }

    private fun activeChunkSource(source: ClientChunkCache, storage: Any = chunkStorage.get(source)): ClientLevel? {
        val level = levelIdentity as? ClientLevel ?: return null
        return level.takeIf { it.javaClass == ClientLevel::class.java && source.javaClass == ClientChunkCache::class.java &&
            it.chunkSource === source && chunkStorage.get(source) === storage && storage.javaClass == chunkStorage.type }
    }

    private fun visibleChunks(storage: Any, centerX: Int = chunkCenterX.getInt(storage),
                              centerZ: Int = chunkCenterZ.getInt(storage)): Map<Long, LevelChunk> = buildMap {
        val radius = chunkRadius.getInt(storage)
        val slots = chunkSlots.get(storage) as AtomicReferenceArray<*>
        for (index in 0..<slots.length()) {
            val chunk = slots.get(index) as? LevelChunk ?: continue
            val position = chunk.pos
            if (abs(position.x() - centerX) <= radius && abs(position.z() - centerZ) <= radius) put(position.pack(), chunk)
        }
    }

    private fun <Result> publishChunkLookup(level: ClientLevel, source: ClientChunkCache,
                                           before: Map<Long, LevelChunk>, after: Map<Long, LevelChunk>,
                                           publish: () -> Result): Result {
        val columns = (before.keys + after.keys).filter { before[it] !== after[it] }
        if (columns.isEmpty()) return publish()
        val publications = mutableListOf<ChunkPublication>()
        var completed = false
        return AutoCloseable {
            var failure: Throwable? = null
            for (publication in publications.asReversed()) {
                try {
                    if (completed) publication.complete()
                    publication.close()
                } catch (problem: Throwable) {
                    if (failure == null) failure = problem else failure.addSuppressed(problem)
                }
            }
            failure?.let { throw it }
        }.use {
            for (column in columns) publications.add(checkNotNull(beginChunkInputPublication(level, source,
                ChunkPos.getX(column), ChunkPos.getZ(column), false)))
            val result = publish()
            if (activeChunkSource(source) === level) {
                for (column in columns) {
                    before[column]?.let { observeChunkUnloaded(ChunkPos.getX(column), ChunkPos.getZ(column), level, it) }
                    after[column]?.let { observeChunkLoaded(ChunkPos.getX(column), ChunkPos.getZ(column),
                        level.minSectionY, level.sectionsCount, level, it) }
                }
            }
            completed = true
            result
        }
    }

    private fun visibleSlot(storage: Any, chunk: LevelChunk?): Map<Long, LevelChunk> =
        if (chunk != null && chunkInRange.invoke(storage, chunk.pos.x(), chunk.pos.z()) == true)
            mapOf(chunk.pos.pack() to chunk) else emptyMap()

    @JvmStatic
    @Synchronized
    fun publishChunkDrop(source: ClientChunkCache, storage: Any, slots: AtomicReferenceArray<LevelChunk?>,
                         index: Int, expected: LevelChunk?, next: LevelChunk?, publish: BooleanSupplier): Boolean {
        val level = activeChunkSource(source, storage)
        if (level == null || chunkSlots.get(storage) !== slots || slots.get(index) !== expected) return publish.asBoolean
        return publishChunkLookup(level, source, visibleSlot(storage, expected), visibleSlot(storage, next)) { publish.asBoolean }
    }

    @JvmStatic
    @Synchronized
    fun publishChunkReplacement(source: ClientChunkCache, storage: Any, slots: AtomicReferenceArray<LevelChunk?>,
                                index: Int, next: LevelChunk?, publish: Supplier<LevelChunk?>): LevelChunk? {
        val level = activeChunkSource(source, storage)
        if (level == null || chunkSlots.get(storage) !== slots) return publish.get()
        return publishChunkLookup(level, source, visibleSlot(storage, slots.get(index)), visibleSlot(storage, next)) { publish.get() }
    }

    @JvmStatic
    @Synchronized
    fun publishChunkViewCenter(source: ClientChunkCache, chunkX: Int, chunkZ: Int, publish: Runnable) {
        val storage = chunkStorage.get(source)
        val level = activeChunkSource(source, storage)
        if (level == null || chunkCenterX.getInt(storage) == chunkX && chunkCenterZ.getInt(storage) == chunkZ) {
            publish.run()
            return
        }
        publishChunkLookup(level, source, visibleChunks(storage), visibleChunks(storage, chunkX, chunkZ)) { publish.run() }
    }

    @JvmStatic
    @Synchronized
    fun publishChunkStorage(source: ClientChunkCache, next: Any, publish: Runnable) {
        val storage = chunkStorage.get(source)
        val level = activeChunkSource(source, storage)
        if (level == null || storage === next || next.javaClass != chunkStorage.type) {
            publish.run()
            return
        }
        publishChunkLookup(level, source, visibleChunks(storage), visibleChunks(next)) { publish.run() }
    }

    @JvmStatic
    @Synchronized
    fun publishTintCache(cache: BlockTintCache, column: Long?, publish: Runnable) {
        val invalidation = D3D12RtTintInputs.beginInvalidation(cache, column)
        try {
            revokeTintCacheReaders(cache, column, publish)
        } finally {
            D3D12RtTintInputs.finishInvalidation(invalidation)
        }
    }

    private fun revokeTintCacheReaders(cache: BlockTintCache, column: Long?, publish: Runnable) {
        val readers = tintReaders[cache]
        if (cache.javaClass != BlockTintCache::class.java || readers == null) {
            publish.run()
            return
        }
        val candidates = if (column == null) readers.values.flatMap { it } else
            buildSet {
                for (deltaZ in -1..1) {
                    for (deltaX in -1..1) addAll(readers[ChunkPos.pack(ChunkPos.getX(column) + deltaX,
                        ChunkPos.getZ(column) + deltaZ)].orEmpty())
                }
            }
        val affected = candidates.filter { compileScopes[it.sectionNode] === it }.map { it.sectionNode }.distinct().toLongArray()
        revokeCompiledInputs(affected, publish)
    }

    @JvmStatic
    @Synchronized
    fun ownTintCacheSource(level: ClientLevel, cache: BlockTintCache,
                           source: java.util.function.ToIntFunction<BlockPos>?, resolver: ColorResolver?) {
        D3D12RtTintInputs.ownSource(level, cache, source, resolver)
    }

    @JvmStatic
    fun recordTintCacheLookup(level: ClientLevel, cache: BlockTintCache, resolver: ColorResolver?) {
        if (D3D12RtModelSupport.currentScope() == null && !D3D12RtTintInputs.hasRead()) return
        synchronized(D3D12SectionGeometry::class.java) {
            if (level !== levelIdentity || resolver == null ||
                !D3D12RtTintInputs.sourceCurrent(cache, level, resolver)) rejectBiomeInput()
        }
    }

    @JvmStatic
    @Synchronized
    fun beginTintCacheRead(cache: BlockTintCache, column: Long, index: Int): D3D12RtTintInputs.Read =
        D3D12RtTintInputs.beginRead(cache, column, index, levelIdentity, epoch).also { read ->
            read.scope?.let { recordTintRead(it, cache, column) }
            if (!read.contentAvailable) D3D12RtTintInputs.markContentUnknown().forEach(::rejectTintCacheRead)
        }

    private fun rejectTintCacheRead(read: D3D12RtTintInputs.Read?) {
        val scope = read?.scope ?: return
        if ((!read.available || !read.contentAvailable) && compileScopes[scope.sectionNode] === scope)
            revokeCompiledInputs(longArrayOf(scope.sectionNode), Runnable {})
    }

    @JvmStatic
    @Synchronized
    fun recordBiomeFallback() {
        rejectBiomeInput()
    }

    @JvmStatic
    fun recordBiomeManagerRead(manager: BiomeManager, source: BiomeResolver?) {
        if (D3D12RtModelSupport.currentScope() == null && !D3D12RtTintInputs.hasRead()) return
        synchronized(D3D12SectionGeometry::class.java) {
            if (manager.javaClass != BiomeManager::class.java || source !== levelIdentity ||
                source?.javaClass != ClientLevel::class.java) rejectBiomeInput()
        }
    }

    @JvmStatic
    fun recordTintCalculatorRead(level: ClientLevel, resolver: ColorResolver?) {
        if (D3D12RtModelSupport.currentScope() == null && !D3D12RtTintInputs.hasRead()) return
        synchronized(D3D12SectionGeometry::class.java) {
            if (level !== levelIdentity || level.javaClass != ClientLevel::class.java ||
                !D3D12RtTintInputs.fixedResolver(resolver))
                rejectBiomeInput()
        }
    }

    private fun rejectBiomeInput() {
        D3D12RtTintInputs.markContentUnknown().forEach(::rejectTintCacheRead)
        D3D12RtModelSupport.currentScope()?.let { scope ->
            if (compileScopes[scope.sectionNode] === scope) revokeCompiledInputs(longArrayOf(scope.sectionNode), Runnable {})
        }
    }

    @JvmStatic
    @Synchronized
    fun readColorTable(owner: Class<*>, operation: java.util.function.Supplier<IntArray?>): IntArray? {
        val array = operation.get()
        val index = D3D12RtColorTables.index(owner)
        val available = D3D12RtColorTables.available(index, array)
        D3D12RtTintInputs.readColorTable(index, available).forEach(::rejectTintCacheRead)
        D3D12RtModelSupport.currentScope()?.let { scope ->
            if (compileScopes[scope.sectionNode] === scope) {
                if (available) {
                    scope.colorTables = scope.colorTables or (1 shl index)
                    colorTableReaders[index].add(scope)
                } else revokeCompiledInputs(longArrayOf(scope.sectionNode), Runnable {})
            }
        }
        return array
    }

    @JvmStatic
    @Synchronized
    fun publishColorTable(owner: Class<*>, array: IntArray?, operation: Runnable) {
        val publication = D3D12RtColorTables.begin(owner, array)
        val caches = D3D12RtTintInputs.colorTableCaches(publication.index)
        val invalidations = caches.map { D3D12RtTintInputs.beginInvalidation(it, null) }
        var completed = false
        try {
            val affected = buildSet {
                addAll(colorTableReaders[publication.index])
                for (cache in caches) for (readers in tintReaders[cache]?.values.orEmpty()) addAll(readers)
            }.filter { compileScopes[it.sectionNode] === it }.map { it.sectionNode }.distinct().toLongArray()
            revokeCompiledInputs(affected, operation)
            completed = true
        } finally {
            var sealed = false
            try {
                val known = IdentityHashMap<BlockTintCache, Boolean>().apply { caches.forEach { put(it, true) } }
                for (cache in D3D12RtTintInputs.colorTableCaches(publication.index)) if (cache !in known) {
                    val late = D3D12RtTintInputs.beginInvalidation(cache, null)
                    try { revokeTintCacheReaders(cache, null, Runnable {}) }
                    finally { D3D12RtTintInputs.finishInvalidation(late) }
                }
                sealed = true
            } finally {
                invalidations.forEach(D3D12RtTintInputs::finishInvalidation)
                D3D12RtColorTables.finish(publication, array, completed && sealed)
            }
        }
    }

    @JvmStatic
    @Synchronized
    fun readTintCacheSlot(array: IntArray, index: Int, operation: IntSupplier): Int {
        D3D12RtTintInputs.prepareRead(array, index, levelIdentity, epoch)
        val value = operation.asInt
        val read = D3D12RtTintInputs.readSlot(array, index, value, levelIdentity, epoch)
        if (read?.contentAvailable == false) D3D12RtTintInputs.markContentUnknown().forEach(::rejectTintCacheRead)
        else rejectTintCacheRead(read)
        return value
    }

    @JvmStatic
    @Synchronized
    fun writeTintCacheSlot(array: IntArray, index: Int, value: Int, operation: Runnable) {
        val read = D3D12RtTintInputs.beforeWrite(array, index)
        val changed = array[index] != -1 && array[index] != value
        if (changed && read != null) revokeTintCacheReaders(read.cache, read.columnKey, Runnable {})
        try {
            operation.run()
            D3D12RtTintInputs.afterWrite(read, array, index, levelIdentity, epoch)
        } catch (failure: Throwable) {
            D3D12RtTintInputs.failedWrite(read)
            D3D12RtTintInputs.discardUnknown(array, index, levelIdentity, epoch)
            throw failure
        } finally {
            rejectTintCacheRead(read)
        }
    }

    @JvmStatic
    @Synchronized
    fun finishTintCacheRead(read: D3D12RtTintInputs.Read) {
        if (!read.completed || !read.consumed || !D3D12RtTintInputs.current(read, levelIdentity, epoch)) read.available = false
        if (!read.available) read.layer?.let { D3D12RtTintInputs.discardUnknown(it, read.index, levelIdentity, epoch) }
        rejectTintCacheRead(read)
        D3D12RtBlockTintInputs.record(read, levelIdentity, epoch)
    }

    private fun removeCompileScope(sectionNode: Long) {
        val scope = compileScopes.remove(sectionNode) ?: return
        for (block in scope.blockExtensions.keys) blockExtensionReaders[block]?.let { readers ->
            readers.remove(scope)
            if (readers.isEmpty()) blockExtensionReaders.remove(block)
        }
        scope.blockExtensions.clear()
        for (index in colorTableReaders.indices) if (scope.colorTables and (1 shl index) != 0) colorTableReaders[index].remove(scope)
        scope.colorTables = 0
        for (owner in scope.biomeInfoOwners.keys) biomeInfoReaders[owner]?.let { readers ->
            readers.remove(scope)
            if (readers.isEmpty()) biomeInfoReaders.remove(owner)
        }
        scope.biomeInfoOwners.clear()
        for (node in scope.biomeNodes) biomeReaders[node]?.let { readers ->
            readers.remove(scope)
            if (readers.isEmpty()) biomeReaders.remove(node)
        }
        scope.biomeNodes.clear()
        for ([storage, node] in scope.lightInputs?.rawNodes.orEmpty()) {
            val readers = lightQueueReaders[storage] ?: continue
            readers[node]?.let { owners ->
                owners.remove(sectionNode)
                if (owners.isEmpty()) readers.remove(node)
            }
            if (readers.isEmpty()) lightQueueReaders.remove(storage)
        }
        for (layer in scope.lightInputs?.layers.orEmpty()) lightLayerReaders[layer]?.let { readers ->
            readers.remove(sectionNode)
            if (readers.isEmpty()) {
                lightLayerReaders.remove(layer)
                unlinkLightArray(layer)
            }
        }
        for (column in scope.lightColumns) {
            lightReaders[column]?.let { readers ->
                readers.remove(sectionNode)
                if (readers.isEmpty()) lightReaders.remove(column)
            }
        }
        scope.lightColumns.clear()
        for ([cache, columns] in scope.tintInputs) {
            val readers = tintReaders[cache] ?: continue
            for (column in columns) {
                readers[column]?.let { scopes ->
                    scopes.remove(scope)
                    if (scopes.isEmpty()) readers.remove(column)
                }
            }
            if (readers.isEmpty()) tintReaders.remove(cache)
        }
        scope.tintInputs.clear()
    }

    private fun revokeCompiledInputs(affected: LongArray, publish: Runnable) {
        if (affected.isEmpty()) {
            publish.run()
            return
        }
        D3D12RtGeometryPool.invalidateSections(affected) {
            contentRevision++
            for (node in affected) {
                removeCompileScope(node)
                sections.remove(node)
            }
            val retired = affected.toSet()
            pendingUploads.entries.removeAll { it.value.sectionNode in retired }
            publish.run()
        }
    }

    @JvmStatic
    @Synchronized
    fun publishLightMap(storage: LayerLightSectionStorage<*>, next: DataLayerStorageMap<*>, publish: Runnable) {
        val publication = D3D12RtLightInputs.publication(levelIdentity, storage, next)
        if (publication == null) {
            publish.run()
            return
        }
        val candidates = if (publication.allColumns) compileScopes.keys.toList() else
            publication.columns.flatMap { lightReaders[it].orEmpty() }.distinct()
        val affected = candidates.filter { compileScopes[it]?.let { scope ->
            scope.lightInputs?.affected(publication) ?: true
        } == true }.toLongArray()
        val publicationWorld = levelIdentity
        val publicationEpoch = epoch
        try {
            revokeCompiledInputs(affected, publish)
        } finally {
            if (publicationWorld === levelIdentity && publicationEpoch == epoch) D3D12RtLightInputs.finishPublication(publication)
        }
    }

    class LightQueueMutation internal constructor(internal val mutation: D3D12RtLightInputs.QueueMutation) : AutoCloseable {
        internal var completed = false
        internal var closed = false
        fun complete() = completeLightQueueMutation(this)
        override fun close() = finishLightQueueMutation(this)
    }

    @JvmStatic
    @Synchronized
    fun ownLightQueue(storage: LayerLightSectionStorage<*>, queue: Long2ObjectMap<DataLayer>) =
        D3D12RtLightInputs.ownQueue(storage, queue)

    @JvmStatic
    @Synchronized
    fun beginLightQueueMutation(storage: LayerLightSectionStorage<*>, node: Long): LightQueueMutation? {
        val mutation = D3D12RtLightInputs.beginQueueMutation(storage, node) ?: return null
        val publication = LightQueueMutation(mutation)
        try {
            revokeCompiledInputs(lightQueueReaders[storage]?.get(node).orEmpty().toLongArray()) {}
        } catch (problem: Throwable) {
            D3D12RtLightInputs.finishQueueMutation(mutation, false)
            publication.closed = true
            throw problem
        }
        return publication
    }

    @JvmStatic
    @Synchronized
    private fun completeLightQueueMutation(publication: LightQueueMutation) {
        check(!publication.closed)
        publication.completed = true
    }

    @JvmStatic
    @Synchronized
    private fun finishLightQueueMutation(publication: LightQueueMutation) {
        if (publication.closed) return
        publication.closed = true
        D3D12RtLightInputs.finishQueueMutation(publication.mutation, publication.completed)
    }

    class LightMapMutation internal constructor(internal val mutation: D3D12RtLightInputs.MapMutation,
                                               internal val recoverInput: Boolean) : AutoCloseable {
        internal var completed = false
        internal var closed = false
        fun complete() = completeLightMapMutation(this)
        override fun close() = finishLightMapMutation(this)
    }

    class LightMapCopy internal constructor(internal val copy: D3D12RtLightInputs.MapCopy)

    @JvmStatic
    @Synchronized
    fun beginLightMapMutation(map: DataLayerStorageMap<*>, node: Long, recoverInput: Boolean): LightMapMutation {
        val mutation = D3D12RtLightInputs.beginMapMutation(levelIdentity, map, node)
        return beginLightMapMutation(mutation, recoverInput)
    }

    @JvmStatic
    @Synchronized
    fun ownLightMap(map: DataLayerStorageMap<*>, layers: Long2ObjectOpenHashMap<DataLayer>, tops: Long2IntOpenHashMap?) =
        D3D12RtLightInputs.ownMap(map, layers, tops)

    @JvmStatic
    @Synchronized
    fun beginLightSkyMetadata(storage: SkyLightSectionStorage, node: Long): LightMapMutation =
        beginLightMapMutation(D3D12RtLightInputs.beginSkyMetadata(levelIdentity, storage, node), false)

    private fun beginLightMapMutation(mutation: D3D12RtLightInputs.MapMutation, recoverInput: Boolean): LightMapMutation {
        val publication = LightMapMutation(mutation, recoverInput)
        try {
            val candidates = if (mutation.metadata) compileScopes.keys else lightReaders[SectionPos.getZeroNode(mutation.node)].orEmpty()
            val affected = candidates.filter { owner ->
                compileScopes[owner]?.lightInputs?.affected(mutation) == true
            }.toLongArray()
            revokeCompiledInputs(affected) {}
        } catch (problem: Throwable) {
            D3D12RtLightInputs.finishMapMutation(levelIdentity, mutation, false, recoverInput)
            publication.closed = true
            throw problem
        }
        return publication
    }

    @JvmStatic
    @Synchronized
    private fun completeLightMapMutation(publication: LightMapMutation) {
        check(!publication.closed)
        publication.completed = true
    }

    @JvmStatic
    @Synchronized
    private fun finishLightMapMutation(publication: LightMapMutation) {
        if (publication.closed) return
        publication.closed = true
        D3D12RtLightInputs.finishMapMutation(levelIdentity, publication.mutation, publication.completed, publication.recoverInput)
    }

    @JvmStatic
    @Synchronized
    fun beginLightMapCopy(map: DataLayerStorageMap<*>): LightMapCopy = LightMapCopy(D3D12RtLightInputs.beginMapCopy(map))

    @JvmStatic
    @Synchronized
    fun finishLightMapCopy(copy: LightMapCopy, result: DataLayerStorageMap<*>) = D3D12RtLightInputs.finishMapCopy(copy.copy, result)

    class LightLayerPublication internal constructor(internal val world: Any?, internal val epoch: Int,
                                                    internal val layer: DataLayer, internal val input: D3D12RtLightInputs.Input,
                                                    internal val failureRevision: Long, internal val recoverInput: Boolean) : AutoCloseable {
        internal var completed = false
        internal var closed = false
        fun complete() = completeLightLayerPublication(this)
        override fun close() = finishLightLayerPublication(this)
    }

    class LightLayerCopy internal constructor(internal val world: Any?, internal val epoch: Int, internal val layer: DataLayer,
                                             internal val input: D3D12RtLightInputs.Input,
                                             internal val version: Long, internal val available: Boolean)

    @JvmStatic
    @Synchronized
    fun beginLightLayerCopy(layer: DataLayer): LightLayerCopy? =
        layer.takeIf { it.javaClass == DataLayer::class.java }?.let {
            val input = D3D12RtLightInputs.copyInput(layer)
            LightLayerCopy(levelIdentity, epoch, layer, input, input.version, input.available && D3D12RtLightArrays.available(layer))
        }

    @JvmStatic
    @Synchronized
    fun finishLightLayerCopy(copy: LightLayerCopy?, result: DataLayer) {
        if (copy == null) return
        D3D12RtLightInputs.copiedInput(result, copy.available && copy.input.available && copy.input.version == copy.version &&
            D3D12RtLightArrays.available(copy.layer))
    }

    private fun linkLightArray(layer: DataLayer, bytes: ByteArray) {
        if (lightLayerArrays[layer] === bytes) return
        unlinkLightArray(layer)
        lightLayerArrays[layer] = bytes
        lightArrayReaders.getOrPut(bytes, ::linkedSetOf).add(layer)
    }

    private fun unlinkLightArray(layer: DataLayer) {
        val bytes = lightLayerArrays.remove(layer) ?: return
        lightArrayReaders[bytes]?.let { layers ->
            layers.remove(layer)
            if (layers.isEmpty()) lightArrayReaders.remove(bytes)
        }
    }

    @JvmStatic
    @Synchronized
    private fun publishLightArray(layer: DataLayer, bytes: ByteArray, use: D3D12RtLightArrays.Use) {
        if (layer in lightLayerReaders && D3D12RtLightArrays.current(layer) === bytes) linkLightArray(layer, bytes)
        if (!D3D12RtLightArrays.record(bytes, use)) return
        val layers = lightArrayReaders[bytes].orEmpty().toList()
        val affected = layers.flatMap { lightLayerReaders[it].orEmpty() }.distinct().toLongArray()
        for (reader in layers) D3D12RtLightInputs.invalidateArray(reader)
        revokeCompiledInputs(affected) {}
    }

    private fun readLightArray(layer: DataLayer, original: Supplier<ByteArray>): ByteArray =
        beginLightLayerPublication(layer, false).use { publication ->
            val bytes = original.get()
            publication?.complete()
            bytes
        }

    @JvmStatic
    @Synchronized
    fun beginLightLayerPublication(layer: DataLayer, recoverInput: Boolean): LightLayerPublication? {
        val input = D3D12RtLightInputs.input(layer) ?: return null
        input.active++
        input.version++
        val publication = LightLayerPublication(levelIdentity, epoch, layer, input, input.failureRevision, recoverInput)
        try {
            revokeCompiledInputs(lightLayerReaders[layer].orEmpty().toLongArray()) {}
        } catch (problem: Throwable) {
            input.active--
            input.failed = true
            input.failureRevision++
            publication.closed = true
            throw problem
        }
        return publication
    }

    @JvmStatic
    @Synchronized
    private fun completeLightLayerPublication(publication: LightLayerPublication) {
        check(!publication.closed)
        publication.completed = true
    }

    @JvmStatic
    @Synchronized
    private fun finishLightLayerPublication(publication: LightLayerPublication) {
        if (publication.closed) return
        val input = publication.input
        try {
            if (!publication.completed) {
                input.failed = true
                input.failureRevision++
            } else if (publication.recoverInput && input.failureRevision == publication.failureRevision) {
                input.failed = false
            }
        } finally {
            input.active--
            publication.closed = true
        }
    }

    private fun invalidateCompilerInputs(compiler: SectionCompiler?, publish: () -> Unit) {
        val nodes = buildSet {
            for (chunk in loadedChunks.values) addAll(chunk.nodes.asList())
            addAll(compileScopes.keys)
            addAll(sections.keys)
            addAll(pendingUploads.values.map { it.sectionNode })
        }.toLongArray()
        D3D12RtGeometryPool.invalidateModelResources(nodes, modelResources.sourceUniverse) {
            compilerIdentity = compiler
            compilerRevision++
            contentRevision++
            compileScopes.clear()
            lightReaders.clear()
            lightLayerReaders.clear()
            lightQueueReaders.clear()
            lightArrayReaders.clear()
            lightLayerArrays.clear()
            tintReaders.clear()
            biomeReaders.clear()
            biomeInfoReaders.clear()
            colorTableReaders.forEach { it.clear() }
            blockExtensionReaders.clear()
            pendingUploads.clear()
            sections.clear()
            D3D12RtMaterialTable.clear()
            publish()
        }
    }

    private fun regionInputs(sectionNode: Long): List<Long> = buildList(27) {
        for (deltaZ in -1..1) {
            for (deltaY in -1..1) {
                for (deltaX in -1..1) add(SectionPos.offset(sectionNode, deltaX, deltaY, deltaZ))
            }
        }
    }

    private fun dependentSections(nodes: LongArray): LongArray {
        val pending = pendingUploads.values.mapTo(hashSetOf()) { it.sectionNode }
        return buildSet {
            for (node in nodes) {
                addAll(biomeReaders[node].orEmpty().map { it.sectionNode })
                for (owner in regionInputs(node)) {
                    if (isLoadedSection(owner) || owner in compileScopes || sections.containsKey(owner) || owner in pending ||
                        D3D12RtGeometryPool.hasSectionCapture(owner)) add(owner)
                }
            }
        }.toLongArray()
    }

    private fun isLoadedSection(sectionNode: Long): Boolean =
        loadedChunks[SectionPos.asLong(SectionPos.x(sectionNode), 0, SectionPos.z(sectionNode))]
            ?.nodes?.contains(sectionNode) == true

    @JvmStatic
    @Synchronized
    fun observeChunkLoaded(chunkX: Int, chunkZ: Int, minSectionY: Int, sectionCount: Int,
                           levelIdentity: Any?, chunkIdentity: Any) {
        if (levelIdentity == null || this.levelIdentity !== levelIdentity) return
        require(sectionCount in 1..256)
        val key = SectionPos.asLong(chunkX, 0, chunkZ)
        val nodes = LongArray(sectionCount) { SectionPos.asLong(chunkX, minSectionY + it, chunkZ) }
        val retired = loadedChunks[key]?.nodes ?: longArrayOf()
        val changed = (retired.asList() + nodes.asList()).distinct().toLongArray()
        val dependent = dependentSections(changed)
        val retained = nodes.toSet()
        val chunk = (chunkIdentity as? LevelChunk)?.takeIf { it.javaClass == LevelChunk::class.java && it.level === levelIdentity }
        val sources = chunk?.let { nodes.withIndex().mapNotNull { [index, node] ->
                D3D12RtSectionArrays.section(chunk, index)?.takeIf { it.javaClass == LevelChunkSection::class.java &&
                    it.states.javaClass == PalettedContainer::class.java }?.let { BlockSource(node, chunk, it, index) }
            } }.orEmpty()
        val biomes = chunk?.let { nodes.withIndex().mapNotNull { [index, node] ->
            D3D12RtSectionArrays.section(chunk, index)?.takeIf { it.javaClass == LevelChunkSection::class.java }
                ?.let { BiomeSource(node, chunk, it, index) }
        } }.orEmpty()
        D3D12RtGeometryPool.replaceChunk(nodes, true, dependent, retired.filter { it !in retained }.toLongArray()) {
            revokeSections(changed, dependent)
            loadedChunks[key]?.blockSources?.forEach(::removeBlockSource)
            loadedChunks[key]?.biomeSources?.forEach(::removeBiomeSource)
            loadedChunks[key] = LoadedChunk(chunkIdentity, nodes, sources, biomes)
            val column = ChunkPos.pack(chunkX, chunkZ)
            if (chunk != null && !D3D12RtSectionArrays.available(chunk)) sectionArrayInputs.add(column)
            else sectionArrayInputs.remove(column)
            for (source in sources) {
                blockPaletteSources.getOrPut(source.palette, ::linkedSetOf).add(source)
                blockSectionSources.getOrPut(source.section, ::linkedSetOf).add(source)
                refreshBlockStorageSource(source)
            }
            for (source in biomes) {
                biomeSectionSources.getOrPut(source.section, ::linkedSetOf).add(source)
                refreshBiomePaletteSource(source)
            }
        }
        if (chunk != null && ChunkPos.pack(chunkX, chunkZ) in sectionArrayInputs) {
            (chunk.level as? ClientLevel)?.takeIf { it.javaClass == ClientLevel::class.java }
                ?.let { invalidateTintCaches(it, chunkX, chunkZ) }
        }
    }

    @JvmStatic
    @Synchronized
    fun observeChunkUnloaded(chunkX: Int, chunkZ: Int, levelIdentity: Any?, chunkIdentity: Any) {
        if (levelIdentity == null || this.levelIdentity !== levelIdentity) return
        val key = SectionPos.asLong(chunkX, 0, chunkZ)
        val loaded = loadedChunks[key]?.takeIf { it.identity === chunkIdentity } ?: return
        val dependent = dependentSections(loaded.nodes)
        D3D12RtGeometryPool.replaceChunk(loaded.nodes, false, dependent) {
            revokeSections(loaded.nodes, dependent)
            loaded.blockSources.forEach(::removeBlockSource)
            loaded.biomeSources.forEach(::removeBiomeSource)
            loadedChunks.remove(key)
            sectionArrayInputs.remove(ChunkPos.pack(chunkX, chunkZ))
        }
    }

    private fun removeBlockSource(source: BlockSource) {
        removeBlockStorageSource(source)
        blockPaletteSources[source.palette]?.let { sources ->
            sources.remove(source)
            if (sources.isEmpty()) blockPaletteSources.remove(source.palette)
        }
        blockSectionSources[source.section]?.let { sources ->
            sources.remove(source)
            if (sources.isEmpty()) blockSectionSources.remove(source.section)
        }
    }

    private fun refreshBiomePaletteSource(source: BiomeSource) {
        removeBiomePaletteSource(source)
        source.palette = (source.section.biomes as? PalettedContainer<*>)?.takeIf { it.javaClass == PalettedContainer::class.java }
        source.palette?.let { biomePaletteSources.getOrPut(it, ::linkedSetOf).add(source) }
        refreshBiomeStorageSource(source)
    }

    private fun removeBiomePaletteSource(source: BiomeSource) {
        removeBiomeStorageSource(source)
        source.palette?.let { palette ->
            biomePaletteSources[palette]?.let { sources ->
                sources.remove(source)
                if (sources.isEmpty()) biomePaletteSources.remove(palette)
            }
        }
    }

    private fun refreshBlockStorageSource(source: BlockSource) {
        removeBlockStorageSource(source)
        source.storage = D3D12RtPaletteStorage.current(source.palette)
        source.entries = D3D12RtPaletteEntries.current(source.palette)
        source.entries?.let { blockEntrySources.getOrPut(it, ::linkedSetOf).add(source) }
        source.storage?.let {
            blockStorageSources.getOrPut(it, ::linkedSetOf).add(source)
            linkPaletteArray(it)
        }
        updateBlockDataAvailability(source)
    }

    private fun removeBlockStorageSource(source: BlockSource) {
        for (owner in source.mappingOwners) blockMappingSources[owner]?.let {
            it.remove(source)
            if (it.isEmpty()) blockMappingSources.remove(owner)
        }
        source.mappingOwners = emptyList()
        source.entries?.let { entries ->
            blockEntrySources[entries]?.let { sources ->
                sources.remove(source)
                if (sources.isEmpty()) blockEntrySources.remove(entries)
            }
        }
        source.entries = null
        source.storage?.let { storage ->
            blockStorageSources[storage]?.let { sources ->
                sources.remove(source)
                if (sources.isEmpty()) blockStorageSources.remove(storage)
            }
            unlinkPaletteArray(storage)
        }
        source.storage = null
        unavailableBlockStorage.remove(source.node)
    }

    private fun refreshBiomeStorageSource(source: BiomeSource) {
        removeBiomeStorageSource(source)
        source.storage = source.palette?.let(D3D12RtPaletteStorage::current)
        source.entries = source.palette?.let(D3D12RtPaletteEntries::current)
        source.entries?.let { biomeEntrySources.getOrPut(it, ::linkedSetOf).add(source) }
        source.storage?.let {
            biomeStorageSources.getOrPut(it, ::linkedSetOf).add(source)
            linkPaletteArray(it)
        }
        updateBiomeDataAvailability(source)
    }

    private fun removeBiomeStorageSource(source: BiomeSource) {
        for (owner in source.mappingOwners) biomeMappingSources[owner]?.let {
            it.remove(source)
            if (it.isEmpty()) biomeMappingSources.remove(owner)
        }
        source.mappingOwners = emptyList()
        source.entries?.let { entries ->
            biomeEntrySources[entries]?.let { sources ->
                sources.remove(source)
                if (sources.isEmpty()) biomeEntrySources.remove(entries)
            }
        }
        source.entries = null
        source.storage?.let { storage ->
            biomeStorageSources[storage]?.let { sources ->
                sources.remove(source)
                if (sources.isEmpty()) biomeStorageSources.remove(storage)
            }
            unlinkPaletteArray(storage)
        }
        source.storage = null
        unavailableBiomeStorage.remove(source.node)
    }

    private fun removeBiomeSource(source: BiomeSource) {
        removeBiomePaletteSource(source)
        biomeSectionSources[source.section]?.let { sources ->
            sources.remove(source)
            if (sources.isEmpty()) biomeSectionSources.remove(source.section)
        }
    }

    private fun revokeSections(inputs: LongArray, dependent: LongArray) {
        val changed = (inputs.asList() + dependent.asList()).toSet()
        contentRevision++
        for (node in inputs) inputVersions[node] = contentRevision
        for (node in changed) {
            removeCompileScope(node)
            sections.remove(node)
        }
        pendingUploads.entries.removeAll { it.value.sectionNode in changed }
    }

    @JvmStatic
    @Synchronized
    fun isCurrent(scope: CompileScope?): Boolean = scope != null &&
        scope.worldIdentity === levelIdentity && scope.epoch == epoch &&
        !hasUnavailableBlockInput(scope.sectionNode) &&
        !hasUnavailableChunkInput(ChunkPos.pack(SectionPos.x(scope.sectionNode), SectionPos.z(scope.sectionNode))) &&
        scope.modelRevision == modelRevision &&
        scope.compilerRevision == compilerRevision && scope.compilerIdentity === compilerIdentity &&
        scope.compilerIdentity === D3D12RtCompilerInputs.current()?.compiler &&
        compileScopes[scope.sectionNode] === scope && scope.matchesInputs(inputVersions) &&
        scope.tintInputs.keys.all { D3D12RtTintInputs.sourceCurrent(it, levelIdentity) } &&
        scope.blockExtensions.all { [block, input] -> D3D12RtBlockExtensions.current(input, block) } &&
        scope.lightInputs?.matches(scope.worldIdentity) != false

    @JvmStatic
    @Synchronized
    fun sourceCapture(scope: CompileScope): D3D12RtGeometryPool.CaptureTarget? =
        if (isCurrent(scope)) D3D12RtGeometryPool.missingCaptures(scope.sectionNode).singleOrNull { it.layer == -1 } else null

    @JvmStatic
    @Synchronized
    fun publishSourceSupport(target: D3D12RtGeometryPool.CaptureTarget,
                             manifest: D3D12RtModelSupport.SourceManifest): Boolean =
        isCurrent(manifest.scope) && D3D12RtGeometryPool.publishSourceSupport(target, manifest)

    @JvmStatic
    @Synchronized
    fun revokeSourceSupport(manifest: D3D12RtModelSupport.SourceManifest) {
        D3D12RtGeometryPool.revokeSourceSupport(manifest)
    }

    @JvmStatic
    @Synchronized
    fun invalidateSection(sectionNode: Long, levelIdentity: Any?) {
        if (levelIdentity == null || this.levelIdentity !== levelIdentity) return
        val inputs = longArrayOf(sectionNode)
        val dependent = dependentSections(inputs)
        val affected = (inputs.asList() + dependent.asList()).distinct().toLongArray()
        D3D12RtGeometryPool.invalidateSections(affected) { revokeSections(inputs, dependent) }
    }

    fun find(sectionNode: Long): SectionGeometry? = sections[sectionNode]

    @JvmStatic
    fun geometryVersionOf(sectionNode: Long): Int = sections[sectionNode]?.geometryVersion ?: -1

    @JvmStatic
    @Synchronized
    fun meshLayer(meshIdentity: Any, layer: String): LayerUpload? {
        try {
            reapMeshUploads()
            return meshUploads[MeshKey(meshIdentity)]?.get(layer)
        } finally {
            Reference.reachabilityFence(meshIdentity)
        }
    }

    @JvmStatic
    @Synchronized
    fun stageUpload(sectionNode: Long, meshIdentity: Any, layer: String, indexCount: Int,
                    levelIdentity: Any?, vertex: ByteBuffer?, indexSource: IndexSource,
                    indexStrideBytes: Int, index: ByteBuffer?) {
        observeLevel(levelIdentity)
        if (levelIdentity == null || D3D12RtGeometryPool.isSectionUnloaded(sectionNode)) return
        val pending = pendingUploads.getOrPut(meshIdentity) { PendingUpload(sectionNode, epoch) }
        if (pending.sectionNode != sectionNode || pending.epoch != epoch || pending.layers[layer]?.hasPayload == true) return
        fun copy(bytes: ByteBuffer): ByteBuffer = ByteBuffer.allocate(bytes.remaining())
            .order(bytes.order()).apply { put(bytes.duplicate()); flip() }
        val upload = LayerUpload(layer, indexCount, vertex?.remaining() ?: 0, index?.remaining() ?: 0,
            indexSource, indexStrideBytes)
        val missing = PendingLayer(null, null, upload)
        pending.layers[layer] = missing
        val captured = try {
            val vertices = vertex?.let(::copy)
            val indices = index?.let(::copy)
            PendingLayer(vertices, indices, upload.copy(meshlets = buildMeshlets(vertices, indexCount)))
        } catch (failure: Throwable) {
            reportCaptureFailure(failure)
            missing
        }
        pending.layers[layer] = captured
        reapMeshUploads()
        meshUploads.getOrPut(MeshKey(meshIdentity, releasedMeshes)) { linkedMapOf() }[layer] = captured.upload
        Reference.reachabilityFence(meshIdentity)
    }

    @JvmStatic
    @JvmOverloads
    @Synchronized
    fun commitCurrentUpload(sectionNode: Long, meshIdentity: SectionMesh, levelIdentity: Any?, expectedEpoch: Int,
                            scope: CompileScope?, manifest: D3D12RtModelSupport.Manifest? = null): Boolean {
        if (levelIdentity == null || this.levelIdentity !== levelIdentity || epoch != expectedEpoch ||
            meshIdentity === CompiledSectionMesh.UNCOMPILED || !isCurrent(scope) ||
            scope?.sectionNode != sectionNode) return false
        commitUpload(sectionNode, meshIdentity, levelIdentity, manifest?.takeIf { it.scope === scope })
        return true
    }

    @JvmStatic
    @JvmOverloads
    @Synchronized
    fun commitUpload(sectionNode: Long, meshIdentity: SectionMesh, levelIdentity: Any?,
                     manifest: D3D12RtModelSupport.Manifest? = null) {
        observeLevel(levelIdentity)
        if (levelIdentity == null || meshIdentity === CompiledSectionMesh.UNCOMPILED ||
            D3D12RtGeometryPool.isSectionUnloaded(sectionNode)) return
        val layers = buildMap {
            for (layer in ChunkSectionLayer.entries) {
                val draw = meshIdentity.getSectionDraw(layer) ?: continue
                if (draw.indexCount() > 0) put(layer.name, draw)
            }
        }
        val accepted = pendingUploads[meshIdentity]?.takeIf { it.epoch == epoch && it.sectionNode == sectionNode }
        val support = manifest?.takeIf { it.scope.sectionNode == sectionNode && isCurrent(it.scope) }
        if (accepted != null) pendingUploads.remove(meshIdentity)
        D3D12RtGeometryPool.replaceSection(sectionNode, layers.keys) {
            sections.remove(sectionNode)
            for ([layer, draw] in layers) {
                val source = if (draw.hasCustomIndexBuffer()) IndexSource.CUSTOM_SLICE else IndexSource.SEQUENTIAL_QUADS
                val stride = if (draw.hasCustomIndexBuffer()) draw.indexType().bytes else 0
                val captured = accepted?.layers?.get(layer)?.takeIf {
                    it.hasPayload && it.upload.indexCount == draw.indexCount() && it.upload.indexSource == source &&
                        (source == IndexSource.SEQUENTIAL_QUADS || it.upload.indexStrideBytes == stride)
                }
                val upload = captured?.upload ?: LayerUpload(layer, draw.indexCount(), 0, 0, source, stride)
                publishUpload(sectionNode, upload, captured?.vertex, captured?.index)
            }
            D3D12RtGeometryPool.observeCompiledSection(sectionNode, support)
        }
    }

    @JvmStatic
    @Synchronized
    fun discardUpload(meshIdentity: Any) {
        pendingUploads.remove(meshIdentity)
        reapMeshUploads()
    }

    @JvmStatic
    @Synchronized
    fun removeSection(sectionNode: Long) {
        pendingUploads.entries.removeAll { it.value.sectionNode == sectionNode }
        val scope = compileScopes[sectionNode]?.takeIf { isLoadedSection(sectionNode) && isCurrent(it) }
        if (scope != null) {
            D3D12RtGeometryPool.retainSection(sectionNode, scope)?.let { evicted ->
                sections.remove(sectionNode)
                for (node in evicted) {
                    removeCompileScope(node)
                    sections.remove(node)
                    pendingUploads.entries.removeAll { it.value.sectionNode == node }
                }
                return
            }
        }
        removeCompileScope(sectionNode)
        if (isLoadedSection(sectionNode)) {
            D3D12RtGeometryPool.releaseSection(sectionNode) { sections.remove(sectionNode) }
        } else {
            D3D12RtGeometryPool.replaceSection(sectionNode) { sections.remove(sectionNode) }
        }
    }

    /**
     * 记录一次上传；`levelIdentity` 变化时清空注册表并推进 epoch。渲染线程与 worker 线程都会调用。
     */
    @JvmStatic
    @Synchronized
    fun captureUpload(sectionNode: Long, layer: String, indexCount: Int, vertexBytes: Int,
                      indexBytes: Int, levelIdentity: Any?, vertex: ByteBuffer?,
                      indexSource: IndexSource, indexStrideBytes: Int, index: ByteBuffer? = null) {
        val started = System.nanoTime()
        observeLevel(levelIdentity)
        if (levelIdentity == null) return
        val upload = LayerUpload(layer, indexCount, vertexBytes, indexBytes, indexSource, indexStrideBytes,
            buildMeshlets(vertex, indexCount))
        publishUpload(sectionNode, upload, vertex, index, started)
    }

    private fun buildMeshlets(vertex: ByteBuffer?, indexCount: Int): List<D3D12MeshletBuilder.Meshlet> {
        val shadowLimit = if (D3D12Settings.meshTerrain) Int.MAX_VALUE else MESHLET_SHADOW_UPLOADS
        var meshlets: List<D3D12MeshletBuilder.Meshlet> = emptyList()
        if (vertex != null && indexCount > 0 && meshletUploads.get() < shadowLimit) {
            val built = try {
                D3D12MeshletBuilder.build(vertex)
            } catch (ignored: Throwable) {
                null
            }
            if (built != null) {
                meshlets = built.meshlets
                val count = meshletUploads.incrementAndGet()
                meshletQuads.addAndGet(built.quads.toLong())
                meshletCount.addAndGet(built.meshletCount.toLong())
                meshletPure.addAndGet(built.pureMeshlets.toLong())
                for (direction in 0 until 7) {
                    meshletDirections.addAndGet(direction, built.directionCounts[direction])
                }
                if (!D3D12Settings.meshTerrain &&
                    (count == MESHLET_SHADOW_UPLOADS.toLong() || count % 16L == 0L)) {
                    LOGGER.warn("meshlet shadow: {}", meshletSummary())
                }
            }
        }
        return meshlets
    }

    private fun publishUpload(sectionNode: Long, upload: LayerUpload, vertex: ByteBuffer?,
                              index: ByteBuffer?, started: Long = System.nanoTime()) {
        val layer = upload.layer
        val indexCount = upload.indexCount
        val vertexBytes = upload.vertexBytes
        val indexBytes = upload.indexBytes
        val indexSource = upload.indexSource
        val indexStrideBytes = upload.indexStrideBytes
        checkContract(layer, indexCount, vertexBytes, indexBytes, indexSource, indexStrideBytes)
        val existing = sections[sectionNode]
        val layers = if (existing == null) {
            LinkedHashMap<String, LayerUpload>(3)
        } else {
            LinkedHashMap(existing.layers)
        }
        layers[layer] = upload
        val version = (++generation).toInt()
        versionBumps.incrementAndGet()
        val custom = layers.values.any { it.indexSource == IndexSource.CUSTOM_SLICE }
        if (custom) {
            customIndexLayers.incrementAndGet()
        }
        val geometry = SectionGeometry(
            key = sectionNode,
            epoch = epoch,
            generation = generation,
            geometryVersion = version,
            capturedAtNanos = started,
            layers = layers,
        )
        val contracts = layers.keys.map(::kindOfLayer)
        for (kind in contracts) {
            kindCounts.incrementAndGet(kind.ordinal)
        }
        quadIndices.addAndGet((layers.values.sumOf { it.vertexBytes } / D3D12MeshletBuilder.VERTEX_STRIDE / QUAD_VERTICES * QUAD_INDICES).toLong())
        sections[sectionNode] = geometry
        try {
            if (vertex != null) {
                D3D12RtGeometryPool.publish(sectionNode, layer, version, vertex, index, indexCount)
            } else if (indexCount > 0) {
                D3D12RtGeometryPool.markMissing(sectionNode, layer, version)
            }
        } catch (failure: Throwable) {
            reportCaptureFailure(failure)
        }
        val total = uploads.incrementAndGet()
        if (indexCount <= 0) {
            emptyUploads.incrementAndGet()
        } else {
            capturedTriangles.addAndGet(upload.triangles.toLong())
        }
        vertexBytesTotal.addAndGet(vertexBytes.toLong())
        indexBytesTotal.addAndGet(indexBytes.toLong())
        captureNanosTotal.addAndGet(System.nanoTime() - started)
        if (total <= 3L || total % 1024L == 0L) {
            val pos = SectionPos.of(sectionNode)
            LOGGER.debug("section upload #{} at ({},{},{}) vBytes={} iBytes={} bad={}",
                total, pos.x(), pos.y(), pos.z(), vertexBytes, indexBytes,
                D3D12RtGeometryPool.validate())
        }
    }

    private val captureFailures = AtomicLong()

    private fun reportCaptureFailure(failure: Throwable) {
        val count = captureFailures.incrementAndGet()
        if (count <= 4L || count and (count - 1) == 0L) {
            IllusionLogger.warnMessage("section capture: ${failure.javaClass.simpleName} ($count)")
        }
    }

    private fun poolSummary(): String = try {
        D3D12RtGeometryPool.summary()
    } catch (t: Throwable) {
        "rt geometry pool: summary failed: ${t.javaClass.simpleName}"
    }

    /** 采集成本与规模的一行摘要（日志用；调用方自己控制频率）。 */
    fun summary(): String {
        val count = uploads.get()
        val avgUs = if (count == 0L) 0.0 else captureNanosTotal.get() / 1000.0 / count
        return "section geometry: sections=${sections.size} uploads=$count " +
            "triangles=${capturedTriangles.get()} empty=${emptyUploads.get()} " +
            "vMB=${vertexBytesTotal.get() / (1024 * 1024)} iMB=${indexBytesTotal.get() / (1024 * 1024)} " +
            "avg=${"%.1f".format(avgUs)}us epoch=$epoch gen=$generation"
    }


    // ---- S1 / P83-a：SectionGeometry 契约 -------------------------------------------------
    // 契约本身是"一份几何、两个导出"的唯一上游（rt_plan §3.0）。今天能填的是
    // sectionId / geometryVersion / epoch / origin / flags / 顶点与 quad 索引范围 / AABB；
    // faceRange 与 materialSetId 归体素导出（P83-c），在材质表落地前恒为 0。

    /** 原版方块几何每个 quad 4 个顶点；quad q 的索引 = (4q, 4q+1, 4q+2, 4q, 4q+2, 4q+3)。 */
    const val QUAD_VERTICES = 4
    const val QUAD_INDICES = 6

    /** `flags` 的位（rt_plan §3.0）。 */
    object Flag {
        const val SOLID = 1 shl 0
        const val CUTOUT = 1 shl 1
        const val TRANSLUCENT = 1 shl 2
        const val MODEL = 1 shl 3
        const val FLUID = 1 shl 4
    }

    /**
     * rt_plan §3.0 的 8 类几何。今天的分类只到"层"这一粒度（上传回调只给层，没有方块 id），
     * 逐方块判定要等 S2 的材质表；因此 [authoritative] 的结论只在 CUBE 一行是精确的。
     */
    enum class GeometryKind(val voxelDda: Boolean, val triangles: Boolean, val note: String) {
        CUBE(true, true, "双轨都可用"),
        SHAPED_BLOCK(false, true, "体素只记近似，强制转 RayQuery"),
        CUTOUT_PLANT(false, true, "无厚度，归 CUTOUT"),
        LEAVES(false, true, "体素只记占据"),
        TRANSLUCENT(false, true, "合成归 OIT，不参与 DDA 短路"),
        ENTITY(false, true, "动态实例"),
        MODEL_BLOCK(false, false, "顶点是否进 uber buffer 未验证"),
        SUBVOXEL(false, true, "细节由命中点 UV 补"),
    }

    /** 权威轨：两个都为 true 时按 §3.5 的分派规则选。 */
    fun authoritative(kind: GeometryKind): String = when {
        kind.voxelDda && kind.triangles -> "either"
        kind.triangles -> "triangles"
        kind.voxelDda -> "voxel"
        else -> "none"
    }

    /**
     * 上传回调给的层名是 `ChunkSectionLayer.name()`（全大写枚举名，不随语言变），四种取值固定，
     * 所以 [ordinal] 可以直接当槽号：`sectionNode * 8 + ordinal` 不会互相覆盖。
     * 契约位与几何类别跟着层走，一处定义、索引池与材质表共用。
     */
    enum class Layer(val flag: Int, val kind: GeometryKind) {
        SOLID(Flag.SOLID, GeometryKind.CUBE),
        CUTOUT(Flag.CUTOUT, GeometryKind.CUTOUT_PLANT),
        TRANSLUCENT(Flag.TRANSLUCENT, GeometryKind.TRANSLUCENT),
        OTHER(0, GeometryKind.SUBVOXEL),
        ;

        companion object {
            fun of(layer: String): Layer = when (layer.uppercase(Locale.ROOT)) {
                "SOLID" -> SOLID
                "CUTOUT" -> CUTOUT
                "TRANSLUCENT" -> TRANSLUCENT
                else -> OTHER
            }
        }
    }

    /** 由层名映射到几何类别（粗粒度）。 */
    fun kindOfLayer(layer: String): GeometryKind = Layer.of(layer).kind

    /**
     * 契约的一条记录。字段顺序与 rt_plan §3.0 的 15 行一一对应；`-1` = 该字段还没导出
     * （顶点/索引范围要等全局 quad 索引缓冲，见 S1 步骤 3）。
     */
    data class Contract(
        val sectionId: Long,
        val geometryVersion: Int,
        val epoch: Int,
        val originX: Int,
        val originY: Int,
        val originZ: Int,
        val flags: Int,
        val vertexOffset: Int,
        val vertexCount: Int,
        val quadIndexOffset: Int,
        val quadIndexCount: Int,
        val faceRange: Int,
        val materialSetId: Int,
        val worldMin: FloatArray,
        val worldMax: FloatArray,
        val kinds: Set<GeometryKind>,
    ) {
        val authoritative: String
            get() = if (kinds.isEmpty()) "none"
            else if (kinds.size == 1) D3D12SectionGeometry.authoritative(kinds.first())
            else "either"
    }

    private val violations = AtomicLong()
    private val versionBumps = AtomicLong()
    private val quadIndices = AtomicLong()
    private val kindCounts = AtomicIntegerArray(GeometryKind.entries.size)

    /** 契约记录；`null` = 这个 section 还没被采集过。 */
    fun contract(sectionNode: Long): Contract? {
        val geometry = sections[sectionNode] ?: return null
        val pos = SectionPos.of(sectionNode)
        val flags = geometry.layers.keys.fold(0) { acc, layer -> acc or Layer.of(layer).flag }
        val vertexCount = geometry.layers.values.sumOf { it.vertexBytes } / D3D12MeshletBuilder.VERTEX_STRIDE
        val quads = vertexCount / QUAD_VERTICES
        val originX = pos.x() * 16
        val originY = pos.y() * 16
        val originZ = pos.z() * 16
        return Contract(
            sectionId = sectionNode,
            geometryVersion = geometry.geometryVersion,
            epoch = geometry.epoch,
            originX = originX,
            originY = originY,
            originZ = originZ,
            flags = flags,
            vertexOffset = -1,
            vertexCount = vertexCount,
            quadIndexOffset = -1,
            quadIndexCount = quads * QUAD_INDICES,
            faceRange = 0,
            materialSetId = 0,
            worldMin = floatArrayOf(originX.toFloat(), originY.toFloat(), originZ.toFloat()),
            worldMax = floatArrayOf((originX + 16).toFloat(), (originY + 16).toFloat(), (originZ + 16).toFloat()),
            kinds = geometry.layers.keys.map(::kindOfLayer).toSet(),
        )
    }

    /** 契约规模的单行摘要（日志与 `/rt/debug/geometry` 共用）。 */
    fun contractSummary(): String {
        val counts = GeometryKind.entries.mapIndexed { i, kind -> "${kind.name}=${kindCounts.get(i)}" }
        return "contract: sections=${sections.size} versions=$versionBumps violations=$violations " +
            "quadIdx=${quadIndices.get()} custom=${customIndexLayers.get()}" +
            (firstViolation?.let { " first=${it}" } ?: "") +
            " [${counts.joinToString(" ")}]"
    }
    /**
     * shadow 统计摘要：`pure` = 单方向 meshlet 占比，`dirs` = 逐 Quad 朝向分布（+X…-Z, non-axis）。
     */
    fun meshletSummary(): String {
        val sections = meshletUploads.get()
        val meshlets = meshletCount.get()
        val purePct = if (meshlets == 0L) 0.0 else meshletPure.get() * 100.0 / meshlets
        val perSection = if (sections == 0L) 0.0 else meshlets.toDouble() / sections
        val dirs = (0 until 7).joinToString(",") { meshletDirections.get(it).toString() }
        return "sections=$sections meshlets=$meshlets perSection=${"%.1f".format(perSection)} " +
            "quads=${meshletQuads.get()} pure=${"%.1f".format(purePct)}% dirs=[$dirs]"
    }
}
