package cn.orzmc.realtime.paper.device

import cn.orzmc.realtime.core.device.DemoBlock
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
    val demoBlocks: List<DemoBlock> = emptyList(),
    /**
     * Start the device enabled. Only `device demo` passes `true`: a teaching
     * build is meant to light up immediately.
     */
    val enabled: Boolean = false,
)

/** Thrown when `enable` is asked for a device that still has slots without a position. */
class DeviceIncompleteException(
    id: UUID,
    val unplaced: Int,
) : IllegalStateException("$unplaced slot(s) of device $id have no position yet")

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
            if (enabled && currentDevice.unplacedOutputs.isNotEmpty()) {
                throw DeviceIncompleteException(currentDevice.id, currentDevice.unplacedOutputs.size)
            }

            val updatedDevice =
                RedstoneDevice(
                    id = currentDevice.id,
                    name = currentDevice.name,
                    worldName = currentDevice.worldName,
                    enabled = enabled,
                    outputs = currentDevice.outputs,
                    resolution = currentDevice.resolution,
                    encoding = currentDevice.encoding,
                    demoBlocks = currentDevice.demoBlocks,
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
            request.outputs.filterNot(DeviceOutput::unplaced).forEach { output ->
                requireAllowed(currentState, request.worldName, output)
            }
            request.demoBlocks.forEach { block ->
                requireAllowed(currentState, request.worldName, DeviceOutput(0, block.x, block.y, block.z))
            }
            val taken = occupiedPositions(currentState)
            request.outputs.filterNot(DeviceOutput::unplaced).forEach { output ->
                if (Position(request.worldName, output.x, output.y, output.z) in taken) {
                    throw DeviceOutputConflictException(output.x, output.y, output.z)
                }
            }

            val device =
                RedstoneDevice(
                    id = UUID.randomUUID(),
                    name = request.name,
                    worldName = request.worldName,
                    enabled = request.enabled,
                    outputs = request.outputs,
                    resolution = request.resolution,
                    encoding = request.encoding,
                    demoBlocks = request.demoBlocks,
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
            // A live device would keep writing the old slot (a redstone block or a
            // lectern) after it left the device, so slots move while the device is
            // off. Disabling already empties every slot.
            if (currentDevice.enabled) throw DeviceMustBeDisabledException(id)
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
                    demoBlocks = currentDevice.demoBlocks,
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
                            !output.unplaced &&
                                (
                                    excludingOutput == null ||
                                        device.id != excludingOutput.first ||
                                        output.bitIndex != excludingOutput.second
                                )
                        }.map { output -> Position(device.worldName, output.x, output.y, output.z) }
                }
        val demo =
            state.devices
                .asSequence()
                .flatMap { device ->
                    device.demoBlocks.asSequence().map { block ->
                        Position(device.worldName, block.x, block.y, block.z)
                    }
                }
        val reserved =
            state.cleanupJobs
                .asSequence()
                .flatMap { job ->
                    val slots =
                        job.outputs.asSequence().map { cleanupOutput ->
                            Position(job.worldName, cleanupOutput.output.x, cleanupOutput.output.y, cleanupOutput.output.z)
                        }
                    val display =
                        job.display.asSequence().map { demoOutput ->
                            Position(job.worldName, demoOutput.block.x, demoOutput.block.y, demoOutput.block.z)
                        }
                    slots + display
                }
        return (active + demo + reserved).toSet()
    }

    private data class Position(
        val worldName: String,
        val x: Int,
        val y: Int,
        val z: Int,
    )
}
