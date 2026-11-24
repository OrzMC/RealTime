package cn.orzmc.realtime.paper.device

import org.bukkit.configuration.InvalidConfigurationException
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.io.IOException

class RedstoneConfigurationLoader {
    fun load(dataFolder: File): RedstoneConfiguration {
        val file = File(dataFolder, CONFIG_FILE)
        if (!file.isFile) {
            throw DeviceRepositoryException("$CONFIG_FILE is missing")
        }

        val yaml = YamlConfiguration()
        try {
            yaml.load(file)
        } catch (exception: IOException) {
            throw DeviceRepositoryException("Unable to read $CONFIG_FILE", exception)
        } catch (exception: InvalidConfigurationException) {
            throw DeviceRepositoryException("Unable to parse $CONFIG_FILE: ${exception.message}", exception)
        }

        val enabled =
            yaml.get("enabled") as? Boolean
                ?: throw DeviceRepositoryException("$CONFIG_FILE: enabled must be true or false")
        // `output-power` from pre-release builds is ignored: outputs are full-strength
        // power sources, so there is no per-output strength to configure.
        return RedstoneConfiguration(enabled)
    }

    private companion object {
        const val CONFIG_FILE = "modules/redstone.yml"
    }
}
