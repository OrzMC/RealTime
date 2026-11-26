package cn.orzmc.realtime.core.device

/**
 * One output slot of a device.
 *
 * [unplaced] marks a slot whose block position has not been chosen yet: `device create ... manual` starts every slot
 * this way so the operator can place each source exactly where the build has room. Unplaced slots are never written,
 * are ignored by conflict checks, and must all be placed before the device can be enabled.
 */
data class DeviceOutput(
    val bitIndex: Int,
    val x: Int,
    val y: Int,
    val z: Int,
    val unplaced: Boolean = false,
) {
    init {
        require(bitIndex in 0 until OutputResolution.MAX_OUTPUT_COUNT) {
            "bitIndex must be between 0 and ${OutputResolution.MAX_OUTPUT_COUNT - 1}"
        }
    }
}
