package dev.fancycrests.render

/**
 * Conversion between the render distance (blocks) exposed by
 * [dev.fancycrests.api.RenderDistance] and the text_display
 * `view_range` metadata value.
 *
 * Vanilla culls a display entity when
 * `sqrDist >= (viewRange * 64.0 * viewScale)^2`
 * (`Display#shouldRenderAtSqrDistance`, 26.2 bytecode), with the default
 * `view_scale` of 1.0. Therefore, for a wanted visible radius of `blocks`:
 *
 * ```
 * view_range = blocks / 64.0
 * ```
 *
 * The client "entity distance" video setting multiplies via `viewScale` and
 * the vanilla tracking range can shorten the effective distance further; both
 * effects are out of server control and documented in the admin manual.
 */
object ViewRanges {
    /** Divisor between blocks and view_range units (from vanilla bytecode). */
    const val BLOCKS_PER_VIEW_RANGE_UNIT: Double = 64.0

    /** Documented blocks -> view_range conversion. Input must be positive and finite. */
    fun blocksToViewRange(blocks: Double): Float {
        require(blocks.isFinite() && blocks > 0.0) { "render distance must be positive finite: $blocks" }
        return (blocks / BLOCKS_PER_VIEW_RANGE_UNIT).toFloat()
    }
}
