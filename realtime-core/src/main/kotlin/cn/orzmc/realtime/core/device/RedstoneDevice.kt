package cn.orzmc.realtime.core.device

class RedstoneDevice(
    val id: UUID,
    val name: String,
    val worldName: String,
    val enabled: Boolean,
    outputs: List<DeviceOutput>,
) {
    val outputs: List<DeviceOutput> = outputs.sortedBy(DeviceOutput::bitIndex)

    init {
        require(name.isNotBlank()) { "Device name must not be blank" }
        require(worldName.isNotBlank()) { "World name must not be blank" }
        require(this.outputs.size == DeviceOutput.OUTPUT_COUNT) {
            "A redstone device must define exactly ${DeviceOutput.OUTPUT_COUNT} outputs"
        }
        require(this.outputs.map(DeviceOutput::bitIndex).toSet() == (0 until DeviceOutput.OUTPUT_COUNT).toSet()) {
            "Device output bit indexes must cover 0 through ${DeviceOutput.OUTPUT_COUNT - 1} exactly once"
        }
        require(this.outputs.map { Triple(it.x, it.y, it.z) }.toSet().size == DeviceOutput.OUTPUT_COUNT) {
            "Device output block positions must be unique"
        }
    }
}
