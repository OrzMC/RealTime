package cn.orzmc.realtime.core.device

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class RedstoneDeviceTest {
    @Test
    fun `sorts outputs by bit index and validates the four minute digits`() {
        val outputs = outputs(OutputResolution.MINUTE).reversed()
        val device = RedstoneDevice(UUID.randomUUID(), "clock", "world", true, outputs, OutputResolution.MINUTE)

        assertEquals(OutputResolution.MINUTE, device.resolution)
        assertEquals((0 until 4).toList(), device.outputs.map(DeviceOutput::bitIndex))
    }

    @Test
    fun `accepts six outputs for second resolution`() {
        val device =
            RedstoneDevice(
                UUID.randomUUID(),
                "clock",
                "world",
                true,
                outputs(OutputResolution.SECOND),
                OutputResolution.SECOND,
            )

        assertEquals(OutputResolution.SECOND, device.resolution)
        assertEquals((0 until 6).toList(), device.outputs.map(DeviceOutput::bitIndex))
    }

    @Test
    fun `rejects output counts that do not match the declared resolution`() {
        assertThrows(IllegalArgumentException::class.java) {
            RedstoneDevice(
                UUID.randomUUID(),
                "clock",
                "world",
                true,
                outputs(OutputResolution.MINUTE),
                OutputResolution.SECOND,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            RedstoneDevice(
                UUID.randomUUID(),
                "clock",
                "world",
                true,
                outputs(OutputResolution.SECOND),
                OutputResolution.MINUTE,
            )
        }
    }

    @Test
    fun `rejects duplicate bit indexes and duplicate block positions`() {
        val duplicateBit = outputs(OutputResolution.MINUTE).toMutableList().also { it[3] = it[0].copy(x = 100) }
        assertThrows(IllegalArgumentException::class.java) {
            RedstoneDevice(UUID.randomUUID(), "clock", "world", true, duplicateBit, OutputResolution.MINUTE)
        }

        val duplicatePosition =
            outputs(OutputResolution.SECOND).toMutableList().also { it[5] = it[5].copy(x = 0, y = 64, z = 0) }
        assertThrows(IllegalArgumentException::class.java) {
            RedstoneDevice(
                UUID.randomUUID(),
                "clock",
                "world",
                true,
                duplicatePosition,
                OutputResolution.SECOND,
            )
        }
    }

    @Test
    fun `unplaced slots do not need a unique position`() {
        val outputs =
            listOf(
                DeviceOutput(0, 0, 64, 0, unplaced = true),
                DeviceOutput(1, 0, 64, 0, unplaced = true),
                DeviceOutput(2, 4, 64, 0),
                DeviceOutput(3, 6, 64, 0),
            )
        val device = RedstoneDevice(UUID.randomUUID(), "clock", "world", false, outputs, OutputResolution.MINUTE)

        assertEquals(2, device.unplacedOutputs.size)
        assertEquals(4, device.outputCount)
    }

    @Test
    fun `rejects bit indexes beyond the supported maximum`() {
        assertThrows(IllegalArgumentException::class.java) {
            DeviceOutput(bitIndex = OutputResolution.MAX_DIGITS, x = 0, y = 64, z = 0)
        }
    }

    @Test
    fun `display blocks must not overlap slots and must be unique`() {
        val slots = (0 until 4).map { DeviceOutput(it, it * 2, 64, 0) }
        val display = listOf(DemoBlock(0, 65, 0, "minecraft:redstone_lamp"))

        val device =
            RedstoneDevice(
                UUID.randomUUID(),
                "clock",
                "world",
                true,
                slots,
                OutputResolution.MINUTE,
                display,
            )
        assertTrue(device.hasDisplay)
        assertEquals(display, device.demoBlocks)

        assertFalse(
            RedstoneDevice(UUID.randomUUID(), "clock", "world", true, slots, OutputResolution.MINUTE)
                .hasDisplay,
        )
        assertThrows(IllegalArgumentException::class.java) {
            RedstoneDevice(
                UUID.randomUUID(),
                "clock",
                "world",
                true,
                slots,
                OutputResolution.MINUTE,
                listOf(DemoBlock(0, 64, 0, "minecraft:redstone_lamp")),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            RedstoneDevice(
                UUID.randomUUID(),
                "clock",
                "world",
                true,
                slots,
                OutputResolution.MINUTE,
                listOf(
                    DemoBlock(10, 65, 0, "minecraft:redstone_lamp"),
                    DemoBlock(10, 65, 0, "minecraft:stone"),
                ),
            )
        }
    }

    private fun outputs(resolution: OutputResolution) =
        (0 until resolution.digitCount).map { index ->
            DeviceOutput(bitIndex = index, x = index * 2, y = 64, z = 0)
        }
}
