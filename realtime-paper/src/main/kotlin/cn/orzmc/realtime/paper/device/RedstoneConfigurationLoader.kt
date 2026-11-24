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
        return RedstoneConfiguration(enabled, parseLimits(yaml))
    }

    private fun parseLimits(yaml: YamlConfiguration): RedstoneLimits {
        val maxDevices = optionalInt(yaml, "limits.max-devices", RedstoneLimits.DEFAULT_MAX_DEVICES)
        val cooldown = optionalInt(yaml, "limits.edit-cooldown-seconds", RedstoneLimits.DEFAULT_EDIT_COOLDOWN_SECONDS)
        if (maxDevices < 0) {
            throw DeviceRepositoryException("$CONFIG_FILE: limits.max-devices must not be negative")
        }
        if (cooldown < 0) {
            throw DeviceRepositoryException("$CONFIG_FILE: limits.edit-cooldown-seconds must not be negative")
        }

        val rawAreas = yaml.get("limits.allowed-areas") ?: return RedstoneLimits(emptyList(), maxDevices, cooldown)
        val areas =
            when (rawAreas) {
                is List<*> -> rawAreas.mapIndexed { index, value -> parseArea(value, "limits.allowed-areas[$index]") }
                else -> throw DeviceRepositoryException("$CONFIG_FILE: limits.allowed-areas must be a list")
            }
        return RedstoneLimits(areas, maxDevices, cooldown)
    }

    private fun parseArea(
        value: Any?,
        context: String,
    ): AllowedArea {
        val raw =
            value as? Map<*, *>
                ?: throw DeviceRepositoryException("$CONFIG_FILE: $context must be a map")
        val area =
            runCatching {
                AllowedArea(
                    world = requiredString(raw, "world", context),
                    minX = requiredInt(raw, "min-x", context),
                    maxX = requiredInt(raw, "max-x", context),
                    minZ = requiredInt(raw, "min-z", context),
                    maxZ = requiredInt(raw, "max-z", context),
                )
            }.getOrElse { exception ->
                throw DeviceRepositoryException("$CONFIG_FILE: $context is invalid: ${exception.message}", exception)
            }
        return area
    }

    private fun optionalInt(
        yaml: YamlConfiguration,
        path: String,
        fallback: Int,
    ): Int {
        val value = yaml.get(path) ?: return fallback
        return value as? Int
            ?: throw DeviceRepositoryException("$CONFIG_FILE: $path must be an integer")
    }

    private fun requiredString(
        values: Map<*, *>,
        key: String,
        context: String,
    ): String =
        (values[key] as? String)?.takeIf(String::isNotBlank)
            ?: throw DeviceRepositoryException("$CONFIG_FILE: $context.$key must be a non-empty string")

    private fun requiredInt(
        values: Map<*, *>,
        key: String,
        context: String,
    ): Int =
        values[key] as? Int
            ?: throw DeviceRepositoryException("$CONFIG_FILE: $context.$key must be an integer")

    private companion object {
        const val CONFIG_FILE = "modules/redstone.yml"
    }
}
