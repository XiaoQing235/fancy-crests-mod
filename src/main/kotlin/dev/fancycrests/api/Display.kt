package dev.fancycrests.api

import java.util.UUID
import net.minecraft.network.chat.Component

/**
 * Immutable, observer-independent display snapshot for one target player.
 * Produced by core resolution; consumed by the head renderer and exporters.
 * The two lines stay structured Components end to end.
 */
data class DisplaySnapshot(
    val target: UUID,
    val firstLine: Component,
    /** Null means no second line (no placeholder entity is kept for it). */
    val secondLine: Component?,
    val matchedSecondLineRuleId: String?,
    /** Monotonic snapshot version for this target. */
    val version: Long,
    /** Server tick at which this snapshot was resolved. */
    val resolvedAtTick: Long,
)

/**
 * Channel contract between core (producer) and the head renderer (consumer).
 * All methods are called on the server main thread.
 */
interface HeadDisplayChannel {
    /** Enables the channel with the given settings. */
    fun start(settings: RenderSettings)

    /** Replaces render settings (hot reload); applied to existing entities. */
    fun updateSettings(settings: RenderSettings)

    /** Publishes the newest snapshot for a target (full state, diffing is internal). */
    fun publish(snapshot: DisplaySnapshot)

    /** Drops head rendering for a target (logout, no eligible content, ...). */
    fun clearTarget(target: UUID)

    /** Disables the head channel: unmounts, removes virtual entities and teams. */
    fun stop()
}

/** Render distance in blocks; positive and finite. Converted to view_range metadata. */
data class RenderDistance(val blocks: Double) {
    init {
        require(blocks.isFinite() && blocks > 0.0) { "render distance must be positive finite: $blocks" }
    }
}

/** Local offset in blocks relative to the mount anchor; Y positive is up. All finite. */
data class RenderOffset(val x: Double, val y: Double, val z: Double) {
    init {
        require(x.isFinite() && y.isFinite() && z.isFinite()) { "offset must be finite: $x/$y/$z" }
    }

    companion object {
        val ZERO = RenderOffset(0.0, 0.0, 0.0)
    }
}

/** Immutable render settings; independent of text rule matching. */
data class RenderSettings(
    val renderDistance: RenderDistance,
    val firstLineOffset: RenderOffset,
    val secondLineOffset: RenderOffset,
)
