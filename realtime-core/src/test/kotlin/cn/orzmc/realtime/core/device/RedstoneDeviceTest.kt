package cn.orzmc.realtime.core.device

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.UUID

class RedstoneDeviceTest {
    @Test
    fun `sorts outputs by bit index and validates all sixteen minute outputs`() {
        val outputs = outputs(OutputResolution.MINUTE).reversed()
        val device = RedstoneDevice(UUID.randomUUID(), "clock", "world", true, outputs)

        assertEquals(OutputResolution.MINUTE, device.resolution)
        assertEquals((0 until 16).toList(), device.outputs.map(DeviceOutput::bitIndex))
    }

    @Test
    fun `accepts twenty four outputs for second resolution`() {
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
        assertEquals((0 until 24).toList(), device.outputs.map(DeviceOutput::bitIndex))
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
        val duplicateBit = outputs(OutputResolution.MINUTE).toMutableList().also { it[15] = it[0].copy(x = 100) }
        assertThrows(IllegalArgumentException::class.java) {
            RedstoneDevice(UUID.randomUUID(), "clock", "world", true, duplicateBit)
        }

        val duplicatePosition =
            outputs(OutputResolution.SECOND).toMutableList().also { it[23] = it[23].copy(x = 0, y = 64, z = 0) }
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
    fun `rejects bit indexes beyond the supported maximum`() {
        assertThrows(IllegalArgumentException::class.java) {
            DeviceOutput(bitIndex = OutputResolution.MAX_OUTPUT_COUNT, x = 0, y = 64, z = 0)
        }
    }

    private fun outputs(resolution: OutputResolution) =
        (0 until resolution.outputCount).map { index ->
            DeviceOutput(bitIndex = index, x = index * 2, y = 64, z = 0)
        }
}
