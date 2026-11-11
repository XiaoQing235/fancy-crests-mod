package dev.fancycrests.render

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The retro-pairing candidate filter (render batch E2): after an online
 * `headRender` enable, every online player becomes a "new target" at its
 * first publish and label pairings must be backfilled for exactly the
 * observers that could still reference the target - never the target itself,
 * never a client in another dimension. Level identity stands in for
 * `ServerLevel` (one instance per loaded dimension), compared by reference.
 */
class BackfillPolicyTest {

    private val targetUuid = UUID.randomUUID()
    private val observerUuid = UUID.randomUUID()
    private val overworld = Any()
    private val nether = Any()

    @Test
    fun `same dimension observer is a candidate`() {
        assertTrue(
            BackfillPolicy.shouldBackfill(targetUuid, overworld, observerUuid, overworld),
            "different player in the same dimension gets backfilled",
        )
    }

    @Test
    fun `the target itself is never a candidate`() {
        assertFalse(
            BackfillPolicy.shouldBackfill(targetUuid, overworld, targetUuid, overworld),
            "a player must not render their own head label",
        )
    }

    @Test
    fun `another dimension is never a candidate`() {
        assertFalse(
            BackfillPolicy.shouldBackfill(targetUuid, overworld, observerUuid, nether),
            "foreign-world clients cannot reference the target (ghost labels)",
        )
    }

    @Test
    fun `dimension equality is by identity, not structural equality`() {
        val overworldTwin = Any() // same "shape", different instance -> different dimension
        assertFalse(BackfillPolicy.shouldBackfill(targetUuid, overworld, observerUuid, overworldTwin))
        assertTrue(BackfillPolicy.shouldBackfill(targetUuid, overworldTwin, observerUuid, overworldTwin))
    }

    @Test
    fun `equal uuids across dimensions stay excluded even though self-check comes first`() {
        assertFalse(BackfillPolicy.shouldBackfill(targetUuid, overworld, targetUuid, nether))
    }
}
