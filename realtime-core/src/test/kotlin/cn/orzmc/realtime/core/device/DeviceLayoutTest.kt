package cn.orzmc.realtime.core.device

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DeviceLayoutTest {
    @Test
    fun `default layout keeps one empty block between two lecterns`() {
        val layout = DeviceLayout.DEFAULT

        assertEquals(2, layout.stride)
        assertEquals(listOf(0, 2, 4, 6), (0..3).map(layout::offset))
        val outputs = layout.outputs(OutputResolution.MINUTE, LayoutAxis.X_PLUS, 100, 70, -8)
        assertEquals(listOf(100, 102, 104, 106), outputs.map(DeviceOutput::x))
        assertTrue(outputs.all { it.y == 70 && it.z == -8 })
        assertEquals((0 until 4).toList(), outputs.map(DeviceOutput::bitIndex))
    }

    @Test
    fun `one slot per digit for both resolutions`() {
        assertEquals(
            4,
            DeviceLayout.DEFAULT.outputs(OutputResolution.MINUTE, LayoutAxis.X_PLUS, 0, 64, 0).size,
        )
        assertEquals(
            listOf(0, 2, 4, 6, 8, 10),
            DeviceLayout.DEFAULT.outputs(OutputResolution.SECOND, LayoutAxis.X_PLUS, 0, 64, 0).map(DeviceOutput::x),
        )
    }

    @Test
    fun `axes grow in the requested direction`() {
        val outputs =
            DeviceLayout.DEFAULT.outputs(OutputResolution.MINUTE, LayoutAxis.X_MINUS, 5, 64, 5)

        assertEquals(listOf(5, 3, 1, -1), outputs.map(DeviceOutput::x))
        assertEquals(
            listOf(40, 38, 36, 34),
            DeviceLayout.DEFAULT.outputs(OutputResolution.MINUTE, LayoutAxis.Z_MINUS, 0, 80, 40).map(DeviceOutput::z),
        )
    }

    @Test
    fun `rejects invalid spacing and oversized spans`() {
        assertThrows(IllegalArgumentException::class.java) { DeviceLayout(stride = 0) }
        assertThrows(IllegalArgumentException::class.java) {
            DeviceLayout(stride = DeviceLayout.MAX_SPAN).outputs(OutputResolution.SECOND, LayoutAxis.X_PLUS, 0, 64, 0)
        }
    }

    @Test
    fun `parses axis and facing keywords`() {
        assertEquals(LayoutAxis.X_PLUS, LayoutAxis.fromConfig("X+"))
        assertEquals(LayoutAxis.Z_MINUS, LayoutAxis.fromConfig("z-"))
        assertEquals(null, LayoutAxis.fromConfig("y+"))
        assertEquals(LayoutAxis.X_PLUS, LayoutAxis.fromFacing("east"))
        assertEquals(LayoutAxis.Z_MINUS, LayoutAxis.fromFacing("NORTH"))
        assertEquals(null, LayoutAxis.fromFacing("up"))
        assertTrue(LayoutAxis.X_MINUS.alongX)
        assertTrue(!LayoutAxis.Z_PLUS.alongX)
    }
}
