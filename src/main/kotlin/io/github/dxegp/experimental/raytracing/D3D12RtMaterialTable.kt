package io.github.dxegp.experimental.raytracing

import com.mojang.blaze3d.vertex.DefaultVertexFormat
import io.github.dxegp.misc.IllusionLogger
import io.github.dxegp.misc.settings.D3D12Settings
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.texture.TextureAtlas
import net.minecraft.client.renderer.texture.TextureAtlasSprite
import net.minecraft.data.AtlasIds
import net.minecraft.resources.Identifier
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.common.NeoForge
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.pow
import io.github.dxegp.d3d12.render.adapter.D3D12Device

/**
 * S2 的材质表（rt_plan §3.3 的最小版）：图集精灵 → 一条 40 B 的材质记录。
 * 颜色存**线性**空间：光追阶段在线性空间积分，乘 albedo 之前不能再转一次。
 * `flags` 留在 +28 不动，金属度追加在 +32，既有偏移一个都没变（见 docs/rt/README.md §17.2）。
 */
object D3D12RtMaterialTable {
    const val ENTRY_BYTES = 40

    /** 逐 quad 索引是 u16，表项上限跟着走。 */
    const val MAX_ENTRIES = 1 shl 16

    /** 表项 0 是兜底：中性灰、不发光、粗糙。没查到精灵的 quad 都指它。 */
    const val FALLBACK = 0

    /** UV 空间的候选桶分辨率；2048 图集里一个 16 px 精灵约占一格。 */
    private const val GRID = 128

    /** 每个精灵的采样网格（4×4 = 16 个像素），用来算平均色。 */
    private const val SAMPLES = 4

    private const val DEFAULT_ALBEDO = 0.6f
    private const val DEFAULT_ROUGHNESS = 0.8f

    /** `flags` 的位；与 rt_plan §3.3 一致。 */
    private const val FLAG_TRANSLUCENT = 1 shl 0
    private const val FLAG_SUBSURFACE = 1 shl 1
    private const val FLAG_TWO_SIDED = 1 shl 2
    private const val FLAG_PULSING = 1 shl 3

    /** 一个精灵在烘焙期的常量：图集矩形 + 平均色（线性）+ 材质。 */
    private class Sprite(
        val u0: Float,
        val v0: Float,
        val u1: Float,
        val v1: Float,
        val albedoR: Float,
        val albedoG: Float,
        val albedoB: Float,
        val roughness: Float,
        val metalness: Float,
        val emissive: Float,
        val flags: Int,
        val maps: D3D12RtMaterialMaps.Look,
    ) {
        var textureOffset = 0
    }

    /** 一次烘焙的不可变结果：精灵表 + UV 候选桶 + 顶点格式偏移。 */
    private class Snapshot(
        val atlas: Any,
        val sprites: Array<Sprite?>,
        val bucketStart: IntArray,
        val bucketItems: IntArray,
        val stride: Int,
        val uvOffset: Int,
        val colorOffset: Int,
        val textureBytes: ByteArray,
        val firstSprite: TextureAtlasSprite?,
        val mapsEnabled: Boolean,
        val packPath: String,
        val parallaxDepth: Float,
    ) {
        val size: Int get() = sprites.size
    }

    @Volatile
    private var snapshot: Snapshot? = null

    private val entries = ByteBuffer.allocate(MAX_ENTRIES * ENTRY_BYTES).order(ByteOrder.LITTLE_ENDIAN)
    private val entryLock = Any()
    private val materialIds = ConcurrentHashMap<Long, Int>()
    private var entryCount: Int = 0

    private val revision = AtomicLong()
    private val historyRevision = AtomicLong()
    private val bakes = AtomicLong()
    private val spriteTotal = AtomicLong()
    private val quadsClassified = AtomicLong()
    private val spriteMisses = AtomicLong()
    private val tableFull = AtomicLong()
    private val bakeNanos = AtomicLong()

    init {
        writeEntry(FALLBACK, DEFAULT_ALBEDO, DEFAULT_ALBEDO, DEFAULT_ALBEDO,
            0f, 0f, 0f, DEFAULT_ROUGHNESS, 0f, 0)
        entryCount = 1
    }

    /** 由模组构造期调用；监听只在渲染线程上跑，且光追关掉时整条路径不工作。 */
    fun register() {
        D3D12RtGeometryRecovery.register()
        NeoForge.EVENT_BUS.addListener<ClientTickEvent.Post> {
            // G-Buffer 的释放在这里顺带做：与材质表共享同一个「光追是否还开着」的判定点。
            D3D12RtGBuffer.tick()
            D3D12RtFrame.tick()
            // 场景自检要独占编码器并等栅栏，只能在没有命令列表打开的时候跑（tick 正好满足）。
            D3D12RtTrace.validateOnce(D3D12RtGBuffer.deviceForSelfTest() ?: D3D12Device.current())
            tick()
        }
    }

    fun isReady(): Boolean = snapshot != null

    private fun tick() {
        if (!D3D12Settings.rtEnabled) {
            return
        }
        val atlas = blocksAtlas() ?: return
        val current = snapshot
        // 按 Atlas 对象身份判断：getTextures() 每次返回新的 unmodifiableMap 包装，比 Map 身份永远不等。
        val textures = atlas.textures
        if (current != null && current.atlas === atlas && current.size == textures.size &&
            current.firstSprite === textures.values.firstOrNull() &&
            current.mapsEnabled == D3D12Settings.materialsEnabled &&
            current.packPath == D3D12Settings.materialsPackPath &&
            current.parallaxDepth == D3D12Settings.materialsParallaxDepth) {
            return
        }
        if (!bake(atlas, textures)) {
            return
        }
        // 材质表就绪前的上传只能写兜底索引，这里回填一遍：顶点副本在池里是稳定的。
        val refreshed = D3D12RtGeometryPool.refreshMaterials()
        IllusionLogger.infoMessage("rt materials: ${summary()}")
    }

    private fun blocksAtlas(): TextureAtlas? {
        val client = Minecraft.getInstance()
        return runCatching { client.atlasManager.getAtlasOrThrow(AtlasIds.BLOCKS) }.getOrNull()
    }

    /**
     * 烘一张表。返回 false 表示图集还没准备好（启动早期 `textures` 是空的），下一 tick 再试。
     * 见 docs/rt/README.md
     */
    private fun bake(atlas: TextureAtlas, textures: Map<Identifier, TextureAtlasSprite>): Boolean {
        if (textures.isEmpty()) {
            return false
        }
        val started = System.nanoTime()
        val format = DefaultVertexFormat.BLOCK
        D3D12RtMaterialMaps.beginBake()
        val sprites = arrayOfNulls<Sprite>(textures.size)
        var index = 0
        var translucent = 0
        for (sprite in textures.values) {
            val baked = bakeSprite(sprite)
            sprites[index++] = baked
            if (baked != null && baked.flags and FLAG_TRANSLUCENT != 0) {
                translucent++
            }
        }
        val buckets = Buckets(sprites)
        val baked = Snapshot(
            atlas = atlas,
            sprites = sprites,
            bucketStart = buckets.start,
            bucketItems = buckets.items,
            stride = format.vertexSize,
            uvOffset = format.getElement(DefaultVertexFormat.UV0_SEMANTIC_NAME)?.offset() ?: -1,
            colorOffset = format.getElement(DefaultVertexFormat.COLOR_SEMANTIC_NAME)?.offset() ?: -1,
            textureBytes = packTextures(sprites, D3D12Settings.materialsParallaxDepth),
            firstSprite = textures.values.firstOrNull(),
            mapsEnabled = D3D12Settings.materialsEnabled,
            packPath = D3D12Settings.materialsPackPath,
            parallaxDepth = D3D12Settings.materialsParallaxDepth,
        )
        synchronized(entryLock) {
            materialIds.clear()
            entries.clear()
            entryCount = 1
            writeEntry(FALLBACK, DEFAULT_ALBEDO, DEFAULT_ALBEDO, DEFAULT_ALBEDO,
                0f, 0f, 0f, DEFAULT_ROUGHNESS, 0f, 0)
            snapshot = baked
            historyRevision.incrementAndGet()
        }
        bakes.incrementAndGet()
        revision.incrementAndGet()
        spriteTotal.set(sprites.size.toLong())
        bakeNanos.set(System.nanoTime() - started)
        IllusionLogger.debugMessage("rt materials: sprites=${sprites.size} translucent=$translucent")
        D3D12RtMaterialMaps.summary()
        return true
    }

    /** UV 候选桶：精灵矩形按 [GRID]² 网格切进 CSR 结构，查询先取桶再做矩形包含测试。 */
    private class Buckets(sprites: Array<Sprite?>) {
        val start: IntArray
        val items: IntArray

        init {
            val cells = GRID * GRID
            val counts = IntArray(cells)
            for (sprite in sprites) {
                if (sprite == null) continue
                forEachCell(sprite) { counts[it]++ }
            }
            start = IntArray(cells + 1)
            var acc = 0
            for (i in 0 until cells) {
                start[i] = acc
                acc += counts[i]
            }
            start[cells] = acc
            items = IntArray(acc)
            val cursor = start.copyOf()
            for (i in sprites.indices) {
                val sprite = sprites[i] ?: continue
                forEachCell(sprite) { items[cursor[it]++] = i }
            }
        }
    }

    private inline fun forEachCell(sprite: Sprite, action: (Int) -> Unit) {
        val x0 = cellIndex(sprite.u0)
        val x1 = cellIndex(sprite.u1)
        val y0 = cellIndex(sprite.v0)
        val y1 = cellIndex(sprite.v1)
        for (y in y0..y1) {
            for (x in x0..x1) {
                action(y * GRID + x)
            }
        }
    }

    private fun cellIndex(value: Float): Int = (value * GRID).toInt().coerceIn(0, GRID - 1)

    /** 一个精灵的平均色（线性）与启发式材质；图集被清空或取不到像素时返回 null。 */
    private fun bakeSprite(sprite: TextureAtlasSprite): Sprite? = runCatching {
        val contents = sprite.contents()
        val path = contents.name().path
        val width = contents.width()
        val height = contents.height()
        if (width <= 0 || height <= 0) {
            return@runCatching null
        }
        var red = 0.0
        var green = 0.0
        var blue = 0.0
        var weight = 0.0
        var transparent = 0
        var samples = 0
        for (sy in 0 until SAMPLES) {
            for (sx in 0 until SAMPLES) {
                val x = ((sx + 0.5f) * width / SAMPLES).toInt()
                val y = ((sy + 0.5f) * height / SAMPLES).toInt()
                val argb = sprite.getPixelARGB(0, x, y)
                val alpha = (argb ushr 24) and 0xFF
                samples++
                if (alpha == 0) {
                    transparent++
                    continue
                }
                val w = alpha / 255.0
                red += linear(((argb ushr 16) and 0xFF) / 255f) * w
                green += linear(((argb ushr 8) and 0xFF) / 255f) * w
                blue += linear((argb and 0xFF) / 255f) * w
                weight += w
            }
        }
        val look = lookOf(path)
        val sourceMaps = D3D12RtMaterialMaps.look(contents.name(), width, height)
        val albedoMap = if (sourceMaps.spec != null || sourceMaps.normal != null) {
            D3D12RtMaterialMaps.Image(width, height, IntArray(width * height) { pixel ->
                sprite.getPixelARGB(0, pixel % width, pixel / width)
            })
        } else null
        val maps = D3D12RtMaterialMaps.Look(sourceMaps.spec, sourceMaps.normal, albedoMap)
        val roughness = look.roughness
        val metalness = 0f
        if (weight <= 0.0) {
            return@runCatching Sprite(
                sprite.u0, sprite.v0, sprite.u1, sprite.v1,
                DEFAULT_ALBEDO, DEFAULT_ALBEDO, DEFAULT_ALBEDO,
                roughness, metalness, look.emissive, look.flags, maps,
            )
        }
        val translucent = transparent * 3 >= samples
        Sprite(
            sprite.u0, sprite.v0, sprite.u1, sprite.v1,
            (red / weight).toFloat(), (green / weight).toFloat(), (blue / weight).toFloat(),
            roughness, metalness, look.emissive,
            if (translucent) look.flags or FLAG_TRANSLUCENT else look.flags,
            maps,
        )
    }.getOrNull()

    private fun linear(value: Float): Double = value.toDouble().pow(2.2)

    private fun packTextures(sprites: Array<Sprite?>, parallaxDepth: Float): ByteArray {
        val mapped = sprites.filterNotNull().filter { it.maps.spec != null || it.maps.normal != null }
        val size = mapped.sumOf { sprite ->
            64L + 4L * ((sprite.maps.spec?.pixels?.size ?: 0) + (sprite.maps.normal?.pixels?.size ?: 0) +
                (sprite.maps.albedo?.pixels?.size ?: 0))
        }
        require(size <= Int.MAX_VALUE) { "Material textures exceed buffer capacity" }
        val data = ByteBuffer.allocate(size.toInt()).order(ByteOrder.LITTLE_ENDIAN)
        for (sprite in mapped) {
            val base = data.position()
            sprite.textureOffset = base + 1
            data.putFloat(sprite.u0).putFloat(sprite.v0)
                .putFloat(1f / (sprite.u1 - sprite.u0)).putFloat(1f / (sprite.v1 - sprite.v0))
            val opaque = sprite.maps.albedo?.pixels?.all { it ushr 24 == 255 } == true
            data.putFloat(base + 52, if (opaque) parallaxDepth else 0f)
            val minimumHeight = sprite.maps.normal?.pixels?.minOfOrNull { it ushr 24 } ?: 255
            data.putFloat(base + 56, 1f - minimumHeight / 255f)
            data.position(base + 64)
            for ([index, image] in listOf(sprite.maps.spec, sprite.maps.normal, sprite.maps.albedo).withIndex()) {
                val at = base + 16 + index * 12
                data.putInt(at, if (image == null) 0 else data.position() - base)
                data.putInt(at + 4, image?.width ?: 0)
                data.putInt(at + 8, image?.height ?: 0)
                image?.pixels?.forEach { data.putInt(it) }
            }
        }
        return data.array()
    }

    /** 粗糙度 / 自发光强度 / 材质位；先认透明与发光，再按材质族。路径形如 `block/stone_bricks`。 */
    private fun lookOf(path: String): Look = when {
        path.contains("water") || path.contains("ice") || path.contains("glass") ||
            path.contains("portal") || path.contains("slime") || path.contains("honey") ->
            Look(0.05f, 0f, if (path.contains("water")) FLAG_TWO_SIDED else 0)
        path.contains("lava") || path.contains("magma") -> Look(0.6f, 3.0f, FLAG_PULSING)
        path.contains("fire") || path.contains("flame") || path.contains("campfire") ->
            Look(0.9f, 2.5f, FLAG_PULSING or FLAG_TWO_SIDED)
        path.contains("glowstone") || path.contains("shroomlight") || path.contains("sea_lantern") ||
            path.contains("froglight") || path.contains("glow_lichen") -> Look(0.7f, 2.0f, 0)
        path.contains("torch") || path.contains("lantern") || path.contains("candle") -> Look(0.9f, 1.8f, 0)
        path.contains("beacon") || path.contains("end_rod") || path.contains("redstone_lamp") ||
            path.contains("jack_o_lantern") -> Look(0.5f, 1.5f, 0)
        path.contains("iron") || path.contains("gold") || path.contains("copper") ||
            path.contains("netherite") || path.contains("steel") -> Look(0.25f, 0f, 0)
        path.contains("snow") -> Look(0.6f, 0f, 0)
        path.contains("dirt") || path.contains("grass_block") || path.contains("sand") ||
            path.contains("gravel") || path.contains("podzol") || path.contains("mycelium") ||
            path.contains("farmland") || path.contains("clay") || path.contains("mud") ||
            path.contains("moss_block") -> Look(0.95f, 0f, 0)
        path.contains("leaves") || path.contains("vine") || path.contains("moss") ->
            Look(0.85f, 0f, FLAG_SUBSURFACE or FLAG_TWO_SIDED)
        path.contains("plant") || path.contains("flower") || path.contains("sapling") ||
            path.contains("fern") || path.contains("crop") || path.contains("wheat") ||
            path.contains("stem") || path.contains("grass") || path.contains("bush") ||
            path.contains("coral") || path.contains("lily") || path.contains("kelp") ||
            path.contains("seagrass") -> Look(0.85f, 0f, FLAG_SUBSURFACE or FLAG_TWO_SIDED)
        path.contains("stone") || path.contains("deepslate") || path.contains("andesite") ||
            path.contains("diorite") || path.contains("granite") || path.contains("basalt") ||
            path.contains("blackstone") || path.contains("cobble") || path.contains("brick") ||
            path.contains("concrete") || path.contains("terracotta") -> Look(0.8f, 0f, 0)
        path.contains("wool") || path.contains("carpet") || path.contains("planks") -> Look(0.9f, 0f, 0)
        else -> Look(DEFAULT_ROUGHNESS, 0f, 0)
    }

    private class Look(val roughness: Float, val emissive: Float, val flags: Int)

    /**
     * 逐 quad 材质索引：UV0 四角**平均**当作 quad 中心（四个角的 UV 都落在精灵边界上），。
     * 见 docs/rt/README.md
     */
    @JvmStatic
    fun quadMaterials(vertex: ByteBuffer, baseOffset: Int, quadCount: Int): ShortArray {
        val ids = ShortArray(quadCount)
        val s = snapshot ?: return ids
        if (s.uvOffset < 0 || s.colorOffset < 0 || s.stride < s.uvOffset + 8) {
            return ids
        }
        val view = vertex.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        for (q in 0 until quadCount) {
            var u = 0f
            var v = 0f
            var red = 0
            var green = 0
            var blue = 0
            var alpha = 0
            for (corner in 0 until 4) {
                val at = baseOffset + (q * 4 + corner) * s.stride
                u += view.getFloat(at + s.uvOffset)
                v += view.getFloat(at + s.uvOffset + 4)
                val argb = view.getInt(at + s.colorOffset)
                red += (argb ushr 16) and 0xFF
                green += (argb ushr 8) and 0xFF
                blue += argb and 0xFF
                alpha += (argb ushr 24) and 0xFF
            }
            val argb = ((alpha shr 2) and 0xFF shl 24) or
                ((red shr 2) and 0xFF shl 16) or
                ((green shr 2) and 0xFF shl 8) or
                ((blue shr 2) and 0xFF)
            ids[q] = materialIdOf(s, u * 0.25f, v * 0.25f, argb).toShort()
        }
        quadsClassified.addAndGet(quadCount.toLong())
        return ids
    }

    private fun materialIdOf(s: Snapshot, u: Float, v: Float, argb: Int): Int {
        val sprite = spriteAt(s, u, v)
        if (sprite < 0) {
            spriteMisses.incrementAndGet()
            return FALLBACK
        }
        val key = (sprite.toLong() shl 32) or (argb.toLong() and 0xFFFF_FFFFL)
        materialIds[key]?.let { return it }
        return appendEntry(s, sprite, key, argb)
    }

    private fun spriteAt(s: Snapshot, u: Float, v: Float): Int {
        val cell = cellIndex(v) * GRID + cellIndex(u)
        for (i in s.bucketStart[cell] until s.bucketStart[cell + 1]) {
            val index = s.bucketItems[i]
            val sprite = s.sprites[index] ?: continue
            if (u >= sprite.u0 && u <= sprite.u1 && v >= sprite.v0 && v <= sprite.v1) {
                return index
            }
        }
        return -1
    }

    private fun appendEntry(s: Snapshot, sprite: Int, key: Long, argb: Int): Int = synchronized(entryLock) {
        if (snapshot !== s) return FALLBACK
        materialIds[key]?.let { return it }
        val baked = s.sprites[sprite]
        if (baked == null || entryCount >= MAX_ENTRIES) {
            tableFull.incrementAndGet()
            return FALLBACK
        }
        val tintR = linear(((argb ushr 16) and 0xFF) / 255f).toFloat()
        val tintG = linear(((argb ushr 8) and 0xFF) / 255f).toFloat()
        val tintB = linear((argb and 0xFF) / 255f).toFloat()
        val index = entryCount++
        writeEntry(
            index,
            baked.albedoR * tintR, baked.albedoG * tintG, baked.albedoB * tintB,
            baked.albedoR * baked.emissive, baked.albedoG * baked.emissive, baked.albedoB * baked.emissive,
            baked.roughness, baked.metalness, baked.flags,
            baked.textureOffset,
        )
        materialIds[key] = index
        // 新条目必须让上传镜像重建，否则光追读到的材质缓冲停在首帧那份（弹射项 albedo 全 0）。
        revision.incrementAndGet()
        index
    }

    private fun writeEntry(index: Int, r: Float, g: Float, b: Float,
                           er: Float, eg: Float, eb: Float, roughness: Float,
                           metalness: Float, flags: Int, textureOffset: Int = 0) {
        val at = index * ENTRY_BYTES
        entries.putFloat(at, r)
        entries.putFloat(at + 4, g)
        entries.putFloat(at + 8, b)
        entries.putFloat(at + 12, er)
        entries.putFloat(at + 16, eg)
        entries.putFloat(at + 20, eb)
        entries.putFloat(at + 24, roughness)
        // flags 留在 +28、金属度追加在 +32：老偏移一个都不动，着色器只多读一个字。
        entries.putInt(at + 28, flags)
        entries.putFloat(at + 32, metalness)
        entries.putInt(at + 36, textureOffset)
        entries.position((index + 1) * ENTRY_BYTES)
    }

    /** 每次成功重烘 +1：材质上传镜像靠它判断要不要重传。 */
    @JvmStatic
    fun revision(): Long = revision.get()

    fun historyRevision(): Long = historyRevision.get()

    /** 材质记录的只读视图（`limit` = 已写字节数），给上传用。 */
    fun entryBytes(): ByteBuffer = synchronized(entryLock) {
        val records = entries.duplicate().order(ByteOrder.LITTLE_ENDIAN).also { it.flip() }
        val maps = snapshot?.textureBytes ?: byteArrayOf()
        val recordBytes = records.remaining()
        ByteBuffer.allocate(recordBytes + maps.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(records)
            for (index in 0 until entryCount) {
                val at = index * ENTRY_BYTES + 36
                val relative = getInt(at)
                if (relative != 0) putInt(at, recordBytes + relative - 1)
            }
            put(maps)
            flip()
        }
    }

    @JvmStatic
    fun entryCount(): Int = synchronized(entryLock) { entryCount }

    @JvmStatic
    fun summary(): String = "entries=${entryCount()} sprites=${spriteTotal.get()} " +
        "quads=${quadsClassified.get()} spriteMiss=${spriteMisses.get()} full=${tableFull.get()} " +
        "bakes=${bakes.get()} bakeUs=${bakeNanos.get() / 1000}"

    /** 世界切换或诊断重置后，下次 tick 重新烘焙材质表。 */
    @JvmStatic
    fun clear() {
        snapshot = null
        synchronized(entryLock) {
            materialIds.clear()
            entries.clear()
            entryCount = 1
            writeEntry(FALLBACK, DEFAULT_ALBEDO, DEFAULT_ALBEDO, DEFAULT_ALBEDO,
                0f, 0f, 0f, DEFAULT_ROUGHNESS, 0f, 0)
            revision.incrementAndGet()
            historyRevision.incrementAndGet()
        }
    }
}


