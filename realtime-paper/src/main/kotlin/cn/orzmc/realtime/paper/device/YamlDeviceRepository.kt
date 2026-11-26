package cn.orzmc.realtime.paper.device

import cn.orzmc.realtime.core.device.DemoBlock
import cn.orzmc.realtime.core.device.DeviceOutput
import cn.orzmc.realtime.core.device.OutputResolution
import cn.orzmc.realtime.core.device.RedstoneDevice
import org.bukkit.configuration.InvalidConfigurationException
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

class YamlDeviceRepository(
    dataFolder: File,
) {
    private val file = File(dataFolder, DEVICE_FILE)
    private val backupFile = File(dataFolder, BACKUP_FILE)

    private val skippedLegacy = mutableListOf<String>()

    /**
     * True while loading a file written by a version that only knew the removed
     * BCD encoding: there an absent `encoding` means BCD, so the entry is skipped.
     * From schema 7 on, `encoding` is simply not written any more.
     */
    private var skippedLegacyOnly = false

    /** Legacy BCD entries met by the last [loadStore] call; reported by the plugin. */
    var skippedLegacyEntries: List<String> = emptyList()
        private set

    fun loadStore(): DeviceStoreSnapshot {
        if (!file.isFile) {
            val empty = DeviceStoreSnapshot(emptyList())
            save(empty)
            return empty
        }

        val yaml = YamlConfiguration()
        try {
            yaml.load(file)
        } catch (exception: IOException) {
            throw DeviceRepositoryException("Unable to read $DEVICE_FILE", exception)
        } catch (exception: InvalidConfigurationException) {
            throw DeviceRepositoryException("Unable to parse $DEVICE_FILE: ${exception.message}", exception)
        }

        val schemaVersion =
            yaml.get("schema-version") as? Int
                ?: throw DeviceRepositoryException("$DEVICE_FILE: schema-version must be an integer")
        if (schemaVersion !in LEGACY_SCHEMA_VERSION..SUPPORTED_SCHEMA_VERSION) {
            throw DeviceRepositoryException(
                "$DEVICE_FILE: unsupported schema-version $schemaVersion (supported: 1..$SUPPORTED_SCHEMA_VERSION)",
            )
        }

        skippedLegacy.clear()
        skippedLegacyOnly = schemaVersion < SUPPORTED_SCHEMA_VERSION
        val devices = parseDevices(yaml)
        val cleanupJobs =
            if (schemaVersion == LEGACY_SCHEMA_VERSION) {
                emptyList()
            } else {
                parseCleanupJobs(yaml)
            }
        skippedLegacyEntries = skippedLegacy.toList()
        val snapshot = DeviceStoreSnapshot(devices, cleanupJobs)
        validateStore(snapshot, "$DEVICE_FILE")

        // Older schemas are migrated in-place through the same atomic replacement path.
        if (schemaVersion < SUPPORTED_SCHEMA_VERSION) save(snapshot)
        return snapshot
    }

    fun save(snapshot: DeviceStoreSnapshot) {
        validateStore(snapshot, "Device store")

        file.parentFile?.mkdirs()
        val yaml = YamlConfiguration()
        yaml.set("schema-version", SUPPORTED_SCHEMA_VERSION)
        yaml.set(
            "devices",
            snapshot.devices.map { device ->
                linkedMapOf(
                    "id" to device.id.toString(),
                    "name" to device.name,
                    "world" to device.worldName,
                    "enabled" to device.enabled,
                    "resolution" to device.resolution.configValue,
                    "demo" to
                        device.demoBlocks.map { block ->
                            linkedMapOf(
                                "x" to block.x,
                                "y" to block.y,
                                "z" to block.z,
                                "data" to block.data,
                            )
                        },
                    "outputs" to
                        device.outputs.map { output ->
                            val entry =
                                linkedMapOf<String, Any>(
                                    "bit-index" to output.bitIndex,
                                    "x" to output.x,
                                    "y" to output.y,
                                    "z" to output.z,
                                )
                            if (output.unplaced) entry["unplaced"] = true
                            entry
                        },
                )
            },
        )
        yaml.set(
            "cleanup-jobs",
            snapshot.cleanupJobs.map { job ->
                linkedMapOf(
                    "id" to job.id.toString(),
                    "device-id" to job.deviceId.toString(),
                    "device-name" to job.deviceName,
                    "world" to job.worldName,
                    "world-uuid" to job.worldId?.toString(),
                    "resolution" to job.resolution.configValue,
                    "display" to
                        job.display.map { demoOutput ->
                            linkedMapOf(
                                "x" to demoOutput.block.x,
                                "y" to demoOutput.block.y,
                                "z" to demoOutput.block.z,
                                "data" to demoOutput.block.data,
                                "status" to demoOutput.status.name,
                                "detail" to demoOutput.detail,
                            )
                        },
                    "outputs" to
                        job.outputs.map { cleanupOutput ->
                            linkedMapOf(
                                "bit-index" to cleanupOutput.output.bitIndex,
                                "x" to cleanupOutput.output.x,
                                "y" to cleanupOutput.output.y,
                                "z" to cleanupOutput.output.z,
                                "status" to cleanupOutput.status.name,
                                "detail" to cleanupOutput.detail,
                            )
                        },
                )
            },
        )

        val temporaryFile = File(file.parentFile, "${file.name}.tmp")
        try {
            yaml.save(temporaryFile)
            if (file.isFile) {
                backupFile.parentFile?.mkdirs()
                file.copyTo(backupFile, overwrite = true)
            }
            try {
                Files.move(
                    temporaryFile.toPath(),
                    file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    temporaryFile.toPath(),
                    file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
        } catch (exception: IOException) {
            throw DeviceRepositoryException("Unable to save $DEVICE_FILE", exception)
        } finally {
            temporaryFile.delete()
        }
    }

    private fun parseDevices(yaml: YamlConfiguration): List<RedstoneDevice> {
        val rawDevices =
            yaml.get("devices") as? List<*>
                ?: throw DeviceRepositoryException("$DEVICE_FILE: devices must be a list")
        return rawDevices.mapIndexedNotNull { index, value ->
            val raw =
                value as? Map<*, *>
                    ?: throw DeviceRepositoryException("$DEVICE_FILE: devices[$index] must be a map")
            parseDevice(raw, index)
        }
    }

    private fun parseCleanupJobs(yaml: YamlConfiguration): List<DeviceCleanupJob> {
        val rawJobs =
            yaml.get("cleanup-jobs") as? List<*>
                ?: throw DeviceRepositoryException("$DEVICE_FILE: cleanup-jobs must be a list")
        return rawJobs.mapIndexedNotNull { index, value ->
            val raw =
                value as? Map<*, *>
                    ?: throw DeviceRepositoryException("$DEVICE_FILE: cleanup-jobs[$index] must be a map")
            parseCleanupJob(raw, index)
        }
    }

    private fun validateStore(
        snapshot: DeviceStoreSnapshot,
        context: String,
    ) {
        if (
            snapshot.devices
                .map(RedstoneDevice::id)
                .toSet()
                .size != snapshot.devices.size
        ) {
            throw DeviceRepositoryException("$context: device IDs must be unique")
        }
        if (
            snapshot.cleanupJobs
                .map(DeviceCleanupJob::id)
                .toSet()
                .size != snapshot.cleanupJobs.size
        ) {
            throw DeviceRepositoryException("$context: cleanup job IDs must be unique")
        }

        val activePositions =
            snapshot.devices.flatMap { device ->
                device.outputs.filterNot(DeviceOutput::unplaced).map { output ->
                    Position(device.worldName, output.x, output.y, output.z)
                } + device.demoBlocks.map { block -> Position(device.worldName, block.x, block.y, block.z) }
            }
        if (activePositions.toSet().size != activePositions.size) {
            throw DeviceRepositoryException("$context: output block positions must be unique across devices")
        }

        val cleanupPositions =
            snapshot.cleanupJobs.flatMap { job ->
                job.outputs.map { output ->
                    Position(job.worldName, output.output.x, output.output.y, output.output.z)
                } +
                    job.display.map { demoOutput ->
                        Position(job.worldName, demoOutput.block.x, demoOutput.block.y, demoOutput.block.z)
                    }
            }
        if (cleanupPositions.toSet().size != cleanupPositions.size) {
            throw DeviceRepositoryException("$context: cleanup output positions must be unique across jobs")
        }
        if (activePositions.any { it in cleanupPositions.toSet() }) {
            throw DeviceRepositoryException("$context: active devices must not overlap pending cleanup positions")
        }
    }

    private fun parseDevice(
        raw: Map<*, *>,
        index: Int,
    ): RedstoneDevice? {
        val context = "$DEVICE_FILE: devices[$index]"
        if (isLegacyBcdEntry(raw)) {
            skippedLegacy += "$context (${raw["name"] ?: "unnamed"})"
            return null
        }
        val id = parseUuid(requiredString(raw, "id", context), "$context.id")
        val name = requiredString(raw, "name", context)
        val worldName = requiredString(raw, "world", context)
        val enabled =
            raw["enabled"] as? Boolean
                ?: throw DeviceRepositoryException("$context.enabled must be true or false")
        val rawOutputs =
            raw["outputs"] as? List<*>
                ?: throw DeviceRepositoryException("$context.outputs must be a list")
        val resolution = parseResolution(raw, context)
        val outputs =
            rawOutputs.mapIndexed { outputIndex, value ->
                val output =
                    value as? Map<*, *>
                        ?: throw DeviceRepositoryException("$context.outputs[$outputIndex] must be a map")
                DeviceOutput(
                    bitIndex = requiredInt(output, "bit-index", "$context.outputs[$outputIndex]"),
                    x = requiredInt(output, "x", "$context.outputs[$outputIndex]"),
                    y = requiredInt(output, "y", "$context.outputs[$outputIndex]"),
                    z = requiredInt(output, "z", "$context.outputs[$outputIndex]"),
                    unplaced = (output["unplaced"] as? Boolean) ?: false,
                )
            }

        val demoBlocks = parseDemoBlocks(raw, context)
        return try {
            RedstoneDevice(id, name, worldName, enabled, outputs, resolution, demoBlocks)
        } catch (exception: IllegalArgumentException) {
            throw DeviceRepositoryException("$context is invalid: ${exception.message}", exception)
        }
    }

    private fun parseCleanupJob(
        raw: Map<*, *>,
        index: Int,
    ): DeviceCleanupJob? {
        val context = "$DEVICE_FILE: cleanup-jobs[$index]"
        if (isLegacyBcdEntry(raw)) {
            skippedLegacy += "$context (${raw["device-name"] ?: "unnamed"})"
            return null
        }
        val worldId =
            when (val value = raw["world-uuid"]) {
                null -> null
                is String -> parseUuid(value, "$context.world-uuid")
                else -> throw DeviceRepositoryException("$context.world-uuid must be a UUID or null")
            }
        val rawOutputs =
            raw["outputs"] as? List<*>
                ?: throw DeviceRepositoryException("$context.outputs must be a list")
        val resolution = parseResolution(raw, context)
        val outputs =
            rawOutputs.mapIndexed { outputIndex, value ->
                val output =
                    value as? Map<*, *>
                        ?: throw DeviceRepositoryException("$context.outputs[$outputIndex] must be a map")
                val outputContext = "$context.outputs[$outputIndex]"
                val deviceOutput =
                    DeviceOutput(
                        bitIndex = requiredInt(output, "bit-index", outputContext),
                        x = requiredInt(output, "x", outputContext),
                        y = requiredInt(output, "y", outputContext),
                        z = requiredInt(output, "z", outputContext),
                    )
                val statusName = requiredString(output, "status", outputContext)
                val status =
                    try {
                        DeviceCleanupStatus.valueOf(statusName)
                    } catch (exception: IllegalArgumentException) {
                        throw DeviceRepositoryException("$outputContext.status is unsupported: $statusName", exception)
                    }
                val detail =
                    when (val value = output["detail"]) {
                        null -> null
                        is String -> value
                        else -> throw DeviceRepositoryException("$outputContext.detail must be a string or null")
                    }
                try {
                    DeviceCleanupOutput(deviceOutput, status, detail)
                } catch (exception: IllegalArgumentException) {
                    throw DeviceRepositoryException("$outputContext is invalid: ${exception.message}", exception)
                }
            }

        val display = parseDemoCleanup(raw, context)
        return try {
            DeviceCleanupJob(
                id = parseUuid(requiredString(raw, "id", context), "$context.id"),
                deviceId = parseUuid(requiredString(raw, "device-id", context), "$context.device-id"),
                deviceName = requiredString(raw, "device-name", context),
                worldName = requiredString(raw, "world", context),
                worldId = worldId,
                outputs = outputs,
                resolution = resolution,
                display = display,
            )
        } catch (exception: IllegalArgumentException) {
            throw DeviceRepositoryException("$context is invalid: ${exception.message}", exception)
        }
    }

    private fun parseDemoBlocks(
        raw: Map<*, *>,
        context: String,
    ): List<DemoBlock> {
        val values =
            when (val demo = raw["demo"]) {
                null -> return emptyList()
                is List<*> -> demo
                else -> throw DeviceRepositoryException("$context.demo must be a list")
            }
        return values.mapIndexed { index, value ->
            val block =
                value as? Map<*, *>
                    ?: throw DeviceRepositoryException("$context.demo[$index] must be a map")
            val blockContext = "$context.demo[$index]"
            try {
                DemoBlock(
                    x = requiredInt(block, "x", blockContext),
                    y = requiredInt(block, "y", blockContext),
                    z = requiredInt(block, "z", blockContext),
                    data = requiredString(block, "data", blockContext),
                )
            } catch (exception: IllegalArgumentException) {
                throw DeviceRepositoryException("$blockContext is invalid: ${exception.message}", exception)
            }
        }
    }

    private fun parseDemoCleanup(
        raw: Map<*, *>,
        context: String,
    ): List<DeviceCleanupDemo> {
        val values =
            when (val display = raw["display"]) {
                null -> return emptyList()
                is List<*> -> display
                else -> throw DeviceRepositoryException("$context.display must be a list")
            }
        return values.mapIndexed { index, value ->
            val block =
                value as? Map<*, *>
                    ?: throw DeviceRepositoryException("$context.display[$index] must be a map")
            val blockContext = "$context.display[$index]"
            val statusName = requiredString(block, "status", blockContext)
            val status =
                try {
                    DeviceCleanupStatus.valueOf(statusName)
                } catch (exception: IllegalArgumentException) {
                    throw DeviceRepositoryException("$blockContext.status is unsupported: $statusName", exception)
                }
            val detail =
                when (val value = block["detail"]) {
                    null -> null
                    is String -> value
                    else -> throw DeviceRepositoryException("$blockContext.detail must be a string or null")
                }
            try {
                DeviceCleanupDemo(
                    block =
                        DemoBlock(
                            x = requiredInt(block, "x", blockContext),
                            y = requiredInt(block, "y", blockContext),
                            z = requiredInt(block, "z", blockContext),
                            data = requiredString(block, "data", blockContext),
                        ),
                    status = status,
                    detail = detail,
                )
            } catch (exception: IllegalArgumentException) {
                throw DeviceRepositoryException("$blockContext is invalid: ${exception.message}", exception)
            }
        }
    }

    private fun parseResolution(
        values: Map<*, *>,
        context: String,
    ): OutputResolution {
        val value =
            when (val raw = values["resolution"]) {
                null -> return OutputResolution.SECOND
                is String -> raw
                else -> throw DeviceRepositoryException("$context.resolution must be a string")
            }
        return OutputResolution.fromConfig(value)
            ?: throw DeviceRepositoryException(
                "$context.resolution is unsupported: $value (supported: " +
                    OutputResolution.entries.joinToString { it.configValue } +
                    ")",
            )
    }

    /**
     * True for an entry written by a version that still supported the removed
     * BCD encoding. Those devices (and their pending cleanup jobs) are skipped
     * instead of being loaded as a lectern device with the wrong geometry.
     */
    private fun isLegacyBcdEntry(values: Map<*, *>): Boolean =
        when (val raw = values["encoding"]) {
            null -> skippedLegacyOnly
            is String -> raw.lowercase() == "bcd" || raw.lowercase() == "binary"
            else -> throw DeviceRepositoryException("encoding must be a string")
        }

    private fun requiredString(
        values: Map<*, *>,
        key: String,
        context: String,
    ): String =
        (values[key] as? String)?.takeIf(String::isNotBlank)
            ?: throw DeviceRepositoryException("$context.$key must be a non-empty string")

    private fun requiredInt(
        values: Map<*, *>,
        key: String,
        context: String,
    ): Int =
        values[key] as? Int
            ?: throw DeviceRepositoryException("$context.$key must be an integer")

    private fun parseUuid(
        value: String,
        context: String,
    ): UUID =
        try {
            UUID.fromString(value)
        } catch (exception: IllegalArgumentException) {
            throw DeviceRepositoryException("$context must be a UUID", exception)
        }

    private data class Position(
        val worldName: String,
        val x: Int,
        val y: Int,
        val z: Int,
    )

    private companion object {
        const val DEVICE_FILE = "data/devices.yml"
        const val BACKUP_FILE = "backups/devices.yml.bak"
        const val LEGACY_SCHEMA_VERSION = 1
        const val SUPPORTED_SCHEMA_VERSION = 7
    }
}
