package cn.orzmc.realtime.core.device

data class BcdHhmmFrame(
    val hourTens: Int,
    val hourOnes: Int,
    val minuteTens: Int,
    val minuteOnes: Int,
) {
    init {
        require(listOf(hourTens, hourOnes, minuteTens, minuteOnes).all { it in 0..9 }) {
            "Each BCD digit must be between 0 and 9"
        }
    }

    val digits: List<Int>
        get() = listOf(hourTens, hourOnes, minuteTens, minuteOnes)

    /**
     * Sixteen outputs ordered by digit group HHmm, with each digit LSB first.
     *
     * Bit indexes 0..3 are hour tens, 4..7 hour ones, 8..11 minute tens,
     * and 12..15 minute ones.
     */
    val bits: List<Boolean>
        get() =
            digits.flatMap { digit ->
                (0..3).map { bit -> digit and (1 shl bit) != 0 }
            }
}
