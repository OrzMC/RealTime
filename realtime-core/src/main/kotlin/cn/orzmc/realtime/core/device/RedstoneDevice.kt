package cn.orzmc.realtime.core.device

import java.util.UUID

/**
 * A configured clock device.
 *
 * Every device is a row of lecterns, one per clock digit: [resolution] decides how
 * many digits there are (`HHmm` → 4, `HHmmss` → 6) and therefore how many slots
 * the device must define.
 */
class RedstoneDevice(
    val id: UUID,
    val name: String,
    val worldName: String,
    val enabled: Boolean,
    outputs: List<DeviceOutput>,
    val resolution: OutputResolution = OutputResolution.SECOND,
    /**
     * Display blocks of a created device: comparators, dust and lamps the plugin
     * places and removes together with the device. Empty for a device built with
     * `manual`/`nodisplay`, which only ever touches its own slots.
     */
    val demoBlocks: List<DemoBlock> = emptyList(),
) {
    val outputs: List<DeviceOutput> = outputs.sortedBy(DeviceOutput::bitIndex)

    /** Slots whose position has not been chosen yet (`create ... manual`). */
    val unplacedOutputs: List<DeviceOutput>
        get() = this.outputs.filter(DeviceOutput::unplaced)

    /** Number of output slots this device must define: one per digit. */
    val outputCount: Int
        get() = resolution.digitCount

    init {
        require(name.isNotBlank()) { "Device name must not be blank" }
        require(worldName.isNotBlank()) { "World name must not be blank" }
        require(this.outputs.size == outputCount) {
            "A ${resolution.configValue}-resolution device must define exactly $outputCount outputs (${resolution.label})"
        }
        require(this.outputs.map(DeviceOutput::bitIndex).toSet() == (0 until outputCount).toSet()) {
            "Device output slot indexes must cover 0 through ${outputCount - 1} exactly once"
        }
        val placedOutputs = this.outputs.filterNot(DeviceOutput::unplaced)
        require(
            placedOutputs.map { Triple(it.x, it.y, it.z) }.toSet().size == placedOutputs.size,
        ) {
            "Device output block positions must be unique"
        }
        val slotPositions = placedOutputs.map { Triple(it.x, it.y, it.z) }.toSet()
        require(
            this.demoBlocks
                .map { Triple(it.x, it.y, it.z) }
                .toSet()
                .size == demoBlocks.size,
        ) {
            "Device display block positions must be unique"
        }
        require(this.demoBlocks.none { Triple(it.x, it.y, it.z) in slotPositions }) {
            "Device display blocks must not overlap output slots"
        }
    }

    /** True when the plugin also owns display blocks for this device. */
    val hasDisplay: Boolean
        get() = demoBlocks.isNotEmpty()
}
