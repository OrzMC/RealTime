package cn.orzmc.realtime.paper.config

import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.DateTimeException
import java.time.ZoneId
import java.util.Locale

class RealTimeConfigurationLoaderTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `loads explicit timezone locale and command format`() {
        writeConfiguration()

        val configuration = RealTimeConfigurationLoader().load(temporaryDirectory.toFile())

        assertEquals(true, configuration.generalEnabled)
        assertEquals(ZoneId.of("Asia/Shanghai"), configuration.zoneId)
        assertEquals(Locale.forLanguageTag("zh-CN"), configuration.locale)
        assertEquals(true, configuration.commandEnabled)
        assertEquals("uuuu-MM-dd HH:mm:ss XXX", configuration.commandFormat)
        assertEquals("{prefix}clocks {count}", configuration.messages["command.overview.header"])
        assertEquals("{prefix}device diagnostics", configuration.messages["command.status.header"])
    }

    @Test
    fun `the shipped messages template declares exactly the keys the loader knows`() {
        val yaml = YamlConfiguration()
        val stream =
            RealTimeConfigurationLoader::class.java.getResourceAsStream("/messages.yml")
                ?: error("messages.yml is missing from the plugin resources")
        stream.reader(Charsets.UTF_8).use { reader -> yaml.load(reader) }
        val shipped = yaml.getKeys(true).filter { yaml.get(it) is String }.toSet()
        val declared =
            RealTimeConfigurationLoader.REQUIRED_MESSAGE_KEYS.toSet() +
                RealTimeConfigurationLoader.OPTIONAL_MESSAGE_KEYS

        assertEquals(emptySet<String>(), declared - shipped, "keys declared but missing from messages.yml")
        assertEquals(
            emptySet<String>(),
            shipped.filter { it.startsWith("command.") }.toSet() - declared,
            "messages.yml keys that nothing renders",
        )
    }

    @Test
    fun `loads default device messages when upgrading an older message file`() {
        writeConfiguration()
        val messagesFile = temporaryDirectory.resolve("messages.yml")
        val legacyMessages = Files.readString(messagesFile).substringBefore("  device:")
        Files.writeString(messagesFile, legacyMessages)

        val configuration = RealTimeConfigurationLoader().load(temporaryDirectory.toFile())

        assertEquals("{prefix}&b钟（{count}）", configuration.messages["command.overview.header"])
        assertEquals("{prefix}&b运行状态", configuration.messages["command.status.header"])
    }

    @Test
    fun `rejects invalid timezone with file and key context`() {
        writeConfiguration(timezone = "Mars/Olympus")

        val exception =
            assertThrows(ConfigurationException::class.java) {
                RealTimeConfigurationLoader().load(temporaryDirectory.toFile())
            }

        assertEquals(true, exception.message!!.contains("config.yml"))
        assertEquals(true, exception.message!!.contains("time.timezone"))
        assertEquals(true, exception.cause is DateTimeException)
    }

    @Test
    fun `rejects invalid locale with file and key context`() {
        writeConfiguration(locale = "bad_locale_tag_!")

        val exception =
            assertThrows(ConfigurationException::class.java) {
                RealTimeConfigurationLoader().load(temporaryDirectory.toFile())
            }

        assertEquals(true, exception.message!!.contains("time.locale"))
    }

    @Test
    fun `rejects invalid date time format before activating configuration`() {
        writeConfiguration(format = "uuuu-MM-dd-XXXXXX")

        val exception =
            assertThrows(ConfigurationException::class.java) {
                RealTimeConfigurationLoader().load(temporaryDirectory.toFile())
            }

        assertEquals(true, exception.message!!.contains("modules/command.yml"))
        assertEquals(true, exception.message!!.contains("format"))
    }

    @Test
    fun `rejects unsupported configuration versions`() {
        writeConfiguration(version = 2)

        val exception =
            assertThrows(ConfigurationException::class.java) {
                RealTimeConfigurationLoader().load(temporaryDirectory.toFile())
            }

        assertEquals(true, exception.message!!.contains("config-version"))
    }

    @Test
    fun `migrates unversioned root configuration and keeps a backup`() {
        writeConfiguration(version = null)

        val configuration = RealTimeConfigurationLoader().load(temporaryDirectory.toFile())

        assertEquals(ZoneId.of("Asia/Shanghai"), configuration.zoneId)
        assertEquals(true, Files.isRegularFile(temporaryDirectory.resolve("backups/config.yml.v0.bak")))
        assertEquals(true, Files.readString(temporaryDirectory.resolve("config.yml")).contains("config-version: 1"))
    }

    private fun writeConfiguration(
        version: Int? = 1,
        timezone: String = "Asia/Shanghai",
        locale: String = "zh_CN",
        format: String = "uuuu-MM-dd HH:mm:ss XXX",
    ) {
        Files.createDirectories(temporaryDirectory.resolve("modules"))
        Files.writeString(
            temporaryDirectory.resolve("config.yml"),
            """
            ${version?.let { "config-version: $it" }.orEmpty()}
            general:
              enabled: true
            time:
              timezone: $timezone
              locale: $locale
            logging:
              debug: false
            """.trimIndent(),
        )
        Files.writeString(
            temporaryDirectory.resolve("modules/command.yml"),
            """
            enabled: true
            format: '$format'
            """.trimIndent(),
        )
        Files.writeString(
            temporaryDirectory.resolve("messages.yml"),
            """
            prefix: '&8[&bRealTime&8]&r '
            command:
              now: '{prefix}now'
              info:
                header: '{prefix}info'
                version: '{prefix}version {version}'
                timezone: '{prefix}timezone {timezone}'
                locale: '{prefix}locale {locale}'
                global-enabled: '{prefix}global {enabled}'
                command-enabled: '{prefix}command {enabled}'
              reload:
                success: '{prefix}reloaded'
                failure: '{prefix}failed {reason}'
              no-permission: '{prefix}denied'
              disabled: '{prefix}disabled'
              usage: '{prefix}usage'
              device:
                unavailable: '{prefix}unavailable'
              status:
                header: '{prefix}device diagnostics'
                devices: '{prefix}devices {configured} enabled {enabled} outputs {outputs}'
                checks: '{prefix}checks {checks} unloaded {unloaded} stale {stale}'
                outcomes: '{prefix}changed {changed} unchanged {unchanged} invalid {invalid} failures {failures}'
              overview:
                header: '{prefix}clocks {count}'
            """.trimIndent(),
        )
    }
}
