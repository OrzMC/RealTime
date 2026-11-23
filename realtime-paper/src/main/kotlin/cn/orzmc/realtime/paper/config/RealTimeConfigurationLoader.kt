package cn.orzmc.realtime.paper.config

import java.io.File
import java.io.IOException
import java.time.DateTimeException
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.IllformedLocaleException
import java.util.Locale
import org.bukkit.configuration.InvalidConfigurationException
import org.bukkit.configuration.file.YamlConfiguration

class RealTimeConfigurationLoader {
    fun load(dataFolder: File): RealTimeConfiguration {
        val rootFile = File(dataFolder, ROOT_CONFIG)
        val root = loadYaml(rootFile, ROOT_CONFIG)
        migrateRootConfig(root, rootFile)

        val generalEnabled = requiredBoolean(root, "general.enabled", ROOT_CONFIG)
        val timezoneName = requiredString(root, "time.timezone", ROOT_CONFIG)
        val localeName = requiredString(root, "time.locale", ROOT_CONFIG)
        val debugLogging = requiredBoolean(root, "logging.debug", ROOT_CONFIG)

        val zoneId =
            try {
                ZoneId.of(timezoneName)
            } catch (exception: DateTimeException) {
                throw ConfigurationException(
                    "$ROOT_CONFIG: invalid time.timezone '$timezoneName'",
                    exception,
                )
            }

        val locale =
            try {
                Locale.Builder().setLanguageTag(localeName.replace('_', '-')).build()
            } catch (exception: IllformedLocaleException) {
                throw ConfigurationException(
                    "$ROOT_CONFIG: invalid time.locale '$localeName'",
                    exception,
                )
            }
        if (locale.language.isBlank()) {
            throw ConfigurationException("$ROOT_CONFIG: time.locale must include a language")
        }

        val command = loadYaml(File(dataFolder, COMMAND_CONFIG), COMMAND_CONFIG)
        val commandEnabled = requiredBoolean(command, "enabled", COMMAND_CONFIG)
        val commandFormat = requiredString(command, "format", COMMAND_CONFIG)
        try {
            DateTimeFormatter.ofPattern(commandFormat, locale)
        } catch (exception: IllegalArgumentException) {
            throw ConfigurationException(
                "$COMMAND_CONFIG: invalid format pattern '$commandFormat'",
                exception,
            )
        }

        val messagesYaml = loadYaml(File(dataFolder, MESSAGES_CONFIG), MESSAGES_CONFIG)
        val messages =
            REQUIRED_MESSAGES.associateWith { key ->
                requiredString(messagesYaml, key, MESSAGES_CONFIG)
            }

        return RealTimeConfiguration(
            generalEnabled = generalEnabled,
            zoneId = zoneId,
            locale = locale,
            debugLogging = debugLogging,
            commandEnabled = commandEnabled,
            commandFormat = commandFormat,
            messages = messages,
        )
    }

    private fun migrateRootConfig(
        root: YamlConfiguration,
        file: File,
    ) {
        val version = root.get("config-version")
        if (version == null) {
            val backup = File(file.parentFile, "backups/config.yml.v0.bak")
            if (!backup.exists()) {
                backup.parentFile?.mkdirs()
                try {
                    file.copyTo(backup)
                } catch (exception: IOException) {
                    throw ConfigurationException("Unable to back up legacy $ROOT_CONFIG", exception)
                }
            }

            if (!root.contains("general.enabled")) root.set("general.enabled", true)
            if (!root.contains("time.timezone")) root.set("time.timezone", "Asia/Shanghai")
            if (!root.contains("time.locale")) root.set("time.locale", "zh_CN")
            if (!root.contains("logging.debug")) root.set("logging.debug", false)
            root.set("config-version", SUPPORTED_CONFIG_VERSION)
            try {
                root.save(file)
            } catch (exception: IOException) {
                throw ConfigurationException("Unable to migrate legacy $ROOT_CONFIG", exception)
            }
            return
        }

        if (version !is Int || version != SUPPORTED_CONFIG_VERSION) {
            throw ConfigurationException(
                "$ROOT_CONFIG: config-version must be $SUPPORTED_CONFIG_VERSION",
            )
        }
    }

    private fun loadYaml(
        file: File,
        relativePath: String,
    ): YamlConfiguration {
        if (!file.isFile) {
            throw ConfigurationException("$relativePath is missing")
        }

        val yaml = YamlConfiguration()
        try {
            yaml.load(file)
        } catch (exception: IOException) {
            throw ConfigurationException("Unable to read $relativePath", exception)
        } catch (exception: InvalidConfigurationException) {
            throw ConfigurationException("Unable to parse $relativePath: ${exception.message}", exception)
        }
        return yaml
    }

    private fun requiredString(
        yaml: YamlConfiguration,
        path: String,
        file: String,
    ): String {
        val value = yaml.get(path)
        if (value !is String || value.isBlank()) {
            throw ConfigurationException("$file: '$path' must be a non-empty string")
        }
        return value.trim()
    }

    private fun requiredBoolean(
        yaml: YamlConfiguration,
        path: String,
        file: String,
    ): Boolean {
        val value = yaml.get(path)
        if (value !is Boolean) {
            throw ConfigurationException("$file: '$path' must be true or false")
        }
        return value
    }

    private companion object {
        const val ROOT_CONFIG = "config.yml"
        const val COMMAND_CONFIG = "modules/command.yml"
        const val MESSAGES_CONFIG = "messages.yml"
        const val SUPPORTED_CONFIG_VERSION = 1

        val REQUIRED_MESSAGES =
            listOf(
                "prefix",
                "command.now",
                "command.info.header",
                "command.info.version",
                "command.info.timezone",
                "command.info.locale",
                "command.info.global-enabled",
                "command.info.command-enabled",
                "command.reload.success",
                "command.reload.failure",
                "command.no-permission",
                "command.disabled",
                "command.usage",
            )
    }
}
