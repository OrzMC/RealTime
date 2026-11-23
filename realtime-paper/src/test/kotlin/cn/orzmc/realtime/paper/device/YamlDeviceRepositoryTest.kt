package cn.orzmc.realtime.paper.device

import cn.orzmc.realtime.core.device.DeviceOutput
import cn.orzmc.realtime.core.device.RedstoneDevice
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

class YamlDeviceRepositoryTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `creates an empty versioned device store when missing`() {
        val repository = YamlDeviceRepository(temporaryDirectory.toFile())

        assertTrue(repository.load().isEmpty())
        assertTrue(Files.isRegularFile(temporaryDirectory.resolve("data/devices.yml")))
    }

    @Test
    fun `persists and reloads device records`() {
        val repository = YamlDeviceRepository(temporaryDirectory.toFile())
        val device = device()

        repository.save(listOf(device))

        val loaded = repository.load().single()
        assertEquals(device.id, loaded.id)
        assertEquals(device.name, loaded.name)
        assertEquals(device.worldName, loaded.worldName)
        assertEquals(device.enabled, loaded.enabled)
        assertEquals(device.outputs, loaded.outputs)
    }

    @Test
    fun `keeps a backup before replacing an existing device store`() {
        val repository = YamlDeviceRepository(temporaryDirectory.toFile())
        repository.save(listOf(device(name = "first")))
        repository.save(listOf(device(name = "second")))

        assertTrue(Files.isRegularFile(temporaryDirectory.resolve("backups/devices.yml.bak")))
        assertEquals("second", repository.load().single().name)
    }

    @Test
    fun `rejects unsupported device schema versions`() {
        val dataDirectory = Files.createDirectories(temporaryDirectory.resolve("data"))
        Files.writeString(dataDirectory.resolve("devices.yml"), "schema-version: 99\ndevices: []\n")

        assertThrows(DeviceRepositoryException::class.java) {
            YamlDeviceRepository(temporaryDirectory.toFile()).load()
        }
    }

    private fun device(name: String = "clock") =
        RedstoneDevice(
            id = UUID.randomUUID(),
            name = name,
            worldName = "world",
            enabled = true,
            outputs =
                (0 until 16).map { index ->
                    DeviceOutput(bitIndex = index, x = index * 2, y = 64, z = 0)
                },
        )
}
