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
                "command.overview.header" to "{prefix}&b钟（{count}）",
                "command.overview.empty" to "{prefix}&7还没有任何钟。站在要建的位置执行 &f/rtime create <名字>&7。",
                "command.overview.item" to "{prefix}{state} &f{name} &8· &7{resolution} &8· &7{count} 个讲台{extra}",
                "command.overview.unplaced" to " &8· &c{count} 个未放置",
                "command.overview.cleanup-hint" to "{prefix}&7还有 {count} 个清理任务没跑完：&f/rtime cleanup",
                "command.device.header" to "{prefix}&b钟：&f{name}",
                "command.device.state-line" to "{prefix}&7状态：{state}",
                "command.device.world" to "{prefix}&7世界：&f{world}",
                "command.device.resolution" to "{prefix}&7精度：&f{resolution} &7（{count} 个讲台）",
                "command.device.outputs" to "{prefix}&7槽位（{count}）：",
                "command.device.output" to "{prefix}&8槽 {bit}：&f{x}, {y}, {z}",
                "command.device.output-unplaced" to "{prefix}&8槽 {bit}：&7未放置（&f/rtime place {name} {bit}&7 立讲台）",
                "command.device.hint" to
                    "{prefix}&7操作：&f/rtime on {name}&7 · &f/rtime off {name}&7 · &f/rtime place {name} <槽位>&7 · &f/rtime remove {name}",
                "command.device.state-enabled" to "&a启用",
                "command.device.state-disabled" to "&c禁用",
                "command.create.built" to "{prefix}&a已建好 &f{name} &8· &7{resolution} · &7{count} 个讲台 + {display} 个展示方块 &8· {state}",
                "command.create.manual-hint" to
                    "{prefix}&7槽位都还没位置：对着目标方块执行 &f/rtime place {name} <0..{max}>&7，每摆一个会立刻立起讲台；全部摆完 &f/rtime on {name}&7。",
                "command.create.unknown-option" to
                    "{prefix}&c不认识的参数：&f{value} &7（可用：minute / second / manual / nodisplay / at <x y z> / facing <朝向>）",
                "command.create.duplicate-option" to "{prefix}&c参数重复：&f{value}",
                "command.create.duplicate-name" to "{prefix}&c已经有一台叫 &f{name}&c 的钟了，换个名字。",
                "command.create.reserved-name" to "{prefix}&c&f{name}&c 是命令关键字，换一个名字。",
                "command.create.missing-coordinates" to "{prefix}&c&fat &7后面要跟三个整数：&f/rtime create clock at 100 64 0",
                "command.create.invalid-coordinate" to "{prefix}&c坐标无效：&f{value}",
                "command.create.invalid-facing" to "{prefix}&c朝向无效：&f{value} &7（可用：north、south、east、west）",
                "command.create.placement-required" to "{prefix}&c建钟要在游戏里执行：站到目标位置再输入命令（控制台没有朝向和世界）。",
                "command.create.limit" to "{prefix}&c钟的数量已达上限（{limit}），请先拆掉不用的。",
                "command.create.area" to "{prefix}&c坐标不在允许区域内：&f{world} {x}, {z}",
                "command.create.conflict" to "{prefix}&c这个位置已经被占用：&f{x}, {y}, {z}",
                "command.place.success" to "{prefix}&a槽 {bit} 已放到 &f{x}, {y}, {z}&a，讲台已立起。",
                "command.place.invalid-slot" to "{prefix}&c槽位号无效：&f{value}",
                "command.place.invalid-coordinates" to "{prefix}&c坐标无效：&f{value}",
                "command.place.world-mismatch" to "{prefix}&c这台钟在世界 &f{world}&c，你当前在 &f{current}&c。",
                "command.place.must-disable" to "{prefix}&c先关机再摆位（开着的钟会一直写旧槽位）：&f/rtime off <名字>",
                "command.place.index" to "{prefix}&c槽位超出范围（0–{max}）：&f{bit}",
                "command.toggle.enabled" to "{prefix}&a{name} 已开机。",
                "command.toggle.disabled" to "{prefix}&e{name} 已关机（讲台保留，输出归零）。",
                "command.toggle.incomplete" to "{prefix}&c还有 {count} 个槽位没有位置：用 &f/rtime place <名字> <槽位>&7 摆好再开机。",
                "command.remove.success" to "{prefix}&a{name} 已拆除，清理任务入队：&f{job}",
                "command.remove.must-disable" to "{prefix}&c先关机再拆：&f/rtime off <名字>",
                "command.remove.not-found" to "{prefix}&c没有这台钟：&f{name}",
                "command.status.header" to "{prefix}&b运行状态",
                "command.status.devices" to "{prefix}&7钟：&f{configured} 台（开着 {enabled} 台）· 槽位：&f{outputs}",
                "command.status.checks" to "{prefix}&7输出检查：&f{checks} · 未加载区块跳过：&f{unloaded} · 过期配置：&f{stale}",
                "command.status.outcomes" to "{prefix}&7变化：&f{changed} · 无变化：&f{unchanged} · 非法方块：&f{invalid} · 写入失败：&f{failures}",
                "command.status.display" to "{prefix}&7展示方块：放置 &f{placed}&7 · 清除 &f{cleared}",
                "command.status.analog" to "{prefix}&7讲台：放置 &f{lecterns}&7 · 书页写入 &f{books}",
                "command.status.ignored" to "{prefix}&e有 {count} 台旧版 BCD 钟已不再加载（本版本已移除 BCD 编码），请从 devices.yml 里删掉：&f{names}",
                "command.cleanup.header" to "{prefix}&b清理队列（{count}）",
                "command.cleanup.empty" to "{prefix}&7清理队列是空的。",
                "command.cleanup.item" to "{prefix}&f{name} &7[{id}] &8{world} &8待处理：&e{pending} &8需人工处理：&c{attention}",
                "command.cleanup.retry-success" to "{prefix}&a已重新排队：&f{id}",
                "command.cleanup.dismiss-success" to "{prefix}&a已丢弃清理任务 &f{name} &7[{id}]&a，方块保持原样。",
                "command.cleanup.dismiss-has-pending" to "{prefix}&c这个任务还有待处理项，等它跑完再丢弃：&f{id}",
                "command.cleanup.invalid-id" to "{prefix}&c清理任务 ID 格式无效：&f{id}",
                "command.cleanup.not-found" to "{prefix}&c没有这个清理任务：&f{id}",
                "command.cleanup.error" to "{prefix}&c清理任务操作失败：&f{reason}",
                "command.help.header" to "{prefix}&bRealTime 命令",
                "command.help.time" to "{prefix}&f/rtime &7- 现在几点 + 所有钟",
                "command.help.device" to "{prefix}&f/rtime <钟名> &7- 这台钟的详情",
                "command.help.create" to
                    "{prefix}&f/rtime create <名字> [minute] [manual] [at x y z] [facing 朝向] &7- 在你面前建一台钟（默认按秒、带展示方块、自动开机）",
                "command.help.toggle" to "{prefix}&f/rtime on|off <钟名> &7- 开机 / 关机",
                "command.help.place" to "{prefix}&f/rtime place <钟名> <槽位> &7- 把某个槽位摆到你看着的位置（讲台立刻立起来）",
                "command.help.remove" to "{prefix}&f/rtime remove <钟名> &7- 拆掉并清理方块（先 off）",
                "command.help.status" to "{prefix}&f/rtime status &7- 输出诊断（排查\"为什么不亮\"）",
                "command.help.cleanup" to "{prefix}&f/rtime cleanup [retry|dismiss <任务ID>] &7- 拆钟后的清理队列",
                "command.help.update" to
                    "{prefix}&f/rtime update [check|download] &7- 检查新版本 / 下载到 plugins/update（重启生效）",
                "command.update.checking" to "{prefix}&7正在检查新版本…",
                "command.update.downloading" to "{prefix}&7正在下载新版本…",
                "command.update.idle" to "{prefix}&7更新：还没有检查过（&f/rtime update&7 立刻检查）",
                "command.update.latest" to "{prefix}&7更新：已是最新（&f{version}&7）",
                "command.update.available" to
                    "{prefix}&e更新：&f{version}&e 可用（当前 &f{current}&e，来源 &f{source}&e）· &f/rtime update download&7 下载到更新目录",
                "command.update.downloaded" to
                    "{prefix}&a已下载 &f{version}&a 到 &f{file}&a（&f{bytes}&a 字节，校验通过）。重启服务器即生效。",
                "command.update.pending" to "{prefix}&e更新：&f{version}&e 已下载待重启（&f{file}&e）",
                "command.update.disabled" to "{prefix}&7更新检查已关闭：&f{reason}",
                "command.update.failed" to "{prefix}&c更新检查失败：&f{reason}",
                "command.update.refused" to "{prefix}&c没有下载：&f{reason}",
                "command.help.reload" to "{prefix}&f/rtime reload &7- 重载配置",
                "command.help.footer" to "{prefix}&7名字和槽位号都能 Tab 补全；&fmanual &7建一台自己摆位的钟，&fnodisplay &7不放比较器/灯。",
                "command.removed" to "{prefix}&e&f{verb}&e 这个写法已经移除，改用 &f{replacement}&e。",
                "command.not-found" to "{prefix}&c没有这台钟：&f{name} &7（&f/rtime&7 看全部）",
                "command.ambiguous" to "{prefix}&c有 {count} 台钟都叫 &f{name}&c，请先在 devices.yml 里改名。",
                "command.retry" to "{prefix}&c操作太快，请 &f{seconds} &c秒后再试。",
                "command.warn.redstone-disabled" to
                    "{prefix}&e注意：红石输出模块未启用（plugins/RealTime/modules/redstone.yml → enabled: true），设备不会真的输出或放置方块。改完执行 /rtime reload。",
                "command.unavailable" to "{prefix}&c设备管理服务当前不可用。",
                "command.invalid-id" to "{prefix}&cID 无效：&f{id}",
                "command.error" to "{prefix}&c设备操作失败：&f{reason}",
            )

        /** Message keys every install must define (they have no fallback). */
        val REQUIRED_MESSAGE_KEYS: List<String> = REQUIRED_MESSAGES

        /** Message keys that fall back to the built-in defaults when absent. */
        val OPTIONAL_MESSAGE_KEYS: Set<String> = OPTIONAL_MESSAGES.keys
    }
}
