package dev.fancycrests.render

import net.minecraft.server.network.ServerGamePacketListenerImpl
import java.util.UUID

/**
 * One entry of [RenderService]'s pending-resend set: the observer connection
 * whose client missed part of a label update, and the target whose content is
 * stale client-side. Coarser than observer×target×layer on purpose: a failed
 * send usually invalidates several layers of the same (observer, target) pair
 * at once, and one retry pass re-runs the full diff for the pair anyway.
 */
internal data class PendingResend(
    val listener: ServerGamePacketListenerImpl,
    val targetUuid: UUID,
)

/**
 * Pure admission and batching policy for the bounded pending-resend set
 * (audit leftover L1). Kept free of any Minecraft/service state so the
 * eviction and per-tick cap rules are unit-testable in isolation.
 *
 * Semantics:
 *  - the queue is insertion-ordered (oldest first) and de-duplicated;
 *    re-marking an already pending key keeps its original position, so a
 *    repeatedly failing pair cannot starve newer entries;
 *  - the queue is bounded: admitting beyond [MAX_ENTRIES] evicts the oldest
 *    entries (a dropped entry is not fatal — the next content change for
 *    that pair re-publishes through the normal path);
 *  - [takeRetryBatch] removes and returns at most [MAX_RESENDS_PER_TICK]
 *    entries so one retry sweep cannot stall the tick; entries whose retry
 *    could not run for a transient reason (target offline, entity id being
 *    refreshed) are re-admitted by the caller.
 */
internal object ResendQueuePolicy {

    /** Hard bound on queued (observer, target) pairs. */
    const val MAX_ENTRIES = 256

    /** Upper bound on retries per end-tick sweep. */
    const val MAX_RESENDS_PER_TICK = 16

    /**
     * Adds [key] to the insertion-ordered [queue] unless already present.
     * Grows the queue only up to [maxEntries]; overflow evicts from the
     * front (oldest). Returns true when the key was newly added (including
     * the pathological case where it was added and immediately evicted,
     * i.e. `maxEntries <= 0`).
     */
    fun <T> admit(queue: LinkedHashSet<T>, key: T, maxEntries: Int = MAX_ENTRIES): Boolean {
        if (!queue.add(key)) return false
        while (queue.size > maxEntries) {
            val oldest = queue.first()
            queue.remove(oldest)
            if (oldest == key) break // maxEntries <= 0: nothing is retained
        }
        return true
    }

    /**
     * Removes and returns up to [limit] entries in FIFO order (empty list
     * when the queue is empty). Removing on take keeps the queue bounded by
     * live failures only; callers re-admit entries whose retry had to be
     * deferred or failed again.
     */
    fun <T> takeRetryBatch(queue: LinkedHashSet<T>, limit: Int = MAX_RESENDS_PER_TICK): List<T> {
        if (queue.isEmpty() || limit <= 0) return emptyList()
        val batch = ArrayList<T>(minOf(limit, queue.size))
        val iterator = queue.iterator()
        while (iterator.hasNext() && batch.size < limit) {
            batch.add(iterator.next())
            iterator.remove()
        }
        return batch
    }
}
