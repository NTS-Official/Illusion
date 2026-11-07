package io.github.dxegp.experimental.raytracing

import com.electronwill.nightconfig.core.Config
import com.electronwill.nightconfig.core.file.CommentedFileConfig
import com.google.gson.JsonParser
import io.github.dxegp.misc.IllusionLogger
import java.nio.file.Files
import java.nio.file.Path

internal object D3D12RtConfigMigration {
    fun migrate(path: Path) {
        if (!Files.isRegularFile(path)) return
        try {
            CommentedFileConfig.builder(path).sync().preserveInsertionOrder().build().use { config ->
                config.load()
                migratePackSettings(path.parent.parent.resolve("dxrmc/shaderpacks"))
                if (migrateValues(config)) {
                    config.save()
                    IllusionLogger.infoMessage("rt config: migrated to hardware tracing")
                }
            }
        } catch (failure: Exception) {
            IllusionLogger.warnMessage("rt config: migration failed (${failure.javaClass.simpleName})")
        }
    }

    private fun migratePackSettings(root: Path) {
        val previous = root.resolve("B-Mega/settings.json")
        val current = root.resolve("B-DXR/settings.json")
        if (!Files.isRegularFile(previous) || Files.exists(current)) return
        val settings = JsonParser.parseString(Files.readString(previous)).asJsonObject
        settings.addProperty("backend", "DXR")
        settings.remove("asTrace")
        Files.createDirectories(current.parent)
        Files.writeString(current, settings.toString())
    }

    fun migrateValues(config: Config): Boolean {
        var changed = false
        for ([key, oldValue, newValue] in listOf(
            Triple("rt.backend", "MEGA", "DXR"),
            Triple("rt.denoise", "MEGAKERNEL", "RAW"),
            Triple("shaderpack.active", "B-Mega", "B-DXR"),
        )) {
            if (config.get<Any?>(key)?.toString()?.trim()?.equals(oldValue, ignoreCase = true) == true) {
                config.set<Any>(key, newValue)
                changed = true
            }
        }
        if (config.contains("rt.asTrace")) {
            config.remove<Any>("rt.asTrace")
            changed = true
        }
        return changed
    }
}
