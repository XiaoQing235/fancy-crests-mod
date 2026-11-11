package dev.fancycrests.core.permission

import dev.fancycrests.api.PermissionService
import dev.fancycrests.api.PermissionVerdict
import dev.fancycrests.api.Permissions
import dev.fancycrests.core.ServerAdapter
import dev.fancycrests.core.config.NodeGrants
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * One pluggable source of *explicit* per-player verdicts. Backends only answer
 * with explicit ALLOW/DENY; UNDEFINED means "no opinion" and the chain moves on.
 *
 * The built-in implementation ([BuiltInBackend]) reads the allow/deny lists
 * from the config file. A LuckPerms adapter can be added later without
 * touching the evaluation order.
 */
interface PermissionBackend {
    /** Diagnostic name shown by /fancycrests diagnose. */
    val name: String

    /**
     * Explicit verdict for [uuid] on [node], or UNDEFINED when this backend
     * has no explicit entry. Never applies default policies.
     */
    fun explicitVerdict(uuid: UUID, node: String): PermissionVerdict

    /**
     * False while this backend cannot answer reliably for [uuid] yet — e.g.
     * an external provider still loading that user's data. Callers must
     * DEFER revalidation instead of treating the interim UNDEFINED verdicts
     * as a deny: unequipping and persisting titles on a "not loaded yet"
     * answer would never be rolled back. Defaults to always ready.
     */
    fun isReady(uuid: UUID): Boolean = true

    /**
     * Ask this backend to make itself ready for [uuid] (e.g. trigger the
     * external provider's user load). Invoked exactly when a revalidation
     * is deferred because [isReady] returned false — the permission checks
     * that would otherwise have kicked off a lazy load never ran. The
     * backend's own load-completion event then drives the real recheck.
     */
    fun warmUp(uuid: UUID) {}
}

/** How a node is decided when no backend gave an explicit verdict. */
enum class DefaultPolicyType {
    /** Available to every player. */
    ALLOW_ALL,

    /** Available to players at or above a vanilla operator level. */
    OP_LEVEL,

    /** Denied unless explicitly allowed. */
    DENY_ALL,
}

/** Documented default policy of one permission node (plan chapter 1). */
data class NodeDefaultPolicy(
    val node: String,
    val type: DefaultPolicyType,
    /** Only meaningful for [DefaultPolicyType.OP_LEVEL]. */
    val opLevel: Int = 0,
)

/**
 * Default policies of the standard nodes, per the plan:
 *
 *  - `fancycrests.menu`                → ALLOW_ALL (normal players can open the menu)
 *  - `fancycrests.equip`               → ALLOW_ALL (equip/unequip own titles)
 *  - `fancycrests.nickname.set`        → DENY_ALL (must be explicitly granted)
 *  - `fancycrests.nickname.bypass_reserved` → DENY_ALL (admin-granted only)
 *  - `fancycrests.admin.*`             → OP_LEVEL 2 (vanilla gamemaster/admin)
 *
 * An explicit deny can never be overridden by the operator fallback: the
 * evaluation order in [PermissionManager] checks deny before allow before
 * default. Custom (unknown) nodes default to DENY_ALL — UNDEFINED is never
 * returned for a node that has a policy entry, and all standard nodes do.
 */
object NodeDefaults {
    const val ADMIN_OP_LEVEL = 2

    private val policies: Map<String, NodeDefaultPolicy> = buildMap {
        fun node(name: String, type: DefaultPolicyType, opLevel: Int = 0) {
            put(name, NodeDefaultPolicy(name, type, opLevel))
        }
        node(Permissions.MENU, DefaultPolicyType.ALLOW_ALL)
        node(Permissions.EQUIP, DefaultPolicyType.ALLOW_ALL)
        node(Permissions.NICKNAME_SET, DefaultPolicyType.DENY_ALL)
        node(Permissions.NICKNAME_BYPASS_RESERVED, DefaultPolicyType.DENY_ALL)
        node(Permissions.GRANT, DefaultPolicyType.OP_LEVEL, ADMIN_OP_LEVEL)
        node(Permissions.REVOKE, DefaultPolicyType.OP_LEVEL, ADMIN_OP_LEVEL)
        node(Permissions.INSPECT, DefaultPolicyType.OP_LEVEL, ADMIN_OP_LEVEL)
        node(Permissions.PREVIEW, DefaultPolicyType.OP_LEVEL, ADMIN_OP_LEVEL)
        node(Permissions.RELOAD, DefaultPolicyType.OP_LEVEL, ADMIN_OP_LEVEL)
        node(Permissions.VALIDATE, DefaultPolicyType.OP_LEVEL, ADMIN_OP_LEVEL)
        node(Permissions.DIAGNOSE, DefaultPolicyType.OP_LEVEL, ADMIN_OP_LEVEL)
    }

    fun policyFor(node: String): NodeDefaultPolicy? = policies[node]

    /** True when [node] is one of the standard nodes with a documented default. */
    fun isStandardNode(node: String): Boolean = policies.containsKey(node)
}

/**
 * Built-in backend: explicit per-UUID allow/deny lists loaded from
 * `permissions.nodes` in the config file. Reload replaces the whole table.
 */
class BuiltInBackend : PermissionBackend {
    override val name: String = "builtin"

    @Volatile
    private var grants: Map<UUID, NodeGrants> = emptyMap()

    fun reload(nodes: Map<UUID, NodeGrants>) {
        grants = nodes
    }

    override fun explicitVerdict(uuid: UUID, node: String): PermissionVerdict {
        val entry = grants[uuid] ?: return PermissionVerdict.UNDEFINED
        if (entry.deny.any { it.matchesNode(node) }) return PermissionVerdict.DENY
        if (entry.allow.any { it.matchesNode(node) }) return PermissionVerdict.ALLOW
        return PermissionVerdict.UNDEFINED
    }

    /**
     * Node matching: exact match, or a trailing wildcard entry ("fancycrests.admin.*").
     */
    private fun String.matchesNode(node: String): Boolean {
        if (this == node) return true
        if (endsWith(".*")) {
            val prefix = substring(0, length - 1) // keep the trailing dot
            return node.startsWith(prefix)
        }
        return false
    }
}

/**
 * Permission resolution implementing [PermissionService].
 *
 * Evaluation order per node (plan chapter 1): explicit deny → explicit allow →
 * documented node default policy. Every backend is consulted for an explicit
 * verdict; DENY from any backend in the chain beats ALLOW from any other
 * backend (and an explicit allow beats the node default).
 *
 * UNDEFINED is only returned for non-standard nodes without any explicit entry
 * and without a default policy — which cannot happen for the standard nodes.
 */
class PermissionManager(
    private val adapter: ServerAdapter,
    backends: List<PermissionBackend> = emptyList(),
) : PermissionService {

    private val backendChain: List<PermissionBackend> = backends.toList()

    /** Extra backends can be registered later (LuckPerms adapter, P4). */
    @Volatile
    private var dynamicBackends: List<PermissionBackend> = emptyList()

    /** UUIDs whose permissions changed and whose eligibility should be rechecked. */
    private val dirtyPlayers = ConcurrentHashMap.newKeySet<UUID>()

    /** Set by CoreServices: recheck callback invoked on the main thread. */
    @Volatile
    var onPermissionChanged: ((UUID) -> Unit)? = null

    fun registerBackend(backend: PermissionBackend) {
        dynamicBackends = dynamicBackends + backend
    }

    fun removeBackend(backend: PermissionBackend) {
        dynamicBackends = dynamicBackends - backend
    }

    /** Backend chain for diagnostics, in consultation order. */
    fun backendNames(): List<String> =
        (backendChain + dynamicBackends).map { it.name }

    /**
     * True when every backend can answer reliably for [uuid] right now.
     * Revalidation must be deferred (not run) while this is false — see
     * [PermissionBackend.isReady].
     */
    fun isReady(uuid: UUID): Boolean =
        (backendChain + dynamicBackends).all { it.isReady(uuid) }

    /**
     * Trigger every backend's readiness path for [uuid]; called when a
     * revalidation is deferred so lazy user loads still start.
     */
    fun warmUp(uuid: UUID) {
        for (backend in backendChain + dynamicBackends) {
            runCatching { backend.warmUp(uuid) }
        }
    }

    override fun check(playerUuid: UUID, node: String): PermissionVerdict {
        // 1. explicit verdicts. A deny from ANY backend wins over an allow from
        // any other backend, so the documented "explicit deny is final" rule
        // holds across the whole chain instead of only within one backend.
        var allowed = false
        for (backend in backendChain + dynamicBackends) {
            when (backend.explicitVerdict(playerUuid, node)) {
                PermissionVerdict.DENY -> return PermissionVerdict.DENY
                PermissionVerdict.ALLOW -> allowed = true
                PermissionVerdict.UNDEFINED -> Unit
            }
        }
        if (allowed) return PermissionVerdict.ALLOW
        // 2. documented default policy
        return when (val policy = NodeDefaults.policyFor(node)) {
            null -> PermissionVerdict.UNDEFINED
            is NodeDefaultPolicy -> when (policy.type) {
                DefaultPolicyType.ALLOW_ALL -> PermissionVerdict.ALLOW
                DefaultPolicyType.DENY_ALL -> PermissionVerdict.DENY
                DefaultPolicyType.OP_LEVEL ->
                    if (adapter.operatorLevel(playerUuid) >= policy.opLevel) {
                        PermissionVerdict.ALLOW
                    } else {
                        PermissionVerdict.DENY
                    }
            }
        }
    }

    /** Marks [uuid] for an eligibility recheck; returns true when newly marked. */
    fun markDirty(uuid: UUID): Boolean = dirtyPlayers.add(uuid)

    /** Immediately rechecks the given player (call on the main thread). */
    fun recheckNow(uuid: UUID) {
        dirtyPlayers.remove(uuid)
        onPermissionChanged?.invoke(uuid)
    }
}
