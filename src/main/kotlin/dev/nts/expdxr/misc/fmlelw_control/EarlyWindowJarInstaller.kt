package dev.nts.expdxr.misc.fmlelw_control

import dev.nts.expdxr.Illusion
import net.neoforged.fml.loading.FMLPaths
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * 保证 early-window provider 对 FML 可用。
 *
 * FML 只在 `mods/` 下的**顶层 `*.jar`** 里找早期服务，且带这类 `META-INF/services` 条目的 jar 会被从
 * 模组发现中排除 —— provider 放进模组 jar，模组自己就会消失；`META-INF/jarjar` 也不行（解压晚于
 * `ImmediateWindowHandler.load()`）。所以主 jar 内嵌 `META-INF/dxrmc/earlywindow.jar`，这里解到 `mods/`
 * 并把 `fml.toml` 指向它；两条都要重启一次才生效。
 * 见 docs/misc/mixins-and-earlywindow.md §5.5
 */
object EarlyWindowJarInstaller {
    private const val RESOURCE = "/META-INF/dxrmc/earlywindow.jar"
    private const val FILE_NAME = "dxrmc-earlywindow.jar"
    private const val PROVIDER_NAME = "dxrmc"
    private const val TOML_KEY = "earlyWindowProvider"

    fun ensureInstalled() {
        val target = FMLPaths.GAMEDIR.get().resolve("mods").resolve(FILE_NAME)
        val installed = installProviderJar(target)
        val pointed = pointConfigAtProvider(FMLPaths.CONFIGDIR.get().resolve("fml.toml"))
        val tag = "[${Illusion.MODID}]"
        when {
            installed && pointed -> Illusion.LOGGER.info(
                "$tag early-window provider unpacked into mods/; restart the game once so FML picks up the single-window path"
            )
            installed -> Illusion.LOGGER.info(
                "$tag early-window provider unpacked into mods/ (fml.toml already selects it)"
            )
            pointed -> Illusion.LOGGER.info(
                "$tag fml.toml now selects the dxrmc early window provider; restart the game once"
            )
        }
    }

    /** 磁盘内容与内嵌副本不同才写盘；返回值表示是否写过。 */
    private fun installProviderJar(target: Path): Boolean {
        val bundled = try {
            EarlyWindowJarInstaller::class.java.getResourceAsStream(RESOURCE)?.use { it.readBytes() }
        } catch (t: Throwable) {
            Illusion.LOGGER.warn("[${Illusion.MODID}] cannot read the bundled early-window jar: $t")
            null
        } ?: run {
            Illusion.LOGGER.warn("[${Illusion.MODID}] bundled early-window jar is missing from this build")
            return false
        }

        if (target.isRegularFile() && target.readBytes().contentEquals(bundled)) {
            return false
        }
        return try {
            Files.createDirectories(target.parent)
            // 先写同目录 .tmp 再 move：mods/ 里半写的 jar 会让下一次启动失败。
            val temp = target.resolveSibling("$FILE_NAME.tmp")
            Files.write(temp, bundled)
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
            } catch (locked: Throwable) {
                // 旧 jar 被本进程占用、无法原地替换：挪成 .stale（loader 不会重扫），再把新的放到正式路径。
                val stale = target.resolveSibling("dxrmc-earlywindow.stale")
                Files.move(target, stale, StandardCopyOption.REPLACE_EXISTING)
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
                Illusion.LOGGER.info(
                    "[${Illusion.MODID}] old early-window provider was locked by this process; replaced via $stale"
                )
            }
            true
        } catch (t: Throwable) {
            Illusion.LOGGER.warn("[${Illusion.MODID}] cannot install ${target}: $t")
            false
        }
    }

    /** `fml.toml` 不存在时不动（不创建）；返回值表示是否改写。 */
    private fun pointConfigAtProvider(config: Path): Boolean {
        if (!config.exists()) {
            return false
        }
        return try {
            val text = config.readText()
            val wanted = "$TOML_KEY = \"$PROVIDER_NAME\""
            if (Regex("(?m)^\\s*$TOML_KEY\\s*=\\s*\"$PROVIDER_NAME\"\\s*$").containsMatchIn(text)) {
                return false
            }
            val pattern = Regex("(?m)^\\s*$TOML_KEY\\s*=")
            val rewritten = if (pattern.containsMatchIn(text)) {
                text.replace(Regex("(?m)^\\s*$TOML_KEY\\s*=.*$"), wanted)
            } else {
                text.trimEnd() + "\\n" + wanted + "\\n"
            }
            config.writeText(rewritten)
            true
        } catch (t: Throwable) {
            Illusion.LOGGER.warn("[${Illusion.MODID}] cannot update ${config}: $t")
            false
        }
    }
}
