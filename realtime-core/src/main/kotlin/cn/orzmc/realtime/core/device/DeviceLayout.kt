package cn.orzmc.realtime.core.device

/** Direction a device grows from its origin block. */
enum class LayoutAxis(
    val configValue: String,
) {
    X_PLUS("x+"),
    X_MINUS("x-"),
    Z_PLUS("z+"),
    Z_MINUS("z-"),
    ;

    companion object {
        fun fromConfig(value: String): LayoutAxis? = entries.firstOrNull { it.configValue == value.lowercase() }
    }
}

/**
 * Slot placement of a device created from a single origin block.
 *
 * [stride] is the distance between two consecutive slots, so a stride of 2
 * leaves exactly one empty block between them: adjacent power sources can feed
 * into each other's circuits, and an analog lectern needs room for the
 * comparator that reads it.
 *
 * [groupSize] groups slots (one digit per group in the BCD layout) and
 * [groupGap] adds extra empty blocks between groups, so the in-world build
 * shows where one digit ends and the next begins.
 *
 * Offset of slot `i` is `i * stride + (i / groupSize) * groupGap`.
 */
data class DeviceLayout(
    val stride: Int = 1,
    val groupSize: Int = NO_GROUPS,
    val groupGap: Int = 0,
) {
    init {
        require(stride >= 1) { "stride must be at least 1" }
        require(groupSize >= 1) { "groupSize must be at least 1" }
        require(groupGap >= 0) { "groupGap must not be negative" }
    }

    /** Distance from the origin block to the slot at [index]. */
    fun offset(index: Int): Int {
        require(index >= 0) { "index must not be negative" }
        return index * stride + (index / groupSize) * groupGap
    }

    /**
     * Output slots for [encoding] starting at the origin block, laid out along
     * [axis]. Last slot distance is limited so a device cannot walk across the
     * whole world by accident.
     */
    fun outputs(
        encoding: OutputEncoding,
        resolution: OutputResolution,
        axis: LayoutAxis,
        x: Int,
        y: Int,
        z: Int,
    ): List<DeviceOutput> {
        val count = encoding.outputCount(resolution)
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
        /** Disables grouping: every slot keeps the same stride. */
        const val NO_GROUPS = Int.MAX_VALUE

        /** Widest device a single `device create` may build, in blocks. */
        const val MAX_SPAN = 512

        /**
         * Default placement per encoding.
         *
         * Analog slots are lecterns read by a comparator, so they always keep one
         * empty block between neighbours. BCD slots are power sources and keep
         * the compact layout they have had since 0.1.0.
         */
        fun default(encoding: OutputEncoding): DeviceLayout =
            when (encoding) {
                OutputEncoding.BCD -> DeviceLayout()
                OutputEncoding.ANALOG -> DeviceLayout(stride = 2)
            }
    }
}
