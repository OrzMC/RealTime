package cn.orzmc.realtime.core.device

import java.util.UUID

/**
 * A configured clock device.
 *
 * [outputs] holds one slot per bit ([OutputEncoding.BCD]) or one slot per digit
 * ([OutputEncoding.ANALOG]); the slot count therefore depends on both
 * [resolution] and [encoding].
 */
class RedstoneDevice(
    val id: UUID,
    val name: String,
    val worldName: String,
    val enabled: Boolean,
    outputs: List<DeviceOutput>,
    val resolution: OutputResolution = OutputResolution.MINUTE,
    val encoding: OutputEncoding = OutputEncoding.BCD,
    /**
     * Display blocks of a `device demo` build: lamps, comparators and dust the
     * plugin places and removes together with the device. Empty for a normal
     * device, which only ever touches its own slots.
     */
    val demoBlocks: List<DemoBlock> = emptyList(),
) {
    val outputs: List<DeviceOutput> = outputs.sortedBy(DeviceOutput::bitIndex)

    /** Slots whose position has not been chosen yet (`device create ... manual`). */
    val unplacedOutputs: List<DeviceOutput>
        get() = outputs.filter(DeviceOutput::unplaced)

    /** Number of output slots this device must define. */
    val outputCount: Int
        get() = encoding.outputCount(resolution)

    init {
        require(name.isNotBlank()) { "Device name must not be blank" }
        require(worldName.isNotBlank()) { "World name must not be blank" }
        require(this.outputs.size == outputCount) {
            "A ${resolution.configValue}-resolution ${encoding.configValue} device must define exactly " +
                "$outputCount outputs (${resolution.label} ${encoding.configValue})"
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
            "Device demo block positions must be unique"
        }
        require(this.demoBlocks.none { Triple(it.x, it.y, it.z) in slotPositions }) {
            "Device demo blocks must not overlap output slots"
        }
    }

    /** A demo device also owns the display blocks of its teaching build. */
    val isDemo: Boolean
        get() = demoBlocks.isNotEmpty()
}
