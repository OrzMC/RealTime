package cn.orzmc.realtime.core.device

/**
 * Direction a device grows from its origin block.
 *
 * [fromConfig] keeps the short internal form used in persisted devices
 * (`x+`, `x-`, `z+`, `z-`); [fromFacing] maps the player-facing keywords the
 * command surface uses (`north` grows towards negative z, …).
 */
enum class LayoutAxis(
    val configValue: String,
) {
    X_PLUS("x+"),
    X_MINUS("x-"),
    Z_PLUS("z+"),
    Z_MINUS("z-"),
    ;

    /** True when the device grows along the x axis. */
    val alongX: Boolean
        get() = this == X_PLUS || this == X_MINUS

    companion object {
        fun fromConfig(value: String): LayoutAxis? = entries.firstOrNull { it.configValue == value.lowercase() }

        /** Compass keyword a player types, e.g. `facing east` grows towards positive x. */
        fun fromFacing(value: String): LayoutAxis? =
            when (value.lowercase()) {
                "north" -> Z_MINUS
                "south" -> Z_PLUS
                "east" -> X_PLUS
                "west" -> X_MINUS
                else -> null
            }
    }
}

/**
 * Slot placement of a device created from a single origin block.
 *
 * [stride] is the distance between two consecutive slots. The default of 2
 * leaves one empty block between two lecterns, which is exactly the room the
 * comparator reading each of them needs.
 *
 * Offset of slot `i` is `i * stride`.
 */
data class DeviceLayout(
    val stride: Int = DEFAULT_STRIDE,
) {
    init {
        require(stride >= 1) { "stride must be at least 1" }
    }

    /** Distance from the origin block to the slot at [index]. */
    fun offset(index: Int): Int {
        require(index >= 0) { "index must not be negative" }
        return index * stride
    }

    /**
     * Output slots for [resolution] starting at the origin block, laid out along
     * [axis]. The last slot distance is limited so a device cannot walk across
     * the whole world by accident.
     */
    fun outputs(
        resolution: OutputResolution,
        axis: LayoutAxis,
        x: Int,
        y: Int,
        z: Int,
    ): List<DeviceOutput> {
        val count = resolution.digitCount
        val last = offset(count - 1)
        require(last <= MAX_SPAN) { "layout spans $last blocks, the maximum is $MAX_SPAN" }
        return (0 until count).map { index ->
            val distance = offset(index)
            when (axis) {
                LayoutAxis.X_PLUS -> DeviceOutput(index, x + distance, y, z)
                LayoutAxis.X_MINUS -> DeviceOutput(index, x - distance, y, z)
                LayoutAxis.Z_PLUS -> DeviceOutput(index, x, y, z + distance)
                LayoutAxis.Z_MINUS -> DeviceOutput(index, x, y, z - distance)
            }
        }
    }

    companion object {
        /** One empty block between two slots, i.e. room for the reading comparator. */
        const val DEFAULT_STRIDE = 2

        /** Widest device a single `create` may build, in blocks. */
        const val MAX_SPAN = 512

        /** The layout every `create` uses; kept as a value so tests can name it. */
        val DEFAULT = DeviceLayout()
    }
}
