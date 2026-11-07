package io.github.dxegp.experimental.raytracing

import com.mojang.blaze3d.platform.NativeImage
import io.github.dxegp.misc.IllusionLogger
import io.github.dxegp.misc.settings.D3D12Settings
import net.minecraft.client.Minecraft
import net.minecraft.resources.Identifier
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * labPBR 材质图接入（见 `docs/rt/README.md` §17）：按精灵名找 `_s` / `_n`，保留 ARGB 像素。
 *
 * 两个来源：`[materials] packPath` 指向的目录（开发期直连材质包仓库，发行版不带），或游戏里
 * 启用的资源包（玩家自己启用的 PBR 材质包）。两者都取不到时必须返回空的 [Look] 退化：材质表
 * 保留内置启发式，绝不能让「没有材质包」变成报错或全黑。
 */
internal object D3D12RtMaterialMaps {
    class Image(val width: Int, val height: Int, val pixels: IntArray)

    class Look(val spec: Image?, val normal: Image?, val albedo: Image? = null)

    private val NONE = Look(null, null)

    private val cache = ConcurrentHashMap<String, Look>()

    private var specHits = 0
    private var normalHits = 0
    private var looked = 0
    private var source = "off"

    /** 每次重烘前清空：图集换了就要重新解析一遍，缓存里的旧值不能再信。 */
    fun beginBake() {
        cache.clear()
        specHits = 0
        normalHits = 0
        looked = 0
        source = when {
            !D3D12Settings.materialsEnabled -> "off"
            D3D12Settings.materialsPackPath.isNotEmpty() -> "packPath"
            else -> "resources"
        }
    }

    fun summary(): String {
        val text = "rt materials: spec=$specHits normal=$normalHits of $looked from $source"
        IllusionLogger.infoMessage(text)
        return text
    }

    /**
     * 查一张精灵的材质图；`path` 是 `block/stone` 这种贴图路径（与图集精灵同名）。
     *
     * 没有映射时缓存空的 [Look]：一个世界几万个精灵，重复 IO 会把重烘拖成卡顿。
     */
    fun look(id: Identifier, frameWidth: Int, frameHeight: Int): Look {
        if (source == "off") return NONE
        val key = id.namespace + ":" + id.path
        cache[key]?.let { return it }
        looked++
        fun image(suffix: String): Image? = readMap(id, suffix)?.use { stream ->
            readImage(stream, frameWidth, frameHeight)
        }
        val spec = image("_s")
        val normal = image("_n")
        if (spec != null) specHits++
        if (normal != null) normalHits++
        val result = Look(spec, normal)
        cache[key] = result
        return result
    }

    /** 取一张图；返回的流由调用方关闭。找不到就是 `null`，不抛。 */
    private fun readMap(id: Identifier, suffix: String): InputStream? = runCatching {
        val packPath = D3D12Settings.materialsPackPath
        if (packPath.isNotEmpty()) {
            return@runCatching readFromDirectory(packPath, id, suffix)
        }
        val location = Identifier.fromNamespaceAndPath(id.namespace, "textures/" + id.path + suffix + ".png")
        val resource = Minecraft.getInstance().resourceManager.getResource(location)
        // Optional 为空说明这一层资源包没提供这张图：正常情况，不当异常。
        if (resource.isPresent) resource.get().open() else null
    }.getOrNull()

    private fun readFromDirectory(packPath: String, id: Identifier, suffix: String): InputStream? {
        val root = Path.of(packPath)
        val base = if (root.isAbsolute) root else Minecraft.getInstance().gameDirectory.toPath().resolve(root)
        val file = base.resolve("assets").resolve(id.namespace).resolve("textures")
            .resolve(id.path + suffix + ".png")
        return if (Files.isRegularFile(file)) Files.newInputStream(file) else null
    }

    private fun decode(stream: InputStream): NativeImage? =
        runCatching { NativeImage.read(stream) }.getOrNull()

    internal fun readImage(stream: InputStream, frameWidth: Int, frameHeight: Int): Image? =
        decode(stream)?.use { image ->
            val height = (image.width.toLong() * frameHeight / frameWidth).toInt().coerceIn(1, image.height)
            Image(image.width, height, IntArray(image.width * height) { index ->
                image.getPixel(index % image.width, index / image.width)
            })
        }

}
