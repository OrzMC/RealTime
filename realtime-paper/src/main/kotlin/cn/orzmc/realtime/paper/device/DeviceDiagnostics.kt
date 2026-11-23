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
)

fun interface DeviceDiagnostics {
    fun snapshot(): RedstoneDiagnosticsSnapshot
}
