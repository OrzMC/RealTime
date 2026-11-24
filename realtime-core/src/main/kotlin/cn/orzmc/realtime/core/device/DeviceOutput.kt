package cn.orzmc.realtime.core.device

data class DeviceOutput(
    val bitIndex: Int,
    val x: Int,
    val y: Int,
    val z: Int,
) {
    init {
        require(bitIndex in 0 until OutputResolution.MAX_OUTPUT_COUNT) {
            "bitIndex must be between 0 and ${OutputResolution.MAX_OUTPUT_COUNT - 1}"
        }
    }
}
