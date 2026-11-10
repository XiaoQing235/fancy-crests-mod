package dev.fancycrests.core.refresh

import dev.fancycrests.core.ServerAdapter
import dev.fancycrests.core.config.FancyCrestsConfig
import dev.fancycrests.core.resolve.SnapshotPublisher
import dev.fancycrests.core.resolve.SnapshotResolver
import java.util.TreeMap
import java.util.UUID

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
        val now = adapter.currentTick()
        val duePeriods = nextDue.entries.filter { it.value <= now }.map { it.key }
        if (duePeriods.isEmpty()) return
        for (period in duePeriods) {
            nextDue[period] = now + period
            refreshGroup(period)
        }
    }

    private fun refreshGroup(period: Int) {
        val ruleIds = rulePeriods.filterValues { it == period }.keys
        if (ruleIds.isEmpty()) return
        for (uuid in adapter.onlinePlayerUuids()) {
            val matched = resolver.matchedRule(uuid, adapter.dimensionKey(uuid)) ?: continue
            if (matched.id in ruleIds) {
                publisher.publishIfChanged(stateOf(uuid))
            }
        }
    }
}
