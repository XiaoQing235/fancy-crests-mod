package dev.fancycrests.render

import dev.fancycrests.api.DisplaySnapshot
import dev.fancycrests.api.RenderOffset
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.Packet
import net.minecraft.server.network.ServerGamePacketListenerImpl
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Per-player team overlay state on one connection: the virtual team name and
 * the protocol-visible member name (the current GameProfile name; internal
 * bookkeeping is UUID-keyed).
 */
internal class PlayerOverlay(
    @JvmField val uuid: UUID,
    @JvmField val virtualTeamName: String,
    @JvmField var currentName: String,
    /** True once the create packet has been queued on the ordered send path. */
    @JvmField var teamSent: Boolean,
) {
    /**
     * Effective source team mirrored into the last create/modify packet
     * produced for this overlay (team name + ledger props; `null` team means
     * teamless, rendered with neutral properties). Used to short-circuit
     * redundant property follow-ups: team create/remove/CHANGE/JOIN/LEAVE
     * each recompute follow-ups for every affected member x connection, and
     * without this cache a single CHANGE on a large team emits one modify
     * packet per member per connection even when nothing observable changed.
     *
     * Equality is structural ([SourceTeamProps] is a data class over
     * components/enums); a false "changed" verdict only costs one redundant
     * packet, a false "unchanged" is impossible (different values compare
     * unequal).
     */
    @JvmField var lastSentSourceTeam: String? = null
    @JvmField var lastSentSourceProps: SourceTeamProps? = null

    /**
     * True when the client's virtual team for this overlay already reflects
     * exactly [team]/[props] (and the create packet was sent at all), so no
     * follow-up packet is needed.
     */
    fun matchesLastSent(team: String?, props: SourceTeamProps?): Boolean =
        teamSent && lastSentSourceTeam == team && lastSentSourceProps == props
}

/**
 * Label-side state for one (observer connection, target) pair. Only exists
 * while labels for that target were generated on this connection (i.e. the
 * observer currently tracks the target and the channel is live for it).
 */
internal class ObserverTargetState(
    @JvmField val targetUuid: UUID,
    /** Entity id of the target player at spawn time; guards stale references. */
    @JvmField var targetEntityId: Int,
    @JvmField val worldGeneration: Long,
    @JvmField var foregroundId: Int,
    @JvmField var backgroundId: Int,
    /** 0 = second-line entity currently absent. */
    @JvmField var secondLineId: Int,
    @JvmField var sentFirstLine: Component? = null,
    @JvmField var sentSecondLine: Component? = null,
    @JvmField var sentSneaking: Boolean = false,
    @JvmField var sentFirstLineOffset: RenderOffset? = null,
    @JvmField var sentSecondLineOffset: RenderOffset? = null,
    @JvmField var sentViewRangeBlocks: Double = -1.0,
    /** Last real passenger ids seen for the vehicle on this connection. */
    @JvmField var lastRealPassengers: List<Int> = emptyList(),
) {
    /** Entity ids of the label entities currently generated on this connection. */
    fun liveLabelIds(): List<Int> = buildList {
        add(foregroundId)
        add(backgroundId)
        if (secondLineId != 0) add(secondLineId)
    }
}

/**
 * Target-side shared label allocation: entity ids and UUIDs for the (up to)
 * three label entities of one target. Ids come from the server's unified
 * allocator (`ServerLevel.getNextEntityId()`), are allocated once per target
 * and reallocated when the target's entity id changes (respawn), which
 * invalidates every observer-side instance referencing the old cycle.
 */
internal class LabelEntityIds {
    @JvmField var foregroundId: Int = 0
    @JvmField var backgroundId: Int = 0
    @JvmField var secondLineId: Int = 0
    @JvmField var foregroundUuid: UUID = UUID.randomUUID()
    @JvmField var backgroundUuid: UUID = UUID.randomUUID()
    @JvmField var secondLineUuid: UUID = UUID.randomUUID()
    @JvmField var allocatedFirstLine: Boolean = false

    /** Allocates/refreshes first-line ids; returns true when ids changed. */
    fun allocateFirstLine(allocator: () -> Int): Boolean {
        if (allocatedFirstLine) return false
        foregroundId = allocator()
        backgroundId = allocator()
        foregroundUuid = UUID.randomUUID()
        backgroundUuid = UUID.randomUUID()
        allocatedFirstLine = true
        return true
    }

    /** Allocates the second-line id once; returns true when newly allocated. */
    fun allocateSecondLine(allocator: () -> Int): Boolean {
        if (secondLineId != 0) return false
        secondLineId = allocator()
        secondLineUuid = UUID.randomUUID()
        return true
    }

    /** Invalidates the whole cycle (target entity id changed / respawn). */
    fun invalidate() {
        allocatedFirstLine = false
        secondLineId = 0
    }
}

/**
 * Per-target render state: newest snapshot plus the shared label allocation.
 * Content is observer-independent; only connection state differs per viewer.
 */
internal class TargetRenderState(
    @JvmField val uuid: UUID,
    @JvmField var snapshot: DisplaySnapshot,
    @JvmField var currentEntityId: Int,
    @JvmField val labelIds: LabelEntityIds = LabelEntityIds(),
    @JvmField var sneaking: Boolean = false,
    @JvmField var lastRealPassengers: List<Int> = emptyList(),
    /**
     * Number of connections currently holding labels for this target.
     *
     * Atomic: mutated from the server main thread (tracking events, spawn,
     * end-tick) and from the netty thread via disconnect handling; a plain
     * Int `+=`/`-=` loses updates and would leave the `observerCount <= 0`
     * gate in [RenderService.onEndTick] stuck (or polling forever) after a
     * lost decrement/increment race.
     */
    @JvmField
    val observerCount: AtomicInteger = AtomicInteger(0),
)

/**
 * All per-connection render bookkeeping: team ledger + overlays, label states
 * for tracked targets, world generation stamp and one-shot init packets.
 *
 * Mutations happen under [lock]; every packet list produced is a fresh
 * per-connection object (shared inbound packet objects are only read).
 */
internal class ConnectionRenderState(
    @JvmField val listener: ServerGamePacketListenerImpl,
    @JvmField val sessionStamp: Long,
) {
    @JvmField val lock = Any()

    /** Pre-transformation source team state (team ledger). */
    @JvmField val ledger = SourceTeamLedger()

    /** UUID -> team overlay; plus a name index for protocol member names. */
    @JvmField val overlays = LinkedHashMap<UUID, PlayerOverlay>()
    @JvmField val overlaysByName = HashMap<String, PlayerOverlay>()

    /** Target UUID -> label state (labels generated on this connection). */
    @JvmField val targets = LinkedHashMap<UUID, ObserverTargetState>()

    /** One-shot init packets (virtual team creates) sent after the first transformed packet. */
    @JvmField var pendingInit: List<Packet<*>>? = null

    /** World generation stamp; bumped when the observer changes level. */
    @JvmField var worldGeneration: Long = 0

    /** Level identity last seen for the observer (null until first tick). */
    @JvmField var lastSeenLevel: net.minecraft.world.level.Level? = null

    fun isTakenOver(playerName: String): Boolean = overlaysByName.containsKey(playerName)

    fun overlayOf(playerName: String): PlayerOverlay? = overlaysByName[playerName]

    /** Registers an overlay; returns it, or null when the UUID is already overlaid. */
    fun addOverlay(uuid: UUID, name: String): PlayerOverlay? {
        if (overlays.containsKey(uuid)) return null
        val reserved = { candidate: String ->
            ledger.isReserved(candidate) || overlays.values.any { it.virtualTeamName == candidate }
        }
        val overlay = PlayerOverlay(uuid, VirtualTeamNames.forUuid(uuid, reserved), name, false)
        overlays[uuid] = overlay
        overlaysByName[name] = overlay
        return overlay
    }

    /** Re-keys the name index after a player name change; returns the old name. */
    fun renameOverlay(overlay: PlayerOverlay, newName: String): String {
        val old = overlay.currentName
        if (overlaysByName.remove(old) === overlay) {
            overlaysByName[newName] = overlay
        }
        overlay.currentName = newName
        return old
    }

    fun removeOverlay(uuid: UUID): PlayerOverlay? {
        val overlay = overlays.remove(uuid) ?: return null
        if (overlaysByName[overlay.currentName] === overlay) {
            overlaysByName.remove(overlay.currentName)
        }
        return overlay
    }
}
