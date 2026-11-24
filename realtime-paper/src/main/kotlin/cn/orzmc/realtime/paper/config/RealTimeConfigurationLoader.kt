package cn.orzmc.realtime.paper.config

import org.bukkit.configuration.InvalidConfigurationException
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.io.IOException
import java.time.DateTimeException
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.IllformedLocaleException
import java.util.Locale

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
            } +
                OPTIONAL_MESSAGES.mapValues { (key, fallback) ->
                    if (messagesYaml.contains(key)) requiredString(messagesYaml, key, MESSAGES_CONFIG) else fallback
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

        val OPTIONAL_MESSAGES =
            mapOf(
                "command.device.unavailable" to "{prefix}&c设备管理服务当前不可用。",
                "command.device.cleanup.header" to "{prefix}&b待清理设备队列（{count}）",
                "command.device.cleanup.empty" to "{prefix}&7当前没有待清理设备。",
                "command.device.cleanup.item" to "{prefix}&f{name} &7[{id}] &8世界：&f{world} &8待处理：&e{pending} &8需人工处理：&c{attention}",
                "command.device.cleanup.retry.success" to "{prefix}&a已重新排队清理任务：&f{id}",
                "command.device.cleanup.invalid-id" to "{prefix}&c清理任务 ID 格式无效：&f{id}",
                "command.device.cleanup.not-found" to "{prefix}&c未找到清理任务：&f{id}",
                "command.device.cleanup.error" to "{prefix}&c清理任务操作失败：&f{reason}",
                "command.device.status.header" to "{prefix}&b设备输出诊断",
                "command.device.status.devices" to "{prefix}&7配置设备：&f{configured} &7启用：&f{enabled} &7输出点：&f{outputs}",
                "command.device.status.checks" to "{prefix}&7输出检查：&f{checks} &7未加载区块跳过：&f{unloaded} &7过期配置更新：&f{stale}",
                "command.device.status.outcomes" to
                    "{prefix}&7功率变更：&f{changed} &7无变化：&f{unchanged} &7非红石线：&f{invalid} &7写入失败：&f{failures}",
                "command.device.list.header" to "{prefix}&b设备列表（{count}）",
                "command.device.list.empty" to "{prefix}&7当前没有配置任何设备。",
                "command.device.list.item" to "{prefix}&f{name} &7[{id}] &8世界：&f{world} &8状态：{enabled}",
                "command.device.info.header" to "{prefix}&b设备信息：&f{name}",
                "command.device.info.id" to "{prefix}&7ID：&f{id}",
                "command.device.info.world" to "{prefix}&7世界：&f{world}",
                "command.device.info.enabled" to "{prefix}&7状态：{enabled}",
                "command.device.info.outputs" to "{prefix}&7输出点（{count}）：",
                "command.device.info.output" to "{prefix}&8位 {bit}：&f{x}, {y}, {z}",
                "command.device.state.enabled" to "&a启用",
                "command.device.state.disabled" to "&c禁用",
                "command.device.enable.success" to "{prefix}&a设备 {name} 已启用。&7({id})",
                "command.device.disable.success" to "{prefix}&e设备 {name} 已禁用。&7({id})",
                "command.device.remove.success" to "{prefix}&a设备 {name} 已移出活动列表，清理任务：&f{job}&7（设备 {id}）",
                "command.device.remove.must-disable" to "{prefix}&c请先禁用设备后再移除：&f{id}",
                "command.device.invalid-id" to "{prefix}&c设备 ID 格式无效：&f{id}",
                "command.device.not-found" to "{prefix}&c未找到设备：&f{id}",
                "command.device.error" to "{prefix}&c设备操作或刷新失败：&f{reason}",
            )
    }
}
