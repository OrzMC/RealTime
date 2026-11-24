package cn.orzmc.realtime.core.device

import java.util.UUID

class RedstoneDevice(
    val id: UUID,
    val name: String,
    val worldName: String,
    val enabled: Boolean,
    outputs: List<DeviceOutput>,
    val resolution: OutputResolution = OutputResolution.MINUTE,
) {
    val outputs: List<DeviceOutput> = outputs.sortedBy(DeviceOutput::bitIndex)

    init {
        require(name.isNotBlank()) { "Device name must not be blank" }
        require(worldName.isNotBlank()) { "World name must not be blank" }
        require(this.outputs.size == resolution.outputCount) {
            "A ${resolution.configValue}-resolution redstone device must define exactly " +
                "${resolution.outputCount} outputs (${resolution.label})"
        }
        require(this.outputs.map(DeviceOutput::bitIndex).toSet() == (0 until resolution.outputCount).toSet()) {
            "Device output bit indexes must cover 0 through ${resolution.outputCount - 1} exactly once"
        }
        require(
            this.outputs
                .map { Triple(it.x, it.y, it.z) }
                .toSet()
                .size == resolution.outputCount,
        ) {
            "Device output block positions must be unique"
        }
    }
}
