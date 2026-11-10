package dev.fancycrests.command

import dev.fancycrests.api.PlayerTitleState
import dev.fancycrests.api.TitleId
import dev.fancycrests.core.event.OperationId
import dev.fancycrests.core.event.TitleChangeEventPre
import dev.fancycrests.core.event.TitleOperationType
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The feedback bookkeeping maps must never grow without bound: grant/revoke
 * accept arbitrary offline UUIDs, and recheck pre-events can be cancelled or
 * fail their write, leaving entries behind forever.
 */
class PlayerFeedbackBoundedMapsTest {

    @Test
    fun `bounded insert caps table growth`() {
        val map = ConcurrentHashMap<String, Long>()
        repeat(PlayerFeedback.MAP_CAPACITY * 2 + 100) { index ->
            PlayerFeedback.insertBounded(map, "key-$index", index.toLong())
        }
        assertTrue(map.size <= PlayerFeedback.MAP_CAPACITY, "map must stay bounded, has ${map.size}")
    }

    @Test
    fun `pending recheck removals stay bounded across many cancelled rechecks`() {
        val uuid = UUID.randomUUID()
        val current = PlayerTitleState(uuid, listOf(TitleId("vip")), emptyList(), null, 0)
        val candidate = PlayerTitleState(uuid, emptyList(), emptyList(), null, 0)
        // Simulate rechecks whose post event never arrives (cancelled or
        // write failure): every pre entry would linger forever unbounded.
        repeat(PlayerFeedback.MAP_CAPACITY * 2 + 100) { index ->
            PlayerFeedback.onPre(
                TitleChangeEventPre(
                    uuid = uuid,
                    operationId = OperationId("op-$index-${UUID.randomUUID()}"),
                    type = TitleOperationType.RECHECK,
                    current = current,
                    candidate = candidate,
                    reason = "test",
                ),
            )
        }
        assertTrue(
            PlayerFeedback.pendingRecheckRemovals.size <= PlayerFeedback.MAP_CAPACITY,
            "pending map must stay bounded, has ${PlayerFeedback.pendingRecheckRemovals.size}",
        )
    }
}
