package cn.orzmc.realtime.core.device

/**
 * One BCD clock frame: hour, minute, and second digits for a single instant.
 *
 * Digits are ordered hour → minute → second, and every digit is emitted
 * least-significant bit first. A device exposes the prefix of that sequence
 * that matches its [OutputResolution].
 */
data class BcdTimeFrame(
    val hourTens: Int,
    val hourOnes: Int,
    val minuteTens: Int,
    val minuteOnes: Int,
    val secondTens: Int,
    val secondOnes: Int,
) {
    init {
        require(
            listOf(hourTens, hourOnes, minuteTens, minuteOnes, secondTens, secondOnes).all { it in 0..9 },
        ) {
            "Each BCD digit must be between 0 and 9"
        }
    }

    val digits: List<Int>
        get() = listOf(hourTens, hourOnes, minuteTens, minuteOnes, secondTens, secondOnes)

    val bits: List<Boolean>
        get() = bits(OutputResolution.SECOND)

    /**
     * Output bits for [resolution], ordered by bit index.
     *
     * Bit indexes 0..3 are hour tens, 4..7 hour ones, 8..11 minute tens,
     * 12..15 minute ones, 16..19 second tens, and 20..23 second ones.
     */
    fun bits(resolution: OutputResolution): List<Boolean> =
        digits
            .subList(0, resolution.digitCount)
            .flatMap { digit ->
                (0 until OutputResolution.BITS_PER_DIGIT).map { bit ->
                    (digit shr bit) and 1 != 0
                }
            }

    fun digits(resolution: OutputResolution): List<Int> = digits.subList(0, resolution.digitCount)
}
