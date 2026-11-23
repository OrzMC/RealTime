package cn.orzmc.realtime.paper.device

import cn.orzmc.realtime.core.device.DeviceOutput
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

        val devices = parseDevices(yaml)
        val cleanupJobs =
            if (schemaVersion == LEGACY_SCHEMA_VERSION) {
                emptyList()
            } else {
                parseCleanupJobs(yaml)
            }
        val snapshot = DeviceStoreSnapshot(devices, cleanupJobs)
        validateStore(snapshot, "$DEVICE_FILE")

        // Schema v1 is migrated in-place through the same atomic replacement path.
        if (schemaVersion == LEGACY_SCHEMA_VERSION) save(snapshot)
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
                    "outputs" to
                        device.outputs.map { output ->
                            linkedMapOf(
                                "bit-index" to output.bitIndex,
                                "x" to output.x,
                                "y" to output.y,
                                "z" to output.z,
                            )
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
        return rawDevices.mapIndexed { index, value ->
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
        return rawJobs.mapIndexed { index, value ->
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
                device.outputs.map { output -> Position(device.worldName, output.x, output.y, output.z) }
            }
        if (activePositions.toSet().size != activePositions.size) {
            throw DeviceRepositoryException("$context: output block positions must be unique across devices")
        }

        val cleanupPositions =
            snapshot.cleanupJobs.flatMap { job ->
                job.outputs.map { output ->
                    Position(job.worldName, output.output.x, output.output.y, output.output.z)
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
    ): RedstoneDevice {
        val context = "$DEVICE_FILE: devices[$index]"
        val id = parseUuid(requiredString(raw, "id", context), "$context.id")
        val name = requiredString(raw, "name", context)
        val worldName = requiredString(raw, "world", context)
        val enabled =
            raw["enabled"] as? Boolean
                ?: throw DeviceRepositoryException("$context.enabled must be true or false")
        val rawOutputs =
            raw["outputs"] as? List<*>
                ?: throw DeviceRepositoryException("$context.outputs must be a list")
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
                )
            }

        return try {
            RedstoneDevice(id, name, worldName, enabled, outputs)
        } catch (exception: IllegalArgumentException) {
            throw DeviceRepositoryException("$context is invalid: ${exception.message}", exception)
        }
    }

    private fun parseCleanupJob(
        raw: Map<*, *>,
        index: Int,
    ): DeviceCleanupJob {
        val context = "$DEVICE_FILE: cleanup-jobs[$index]"
        val worldId =
            when (val value = raw["world-uuid"]) {
                null -> null
                is String -> parseUuid(value, "$context.world-uuid")
                else -> throw DeviceRepositoryException("$context.world-uuid must be a UUID or null")
            }
        val rawOutputs =
            raw["outputs"] as? List<*>
                ?: throw DeviceRepositoryException("$context.outputs must be a list")
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

        return try {
            DeviceCleanupJob(
                id = parseUuid(requiredString(raw, "id", context), "$context.id"),
                deviceId = parseUuid(requiredString(raw, "device-id", context), "$context.device-id"),
                deviceName = requiredString(raw, "device-name", context),
                worldName = requiredString(raw, "world", context),
                worldId = worldId,
                outputs = outputs,
            )
        } catch (exception: IllegalArgumentException) {
            throw DeviceRepositoryException("$context is invalid: ${exception.message}", exception)
        }
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
        const val SUPPORTED_SCHEMA_VERSION = 2
    }
}
