package cn.orzmc.realtime.core.device

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AnalogScaleTest {
    @Test
    fun `every digit maps onto a distinct comparator level`() {
        val levels =
            (0..AnalogScale.MAX_DIGIT).map { digit ->
                AnalogScale.level(AnalogScale.pageForDigit(digit), AnalogScale.BOOK_PAGES)
            }

        assertEquals((1..10).toList(), levels)
        assertEquals(levels.distinct().size, levels.size)
    }

    @Test
    fun `fifteen page book spans all fifteen levels without rounding loss`() {
        val levels =
            (0 until AnalogScale.BOOK_PAGES).map { page ->
                AnalogScale.level(page, AnalogScale.BOOK_PAGES)
            }

        assertEquals((1..15).toList(), levels)
    }

    @Test
    fun `short books still produce valid levels`() {
        assertEquals(AnalogScale.MAX_LEVEL, AnalogScale.level(0, 1))
        assertEquals(listOf(1, 15), listOf(0, 1).map { page -> AnalogScale.level(page, 2) })
        assertEquals(listOf(1, 8, 15), listOf(0, 1, 2).map { page -> AnalogScale.level(page, 3) })
    }

    @Test
    fun `rejects digits and pages outside the encodable range`() {
        assertThrows(IllegalArgumentException::class.java) { AnalogScale.pageForDigit(-1) }
        assertThrows(IllegalArgumentException::class.java) { AnalogScale.pageForDigit(AnalogScale.MAX_DIGIT + 1) }
        assertThrows(IllegalArgumentException::class.java) { AnalogScale.level(15, AnalogScale.BOOK_PAGES) }
        assertThrows(IllegalArgumentException::class.java) { AnalogScale.level(0, -1) }
    }

    @Test
    fun `book page count and level count stay in sync`() {
        // level(page) = floor(page / (pages - 1) * 14) + 1 must hit every level exactly once.
        assertEquals(AnalogScale.MAX_LEVEL, AnalogScale.BOOK_PAGES)
        assertTrue(AnalogScale.levelForDigit(AnalogScale.MAX_DIGIT) <= AnalogScale.MAX_LEVEL)
    }
}
