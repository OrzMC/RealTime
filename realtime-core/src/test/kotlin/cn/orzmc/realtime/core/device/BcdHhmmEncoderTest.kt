package cn.orzmc.realtime.core.device

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.LocalTime

class BcdHhmmEncoderTest {
    @Test
    fun `encodes midnight and final minute with documented bit order`() {
        val midnight = BcdHhmmEncoder.encode(0, 0)
        val lastMinute = BcdHhmmEncoder.encode(23, 59)

        assertEquals(listOf(0, 0, 0, 0), midnight.digits)
        assertEquals(List(16) { false }, midnight.bits)
        assertEquals(listOf(2, 3, 5, 9), lastMinute.digits)
        assertEquals(16, lastMinute.bits.size)
        assertEquals(
            listOf(
                false,
                true,
                false,
                false,
                true,
                true,
                false,
                false,
                true,
                false,
                true,
                false,
                true,
                false,
                false,
                true,
            ),
            lastMinute.bits,
        )
    }

    @Test
    fun `encodes every valid minute into sixteen BCD bits`() {
        for (hour in 0..23) {
            for (minute in 0..59) {
                val frame = BcdHhmmEncoder.encode(hour, minute)
                assertEquals(16, frame.bits.size)
                assertEquals(listOf(hour / 10, hour % 10, minute / 10, minute % 10), frame.digits)

                val decodedDigits =
                    frame.bits.chunked(4).map { bits ->
                        bits.withIndex().sumOf { (index, set) -> if (set) 1 shl index else 0 }
                    }
                assertEquals(frame.digits, decodedDigits)
            }
        }
    }

    @Test
    fun `accepts local time and ignores seconds`() {
        assertEquals(
            BcdHhmmEncoder.encode(12, 34),
            BcdHhmmEncoder.encode(LocalTime.of(12, 34, 56)),
        )
    }

    @Test
    fun `rejects out of range hour and minute values`() {
        assertThrows(IllegalArgumentException::class.java) { BcdHhmmEncoder.encode(-1, 0) }
        assertThrows(IllegalArgumentException::class.java) { BcdHhmmEncoder.encode(24, 0) }
        assertThrows(IllegalArgumentException::class.java) { BcdHhmmEncoder.encode(0, -1) }
        assertThrows(IllegalArgumentException::class.java) { BcdHhmmEncoder.encode(0, 60) }
    }
}
