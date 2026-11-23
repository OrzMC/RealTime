package cn.orzmc.realtime.paper.device

data class RedstoneConfiguration(
    val enabled: Boolean,
    val outputPower: Int,
) {
    init {
        require(outputPower in 1..15) { "outputPower must be between 1 and 15" }
    }
}
