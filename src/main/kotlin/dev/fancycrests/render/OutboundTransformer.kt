package dev.fancycrests.render

import dev.fancycrests.mixin.ClientboundSetPlayerTeamPacketAccessor
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientboundBundlePacket
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket
import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket
import java.util.UUID

/**
 * Pure per-connection outbound packet transformation. Given a connection
 * state and a packet it either returns null (deliver unchanged, no resend) or
 * the ordered replacement list. It mutates only per-connection state, never a
 * shared packet object: every rebuild constructs fresh packets and fresh
 * collections; immutable sub-objects (e.g. `Parameters` records, Components)
 * are reused read-only.
 *
 * Handled packet kinds:
 *  - [ClientboundSetPlayerTeamPacket]: ledger update; taken-over member
 *    entries removed from create lists (packet rebuilt); taken-over member
 *    join/leave dropped; property changes of affected players turned into
 *    virtual-team follow-up updates.
 *  - [ClientboundPlayerInfoUpdatePacket]: known-player set maintenance; new
 *    players get their virtual team created before their entity can show.
 *  - [ClientboundPlayerInfoRemovePacket]: virtual team deletion follow-up.
 *  - [ClientboundSetPassengersPacket]: raw list from the packet as base,
 *    generated label ids appended (dedup), packet rebuilt.
 *  - [ClientboundRemoveEntitiesPacket]: when the target vehicle is removed,
 *    its label ids are appended to the same removal.
 *  - [ClientboundBundlePacket]: members transformed recursively and rebuilt
 *    in order; unrelated sub-packets stay untouched; never nests bundles.
 */
internal class OutboundTransformer(
    /** Called when the transformer discards an observer target state itself. */
    private val onObserverStateClosed: (targetUuid: UUID) -> Unit = {},
) {

    fun transform(state: ConnectionRenderState, packet: Packet<*>): List<Packet<*>>? = when (packet) {
        is ClientboundSetPlayerTeamPacket -> transformTeam(state, packet)
        is ClientboundPlayerInfoUpdatePacket -> transformPlayerInfoUpdate(state, packet)
        is ClientboundPlayerInfoRemovePacket -> transformPlayerInfoRemove(state, packet)
        is ClientboundSetPassengersPacket -> transformPassengers(state, packet)
        is ClientboundRemoveEntitiesPacket -> transformRemoveEntities(state, packet)
        is ClientboundBundlePacket -> transformBundle(state, packet)
        else -> null
    }

    // ------------------------------------------------------------------
    // Team packets
    // ------------------------------------------------------------------

    private fun transformTeam(state: ConnectionRenderState, packet: ClientboundSetPlayerTeamPacket): List<Packet<*>>? {
        val teamAction: ClientboundSetPlayerTeamPacket.Action? = packet.teamAction
        val playerAction: ClientboundSetPlayerTeamPacket.Action? = packet.playerAction
        val name = packet.name

        return if (teamAction != null) {
            if (teamAction == ClientboundSetPlayerTeamPacket.Action.ADD) {
                transformTeamCreate(state, packet, name)
            } else {
                transformTeamRemove(state, packet, name)
            }
        } else if (playerAction != null) {
            transformMemberOp(state, packet, name, playerAction == ClientboundSetPlayerTeamPacket.Action.ADD)
        } else {
            transformTeamChange(state, packet, name)
        }
    }

    /** method ADD (0): team create with initial member list. */
    private fun transformTeamCreate(state: ConnectionRenderState, packet: ClientboundSetPlayerTeamPacket, name: String): List<Packet<*>>? {
        val parameters = packet.parameters.orElse(null) ?: return null
        val players = packet.players.toList()
        val props = propsOf(parameters)
        state.ledger.teamCreated(name, props, players)

        val followUps = propertyUpdatesFor(state, players)
        val kept = players.filterNot { state.isTakenOver(it) }
        return if (kept.size == players.size) {
            // Nothing to filter: original must still flow; deliver follow-ups after it.
            followUps.ifEmpty { null }?.let { listOf(packet) + it }
        } else {
            RenderDiagnostics.teamPacketsRewritten.incrementAndGet()
            listOf(
                ClientboundSetPlayerTeamPacketAccessor.fancycrestsCreate(name, METHOD_ADD, packet.parameters, kept),
            ) + followUps
        }
    }

    /** method REMOVE (1): team deletion; the removal always flows unchanged. */
    private fun transformTeamRemove(state: ConnectionRenderState, packet: ClientboundSetPlayerTeamPacket, name: String): List<Packet<*>>? {
        val affected = state.ledger.teamRemoved(name)
        val followUps = propertyUpdatesFor(state, affected)
        return followUps.ifEmpty { null }?.let { listOf(packet) + it }
    }

    /** method CHANGE (2): property update; membership untouched; flows unchanged. */
    private fun transformTeamChange(state: ConnectionRenderState, packet: ClientboundSetPlayerTeamPacket, name: String): List<Packet<*>>? {
        val parameters = packet.parameters.orElse(null) ?: return null
        val affected = state.ledger.teamPropsChanged(name, propsOf(parameters))
        val followUps = propertyUpdatesFor(state, affected)
        return followUps.ifEmpty { null }?.let { listOf(packet) + it }
    }

    /** method JOIN (3) / LEAVE (4): member operation. */
    private fun transformMemberOp(
        state: ConnectionRenderState,
        packet: ClientboundSetPlayerTeamPacket,
        name: String,
        join: Boolean,
    ): List<Packet<*>>? {
        val players = packet.players.toList()
        if (players.isEmpty()) return null
        for (member in players) {
            if (join) {
                state.ledger.memberJoined(name, member)
            } else {
                state.ledger.memberLeft(member)
            }
        }
        val followUps = propertyUpdatesFor(state, players)
        val anyTakenOver = players.any { state.isTakenOver(it) }
        return if (!anyTakenOver) {
            followUps.ifEmpty { null }?.let { listOf(packet) + it }
        } else if (players.size == 1) {
            // Single taken-over member op: the client membership of this
            // player is owned by the virtual team; drop the source op and
            // deliver only the property follow-ups (if the effective source
            // changed). Returning the list as-is matters: null would deliver
            // the source op unchanged and drag the member back into the
            // source team client-side (a client entry lives in exactly one
            // team), and an empty list means "drop entirely", which is the
            // correct outcome when nothing observable changed.
            RenderDiagnostics.teamPacketsDropped.incrementAndGet()
            followUps
        } else {
            // Mixed list (defensive; vanilla sends single-member packets):
            // rebuild keeping only non-taken entries.
            val kept = players.filterNot { state.isTakenOver(it) }
            RenderDiagnostics.teamPacketsRewritten.incrementAndGet()
            listOf(
                ClientboundSetPlayerTeamPacketAccessor.fancycrestsCreate(
                    name,
                    if (join) METHOD_JOIN else METHOD_LEAVE,
                    packet.parameters,
                    kept,
                ),
            ) + followUps
        }
    }

    /** Virtual-team property updates for taken-over players whose effective source changed. */
    private fun propertyUpdatesFor(state: ConnectionRenderState, playerNames: List<String>): List<Packet<*>> {
        if (playerNames.isEmpty()) return emptyList()
        val updates = ArrayList<Packet<*>>(playerNames.size)
        for (name in playerNames) {
            val overlay = state.overlayOf(name) ?: continue
            val effective = state.ledger.teamOf(name)
            val team = effective?.first
            val props = effective?.second
            // Short-circuit: the client's virtual team already reflects this
            // exact effective source, so a modify packet would be redundant.
            // Teamless members compare as (null, null) - the neutral look is
            // derived from the same member name, never re-sent.
            if (overlay.matchesLastSent(team, props)) continue
            updates += if (overlay.teamSent) {
                TeamPackets.modifyVirtual(overlay.virtualTeamName, overlay.currentName, props)
            } else {
                overlay.teamSent = true
                TeamPackets.createVirtual(overlay.virtualTeamName, overlay.currentName, props)
            }
            overlay.lastSentSourceTeam = team
            overlay.lastSentSourceProps = props
        }
        return updates
    }

    private fun propsOf(parameters: ClientboundSetPlayerTeamPacket.Parameters): SourceTeamProps {
        val (friendlyFire, friendlyInvisibles) = SourceTeamProps.decodeOptions(parameters.options)
        return SourceTeamProps(
            displayName = parameters.displayName,
            playerPrefix = parameters.playerPrefix,
            playerSuffix = parameters.playerSuffix,
            nameTagVisibility = parameters.nameTagVisibility,
            // Death-message visibility is not part of the 26.2 wire
            // parameters; ALWAYS matches what a client could observe.
            deathMessageVisibility = net.minecraft.world.scores.Team.Visibility.ALWAYS,
            collisionRule = parameters.collisionRule,
            color = parameters.color,
            allowFriendlyFire = friendlyFire,
            seeFriendlyInvisibles = friendlyInvisibles,
        )
    }

    // ------------------------------------------------------------------
    // Player info packets (always delivered unchanged; follow-ups appended)
    // ------------------------------------------------------------------

    private fun transformPlayerInfoUpdate(state: ConnectionRenderState, packet: ClientboundPlayerInfoUpdatePacket): List<Packet<*>>? {
        if (!packet.actions().contains(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER)) return null
        val followUps = ArrayList<Packet<*>>()
        for (entry in packet.entries()) {
            val profile = entry.profile() ?: continue
            val uuid = entry.profileId()
            val name = profile.name()
            val existing = state.overlays[uuid]
            if (existing == null) {
                val overlay = state.addOverlay(uuid, name)
                if (overlay != null) {
                    // Prepare the independent virtual team before the entity
                    // can show up on this client.
                    overlay.teamSent = true
                    val effective = state.ledger.teamOf(name)
                    overlay.lastSentSourceTeam = effective?.first
                    overlay.lastSentSourceProps = effective?.second
                    followUps += TeamPackets.createVirtual(
                        overlay.virtualTeamName,
                        overlay.currentName,
                        effective?.second,
                    )
                    RenderDiagnostics.virtualTeams.incrementAndGet()
                }
            } else if (existing.currentName != name) {
                val oldName = state.renameOverlay(existing, name)
                followUps += TeamPackets.memberOp(existing.virtualTeamName, oldName, add = false)
                followUps += TeamPackets.memberOp(existing.virtualTeamName, name, add = true)
                // Neutral (teamless) virtual-team props embed the member
                // name; force a refresh on the next property update.
                existing.lastSentSourceTeam = null
                existing.lastSentSourceProps = null
            }
        }
        return followUps.ifEmpty { null }?.let { listOf(packet) + it }
    }

    private fun transformPlayerInfoRemove(state: ConnectionRenderState, packet: ClientboundPlayerInfoRemovePacket): List<Packet<*>>? {
        val followUps = ArrayList<Packet<*>>()
        for (uuid in packet.profileIds()) {
            val overlay = state.removeOverlay(uuid) ?: continue
            followUps += TeamPackets.removeVirtual(overlay.virtualTeamName)
            RenderDiagnostics.virtualTeams.decrementAndGet()
        }
        return followUps.ifEmpty { null }?.let { listOf(packet) + it }
    }

    // ------------------------------------------------------------------
    // Passenger and removal packets
    // ------------------------------------------------------------------

    private fun transformPassengers(state: ConnectionRenderState, packet: ClientboundSetPassengersPacket): List<Packet<*>>? {
        val vehicle = packet.vehicle
        val raw = packet.passengers.toList()
        val observerState = state.targets.values.firstOrNull { it.targetEntityId == vehicle } ?: return null
        val labelIds = observerState.liveLabelIds()
        if (labelIds.isEmpty()) return null
        val merged = PassengerMerge.merge(raw, labelIds)
        observerState.lastRealPassengers = raw
        return if (merged.size == raw.size) {
            null // labels already mounted; nothing to rewrite
        } else {
            RenderDiagnostics.passengerPacketsRewritten.incrementAndGet()
            listOf(PassengerPackets.create(vehicle, merged.toIntArray()))
        }
    }

    private fun transformRemoveEntities(state: ConnectionRenderState, packet: ClientboundRemoveEntitiesPacket): List<Packet<*>>? {
        val ids = packet.entityIds
        val affected = state.targets.values.filter { ids.contains(it.targetEntityId) }
        if (affected.isEmpty()) return null
        val extra = affected.flatMap { it.liveLabelIds() }.distinct()
        for (observerState in affected) {
            state.targets.remove(observerState.targetUuid)
            onObserverStateClosed(observerState.targetUuid)
        }
        if (extra.isEmpty()) return null
        val mergedIds = ArrayList<Int>(ids.size + extra.size)
        for (id in ids) mergedIds.add(id)
        for (id in extra) if (!mergedIds.contains(id)) mergedIds.add(id)
        return listOf(ClientboundRemoveEntitiesPacket(*mergedIds.toIntArray()))
    }

    // ------------------------------------------------------------------
    // Bundle packets
    // ------------------------------------------------------------------

    private fun transformBundle(state: ConnectionRenderState, packet: ClientboundBundlePacket): List<Packet<*>>? {
        RenderDiagnostics.bundlesProcessed.incrementAndGet()
        var changed = false
        val rebuilt = ArrayList<net.minecraft.network.protocol.Packet<in net.minecraft.network.protocol.game.ClientGamePacketListener>>()
        for (sub in packet.subPackets()) {
            val replacement = try {
                transform(state, sub)
            } catch (e: Exception) {
                // Exception (not Throwable): Errors propagate; sub-packet
                // transformation failures degrade to passthrough.
                RenderDiagnostics.recordError("bundle sub-transform", e)
                null
            }
            if (replacement == null) {
                rebuilt += sub
            } else {
                changed = true
                // Splice rebuild + follow-ups in order right where the
                // original sub-packet sat; follow-ups never precede their
                // trigger. A nested bundle transforms in place — no new
                // nesting is introduced.
                rebuilt += @Suppress("UNCHECKED_CAST") (
                    replacement as List<net.minecraft.network.protocol.Packet<in net.minecraft.network.protocol.game.ClientGamePacketListener>>
                    )
            }
        }
        return if (changed) {
            listOf(ClientboundBundlePacket(rebuilt))
        } else {
            null
        }
    }

    private companion object {
        /** Team packet method ints (verified from 26.2 factory bytecode). */
        const val METHOD_ADD = 0
        const val METHOD_JOIN = 3
        const val METHOD_LEAVE = 4
    }
}
