package dev.fancycrests.render

import net.minecraft.network.chat.CommonComponents
import net.minecraft.network.chat.Component
import net.minecraft.world.scores.PlayerTeam
import net.minecraft.world.scores.Team
import net.minecraft.world.scores.TeamColor
import java.util.Optional

/**
 * Immutable snapshot of the properties a client sees for one source team,
 * mirroring `ClientboundSetPlayerTeamPacket.Parameters`:
 * displayName, prefix, suffix, nameTag/deathMessage visibility, collision
 * rule, color and the packed options byte (friendly fire / friendly
 * invisibles).
 */
data class SourceTeamProps(
    val displayName: Component,
    val playerPrefix: Component,
    val playerSuffix: Component,
    val nameTagVisibility: Team.Visibility,
    val deathMessageVisibility: Team.Visibility,
    val collisionRule: Team.CollisionRule,
    val color: Optional<TeamColor>,
    val allowFriendlyFire: Boolean,
    val seeFriendlyInvisibles: Boolean,
) {
    companion object {
        /** Neutral properties of a player with no source team (26.2 no-team look). */
        fun neutral(playerName: String): SourceTeamProps = SourceTeamProps(
            displayName = Component.literal(playerName),
            playerPrefix = CommonComponents.EMPTY,
            playerSuffix = CommonComponents.EMPTY,
            nameTagVisibility = Team.Visibility.ALWAYS,
            deathMessageVisibility = Team.Visibility.ALWAYS,
            collisionRule = Team.CollisionRule.ALWAYS,
            color = Optional.empty(),
            allowFriendlyFire = true,
            // No teammates -> no friendly-invisible seeing; deliberate
            // difference from a fresh PlayerTeam default, documented in
            // docs/render-injections.md.
            seeFriendlyInvisibles = false,
        )

        /** Reads the effective properties from a live server [PlayerTeam]. */
        fun from(team: PlayerTeam): SourceTeamProps {
            val options = team.packOptions().toInt()
            return SourceTeamProps(
                displayName = team.getDisplayName(),
                playerPrefix = team.getPlayerPrefix(),
                playerSuffix = team.getPlayerSuffix(),
                nameTagVisibility = team.getNameTagVisibility(),
                deathMessageVisibility = team.getDeathMessageVisibility(),
                collisionRule = team.getCollisionRule(),
                color = team.getColor(),
                allowFriendlyFire = options and 1 != 0,
                seeFriendlyInvisibles = options and 2 != 0,
            )
        }

        /** Decodes a packet [ClientboundSetPlayerTeamPacket.Parameters.options] byte. */
        fun decodeOptions(options: Byte): Pair<Boolean, Boolean> =
            (options.toInt() and 1 != 0) to (options.toInt() and 2 != 0)

        /** Packs friendly-fire / friendly-invisibles into the options byte. */
        fun encodeOptions(allowFriendlyFire: Boolean, seeFriendlyInvisibles: Boolean): Byte =
            ((if (allowFriendlyFire) 1 else 0) or (if (seeFriendlyInvisibles) 2 else 0)).toByte()
    }
}

/**
 * Per-connection ledger of the *pre-transformation* source team state:
 * every team create/property-change/member-op/delete that the connection
 * would have seen without this mod, applied strictly in send order.
 *
 * The ledger mirrors the scoreboard invariant that one entry (player name)
 * belongs to at most one team: joining a team implicitly leaves the previous
 * one. Membership is keyed by player name (protocol representation); callers
 * map UUIDs to the current GameProfile name.
 *
 * Pure state machine; no packet construction, no sending.
 */
class SourceTeamLedger {

    private val teams = LinkedHashMap<String, SourceTeamProps>()

    /** playerName -> owning team name; absent = teamless. */
    private val memberToTeam = HashMap<String, String>()

    val teamNames: Set<String> get() = teams.keys

    fun teamCount(): Int = teams.size

    /** Team create / re-create; replaces props and resets membership to [members]. */
    fun teamCreated(name: String, props: SourceTeamProps, members: Collection<String>) {
        teams[name] = props
        // Drop any stale membership pointing at this team, then apply the list.
        memberToTeam.entries.removeAll { it.value == name }
        for (member in members) {
            memberToTeam[member] = name
        }
    }

    /** Team property change (method CHANGE); membership untouched. Returns member names affected. */
    fun teamPropsChanged(name: String, props: SourceTeamProps): List<String> {
        if (!teams.containsKey(name)) {
            // Property update for a team we never saw created: treat as create
            // with no members so later joins still resolve.
            teams[name] = props
            return emptyList()
        }
        teams[name] = props
        return membersOf(name)
    }

    /** Team delete (method REMOVE). Returns member names that lost this source. */
    fun teamRemoved(name: String): List<String> {
        val affected = membersOf(name)
        teams.remove(name)
        memberToTeam.entries.removeAll { it.value == name }
        return affected
    }

    /** Member join (method JOIN); implicitly leaves any previous team. Returns previous team or null. */
    fun memberJoined(teamName: String, playerName: String): String? {
        if (!teams.containsKey(teamName)) return null
        val previous = memberToTeam.put(playerName, teamName)
        return previous
    }

    /** Member leave (method LEAVE). Returns the team it left, or null when unknown. */
    fun memberLeft(playerName: String): String? {
        if (!memberToTeam.containsKey(playerName)) return null
        return memberToTeam.remove(playerName)
    }

    fun membersOf(teamName: String): List<String> =
        memberToTeam.entries.filter { it.value == teamName }.map { it.key }

    /** Effective source team of a player, or null when teamless. */
    fun teamOf(playerName: String): Pair<String, SourceTeamProps>? {
        val teamName = memberToTeam[playerName] ?: return null
        val props = teams[teamName] ?: return null
        return teamName to props
    }

    fun isReserved(name: String): Boolean = teams.containsKey(name)
}
