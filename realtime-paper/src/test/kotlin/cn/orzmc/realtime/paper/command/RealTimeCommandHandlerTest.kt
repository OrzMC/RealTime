package cn.orzmc.realtime.paper.command

import cn.orzmc.realtime.core.time.ClockRealTimeService
import cn.orzmc.realtime.paper.config.RealTimeConfiguration
import cn.orzmc.realtime.paper.message.MessageCatalog
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale

class RealTimeCommandHandlerTest {
    @Test
    fun `no arguments displays current configured time`() {
        val target = RecordingTarget(setOf(RealTimeCommandHandler.COMMAND_PERMISSION))
        val handler = handler()

        handler.execute(target, emptyArray())

        assertEquals(1, target.messages.size)
        assertTrue(target.messages.single().contains("2026-11-23 10:00:00 +08:00"))
    }

    @Test
    fun `info displays version timezone and module state`() {
        val target = RecordingTarget(setOf(RealTimeCommandHandler.COMMAND_PERMISSION))
        val handler = handler()

        handler.execute(target, arrayOf("info"))

        assertTrue(target.messages.any { it.contains("version 1.2.3") })
        assertTrue(target.messages.any { it.contains("timezone Asia/Shanghai") })
        assertTrue(target.messages.any { it.contains("locale zh-CN") })
    }

    @Test
    fun `command permission is required for now and info`() {
        val target = RecordingTarget(emptySet())
        val handler = handler()

        handler.execute(target, arrayOf("now"))

        assertEquals(listOf("[RealTime] denied"), target.messages)
    }

    @Test
    fun `reload requires admin permission`() {
        val target = RecordingTarget(setOf(RealTimeCommandHandler.COMMAND_PERMISSION))
        var reloadCalled = false
        val handler = handler(reload = {
            reloadCalled = true
            Result.success(state())
        })

        handler.execute(target, arrayOf("reload"))

        assertFalse(reloadCalled)
        assertEquals(listOf("[RealTime] denied"), target.messages)
    }

    @Test
    fun `invalid arguments return usage`() {
        val target = RecordingTarget(setOf(RealTimeCommandHandler.COMMAND_PERMISSION))
        val handler = handler()

        handler.execute(target, arrayOf("now", "extra"))

        assertEquals(listOf("[RealTime] usage"), target.messages)
    }

    @Test
    fun `failed reload reports error without changing active state`() {
        val target = RecordingTarget(setOf(RealTimeCommandHandler.ADMIN_PERMISSION))
        val activeState = state()
        val handler =
            RealTimeCommandHandler(
                pluginVersion = "1.2.3",
                currentState = { activeState },
                reload = { Result.failure(IllegalArgumentException("bad timezone")) },
            )

        handler.execute(target, arrayOf("reload"))

        assertTrue(target.messages.single().contains("bad timezone"))
        assertEquals(ZoneId.of("Asia/Shanghai"), activeState.configuration.zoneId)
    }

    private fun handler(
        reload: () -> Result<RuntimeState> = { Result.success(state()) },
    ) = RealTimeCommandHandler(
        pluginVersion = "1.2.3",
        currentState = ::state,
        reload = reload,
    )

    private fun state(): RuntimeState {
        val configuration =
            RealTimeConfiguration(
                generalEnabled = true,
                zoneId = ZoneId.of("Asia/Shanghai"),
                locale = Locale.forLanguageTag("zh-CN"),
                debugLogging = false,
                commandEnabled = true,
                commandFormat = "uuuu-MM-dd HH:mm:ss XXX",
                messages =
                    mapOf(
                        "prefix" to "[RealTime] ",
                        "command.now" to "{prefix}{datetime}",
                        "command.info.header" to "{prefix}info",
                        "command.info.version" to "{prefix}version {version}",
                        "command.info.timezone" to "{prefix}timezone {timezone}",
                        "command.info.locale" to "{prefix}locale {locale}",
                        "command.info.global-enabled" to "{prefix}global {enabled}",
                        "command.info.command-enabled" to "{prefix}command {enabled}",
                        "command.reload.success" to "{prefix}reloaded",
                        "command.reload.failure" to "{prefix}failed {reason}",
                        "command.no-permission" to "{prefix}denied",
                        "command.disabled" to "{prefix}disabled",
                        "command.usage" to "{prefix}usage",
                    ),
            )
        return RuntimeState(
            configuration = configuration,
            timeService =
                ClockRealTimeService(
                    clock = Clock.fixed(Instant.parse("2026-11-23T02:00:00Z"), ZoneOffset.UTC),
                    zoneId = configuration.zoneId,
                    locale = configuration.locale,
                ),
            messages = MessageCatalog(configuration.messages),
        )
    }

    private class RecordingTarget(
        private val permissions: Set<String>,
    ) : CommandTarget {
        val messages = mutableListOf<String>()

        override fun hasPermission(permission: String): Boolean = permission in permissions

        override fun sendMessage(message: String) {
            messages += message
        }
    }
}
