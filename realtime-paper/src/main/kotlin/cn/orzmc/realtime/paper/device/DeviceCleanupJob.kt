package cn.orzmc.realtime.paper.device

import cn.orzmc.realtime.core.device.DeviceOutput
import cn.orzmc.realtime.core.device.OutputResolution
import cn.orzmc.realtime.core.device.RedstoneDevice
import java.util.UUID

enum class DeviceCleanupStatus {
    PENDING,
    CLEARED,
    NEEDS_ATTENTION,
}

data class DeviceCleanupOutput(
    val output: DeviceOutput,
    val status: DeviceCleanupStatus = DeviceCleanupStatus.PENDING,
    val detail: String? = null,
) {
    init {
        if (status == DeviceCleanupStatus.NEEDS_ATTENTION) {
            require(!detail.isNullOrBlank()) { "NEEDS_ATTENTION cleanup output requires a detail" }
        } else {
            require(detail == null) { "$status cleanup output must not contain a detail" }
        }
    }
}

data class DeviceCleanupJob(
    val id: UUID,
    val deviceId: UUID,
    val deviceName: String,
    val worldName: String,
    val worldId: UUID?,
    val outputs: List<DeviceCleanupOutput>,
    val resolution: OutputResolution = OutputResolution.MINUTE,
) {
    init {
        require(deviceName.isNotBlank()) { "deviceName must not be blank" }
        require(worldName.isNotBlank()) { "worldName must not be blank" }
        require(outputs.size == resolution.outputCount) {
            "cleanup job for a ${resolution.configValue}-resolution device must contain exactly " +
                "${resolution.outputCount} outputs (${resolution.label})"
        }
        require(outputs.map { it.output.bitIndex }.toSet() == (0 until resolution.outputCount).toSet()) {
            "cleanup outputs must contain each bit index from 0 through ${resolution.outputCount - 1} exactly once"
        }
        require(outputs.map { it.output }.distinct().size == outputs.size) {
            "cleanup output coordinates must be unique"
        }
    }

    companion object {
        fun fromDevice(
            device: RedstoneDevice,
            worldId: UUID?,
        ): DeviceCleanupJob =
            DeviceCleanupJob(
                id = UUID.randomUUID(),
                deviceId = device.id,
                deviceName = device.name,
                worldName = device.worldName,
                worldId = worldId,
                outputs = device.outputs.map { output -> DeviceCleanupOutput(output) },
                resolution = device.resolution,
            )
    }
}

data class DeviceStoreSnapshot(
    val devices: List<RedstoneDevice>,
    val cleanupJobs: List<DeviceCleanupJob> = emptyList(),
)
