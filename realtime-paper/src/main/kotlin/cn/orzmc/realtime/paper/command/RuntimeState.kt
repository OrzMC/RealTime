package cn.orzmc.realtime.paper.command

import cn.orzmc.realtime.core.time.RealTimeService
import cn.orzmc.realtime.paper.config.RealTimeConfiguration
import cn.orzmc.realtime.paper.message.MessageCatalog

data class RuntimeState(
    val configuration: RealTimeConfiguration,
    val timeService: RealTimeService,
    val messages: MessageCatalog,
)
