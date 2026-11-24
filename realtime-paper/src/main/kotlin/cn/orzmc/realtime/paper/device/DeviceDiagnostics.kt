package cn.orzmc.realtime.paper.device

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
)

fun interface DeviceDiagnostics {
    fun snapshot(): RedstoneDiagnosticsSnapshot
}
