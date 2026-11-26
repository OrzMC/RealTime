package cn.orzmc.realtime.paper.command

import cn.orzmc.realtime.core.device.LayoutAxis
import org.bukkit.block.BlockFace
import java.util.UUID

/**
 * Where a player is aiming, used by the coordinate-free command forms.
 *
 * The origin is the block the player is looking at (within reach) or, when
 * nothing is targeted, the block they are standing on — so "stand there and run
 * the command" and "aim at the spot and run the command" both work. [axis] comes
 * from the horizontal facing, so slots grow in the direction the player looks,
 * and [lecternFacing] turns a lectern towards the player so the page is readable.
 */
data class PlayerPlacement(
    val worldName: String,
    val x: Int,
    val y: Int,
    val z: Int,
    val axis: LayoutAxis,
    val lecternFacing: BlockFace = BlockFace.NORTH,
)

interface CommandTarget {
    /** Player name or `console`. Used for audit logging. */
    val actorName: String

    /** Player UUID, or null when the command comes from the console. */
    val actorId: UUID?

    /** Aim of the executing player, or null for the console and other senders. */
    val placement: PlayerPlacement?
        get() = null

    fun hasPermission(permission: String): Boolean

    fun sendMessage(message: String)
}
