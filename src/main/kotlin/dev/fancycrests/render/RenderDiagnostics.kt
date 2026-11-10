package dev.fancycrests.render

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Diagnostics counters for the head render channel (spec: diagnostics output
 * must distinguish target counts, target-side virtual entities, tracking
 * relations, per-connection virtual teams, sent entities, bundle processing
 * and the most recent error, rate-limited).
 *
 * Counters are plain atomics; gauges are maintained by [RenderService] as it
 * allocates/frees state. Errors are rate-limited to one record per window to
 * avoid per-tick log spam.
 */
object RenderDiagnostics {

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

    /** Records a render pipeline error; rate-limited per wall-clock window. */
    fun recordError(where: String, error: Throwable) {
        synchronized(errorLock) {
            val now = System.nanoTime()
            if (now - lastErrorNanos < ERROR_WINDOW_NANOS) return
            lastErrorNanos = now
            lastError = "$where: $error"
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
