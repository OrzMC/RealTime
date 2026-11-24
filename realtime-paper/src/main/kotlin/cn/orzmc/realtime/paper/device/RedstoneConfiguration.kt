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
)
