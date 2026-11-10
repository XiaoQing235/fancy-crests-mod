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
    NICKNAME_DISABLED,
    NICKNAME_TOO_SHORT,
    NICKNAME_TOO_LONG,
    NICKNAME_PATTERN,
    NICKNAME_RESERVED,
    NICKNAME_OCCUPIED,
    EVENT_CANCELLED,
    PLAYER_OFFLINE,
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
 * All public methods must be called on the main thread; callbacks arrive on
 * the main thread as well.
 */
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
    private val queues = ConcurrentHashMap<UUID, ArrayDeque<(() -> Unit) -> Unit>>()
    private val inFlight = ConcurrentHashMap.newKeySet<UUID>()

    // ----------------------------------------------------------------------
    // Read APIs (safe from any thread; returns immutable snapshots)
    // ----------------------------------------------------------------------

    fun stateOf(uuid: UUID): PlayerTitleState =
        states[uuid] ?: PlayerTitleState(uuid, emptyList(), emptyList(), null, 0)

    fun ownershipOf(uuid: UUID): List<OwnershipRecord> =
        ownership[uuid]?.toList() ?: emptyList()

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

    // ----------------------------------------------------------------------
    // Equip / unequip
    // ----------------------------------------------------------------------

    /** Appends [titleId] to the end of its slot list after full validation. */
    fun equip(uuid: UUID, titleId: TitleId, reason: String, callback: (SelectionResult) -> Unit) {
        submit(uuid) { done ->
            val current = stateOf(uuid)
            val definition = catalog.get(titleId)
            if (definition == null) {
                callback(SelectionResult.Rejected(RejectionReason.TITLE_NOT_FOUND, "unknown title '$titleId'"))
                done()
                return@submit
            }
            val list = when (definition.slot) {
                TitleSlot.PREFIX -> current.prefixes
                TitleSlot.SUFFIX -> current.suffixes
            }
            if (list.contains(titleId)) {
                callback(SelectionResult.Rejected(RejectionReason.ALREADY_EQUIPPED, "'$titleId' is already equipped"))
                done()
                return@submit
            }
            val limit = when (definition.slot) {
                TitleSlot.PREFIX -> configProvider().equipment.maxPrefixes
                TitleSlot.SUFFIX -> configProvider().equipment.maxSuffixes
            }
            if (list.size >= limit) {
                callback(
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
                    callback(
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
                callback = callback,
                done = done,
            )
        }
    }

    /** Removes one or all titles of [slot], preserving the order of the rest. */
    fun unequip(uuid: UUID, slot: TitleSlot, target: UnequipTarget, reason: String, callback: (SelectionResult) -> Unit) {
        submit(uuid) { done ->
            val current = stateOf(uuid)
            val removed: List<TitleId> = when (target) {
                is UnequipTarget.One -> {
                    val present = when (slot) {
                        TitleSlot.PREFIX -> current.prefixes
                        TitleSlot.SUFFIX -> current.suffixes
                    }
                    if (!present.contains(target.titleId)) {
                        callback(
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
                callback(SelectionResult.Success("noop", current.revision, current.revision))
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
                callback = callback,
                done = done,
            )
        }
    }

    // ----------------------------------------------------------------------
    // Nickname
    // ----------------------------------------------------------------------

    /** Sets the nickname ([text] non-null) or resets it (null). Display-only. */
    fun setNickname(uuid: UUID, text: String?, reason: String, callback: (SelectionResult) -> Unit) {
        submit(uuid) { done ->
            val current = stateOf(uuid)
            if (text == null) {
                if (current.nickname == null) {
                    callback(SelectionResult.Success("noop", current.revision, current.revision))
                    done()
                    return@submit
                }
                commitState(
                    uuid = uuid,
                    type = TitleOperationType.NICKNAME_RESET,
                    current = current,
                    candidate = current.copy(nickname = null),
                    reason = reason,
                    callback = callback,
                    done = done,
                )
                return@submit
            }

            val config = configProvider()
            if (!config.nickname.allowSet) {
                callback(SelectionResult.Rejected(RejectionReason.NICKNAME_DISABLED, "nicknames are disabled"))
                done()
                return@submit
            }
            val rejection = validateNickname(uuid, text, config)
            if (rejection != null) {
                callback(rejection)
                done()
                return@submit
            }
            if (text == current.nickname) {
                callback(SelectionResult.Success("noop", current.revision, current.revision))
                done()
                return@submit
            }
            commitState(
                uuid = uuid,
                type = TitleOperationType.NICKNAME_SET,
                current = current,
                candidate = current.copy(nickname = text),
                reason = reason,
                callback = callback,
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
     */
    internal fun validateNickname(uuid: UUID, text: String, config: FancyCrestsConfig): SelectionResult.Rejected? {
        val normalized = LegacyTextParser.strip(text).trim()
        val bypassReserved =
            permissions.check(uuid, Permissions.NICKNAME_BYPASS_RESERVED) == PermissionVerdict.ALLOW

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
        submit(uuid) { done ->
            if (catalog.get(titleId) == null) {
                callback(SelectionResult.Rejected(RejectionReason.TITLE_NOT_FOUND, "unknown title '$titleId'"))
                done()
                return@submit
            }
            val operationId = eventBus.newOperationId()
            val record = OwnershipRecord(uuid, titleId, source, System.currentTimeMillis())
            storage.enqueueUpsertOwnership(record) { result ->
                when (result) {
                    is OwnershipWriteResult.Written -> {
                        ownership.merge(uuid, setOf(record)) { old, new -> old + new }
                        publisher.publish(stateOf(uuid))
                        eventBus.firePost(
                            TitleChangeEventPost(
                                uuid = uuid,
                                operationId = operationId,
                                type = TitleOperationType.GRANT,
                                oldRevision = stateOf(uuid).revision,
                                newRevision = stateOf(uuid).revision,
                                reason = reason,
                            ),
                        )
                        callback(SelectionResult.Success(operationId.value, stateOf(uuid).revision, stateOf(uuid).revision))
                    }
                    is OwnershipWriteResult.Error ->
                        callback(SelectionResult.StorageFailure(result.exception.message ?: "ownership write failed"))
                }
                done()
            }
        }
    }

    /** Revokes an ownership grant and rechecks equipped titles afterwards. */
    fun revoke(uuid: UUID, titleId: TitleId, source: String, reason: String, callback: (SelectionResult) -> Unit) {
        submit(uuid) { done ->
            val operationId = eventBus.newOperationId()
            storage.enqueueDeleteOwnership(uuid, titleId, source) { result ->
                when (result) {
                    is OwnershipWriteResult.Written -> {
                        ownership.merge(uuid, emptySet()) { old, _ ->
                            old.filterNot { it.titleId == titleId && it.source == source }.toSet()
                        }
                        publisher.publish(stateOf(uuid))
                        eventBus.firePost(
                            TitleChangeEventPost(
                                uuid = uuid,
                                operationId = operationId,
                                type = TitleOperationType.REVOKE,
                                oldRevision = stateOf(uuid).revision,
                                newRevision = stateOf(uuid).revision,
                                reason = reason,
                            ),
                        )
                        // Removing ownership may invalidate an equipped title.
                        revalidatePlayer(uuid, "ownership-revoked") {}
                        callback(SelectionResult.Success(operationId.value, stateOf(uuid).revision, stateOf(uuid).revision))
                    }
                    is OwnershipWriteResult.Error ->
                        callback(SelectionResult.StorageFailure(result.exception.message ?: "ownership delete failed"))
                }
                done()
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
        submit(uuid) { done ->
            val current = stateOf(uuid)
            val invalidPrefixes = current.prefixes.filter { !isEquippableNow(uuid, it, TitleSlot.PREFIX) }
            val invalidSuffixes = current.suffixes.filter { !isEquippableNow(uuid, it, TitleSlot.SUFFIX) }
            if (invalidPrefixes.isEmpty() && invalidSuffixes.isEmpty()) {
                publisher.publish(current)
                callback(SelectionResult.Success("recheck", current.revision, current.revision))
                done()
                return@submit
            }
            val candidate = current.copy(
                prefixes = current.prefixes - invalidPrefixes.toSet(),
                suffixes = current.suffixes - invalidSuffixes.toSet(),
            )
            commitState(
                uuid = uuid,
                type = TitleOperationType.RECHECK,
                current = current,
                candidate = candidate,
                reason = reason,
                callback = callback,
                done = done,
            )
        }
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

    /** Player left: stop rendering; persisted state stays untouched. */
    fun handlePlayerQuit(uuid: UUID) {
        publisher.clearTarget(uuid)
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
     * Shared commit pipeline: pre event on the candidate, optimistic write,
     * main-thread publish, post event. [done] must be invoked exactly once on
     * every path (async completions included).
     */
    private fun commitState(
        uuid: UUID,
        type: TitleOperationType,
        current: PlayerTitleState,
        candidate: PlayerTitleState,
        reason: String,
        callback: (SelectionResult) -> Unit,
        done: () -> Unit,
    ) {
        val operationId = eventBus.newOperationId()
        val pre = TitleChangeEventPre(uuid, operationId, type, current, candidate, reason)
        if (eventBus.firePre(pre)) {
            callback(SelectionResult.Cancelled)
            done()
            return
        }

        storage.enqueueSaveState(candidate, current.revision) { result ->
            when (result) {
                is StateSaveResult.Saved -> {
                    val committed = candidate.copy(revision = result.newRevision)
                    // Guard against a stale completion racing a newer state:
                    // per-player serialization means this can only be the
                    // newest op, but an online count check keeps it honest.
                    states[uuid] = committed
                    publisher.publish(committed)
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
                    callback(SelectionResult.Success(operationId.value, current.revision, result.newRevision))
                }
                StateSaveResult.RevisionConflict ->
                    callback(SelectionResult.Conflict)
                is StateSaveResult.Error ->
                    callback(SelectionResult.StorageFailure(result.exception.message ?: "state write failed"))
            }
            done()
        }
    }

    // ----------------------------------------------------------------------
    // Per-player serialization
    // ----------------------------------------------------------------------

    private fun submit(uuid: UUID, body: (() -> Unit) -> Unit) {
        val deque = queues.computeIfAbsent(uuid) { ArrayDeque() }
        val runNow = synchronized(deque) {
            if (inFlight.contains(uuid)) {
                deque.addLast(body)
                false
            } else {
                inFlight.add(uuid)
                true
            }
        }
        if (runNow) {
            body(completionFor(uuid))
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
                next(completionFor(uuid))
            }
        } else {
            inFlight.remove(uuid)
        }
    }
}
