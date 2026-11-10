package dev.fancycrests.render

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Diagnostics counters for the head render channel (spec: diagnostics output
 * must distinguish target counts, target-side virtual entities, tracking
 * relations, per-connection virtual teams, sent entities, bundle processing
 * and the most recent error, rate-limited).
 *
 * Counters are plain atomics; gauges are maintained by [RenderService] as it
 * allocates/frees state. Errors are logged (full stack trace) through slf4j,
 * rate-limited to one log line per window to avoid per-tick log spam;
 * [lastError] still records the most recent message for every error.
 */
object RenderDiagnostics {

    private val LOGGER: Logger = LoggerFactory.getLogger("FancyCrests/Render")

    /** Live targets (players with a published snapshot). */
    val targets = AtomicInteger(0)

    /** Target-side virtual label entities currently allocated. */
    val virtualEntities = AtomicInteger(0)

    /** Live observer->target tracking relations with labels sent. */
    val trackingRelations = AtomicInteger(0)

    /** Virtual teams currently live across all connections. */
    val virtualTeams = AtomicInteger(0)

    /** Cumulative virtual entities actually sent to clients. */
    val entitiesSent = AtomicLong(0)

    /** Cumulative outbound bundles inspected (recursively) by the transformer. */
    val bundlesProcessed = AtomicLong(0)

    /** Cumulative source team packets rebuilt (member entries removed). */
    val teamPacketsRewritten = AtomicLong(0)

    /** Cumulative passenger packets rebuilt (label ids appended). */
    val passengerPacketsRewritten = AtomicLong(0)

    /** Cumulative player packets dropped entirely (taken-over member ops). */
    val teamPacketsDropped = AtomicLong(0)

    @Volatile
    var lastError: String? = null
        private set

    private val errorLock = Any()
    private var lastErrorNanos = 0L
    private val ERROR_WINDOW_NANOS = 5_000_000_000L // 5s

    /**
     * Records a render pipeline error: full stack trace via a rate-limited
     * LOGGER.warn (one line per 5s window, so a per-tick failure cannot
     * flood the log) while [lastError] tracks the newest message for every
     * occurrence, not just the logged ones. Errors (`java.lang.Error`) are
     * not handled here at all: call sites catch `Exception` so that VM-level
     * failures propagate instead of being swallowed as render noise.
     */
    fun recordError(where: String, error: Throwable) {
        val now = System.nanoTime()
        val logAllowed = synchronized(errorLock) {
            if (now - lastErrorNanos >= ERROR_WINDOW_NANOS) {
                lastErrorNanos = now
                true
            } else {
                false
            }
        }
        lastError = "$where: $error"
        if (logAllowed) {
            LOGGER.warn("Render pipeline error in {}", where, error)
        }
    }

    /** Point-in-time snapshot for diagnostics commands. */
    fun snapshot(): Map<String, Long> = linkedMapOf(
        "targets" to targets.get().toLong(),
        "virtualEntities" to virtualEntities.get().toLong(),
        "trackingRelations" to trackingRelations.get().toLong(),
        "virtualTeams" to virtualTeams.get().toLong(),
        "entitiesSent" to entitiesSent.get(),
        "bundlesProcessed" to bundlesProcessed.get(),
        "teamPacketsRewritten" to teamPacketsRewritten.get(),
        "teamPacketsDropped" to teamPacketsDropped.get(),
        "passengerPacketsRewritten" to passengerPacketsRewritten.get(),
    )
}
