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

    companion object {
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

        private val OPTIONAL_MESSAGES =
            mapOf(
                "command.info.redstone-enabled" to "{prefix}&7红石输出模块：&f{enabled}",
                "command.device.warn.redstone-disabled" to
                    "{prefix}&e注意：红石输出模块未启用（plugins/RealTime/modules/redstone.yml → enabled: true），" +
                    "设备不会真的输出或放置方块。改完执行 /rtime reload。",
                "command.help.header" to "{prefix}&bRealTime &7常用命令（&f/rtime help all&7 看全部）",
                "command.help.time" to "{prefix}&f/rtime &7- 现在几点",
                "command.help.create" to
                    "{prefix}&f/rtime create <名字> [bcd|analog] [second|minute] &7- 在你面前建一台钟（默认 bcd + 按分），建完自动亮",
                "command.help.info" to "{prefix}&f/rtime info [设备] &7- 看插件状态与所有钟；给设备名看细节",
                "command.help.toggle" to "{prefix}&f/rtime on|off <设备> &7- 开机 / 关机",
                "command.help.remove" to "{prefix}&f/rtime remove <设备> &7- 拆掉这台钟（自动清理它放下的方块）",
                "command.help.footer" to "{prefix}&7设备名和槽位号都能用 Tab 补全；示例见 &f/rtime help create",
                "command.help.advanced.header" to "{prefix}&b进阶",
                "command.help.place" to
                    "{prefix}&f/rtime place <设备> <槽位> [x y z] &7- 把某个源放到准星位置，用来避开被占的格子",
                "command.help.status" to "{prefix}&f/rtime status &7- 输出诊断（排查\"为什么不亮\"）",
                "command.help.cleanup" to "{prefix}&f/rtime cleanup [list|retry|dismiss] [任务ID] &7- 拆钟后的清理队列",
                "command.help.reload" to "{prefix}&f/rtime reload &7- 重载配置",
                "command.help.topic.create" to
                    "{prefix}&7细节：不给坐标就用玩家位置与朝向；&fmanual &7建一台未启用的空钟（用 place 逐位放），" +
                    "&fnodisplay &7不放灯/讲台；显式坐标形式 &f/rtime create clock bcd second world 100 70 0 x+ &7仍可用",
                "command.help.topic.place" to
                    "{prefix}&7细节：设备要先 &f/rtime off <设备>&7；槽位号从 0 开始（Tab 补全）；" +
                    "位置可以反复改，用来避开已经被占用的格子",
                "command.help.topic.cleanup" to
                    "{prefix}&7细节：&f/rtime cleanup &7看队列；&f/rtime cleanup retry <任务ID> &7重试\"需人工处理\"的项；" +
                    "&f/rtime cleanup dismiss <任务ID> &7确认方块保持原样并丢弃任务",
                "command.device.create.built" to
                    "{prefix}&a已创建 &f{name} &7[{id}] &8{encoding} {resolution} &8{world} &7{count} 个源 + " +
                    "&f{display} &7个展示方块 &8状态：{state}",
                "command.device.create.manual-hint" to
                    "{prefix}&7这是一台未启用的空钟。用 &f/rtime device place {name} <0..{max}> &7逐个定位" +
                    "（对准目标方块执行即可），全部就位后 &f/rtime device enable {name}&7。",
                "command.device.create.invalid-modifier" to
                    "{prefix}&c未知参数：&f{value} &7（可用：second / minute / nodisplay / manual）",
                "command.device.placement-required" to
                    "{prefix}&c这条命令需要玩家视角：请以玩家身份站在目标位置，或在 6 格内看着一个方块。",
                "command.device.ambiguous" to "{prefix}&c找到 {count} 台同名设备，请改用 ID：&f{name}",
                "command.device.place.world-mismatch" to
                    "{prefix}&c这台设备属于世界 &f{world}&c，你当前在 &f{current}&c。",
                "command.device.unavailable" to "{prefix}&c设备管理服务当前不可用。",
                "command.device.cleanup.header" to "{prefix}&b待清理设备队列（{count}）",
                "command.device.cleanup.empty" to "{prefix}&7当前没有待清理设备。",
                "command.device.cleanup.item" to "{prefix}&f{name} &7[{id}] &8世界：&f{world} &8待处理：&e{pending} &8需人工处理：&c{attention}",
                "command.device.cleanup.retry.success" to "{prefix}&a已重新排队清理任务：&f{id}",
                "command.device.cleanup.dismiss.success" to
                    "{prefix}&a已丢弃清理任务 &f{name} &7[{id}]&a，相关方块保持原样。",
                "command.device.cleanup.dismiss.has-pending" to
                    "{prefix}&c该任务还有待处理项，请等它跑完再丢弃：&f{id}",
                "command.device.cleanup.invalid-id" to "{prefix}&c清理任务 ID 格式无效：&f{id}",
                "command.device.cleanup.not-found" to "{prefix}&c未找到清理任务：&f{id}",
                "command.device.cleanup.error" to "{prefix}&c清理任务操作失败：&f{reason}",
                "command.device.remove.success" to "{prefix}&a设备 &f{name} &a已退休，清理任务已入队：&f{job}",
                "command.device.remove.must-disable" to "{prefix}&c请先禁用设备再执行移除：&f{id}",
                "command.device.remove.not-found" to "{prefix}&c未找到设备：&f{id}",
                "command.device.remove.error" to "{prefix}&c设备移除失败：&f{reason}",
                "command.device.status.header" to "{prefix}&b设备输出诊断",
                "command.device.status.devices" to "{prefix}&7配置设备：&f{configured} &7启用：&f{enabled} &7输出点：&f{outputs}",
                "command.device.status.checks" to "{prefix}&7输出检查：&f{checks} &7未加载区块跳过：&f{unloaded} &7过期配置更新：&f{stale}",
                "command.device.status.outcomes" to
                    "{prefix}&7输出变更：&f{changed} &7无变化：&f{unchanged} &7非法方块：&f{invalid} &7写入失败：&f{failures}",
                "command.device.status.analog" to "{prefix}&7讲台放置：&f{lecterns} &7书页安装：&f{books}",
                "command.device.status.demo" to "{prefix}&7示例方块放置：&f{placed} &7清除：&f{cleared}",
                "command.device.list.header" to "{prefix}&b设备列表（{count}）",
                "command.device.list.empty" to "{prefix}&7当前没有配置任何设备。",
                "command.device.list.item" to "{prefix}&f{name} &7[{id}] &8世界：&f{world} &8状态：{enabled}",
                "command.device.info.header" to "{prefix}&b设备信息：&f{name}",
                "command.device.info.id" to "{prefix}&7ID：&f{id}",
                "command.device.info.world" to "{prefix}&7世界：&f{world}",
                "command.device.info.enabled" to "{prefix}&7状态：{enabled}",
                "command.device.info.resolution" to "{prefix}&7分辨率：&f{resolution}",
                "command.device.info.encoding" to "{prefix}&7编码：&f{encoding}",
                "command.device.info.outputs" to "{prefix}&7输出点（{count}）：",
                "command.device.info.output" to "{prefix}&8槽 {bit}：&f{x}, {y}, {z}",
                "command.device.state.enabled" to "&a启用",
                "command.device.state.disabled" to "&c禁用",
                "command.device.create.success" to
                    "{prefix}&a设备已创建：&f{name} &7[{id}] &8{resolution} &8{encoding} &8{world} &7{count} 个输出 " +
                    "&8（默认禁用，槽位每格间隔 1 空气，BCD 每 4 位一组；确认接线后 device enable）",
                "command.device.create.invalid-resolution" to
                    "{prefix}&c分辨率无效：&f{value} &7（可用：minute、second）",
                "command.device.create.invalid-encoding" to
                    "{prefix}&c编码无效：&f{value} &7（可用：bcd、analog）",
                "command.device.demo.success" to
                    "{prefix}&a教学示例已生成：&f{name} &7[{id}] &8{encoding} &8{world} &7{count} 个槽位 + " +
                    "&f{display} &7个展示方块 &8起点 {x}, {y}, {z}（已启用；用 device remove 可整体清除）",
                "command.device.demo.hint" to
                    "{prefix}&e提示：插件只填空位。建示例前请确认该区域是空地，否则被地形占住的位置会报非法方块，" +
                    "退役时会变成需人工处理（可用 device cleanup retry / dismiss 收尾）。",
                "command.device.create.limit" to "{prefix}&c设备数量已达上限（{limit}），请先移除不再使用的设备。",
                "command.device.create.area" to "{prefix}&c坐标不在允许区域内：&f{world} {x}, {z}",
                "command.device.create.conflict" to "{prefix}&c该坐标已被其他设备或清理任务占用：&f{x}, {y}, {z}",
                "command.device.set.success" to "{prefix}&a槽 {bit} 已改为 &f{x}, {y}, {z}",
                "command.device.set.must-disable" to
                    "{prefix}&c请先禁用设备再改槽位（启用中的设备会持续写旧槽位）：&f{id}",
                "command.device.set.index" to "{prefix}&c位号超出范围（0–{max}）：&f{bit}",
                "command.device.edit.cooldown" to "{prefix}&c操作太快，请 &f{seconds} &c秒后再试。",
                "command.device.enable.success" to "{prefix}&a设备 {name} 已启用。&7({id})",
                "command.device.disable.success" to "{prefix}&e设备 {name} 已禁用。&7({id})",
                "command.device.invalid-id" to "{prefix}&c设备 ID 格式无效：&f{id}",
                "command.device.not-found" to "{prefix}&c未找到设备：&f{id}",
                "command.device.error" to "{prefix}&c设备操作或刷新失败：&f{reason}",
            )

        /** Message keys every install must define (they have no fallback). */
        val REQUIRED_MESSAGE_KEYS: List<String> = REQUIRED_MESSAGES

        /** Message keys that fall back to the built-in defaults when absent. */
        val OPTIONAL_MESSAGE_KEYS: Set<String> = OPTIONAL_MESSAGES.keys
    }
}
