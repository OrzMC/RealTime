package cn.orzmc.realtime.paper.device

import cn.orzmc.realtime.core.device.RedstoneDevice
import cn.orzmc.realtime.paper.command.RuntimeState
import java.util.UUID

interface DeviceAdministration {
    fun list(): List<RedstoneDevice>

    fun find(id: UUID): RedstoneDevice?

    fun setEnabled(
        id: UUID,
        enabled: Boolean,
    ): RedstoneDevice

    fun remove(id: UUID): RedstoneDevice
}

class DeviceNotFoundException(id: UUID) : IllegalArgumentException("Device not found: $id")

/**
 * Serializes persisted device mutations with runtime configuration reloads.
 *
 * The caller supplies the same lock used by reloadRuntimeState. Persistence is
 * completed before the new immutable runtime snapshot is made visible.
 */
class DeviceAdministrationService(
    private val stateLock: Any,
    private val stateProvider: () -> RuntimeState,
    private val persistDevices: (List<RedstoneDevice>) -> Unit,
    private val replaceState: (RuntimeState) -> Unit,
    private val requestRefresh: () -> Unit,
) : DeviceAdministration {
    override fun list(): List<RedstoneDevice> = stateProvider().devices

    override fun find(id: UUID): RedstoneDevice? = stateProvider().devices.firstOrNull { it.id == id }

    override fun setEnabled(
        id: UUID,
        enabled: Boolean,
    ): RedstoneDevice =
        synchronized(stateLock) {
            val currentState = stateProvider()
            val currentDevice =
                currentState.devices.firstOrNull { it.id == id }
                    ?: throw DeviceNotFoundException(id)
            if (currentDevice.enabled == enabled) return@synchronized currentDevice

            val updatedDevice =
                RedstoneDevice(
                    id = currentDevice.id,
                    name = currentDevice.name,
                    worldName = currentDevice.worldName,
                    enabled = enabled,
                    outputs = currentDevice.outputs,
                )
            val updatedDevices =
                currentState.devices.map { device ->
                    if (device.id == id) updatedDevice else device
                }

            persistDevices(updatedDevices)
            replaceState(currentState.copy(devices = updatedDevices))
            requestRefresh()
            updatedDevice
        }

    override fun remove(id: UUID): RedstoneDevice =
        synchronized(stateLock) {
            val currentState = stateProvider()
            val removedDevice =
                currentState.devices.firstOrNull { it.id == id }
                    ?: throw DeviceNotFoundException(id)
            val updatedDevices = currentState.devices.filterNot { it.id == id }

            persistDevices(updatedDevices)
            replaceState(currentState.copy(devices = updatedDevices))
            requestRefresh()
            removedDevice
        }
}
