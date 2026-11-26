package cn.orzmc.realtime.core.device

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DeviceDemoTest {
    private fun slots(
        axis: LayoutAxis,
        count: Int = 2,
    ): List<DeviceOutput> = DeviceLayout.DEFAULT.outputs(OutputResolution.SECOND, axis, 100, 70, 0).take(count)

    @Test
    fun `a device grows a comparator dust lamp row perpendicular to an x axis`() {
        val outputs = slots(LayoutAxis.X_PLUS, count = 1)
        val slot = outputs.single()
        val display = DeviceDemo.display(LayoutAxis.X_PLUS, outputs)

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
    fun `the row turns the comparator towards the lectern on a z axis`() {
        val outputs = slots(LayoutAxis.Z_MINUS, count = 1)
        val slot = outputs.single()
        val display = DeviceDemo.display(LayoutAxis.Z_MINUS, outputs)

        val comparator = display.single { it.data.startsWith("minecraft:comparator") }
        assertEquals("west", comparator.data.removePrefix("minecraft:comparator[facing=").removeSuffix("]"))
        assertEquals(slot.x + 1, comparator.x)
        assertEquals(slot.z, comparator.z)
        assertEquals("minecraft:redstone_lamp", display.last().data)
        assertEquals(slot.x + 3, display.last().x)
    }

    @Test
    fun `every display block is unique and never lands on a slot`() {
        LayoutAxis.entries.forEach { axis ->
            val outputs = DeviceLayout.DEFAULT.outputs(OutputResolution.SECOND, axis, 0, 70, 0)
            val display = DeviceDemo.display(axis, outputs)

            assertEquals(display.size, display.map { Triple(it.x, it.y, it.z) }.toSet().size, axis.toString())
            val slotPositions = outputs.map { Triple(it.x, it.y, it.z) }.toSet()
            assertTrue(display.none { Triple(it.x, it.y, it.z) in slotPositions }, axis.toString())
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
