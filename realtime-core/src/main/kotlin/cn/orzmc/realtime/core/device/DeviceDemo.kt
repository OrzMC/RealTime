package cn.orzmc.realtime.core.device

/**
 * One block that makes a created device readable in the world.
 *
 * [data] is a block-data string (`minecraft:redstone_comparator[...]`,
 * `minecraft:redstone_wire`, …). The plugin only ever removes a display block
 * whose type still matches, so a player build that replaced it is reported
 * instead of destroyed.
 */
data class DemoBlock(
    val x: Int,
    val y: Int,
    val z: Int,
    val data: String,
) {
    init {
        require(data.isNotBlank()) { "demo block data must not be blank" }
    }

    /** Material part of [data], i.e. everything before the first `[`. */
    val material: String
        get() = data.substringBefore('[')
}

/**
 * Presentation blocks of a created device.
 *
 * The plugin owns the device slots (lecterns); this adds the minimum display
 * that makes each slot readable without any player wiring:
 *
 * ```
 * lectern → comparator → dust → lamp   (perpendicular to the device axis)
 * ```
 *
 * The comparator reads the lectern, the dust carries the level to the lamp, and
 * the comparator and the dust need a support block underneath.
 */
object DeviceDemo {
    private const val LAMP = "minecraft:redstone_lamp"
    private const val DUST = "minecraft:redstone_wire"
    private const val STONE = "minecraft:stone"

    /**
     * Display blocks for [slots], grown perpendicular to [axis].
     *
     * The display always extends towards `+z` for an `x` axis and towards `+x`
     * for a `z` axis, so a device built in a corner grows into open space.
     */
    fun display(
        axis: LayoutAxis,
        slots: List<DeviceOutput>,
    ): List<DemoBlock> {
        require(slots.isNotEmpty()) { "a device needs at least one slot" }
        val alongX = axis.alongX
        return slots.flatMap { slot -> analogRow(slot, alongX) }
    }

    private fun analogRow(
        slot: DeviceOutput,
        alongX: Boolean,
    ): List<DemoBlock> {
        // The comparator's `facing` points at its input, which is the lectern.
        val comparatorFacing = if (alongX) "north" else "west"
        return buildList {
            (1..3).forEach { step ->
                val (x, z) =
                    if (alongX) {
                        slot.x to slot.z + step
                    } else {
                        slot.x + step to slot.z
                    }
                // Comparators and dust need a solid block underneath.
                if (step <= 2) add(DemoBlock(x, slot.y - 1, z, STONE))
                when (step) {
                    1 -> add(DemoBlock(x, slot.y, z, "minecraft:comparator[facing=$comparatorFacing]"))
                    2 -> add(DemoBlock(x, slot.y, z, DUST))
                    else -> add(DemoBlock(x, slot.y, z, LAMP))
                }
            }
        }
    }
}
