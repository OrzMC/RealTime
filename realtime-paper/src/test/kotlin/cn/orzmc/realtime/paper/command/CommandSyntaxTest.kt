package cn.orzmc.realtime.paper.command

import cn.orzmc.realtime.core.device.LayoutAxis
import cn.orzmc.realtime.core.device.OutputResolution
import cn.orzmc.realtime.paper.command.CommandSyntax.CleanupAction
import cn.orzmc.realtime.paper.command.CommandSyntax.Coordinates
import cn.orzmc.realtime.paper.command.CommandSyntax.CreateMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class CommandSyntaxTest {
    private fun parse(vararg args: String) = CommandSyntax.parse(arrayOf(*args))

    @Test
    fun `no arguments shows the overview and a bare name shows that clock`() {
        assertEquals(CommandSyntax.Invocation.Overview, parse())
        assertEquals(CommandSyntax.Invocation.ShowDevice("lobby"), parse("lobby"))
        assertEquals(CommandSyntax.Invocation.ShowDevice("Lobby"), parse("Lobby"))
    }

    @Test
    fun `create defaults to second resolution, display blocks and enabled`() {
        val create = parse("create", "lobby") as CommandSyntax.Invocation.Create

        assertEquals("lobby", create.name)
        assertEquals(OutputResolution.SECOND, create.spec.resolution)
        assertEquals(CreateMode.DISPLAY, create.spec.mode)
        assertEquals(null, create.spec.at)
        assertEquals(null, create.spec.facing)
    }

    @Test
    fun `create accepts resolution, mode, origin and facing in any order`() {
        val create =
            parse("create", "wall", "facing", "west", "at", "10", "64", "-3", "minute", "nodisplay") as
                CommandSyntax.Invocation.Create

        assertEquals(OutputResolution.MINUTE, create.spec.resolution)
        assertEquals(CreateMode.NO_DISPLAY, create.spec.mode)
        assertEquals(Coordinates(10, 64, -3), create.spec.at)
        assertEquals(LayoutAxis.X_MINUS, create.spec.facing)
    }

    @Test
    fun `manual wins over nodisplay and still parses the origin`() {
        val create =
            parse("create", "machine", "nodisplay", "manual", "at", "0", "70", "0") as
                CommandSyntax.Invocation.Create

        assertEquals(CreateMode.MANUAL, create.spec.mode)
        assertEquals(Coordinates(0, 70, 0), create.spec.at)
    }

    @Test
    fun `create reports the bad token instead of dumping the usage`() {
        val unknown = parse("create", "lobby", "seconds") as CommandSyntax.Invocation.Invalid
        assertEquals("command.create.unknown-option", unknown.key)
        assertEquals("seconds", unknown.placeholders["value"])

        val facing = parse("create", "lobby", "facing", "up") as CommandSyntax.Invocation.Invalid
        assertEquals("command.create.invalid-facing", facing.key)

        val missing = parse("create", "lobby", "at", "1", "2") as CommandSyntax.Invocation.Invalid
        assertEquals("command.create.missing-coordinates", missing.key)

        val duplicate = parse("create", "lobby", "minute", "second") as CommandSyntax.Invocation.Invalid
        assertEquals("command.create.duplicate-option", duplicate.key)
    }

    @Test
    fun `a verb cannot be used as a clock name`() {
        val invalid = parse("create", "place") as CommandSyntax.Invocation.Invalid
        assertEquals("command.create.reserved-name", invalid.key)
    }

    @Test
    fun `toggle, remove and place carry the clock name`() {
        assertEquals(
            CommandSyntax.Invocation.Toggle("lobby", true),
            parse("on", "lobby"),
        )
        assertEquals(
            CommandSyntax.Invocation.Toggle("lobby", false),
            parse("OFF", "lobby"),
        )
        assertEquals(CommandSyntax.Invocation.Remove("lobby"), parse("remove", "lobby"))
        assertEquals(
            CommandSyntax.Invocation.Place("lobby", 3, null),
            parse("place", "lobby", "3"),
        )
        assertEquals(
            CommandSyntax.Invocation.Place("lobby", 3, Coordinates(1, 64, 2)),
            parse("place", "lobby", "3", "1", "64", "2"),
        )
    }

    @Test
    fun `place rejects a bad slot or a partial coordinate triple`() {
        assertEquals(
            "command.place.invalid-slot",
            (parse("place", "lobby", "x") as CommandSyntax.Invocation.Invalid).key,
        )
        assertEquals(
            "command.place.invalid-slot",
            (parse("place", "lobby", "-1") as CommandSyntax.Invocation.Invalid).key,
        )
        assertEquals(
            "command.place.invalid-coordinates",
            (parse("place", "lobby", "3", "1", "2", "x") as CommandSyntax.Invocation.Invalid).key,
        )
        assertEquals("command.usage", (parse("place", "lobby", "3", "1") as CommandSyntax.Invocation.Invalid).key)
    }

    @Test
    fun `cleanup lists, retries and dismisses by job id`() {
        assertEquals(
            CommandSyntax.Invocation.Cleanup(CleanupAction.List),
            parse("cleanup"),
        )
        val id = UUID.randomUUID()
        assertEquals(
            CommandSyntax.Invocation.Cleanup(CleanupAction.Retry(id)),
            parse("cleanup", "retry", id.toString()),
        )
        assertEquals(
            CommandSyntax.Invocation.Cleanup(CleanupAction.Dismiss(id)),
            parse("cleanup", "dismiss", id.toString()),
        )
        assertEquals(
            "command.cleanup.invalid-id",
            (parse("cleanup", "retry", "nope") as CommandSyntax.Invocation.Invalid).key,
        )
    }

    @Test
    fun `simple verbs reject extra arguments`() {
        assertEquals("command.usage", (parse("status", "x") as CommandSyntax.Invocation.Invalid).key)
        assertEquals("command.usage", (parse("help", "all") as CommandSyntax.Invocation.Invalid).key)
        assertEquals("command.usage", (parse("reload", "now") as CommandSyntax.Invocation.Invalid).key)
        assertEquals("command.usage", (parse("on") as CommandSyntax.Invocation.Invalid).key)
    }

    @Test
    fun `verbs removed in this version point at their replacement`() {
        listOf("info", "list", "device", "demo", "set", "now").forEach { verb ->
            val removed = parse(verb) as CommandSyntax.Invocation.Removed
            assertEquals(verb, removed.verb)
            assertTrue(removed.replacement.isNotBlank())
        }
    }
}
