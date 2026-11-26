package cn.orzmc.realtime.core.device

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.LocalTime

class TimeDigitsTest {
    @Test
    fun `splits midnight and the last second into clock order`() {
        assertEquals(listOf(0, 0, 0, 0, 0, 0), TimeDigits.of(0, 0, 0).digits)
        assertEquals(listOf(2, 3, 5, 9, 5, 9), TimeDigits.of(23, 59, 59).digits)
    }

    @Test
    fun `emits a resolution specific prefix of the same digits`() {
        val digits = TimeDigits.of(23, 59, 47)

        assertEquals(listOf(2, 3, 5, 9), digits.values(OutputResolution.MINUTE))
        assertEquals(listOf(2, 3, 5, 9, 4, 7), digits.values(OutputResolution.SECOND))
        // The minute prefix is a prefix: the hour and minute digits never move.
        assertEquals(digits.values(OutputResolution.SECOND).take(4), digits.values(OutputResolution.MINUTE))
    }

    @Test
    fun `every digit a device can emit is a valid comparator page`() {
        for (hour in 0..23) {
            for (minute in 0..59) {
                TimeDigits.of(hour, minute, 59).values(OutputResolution.SECOND).forEach { digit ->
                    assertEquals(digit, AnalogScale.pageForDigit(digit))
                    assertEquals(digit + 1, AnalogScale.levelForDigit(digit))
                }
            }
        }
    }

    @Test
    fun `uses local time seconds and rejects out of range values`() {
        assertEquals(TimeDigits.of(12, 34, 56), TimeDigits.of(LocalTime.of(12, 34, 56)))

        assertThrows(IllegalArgumentException::class.java) { TimeDigits.of(-1, 0, 0) }
        assertThrows(IllegalArgumentException::class.java) { TimeDigits.of(24, 0, 0) }
        assertThrows(IllegalArgumentException::class.java) { TimeDigits.of(0, -1, 0) }
        assertThrows(IllegalArgumentException::class.java) { TimeDigits.of(0, 60, 0) }
        assertThrows(IllegalArgumentException::class.java) { TimeDigits.of(0, 0, -1) }
        assertThrows(IllegalArgumentException::class.java) { TimeDigits.of(0, 0, 60) }
    }
}
