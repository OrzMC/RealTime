package cn.orzmc.realtime.core.device

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.LocalTime

class BcdTimeEncoderTest {
    @Test
    fun `encodes midnight and final second with documented bit order`() {
        val midnight = BcdTimeEncoder.encode(0, 0, 0)
        val lastSecond = BcdTimeEncoder.encode(23, 59, 59)

        assertEquals(listOf(0, 0, 0, 0, 0, 0), midnight.digits)
        assertEquals(List(24) { false }, midnight.bits)
        assertEquals(listOf(2, 3, 5, 9, 5, 9), lastSecond.digits)
        assertEquals(24, lastSecond.bits.size)
        assertEquals(
            listOf(
                // hour tens: 2
                false,
                true,
                false,
                false,
                // hour ones: 3
                true,
                true,
                false,
                false,
                // minute tens: 5
                true,
                false,
                true,
                false,
                // minute ones: 9
                true,
                false,
                false,
                true,
                // second tens: 5
                true,
                false,
                true,
                false,
                // second ones: 9
                true,
                false,
                false,
                true,
            ),
            lastSecond.bits,
        )
    }

    @Test
    fun `encodes every valid time into twenty four BCD bits`() {
        for (hour in 0..23) {
            for (minute in 0..59) {
                for (second in 0..59 step 7) {
                    val frame = BcdTimeEncoder.encode(hour, minute, second)
                    assertEquals(24, frame.bits.size)
                    assertEquals(
                        listOf(hour / 10, hour % 10, minute / 10, minute % 10, second / 10, second % 10),
                        frame.digits,
                    )

                    val decodedDigits =
                        frame.bits.chunked(4).map { bits ->
                            bits.withIndex().sumOf { (index, set) -> if (set) 1 shl index else 0 }
                        }
                    assertEquals(frame.digits, decodedDigits)
                }
            }
        }
    }

    @Test
    fun `exposes a resolution specific prefix of the same frame`() {
        val frame = BcdTimeEncoder.encode(23, 59, 47)

        assertEquals(listOf(2, 3, 5, 9), frame.digits(OutputResolution.MINUTE))
        assertEquals(listOf(2, 3, 5, 9, 4, 7), frame.digits(OutputResolution.SECOND))
        assertEquals(16, frame.bits(OutputResolution.MINUTE).size)
        assertEquals(24, frame.bits(OutputResolution.SECOND).size)
        assertEquals(frame.bits.take(16), frame.bits(OutputResolution.MINUTE))

        // Second tens (4) occupies bits 16..19, second ones (7) occupies bits 20..23.
        assertEquals(
            listOf(false, false, true, false, true, true, true, false),
            frame.bits(OutputResolution.SECOND).drop(16),
        )
    }

    @Test
    fun `uses local time seconds and rejects out of range values`() {
        assertEquals(
            BcdTimeEncoder.encode(12, 34, 56),
            BcdTimeEncoder.encode(LocalTime.of(12, 34, 56)),
        )

        assertThrows(IllegalArgumentException::class.java) { BcdTimeEncoder.encode(-1, 0, 0) }
        assertThrows(IllegalArgumentException::class.java) { BcdTimeEncoder.encode(24, 0, 0) }
        assertThrows(IllegalArgumentException::class.java) { BcdTimeEncoder.encode(0, -1, 0) }
        assertThrows(IllegalArgumentException::class.java) { BcdTimeEncoder.encode(0, 60, 0) }
        assertThrows(IllegalArgumentException::class.java) { BcdTimeEncoder.encode(0, 0, -1) }
        assertThrows(IllegalArgumentException::class.java) { BcdTimeEncoder.encode(0, 0, 60) }
    }
}
