package cn.orzmc.realtime.core.device

import java.time.LocalTime

/**
 * The six decimal digits of one wall-clock frame, ordered hour → minute → second.
 *
 * A device emits the prefix that matches its [OutputResolution]: `HHmm` (four
 * digits) for `minute` and `HHmmss` (six digits) for `second`. One digit drives
 * one lectern page, so a comparator behind the lectern reads `digit + 1`
 * (see [AnalogScale]).
 */
data class TimeDigits(
    val hourTens: Int,
    val hourOnes: Int,
    val minuteTens: Int,
    val minuteOnes: Int,
    val secondTens: Int,
    val secondOnes: Int,
) {
    init {
        require(digits.all { it in 0..9 }) { "Each clock digit must be between 0 and 9" }
    }

    /** Every digit, hour first. */
    val digits: List<Int>
        get() = listOf(hourTens, hourOnes, minuteTens, minuteOnes, secondTens, secondOnes)

    /** Slot values for [resolution], hour first: one digit per slot. */
    fun values(resolution: OutputResolution): List<Int> = digits.subList(0, resolution.digitCount)

    companion object {
        fun of(time: LocalTime): TimeDigits = of(time.hour, time.minute, time.second)

        fun of(
            hour: Int,
            minute: Int,
            second: Int,
        ): TimeDigits {
            require(hour in 0..23) { "hour must be between 0 and 23" }
            require(minute in 0..59) { "minute must be between 0 and 59" }
            require(second in 0..59) { "second must be between 0 and 59" }
            return TimeDigits(
                hourTens = hour / 10,
                hourOnes = hour % 10,
                minuteTens = minute / 10,
                minuteOnes = minute % 10,
                secondTens = second / 10,
                secondOnes = second % 10,
            )
        }
    }
}
