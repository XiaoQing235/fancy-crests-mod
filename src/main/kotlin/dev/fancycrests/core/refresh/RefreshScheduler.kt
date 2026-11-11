package dev.fancycrests.core.refresh

import dev.fancycrests.core.ServerAdapter
import dev.fancycrests.core.config.FancyCrestsConfig
import dev.fancycrests.core.resolve.SnapshotPublisher
import dev.fancycrests.core.resolve.SnapshotResolver
import java.util.TreeMap
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Tick-driven re-resolution of dynamic second-line templates (plan chapter 4
 * "刷新策略").
 *
 * Scheduling is per refresh period: rules whose templates contain built-in
 * placeholders are grouped by their configured `refreshTicks`; when a group's
 * deadline arrives, every online player whose currently matching rule belongs
 * to that group is re-resolved, and [SnapshotPublisher.publishIfChanged]
 * publishes only when the rendered content actually changed. Static-only
 * rules are never scheduled.
 *
 * Lifecycle:
 *  - [rebuild] (server start and every successful config replacement)
 *    recomputes the groups from the current config; deadlines reset, so tasks
 *    replaced by a reload are effectively cancelled;
 *  - the schedule stays empty while no channel consumes shared snapshots
 *    (head render, chat, tab list and placeholder export are ALL disabled) —
 *    turning off the head channel alone never cancels the shared refresh
 *    while chat/tab/export still consume it;
 *  - [stop] releases the tick hook (whole mod shutting down).
 *
 * Eligibility and lifecycle changes are event-driven and never wait for a
 * refresh period; those paths publish directly through
 * [SnapshotPublisher.publish].
 */
class RefreshScheduler(
    private val adapter: ServerAdapter,
    private val configProvider: () -> FancyCrestsConfig,
    private val resolver: SnapshotResolver,
    private val stateOf: (UUID) -> dev.fancycrests.api.PlayerTitleState,
    private val publisher: SnapshotPublisher,
) {
    private val logger: Logger = LoggerFactory.getLogger("FancyCrests/Refresh")

    /** Minimum interval between error logs for the same player. */
    private val errorLogIntervalMs = 60_000L

    /** Per-player timestamp of the last logged refresh failure. */
    private val lastErrorLoggedAt = ConcurrentHashMap<UUID, Long>()

    /** rule id -> refresh period (dynamic rules only). Main-thread mutated. */
    private var rulePeriods: Map<String, Int> = emptyMap()

    /** period -> tick at which the group is due next. Main-thread mutated. */
    private val nextDue = TreeMap<Int, Long>()

    /** Deactivates the tick handler when closed; fabric events cannot unregister. */
    private val active = java.util.concurrent.atomic.AtomicBoolean(false)

    private val tickHandle: AutoCloseable = adapter.registerEndTickHandler { onTick() }

    /** Recomputes the schedule from the current configuration. Main thread. */
    fun rebuild() {
        val config = configProvider()
        val anyConsumer = config.channels.headRender || config.channels.chat ||
            config.channels.tabList || config.channels.placeholdersExport
        rulePeriods = if (anyConsumer) {
            config.sortedSecondLineRules()
                .filter { TemplateTokenizer.containsPlaceholders(it.template) }
                .associate { it.id to it.refreshTicks }
        } else {
            emptyMap()
        }
        nextDue.clear()
        val now = adapter.currentTick()
        for (period in rulePeriods.values.distinct()) {
            nextDue[period] = now + period
        }
        active.set(rulePeriods.isNotEmpty())
    }

    /** Stops all periodic work and releases the tick hook. */
    fun stop() {
        active.set(false)
        rulePeriods = emptyMap()
        nextDue.clear()
        try {
            tickHandle.close()
        } catch (t: Throwable) {
            // Closing must never break shutdown.
        }
    }

    private fun onTick() {
        if (!active.get() || rulePeriods.isEmpty()) return
        // This runs inside END_SERVER_TICK: nothing here may ever throw,
        // or a single failing refresh takes the whole server down.
        try {
            val now = adapter.currentTick()
            val duePeriods = nextDue.entries.filter { it.value <= now }.map { it.key }
            if (duePeriods.isEmpty()) return
            for (period in duePeriods) {
                nextDue[period] = now + period
                try {
                    refreshGroup(period)
                } catch (t: Throwable) {
                    logger.error("Second-line refresh group (period {} ticks) failed", period, t)
                }
            }
        } catch (t: Throwable) {
            logger.error("Second-line refresh tick failed", t)
        }
    }

    private fun refreshGroup(period: Int) {
        val ruleIds = rulePeriods.filterValues { it == period }.keys
        if (ruleIds.isEmpty()) return
        for (uuid in adapter.onlinePlayerUuids()) {
            try {
                val matched = resolver.matchedRule(uuid, adapter.dimensionKey(uuid))
                if (matched == null) {
                    // No rule matches the player's current dimension any
                    // more (audit #7): the last published second line was
                    // matched in another dimension and is stale. Re-resolve
                    // and publishIfChanged clears it (the second line
                    // becomes null). Players without a published view are
                    // skipped — there is nothing to clear and re-resolving
                    // every period would be pure busy work.
                    if (publisher.latestView(uuid)?.snapshot != null) {
                        publisher.publishIfChanged(stateOf(uuid))
                    }
                } else if (matched.id in ruleIds) {
                    publisher.publishIfChanged(stateOf(uuid))
                }
            } catch (t: Throwable) {
                // One player's failure (e.g. a LuckPerms backend throwing in
                // matchedRule) must not abort the refresh of the others, nor
                // flood the log every tick.
                logPlayerFailure(uuid, t)
            }
        }
    }

    /** Logs at most once per [errorLogIntervalMs] for the same player. */
    private fun logPlayerFailure(uuid: UUID, t: Throwable) {
        val now = System.currentTimeMillis()
        val last = lastErrorLoggedAt[uuid] ?: 0L
        if (now - last < errorLogIntervalMs) return
        lastErrorLoggedAt[uuid] = now
        logger.error("Second-line refresh failed for player {}; other players are unaffected", uuid, t)
    }
}
