package cn.orzmc.realtime.core.device

import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class RedstoneDeviceTest {
    @Test
    fun `sorts outputs by bit index and validates all sixteen outputs`() {
        val outputs = outputs().reversed()
        val device = RedstoneDevice(UUID.randomUUID(), "clock", UUID.randomUUID(), true, outputs)

        assertEquals((0 until 16).toList(), device.outputs.map(DeviceOutput::bitIndex))
    }

    @Test
    fun `rejects duplicate bit indexes and duplicate block positions`() {
        val duplicateBit = outputs().toMutableList().also { it[15] = it[0].copy(x = 100) }
        assertThrows(IllegalArgumentException::class.java) {
            RedstoneDevice(UUID.randomUUID(), "clock", UUID.randomUUID(), true, duplicateBit)
        }

        val duplicatePosition = outputs().toMutableList().also { it[15] = it[15].copy(x = 0, y = 64, z = 0) }
        assertThrows(IllegalArgumentException::class.java) {
            RedstoneDevice(UUID.randomUUID(), "clock", UUID.randomUUID(), true, duplicatePosition)
        }
    }

    private fun outputs() =
        (0 until DeviceOutput.OUTPUT_COUNT).map { index ->
            DeviceOutput(bitIndex = index, x = index * 2, y = 64, z = 0)
        }
}
