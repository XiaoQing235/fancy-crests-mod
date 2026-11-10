package dev.fancycrests.render

import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket
import net.minecraft.world.scores.PlayerTeam
import net.minecraft.world.scores.Scoreboard
import net.minecraft.world.scores.Team

/**
 * Construction of team packets for the per-player virtual teams and for
 * restore operations, via the public 26.2 static factories.
 *
 * The factories only accept a live [PlayerTeam]; we therefore build short-lived
 * dummy teams on a private [Scoreboard] that is never registered anywhere and
 * never touches the server's real scoreboard. [PlayerTeam]'s constructor is a
 * plain field initializer (verified bytecode), so this is side-effect free.
 */
internal object TeamPackets {

    /** Dummy scoreboard for packet-time team dummies; never visible server-side. */
    private val dummyBoard: Scoreboard by lazy { Scoreboard() }

    /** Builds a dummy [PlayerTeam] carrying [name] and [props] (no members). */
    fun dummyTeam(name: String, props: SourceTeamProps): PlayerTeam {
        val team = PlayerTeam(dummyBoard, name)
        team.setDisplayName(props.displayName)
        team.setPlayerPrefix(props.playerPrefix)
        team.setPlayerSuffix(props.playerSuffix)
        // Virtual teams fix name tag visibility to NEVER (the suppression
        // gate); death message visibility and collision rule are copied from
        // the effective source for best-effort compatibility.
        team.setNameTagVisibility(Team.Visibility.NEVER)
        team.setDeathMessageVisibility(props.deathMessageVisibility)
        team.setCollisionRule(props.collisionRule)
        team.setColor(props.color)
        team.unpackOptions(
            SourceTeamProps.encodeOptions(
                props.allowFriendlyFire,
                props.seeFriendlyInvisibles,
            )
        )
        return team
    }

    /** Adds a member entry to a dummy team (live set view, used by create packets). */
    fun withMember(team: PlayerTeam, member: String): PlayerTeam {
        @Suppress("UNCHECKED_CAST")
        (team.players as MutableCollection<String>).add(member)
        return team
    }

    /** Create packet for a virtual team, including its single member entry. */
    fun createVirtual(virtualTeamName: String, memberName: String, source: SourceTeamProps?): ClientboundSetPlayerTeamPacket {
        val team = withMember(dummyTeam(virtualTeamName, source ?: SourceTeamProps.neutral(memberName)), memberName)
        return ClientboundSetPlayerTeamPacket.createAddOrModifyPacket(team, true)
    }

    /** Property-only update packet (method CHANGE) for an existing virtual team. */
    fun modifyVirtual(virtualTeamName: String, memberName: String, source: SourceTeamProps?): ClientboundSetPlayerTeamPacket {
        val team = withMember(dummyTeam(virtualTeamName, source ?: SourceTeamProps.neutral(memberName)), memberName)
        return ClientboundSetPlayerTeamPacket.createAddOrModifyPacket(team, false)
    }

    /** Delete packet for a virtual team (removes its members client-side). */
    fun removeVirtual(virtualTeamName: String): ClientboundSetPlayerTeamPacket =
        ClientboundSetPlayerTeamPacket.createRemovePacket(dummyTeam(virtualTeamName, SourceTeamProps.neutral(virtualTeamName)))

    /** Member add/remove op on a virtual team (protocol-visible member name). */
    fun memberOp(virtualTeamName: String, memberName: String, add: Boolean): ClientboundSetPlayerTeamPacket =
        ClientboundSetPlayerTeamPacket.createPlayerPacket(
            dummyTeam(virtualTeamName, SourceTeamProps.neutral(memberName)),
            memberName,
            if (add) ClientboundSetPlayerTeamPacket.Action.ADD else ClientboundSetPlayerTeamPacket.Action.REMOVE,
        )

    /** Restore op: add a member back to its current effective source team. */
    fun restoreToSource(sourceTeamName: String, memberName: String): ClientboundSetPlayerTeamPacket =
        ClientboundSetPlayerTeamPacket.createPlayerPacket(
            dummyTeam(sourceTeamName, SourceTeamProps.neutral(memberName)),
            memberName,
            ClientboundSetPlayerTeamPacket.Action.ADD,
        )
}
