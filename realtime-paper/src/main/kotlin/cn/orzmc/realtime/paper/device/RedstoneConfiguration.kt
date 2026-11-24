package cn.orzmc.realtime.paper.device

/**
 * `modules/redstone.yml` runtime configuration.
 *
 * Outputs are full-strength power sources: a logical 1 places a
 * `minecraft:redstone_block` in the output slot and a logical 0 leaves the slot
 * empty. Per-output signal strength is therefore fixed at 15; earlier
 * pre-release builds supported a 1–15 `output-power` value on forced dust power,
 * which cannot drive real redstone on the 26.x API surface.
 */
data class RedstoneConfiguration(
    val enabled: Boolean,
    val limits: RedstoneLimits = RedstoneLimits(),
) {
    init {
        require(limits.maxDevices >= 0) { "maxDevices must not be negative" }
        require(limits.editCooldownSeconds >= 0) { "editCooldownSeconds must not be negative" }
    }
}

/**
 * Authoring limits for `device create` / `device set`.
 *
 * An empty [allowedAreas] means "no area restriction": the commands are still
 * gated behind `realtime.device.edit` (default op), so this is an additional
 * guard for servers that hand that node to non-operators.
 */
data class RedstoneLimits(
    val allowedAreas: List<AllowedArea> = emptyList(),
    val maxDevices: Int = DEFAULT_MAX_DEVICES,
    val editCooldownSeconds: Int = DEFAULT_EDIT_COOLDOWN_SECONDS,
) {
    fun allows(
        world: String,
        x: Int,
        z: Int,
    ): Boolean = allowedAreas.isEmpty() || allowedAreas.any { it.contains(world, x, z) }

    companion object {
        const val DEFAULT_MAX_DEVICES = 50
        const val DEFAULT_EDIT_COOLDOWN_SECONDS = 3
    }
}

/** Inclusive horizontal bounding box for device authoring, per world. */
data class AllowedArea(
    val world: String,
    val minX: Int,
    val maxX: Int,
    val minZ: Int,
    val maxZ: Int,
) {
    init {
        require(world.isNotBlank()) { "allowed area world must not be blank" }
        require(minX <= maxX) { "allowed area min-x must not exceed max-x" }
        require(minZ <= maxZ) { "allowed area min-z must not exceed max-z" }
    }

    fun contains(
        world: String,
        x: Int,
        z: Int,
    ): Boolean = this.world == world && x in minX..maxX && z in minZ..maxZ
}
