package cn.orzmc.realtime.core.device

/**
 * Clock resolution of a device: which prefix of `HHmmss` it displays.
 *
 * [MINUTE] shows `HHmm` (four digits, four lecterns) and [SECOND] shows `HHmmss`
 * (six digits, six lecterns). One digit drives one lectern page.
 */
enum class OutputResolution(
    val configValue: String,
    val digitCount: Int,
) {
    MINUTE("minute", 4),
    SECOND("second", 6),
    ;

    val label: String
        get() = if (this == SECOND) "HHmmss" else "HHmm"

    companion object {
        /** Most digits a device can hold: `HHmmss`. */
        const val MAX_DIGITS = 6

        fun fromConfig(value: String): OutputResolution? = entries.firstOrNull { it.configValue == value.lowercase() }
    }
}
