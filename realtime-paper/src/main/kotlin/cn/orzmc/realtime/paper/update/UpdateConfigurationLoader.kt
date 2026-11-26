package cn.orzmc.realtime.paper.update

import cn.orzmc.realtime.core.update.UpdateChannel
import cn.orzmc.realtime.paper.config.ConfigurationException
import org.bukkit.configuration.InvalidConfigurationException
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.io.IOException
import java.time.Duration

/**
 * Reads `modules/updates.yml`.
 *
 * Optional keys fall back to the built-in defaults, so an install that predates the
 * updater keeps working after the resource is written on first start. Anything that
 * is present but wrong is rejected instead of silently ignored: a typo in
 * `allowed-hosts` must not quietly widen the allowlist.
 */
class UpdateConfigurationLoader {
    fun load(dataFolder: File): UpdateConfiguration {
        val file = File(dataFolder, CONFIG_FILE)
        if (!file.isFile) {
            throw ConfigurationException("$CONFIG_FILE is missing")
        }

        val yaml = YamlConfiguration()
        try {
            yaml.load(file)
        } catch (exception: IOException) {
            throw ConfigurationException("Unable to read $CONFIG_FILE", exception)
        } catch (exception: InvalidConfigurationException) {
            throw ConfigurationException("Unable to parse $CONFIG_FILE: ${exception.message}", exception)
        }

        val version = yaml.get("config-version") ?: SUPPORTED_CONFIG_VERSION
        if (version !is Int || version != SUPPORTED_CONFIG_VERSION) {
            throw ConfigurationException("$CONFIG_FILE: config-version must be $SUPPORTED_CONFIG_VERSION")
        }

        val defaults = UpdateConfiguration()
        val enabled = optionalBoolean(yaml, "enabled", defaults.enabled)
        val mode =
            optionalString(yaml, "mode")?.let { value ->
                UpdateMode.fromConfig(value)
                    ?: throw ConfigurationException(
                        "$CONFIG_FILE: mode must be one of ${UpdateMode.entries.joinToString { it.configValue }}",
                    )
            } ?: defaults.mode
        val channel =
            optionalString(yaml, "channel")?.let { value ->
                UpdateChannel.fromConfig(value)
                    ?: throw ConfigurationException(
                        "$CONFIG_FILE: channel must be one of ${UpdateChannel.entries.joinToString { it.configValue }}",
                    )
            } ?: defaults.channel
        val source =
            optionalString(yaml, "source")?.let { value ->
                UpdateSourcePreference.fromConfig(value)
                    ?: throw ConfigurationException(
                        "$CONFIG_FILE: source must be one of ${UpdateSourcePreference.entries.joinToString { it.configValue }}",
                    )
            } ?: defaults.source
        val intervalHours = optionalLong(yaml, "check-interval-hours", defaults.checkInterval.toHours())
        if (intervalHours !in UpdateConfiguration.MIN_INTERVAL_HOURS..UpdateConfiguration.MAX_INTERVAL_HOURS) {
            throw ConfigurationException(
                "$CONFIG_FILE: check-interval-hours must be between ${UpdateConfiguration.MIN_INTERVAL_HOURS} and " +
                    UpdateConfiguration.MAX_INTERVAL_HOURS,
            )
        }

        val maxMb = optionalLong(yaml, "safety.max-download-mb", UpdateConfiguration.DEFAULT_MAX_DOWNLOAD_MB)
        if (maxMb !in 1..UpdateConfiguration.MAX_DOWNLOAD_MB) {
            throw ConfigurationException(
                "$CONFIG_FILE: safety.max-download-mb must be between 1 and ${UpdateConfiguration.MAX_DOWNLOAD_MB}",
            )
        }

        val allowedHosts = parseAllowedHosts(yaml) ?: defaults.allowedHosts
        val apiBaseOverride = optionalString(yaml, "safety.api-base-override")?.takeIf { it.isNotBlank() }

        return UpdateConfiguration(
            enabled = enabled,
            mode = mode,
            channel = channel,
            checkInterval = Duration.ofHours(intervalHours),
            source = source,
            notifyConsole = optionalBoolean(yaml, "notify.console", defaults.notifyConsole),
            notifyPlayers = optionalBoolean(yaml, "notify.players", defaults.notifyPlayers),
            skipDevBuilds = optionalBoolean(yaml, "safety.skip-dev-builds", defaults.skipDevBuilds),
            maxDownloadBytes = maxMb * 1024L * 1024L,
            allowInsecureHttp = optionalBoolean(yaml, "safety.allow-insecure-http", defaults.allowInsecureHttp),
            apiBaseOverride = apiBaseOverride,
            allowedHosts = allowedHosts,
        )
    }

    private fun parseAllowedHosts(yaml: YamlConfiguration): List<String>? {
        val raw = yaml.get("safety.allowed-hosts") ?: return null
        val values =
            when (raw) {
                is List<*> ->
                    raw.map { entry ->
                        (entry as? String)?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
                            ?: throw ConfigurationException("$CONFIG_FILE: safety.allowed-hosts entries must be host names")
                    }
                else -> throw ConfigurationException("$CONFIG_FILE: safety.allowed-hosts must be a list")
            }
        if (values.isEmpty()) {
            throw ConfigurationException("$CONFIG_FILE: safety.allowed-hosts must not be empty")
        }
        return values
    }

    private fun optionalString(
        yaml: YamlConfiguration,
        path: String,
    ): String? {
        val value = yaml.get(path) ?: return null
        return value as? String ?: throw ConfigurationException("$CONFIG_FILE: $path must be a string")
    }

    private fun optionalBoolean(
        yaml: YamlConfiguration,
        path: String,
        fallback: Boolean,
    ): Boolean {
        val value = yaml.get(path) ?: return fallback
        return value as? Boolean ?: throw ConfigurationException("$CONFIG_FILE: $path must be true or false")
    }

    private fun optionalLong(
        yaml: YamlConfiguration,
        path: String,
        fallback: Long,
    ): Long {
        val value = yaml.get(path) ?: return fallback
        return when (value) {
            is Int -> value.toLong()
            is Long -> value
            else -> throw ConfigurationException("$CONFIG_FILE: $path must be an integer")
        }
    }

    private companion object {
        const val CONFIG_FILE = "modules/updates.yml"
        const val SUPPORTED_CONFIG_VERSION = 1
    }
}
