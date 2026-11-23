package cn.orzmc.realtime.core.device

data class DeviceOutput(
    val bitIndex: Int,
    val x: Int,
    val y: Int,
    val z: Int,
) {
    init {
        require(bitIndex in 0 until OUTPUT_COUNT) {
            "bitIndex must be between 0 and ${OUTPUT_COUNT - 1}"
        }
    }

    companion object {
        const val OUTPUT_COUNT = 16
    }
}
