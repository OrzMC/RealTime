package cn.orzmc.realtime.core.device

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DeviceDemoTest {
    private fun slots(
        encoding: OutputEncoding,
        axis: LayoutAxis,
        count: Int = 2,
    ): List<DeviceOutput> = DeviceLayout.default(encoding).outputs(encoding, OutputResolution.SECOND, axis, 100, 70, 0).take(count)

    @Test
    fun `bcd demo puts one lamp above every slot`() {
        val outputs = slots(OutputEncoding.BCD, LayoutAxis.X_PLUS)
        val display = DeviceDemo.display(OutputEncoding.BCD, LayoutAxis.X_PLUS, outputs)

        assertEquals(outputs.size, display.size)
        assertEquals(outputs.map { "minecraft:redstone_lamp" }.toSet(), display.map { it.data }.toSet())
        outputs.forEachIndexed { index, slot ->
            assertEquals(slot.x, display[index].x)
            assertEquals(slot.y + 1, display[index].y)
            assertEquals(slot.z, display[index].z)
        }
    }

    @Test
    fun `analog demo grows a comparator dust lamp row perpendicular to an x axis`() {
        val outputs = slots(OutputEncoding.ANALOG, LayoutAxis.X_PLUS, count = 1)
        val slot = outputs.single()
        val display = DeviceDemo.display(OutputEncoding.ANALOG, LayoutAxis.X_PLUS, outputs)

        // Support under comparator and dust, then comparator -> dust -> lamp.
        assertEquals(
            listOf(
                DemoBlock(slot.x, slot.y - 1, slot.z + 1, "minecraft:stone"),
                DemoBlock(slot.x, slot.y, slot.z + 1, "minecraft:comparator[facing=north]"),
                DemoBlock(slot.x, slot.y - 1, slot.z + 2, "minecraft:stone"),
                DemoBlock(slot.x, slot.y, slot.z + 2, "minecraft:redstone_wire"),
                DemoBlock(slot.x, slot.y, slot.z + 3, "minecraft:redstone_lamp"),
            ),
            display,
        )
    }

    @Test
    fun `analog demo turns the comparator towards the lectern on a z axis`() {
        val outputs = slots(OutputEncoding.ANALOG, LayoutAxis.Z_MINUS, count = 1)
        val slot = outputs.single()
        val display = DeviceDemo.display(OutputEncoding.ANALOG, LayoutAxis.Z_MINUS, outputs)

        val comparator = display.single { it.data.startsWith("minecraft:comparator") }
        assertEquals("west", comparator.data.removePrefix("minecraft:comparator[facing=").removeSuffix("]"))
        assertEquals(slot.x + 1, comparator.x)
        assertEquals(slot.z, comparator.z)
        assertEquals("minecraft:redstone_lamp", display.last().data)
        assertEquals(slot.x + 3, display.last().x)
    }

    @Test
    fun `every demo block is unique and never lands on a slot`() {
        listOf(OutputEncoding.BCD, OutputEncoding.ANALOG).forEach { encoding ->
            val outputs = DeviceLayout.default(encoding).outputs(encoding, OutputResolution.SECOND, LayoutAxis.X_PLUS, 0, 70, 0)
            val display = DeviceDemo.display(encoding, LayoutAxis.X_PLUS, outputs)

            assertEquals(display.size, display.map { Triple(it.x, it.y, it.z) }.toSet().size, encoding.toString())
            val slotPositions = outputs.map { Triple(it.x, it.y, it.z) }.toSet()
            assertTrue(display.none { Triple(it.x, it.y, it.z) in slotPositions }, encoding.toString())
        }
    }

    @Test
    fun `material is the block id without its state`() {
        assertEquals("minecraft:comparator", DemoBlock(0, 0, 0, "minecraft:comparator[facing=north]").material)
        assertEquals("minecraft:stone", DemoBlock(0, 0, 0, "minecraft:stone").material)
    }

    @Test
    fun `rejects blank block data`() {
        assertThrows(IllegalArgumentException::class.java) { DemoBlock(0, 0, 0, " ") }
    }
}
