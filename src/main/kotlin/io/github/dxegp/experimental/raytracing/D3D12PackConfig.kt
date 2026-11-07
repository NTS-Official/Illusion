package io.github.dxegp.experimental.raytracing

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.github.dxegp.IllusionConfig
import io.github.dxegp.misc.IllusionLogger
import io.github.dxegp.misc.settings.D3D12Settings
import java.io.File

/**
 * 光影包自己声明的设置项（`shaders/configs.json` 的 `attributes`）。
 *
 * 光追相关的开关属于**包**：同一个键在不同包里含义可以不同，所以界面挂在
 * 「视频设置 → 光影包 → 光影包设置」，不放在全局配置里。见 docs/rt/README.md 11.6。
 */
internal class PackAttribute(
    /** 稳定 id，同时也是 `settings.json` 里的键。 */
    val name: String,
    /** 说明文案：`foo.bar` 形态当语言键，否则当字面量。 */
    val description: String,
    val kind: Kind,
    val min: Double,
    val max: Double,
    /** 枚举取值（就是 TOML 里的常量名，不翻译）。 */
    val choices: List<String>,
    val defaultText: String,
    val group: String,
    /** 绑到的 TOML 键（`<section>/<key>`）；为空表示只记进包的 `settings.json`。 */
    val configKey: String?,
    val impact: String,
) {
    enum class Kind { BOOL, INT_RANGE, FLOAT_RANGE, ENUM }

    /** 滑块一步改多少：整数档一律 1。 */
    val step: Double
        get() = if (kind == Kind.INT_RANGE) 1.0 else (max - min) / 100.0

    val isSlider: Boolean get() = kind == Kind.INT_RANGE || kind == Kind.FLOAT_RANGE
}

internal object D3D12PackConfig {
    private const val CONFIG_FILE = "configs.json"
    private const val SETTINGS_FILE = "settings.json"
    private const val USER_ROOT = "dxrmc/shaderpacks"

    private var cachedPack: String? = null
    private var cached: List<PackAttribute> = emptyList()

    /** 某个包声明的设置项；没有 `configs.json` 或写坏了就是空表（界面会说明）。 */
    fun attributes(pack: String = D3D12ShaderPacks.activeName()): List<PackAttribute> {
        if (cachedPack == pack) return cached
        val parsed = runCatching { parse(pack) }.getOrElse { error ->
            IllusionLogger.warnMessage("shaderpack: configs unreadable (${error.message})")
            emptyList()
        }
        cachedPack = pack
        cached = parsed
        return parsed
    }

    fun invalidate() {
        cachedPack = null
        cached = emptyList()
    }

    /** 分组顺序按首次出现的先后，组内保持文件顺序。 */
    fun groups(pack: String = D3D12ShaderPacks.activeName()): List<Pair<String, List<PackAttribute>>> {
        val order = LinkedHashMap<String, MutableList<PackAttribute>>()
        for (attribute in attributes(pack)) {
            order.getOrPut(attribute.group) { ArrayList() } += attribute
        }
        return order.map { [group, list] -> group to list.toList() }
    }

    /** 当前值：先看包的 `settings.json`，再看它绑的 TOML 键，最后用默认值。 */
    fun value(attribute: PackAttribute, pack: String = D3D12ShaderPacks.activeName()): String =
        stored(pack)[attribute.name] ?: readConfig(attribute.configKey) ?: attribute.defaultText

    /** 只读包自己的 `settings.json`（不含 TOML 与默认值）：用于判断「这个包有没有明确表过态」。 */
    fun storedValue(attribute: PackAttribute, pack: String = D3D12ShaderPacks.activeName()): String? =
        stored(pack)[attribute.name]

    fun bool(attribute: PackAttribute): Boolean = value(attribute) == "true"

    fun number(attribute: PackAttribute): Double = value(attribute).toDoubleOrNull() ?: 0.0

    fun int(attribute: PackAttribute): Int = number(attribute).toInt()

    /** 枚举当前落在第几个取值；写坏的值回落 0（不改文件）。 */
    fun enumIndex(attribute: PackAttribute): Int =
        attribute.choices.indexOf(value(attribute)).coerceAtLeast(0)

    fun set(attribute: PackAttribute, text: String, pack: String = D3D12ShaderPacks.activeName()) {
        val stored = LinkedHashMap(stored(pack))
        stored[attribute.name] = text
        writeSettings(pack, stored)
        writeConfig(attribute.configKey, text)
    }

    fun reset(attribute: PackAttribute, pack: String = D3D12ShaderPacks.activeName()) {
        val stored = LinkedHashMap(stored(pack))
        stored.remove(attribute.name)
        writeSettings(pack, stored)
        writeConfig(attribute.configKey, attribute.defaultText)
    }

    // ---- configs.json ----

    private fun parse(pack: String): List<PackAttribute> {
        val relative = "$pack/shaders/$CONFIG_FILE"
        val text = D3D12RtShaderSources.bundledText(relative)
            ?: userFile(pack, CONFIG_FILE).takeIf { it.isFile }?.readText()
            ?: return emptyList()
        val root = JsonParser.parseString(text).asJsonObject
        val attributes = root.getAsJsonArray("attributes") ?: return emptyList()
        val parsed = ArrayList<PackAttribute>(attributes.size())
        for (element in attributes) {
            val json = element.takeIf { it.isJsonObject }?.asJsonObject ?: continue
            val attribute = attribute(json) ?: continue
            parsed += attribute
        }
        return parsed.toList()
    }

    private fun attribute(json: JsonObject): PackAttribute? {
        val name = json.string("name") ?: return null
        val type = json.string("type") ?: return null
        val description = json.string("description") ?: name
        val group = json.string("group") ?: ""
        val configKey = json.string("key")?.replace(':', '/')
        val defaultText = json.string("default_value") ?: defaultFor(type)
        val impact = json.string("performance_impact") ?: ""
        val spec = type.substringAfter(':', "")
        return when {
            type == "bool" -> PackAttribute(
                name, description, PackAttribute.Kind.BOOL, 0.0, 0.0, emptyList(),
                defaultText, group, configKey, impact,
            )
            type.startsWith("int_range") ->
                range(name, description, spec, defaultText, group, configKey, impact, false)
            type.startsWith("float_range") ->
                range(name, description, spec, defaultText, group, configKey, impact, true)
            type.startsWith("enum") -> PackAttribute(
                name, description, PackAttribute.Kind.ENUM, 0.0, 0.0,
                spec.split('-').filter { it.isNotEmpty() }, defaultText, group, configKey, impact,
            )
            // vec3 之类的先不支持：包可以声明，界面跳过（见 docs/rt/README.md 11.6）。
            else -> null
        }
    }

    private fun range(
        name: String,
        description: String,
        spec: String,
        defaultText: String,
        group: String,
        configKey: String?,
        impact: String,
        floating: Boolean,
    ): PackAttribute? {
        val bounds = spec.split('-').mapNotNull { it.trim().toDoubleOrNull() }
        if (bounds.size < 2) return null
        val kind = if (floating) PackAttribute.Kind.FLOAT_RANGE else PackAttribute.Kind.INT_RANGE
        return PackAttribute(
            name, description, kind, bounds[0], bounds[1], emptyList(), defaultText, group, configKey, impact,
        )
    }

    private fun defaultFor(type: String): String = when {
        type == "bool" -> "false"
        type.startsWith("int_range") || type.startsWith("float_range") ->
            type.substringAfter(':', "").split('-').firstOrNull()?.trim() ?: "0"
        type.startsWith("enum") -> type.substringAfter(':', "").split('-').firstOrNull() ?: ""
        else -> ""
    }

    // ---- 每个包的 settings.json ----

    private fun stored(pack: String): Map<String, String> {
        val file = userFile(pack, SETTINGS_FILE)
        if (!file.isFile) return emptyMap()
        return runCatching {
            val root = JsonParser.parseString(file.readText()).asJsonObject
            root.entrySet().associate { [key, value] -> key to value.asString }
        }.getOrElse { error ->
            IllusionLogger.warnMessage("shaderpack: settings unreadable (${error.message})")
            emptyMap()
        }
    }

    private fun writeSettings(pack: String, values: Map<String, String>) {
        val file = userFile(pack, SETTINGS_FILE)
        runCatching {
            file.parentFile?.mkdirs()
            val json = JsonObject()
            for ([key, value] in values) json.addProperty(key, value)
            file.writeText(json.toString())
        }.onFailure { error ->
            IllusionLogger.warnMessage("shaderpack: write failed (${error.message})")
        }
    }

    private fun userFile(pack: String, name: String): File = File(File(userRoot(), pack), name)

    private fun userRoot(): File = File(System.getProperty("user.dir"), USER_ROOT)

    // ---- 绑到 TOML 的键 ----

    private fun readConfig(key: String?): String? {
        if (key == null || !IllusionConfig.SPEC.isLoaded) return null
        return runCatching {
            when (key) {
                "rt/mode" -> IllusionConfig.RAYTRACING_MODE.get().name
                "rt/backend" -> IllusionConfig.RAYTRACING_BACKEND.get().name
                "rt/qualityProfile" -> IllusionConfig.RAYTRACING_QUALITY_PROFILE.get().name
                "rt/samplingMode" -> IllusionConfig.RAYTRACING_SAMPLING_MODE.get().name
                "rt/debugView" -> IllusionConfig.RAYTRACING_DEBUG_VIEW.get().name
                "rt/spp" -> IllusionConfig.RAYTRACING_SPP.get().toString()
                "rt/maxBounces" -> IllusionConfig.RAYTRACING_MAX_BOUNCES.get().toString()
                "rt/historyFrames" -> IllusionConfig.RAYTRACING_HISTORY_FRAMES.get().toString()
                "rt/voxelTrustRadius" -> IllusionConfig.RAYTRACING_VOXEL_TRUST_RADIUS.get().toString()
                "rt/hzb" -> IllusionConfig.RAYTRACING_HZB.get().toString()
                "rt/invalidationLog" -> IllusionConfig.RAYTRACING_INVALIDATION_LOG.get().toString()
                else -> null
            }
        }.getOrNull()
    }

    private fun writeConfig(key: String?, text: String) {
        if (key == null || !IllusionConfig.SPEC.isLoaded) return
        runCatching {
            when (key) {
                "rt/mode" ->
                    IllusionConfig.RAYTRACING_MODE.set(enumOf(text, IllusionConfig.RAYTRACING_MODE.get()))
                "rt/backend" ->
                    IllusionConfig.RAYTRACING_BACKEND.set(enumOf(text, IllusionConfig.RAYTRACING_BACKEND.get()))
                "rt/qualityProfile" ->
                    IllusionConfig.RAYTRACING_QUALITY_PROFILE.set(enumOf(text, IllusionConfig.RAYTRACING_QUALITY_PROFILE.get()))
                "rt/samplingMode" ->
                    IllusionConfig.RAYTRACING_SAMPLING_MODE.set(enumOf(text, IllusionConfig.RAYTRACING_SAMPLING_MODE.get()))
                "rt/debugView" ->
                    IllusionConfig.RAYTRACING_DEBUG_VIEW.set(enumOf(text, IllusionConfig.RAYTRACING_DEBUG_VIEW.get()))
                "rt/spp" -> text.toIntOrNull()?.let { IllusionConfig.RAYTRACING_SPP.set(it) }
                "rt/maxBounces" -> text.toIntOrNull()?.let { IllusionConfig.RAYTRACING_MAX_BOUNCES.set(it) }
                "rt/historyFrames" ->
                    text.toIntOrNull()?.let { IllusionConfig.RAYTRACING_HISTORY_FRAMES.set(it) }
                "rt/voxelTrustRadius" ->
                    text.toDoubleOrNull()?.let { IllusionConfig.RAYTRACING_VOXEL_TRUST_RADIUS.set(it) }
                "rt/hzb" -> text.toBooleanStrictOrNull()?.let { IllusionConfig.RAYTRACING_HZB.set(it) }
                "rt/invalidationLog" ->
                    text.toBooleanStrictOrNull()?.let { IllusionConfig.RAYTRACING_INVALIDATION_LOG.set(it) }
                else -> return@runCatching
            }
            // 落盘 + 立刻热生效：设置页改完不该等下一次启动。
            IllusionConfig.SPEC.save()
            D3D12Settings.reload()
        }.onFailure { error ->
            IllusionLogger.warnMessage("shaderpack: apply failed (${error.message})")
        }
    }

    private inline fun <reified T : Enum<T>> enumOf(text: String, fallback: T): T =
        enumValues<T>().firstOrNull { it.name == text } ?: fallback

    private fun JsonObject.string(name: String): String? {
        val element = get(name) ?: return null
        return if (element.isJsonPrimitive) element.asString else null
    }
}
