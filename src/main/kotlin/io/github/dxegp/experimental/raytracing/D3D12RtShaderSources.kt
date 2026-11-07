package io.github.dxegp.experimental.raytracing

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 光追着色器的源码加载器：先看磁盘上的光影包，再回落到 jar 内置的包。
 *
 * 查找顺序是 `dxrmc/shaders/<relative>`（单文件覆盖，调试用）→ `dxrmc/shaderpacks/<relative>`
 * （用户包，`relative` 以包名开头）→ `classpath:/assets/dxr_mc/shaders/<relative>`（内置包）。
 * 见 docs/rt/README.md §11
 */
object D3D12RtShaderSources {
    /** jar 内的根：`assets/dxr_mc/shaders/<relative>`。 */
    private const val RESOURCE_ROOT = "assets/dxr_mc/shaders"

    /** 用户包根：`<工作目录>/dxrmc/shaderpacks/<relative>`。 */
    const val USER_ROOT = "dxrmc/shaderpacks"

    /** 单文件覆盖根：`<工作目录>/dxrmc/shaders/<relative>`。 */
    private const val OVERRIDE_ROOT = "dxrmc/shaders"

    /** include 嵌套上限：契约头最多一层，留点余量给后续拆文件。 */
    private const val MAX_DEPTH = 8

    private val INCLUDE = Regex("^\\s*#include\\s+\"([^\"]+)\"\\s*$", RegexOption.MULTILINE)
    private val cache = ConcurrentHashMap<String, String>()

    /**
     * 读一份着色器源码（相对 `assets/dxr_mc/shaders/`，例如 `B-DXR/shaders/rt_dxr_lib.hlsl`），
     * `#include` 已展开。找不到就抛 [IllegalStateException]，消息里列出找过的位置。
     */
    fun load(relative: String): String = cache.computeIfAbsent(relative) { expand(relative, 0) }

    /** 读一个「片段」文件（要塞进生成式着色器的 HLSL 小块）：丢掉纯注释行与空行，保留缩进。 */
    fun loadSnippet(relative: String): String =
        load(relative).lineSequence()
            .filter { line -> line.isNotBlank() && !line.trimStart().startsWith("//") }
            .joinToString("\n")

    /** 丢掉缓存：改了磁盘上的 HLSL 或换了包之后重新 [load]。 */
    fun invalidate() {
        cache.clear()
    }

    /** 内置资源是否存在；[relative] 相对 `assets/dxr_mc/shaders/`。 */
    fun bundledExists(relative: String): Boolean = javaClass.getResource("/$RESOURCE_ROOT/$relative") != null

    /** 读内置资源，不存在返回 null。 */
    fun bundledText(relative: String): String? =
        javaClass.getResourceAsStream("/$RESOURCE_ROOT/$relative")?.use { it.readBytes().toString(Charsets.UTF_8) }

    /** 用户包目录（`<工作目录>/dxrmc/shaderpacks`），不存在就是 `null`。 */
    fun userRoot(): File? = File(root(), USER_ROOT).takeIf { it.isDirectory }

    /** 用户包目录，不存在就建一个（「打开文件夹」按钮用）。 */
    fun userRootOrCreate(): File = File(root(), USER_ROOT).apply { if (!isDirectory) mkdirs() }

    private fun expand(relative: String, depth: Int): String {
        val text = read(relative)
        if (depth >= MAX_DEPTH) return text
        return INCLUDE.replace(text) { match ->
            val name = match.groupValues[1]
            val resolved = resolve(name, relative)
                ?: throw IllegalStateException(
                    "shader include not found: $name (included by $relative); " +
                        "looked next to it, in <its dir>/include, and in include/",
                )
            expand(resolved, depth + 1)
        }
    }

    private fun resolve(name: String, from: String): String? {
        // 包里的 shader 可以放在任意子目录（B-DXR/shaders/），include 既有同目录也有该目录下的 include/。
        val directory = from.substringBeforeLast('/', "")
        val candidates = buildList {
            if (directory.isNotEmpty()) {
                add("$directory/$name")
                add("$directory/include/$name")
            }
            add(name)
            add("include/$name")
        }
        return candidates.firstOrNull { exists(it) }
    }

    private fun exists(relative: String): Boolean =
        overrideFile(relative).isFile || userFile(relative).isFile || bundledExists(relative)

    private fun read(relative: String): String {
        val override = overrideFile(relative)
        if (override.isFile) return override.readText()
        val user = userFile(relative)
        if (user.isFile) return user.readText()
        bundledText(relative)?.let { return it }
        throw IllegalStateException(
            "shader source not found: $relative; looked in ${override.absolutePath}, " +
                "${user.absolutePath} and classpath:/$RESOURCE_ROOT/$relative",
        )
    }

    private fun overrideFile(relative: String): File = File(File(root(), OVERRIDE_ROOT), path(relative))

    private fun userFile(relative: String): File = File(File(root(), USER_ROOT), path(relative))

    private fun root(): File = File(System.getProperty("user.dir") ?: ".")

    private fun path(relative: String): String = relative.replace('/', File.separatorChar)
}


