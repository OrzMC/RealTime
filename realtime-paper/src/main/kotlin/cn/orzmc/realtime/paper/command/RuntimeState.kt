package cn.orzmc.realtime.paper.command

import cn.orzmc.realtime.core.device.RedstoneDevice
import cn.orzmc.realtime.core.time.RealTimeService
import cn.orzmc.realtime.paper.config.RealTimeConfiguration
import cn.orzmc.realtime.paper.device.DeviceCleanupJob
import cn.orzmc.realtime.paper.device.RedstoneConfiguration
import cn.orzmc.realtime.paper.message.MessageCatalog

data class RuntimeState(
    val configuration: RealTimeConfiguration,
    val timeService: RealTimeService,
    val messages: MessageCatalog,
    val redstoneConfiguration: RedstoneConfiguration = RedstoneConfiguration(enabled = false, outputPower = 15),
    val devices: List<RedstoneDevice> = emptyList(),
    val cleanupJobs: List<DeviceCleanupJob> = emptyList(),
)
