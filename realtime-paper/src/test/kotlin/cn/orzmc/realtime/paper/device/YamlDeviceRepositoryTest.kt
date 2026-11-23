package cn.orzmc.realtime.paper.device

import cn.orzmc.realtime.core.device.DeviceOutput
import cn.orzmc.realtime.core.device.RedstoneDevice
import org.bukkit.configuration.file.YamlConfiguration
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
        val yaml = YamlConfiguration()
        yaml.load(temporaryDirectory.resolve("data/devices.yml").toFile())
        assertEquals(2, yaml.getInt("schema-version"))
        assertTrue(yaml.getList("cleanup-jobs").orEmpty().isEmpty())
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
    fun `persists and reloads cleanup jobs with world identity and per-output state`() {
        val repository = YamlDeviceRepository(temporaryDirectory.toFile())
        val source = device()
        val pendingJob = DeviceCleanupJob.fromDevice(source, UUID.randomUUID())
        val job =
            pendingJob.copy(
                outputs =
                    pendingJob.outputs.mapIndexed { index, output ->
                        when (index) {
                            0 -> DeviceCleanupOutput(output.output, DeviceCleanupStatus.CLEARED)
                            1 -> DeviceCleanupOutput(output.output, DeviceCleanupStatus.NEEDS_ATTENTION, "not redstone wire")
                            else -> output
                        }
                    },
            )

        repository.save(DeviceStoreSnapshot(devices = emptyList(), cleanupJobs = listOf(job)))

        val loaded = repository.loadStore().cleanupJobs.single()
        assertEquals(job, loaded)
        assertEquals(DeviceCleanupStatus.CLEARED, loaded.outputs[0].status)
        assertEquals("not redstone wire", loaded.outputs[1].detail)
    }

    @Test
    fun `migrates schema v1 devices to schema v2 without data loss`() {
        val device = device()
        val dataDirectory = Files.createDirectories(temporaryDirectory.resolve("data"))
        val legacy = YamlConfiguration()
        legacy.set("schema-version", 1)
        legacy.set(
            "devices",
            listOf(
                linkedMapOf(
                    "id" to device.id.toString(),
                    "name" to device.name,
                    "world" to device.worldName,
                    "enabled" to device.enabled,
                    "outputs" to
                        device.outputs.map { output ->
                            linkedMapOf(
                                "bit-index" to output.bitIndex,
                                "x" to output.x,
                                "y" to output.y,
                                "z" to output.z,
                            )
                        },
                ),
            ),
        )
        legacy.save(dataDirectory.resolve("devices.yml").toFile())

        val migrated = YamlDeviceRepository(temporaryDirectory.toFile()).loadStore()

        val migratedDevice = migrated.devices.single()
        assertEquals(device.id, migratedDevice.id)
        assertEquals(device.name, migratedDevice.name)
        assertEquals(device.worldName, migratedDevice.worldName)
        assertEquals(device.enabled, migratedDevice.enabled)
        assertEquals(device.outputs, migratedDevice.outputs)
        assertTrue(migrated.cleanupJobs.isEmpty())
        val stored = YamlConfiguration()
        stored.load(dataDirectory.resolve("devices.yml").toFile())
        assertEquals(2, stored.getInt("schema-version"))
        assertTrue(stored.getList("cleanup-jobs").orEmpty().isEmpty())
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
    fun `rejects active outputs that overlap cleanup reservations`() {
        val device = device()
        val job = DeviceCleanupJob.fromDevice(device, null)

        assertThrows(DeviceRepositoryException::class.java) {
            YamlDeviceRepository(temporaryDirectory.toFile()).save(
                DeviceStoreSnapshot(devices = listOf(device), cleanupJobs = listOf(job)),
            )
        }
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
