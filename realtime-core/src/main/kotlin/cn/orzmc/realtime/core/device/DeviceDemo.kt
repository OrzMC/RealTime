package cn.orzmc.realtime.core.device

/**
 * One block that makes a demo device readable in the world.
 *
 * [data] is a block-data string (`minecraft:redstone_lamp`,
 * `minecraft:comparator[facing=north]`, …). The plugin only ever removes a demo
 * block whose type still matches, so a player build that replaced it is
 * reported instead of destroyed.
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
 * Presentation blocks for the `demo` teaching builds.
 *
 * The plugin owns the device slots; this adds the minimum display that makes a
 * slot readable without any player wiring:
 *
 * ```
 * BCD     lamp above every slot        slot at y, lamp at y + 1
 * analog  lectern, comparator, dust,   comparator reads the lectern, dust
 *         lamp (perpendicular to the   carries the level to the lamp
 *         device axis)
 * ```
 *
 * A `redstone_block` powers the lamp directly above it, so the BCD demo needs no
 * dust at all: one lamp per bit, lit = 1. The analog row is
 * `lectern → comparator → dust → lamp` and needs a support block under the
 * comparator and the dust.
 */
object DeviceDemo {
    /** Demo devices always show seconds, so the display visibly moves. */
    val DEMO_RESOLUTION: OutputResolution = OutputResolution.SECOND

    private const val LAMP = "minecraft:redstone_lamp"
    private const val DUST = "minecraft:redstone_wire"
    private const val STONE = "minecraft:stone"

    /**
     * Display blocks for [slots], grown perpendicular to [axis].
     *
     * The display always extends towards `+z` for an `x` axis and towards `+x`
     * for a `z` axis, so a demo built in a corner grows into open space.
     */
    fun display(
        encoding: OutputEncoding,
        axis: LayoutAxis,
        slots: List<DeviceOutput>,
    ): List<DemoBlock> {
        require(slots.isNotEmpty()) { "a demo needs at least one slot" }
        val alongX = axis == LayoutAxis.X_PLUS || axis == LayoutAxis.X_MINUS
        val blocks = mutableListOf<DemoBlock>()
        slots.forEach { slot ->
            when (encoding) {
                // A redstone block powers the lamp above it: no dust needed.
                OutputEncoding.BCD -> blocks += DemoBlock(slot.x, slot.y + 1, slot.z, LAMP)
                OutputEncoding.ANALOG -> blocks += analogRow(slot, alongX)
            }
        }
        return blocks
    }

    private fun analogRow(
        slot: DeviceOutput,
        alongX: Boolean,
    ): List<DemoBlock> {
        // The comparator's `facing` points at its input, which is the lectern.
        val comparatorFacing = if (alongX) "north" else "west"
        val offsets = 1..3
        return buildList {
            offsets.forEach { step ->
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
