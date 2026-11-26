package cn.orzmc.realtime.paper.update

import cn.orzmc.realtime.core.update.UpdateChannel
import cn.orzmc.realtime.paper.config.ConfigurationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Duration

class UpdateConfigurationLoaderTest {
    @TempDir
    lateinit var folder: File

    @Test
    fun `the shipped resource parses into the documented defaults`() {
        val file = File(folder, "modules/updates.yml")
        file.parentFile.mkdirs()
        file.writeText(javaClass.getResourceAsStream("/modules/updates.yml")!!.reader().readText())

        val configuration = UpdateConfigurationLoader().load(folder)

        assertTrue(configuration.enabled)
        assertEquals(UpdateMode.NOTIFY, configuration.mode)
        assertEquals(UpdateChannel.RELEASE, configuration.channel)
        assertEquals(Duration.ofHours(6), configuration.checkInterval)
        assertEquals(UpdateSourcePreference.AUTO, configuration.source)
        assertTrue(configuration.notifyConsole)
        assertTrue(configuration.notifyPlayers)
        assertTrue(configuration.skipDevBuilds)
        assertEquals(16L * 1024 * 1024, configuration.maxDownloadBytes)
        assertFalse(configuration.allowInsecureHttp)
        assertNull(configuration.apiBaseOverride)
        assertTrue(configuration.allowedHostSet.contains("hangarcdn.papermc.io"))
    }

    @Test
    fun `a minimal file falls back to the safe defaults`() {
        write("")

        val configuration = UpdateConfigurationLoader().load(folder)

        assertTrue(configuration.enabled)
        assertEquals(UpdateMode.NOTIFY, configuration.mode)
        assertEquals(UpdateChannel.RELEASE, configuration.channel)
        assertTrue(configuration.allowedHostSet.contains("api.github.com"))
    }

    @Test
    fun `explicit values are read and normalized`() {
        write(
            """
            config-version: 1
            enabled: false
            mode: download
            channel: any
            check-interval-hours: 24
            source: github
            notify:
              console: false
              players: false
            safety:
              max-download-mb: 4
              skip-dev-builds: false
              allow-insecure-http: true
              api-base-override: 'http://127.0.0.1:8080/'
              allowed-hosts:
                - 127.0.0.1
            """.trimIndent(),
        )

        val configuration = UpdateConfigurationLoader().load(folder)

        assertFalse(configuration.enabled)
        assertEquals(UpdateMode.DOWNLOAD, configuration.mode)
        assertEquals(UpdateChannel.ANY, configuration.channel)
        assertEquals(Duration.ofHours(24), configuration.checkInterval)
        assertEquals(UpdateSourcePreference.GITHUB, configuration.source)
        assertFalse(configuration.notifyConsole)
        assertFalse(configuration.notifyPlayers)
        assertFalse(configuration.skipDevBuilds)
        assertEquals(4L * 1024 * 1024, configuration.maxDownloadBytes)
        assertTrue(configuration.allowInsecureHttp)
        assertEquals("http://127.0.0.1:8080/", configuration.apiBaseOverride)
        assertEquals(setOf("127.0.0.1"), configuration.allowedHostSet)
    }

    @Test
    fun `bad values are rejected instead of silently ignored`() {
        write("mode: sometimes")
        assertThrows(ConfigurationException::class.java) { UpdateConfigurationLoader().load(folder) }

        write("channel: nightly")
        assertThrows(ConfigurationException::class.java) { UpdateConfigurationLoader().load(folder) }

        write("source: curseforge")
        assertThrows(ConfigurationException::class.java) { UpdateConfigurationLoader().load(folder) }

        write("check-interval-hours: 0")
        assertThrows(ConfigurationException::class.java) { UpdateConfigurationLoader().load(folder) }

        write("check-interval-hours: 1000")
        assertThrows(ConfigurationException::class.java) { UpdateConfigurationLoader().load(folder) }

        write("safety:\n  max-download-mb: 0")
        assertThrows(ConfigurationException::class.java) { UpdateConfigurationLoader().load(folder) }

        write("safety:\n  allowed-hosts: []")
        assertThrows(ConfigurationException::class.java) { UpdateConfigurationLoader().load(folder) }

        write("safety:\n  allowed-hosts: not-a-list")
        assertThrows(ConfigurationException::class.java) { UpdateConfigurationLoader().load(folder) }

        write("enabled: 5")
        assertThrows(ConfigurationException::class.java) { UpdateConfigurationLoader().load(folder) }

        write("config-version: 2")
        assertThrows(ConfigurationException::class.java) { UpdateConfigurationLoader().load(folder) }

        // A missing file is an error, not a silent default: onEnable writes it first.
        assertThrows(ConfigurationException::class.java) { UpdateConfigurationLoader().load(File(folder, "nope")) }
    }

    private fun write(text: String) {
        val file = File(folder, "modules/updates.yml")
        file.parentFile.mkdirs()
        file.writeText(text)
    }
}
