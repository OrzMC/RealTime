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
    fun `loads enabled flag and output power`() {
        writeConfiguration("enabled: true\noutput-power: 12\n")

        val configuration = RedstoneConfigurationLoader().load(temporaryDirectory.toFile())

        assertEquals(true, configuration.enabled)
        assertEquals(12, configuration.outputPower)
    }

    @Test
    fun `rejects output power outside redstone range`() {
        writeConfiguration("enabled: true\noutput-power: 16\n")

        assertThrows(DeviceRepositoryException::class.java) {
            RedstoneConfigurationLoader().load(temporaryDirectory.toFile())
        }
    }

    private fun writeConfiguration(content: String) {
        val modules = Files.createDirectories(temporaryDirectory.resolve("modules"))
        Files.writeString(modules.resolve("redstone.yml"), content)
    }
}
