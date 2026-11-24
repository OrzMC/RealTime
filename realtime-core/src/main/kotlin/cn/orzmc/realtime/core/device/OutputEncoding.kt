package cn.orzmc.realtime.core.device

/**
 * How a device materialises a clock frame on its output slots.
 *
 * [BCD] uses one slot per bit: 4 slots per digit, so 16 slots for `minute`
 * devices and 24 slots for `second` devices. A slot is either a power source
 * (logical 1) or empty (logical 0).
 *
 * [ANALOG] uses one slot per digit: 4 slots for `minute` devices and 6 for
 * `second` devices. Each slot is a lectern whose page index is the digit, so a
 * comparator reads a level between 1 and 10 without any bit decoding.
 *
 * Both encodings share the same digit order (hour → minute → second).
 */
enum class OutputEncoding(
    val configValue: String,
) {
    BCD("bcd"),
    ANALOG("analog"),
    ;

    /** Number of output slots this encoding needs for [resolution]. */
    fun outputCount(resolution: OutputResolution): Int =
        when (this) {
            BCD -> resolution.outputCount
            ANALOG -> resolution.digitCount
        }

    /**
     * Slot value that means "this slot emits nothing".
     *
     * BCD uses 0 because a slot is either a power source or empty. Analog
     * devices use [AnalogScale.NO_BOOK] because a lectern holding a 15-page book
     * always emits at least level 1, so the blank state is "no book at all".
     */
    val offValue: Int
        get() =
            when (this) {
                BCD -> 0
                ANALOG -> AnalogScale.NO_BOOK
            }

    fun offValues(resolution: OutputResolution): List<Int> = List(outputCount(resolution)) { offValue }

    companion object {
        /** Accepted aliases; `binary` is the pre-release name of the BCD layout. */
        private val ALIASES: Map<String, OutputEncoding> =
            mapOf(
                "bcd" to BCD,
                "binary" to BCD,
                "analog" to ANALOG,
                "analogue" to ANALOG,
            )

        fun fromConfig(value: String): OutputEncoding? = ALIASES[value.lowercase()]
    }
}
