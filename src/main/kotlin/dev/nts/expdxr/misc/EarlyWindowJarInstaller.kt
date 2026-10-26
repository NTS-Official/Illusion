package dev.nts.expdxr.misc

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
 * P270: keeps the early-window provider available to FML.
 *
 * FML only recognises early services in top-level `*.jar` files under `mods/` (`EarlyServiceDiscovery`
 * walks the mods folder and matches the `.jar` suffix), and a jar carrying one of those
 * `META-INF/services` entries is claimed and then excluded from mod discovery. The provider therefore
 * cannot live inside the mod jar, and cannot be a `META-INF/jarjar` dependency either: nested jars are
 * extracted long after `ImmediateWindowHandler.load()` has already run.
 *
 * The release stays a single download: the mod jar carries the provider jar as
 * `META-INF/dxrmc/earlywindow.jar`, and this object writes it to `mods/dxrmc-earlywindow.jar` and points
 * `config/fml.toml` at our provider. Both take effect on the next launch.
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

    /** Returns true when the jar on disk differs from the bundled copy (and was therefore (re)written). */
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
            // Write a sibling first: a half-written jar in mods/ would break the next launch.
            val temp = target.resolveSibling("$FILE_NAME.tmp")
            Files.write(temp, bundled)
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
            } catch (locked: Throwable) {
                // P271: the running JVM keeps the loaded provider jar open, so it cannot be replaced in place.
                // Move it aside (a stale jar the loader never rescans) and put the new one at the canonical path.
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

    /** Returns true when `fml.toml` had to be rewritten to select our provider. */
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
