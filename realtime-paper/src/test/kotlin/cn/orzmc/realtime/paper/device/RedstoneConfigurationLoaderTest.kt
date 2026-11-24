package cn.orzmc.realtime.paper.device

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
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

    private fun writeConfiguration(content: String) {
        val modules = Files.createDirectories(temporaryDirectory.resolve("modules"))
        Files.writeString(modules.resolve("redstone.yml"), content)
    }
}
