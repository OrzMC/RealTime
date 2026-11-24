package cn.orzmc.realtime.core.device

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OutputEncodingTest {
    @Test
    fun `bcd devices keep four slots per digit`() {
        assertEquals(16, OutputEncoding.BCD.outputCount(OutputResolution.MINUTE))
        assertEquals(24, OutputEncoding.BCD.outputCount(OutputResolution.SECOND))
    }

    @Test
    fun `analog devices use one slot per digit`() {
        assertEquals(4, OutputEncoding.ANALOG.outputCount(OutputResolution.MINUTE))
        assertEquals(6, OutputEncoding.ANALOG.outputCount(OutputResolution.SECOND))
    }

    @Test
    fun `off values differ between encodings`() {
        assertEquals(0, OutputEncoding.BCD.offValue)
        assertEquals(AnalogScale.NO_BOOK, OutputEncoding.ANALOG.offValue)
        assertEquals(List(16) { 0 }, OutputEncoding.BCD.offValues(OutputResolution.MINUTE))
        assertEquals(List(6) { AnalogScale.NO_BOOK }, OutputEncoding.ANALOG.offValues(OutputResolution.SECOND))
    }

    @Test
    fun `parses configured values and the pre-release binary alias`() {
        assertEquals(OutputEncoding.BCD, OutputEncoding.fromConfig("bcd"))
        assertEquals(OutputEncoding.BCD, OutputEncoding.fromConfig("Binary"))
        assertEquals(OutputEncoding.ANALOG, OutputEncoding.fromConfig("analog"))
        assertEquals(OutputEncoding.ANALOG, OutputEncoding.fromConfig("ANALOGUE"))
        assertNull(OutputEncoding.fromConfig("hex"))
    }

    @Test
    fun `analog digits stay below the level range the scale can express`() {
        assertTrue(AnalogScale.MAX_DIGIT < AnalogScale.MAX_LEVEL)
        assertFalse(AnalogScale.isDigit(AnalogScale.NO_BOOK))
        assertFalse(AnalogScale.isDigit(AnalogScale.MAX_DIGIT + 1))
    }
}
