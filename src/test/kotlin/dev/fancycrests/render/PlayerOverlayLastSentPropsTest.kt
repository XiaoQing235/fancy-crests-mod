package dev.fancycrests.render

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The [PlayerOverlay.matchesLastSent] short-circuit (render batch B5): a
 * property follow-up packet may be skipped only when the overlay's create
 * packet was produced AND the effective source (team name + structural
 * SourceTeamProps) is byte-for-byte what the last create/modify embedded.
 * The comparison feeds the transformer's skip decision, so a false
 * "unchanged" would permanently hide property changes; a false "changed"
 * only costs one redundant packet.
 */
class PlayerOverlayLastSentPropsTest {

    private fun overlay(): PlayerOverlay = PlayerOverlay(UUID.randomUUID(), "fcteam", "Alpha", teamSent = false)

    private fun props(tag: String): SourceTeamProps {
        val base = SourceTeamProps.neutral(tag)
        return base.copy(displayName = net.minecraft.network.chat.Component.literal(tag))
    }

    @Test
    fun `never skips before the create packet was produced`() {
        val overlay = overlay()
        assertFalse(overlay.matchesLastSent(null, null), "teamSent=false must always force a create")

        overlay.teamSent = true
        assertTrue(overlay.matchesLastSent(null, null), "teamless created with neutral props matches (null, null)")
    }

    @Test
    fun `same team and structurally equal props skip`() {
        val overlay = overlay()
        overlay.teamSent = true
        overlay.lastSentSourceTeam = "red"
        overlay.lastSentSourceProps = props("red")

        // Fresh but structurally equal props instance (data-class equality
        // over components), as a re-broadcast of the same team would build.
        assertTrue(overlay.matchesLastSent("red", props("red")))
    }

    @Test
    fun `different team name does not skip`() {
        val overlay = overlay()
        overlay.teamSent = true
        overlay.lastSentSourceTeam = "red"
        overlay.lastSentSourceProps = props("red")

        assertFalse(overlay.matchesLastSent("blue", props("blue")))
    }

    @Test
    fun `changed props within the same team do not skip`() {
        val overlay = overlay()
        overlay.teamSent = true
        overlay.lastSentSourceTeam = "red"
        overlay.lastSentSourceProps = props("v1")

        val changed = props("v2").copy(playerPrefix = net.minecraft.network.chat.Component.literal("[VIP] "))
        assertFalse(overlay.matchesLastSent("red", changed))

        // Team removed: teamless state differs from a sent source team.
        assertFalse(overlay.matchesLastSent(null, null))
    }

    @Test
    fun `teamless to teamless keeps skipping`() {
        val overlay = overlay()
        overlay.teamSent = true
        overlay.lastSentSourceTeam = null
        overlay.lastSentSourceProps = null

        // Leaving the only team: ledger reports (null, null) again; the
        // neutral look derives from the (unchanged) member name, so the
        // modify packet is redundant and must be skipped.
        assertTrue(overlay.matchesLastSent(null, null))
    }

    @Test
    fun `joining a team after teamless does not skip`() {
        val overlay = overlay()
        overlay.teamSent = true
        overlay.lastSentSourceTeam = null
        overlay.lastSentSourceProps = null

        assertFalse(overlay.matchesLastSent("red", props("red")))
    }
}
