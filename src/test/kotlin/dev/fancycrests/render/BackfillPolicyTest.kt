package dev.fancycrests.render

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
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

    // ===== backfillObservers: cheap gates + real tracking relation =====

    private data class PlayerLike(val name: String)

    private fun candidate(player: PlayerLike, uuid: UUID, level: Any) =
        BackfillCandidate(player, uuid, level)

    @Test
    fun `tracked same dimension observer receives the backfill`() {
        val observer = PlayerLike("observer")
        val kept = BackfillPolicy.backfillObservers(
            targetUuid, overworld,
            listOf(candidate(observer, observerUuid, overworld)),
            isTracked = { true },
        )
        assertEquals(listOf(observer), kept)
    }

    @Test
    fun `same dimension observer that does not track the target is filtered out`() {
        // Audit #1: backfilling a pair without a vanilla tracking relation
        // left the label frozen at the backfill position once the untracked
        // target moved (no position sync drives it).
        val kept = BackfillPolicy.backfillObservers(
            targetUuid, overworld,
            listOf(candidate(PlayerLike("observer"), observerUuid, overworld)),
            isTracked = { false },
        )
        assertTrue(kept.isEmpty(), "untracked observers must not receive label entities")
    }

    @Test
    fun `untracked observers are dropped while tracked ones are kept in one pass`() {
        val a = PlayerLike("a")
        val b = PlayerLike("b")
        val c = PlayerLike("c")
        val kept = BackfillPolicy.backfillObservers(
            targetUuid, overworld,
            listOf(
                candidate(a, UUID.randomUUID(), overworld),
                candidate(b, UUID.randomUUID(), overworld),
                candidate(c, UUID.randomUUID(), nether),
            ),
            isTracked = { it !== b }, // a tracked, b not tracked, c never probed
        )
        assertEquals(listOf(a), kept)
    }

    @Test
    fun `the target itself is excluded even when the predicate claims tracking`() {
        val kept = BackfillPolicy.backfillObservers(
            targetUuid, overworld,
            listOf(candidate(PlayerLike("self"), targetUuid, overworld)),
            isTracked = { true },
        )
        assertTrue(kept.isEmpty())
    }

    @Test
    fun `candidate order is preserved among kept observers`() {
        val first = PlayerLike("first")
        val second = PlayerLike("second")
        val kept = BackfillPolicy.backfillObservers(
            targetUuid, overworld,
            listOf(
                candidate(second, UUID.randomUUID(), overworld),
                candidate(first, UUID.randomUUID(), overworld),
            ),
            isTracked = { true },
        )
        assertEquals(listOf(second, first), kept)
    }

    @Test
    fun `tracking predicate is not consulted for candidates failing the cheap gates`() {
        // The tracking query scans the dimension's tracked-entity map per
        // observer; it must only run for candidates that passed the
        // self/dimension gates.
        val probed = mutableListOf<UUID>()
        BackfillPolicy.backfillObservers(
            targetUuid, overworld,
            listOf(
                candidate(PlayerLike("self"), targetUuid, overworld),       // self: cheap-excluded
                candidate(PlayerLike("nether"), UUID.randomUUID(), nether), // foreign dim: cheap-excluded
                candidate(PlayerLike("tracked"), observerUuid, overworld),  // probed
            ),
            isTracked = {
                probed.add(observerUuid)
                true
            },
        )
        assertEquals(listOf(observerUuid), probed, "only the same-dimension non-self candidate is probed")
    }
}
