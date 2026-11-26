package cn.orzmc.realtime.paper.device

import cn.orzmc.realtime.core.device.DemoBlock
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

/** One display block of a demo build, cleared after the device slots. */
data class DeviceCleanupDemo(
    val block: DemoBlock,
    val status: DeviceCleanupStatus = DeviceCleanupStatus.PENDING,
    val detail: String? = null,
) {
    init {
        if (status == DeviceCleanupStatus.NEEDS_ATTENTION) {
            require(!detail.isNullOrBlank()) { "NEEDS_ATTENTION cleanup demo requires a detail" }
        } else {
            require(detail == null) { "$status cleanup demo must not contain a detail" }
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
    val resolution: OutputResolution = OutputResolution.SECOND,
    /** Display blocks of a created device; empty for a plain device. */
    val display: List<DeviceCleanupDemo> = emptyList(),
) {
    init {
        require(deviceName.isNotBlank()) { "deviceName must not be blank" }
        require(worldName.isNotBlank()) { "worldName must not be blank" }
        val outputCount = resolution.digitCount
        require(outputs.size == outputCount) {
            "cleanup job for a ${resolution.configValue}-resolution device must contain exactly $outputCount outputs " +
                "(${resolution.label})"
        }
        require(outputs.map { it.output.bitIndex }.toSet() == (0 until outputCount).toSet()) {
            "cleanup outputs must contain each slot index from 0 through ${outputCount - 1} exactly once"
        }
        require(outputs.map { it.output }.distinct().size == outputs.size) {
            "cleanup output coordinates must be unique"
        }
        require(display.map { it.block }.distinct().size == display.size) {
            "cleanup demo block positions must be unique"
        }
        val slotPositions = outputs.map { Triple(it.output.x, it.output.y, it.output.z) }.toSet()
        require(display.none { Triple(it.block.x, it.block.y, it.block.z) in slotPositions }) {
            "cleanup demo blocks must not overlap output slots"
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
                display = device.demoBlocks.map { block -> DeviceCleanupDemo(block) },
            )
    }
}

data class DeviceStoreSnapshot(
    val devices: List<RedstoneDevice>,
    val cleanupJobs: List<DeviceCleanupJob> = emptyList(),
)
