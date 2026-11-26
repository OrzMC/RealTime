package cn.orzmc.realtime.paper.device

import cn.orzmc.realtime.core.device.DeviceOutput
import org.bukkit.block.BlockFace
import java.util.UUID

/** Immutable, thread-safe operational counters for redstone device output. */
data class RedstoneDiagnosticsSnapshot(
    val configuredDevices: Int,
    val enabledDevices: Int,
    val configuredOutputs: Int,
    val outputChecks: Long,
    val unloadedChunkSkips: Long,
    val staleOutputUpdates: Long,
    val invalidOutputBlocks: Long,
    val powerChanges: Long,
    val unchangedOutputs: Long,
    val writeFailures: Long,
    /** Lectern blocks placed for analog output slots. */
    val lecternPlacements: Long = 0,
    /** Books installed into (or removed from) analog output lecterns. */
    val bookWrites: Long = 0,
    /** Display blocks placed for `device demo` builds. */
    val demoBlocksPlaced: Long = 0,
    /** Display blocks removed again when a demo device was disabled or retired. */
    val demoBlocksCleared: Long = 0,
)

fun interface DeviceDiagnostics {
    fun snapshot(): RedstoneDiagnosticsSnapshot
}

/**
 * Immediate, single-slot work the command layer asks for after it moved or
 * created a slot.
 *
 * `place` used to only rewrite the stored coordinate: the lectern appeared when
 * the device was switched on, so an operator could not see whether a slot landed
 * where they meant. [placeSlotNow] builds it on the spot (and clears the slot it
 * came from), which is what makes per-slot authoring reviewable.
 */
interface DevicePlacement {
    /**
     * Puts the lectern of [slot] into the world, facing [facing] (`null` keeps the
     * default orientation), and removes a lectern this device left at [previous].
     */
    fun placeSlotNow(
        deviceId: UUID,
        slot: DeviceOutput,
        previous: DeviceOutput? = null,
        facing: BlockFace? = null,
    )
}
