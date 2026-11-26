package cn.orzmc.realtime.paper.update

import org.bukkit.configuration.InvalidConfigurationException
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.io.IOException

/**
 * Remembers the last version the operator was told about, so a long-running server
 * or one that restarts often does not repeat the same notice.
 *
 * A broken or unwritable file must never break startup: it only costs one repeated
 * console line, so every failure is swallowed after logging.
 */
class UpdateStateStore(
    private val dataFolder: File,
    private val log: (String) -> Unit = {},
) {
    fun notifiedVersion(): String? {
        val file = stateFile() ?: return null
        if (!file.isFile) return null
        val yaml = YamlConfiguration()
        return try {
            yaml.load(file)
            yaml.getString("last-notified-version")?.takeIf { it.isNotBlank() }
        } catch (exception: IOException) {
            log("could not read $STATE_FILE: ${exception.message}")
            null
        } catch (exception: InvalidConfigurationException) {
            log("could not parse $STATE_FILE: ${exception.message}")
            null
        }
    }

    fun saveNotifiedVersion(version: String) {
        val file = stateFile() ?: return
        val yaml = YamlConfiguration()
        yaml.set("schema-version", SCHEMA_VERSION)
        yaml.set("last-notified-version", version)
        try {
            file.parentFile?.mkdirs()
            yaml.save(file)
        } catch (exception: IOException) {
            log("could not write $STATE_FILE: ${exception.message}")
        }
    }

    private fun stateFile(): File? = File(dataFolder, STATE_FILE)

    private companion object {
        const val STATE_FILE = "data/updates.yml"
        const val SCHEMA_VERSION = 1
    }
}
