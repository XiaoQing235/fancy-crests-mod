package dev.fancycrests.adapter.luckperms

import dev.fancycrests.core.CoreServices
import dev.fancycrests.core.ServerAdapter
import dev.fancycrests.core.permission.PermissionBackend
import dev.fancycrests.core.permission.PermissionManager
import net.luckperms.api.LuckPerms
import net.luckperms.api.LuckPermsProvider
import net.luckperms.api.event.EventSubscription
import net.luckperms.api.event.LuckPermsEvent
import net.luckperms.api.event.context.ContextUpdateEvent
import net.luckperms.api.event.node.NodeAddEvent
import net.luckperms.api.event.node.NodeClearEvent
import net.luckperms.api.event.node.NodeMutateEvent
import net.luckperms.api.event.node.NodeRemoveEvent
import net.luckperms.api.event.user.UserDataRecalculateEvent
import net.luckperms.api.event.user.UserLoadEvent
import net.luckperms.api.model.PermissionHolder
import net.luckperms.api.model.user.User
import net.luckperms.api.util.Tristate
import net.minecraft.world.entity.player.Player
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Live handle of a mounted LuckPerms adapter. Returned by [LuckPermsAdapter.mount];
 * call [close] on server stopping (unregisters the backend and all LP event
 * subscriptions).
 *
 * Revocation latency declaration (plan chapter 1): while mounted, permission
 * changes observed through LuckPerms events are applied on the next
 * main-thread task — i.e. immediate/event-driven. If event subscription
 * failed the adapter is never mounted (the backend answers nothing), so
 * "unavailable" never masquerades as "eventually consistent".
 */
class LuckPermsMount internal constructor(
    private val core: CoreServices,
    internal val backend: LuckPermsPermissionBackend,
    private val bridge: LuckPermsEventBridge,
) : AutoCloseable {
    /** Human-readable maximum revocation delay statement for diagnostics. */
    val revocationLatency: String =
        "immediate (event-driven; applied on the next main-thread task after the LuckPerms event)"

    val backendName: String get() = backend.name

    override fun close() {
        try {
            core.permissionManager.removeBackend(backend)
        } catch (t: Throwable) {
            LoggerFactory.getLogger("FancyCrests/LuckPerms").warn("Failed to unregister LuckPerms backend", t)
        }
        bridge.close()
    }

    companion object {
        private val logger: Logger = LoggerFactory.getLogger("FancyCrests/LuckPerms")

        /**
         * Wires the backend into [core]. Throws when LuckPerms is present but
         * unusable (provider not loaded, subscription failure); the caller
         * ([LuckPermsAdapter.mount]) turns any throw into "not mounted".
         */
        internal fun create(core: CoreServices): LuckPermsMount {
            // Note: LuckPermsProvider.NotLoadedException is package-private in
            // api 5.5 (verified with javap); IllegalStateException is the
            // closest catchable supertype.
            val luckPerms: LuckPerms = LuckPermsProvider.get()
            val backend = LuckPermsPermissionBackend(luckPerms, core.serverAdapter, core.permissionManager)
            val bridge = LuckPermsEventBridge(luckPerms, core.serverAdapter, core.permissionManager)
            bridge.subscribeAll()
            return LuckPermsMount(core, backend, bridge)
        }
    }
}

/**
 * [PermissionBackend] reading LuckPerms' *effective* (inherited, wildcard
 * resolved) permission state. Explicit TRUE maps to ALLOW, explicit FALSE to
 * DENY, UNDEFINED stays UNDEFINED so core's chain continues with the built-in
 * backend and the documented node default (core semantics are untouched).
 *
 * When a user is not loaded yet the backend has no opinion (UNDEFINED —
 * never a silent ALLOW) and kicks off an async LuckPerms load; once it
 * completes the player is marked dirty and rechecked on the main thread.
 */
internal class LuckPermsPermissionBackend(
    private val luckPerms: LuckPerms,
    private val adapter: ServerAdapter,
    private val permissions: PermissionManager,
) : PermissionBackend {

    private val logger: Logger = LoggerFactory.getLogger("FancyCrests/LuckPerms")

    /** UUIDs with an in-flight lazy loadUser (dedup guard). */
    private val pendingLoads = ConcurrentHashMap.newKeySet<UUID>()

    override val name: String = "luckperms"

    /**
     * Ready only once LuckPerms has the user's data loaded. Core defers
     * whole-player revalidation while this is false, so the not-loaded
     * window can no longer unequip and persist permission-gated titles;
     * the lazy load below flips readiness and drives the real recheck.
     */
    override fun isReady(uuid: UUID): Boolean = luckPerms.userManager.getUser(uuid) != null

    /** Starts the lazy user load when core defers revalidation. */
    override fun warmUp(uuid: UUID) = requestLazyLoad(uuid)

    override fun explicitVerdict(uuid: UUID, node: String): dev.fancycrests.api.PermissionVerdict {
        val user = luckPerms.userManager.getUser(uuid)
        if (user == null) {
            // Source data not available for this player yet: no opinion.
            requestLazyLoad(uuid)
            return LuckPermsVerdicts.verdictOf(null)
        }
        // Query with the server's current contexts for that user.
        val options = luckPerms.contextManager.getQueryOptions(user)
            .orElseGet { user.queryOptions }
        val tristate = user.cachedData.getPermissionData(options).checkPermission(node)
        return LuckPermsVerdicts.verdictOf(
            when (tristate) {
                Tristate.TRUE -> true
                Tristate.FALSE -> false
                Tristate.UNDEFINED -> null
            },
        )
    }

    /** Asks LuckPerms to load (and cache) the user; rechecks once loaded. */
    private fun requestLazyLoad(uuid: UUID) {
        if (!pendingLoads.add(uuid)) return
        try {
            luckPerms.userManager.loadUser(uuid).whenComplete { _, error ->
                pendingLoads.remove(uuid)
                if (error != null) {
                    logger.debug("LuckPerms loadUser failed for {}", uuid, error)
                } else {
                    scheduleRecheck(uuid)
                }
            }
        } catch (t: Throwable) {
            pendingLoads.remove(uuid)
            logger.debug("LuckPerms loadUser threw for {}", uuid, t)
        }
    }

    private fun scheduleRecheck(uuid: UUID) {
        permissions.markDirty(uuid)
        adapter.execute { permissions.recheckNow(uuid) }
    }
}

/**
 * Subscribes to LuckPerms events and routes every relevant change to
 * markDirty + main-thread recheckNow via [ServerAdapter]. LP fires events on
 * its own async bus, so the recheck is always dispatched, never run inline.
 */

/** Which players a LuckPerms change requires revalidating. */
internal sealed interface RecheckScope {
    /** A change scoped to one known user. */
    data class One(val uuid: UUID) : RecheckScope

    /** Inheritance or context subjects are opaque here: revalidate everyone online. */
    data object AllOnline : RecheckScope
}

/**
 * Pure decision (no LuckPerms types, so it is unit-testable without the
 * optional dependency): a change attributable to a specific user rechecks
 * that user; anything else (group node edits, unknown holders, non-user
 * context subjects) is opaque and conservatively revalidates everyone.
 */
internal fun recheckScopeFor(subjectUuid: UUID?): RecheckScope =
    if (subjectUuid != null) RecheckScope.One(subjectUuid) else RecheckScope.AllOnline

/**
 * Applies a [RecheckScope] through [ServerAdapter].
 *
 * [RecheckScope.AllOnline] snapshots the online list *inside* the
 * main-thread task: LuckPerms events fire on LP's async bus, and reading
 * playerList.players off-thread risks a ConcurrentModificationException
 * (26.2 backs it with a plain ArrayList that the main thread mutates).
 * markDirty itself is thread-safe either way.
 */
internal fun applyRecheckScope(adapter: ServerAdapter, permissions: PermissionManager, scope: RecheckScope) {
    when (scope) {
        is RecheckScope.One -> {
            permissions.markDirty(scope.uuid)
            adapter.execute { permissions.recheckNow(scope.uuid) }
        }
        RecheckScope.AllOnline -> adapter.execute {
            for (uuid in adapter.onlinePlayerUuids()) {
                permissions.markDirty(uuid)
                permissions.recheckNow(uuid)
            }
        }
    }
}

internal class LuckPermsEventBridge(
    private val luckPerms: LuckPerms,
    private val adapter: ServerAdapter,
    private val permissions: PermissionManager,
) : AutoCloseable {

    private val logger: Logger = LoggerFactory.getLogger("FancyCrests/LuckPerms")
    private val subscriptions = ArrayList<EventSubscription<*>>()

    fun subscribeAll() {
        try {
            // User data lifecycle.
            subscribe(UserLoadEvent::class.java) { event ->
                onUserChange(event.user.uniqueId)
            }
            // Fired after any recalculation of a user's permission data
            // (node edits on the user, inherited group changes, context swaps).
            subscribe(UserDataRecalculateEvent::class.java) { event ->
                onUserChange(event.user.uniqueId)
            }
            // Direct node mutations ("PermissionChange"): user-targeted events
            // recheck that user; group-targeted events conservatively recheck
            // every online player (inheritors are unknown here).
            subscribe(NodeAddEvent::class.java) { event -> onNodeMutate(event) }
            subscribe(NodeRemoveEvent::class.java) { event -> onNodeMutate(event) }
            subscribe(NodeClearEvent::class.java) { event -> onNodeMutate(event) }
            // Context changes (world, server, ...) can flip context-conditional
            // grants. Subject-less/non-user subjects (server or global context
            // switches) cannot be narrowed to a player, so conservatively
            // revalidate everyone online — otherwise flipping a context-
            // conditional permission back would only be picked up by the next
            // unrelated recheck, making revocation latency unbounded and
            // breaking the "immediate" declaration of LuckPermsMount.
            subscribe(ContextUpdateEvent::class.java) { event ->
                applyRecheckScope(adapter, permissions, recheckScopeFor(uuidOfSubject(event.subject)))
            }
        } catch (t: Throwable) {
            close()
            throw t
        }
    }

    override fun close() {
        val open = synchronized(subscriptions) {
            val copy = subscriptions.toList()
            subscriptions.clear()
            copy
        }
        for (subscription in open) {
            try {
                subscription.close()
            } catch (t: Throwable) {
                logger.debug("Failed to close LuckPerms subscription", t)
            }
        }
    }

    // ------------------------------------------------------------------

    private fun <T : LuckPermsEvent> subscribe(type: Class<T>, handler: (T) -> Unit) {
        val subscription = luckPerms.eventBus.subscribe(type, handler)
        synchronized(subscriptions) { subscriptions.add(subscription) }
    }

    private fun onUserChange(uuid: UUID) {
        applyRecheckScope(adapter, permissions, recheckScopeFor(uuid))
    }

    private fun onNodeMutate(event: NodeMutateEvent) {
        applyRecheckScope(adapter, permissions, recheckScopeFor(uuidOfHolder(event.target.identifier)))
    }

    /** User holders carry their UUID string as the identifier name. */
    private fun uuidOfHolder(identifier: PermissionHolder.Identifier): UUID? {
        if (identifier.type != PermissionHolder.Identifier.USER_TYPE) return null
        return try {
            UUID.fromString(identifier.name)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun uuidOfSubject(subject: Any?): UUID? = when (subject) {
        null -> null
        is UUID -> subject
        is User -> subject.uniqueId
        is Player -> subject.gameProfile.id
        else -> null
    }
}
