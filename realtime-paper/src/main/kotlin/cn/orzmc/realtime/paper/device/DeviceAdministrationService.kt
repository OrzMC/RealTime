package cn.orzmc.realtime.paper.device

import cn.orzmc.realtime.core.device.DeviceOutput
import cn.orzmc.realtime.core.device.OutputEncoding
import cn.orzmc.realtime.core.device.OutputResolution
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

    /** Creates a disabled device from a fully resolved layout. */
    fun create(request: DeviceCreateRequest): RedstoneDevice

    /** Moves one output slot of an existing device. */
    fun setOutput(
        id: UUID,
        bitIndex: Int,
        x: Int,
        y: Int,
        z: Int,
    ): RedstoneDevice
}

data class DeviceCreateRequest(
    val name: String,
    val worldName: String,
    val resolution: OutputResolution,
    val outputs: List<DeviceOutput>,
    val encoding: OutputEncoding = OutputEncoding.BCD,
)

class DeviceNotFoundException(
    id: UUID,
) : IllegalArgumentException("Device not found: $id")

class DeviceLimitReachedException(
    val limit: Int,
) : IllegalStateException("Device limit reached: $limit")

class DeviceAreaNotAllowedException(
    val worldName: String,
    val x: Int,
    val z: Int,
) : IllegalArgumentException("Position is outside the allowed areas: $worldName $x,$z")

class DeviceOutputConflictException(
    val x: Int,
    val y: Int,
    val z: Int,
) : IllegalArgumentException("Position is already used by another device or cleanup job: $x,$y,$z")

class DeviceOutputIndexException(
    val bitIndex: Int,
    val maxIndex: Int,
) : IllegalArgumentException("Bit index $bitIndex is outside 0..$maxIndex")

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
                    resolution = currentDevice.resolution,
                    encoding = currentDevice.encoding,
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

    override fun create(request: DeviceCreateRequest): RedstoneDevice =
        synchronized(stateLock) {
            val currentState = stateProvider()
            val limits = currentState.redstoneConfiguration.limits
            if (currentState.devices.size >= limits.maxDevices) {
                throw DeviceLimitReachedException(limits.maxDevices)
            }
            request.outputs.forEach { output ->
                requireAllowed(currentState, request.worldName, output)
            }
            val taken = occupiedPositions(currentState)
            request.outputs.forEach { output ->
                if (Position(request.worldName, output.x, output.y, output.z) in taken) {
                    throw DeviceOutputConflictException(output.x, output.y, output.z)
                }
            }

            val device =
                RedstoneDevice(
                    id = UUID.randomUUID(),
                    name = request.name,
                    worldName = request.worldName,
                    enabled = false,
                    outputs = request.outputs,
                    resolution = request.resolution,
                    encoding = request.encoding,
                )
            val updatedDevices = currentState.devices + device

            persistDevices(updatedDevices)
            replaceState(currentState.copy(devices = updatedDevices))
            requestRefresh()
            device
        }

    override fun setOutput(
        id: UUID,
        bitIndex: Int,
        x: Int,
        y: Int,
        z: Int,
    ): RedstoneDevice =
        synchronized(stateLock) {
            val currentState = stateProvider()
            val currentDevice =
                currentState.devices.firstOrNull { it.id == id }
                    ?: throw DeviceNotFoundException(id)
            val maxIndex = currentDevice.outputCount - 1
            if (bitIndex !in 0..maxIndex) throw DeviceOutputIndexException(bitIndex, maxIndex)

            val target = DeviceOutput(bitIndex, x, y, z)
            requireAllowed(currentState, currentDevice.worldName, target)
            val taken = occupiedPositions(currentState, excludingOutput = currentDevice.id to bitIndex)
            if (Position(currentDevice.worldName, x, y, z) in taken) {
                throw DeviceOutputConflictException(x, y, z)
            }

            val updatedDevice =
                RedstoneDevice(
                    id = currentDevice.id,
                    name = currentDevice.name,
                    worldName = currentDevice.worldName,
                    enabled = currentDevice.enabled,
                    outputs = currentDevice.outputs.map { output -> if (output.bitIndex == bitIndex) target else output },
                    resolution = currentDevice.resolution,
                    encoding = currentDevice.encoding,
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

    private fun requireAllowed(
        state: RuntimeState,
        worldName: String,
        output: DeviceOutput,
    ) {
        if (!state.redstoneConfiguration.limits.allows(worldName, output.x, output.z)) {
            throw DeviceAreaNotAllowedException(worldName, output.x, output.z)
        }
    }

    private fun occupiedPositions(
        state: RuntimeState,
        excludingOutput: Pair<UUID, Int>? = null,
    ): Set<Position> {
        val active =
            state.devices
                .asSequence()
                .flatMap { device ->
                    device.outputs
                        .asSequence()
                        .filter { output ->
                            excludingOutput == null ||
                                device.id != excludingOutput.first ||
                                output.bitIndex != excludingOutput.second
                        }.map { output -> Position(device.worldName, output.x, output.y, output.z) }
                }
        val reserved =
            state.cleanupJobs
                .asSequence()
                .flatMap { job ->
                    job.outputs.asSequence().map { cleanupOutput ->
                        Position(job.worldName, cleanupOutput.output.x, cleanupOutput.output.y, cleanupOutput.output.z)
                    }
                }
        return (active + reserved).toSet()
    }

    private data class Position(
        val worldName: String,
        val x: Int,
        val y: Int,
        val z: Int,
    )
}
