package io.github.dxegp.experimental.raytracing

import com.google.gson.JsonParser
import io.github.dxegp.IllusionConfig
import io.github.dxegp.RtBackend
import io.github.dxegp.RtMode
import io.github.dxegp.misc.IllusionLogger
import java.io.File

/**
 * 光影包清单与当前选择。
 *
 * 两类来源：内置包（jar 里的 `assets/dxr_mc/shaders/<包>/`，清单由 `stageShaders` 写进 `packs.txt`）
 * 与用户包（`<工作目录>/dxrmc/shaderpacks/<包>/`）。目录形状与 Iris 一致：包根下有 `shaders/`，
 * 元数据放 `shaders/pack.json`。
 *
 * 清单只回答「用哪一份 HLSL」；能不能跑由 [Pack.missing] 判定——每个后端要的入口文件缺一个就建不出管线。
 * 见 docs/rt/README.md §11
 */
object D3D12ShaderPacks {
    /** 用户选择「无光影」时的取值。 */
    const val NONE: String = ""

    /** 清单读不出来时的兜底包名：跟随 jar 内置的默认包。 */
    const val DEFAULT_PACK: String = "B-DXR"

    private const val BUNDLED_ROOT = "assets/dxr_mc/shaders"
    private const val MANIFEST = "packs.txt"

    /** B-DXR 后端建管线要用的入口文件（相对包内的 `shaders/`）。 */
    private val REQUIRED_ENTRIES = listOf(
        "rt_dxr_lib.hlsl",
        "rt_resolve.hlsl",
        "rt_exposure.hlsl",
        "rt_debug.hlsl",
        "rt_gbuffer_ps.hlsl",
        "rt_material_guide.hlsl",
        "rt_nrd_remodulate.hlsl",
    )

    enum class Kind { BUNDLED, USER }

    /** 一个光影包。[missing] 为空才算能被 B-DXR 后端跑起来。 */
    class Pack(
        val name: String,
        val kind: Kind,
        val displayName: String,
        val author: String,
        val version: String,
        val description: String,
        val missing: List<String>,
        val directory: File?,
    ) {
        val compatible: Boolean get() = missing.isEmpty()
    }

    private var cache: List<Pack>? = null
    private var warnedName: String? = null

    /** 全部包（内置在前、用户包在后；同名时内置优先）。每次换包后要 [reload]。 */
    fun all(): List<Pack> = cache ?: discover().also { cache = it }

    /** 重新扫盘：用户往 `dxrmc/shaderpacks/` 里丢了新包之后调。 */
    fun reload(): List<Pack> {
        cache = null
        D3D12RtShaderSources.invalidate()
        return all()
    }

    /**
     * 当前生效的包名：永远返回一个**读得到 HLSL** 的名字，光追关着也一样（着色器入口在设备建好时就要解析）。
     * 配置里写了不存在或缺入口的包时回落到 [DEFAULT_PACK]，并且只警告一次。
     */
    fun activeName(): String {
        val configured = configured()
        if (configured.isEmpty()) return DEFAULT_PACK
        val pack = all().firstOrNull { it.name == configured }
        if (pack == null || !pack.compatible) {
            if (warnedName != configured) {
                warnedName = configured
                IllusionLogger.warnMessage(
                    "shaderpack: \"$configured\" is not usable (${reason(pack)}), using $DEFAULT_PACK",
                )
            }
            return DEFAULT_PACK
        }
        return configured
    }

    /** 用户在界面里选的那个（`NONE` = 无光影），与 [activeName] 的区别是允许「没选包」。 */
    fun selected(): String = configured()

    /** 包内着色器的加载键（相对 `assets/dxr_mc/shaders/`），例如 `B-DXR/shaders/rt_dxr_lib.hlsl`。 */
    fun sourcePath(file: String): String = "${activeName()}/shaders/$file"

    /**
     * 应用一个选择：包 → `[shaderpack] active` + 包声明的 `[rt] backend` 与 `mode`；
     * 无光影 → 清空 active + `backend = AUTO` + `mode = OFF`。返回是否真的改了东西。
     *
     * `mode` 与 `backend` 一起写：档位是包自己的开关（`configs.json` 的 `mode` 默认 `FULL`），
     * 只写 backend 会让「档位 OFF」把刚选中的包又关掉，只写 mode 则区分不出无光影。
     */
    fun apply(name: String): Boolean {
        val target = if (name == NONE) {
            NONE
        } else {
            val pack = requireNotNull(all().firstOrNull { it.name == name }) { "unknown shaderpack: $name" }
            require(pack.compatible) { "shaderpack $name is missing ${pack.missing.joinToString()}" }
            name
        }
        val backend = if (target == NONE) RtBackend.AUTO else declaredBackend(target)
        val mode = if (target == NONE) RtMode.OFF else declaredMode(target)
        val changed = configured() != target ||
            IllusionConfig.RAYTRACING_BACKEND.get() != backend ||
            IllusionConfig.RAYTRACING_MODE.get() != mode
        IllusionConfig.SHADERPACK_ACTIVE.set(target)
        IllusionConfig.RAYTRACING_BACKEND.set(backend)
        IllusionConfig.RAYTRACING_MODE.set(mode)
        if (IllusionConfig.SPEC.isLoaded) IllusionConfig.SPEC.save()
        D3D12RtShaderSources.invalidate()
        if (changed) {
            // 换了包就等于换了着色器源码：上一个包通过的自检结论对新包无效，下一次打开光追要重跑。
            D3D12RtTrace.resetSelfTest()
            IllusionLogger.infoMessage(
                "shaderpack: applied \"${target.ifEmpty { "none" }}\" " +
                    "backend=${backend.name} mode=${mode.name}"
            )
        }
        return changed
    }

    /** 「打开文件夹」按钮的目标目录（不存在就建）。 */
    fun packsDirectory(): File = D3D12RtShaderSources.userRootOrCreate()

    /** 用系统文件管理器打开包目录。返回是否成功；两个入口（设置页与包列表页）共用这一处实现。 */
    fun openDirectory(): Boolean {
        val directory = packsDirectory()
        val os = System.getProperty("os.name").lowercase()
        val command = when {
            os.contains("win") -> listOf("explorer.exe", directory.absolutePath)
            os.contains("mac") -> listOf("open", directory.absolutePath)
            else -> listOf("xdg-open", directory.absolutePath)
        }
        return try {
            ProcessBuilder(command).start()
            true
        } catch (failure: Exception) {
            IllusionLogger.warnMessage("shaderpack: open failed ($failure)")
            false
        }
    }

    private fun configured(): String =
        if (!IllusionConfig.SPEC.isLoaded) DEFAULT_PACK else IllusionConfig.SHADERPACK_ACTIVE.get().trim()

    /** 包在 `configs.json` 里给 `backend` 声明的值；读不到或写坏一律 DXR。 */
    private fun declaredBackend(pack: String): RtBackend {
        val attribute = D3D12PackConfig.attributes(pack).firstOrNull { it.configKey == "rt/backend" }
            ?: return RtBackend.DXR
        // 只认包明确表过的值：否则会把上一个包留下的全局 backend 当成这个包的声明。
        val declared = D3D12PackConfig.storedValue(attribute, pack) ?: attribute.defaultText
        return RtBackend.entries.firstOrNull { it.name == declared } ?: RtBackend.DXR
    }

    /** 包在 `configs.json` 里给 `mode` 声明的档位；读不到或写坏一律 FULL（选中包就是要出图）。 */
    private fun declaredMode(pack: String): RtMode {
        val attribute = D3D12PackConfig.attributes(pack).firstOrNull { it.configKey == "rt/mode" }
            ?: return RtMode.FULL
        val declared = D3D12PackConfig.storedValue(attribute, pack) ?: attribute.defaultText
        return RtMode.entries.firstOrNull { it.name == declared } ?: RtMode.FULL
    }

    /**
     * 把 `[rt] backend` / `mode` 拉回当前包声明的值，返回是否改动过。
     *
     * 只有在「已经选了包、但这两个键与包声明不一致」时才有动作：老版本只写 backend，升级后 `mode` 会
     * 停在 `OFF`，画面看起来就是「选了包但光追没开」。包是这两个键的唯一来源，所以这里按声明覆盖，
     * 并打一行说明是被谁改回来的。
     */
    fun reconcile(): Boolean {
        val pack = configured()
        if (pack == NONE || !IllusionConfig.SPEC.isLoaded) return false
        if (all().none { it.name == pack && it.compatible }) return false
        val backend = declaredBackend(pack)
        val mode = declaredMode(pack)
        val current = Pair(
            IllusionConfig.RAYTRACING_BACKEND.get(),
            IllusionConfig.RAYTRACING_MODE.get(),
        )
        if (current == Pair(backend, mode)) return false
        IllusionConfig.RAYTRACING_BACKEND.set(backend)
        IllusionConfig.RAYTRACING_MODE.set(mode)
        IllusionConfig.SPEC.save()
        IllusionLogger.infoMessage("shaderpack: reconciled $pack (${backend.name}, ${mode.name})")
        return true
    }

    private fun discover(): List<Pack> {
        val packs = ArrayList<Pack>()
        bundledNames().forEach { name -> packs += bundled(name) }
        D3D12RtShaderSources.userRoot()?.listFiles().orEmpty()
            .filter { it.isDirectory && it.name.isNotBlank() }
            .sortedBy { it.name.lowercase() }
            .forEach { dir -> if (packs.none { it.name == dir.name }) packs += user(dir) }
        if (packs.isEmpty()) packs += bundled(DEFAULT_PACK)
        return packs
    }

    private fun bundledNames(): List<String> {
        val text = D3D12RtShaderSources.bundledText(MANIFEST) ?: return listOf(DEFAULT_PACK)
        return text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .toList()
    }

    private fun bundled(name: String): Pack {
        val meta = D3D12RtShaderSources.bundledText("$name/shaders/pack.json")?.let(::parseMeta)
        val missing = REQUIRED_ENTRIES.filterNot { D3D12RtShaderSources.bundledExists("$name/shaders/$it") }
        return Pack(
            name = name,
            kind = Kind.BUNDLED,
            displayName = meta?.name?.ifEmpty { name } ?: name,
            author = meta?.author.orEmpty(),
            version = meta?.version.orEmpty(),
            description = meta?.description.orEmpty(),
            missing = missing,
            directory = null,
        )
    }

    private fun user(dir: File): Pack {
        val meta = listOf(File(dir, "shaders/pack.json"), File(dir, "pack.json"))
            .firstOrNull { it.isFile }
            ?.let { parseMeta(it.readText()) }
        val missing = REQUIRED_ENTRIES.filterNot { File(dir, "shaders/$it").isFile }
        return Pack(
            name = dir.name,
            kind = Kind.USER,
            displayName = meta?.name?.ifEmpty { dir.name } ?: dir.name,
            author = meta?.author.orEmpty(),
            version = meta?.version.orEmpty(),
            description = meta?.description.orEmpty(),
            missing = missing,
            directory = dir,
        )
    }

    private class Meta(val name: String, val author: String, val version: String, val description: String)

    /** Iris 的 `pack.json` 只保证有 `shaderDescription`，其余键都是可选。 */
    private fun parseMeta(text: String): Meta? = try {
        val json = JsonParser.parseString(text).asJsonObject
        fun field(vararg keys: String): String = keys.firstNotNullOfOrNull { key ->
            json.get(key)?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.ifEmpty { null }
        }.orEmpty()
        Meta(
            name = field("name", "shaderName"),
            author = field("author", "shaderAuthor"),
            version = field("version", "shaderVersion"),
            description = field("shaderDescription", "description"),
        )
    } catch (failure: RuntimeException) {
        IllusionLogger.debugMessage("shaderpack: unreadable pack.json ($failure)")
        null
    }

    private fun reason(pack: Pack?): String = when {
        pack == null -> "not found"
        else -> "missing ${pack.missing.joinToString()}"
    }
}


