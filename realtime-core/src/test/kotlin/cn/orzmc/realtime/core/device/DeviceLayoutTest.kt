package cn.orzmc.realtime.core.device

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DeviceLayoutTest {
    @Test
    fun `compact layout keeps every slot next to the previous one`() {
        val layout = DeviceLayout()

        assertEquals(listOf(0, 1, 2, 3), (0..3).map(layout::offset))
        val outputs = layout.outputs(OutputEncoding.BCD, OutputResolution.MINUTE, LayoutAxis.X_PLUS, 100, 70, -8)
        assertEquals((0 until 16).map { 100 + it }, outputs.map(DeviceOutput::x))
        assertTrue(outputs.all { it.y == 70 && it.z == -8 })
        assertEquals((0 until 16).toList(), outputs.map(DeviceOutput::bitIndex))
    }

    @Test
    fun `stride leaves an empty block between two slots`() {
        val layout = DeviceLayout(stride = 2)

        assertEquals(listOf(0, 2, 4, 6), (0..3).map(layout::offset))
    }

    @Test
    fun `groups add an extra gap so each digit reads as one block of slots`() {
        val layout = DeviceLayout(stride = 2, groupSize = 4, groupGap = 1)

        assertEquals(
            listOf(0, 2, 4, 6, 9, 11, 13, 15, 18, 20, 22, 24),
            (0..11).map(layout::offset),
        )
        // Bit 3 and bit 4 belong to different digits: two empty blocks between them.
        assertEquals(2, layout.offset(4) - layout.offset(3) - 1)
        // Inside a digit exactly one empty block separates the bits.
        assertEquals(1, layout.offset(1) - layout.offset(0) - 1)
    }

    @Test
    fun `analog default layout keeps one block between lecterns`() {
        val layout = DeviceLayout.default(OutputEncoding.ANALOG)

        assertEquals(2, layout.stride)
        assertEquals(DeviceLayout.NO_GROUPS, layout.groupSize)
        val outputs = layout.outputs(OutputEncoding.ANALOG, OutputResolution.SECOND, LayoutAxis.Z_MINUS, 0, 80, 40)
        assertEquals(listOf(40, 38, 36, 34, 32, 30), outputs.map(DeviceOutput::z))
        assertEquals((0 until 6).toList(), outputs.map(DeviceOutput::bitIndex))
    }

    @Test
    fun `bcd default layout stays compact until it is grouped`() {
        val layout = DeviceLayout.default(OutputEncoding.BCD)

        assertEquals(1, layout.stride)
        assertEquals(DeviceLayout.NO_GROUPS, layout.groupSize)
    }

    @Test
    fun `axes grow in the requested direction`() {
        val layout = DeviceLayout(stride = 2)
        val outputs = layout.outputs(OutputEncoding.ANALOG, OutputResolution.MINUTE, LayoutAxis.X_MINUS, 5, 64, 5)

        assertEquals(listOf(5, 3, 1, -1), outputs.map(DeviceOutput::x))
    }

    @Test
    fun `rejects invalid spacing and oversized spans`() {
        assertThrows(IllegalArgumentException::class.java) { DeviceLayout(stride = 0) }
        assertThrows(IllegalArgumentException::class.java) { DeviceLayout(groupSize = 0) }
        assertThrows(IllegalArgumentException::class.java) { DeviceLayout(groupGap = -1) }
        assertThrows(IllegalArgumentException::class.java) {
            DeviceLayout(stride = DeviceLayout.MAX_SPAN).outputs(
                OutputEncoding.BCD,
                OutputResolution.SECOND,
                LayoutAxis.X_PLUS,
                0,
                64,
                0,
            )
        }
    }

    @Test
    fun `parses axis aliases`() {
        assertEquals(LayoutAxis.X_PLUS, LayoutAxis.fromConfig("X+"))
        assertEquals(LayoutAxis.Z_MINUS, LayoutAxis.fromConfig("z-"))
        assertEquals(null, LayoutAxis.fromConfig("y+"))
    }
}
