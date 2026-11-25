package cn.orzmc.realtime.paper.device

import cn.orzmc.realtime.paper.command.RuntimeState
import java.util.UUID

interface DeviceCleanupAdministration {
    fun list(): List<DeviceCleanupJob>

    fun retry(jobId: UUID): DeviceCleanupJob
}

class DeviceCleanupJobNotFoundException(
    id: UUID,
) : IllegalArgumentException("Cleanup job not found: $id")

class DeviceCleanupJobHasNoAttentionOutputsException(
    id: UUID,
) : IllegalStateException("Cleanup job has no outputs requiring attention: $id")

/**
 * Read-only queue inspection plus an explicit operator retry after a
 * NEEDS_ATTENTION block has been corrected manually.
 *
 * The same runtime-state lock used by reload and device administration keeps
 * cleanup-job mutations serialized with the active device store.
 */
class DeviceCleanupAdministrationService(
    private val stateLock: Any,
    private val stateProvider: () -> RuntimeState,
    private val persistStore: (DeviceStoreSnapshot) -> Unit,
    private val replaceState: (RuntimeState) -> Unit,
    private val requestCleanup: () -> Unit,
) : DeviceCleanupAdministration {
    override fun list(): List<DeviceCleanupJob> =
        synchronized(stateLock) {
            stateProvider().cleanupJobs.toList()
        }

    override fun retry(jobId: UUID): DeviceCleanupJob =
        synchronized(stateLock) {
            val current = stateProvider()
            val job =
                current.cleanupJobs.firstOrNull { it.id == jobId }
                    ?: throw DeviceCleanupJobNotFoundException(jobId)
            val hasAttention =
                job.outputs.any { it.status == DeviceCleanupStatus.NEEDS_ATTENTION } ||
                    job.display.any { it.status == DeviceCleanupStatus.NEEDS_ATTENTION }
            if (!hasAttention) throw DeviceCleanupJobHasNoAttentionOutputsException(jobId)

            val retriedJob =
                job.copy(
                    outputs =
                        job.outputs.map { output ->
                            if (output.status == DeviceCleanupStatus.NEEDS_ATTENTION) {
                                DeviceCleanupOutput(output.output)
                            } else {
                                output
                            }
                        },
                    // Demo display blocks are part of the same job and must be
                    // retried too, otherwise the job can never become CLEARED.
                    display =
                        job.display.map { demo ->
                            if (demo.status == DeviceCleanupStatus.NEEDS_ATTENTION) {
                                DeviceCleanupDemo(demo.block)
                            } else {
                                demo
                            }
                        },
                )
            val updated =
                current.copy(
                    cleanupJobs =
                        current.cleanupJobs.map { existing ->
                            if (existing.id == jobId) retriedJob else existing
                        },
                )

            persistStore(DeviceStoreSnapshot(updated.devices, updated.cleanupJobs))
            replaceState(updated)
            requestCleanup()
            retriedJob
        }
}
