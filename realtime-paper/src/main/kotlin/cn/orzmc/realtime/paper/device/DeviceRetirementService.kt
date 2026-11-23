package cn.orzmc.realtime.paper.device

import cn.orzmc.realtime.paper.command.RuntimeState
import java.util.UUID

class DeviceMustBeDisabledException(
    id: UUID,
) : IllegalStateException("Device must be disabled before retirement: $id")

/**
 * Atomically moves a disabled device from the active store into the deferred
 * cleanup queue. The repository persists both lists in one schema-v2 file
 * replacement before the new runtime snapshot becomes visible.
 *
 * World UUID is supplied by the caller when it is already known. A null UUID
 * leaves the configured world name as the resolution key until a world loads.
 */
fun interface DeviceRetirement {
    fun retire(
        id: UUID,
        worldId: UUID?,
    ): DeviceCleanupJob
}

class DeviceRetirementService(
    private val stateLock: Any,
    private val stateProvider: () -> RuntimeState,
    private val persistStore: (DeviceStoreSnapshot) -> Unit,
    private val replaceState: (RuntimeState) -> Unit,
    private val requestCleanup: () -> Unit,
) : DeviceRetirement {
    override fun retire(
        id: UUID,
        worldId: UUID?,
    ): DeviceCleanupJob =
        synchronized(stateLock) {
            val current = stateProvider()
            val device =
                current.devices.firstOrNull { it.id == id }
                    ?: throw DeviceNotFoundException(id)
            if (device.enabled) throw DeviceMustBeDisabledException(id)

            val cleanupJob = DeviceCleanupJob.fromDevice(device, worldId)
            val updated =
                current.copy(
                    devices = current.devices.filterNot { it.id == id },
                    cleanupJobs = current.cleanupJobs + cleanupJob,
                )

            persistStore(DeviceStoreSnapshot(updated.devices, updated.cleanupJobs))
            replaceState(updated)
            requestCleanup()
            cleanupJob
        }
}
