package cn.orzmc.realtime.paper.device

import cn.orzmc.realtime.core.device.DemoBlock
import cn.orzmc.realtime.core.device.DeviceOutput
import cn.orzmc.realtime.core.device.OutputResolution
import cn.orzmc.realtime.core.device.RedstoneDevice
import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
        assertEquals(7, yaml.getInt("schema-version"))
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
    fun `persists one slot per digit for both resolutions`() {
        val repository = YamlDeviceRepository(temporaryDirectory.toFile())
        val minute = device(resolution = OutputResolution.MINUTE)
        val second = device(name = "second", resolution = OutputResolution.SECOND, startX = 100)

        repository.save(DeviceStoreSnapshot(listOf(minute, second)))

        val loaded = repository.loadStore().devices.sortedBy { it.name }
        assertEquals(listOf(4, 6), loaded.map { it.outputs.size })
        assertEquals(
            5,
            loaded
                .last()
                .outputs
                .last()
                .bitIndex,
        )
        assertTrue(loaded.all { it.outputs.none(DeviceOutput::unplaced) })
    }

    @Test
    fun `persists a build together with its display blocks`() {
        val repository = YamlDeviceRepository(temporaryDirectory.toFile())
        val device = device(demo = true)

        repository.save(DeviceStoreSnapshot(listOf(device)))

        val loaded = repository.loadStore().devices.single()
        assertEquals(device.demoBlocks, loaded.demoBlocks)
        assertTrue(loaded.hasDisplay)
        assertFalse(device().hasDisplay)
    }

    @Test
    fun `keeps the unplaced flag of hand-positioned slots`() {
        val repository = YamlDeviceRepository(temporaryDirectory.toFile())
        val device =
            RedstoneDevice(
                id = UUID.randomUUID(),
                name = "manual",
                worldName = "world",
                enabled = false,
                outputs =
                    (0 until 6).map { index ->
                        DeviceOutput(index, 0, 64, 0, unplaced = index != 3)
                    },
                resolution = OutputResolution.SECOND,
            )

        repository.save(DeviceStoreSnapshot(listOf(device)))

        val loaded = repository.loadStore().devices.single()
        assertEquals(listOf(3), loaded.outputs.filterNot(DeviceOutput::unplaced).map(DeviceOutput::bitIndex))
        assertEquals(5, loaded.unplacedOutputs.size)
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
                            1 ->
                                DeviceCleanupOutput(
                                    output.output,
                                    DeviceCleanupStatus.NEEDS_ATTENTION,
                                    "lectern holds a foreign book",
                                )
                            else -> output
                        }
                    },
            )

        repository.save(DeviceStoreSnapshot(devices = emptyList(), cleanupJobs = listOf(job)))

        val loaded = repository.loadStore().cleanupJobs.single()
        assertEquals(job, loaded)
        assertEquals(DeviceCleanupStatus.CLEARED, loaded.outputs[0].status)
        assertEquals("lectern holds a foreign book", loaded.outputs[1].detail)
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
    fun `rejects an unsupported resolution`() {
        val repository = YamlDeviceRepository(temporaryDirectory.toFile())
        repository.save(DeviceStoreSnapshot(listOf(device())))
        val path = temporaryDirectory.resolve("data/devices.yml")
        val yaml = YamlConfiguration()
        yaml.load(path.toFile())
        val devices = yaml.getMapList("devices").toMutableList()
        devices[0] = LinkedHashMap(devices[0]).also { it["resolution"] = "hour" }
        yaml.set("devices", devices)
        yaml.save(path.toFile())

        val exception = assertThrows(DeviceRepositoryException::class.java) { repository.loadStore() }
        assertTrue(exception.message!!.contains("resolution"))
    }

    @Test
    fun `skips clocks built with the removed BCD encoding and reports them`() {
        val repository = YamlDeviceRepository(temporaryDirectory.toFile())
        val lecternClock = device(name = "lobby")
        repository.save(DeviceStoreSnapshot(listOf(lecternClock)))

        val path = temporaryDirectory.resolve("data/devices.yml")
        val yaml = YamlConfiguration()
        yaml.load(path.toFile())
        val stored = yaml.getMapList("devices").toMutableList()
        // An entry written by 0.5.0 still carries `encoding`; the plugin no longer
        // understands the BCD geometry, so it is skipped instead of mis-loaded.
        stored[0] = LinkedHashMap(stored[0]).also { it["encoding"] = "bcd" }
        stored += LinkedHashMap(stored[0]).also { it["name"] = "second-bcd" }
        yaml.set("devices", stored)
        yaml.save(path.toFile())

        val loaded = repository.loadStore()

        assertTrue(loaded.devices.isEmpty())
        assertEquals(2, repository.skippedLegacyEntries.size)
        assertTrue(repository.skippedLegacyEntries.all { it.contains("bcd") || it.contains("lobby") })
    }

    @Test
    fun `treats a missing encoding as a legacy BCD entry`() {
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

        val repository = YamlDeviceRepository(temporaryDirectory.toFile())
        val migrated = repository.loadStore()

        assertTrue(migrated.devices.isEmpty())
        assertEquals(1, repository.skippedLegacyEntries.size)
        val stored = YamlConfiguration()
        stored.load(dataDirectory.resolve("devices.yml").toFile())
        assertEquals(7, stored.getInt("schema-version"))
    }

    private fun device(
        name: String = "clock",
        resolution: OutputResolution = OutputResolution.MINUTE,
        demo: Boolean = false,
        startX: Int = 0,
    ) = RedstoneDevice(
        id = UUID.randomUUID(),
        name = name,
        worldName = "world",
        enabled = true,
        outputs =
            (0 until resolution.digitCount).map { index ->
                DeviceOutput(bitIndex = index, x = startX + index * 2, y = 64, z = 0)
            },
        resolution = resolution,
        demoBlocks =
            if (demo) {
                listOf(DemoBlock(startX, 65, 0, "minecraft:redstone_lamp"))
            } else {
                emptyList()
            },
    )
}
