package cn.orzmc.realtime.paper.device

/** Read-only snapshot of durable deferred-cleanup work and transient processing state. */
data class DeviceCleanupDiagnosticsSnapshot(
    val jobs: Int,
    val pendingOutputs: Int,
    val needsAttentionOutputs: Int,
    val inFlightOutputs: Int,
    val queuedResults: Int,
)

fun interface DeviceCleanupDiagnostics {
    fun snapshot(): DeviceCleanupDiagnosticsSnapshot
}
