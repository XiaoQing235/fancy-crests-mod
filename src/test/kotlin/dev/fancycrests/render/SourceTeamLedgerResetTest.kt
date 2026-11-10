package dev.fancycrests.render

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Semantics of [SourceTeamLedger.reset] (render batch B1): after an
 * observer's client rebuilt its world view (respawn), the connection's
 * ledger must behave exactly like a freshly attached one - no team
 * knowledge, no membership, no reservations - while a re-seed from the
 * authoritative scoreboard restores the full mirror.
 */
class SourceTeamLedgerResetTest {

    private fun props(tag: String): SourceTeamProps {
        val base = SourceTeamProps.neutral(tag)
        return base.copy(displayName = net.minecraft.network.chat.Component.literal(tag))
    }

    private fun seededLedger(): SourceTeamLedger = SourceTeamLedger().apply {
        teamCreated("red", props("red"), listOf("Alpha", "Beta"))
        teamCreated("blue", props("blue"), listOf("Gamma"))
    }

    @Test
    fun `reset forgets teams, membership and reservations`() {
        val ledger = seededLedger()
        assertEquals(2, ledger.teamCount())

        ledger.reset()

        assertEquals(0, ledger.teamCount())
        assertEquals(emptySet<String>(), ledger.teamNames)
        assertNull(ledger.teamOf("Alpha"))
        assertNull(ledger.teamOf("Beta"))
        assertNull(ledger.teamOf("Gamma"))
        assertFalse(ledger.isReserved("red"))
        assertTrue(ledger.membersOf("red").isEmpty())
    }

    @Test
    fun `reseed after reset restores the authoritative mirror`() {
        val ledger = seededLedger()
        ledger.reset()

        // Re-seed exactly as RenderService.seedLedger does after a respawn.
        ledger.teamCreated("red", props("red"), listOf("Alpha"))
        ledger.teamCreated("blue", props("blue"), listOf("Beta", "Gamma"))

        assertEquals(2, ledger.teamCount())
        assertEquals("red" to props("red"), ledger.teamOf("Alpha"))
        assertEquals("blue" to props("blue"), ledger.teamOf("Gamma"))
        assertTrue(ledger.isReserved("red"))
        assertTrue(ledger.isReserved("blue"))
    }

    @Test
    fun `ledger stays usable after reset for incremental updates`() {
        val ledger = seededLedger()
        ledger.reset()

        // A team create for an unknown team followed by member joins is the
        // normal packet stream after the client world was rebuilt.
        ledger.teamPropsChanged("solo", props("solo"))
        assertNull(ledger.teamOf("Alpha"), "property change without create must not invent membership")

        ledger.teamCreated("solo", props("solo"), emptyList())
        ledger.memberJoined("solo", "Alpha")
        assertEquals("solo", ledger.teamOf("Alpha")?.first)
    }
}
