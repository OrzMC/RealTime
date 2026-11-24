package cn.orzmc.realtime.core.device

/**
 * BCD clock resolution supported by a redstone device.
 *
 * Frames are always emitted in clock order (hour, minute, second) and each
 * digit is emitted least-significant bit first. [MINUTE] therefore uses the
 * first four digits (HHmm, 16 outputs) and [SECOND] uses all six digits
 * (HHmmss, 24 outputs).
 */
enum class OutputResolution(
    val configValue: String,
    val digitCount: Int,
) {
    MINUTE("minute", 4),
    SECOND("second", 6),
    ;

    val outputCount: Int
        get() = digitCount * BITS_PER_DIGIT

    val label: String
        get() = if (this == SECOND) "HHmmss" else "HHmm"

    companion object {
        const val BITS_PER_DIGIT = 4
        const val MAX_OUTPUT_COUNT = 24

        fun fromConfig(value: String): OutputResolution? = entries.firstOrNull { it.configValue == value }
    }
}
