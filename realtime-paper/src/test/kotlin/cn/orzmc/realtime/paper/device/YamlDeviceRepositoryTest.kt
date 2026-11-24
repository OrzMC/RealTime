package cn.orzmc.realtime.paper.device

import cn.orzmc.realtime.core.device.DeviceOutput
import cn.orzmc.realtime.core.device.OutputEncoding
import cn.orzmc.realtime.core.device.OutputResolution
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

        assertTrue(repository.loadStore().devices.isEmpty())
        val yaml = YamlConfiguration()
        yaml.load(temporaryDirectory.resolve("data/devices.yml").toFile())
        assertEquals(4, yaml.getInt("schema-version"))
        assertTrue(yaml.getList("cleanup-jobs").orEmpty().isEmpty())
    }

    @Test
    fun `persists and reloads device records`() {
        val repository = YamlDeviceRepository(temporaryDirectory.toFile())
        val device = device()

        repository.save(DeviceStoreSnapshot(listOf(device)))

        val loaded = repository.loadStore().devices.single()
        assertEquals(device.id, loaded.id)
        assertEquals(device.name, loaded.name)
        assertEquals(device.worldName, loaded.worldName)
        assertEquals(device.enabled, loaded.enabled)
        assertEquals(device.outputs, loaded.outputs)
        assertEquals(OutputResolution.MINUTE, loaded.resolution)
    }

    @Test
    fun `persists and reloads second resolution devices`() {
        val repository = YamlDeviceRepository(temporaryDirectory.toFile())
        val device = device(resolution = OutputResolution.SECOND)

        repository.save(DeviceStoreSnapshot(listOf(device)))

        val loaded = repository.loadStore().devices.single()
        assertEquals(OutputResolution.SECOND, loaded.resolution)
        assertEquals(24, loaded.outputs.size)
        assertEquals(device.outputs, loaded.outputs)
        assertEquals(0, loaded.outputs.first().bitIndex)
        assertEquals(23, loaded.outputs.last().bitIndex)
    }

    @Test
    fun `persists second resolution cleanup jobs`() {
        val repository = YamlDeviceRepository(temporaryDirectory.toFile())
        val job = DeviceCleanupJob.fromDevice(device(resolution = OutputResolution.SECOND), null)

        repository.save(DeviceStoreSnapshot(devices = emptyList(), cleanupJobs = listOf(job)))

        val loaded = repository.loadStore().cleanupJobs.single()
        assertEquals(OutputResolution.SECOND, loaded.resolution)
        assertEquals(24, loaded.outputs.size)
    }

    @Test
    fun `rejects unsupported resolutions`() {
        val dataDirectory = Files.createDirectories(temporaryDirectory.resolve("data"))
        val device = device()
        val yaml = YamlConfiguration()
        yaml.set("schema-version", 3)
        yaml.set(
            "devices",
            listOf(
                linkedMapOf(
                    "id" to device.id.toString(),
                    "name" to device.name,
                    "world" to device.worldName,
                    "enabled" to device.enabled,
                    "resolution" to "hourly",
                    "outputs" to emptyList<Any>(),
                ),
            ),
        )
        yaml.set("cleanup-jobs", emptyList<Any>())
        yaml.save(dataDirectory.resolve("devices.yml").toFile())

        assertThrows(DeviceRepositoryException::class.java) {
            YamlDeviceRepository(temporaryDirectory.toFile()).loadStore()
        }
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
    fun `migrates schema v1 devices to the current schema without data loss`() {
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
        assertEquals(OutputResolution.MINUTE, migratedDevice.resolution)
        assertEquals(OutputEncoding.BCD, migratedDevice.encoding)
        assertTrue(migrated.cleanupJobs.isEmpty())
        val stored = YamlConfiguration()
        stored.load(dataDirectory.resolve("devices.yml").toFile())
        assertEquals(4, stored.getInt("schema-version"))
        assertEquals("minute", stored.getMapList("devices").single()["resolution"])
        assertEquals("bcd", stored.getMapList("devices").single()["encoding"])
        assertTrue(stored.getList("cleanup-jobs").orEmpty().isEmpty())
    }

    @Test
    fun `keeps a backup before replacing an existing device store`() {
        val repository = YamlDeviceRepository(temporaryDirectory.toFile())
        repository.save(DeviceStoreSnapshot(listOf(device(name = "first"))))
        repository.save(DeviceStoreSnapshot(listOf(device(name = "second"))))

        assertTrue(Files.isRegularFile(temporaryDirectory.resolve("backups/devices.yml.bak")))
        assertEquals(
            "second",
            repository
                .loadStore()
                .devices
                .single()
                .name,
        )
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
            YamlDeviceRepository(temporaryDirectory.toFile()).loadStore()
        }
    }

    @Test
    fun `persists and reloads analog devices one slot per digit`() {
        val repository = YamlDeviceRepository(temporaryDirectory.toFile())
        val device = device(resolution = OutputResolution.SECOND, encoding = OutputEncoding.ANALOG)

        repository.save(DeviceStoreSnapshot(listOf(device)))

        val loaded = repository.loadStore().devices.single()
        assertEquals(OutputEncoding.ANALOG, loaded.encoding)
        assertEquals(OutputResolution.SECOND, loaded.resolution)
        assertEquals(6, loaded.outputs.size)
        assertEquals(device.outputs, loaded.outputs)
        assertEquals(5, loaded.outputs.last().bitIndex)
    }

    @Test
    fun `migrates schema v3 devices without an encoding to bcd`() {
        val device = device()
        val dataDirectory = Files.createDirectories(temporaryDirectory.resolve("data"))
        val legacy = YamlConfiguration()
        legacy.set("schema-version", 3)
        legacy.set(
            "devices",
            listOf(
                linkedMapOf(
                    "id" to device.id.toString(),
                    "name" to device.name,
                    "world" to device.worldName,
                    "enabled" to device.enabled,
                    "resolution" to "minute",
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
        legacy.set("cleanup-jobs", emptyList<Any>())
        legacy.save(dataDirectory.resolve("devices.yml").toFile())

        val migrated = YamlDeviceRepository(temporaryDirectory.toFile()).loadStore()

        assertEquals(OutputEncoding.BCD, migrated.devices.single().encoding)
        val stored = YamlConfiguration()
        stored.load(dataDirectory.resolve("devices.yml").toFile())
        assertEquals(4, stored.getInt("schema-version"))
        assertEquals("bcd", stored.getMapList("devices").single()["encoding"])
    }

    @Test
    fun `rejects unsupported encodings`() {
        val repository = YamlDeviceRepository(temporaryDirectory.toFile())
        repository.save(DeviceStoreSnapshot(listOf(device())))
        val path = temporaryDirectory.resolve("data/devices.yml")
        val yaml = YamlConfiguration()
        yaml.load(path.toFile())
        val devices = yaml.getMapList("devices").toMutableList()
        devices[0] = LinkedHashMap(devices[0]).also { it["encoding"] = "hexadecimal" }
        yaml.set("devices", devices)
        yaml.save(path.toFile())

        val exception = assertThrows(DeviceRepositoryException::class.java) { repository.loadStore() }
        assertTrue(exception.message!!.contains("encoding"))
    }

    private fun device(
        name: String = "clock",
        resolution: OutputResolution = OutputResolution.MINUTE,
        encoding: OutputEncoding = OutputEncoding.BCD,
    ) = RedstoneDevice(
        id = UUID.randomUUID(),
        name = name,
        worldName = "world",
        enabled = true,
        outputs =
            (0 until encoding.outputCount(resolution)).map { index ->
                DeviceOutput(bitIndex = index, x = index * 2, y = 64, z = 0)
            },
        resolution = resolution,
        encoding = encoding,
    )
}
