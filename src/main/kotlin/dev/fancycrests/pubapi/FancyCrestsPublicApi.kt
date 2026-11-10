package dev.fancycrests.pubapi

import dev.fancycrests.api.DisplaySnapshot
import dev.fancycrests.api.Permissions
import dev.fancycrests.api.PlaceholderKey
import dev.fancycrests.api.PlaceholderRequest
import dev.fancycrests.api.PlaceholderService
import dev.fancycrests.api.PlayerTitleState
import dev.fancycrests.api.PermissionVerdict
import dev.fancycrests.api.TitleDefinition
import dev.fancycrests.api.TitleId
import dev.fancycrests.api.TitleSlot
import dev.fancycrests.core.CoreServices
import dev.fancycrests.core.selection.Eligibility
import dev.fancycrests.core.selection.SelectionResult
import dev.fancycrests.core.selection.UnequipTarget
import net.minecraft.network.chat.Component
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Public facade of Fancy Crests for other mods and plugins (plan chapter 5).
 *
 * Constructed once by the integration layer after [CoreServices.start]; all
 * mutations go through the core selection/permission services, so every rule
 * of the transaction pipeline (validation, cancellable pre event, single
 * write queue with optimistic revision, main-thread publish, post event)
 * applies identically to API callers.
 *
 * Threading contract:
 *  - read-only snapshot reads ([listTitles], [findTitle], [playerState],
 *    [ownershipOf], [catalogVersion], [resolvePlaceholder]) are safe from any
 *    thread and never block;
 *  - reads that must consult live server state ([isEligible],
 *    [currentSnapshot]) return a future completed on the main thread;
 *  - mutations accept any calling thread: when called off the main thread
 *    they are dispatched via the core ServerAdapter and the returned future
 *    completes asynchronously — the caller never waits on main-thread I/O.
 *
 * Exposed data is immutable: frozen api data classes ([TitleDefinition],
 * [PlayerTitleState], [DisplaySnapshot], [Component]) and the DTOs declared
 * in [PublicApiTypes]. No mutable player, team or entity object leaks.
 *
 * See docs/pubapi.md for per-method preconditions and the compatibility
 * promise.
 */
class FancyCrestsPublicApi(
    private val core: CoreServices,
    private val placeholders: PlaceholderService? = null,
) : AutoCloseable {

    init {
        require(core.isStarted) { "CoreServices must be started before constructing FancyCrestsPublicApi" }
    }

    /** Reason tag stamped on every API-initiated operation. */
    val operationReason: String = "publicapi"

    // ----------------------------------------------------------------------
    // Read-only catalog queries (any thread)
    // ----------------------------------------------------------------------

    /** All catalog titles, ordered by [TitleDefinition.sortOrder] then id. */
    fun listTitles(): List<TitleDefinition> =
        core.catalogService.all()
            .sortedWith(compareBy({ it.sortOrder }, { it.id.value }))

    fun findTitle(id: TitleId): TitleDefinition? = core.catalogService.get(id)

    /** Monotonic catalog version; bumped on every successful config reload. */
    fun catalogVersion(): Long = core.catalogService.version

    // ----------------------------------------------------------------------
    // Read-only player queries (any thread)
    // ----------------------------------------------------------------------

    /** Immutable selection state of the player (empty state if unknown). */
    fun playerState(uuid: UUID): PlayerTitleState = core.selectionService.stateOf(uuid)

    /** Ownership grants of the player as immutable DTOs. */
    fun ownershipOf(uuid: UUID): List<TitleGrant> =
        core.selectionService.ownershipOf(uuid).map { TitleGrant(it.titleId, it.source, it.grantedAt) }

    /** True when the player currently owns the title (optionally from a specific source). */
    fun hasOwnership(uuid: UUID, titleId: TitleId, source: String? = null): Boolean =
        core.selectionService.hasOwnership(uuid, titleId, source)

    /**
     * Equip eligibility of the player for the title, resolved on the main
     * thread (permission and operator context are live server state).
     */
    fun isEligible(uuid: UUID, titleId: TitleId): CompletableFuture<ApiEligibility> {
        val future = CompletableFuture<ApiEligibility>()
        onMain {
            try {
                future.complete(
                    when (core.selectionService.isEligible(uuid, titleId)) {
                        Eligibility.Public -> ApiEligibility.PUBLIC
                        Eligibility.Owned -> ApiEligibility.OWNED
                        Eligibility.Permission -> ApiEligibility.PERMISSION
                        Eligibility.NotEligible -> ApiEligibility.NOT_ELIGIBLE
                    },
                )
            } catch (t: Throwable) {
                future.completeExceptionally(t)
            }
        }
        return future
    }

    /**
     * Current display snapshot of the target, resolved on the main thread
     * from the same immutable inputs the renderer consumes (selection state,
     * catalog, config, permission chain).
     *
     * Note: [DisplaySnapshot.version] carries the player's state revision
     * here, not the render channel's internal snapshot counter (core does not
     * expose the published-snapshot cache yet; see docs/pubapi.md).
     * The profile-name fallback for offline targets is "Unknown", matching
     * core's publish path.
     */
    fun currentSnapshot(uuid: UUID): CompletableFuture<DisplaySnapshot> {
        val future = CompletableFuture<DisplaySnapshot>()
        onMain {
            try {
                val state = core.selectionService.stateOf(uuid)
                val profileName = core.serverAdapter.profileName(uuid) ?: "Unknown"
                val dimensionKey = core.serverAdapter.dimensionKey(uuid)
                val resolved = core.snapshotResolver.resolve(state, profileName, dimensionKey)
                future.complete(
                    DisplaySnapshot(
                        target = uuid,
                        firstLine = resolved.firstLine,
                        secondLine = resolved.secondLine,
                        matchedSecondLineRuleId = resolved.matchedRuleId,
                        version = state.revision,
                        resolvedAtTick = core.serverAdapter.currentTick(),
                    ),
                )
            } catch (t: Throwable) {
                future.completeExceptionally(t)
            }
        }
        return future
    }

    // ----------------------------------------------------------------------
    // Placeholder passthrough (any thread; loop-guarded)
    // ----------------------------------------------------------------------

    /**
     * Resolves one standard placeholder for the target from published
     * snapshots. Returns null for unknown keys, missing values or when the
     * resolution chain is blocked (cycle/depth guard, resolver failure) —
     * consistent with the Text Placeholder API export.
     */
    fun resolvePlaceholder(target: UUID, key: PlaceholderKey): Component? {
        val service = placeholders ?: return null
        val chainId = "fancycrests:${key.value}"
        if (!ResolveChain.enter(chainId)) return null
        return try {
            service.resolve(key, PlaceholderRequest(target, observer = null))
        } catch (_: Throwable) {
            null
        } finally {
            ResolveChain.exit(chainId)
        }
    }

    // ----------------------------------------------------------------------
    // Mutations (any thread; executed and completed on the main thread)
    // ----------------------------------------------------------------------

    /**
     * Grants the title to the player. When [actor] is given, the actor must
     * hold `fancycrests.admin.grant` (rejected with MISSING_PERMISSION
     * otherwise); a null actor marks a trusted integration call.
     */
    fun grantTitle(uuid: UUID, titleId: TitleId, actor: UUID? = null, source: String = "publicapi"): CompletableFuture<ApiCallResult> =
        submit(actor, Permissions.GRANT) { callback ->
            core.selectionService.grant(uuid, titleId, source, operationReason, callback)
        }

    /** Revokes one ownership grant (see [grantTitle] about [actor] and `fancycrests.admin.revoke`). */
    fun revokeTitle(uuid: UUID, titleId: TitleId, actor: UUID? = null, source: String = "publicapi"): CompletableFuture<ApiCallResult> =
        submit(actor, Permissions.REVOKE) { callback ->
            core.selectionService.revoke(uuid, titleId, source, operationReason, callback)
        }

    /**
     * Appends the title to its slot after full validation (eligibility,
     * limits, duplicates). Always executes as the target player; the
     * eligibility rules are the same as for the in-game menu.
     */
    fun equipTitle(uuid: UUID, titleId: TitleId): CompletableFuture<ApiCallResult> =
        submit { callback ->
            core.selectionService.equip(uuid, titleId, operationReason, callback)
        }

    /** Removes one equipped title from its slot. */
    fun unequipTitle(uuid: UUID, slot: TitleSlot, titleId: TitleId): CompletableFuture<ApiCallResult> =
        submit { callback ->
            core.selectionService.unequip(uuid, slot, UnequipTarget.One(titleId), operationReason, callback)
        }

    /** Removes all equipped titles of [slot], preserving the order of the rest. */
    fun unequipAll(uuid: UUID, slot: TitleSlot): CompletableFuture<ApiCallResult> =
        submit { callback ->
            core.selectionService.unequip(uuid, slot, UnequipTarget.All, operationReason, callback)
        }

    /**
     * Sets the nickname. When [actor] is given it must hold
     * `fancycrests.nickname.set`; length/pattern/reserved-name validation is
     * always applied by core regardless of the actor.
     */
    fun setNickname(uuid: UUID, text: String, actor: UUID? = null): CompletableFuture<ApiCallResult> =
        submit(actor, Permissions.NICKNAME_SET) { callback ->
            core.selectionService.setNickname(uuid, text, operationReason, callback)
        }

    /** Resets the nickname (see [setNickname] about [actor]). */
    fun resetNickname(uuid: UUID, actor: UUID? = null): CompletableFuture<ApiCallResult> =
        submit(actor, Permissions.NICKNAME_SET) { callback ->
            core.selectionService.setNickname(uuid, null, operationReason, callback)
        }

    // ----------------------------------------------------------------------
    // Events (bridge of the core TitleChangeEvent Pre/Post pair)
    // ----------------------------------------------------------------------

    /**
     * Subscribes to the cancellable pre-commit event. The listener runs on
     * the main thread; a listener throwing aborts the operation (identical to
     * core listener semantics). Returns a handle that unsubscribes.
     */
    fun onPreChange(listener: (TitleChangePreEvent) -> Unit): AutoCloseable {
        preListeners.add(listener)
        return Subscription { preListeners.remove(listener) }
    }

    /**
     * Subscribes to the non-cancellable post-commit event (main thread, after
     * persistence and publish). Returns a handle that unsubscribes.
     */
    fun onPostChange(listener: (TitleChangePostEvent) -> Unit): AutoCloseable {
        postListeners.add(listener)
        return Subscription { postListeners.remove(listener) }
    }

    /** Drops all event subscriptions registered through this facade. */
    override fun close() {
        preListeners.clear()
        postListeners.clear()
    }

    // ----------------------------------------------------------------------
    // Internals
    // ----------------------------------------------------------------------

    private val preListeners = CopyOnWriteArrayList<(TitleChangePreEvent) -> Unit>()
    private val postListeners = CopyOnWriteArrayList<(TitleChangePostEvent) -> Unit>()

    /**
     * One forwarder per bus, registered for the lifetime of the core services.
     * Exceptions deliberately propagate: the core bus treats a throwing pre
     * listener as "cancel the operation" and a throwing post listener is
     * logged there, preserving core semantics for API listeners.
     */
    private val preForwarder: (dev.fancycrests.core.event.TitleChangeEventPre) -> Unit = { event ->
        val wrapped = TitleChangePreEvent(event)
        for (listener in preListeners) listener(wrapped)
    }

    private val postForwarder: (dev.fancycrests.core.event.TitleChangeEventPost) -> Unit = { event ->
        val wrapped = TitleChangePostEvent(event)
        for (listener in postListeners) listener(wrapped)
    }

    init {
        core.eventBus.addPreListener(preForwarder)
        core.eventBus.addPostListener(postForwarder)
    }

    private class Subscription(private val remove: () -> Unit) : AutoCloseable {
        override fun close() = remove()
    }

    private fun onMain(action: () -> Unit) {
        core.serverAdapter.execute { action() }
    }

    /**
     * Runs [submit] on the main thread (immediately when already there) and
     * completes the future with the core result mapped to [ApiCallResult].
     * [actor]/[node], when both are non-null, enforce the admin permission
     * before the operation reaches the pipeline.
     */
    private fun submit(
        actor: UUID? = null,
        node: String? = null,
        submit: ((SelectionResult) -> Unit) -> Unit,
    ): CompletableFuture<ApiCallResult> {
        val future = CompletableFuture<ApiCallResult>()
        onMain {
            try {
                val denied = actor != null && node != null &&
                    core.permissionManager.check(actor, node) != PermissionVerdict.ALLOW
                if (denied) {
                    future.complete(
                        ApiCallResult.Rejected(ApiRejectionReason.MISSING_PERMISSION, "requires $node"),
                    )
                } else {
                    submit { result -> future.complete(ApiCallResult.of(result)) }
                }
            } catch (t: Throwable) {
                future.completeExceptionally(t)
            }
        }
        return future
    }
}
