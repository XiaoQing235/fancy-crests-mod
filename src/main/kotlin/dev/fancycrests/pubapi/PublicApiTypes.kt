package dev.fancycrests.pubapi

import dev.fancycrests.api.PlayerTitleState
import dev.fancycrests.api.TitleId
import dev.fancycrests.core.event.TitleChangeEventPost
import dev.fancycrests.core.event.TitleChangeEventPre
import dev.fancycrests.core.event.TitleOperationType
import dev.fancycrests.core.selection.RejectionReason
import dev.fancycrests.core.selection.SelectionResult
import java.util.UUID

/**
 * Stable, versioned surface types of the public API. Core-internal classes
 * are never exposed here; everything callers see is either part of the frozen
 * `dev.fancycrests.api` contract or declared in this file.
 */

/** Kind of mutation (mirrors the core pipeline's operation types). */
enum class ApiTitleOperation {
    EQUIP,
    UNEQUIP,
    NICKNAME_SET,
    NICKNAME_RESET,
    GRANT,
    REVOKE,
    /** Eligibility/config driven recheck that may unequip stale titles. */
    RECHECK,
    ;

    internal companion object {
        fun of(type: TitleOperationType): ApiTitleOperation = valueOf(type.name)
    }
}

/** Machine-readable rejection causes (mirrors core RejectionReason plus API-only checks). */
enum class ApiRejectionReason {
    TITLE_NOT_FOUND,
    SLOT_MISMATCH,
    ALREADY_EQUIPPED,
    LIMIT_REACHED,
    NOT_ELIGIBLE,
    NICKNAME_DISABLED,
    NICKNAME_TOO_SHORT,
    NICKNAME_TOO_LONG,
    NICKNAME_PATTERN,
    NICKNAME_RESERVED,
    NICKNAME_OCCUPIED,
    EVENT_CANCELLED,
    PLAYER_OFFLINE,
    /** Public-API only: the optional [FancyCrestsPublicApi] actor lacked the required admin node. */
    MISSING_PERMISSION,
    ;
}

/** Outcome of a mutating public API call. */
sealed interface ApiCallResult {
    /** Committed and published; [operationId] identifies the transaction. */
    data class Success(
        val operationId: String,
        val oldRevision: Long,
        val newRevision: Long,
    ) : ApiCallResult

    /** Validation or eligibility failed; nothing was written or published. */
    data class Rejected(val reason: ApiRejectionReason, val detail: String?) : ApiCallResult

    /** Optimistic revision check failed (concurrent writer won); nothing was written. */
    data object Conflict : ApiCallResult

    /** Persistence failed; in-memory state was not advanced. */
    data class StorageFailure(val message: String) : ApiCallResult

    /** The cancellable pre-commit event (or a listener exception) aborted the operation. */
    data object Cancelled : ApiCallResult

    companion object {
        internal fun of(result: SelectionResult): ApiCallResult = when (result) {
            is SelectionResult.Success ->
                Success(result.operationId, result.oldRevision, result.newRevision)
            is SelectionResult.Rejected ->
                Rejected(ApiRejectionReason.valueOf(result.reason.name), result.detail)
            SelectionResult.Conflict -> Conflict
            is SelectionResult.StorageFailure -> StorageFailure(result.message)
            SelectionResult.Cancelled -> Cancelled
        }
    }
}

/** Immutable ownership grant exposed to external integrations. */
data class TitleGrant(
    val titleId: TitleId,
    /** Origin of the grant, e.g. "local" (admin command) or "publicapi". */
    val source: String,
    /** Epoch millis at grant time. */
    val grantedAt: Long,
)

/** Why a player may (or may not) equip a title. */
enum class ApiEligibility {
    /** Public title (no permission in the definition). */
    PUBLIC,
    /** Locally owned (admin grant or imported ownership). */
    OWNED,
    /** Permission backend answered ALLOW for the title's node. */
    PERMISSION,
    NOT_ELIGIBLE,
}

/**
 * Cancellable pre-commit event. Delivered on the server main thread with the
 * candidate (not yet written) state; cancelling (or a listener throwing)
 * aborts the operation — nothing is persisted or published. Semantics match
 * the core event exactly because this wrapper forwards to it.
 */
class TitleChangePreEvent internal constructor(internal val core: TitleChangeEventPre) {
    val target: UUID get() = core.uuid
    val operationId: String get() = core.operationId.value
    val operation: ApiTitleOperation get() = ApiTitleOperation.of(core.type)
    /** State before the operation. */
    val current: PlayerTitleState get() = core.current
    /** Proposed state; has not been persisted yet. */
    val candidate: PlayerTitleState get() = core.candidate
    /**
     * Ownership grants after the operation, or null when the operation does
     * not touch the ownership table (equip/unequip/nickname/recheck). For
     * [ApiTitleOperation.GRANT] and [ApiTitleOperation.REVOKE] [candidate]
     * keeps the pre-operation equipment state while this list describes the
     * ownership change that is about to be written.
     */
    val candidateOwnership: List<TitleGrant>?
        get() = core.candidateOwnership?.map { TitleGrant(it.titleId, it.source, it.grantedAt) }
    /** Human-readable reason tag, e.g. "command", "publicapi", "permission-changed". */
    val reason: String get() = core.reason

    val isCancelled: Boolean get() = core.cancelled

    fun cancel() = core.cancel()
}

/**
 * Post-commit event. Delivered on the main thread after the transaction was
 * persisted AND the new state was published; cannot be cancelled. Listener
 * exceptions are logged and remaining listeners still run (core behavior).
 */
class TitleChangePostEvent internal constructor(internal val core: TitleChangeEventPost) {
    val target: UUID get() = core.uuid
    val operationId: String get() = core.operationId.value
    val operation: ApiTitleOperation get() = ApiTitleOperation.of(core.type)
    val oldRevision: Long get() = core.oldRevision
    val newRevision: Long get() = core.newRevision
    val reason: String get() = core.reason
}
