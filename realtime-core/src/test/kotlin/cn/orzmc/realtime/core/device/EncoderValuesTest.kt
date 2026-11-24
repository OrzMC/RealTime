package cn.orzmc.realtime.core.device

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class EncoderValuesTest {
    @Test
    fun `bcd values repeat each digit as four booleans turned into 0 and 1`() {
        val frame = BcdTimeEncoder.encode(23, 59, 47)

        val minute = frame.values(OutputEncoding.BCD, OutputResolution.MINUTE)
        assertEquals(16, minute.size)
        assertEquals(
            frame.bits(OutputResolution.MINUTE).map { if (it) 1 else 0 },
            minute,
        )
        // Hour tens = 2 -> bits 0..3 = 0,1,0,0
        assertEquals(listOf(0, 1, 0, 0), minute.take(4))
    }

    @Test
    fun `analog values are the digits themselves in clock order`() {
        val frame = BcdTimeEncoder.encode(23, 59, 47)

        assertEquals(listOf(2, 3, 5, 9), frame.values(OutputEncoding.ANALOG, OutputResolution.MINUTE))
        assertEquals(listOf(2, 3, 5, 9, 4, 7), frame.values(OutputEncoding.ANALOG, OutputResolution.SECOND))
    }

    @Test
    fun `analog values never exceed what a 15 page lectern can express`() {
        val frame = BcdTimeEncoder.encode(23, 59, 59)

        frame.values(OutputEncoding.ANALOG, OutputResolution.SECOND).forEach { digit ->
            assertEquals(digit, AnalogScale.pageForDigit(digit))
            assertEquals(digit + 1, AnalogScale.levelForDigit(digit))
        }
    }
}
