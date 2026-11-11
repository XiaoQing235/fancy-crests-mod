package dev.fancycrests.render

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pure admission/batching rules of the bounded pending-resend set (audit
 * leftover L1): de-duplication with stable position, oldest-first eviction
 * at capacity, and the per-tick retry cap.
 */
class ResendQueuePolicyTest {

    @Test
    fun `admit adds new keys and reports duplicates`() {
        val queue = linkedSetOf("a", "b")
        assertTrue(ResendQueuePolicy.admit(queue, "c"))
        assertEquals(listOf("a", "b", "c"), queue.toList())
        assertFalse(ResendQueuePolicy.admit(queue, "a"), "re-marking a pending key must report false")
        assertEquals(listOf("a", "b", "c"), queue.toList(), "re-marking must not duplicate the entry")
    }

    @Test
    fun `re-marking keeps the original position`() {
        val queue = linkedSetOf("a", "b")
        ResendQueuePolicy.admit(queue, "c")
        // "a" fails again: it must not jump ahead of "b"/"c", or a
        // repeatedly failing pair would starve newer entries.
        ResendQueuePolicy.admit(queue, "a")
        assertEquals(listOf("a", "b", "c"), queue.toList())
    }

    @Test
    fun `overflow evicts the oldest entries first`() {
        val queue = linkedSetOf<String>()
        for (i in 0 until ResendQueuePolicy.MAX_ENTRIES) {
            ResendQueuePolicy.admit(queue, "entry-$i")
        }
        assertEquals(ResendQueuePolicy.MAX_ENTRIES, queue.size)

        assertTrue(ResendQueuePolicy.admit(queue, "newest"))
        assertEquals(ResendQueuePolicy.MAX_ENTRIES, queue.size, "capacity must hold")
        assertEquals("entry-1", queue.first(), "the oldest entry was evicted")
        assertTrue(queue.contains("newest"))
        assertFalse(queue.contains("entry-0"))
    }

    @Test
    fun `zero capacity retains nothing`() {
        val queue = linkedSetOf<String>()
        assertTrue(ResendQueuePolicy.admit(queue, "x", maxEntries = 0))
        assertTrue(queue.isEmpty(), "an entry admitted into a zero-capacity queue is immediately evicted")
    }

    @Test
    fun `takeRetryBatch returns and removes up to the limit in FIFO order`() {
        val queue = linkedSetOf("a", "b", "c", "d", "e")
        val batch = ResendQueuePolicy.takeRetryBatch(queue, limit = 3)
        assertEquals(listOf("a", "b", "c"), batch)
        assertEquals(listOf("d", "e"), queue.toList(), "the remainder stays queued")
    }

    @Test
    fun `takeRetryBatch drains a short queue completely`() {
        val queue = linkedSetOf("only")
        assertEquals(listOf("only"), ResendQueuePolicy.takeRetryBatch(queue, limit = 16))
        assertTrue(queue.isEmpty())
    }

    @Test
    fun `takeRetryBatch on an empty queue is empty`() {
        assertTrue(ResendQueuePolicy.takeRetryBatch(linkedSetOf<String>()).isEmpty())
    }

    @Test
    fun `a non-positive limit takes nothing`() {
        val queue = linkedSetOf("a", "b")
        assertTrue(ResendQueuePolicy.takeRetryBatch(queue, limit = 0).isEmpty())
        assertEquals(2, queue.size)
    }

    @Test
    fun `defaults bound the queue and the tick batch`() {
        val queue = linkedSetOf<String>()
        for (i in 0 until ResendQueuePolicy.MAX_ENTRIES + 50) {
            ResendQueuePolicy.admit(queue, "entry-$i")
        }
        assertEquals(ResendQueuePolicy.MAX_ENTRIES, queue.size)
        assertEquals(ResendQueuePolicy.MAX_RESENDS_PER_TICK, ResendQueuePolicy.takeRetryBatch(queue).size)
    }
}
