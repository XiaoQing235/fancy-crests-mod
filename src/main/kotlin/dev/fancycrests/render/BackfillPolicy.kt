package dev.fancycrests.render

import java.util.UUID

/**
 * A backfill candidate for [BackfillPolicy.backfillObservers]: the observer
 * handle (production: `ServerPlayer`) plus the two cheap inputs of
 * [BackfillPolicy.shouldBackfill].
 *
 * Generic in both the handle and the level so the policy stays free of any
 * Minecraft class and is unit-testable; the expensive tracking predicate is
 * injected as a function for the same reason.
 */
internal data class BackfillCandidate<O, L>(
    val observer: O,
    val uuid: UUID,
    val level: L,
)

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
 * The third gate — the observer must *actually track* the target on the
 * vanilla tracking layer — is applied by [backfillObservers] via the
 * injected [backfillObservers.isTracked] predicate (production: a query over
 * `ChunkMap.forEachEntityTrackedBy`, which consults the same
 * `TrackedEntity.seenBy` set that drives our START/STOP_TRACKING events).
 * Backfilling pairs *without* a tracking relation was audit #1: label
 * position sync rides on the tracking machinery, so an untracked target's
 * labels froze at the backfill position (a hovering stale name) until the
 * observer relogged. Non-tracking observers are now skipped and receive
 * labels through the normal START_TRACKING path exactly when they come into
 * range.
 */
internal object BackfillPolicy {

    /** True when [observerUuid]/[observerLevel] passes the cheap gates (self + dimension) for the target. */
    fun <L> shouldBackfill(
        targetUuid: UUID,
        targetLevel: L,
        observerUuid: UUID,
        observerLevel: L,
    ): Boolean = observerUuid != targetUuid && observerLevel === targetLevel

    /**
     * Reduces [candidates] to the observers that should receive a backfill
     * spawn: the cheap [shouldBackfill] gates first, then the tracking
     * predicate; the result preserves the candidate order. [isTracked] is
     * only invoked for candidates that passed the cheap gates (the sequence
     * short-circuits), because a tracking query costs a scan of the
     * dimension's tracked-entity map per observer.
     */
    fun <O, L> backfillObservers(
        targetUuid: UUID,
        targetLevel: L,
        candidates: List<BackfillCandidate<O, L>>,
        isTracked: (O) -> Boolean,
    ): List<O> = candidates
        .asSequence()
        .filter { shouldBackfill(targetUuid, targetLevel, it.uuid, it.level) }
        .filter { isTracked(it.observer) }
        .map { it.observer }
        .toList()
}
