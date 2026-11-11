package dev.fancycrests.command

import dev.fancycrests.api.TitleId
import dev.fancycrests.core.CoreServices
import dev.fancycrests.core.event.TitleChangeEventPre
import dev.fancycrests.core.event.TitleOperationType
import java.util.concurrent.ConcurrentHashMap
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Player-side feedback bridge (P3 minimal feedback):
 *  - when a recheck unequips titles (lost eligibility, config reload,
 *    ownership revoked, login recheck), the affected player is told which
 *    titles were taken off ("lost eligibility -> unequipped X");
 *  - every committed selection/ownership operation is logged with the
 *    player UUID; identical (uuid, type) log lines are rate-limited so a
 *    burst of operations cannot flood the log (plan chapter 6 "输入校验").
 *
 * Attached once from [FancyCrestsCommands.register]; listeners run on the
 * main thread via the event bus. The unequip diff is captured from the
 * pre-commit candidate (before persistence) and only messaged after the
 * post-commit event confirms the write.
 */
object PlayerFeedback {
    private val logger: Logger = LoggerFactory.getLogger("FancyCrests/AdminOps")

    /** Minimum interval between identical (uuid, type) log entries. */
    private const val LOG_INTERVAL_MS = 5_000L

    /**
     * Hard cap for both bookkeeping maps. grant/revoke accept arbitrary
     * offline UUIDs, so the tables have no natural upper bound from the
     * online player count. Internal for tests.
     */
    internal const val MAP_CAPACITY = 4_096

    /**
     * Inserts into a bounded map. Strategy: when the cap is reached the whole
     * table is dropped. That is the simplest reliable policy (no insertion
     * order tracking, no second map of keys) and both maps only hold
     * best-effort cosmetics — the cost of a clear is one throttle window
     * that may log slightly more and at most one lost recheck notice. The
     * check-then-insert pair can overshoot by a few entries under concurrent
     * writers; the map stays bounded either way. Internal for tests.
     */
    internal fun <K, V> insertBounded(map: ConcurrentHashMap<K, V>, key: K, value: V) {
        if (map.size >= MAP_CAPACITY) map.clear()
        map[key] = value
    }

    @Volatile
    private var currentCore: CoreServices? = null

    /**
     * Bus instance the forwarders are attached to. Each CoreServices.start()
     * builds a new event bus, so the forwarders must re-attach per server
     * start; comparing bus identity makes [wire] idempotent within one bus
     * lifetime no matter how often [attach] runs.
     */
    @Volatile
    private var wiredBus: dev.fancycrests.core.event.TitleEventBus? = null

    /** Guards the one-time Fabric-side SERVER_STARTED registration. */
    @Volatile
    private var lifecycleHookRegistered = false

    /** opId -> titles removed by that pending recheck. Internal for tests. */
    internal val pendingRecheckRemovals = ConcurrentHashMap<String, List<Pair<TitleId, dev.fancycrests.api.TitleSlot>>>()

    /** Internal for tests. */
    internal val lastLoggedAt = ConcurrentHashMap<String, Long>()

    /**
     * Connects this bridge to [core]. Safe to call repeatedly.
     *
     * The Fabric-side [ServerLifecycleEvents.SERVER_STARTED] hook is
     * registered exactly once per class load and forwards to whatever core
     * the module-level holder currently references (a restart builds a new
     * core-internal event bus; the old bus and its listeners are discarded
     * together). Previously every attach on a not-yet-started core added
     * another SERVER_STARTED listener, so Fabric-side listeners accumulated
     * across restarts. The attach-on-a-started-core path is unchanged: the
     * forwarders are wired immediately.
     */
    fun attach(core: CoreServices) {
        currentCore = core
        if (core.isStarted) {
            wire(core)
        }
        if (!lifecycleHookRegistered) {
            lifecycleHookRegistered = true
            ServerLifecycleEvents.SERVER_STARTED.register { currentCore?.let(::wire) }
        }
    }

    private fun wire(core: CoreServices) {
        val bus = core.eventBus
        if (wiredBus === bus) return
        wiredBus = bus
        bus.addPreListener { event -> onPre(event) }
        bus.addPostListener { event ->
            // Resolve the core at dispatch time (holder), not capture time,
            // so a stale bus never forwards into a replaced core.
            currentCore?.let { onPost(event, it) }
        }
    }

    /** Internal for tests. */
    internal fun onPre(event: TitleChangeEventPre) {
        if (event.type != TitleOperationType.RECHECK) return
        val removedPrefixes = event.current.prefixes - event.candidate.prefixes.toSet()
        val removedSuffixes = event.current.suffixes - event.candidate.suffixes.toSet()
        if (removedPrefixes.isEmpty() && removedSuffixes.isEmpty()) return
        val removed = removedPrefixes.map { it to dev.fancycrests.api.TitleSlot.PREFIX } +
            removedSuffixes.map { it to dev.fancycrests.api.TitleSlot.SUFFIX }
        insertBounded(pendingRecheckRemovals, event.operationId.value, removed)
    }

    private fun onPost(event: dev.fancycrests.core.event.TitleChangeEventPost, core: CoreServices) {
        if (event.type == TitleOperationType.RECHECK) {
            val removed = pendingRecheckRemovals.remove(event.operationId.value).orEmpty()
            if (removed.isEmpty()) return // nothing changed; nothing to report
            val names = removed.mapNotNull { (id, _) ->
                core.catalogService.get(id)?.displayName?.string ?: id.value
            }
            if (names.isNotEmpty()) {
                core.serverAdapter.sendSystemMessage(
                    event.uuid,
                    Component.literal("Lost eligibility; unequipped: ${names.joinToString(", ")}.")
                        .withStyle(ChatFormatting.YELLOW),
                )
            }
            throttledLog(
                event.uuid,
                "recheck-unequip",
                "uuid=${event.uuid} unequipped=${names.joinToString(",")} reason=${event.reason}",
            )
            return
        }
        throttledLog(
            event.uuid,
            event.type.name.lowercase(),
            "uuid=${event.uuid} op=${event.type} revision=${event.oldRevision}->${event.newRevision} reason=${event.reason}",
        )
    }

    /** Logs [line] at INFO unless the same key fired within the throttle window. Internal for tests. */
    internal fun throttledLog(uuid: java.util.UUID, key: String, line: String) {
        val mapKey = "$uuid:$key"
        val now = System.currentTimeMillis()
        val last = lastLoggedAt[mapKey] ?: 0L
        if (now - last < LOG_INTERVAL_MS) return
        insertBounded(lastLoggedAt, mapKey, now)
        logger.info("$line (op log)")
    }
}
