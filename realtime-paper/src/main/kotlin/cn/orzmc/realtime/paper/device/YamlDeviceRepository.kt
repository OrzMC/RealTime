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

    fun load(): List<RedstoneDevice> {
        if (!file.isFile) {
            save(emptyList())
            return emptyList()
        }

        val yaml = YamlConfiguration()
        try {
            yaml.load(file)
        } catch (exception: IOException) {
            throw DeviceRepositoryException("Unable to read $DEVICE_FILE", exception)
        } catch (exception: InvalidConfigurationException) {
            throw DeviceRepositoryException("Unable to parse $DEVICE_FILE: ${exception.message}", exception)
        }

        val schemaVersion = yaml.get("schema-version")
        if (schemaVersion !is Int || schemaVersion != SUPPORTED_SCHEMA_VERSION) {
            throw DeviceRepositoryException(
                "$DEVICE_FILE: schema-version must be $SUPPORTED_SCHEMA_VERSION",
            )
        }

        val rawDevices =
            yaml.get("devices") as? List<*>
                ?: throw DeviceRepositoryException("$DEVICE_FILE: devices must be a list")
        val devices =
            rawDevices.mapIndexed { index, value ->
                val raw =
                    value as? Map<*, *>
                        ?: throw DeviceRepositoryException("$DEVICE_FILE: devices[$index] must be a map")
                parseDevice(raw, index)
            }
        validateDevices(devices, "$DEVICE_FILE")
        return devices
    }

    fun save(devices: List<RedstoneDevice>) {
        validateDevices(devices, "Device store")

        file.parentFile?.mkdirs()
        val yaml = YamlConfiguration()
        yaml.set("schema-version", SUPPORTED_SCHEMA_VERSION)
        yaml.set(
            "devices",
            devices.map { device ->
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

    private fun validateDevices(
        devices: List<RedstoneDevice>,
        context: String,
    ) {
        if (devices.map(RedstoneDevice::id).toSet().size != devices.size) {
            throw DeviceRepositoryException("$context: device IDs must be unique")
        }
        val positions =
            devices.flatMap { device ->
                device.outputs.map { output ->
                    Triple(device.worldName, output.x, output.z) to output.y
                }
            }
        if (positions.toSet().size != positions.size) {
            throw DeviceRepositoryException("$context: output block positions must be unique across devices")
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

    private companion object {
        const val DEVICE_FILE = "data/devices.yml"
        const val BACKUP_FILE = "backups/devices.yml.bak"
        const val SUPPORTED_SCHEMA_VERSION = 1
    }
}
