package cn.orzmc.realtime.paper.device

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class RedstoneConfigurationLoaderTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `loads the enabled flag and ignores the retired output power key`() {
        writeConfiguration("enabled: true\noutput-power: 12\n")

        val configuration = RedstoneConfigurationLoader().load(temporaryDirectory.toFile())

        assertEquals(true, configuration.enabled)
    }

    @Test
    fun `loads a configuration without the retired output power key`() {
        writeConfiguration("enabled: false\n")

        val configuration = RedstoneConfigurationLoader().load(temporaryDirectory.toFile())

        assertEquals(false, configuration.enabled)
    }

    @Test
    fun `rejects a missing enabled flag`() {
        writeConfiguration("output-power: 15\n")

        assertThrows(DeviceRepositoryException::class.java) {
            RedstoneConfigurationLoader().load(temporaryDirectory.toFile())
        }
    }

    @Test
    fun `loads authoring limits and allowed areas`() {
        writeConfiguration(
            """
            enabled: true
            limits:
              max-devices: 12
              edit-cooldown-seconds: 5
              allowed-areas:
                - { world: world, min-x: 0, max-x: 100, min-z: -20, max-z: 20 }
            """.trimIndent(),
        )

        val limits = RedstoneConfigurationLoader().load(temporaryDirectory.toFile()).limits

        assertEquals(12, limits.maxDevices)
        assertEquals(5, limits.editCooldownSeconds)
        assertEquals(1, limits.allowedAreas.size)
        assertTrue(limits.allows("world", 50, 0))
        assertTrue(!limits.allows("world", 101, 0))
        assertTrue(!limits.allows("nether", 50, 0))
    }

    @Test
    fun `treats missing limits as defaults`() {
        writeConfiguration("enabled: false\n")

        val limits = RedstoneConfigurationLoader().load(temporaryDirectory.toFile()).limits

        assertEquals(RedstoneLimits.DEFAULT_MAX_DEVICES, limits.maxDevices)
        assertEquals(RedstoneLimits.DEFAULT_EDIT_COOLDOWN_SECONDS, limits.editCooldownSeconds)
        assertTrue(limits.allowedAreas.isEmpty())
        assertTrue(limits.allows("anything", 0, 0))
    }

    @Test
    fun `rejects invalid limits and inverted areas`() {
        writeConfiguration("enabled: true\nlimits:\n  max-devices: -1\n")
        assertThrows(DeviceRepositoryException::class.java) {
            RedstoneConfigurationLoader().load(temporaryDirectory.toFile())
        }

        writeConfiguration(
            "enabled: true\nlimits:\n  allowed-areas:\n    - { world: world, min-x: 10, max-x: 0, min-z: 0, max-z: 0 }\n",
        )
        assertThrows(DeviceRepositoryException::class.java) {
            RedstoneConfigurationLoader().load(temporaryDirectory.toFile())
        }
    }

    private fun writeConfiguration(content: String) {
        val modules = Files.createDirectories(temporaryDirectory.resolve("modules"))
        Files.writeString(modules.resolve("redstone.yml"), content)
    }
}
