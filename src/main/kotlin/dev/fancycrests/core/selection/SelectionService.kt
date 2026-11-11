package dev.fancycrests.core.selection

import dev.fancycrests.api.Permissions
import dev.fancycrests.api.PermissionVerdict
import dev.fancycrests.api.PlayerTitleState
import dev.fancycrests.api.TitleId
import dev.fancycrests.api.TitleSlot
import dev.fancycrests.core.ServerAdapter
import dev.fancycrests.core.catalog.CatalogService
import dev.fancycrests.core.config.FancyCrestsConfig
import dev.fancycrests.core.config.LegacyTextParser
import dev.fancycrests.core.event.TitleChangeEventPost
import dev.fancycrests.core.event.TitleChangeEventPre
import dev.fancycrests.core.event.TitleOperationType
import dev.fancycrests.core.event.TitleEventBus
import dev.fancycrests.core.permission.PermissionManager
import dev.fancycrests.core.resolve.SnapshotPublisher
import dev.fancycrests.core.storage.OwnershipRecord
import dev.fancycrests.core.storage.OwnershipWriteResult
import dev.fancycrests.core.storage.SqliteStorage
import dev.fancycrests.core.storage.StateSaveResult
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import net.minecraft.network.chat.Component
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/** Target of an unequip operation. */
sealed interface UnequipTarget {
    data class One(val titleId: TitleId) : UnequipTarget
    data object All : UnequipTarget
}

/** Machine-readable rejection causes. */
enum class RejectionReason {
    TITLE_NOT_FOUND,
    SLOT_MISMATCH,
    ALREADY_EQUIPPED,
    LIMIT_REACHED,
    NOT_ELIGIBLE,

    /** The caller lacks the equip/unequip node (checked in the service, not the UI). */
    MISSING_PERMISSION,
    NICKNAME_DISABLED,
    NICKNAME_TOO_SHORT,
    NICKNAME_TOO_LONG,
    NICKNAME_PATTERN,
    NICKNAME_RESERVED,
    NICKNAME_OCCUPIED,
    EVENT_CANCELLED,
    PLAYER_OFFLINE,

    /**
     * The operation body crashed before delivering its own completion
     * (backend/listener/user-code exception caught by the queue guard) and
     * before anything was committed — pre-commit failures only: validation,
     * permission backends, pre-event listeners, storage rejection. The
     * player's state is guaranteed unchanged, so the caller may safely
     * retry. Failures after the storage layer reports the write committed
     * never use this reason: the committed result is delivered instead and
     * only the display cascade is allowed to fail (logged, never rewriting
     * the business outcome).
     */
    INTERNAL_ERROR,
}

/** Result of a selection/ownership operation. */
sealed interface SelectionResult {
    data class Success(
        val operationId: String,
        val oldRevision: Long,
        val newRevision: Long,
    ) : SelectionResult

    data class Rejected(val reason: RejectionReason, val detail: String? = null) : SelectionResult

    /** Optimistic revision check failed (concurrent writer); nothing was written. */
    data object Conflict : SelectionResult

    data class StorageFailure(val message: String) : SelectionResult

    /** The pre-commit event (or a listener exception) cancelled the operation. */
    data object Cancelled : SelectionResult
}

/** Why a player may (or may not) equip a title. */
sealed interface Eligibility {
    /** Public title (no permission in the definition). */
    data object Public : Eligibility

    /** Locally owned (admin grant or imported ownership). */
    data object Owned : Eligibility

    /** Permission backend answered ALLOW for the title's node. */
    data object Permission : Eligibility

    data object NotEligible : Eligibility
}

/**
 * Player-facing selection and eligibility domain service.
 *
 * One change flows strictly per plan chapter 2 ("并发与一致性"):
 * main-thread validation → cancellable pre event (candidate state only) →
 * immutable transaction instruction → single write queue with optimistic
 * revision → main-thread result → publish new state → non-cancellable post
 * event. Operations of one player are serialized (the next op starts only
 * after the previous one completed commit and publish), so a stale callback
 * can never overwrite newer state. Failures never report success and publish
 * nothing.
 *
 * Completion delivery is phase-aware: a pre-commit crash answers
 * Rejected(INTERNAL_ERROR) with the state guaranteed unchanged, while a
 * post-commit failure (publish/post-event after the write committed) never
 * rewrites the committed result — the display failure is logged and the true
 * result is delivered. Each operation's completion fires at most once
 * (delivery latch), even when the user callback itself throws.
 *
 * All public methods must be called on the main thread; callbacks arrive on
 * the main thread as well.
 */
/**
 * Raw nickname storage bounds. Visible length stays capped by
 * [NicknameConfig.maxLength]; the raw cap bounds the stored string when
 * every visible character carries a formatting code pair.
 */
private const val RAW_NICKNAME_LENGTH_FACTOR = 4
private const val RAW_NICKNAME_LENGTH_HEADROOM = 8

private val LOGGER: Logger = LoggerFactory.getLogger("FancyCrests/Selection")

class SelectionService(
    private val adapter: ServerAdapter,
    private val storage: SqliteStorage,
    private val catalog: CatalogService,
    private val permissions: PermissionManager,
    private val configProvider: () -> FancyCrestsConfig,
    private val eventBus: TitleEventBus,
    private val publisher: SnapshotPublisher,
    initialStates: Map<UUID, PlayerTitleState>,
    initialOwnership: List<OwnershipRecord>,
) {
    private val states = ConcurrentHashMap<UUID, PlayerTitleState>(initialStates)
    private val ownership = ConcurrentHashMap<UUID, Set<OwnershipRecord>>().apply {
        for (record in initialOwnership) {
            merge(record.uuid, setOf(record)) { old, new -> old + new }
        }
    }

    /** Per-player serial op queues (one in-flight op per player). */
    private val queues = ConcurrentHashMap<UUID, ArrayDeque<PendingOperation>>()
    private val inFlight = ConcurrentHashMap.newKeySet<UUID>()

    /**
     * One queued operation: its [body] plus the completion to deliver when
     * the body crashes before signalling [body]'s own `done` — without
     * [onCrash] the caller's callback would never run (audit #4: API
     * futures and GUI debounces hung forever while the queue slot leaked).
     */
    private class PendingOperation(
        val body: (() -> Unit) -> Unit,
        val onCrash: (Throwable) -> Unit,
    )

    // ----------------------------------------------------------------------
    // Read APIs (safe from any thread; returns immutable snapshots)
    // ----------------------------------------------------------------------

    fun stateOf(uuid: UUID): PlayerTitleState =
        states[uuid] ?: PlayerTitleState(uuid, emptyList(), emptyList(), null, 0)

    fun ownershipOf(uuid: UUID): List<OwnershipRecord> =
        ownership[uuid]?.toList() ?: emptyList()

    /**
     * Inserts [record] and replaces any existing grant with the same
     * (titleId, source) key. The database enforces that key with a primary
     * key, so the in-memory set must apply the same rule; otherwise repeated
     * grants stack up until a restart, inflating ownership counts.
     */
    private fun recordOwnership(uuid: UUID, record: OwnershipRecord) {
        ownership.merge(uuid, setOf(record)) { old, new ->
            val kept = old.filterNot { it.titleId == record.titleId && it.source == record.source }
            (kept + new).toSet()
        }
    }

    /** Removes the grant with the given (titleId, source) key, if present. */
    private fun dropOwnership(uuid: UUID, titleId: TitleId, source: String) {
        ownership.merge(uuid, emptySet()) { old, _ ->
            old.filterNot { it.titleId == titleId && it.source == source }.toSet()
        }
    }

    fun hasOwnership(uuid: UUID, titleId: TitleId, source: String? = null): Boolean =
        ownership[uuid]?.any { it.titleId == titleId && (source == null || it.source == source) } == true

    val trackedPlayerCount: Int get() = states.size

    val ownershipCount: Int get() = ownership.values.sumOf { it.size }

    fun isEligible(uuid: UUID, titleId: TitleId): Eligibility {
        val definition = catalog.get(titleId) ?: return Eligibility.NotEligible
        if (definition.permission == null) return Eligibility.Public
        if (hasOwnership(uuid, titleId)) return Eligibility.Owned
        if (permissions.check(uuid, definition.permission) == PermissionVerdict.ALLOW) {
            return Eligibility.Permission
        }
        return Eligibility.NotEligible
    }

    /**
     * Equip/unequip gate. Checked here (not only in the command layer) so the
     * GUI and the public API obey the same rule: a player who was explicitly
     * denied `fancycrests.equip` cannot change equipment through any path.
     */
    private fun canModifyEquipment(uuid: UUID): Boolean =
        permissions.check(uuid, Permissions.EQUIP) == PermissionVerdict.ALLOW

    // ----------------------------------------------------------------------
    // Equip / unequip
    // ----------------------------------------------------------------------

    /**
     * Appends [titleId] to the end of its slot list after full validation.
     *
     * @param expectedSlot when non-null, the slot the caller believes the
     *   title occupies (e.g. the menu tab it was clicked in). A mismatch
     *   with the title's configured slot is rejected with
     *   [RejectionReason.SLOT_MISMATCH] instead of silently equipping into
     *   the other slot. Callers that pass nothing keep the old semantics.
     */
    fun equip(
        uuid: UUID,
        titleId: TitleId,
        reason: String,
        expectedSlot: TitleSlot? = null,
        callback: (SelectionResult) -> Unit,
    ) {
        val deliver = deliverOnce(callback)
        submit(uuid, crashCompletion(deliver)) { done ->
            if (!canModifyEquipment(uuid)) {
                deliver(
                    SelectionResult.Rejected(
                        RejectionReason.MISSING_PERMISSION,
                        "you lack '${Permissions.EQUIP}'",
                    ),
                )
                done()
                return@submit
            }
            val current = stateOf(uuid)
            val definition = catalog.get(titleId)
            if (definition == null) {
                deliver(SelectionResult.Rejected(RejectionReason.TITLE_NOT_FOUND, "unknown title '$titleId'"))
                done()
                return@submit
            }
            if (expectedSlot != null && expectedSlot != definition.slot) {
                deliver(
                    SelectionResult.Rejected(
                        RejectionReason.SLOT_MISMATCH,
                        "'$titleId' belongs to the ${definition.slot} slot, not $expectedSlot",
                    ),
                )
                done()
                return@submit
            }
            val list = when (definition.slot) {
                TitleSlot.PREFIX -> current.prefixes
                TitleSlot.SUFFIX -> current.suffixes
            }
            if (list.contains(titleId)) {
                deliver(SelectionResult.Rejected(RejectionReason.ALREADY_EQUIPPED, "'$titleId' is already equipped"))
                done()
                return@submit
            }
            val limit = when (definition.slot) {
                TitleSlot.PREFIX -> configProvider().equipment.maxPrefixes
                TitleSlot.SUFFIX -> configProvider().equipment.maxSuffixes
            }
            if (list.size >= limit) {
                deliver(
                    SelectionResult.Rejected(
                        RejectionReason.LIMIT_REACHED,
                        "slot ${definition.slot} is full ($limit)",
                    ),
                )
                done()
                return@submit
            }
            when (val eligibility = isEligible(uuid, titleId)) {
                Eligibility.NotEligible -> {
                    deliver(
                        SelectionResult.Rejected(
                            RejectionReason.NOT_ELIGIBLE,
                            "you do not own '$titleId' and lack its permission",
                        ),
                    )
                    done()
                    return@submit
                }
                else -> Unit // Public / Owned / Permission all qualify
            }

            val candidate = when (definition.slot) {
                TitleSlot.PREFIX -> current.copy(prefixes = current.prefixes + titleId)
                TitleSlot.SUFFIX -> current.copy(suffixes = current.suffixes + titleId)
            }
            commitState(
                uuid = uuid,
                type = TitleOperationType.EQUIP,
                current = current,
                candidate = candidate,
                reason = reason,
                deliver = deliver,
                done = done,
            )
        }
    }

    /** Removes one or all titles of [slot], preserving the order of the rest. */
    fun unequip(uuid: UUID, slot: TitleSlot, target: UnequipTarget, reason: String, callback: (SelectionResult) -> Unit) {
        val deliver = deliverOnce(callback)
        submit(uuid, crashCompletion(deliver)) { done ->
            if (!canModifyEquipment(uuid)) {
                deliver(
                    SelectionResult.Rejected(
                        RejectionReason.MISSING_PERMISSION,
                        "you lack '${Permissions.EQUIP}'",
                    ),
                )
                done()
                return@submit
            }
            val current = stateOf(uuid)
            val removed: List<TitleId> = when (target) {
                is UnequipTarget.One -> {
                    val present = when (slot) {
                        TitleSlot.PREFIX -> current.prefixes
                        TitleSlot.SUFFIX -> current.suffixes
                    }
                    if (!present.contains(target.titleId)) {
                        deliver(
                            SelectionResult.Rejected(
                                RejectionReason.TITLE_NOT_FOUND,
                                "'${target.titleId}' is not equipped in this slot",
                            ),
                        )
                        done()
                        return@submit
                    }
                    listOf(target.titleId)
                }
                UnequipTarget.All -> when (slot) {
                    TitleSlot.PREFIX -> current.prefixes
                    TitleSlot.SUFFIX -> current.suffixes
                }
            }
            if (removed.isEmpty()) {
                // Nothing to do: report success without a transaction.
                deliver(SelectionResult.Success("noop", current.revision, current.revision))
                done()
                return@submit
            }
            val candidate = when (slot) {
                TitleSlot.PREFIX -> current.copy(prefixes = current.prefixes - removed.toSet())
                TitleSlot.SUFFIX -> current.copy(suffixes = current.suffixes - removed.toSet())
            }
            commitState(
                uuid = uuid,
                type = TitleOperationType.UNEQUIP,
                current = current,
                candidate = candidate,
                reason = reason,
                deliver = deliver,
                done = done,
            )
        }
    }

    // ----------------------------------------------------------------------
    // Nickname
    // ----------------------------------------------------------------------

    /** Sets the nickname ([text] non-null) or resets it (null). Display-only. */
    fun setNickname(uuid: UUID, text: String?, reason: String, callback: (SelectionResult) -> Unit) {
        val deliver = deliverOnce(callback)
        submit(uuid, crashCompletion(deliver)) { done ->
            val current = stateOf(uuid)
            if (text == null) {
                if (current.nickname == null) {
                    deliver(SelectionResult.Success("noop", current.revision, current.revision))
                    done()
                    return@submit
                }
                commitState(
                    uuid = uuid,
                    type = TitleOperationType.NICKNAME_RESET,
                    current = current,
                    candidate = current.copy(nickname = null),
                    reason = reason,
                    deliver = deliver,
                    done = done,
                )
                return@submit
            }

            val config = configProvider()
            if (!config.nickname.allowSet) {
                deliver(SelectionResult.Rejected(RejectionReason.NICKNAME_DISABLED, "nicknames are disabled"))
                done()
                return@submit
            }
            val rejection = validateNickname(uuid, text, config)
            if (rejection != null) {
                deliver(rejection)
                done()
                return@submit
            }
            if (text == current.nickname) {
                deliver(SelectionResult.Success("noop", current.revision, current.revision))
                done()
                return@submit
            }
            commitState(
                uuid = uuid,
                type = TitleOperationType.NICKNAME_SET,
                current = current,
                candidate = current.copy(nickname = text),
                reason = reason,
                deliver = deliver,
                done = done,
            )
        }
    }

    /**
     * Nickname validation matrix: length on the format-stripped normalized
     * text, configured regex, forbidden control/bidi characters, reserved
     * names and other known players' real names (case-insensitive; the
     * bypass permission skips reserved/occupied). The identity key (UUID)
     * is never touched.
     *
     * The normalized text strips only *known* code pairs — matching what
     * [LegacyTextParser.parse] renders — so unknown pairs count as two
     * visible characters and cannot bypass the length cap or the pattern.
     */
    internal fun validateNickname(uuid: UUID, text: String, config: FancyCrestsConfig): SelectionResult.Rejected? {
        val normalized = LegacyTextParser.strip(text, knownCodesOnly = true).trim()
        val bypassReserved =
            permissions.check(uuid, Permissions.NICKNAME_BYPASS_RESERVED) == PermissionVerdict.ALLOW

        // Raw-input gates: the stored and rendered value is the raw text, so
        // forbidden characters and the storage bound must be checked before
        // any code pair collapses — otherwise `Bob&\u202E` would pass every
        // check below as the three visible characters "Bob".
        if (containsForbiddenCharacters(text)) {
            return SelectionResult.Rejected(
                RejectionReason.NICKNAME_PATTERN,
                "control characters, line breaks or bidirectional overrides are not allowed",
            )
        }
        val rawLimit = config.nickname.maxLength * RAW_NICKNAME_LENGTH_FACTOR + RAW_NICKNAME_LENGTH_HEADROOM
        if (text.length > rawLimit) {
            return SelectionResult.Rejected(
                RejectionReason.NICKNAME_TOO_LONG,
                "exceeds $rawLimit raw characters including formatting codes (has ${text.length})",
            )
        }

        if (normalized.length < config.nickname.minLength) {
            return SelectionResult.Rejected(
                RejectionReason.NICKNAME_TOO_SHORT,
                "needs at least ${config.nickname.minLength} visible characters (has ${normalized.length})",
            )
        }
        if (normalized.length > config.nickname.maxLength) {
            return SelectionResult.Rejected(
                RejectionReason.NICKNAME_TOO_LONG,
                "exceeds ${config.nickname.maxLength} visible characters (has ${normalized.length})",
            )
        }
        if (containsForbiddenCharacters(normalized)) {
            return SelectionResult.Rejected(
                RejectionReason.NICKNAME_PATTERN,
                "control characters, line breaks or bidirectional overrides are not allowed",
            )
        }
        if (!config.nickname.pattern.matcher(normalized).matches()) {
            return SelectionResult.Rejected(
                RejectionReason.NICKNAME_PATTERN,
                "does not match the allowed pattern ${config.nickname.pattern.pattern()}",
            )
        }
        if (!bypassReserved) {
            if (config.nickname.reservedNames.any { it.equals(normalized, ignoreCase = true) }) {
                return SelectionResult.Rejected(RejectionReason.NICKNAME_RESERVED, "'$normalized' is reserved")
            }
            val occupiedBy = adapter.knownPlayerNames()[normalized.lowercase()]
            if (occupiedBy != null && occupiedBy != uuid) {
                return SelectionResult.Rejected(
                    RejectionReason.NICKNAME_OCCUPIED,
                    "'$normalized' is the real name of another known player",
                )
            }
        }
        return null
    }

    private fun containsForbiddenCharacters(text: String): Boolean {
        for (char in text) {
            when (char) {
                '\n', '\r', '\t' -> return true
                else -> {
                    if (char.code < 0x20 || char.code == 0x7F) return true
                    // Bidirectional text controls (RTL/LTR overrides and isolates).
                    if (char.code in 0x202A..0x202E || char.code in 0x2066..0x2069) return true
                }
            }
        }
        return false
    }

    // ----------------------------------------------------------------------
    // Ownership
    // ----------------------------------------------------------------------

    /** Grants a title (source "local" for admin commands). */
    fun grant(uuid: UUID, titleId: TitleId, source: String, reason: String, callback: (SelectionResult) -> Unit) {
        val deliver = deliverOnce(callback)
        submit(uuid, crashCompletion(deliver)) { done ->
            if (catalog.get(titleId) == null) {
                deliver(SelectionResult.Rejected(RejectionReason.TITLE_NOT_FOUND, "unknown title '$titleId'"))
                done()
                return@submit
            }
            val operationId = eventBus.newOperationId()
            val record = OwnershipRecord(uuid, titleId, source, System.currentTimeMillis())
            val current = stateOf(uuid)
            val revision = current.revision
            val candidateOwnership = ownershipOf(uuid)
                .filterNot { it.titleId == titleId && it.source == source } + record
            val pre = TitleChangeEventPre(
                uuid = uuid,
                operationId = operationId,
                type = TitleOperationType.GRANT,
                current = current,
                candidate = current,
                reason = reason,
                candidateOwnership = candidateOwnership.sortedBy { it.grantedAt },
            )
            if (eventBus.firePre(pre)) {
                deliver(SelectionResult.Cancelled)
                done()
                return@submit
            }
            storage.enqueueUpsertOwnership(record) { result ->
                try {
                    when (result) {
                        is OwnershipWriteResult.Written -> {
                            // Post-commit from here on: the ownership row is
                            // durable, so a display-layer failure must not
                            // rewrite the business result (audit #2).
                            recordOwnership(uuid, record)
                            runPostCommit(uuid, "publish") { publisher.publish(stateOf(uuid)) }
                            runPostCommit(uuid, "post event") {
                                eventBus.firePost(
                                    TitleChangeEventPost(
                                        uuid = uuid,
                                        operationId = operationId,
                                        type = TitleOperationType.GRANT,
                                        oldRevision = revision,
                                        newRevision = revision,
                                        reason = reason,
                                    ),
                                )
                            }
                            deliver(SelectionResult.Success(operationId.value, revision, revision))
                        }
                        is OwnershipWriteResult.Error ->
                            deliver(SelectionResult.StorageFailure(result.exception.message ?: "ownership write failed"))
                    }
                } finally {
                    // The completion signal must survive a throwing publish or
                    // user callback, or the player's queue slot leaks forever.
                    done()
                }
            }
        }
    }

    /** Revokes an ownership grant and rechecks equipped titles afterwards. */
    fun revoke(uuid: UUID, titleId: TitleId, source: String, reason: String, callback: (SelectionResult) -> Unit) {
        val deliver = deliverOnce(callback)
        submit(uuid, crashCompletion(deliver)) { done ->
            val operationId = eventBus.newOperationId()
            val current = stateOf(uuid)
            val revision = current.revision
            val candidateOwnership = ownershipOf(uuid)
                .filterNot { it.titleId == titleId && it.source == source }
            val pre = TitleChangeEventPre(
                uuid = uuid,
                operationId = operationId,
                type = TitleOperationType.REVOKE,
                current = current,
                candidate = current,
                reason = reason,
                candidateOwnership = candidateOwnership,
            )
            if (eventBus.firePre(pre)) {
                deliver(SelectionResult.Cancelled)
                done()
                return@submit
            }
            storage.enqueueDeleteOwnership(uuid, titleId, source) { result ->
                try {
                    when (result) {
                        is OwnershipWriteResult.Written -> {
                            // Post-commit from here on: the ownership row is
                            // deleted, so a display-layer failure must not
                            // rewrite the business result (audit #2).
                            dropOwnership(uuid, titleId, source)
                            runPostCommit(uuid, "publish") { publisher.publish(stateOf(uuid)) }
                            runPostCommit(uuid, "post event") {
                                eventBus.firePost(
                                    TitleChangeEventPost(
                                        uuid = uuid,
                                        operationId = operationId,
                                        type = TitleOperationType.REVOKE,
                                        oldRevision = revision,
                                        newRevision = revision,
                                        reason = reason,
                                    ),
                                )
                            }
                            // Removing ownership may invalidate an equipped title.
                            runPostCommit(uuid, "recheck") {
                                revalidatePlayer(uuid, "ownership-revoked") {}
                            }
                            deliver(SelectionResult.Success(operationId.value, revision, revision))
                        }
                        is OwnershipWriteResult.Error ->
                            deliver(SelectionResult.StorageFailure(result.exception.message ?: "ownership delete failed"))
                    }
                } finally {
                    // As in grant: a throwing callback must not wedge the queue.
                    done()
                }
            }
        }
    }

    // ----------------------------------------------------------------------
    // Rechecks (login, reload, permission change, catalog change)
    // ----------------------------------------------------------------------

    /**
     * Re-verifies every equipped title against the current catalog,
     * ownership and permission state. Invalid references are unequipped and
     * persisted (reason tag [reason]); the survivor snapshot is re-published.
     * Persisted selections are never treated as permanent authorization.
     */
    fun revalidatePlayer(uuid: UUID, reason: String, callback: (SelectionResult) -> Unit) {
        val deliver = deliverOnce(callback)
        // A backend that cannot answer yet (external provider still loading
        // the user) must not turn interim UNDEFINED verdicts into a persisted
        // unequip: defer entirely and let the backend's own readiness event
        // (load completion) drive the recheck, by which time isReady is true.
        if (!permissions.isReady(uuid)) {
            deferRevalidation(uuid, deliver)
            return
        }
        submit(uuid, crashCompletion(deliver)) { done ->
            // Readiness is also re-checked at execution time: while this
            // operation waited in the queue, the backend data may have been
            // unloaded (audit #3), and executing anyway would again persist
            // an unequip on interim UNDEFINED verdicts.
            if (!permissions.isReady(uuid)) {
                deferRevalidation(uuid, deliver)
                done()
                return@submit
            }
            val current = stateOf(uuid)
            val config = configProvider()
            // Two independent filters, in one pass so the persisted order is
            // preserved: (1) the reference must still resolve and be eligible,
            // (2) the slot must not exceed the current equip limit. Without
            // (2) lowering a limit would never take effect on existing players.
            val eligiblePrefixes = current.prefixes.filter { isEquippableNow(uuid, it, TitleSlot.PREFIX) }
            val eligibleSuffixes = current.suffixes.filter { isEquippableNow(uuid, it, TitleSlot.SUFFIX) }
            val keptPrefixes = eligiblePrefixes.take(config.equipment.maxPrefixes)
            val keptSuffixes = eligibleSuffixes.take(config.equipment.maxSuffixes)
            if (keptPrefixes == current.prefixes && keptSuffixes == current.suffixes) {
                // Nothing changed: the business outcome is "no recheck
                // needed"; a failing re-publish must not rewrite it.
                runPostCommit(uuid, "recheck publish") { publisher.publish(current) }
                deliver(SelectionResult.Success("recheck", current.revision, current.revision))
                done()
                return@submit
            }
            val trimmedByLimit = (eligiblePrefixes.size - keptPrefixes.size) +
                (eligibleSuffixes.size - keptSuffixes.size)
            if (trimmedByLimit > 0) {
                // Only the limit path is worth telling the player about;
                // ineligible titles (renamed/removed/permission lost) are
                // already explained by the catalog and permission feedback.
                adapter.sendSystemMessage(
                    uuid,
                    Component.literal(
                        "Equip limit is now prefix=" + config.equipment.maxPrefixes +
                            " suffix=" + config.equipment.maxSuffixes +
                            ": kept " + keptPrefixes.size + " prefix(es) and " + keptSuffixes.size +
                            " suffix(es) in equip order; " + trimmedByLimit +
                            " title(s) were unequipped.",
                    ).withStyle(net.minecraft.ChatFormatting.YELLOW),
                )
            }
            val candidate = current.copy(prefixes = keptPrefixes, suffixes = keptSuffixes)
            commitState(
                uuid = uuid,
                type = TitleOperationType.RECHECK,
                current = current,
                candidate = candidate,
                reason = reason,
                deliver = deliver,
                done = done,
            )
        }
    }

    /**
     * Defers a revalidation whose backend is not ready: starts the lazy user
     * load and reports success without touching any state. The backend's own
     * load-completion event then drives the real recheck once readiness
     * flips.
     */
    private fun deferRevalidation(uuid: UUID, callback: (SelectionResult) -> Unit) {
        // Deferred revalidation never consults explicitVerdict, so the lazy
        // user load it would have triggered must be started here.
        permissions.warmUp(uuid)
        val revision = stateOf(uuid).revision
        callback(SelectionResult.Success("deferred", revision, revision))
    }

    /** Revalidates every online player (config reload, backend switch). */
    fun revalidateOnline(reason: String) {
        for (uuid in adapter.onlinePlayerUuids()) {
            revalidatePlayer(uuid, reason) {}
        }
    }

    /** Player joined: re-verify persisted equipment, then publish. */
    fun handlePlayerJoin(uuid: UUID) {
        revalidatePlayer(uuid, "login") {}
    }

    /**
     * Player left: stop rendering and drop the per-session bookkeeping. The
     * persisted state map ([states]) is deliberately kept — persistence is
     * not a session concern. The per-player operation queue is removed once
     * it is drained; a still-pending operation keeps its queue until it
     * completes (the completion path tolerates the missing entry).
     */
    fun handlePlayerQuit(uuid: UUID) {
        publisher.forgetTarget(uuid)
        queues.computeIfPresent(uuid) { _, deque ->
            synchronized(deque) {
                if (deque.isEmpty()) null else deque
            }
        }
    }

    /** True when [titleId] resolves in the catalog for [slot] and the player qualifies. */
    private fun isEquippableNow(uuid: UUID, titleId: TitleId, slot: TitleSlot): Boolean {
        val definition = catalog.get(titleId) ?: return false
        if (definition.slot != slot) return false
        return isEligible(uuid, titleId) != Eligibility.NotEligible
    }

    // ----------------------------------------------------------------------
    // Transaction core
    // ----------------------------------------------------------------------

    /**
     * Runs one post-commit step (publish, post event, follow-up recheck).
     * By the time these run the database row is already committed and the
     * revision advanced, so a display-layer failure must never rewrite the
     * business result: it is logged and the committed result is still
     * delivered (audit #2). Without the guard, a throwing publisher turned a
     * committed operation into Rejected(INTERNAL_ERROR) claiming the state
     * was unchanged and "safe to retry" — a retry would then duplicate
     * grants or hit ALREADY_EQUIPPED on a re-equip, with the caller left
     * believing nothing happened.
     */
    private fun runPostCommit(uuid: UUID, step: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            LOGGER.error(
                "post-commit {} for player {} failed; the committed result is still delivered",
                step,
                uuid,
                t,
            )
        }
    }

    /**
     * Shared commit pipeline: pre event on the candidate, optimistic write,
     * main-thread publish, post event. [done] is invoked exactly once on
     * every path (async completions included): the storage callback wraps
     * its body in try/finally and [submit] guards the whole body against
     * crashes, so a throwing listener or user callback can never wedge the
     * player's operation queue. [deliver] is latched (see [deliverOnce]) so
     * the completion fires at most once, and once the storage layer reports
     * [StateSaveResult.Saved] the committed result is delivered no matter
     * what the post-commit cascade does (see [runPostCommit]).
     */
    private fun commitState(
        uuid: UUID,
        type: TitleOperationType,
        current: PlayerTitleState,
        candidate: PlayerTitleState,
        reason: String,
        deliver: (SelectionResult) -> Unit,
        done: () -> Unit,
    ) {
        val operationId = eventBus.newOperationId()
        val pre = TitleChangeEventPre(uuid, operationId, type, current, candidate, reason)
        if (eventBus.firePre(pre)) {
            deliver(SelectionResult.Cancelled)
            done()
            return
        }

        storage.enqueueSaveState(candidate, current.revision) { result ->
            try {
                when (result) {
                    is StateSaveResult.Saved -> {
                        val committed = candidate.copy(revision = result.newRevision)
                        // Guard against a stale completion racing a newer state:
                        // per-player serialization means this can only be the
                        // newest op, but an online count check keeps it honest.
                        states[uuid] = committed
                        // The write is committed and the revision advanced:
                        // from here on this outcome is the truth, and only
                        // the display cascade is allowed to fail (audit #2).
                        runPostCommit(uuid, "publish") { publisher.publish(committed) }
                        runPostCommit(uuid, "post event") {
                            eventBus.firePost(
                                TitleChangeEventPost(
                                    uuid = uuid,
                                    operationId = operationId,
                                    type = type,
                                    oldRevision = current.revision,
                                    newRevision = result.newRevision,
                                    reason = reason,
                                ),
                            )
                        }
                        deliver(SelectionResult.Success(operationId.value, current.revision, result.newRevision))
                    }
                    StateSaveResult.RevisionConflict ->
                        deliver(SelectionResult.Conflict)
                    is StateSaveResult.Error ->
                        deliver(SelectionResult.StorageFailure(result.exception.message ?: "state write failed"))
                }
            } finally {
                // The completion signal must survive a throwing publish or
                // user callback, or the player's queue slot leaks forever.
                done()
            }
        }
    }

    // ----------------------------------------------------------------------
    // Per-player serialization
    // ----------------------------------------------------------------------

    /**
     * Wraps the caller's callback into the operation's single delivery
     * latch: the first result wins, any further delivery attempt — notably
     * the crash completion re-entering after a user callback threw on
     * receiving its result (audit #2) — is ignored. Normal paths already
     * deliver exactly once; the latch only suppresses the redundant second
     * delivery, so the delivery semantics of healthy operations are
     * unchanged.
     */
    private fun deliverOnce(callback: (SelectionResult) -> Unit): (SelectionResult) -> Unit {
        val delivered = AtomicBoolean(false)
        return { result ->
            if (delivered.compareAndSet(false, true)) {
                callback(result)
            }
        }
    }

    /**
     * Completion delivered when an operation crashes before delivering its
     * own result: the queue slot was released and the crash struck before
     * anything was committed (pre-commit only — validation, permission
     * backend, pre-event listener, storage rejection), so the state is
     * unchanged and the caller may safely retry. Without it the caller's
     * callback (API future, GUI debounce) would hang forever on a crashed
     * operation (audit #4). The [deliver] latch makes this a no-op when the
     * real result already went out: a callback that throws after receiving
     * its result must not be handed a second, contradictory one (audit #2).
     */
    private fun crashCompletion(deliver: (SelectionResult) -> Unit): (Throwable) -> Unit =
        { t ->
            deliver(
                SelectionResult.Rejected(
                    RejectionReason.INTERNAL_ERROR,
                    "operation crashed: ${t.message ?: t.javaClass.simpleName}",
                ),
            )
        }

    private fun submit(uuid: UUID, onCrash: (Throwable) -> Unit, body: (() -> Unit) -> Unit) {
        val operation = PendingOperation(body, onCrash)
        val deque = queues.computeIfAbsent(uuid) { ArrayDeque() }
        val runNow = synchronized(deque) {
            if (inFlight.contains(uuid)) {
                deque.addLast(operation)
                false
            } else {
                inFlight.add(uuid)
                true
            }
        }
        if (runNow) {
            runGuarded(uuid, operation, completionFor(uuid))
        }
    }

    /**
     * Runs one operation body so the completion signal can never be lost. Any
     * crash (validation, permission backend, event listener, user callback)
     * releases the player's queue slot instead of leaving [inFlight] set
     * forever, which would deadlock every future operation of that player.
     * The wrapped [done] is idempotent, so a body that throws after already
     * scheduling its async completion cannot double-fire it. After the slot
     * is released the operation's crash completion runs, which the delivery
     * latch turns into a no-op when the real result already went out — so
     * the caller's callback fires at most once even for a user callback
     * that throws on receiving its result (audit #2). Crashes reaching this
     * guard are pre-commit by construction: the post-commit cascade is
     * guarded (see [runPostCommit]) and delivers the committed result
     * instead.
     */
    private fun runGuarded(uuid: UUID, operation: PendingOperation, done: () -> Unit) {
        var signalled = false
        val once: () -> Unit = {
            if (!signalled) {
                signalled = true
                done()
            }
        }
        try {
            operation.body(once)
        } catch (t: Throwable) {
            LOGGER.error(
                "Selection operation for player {} crashed; releasing the queue slot",
                uuid,
                t,
            )
            once()
            try {
                operation.onCrash(t)
            } catch (t2: Throwable) {
                // The crash completion itself must never take down the
                // caller with a second exception.
                LOGGER.error("Crash completion for player {} threw; caller callback lost", uuid, t2)
            }
        }
    }

    private fun completionFor(uuid: UUID): () -> Unit = {
        val deque = queues[uuid]
        if (deque != null) {
            val next = synchronized(deque) {
                val polled = deque.removeFirstOrNull()
                if (polled == null) inFlight.remove(uuid)
                polled
            }
            if (next != null) {
                // Keep running the queue inline; we are on the main thread.
                runGuarded(uuid, next, completionFor(uuid))
            }        } else {
            inFlight.remove(uuid)
        }
    }
}
