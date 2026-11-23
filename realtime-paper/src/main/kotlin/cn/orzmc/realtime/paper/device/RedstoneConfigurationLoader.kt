package cn.orzmc.realtime.paper.device

import java.io.File
import java.io.IOException
import org.bukkit.configuration.InvalidConfigurationException
import org.bukkit.configuration.file.YamlConfiguration

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

        val enabled = yaml.get("enabled") as? Boolean
            ?: throw DeviceRepositoryException("$CONFIG_FILE: enabled must be true or false")
        val outputPower = yaml.get("output-power") as? Int
            ?: throw DeviceRepositoryException("$CONFIG_FILE: output-power must be an integer")
        if (outputPower !in 1..15) {
            throw DeviceRepositoryException("$CONFIG_FILE: output-power must be between 1 and 15")
        }
        return RedstoneConfiguration(enabled, outputPower)
    }

    private companion object {
        const val CONFIG_FILE = "modules/redstone.yml"
    }
}
