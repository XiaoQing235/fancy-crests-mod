package dev.fancycrests.render

import java.util.UUID

/**
 * Pure candidate filter for the retro-pairing backfill (render batch E2):
 * which online players should receive label entities for a freshly created
 * [TargetRenderState], decided without touching any Minecraft class so the
 * policy is unit-testable. Production instantiates the level parameter with
 * `ServerLevel`, compared by identity (one instance per loaded dimension).
 *
 * Rules and why:
 *  - never the target itself (`uuid` inequality): a player must not render
 *    their own head label;
 *  - same dimension only (`===`): a client that is not in the target's
 *    dimension cannot reference the target entity at all, and label
 *    entities spawned into the observer's client for a foreign world would
 *    be unreachable ghosts no future event ever cleans.
 *
 * Deliberately NOT a distance check: vanilla's per-entity tracking range is
 * not queryable from a server mod. Over-sending to same-dimension observers
 * outside tracking range is accepted - see [RenderService] backfill docs for
 * the client-side safety arguments (view-range culling, warn-and-ignore
 * mount semantics).
 */
internal object BackfillPolicy {

    /** True when [observerUuid]/[observerLevel] is a backfill candidate for the target. */
    fun <L> shouldBackfill(
        targetUuid: UUID,
        targetLevel: L,
        observerUuid: UUID,
        observerLevel: L,
    ): Boolean = observerUuid != targetUuid && observerLevel === targetLevel
}
