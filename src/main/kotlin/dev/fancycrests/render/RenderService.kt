package dev.fancycrests.render

import dev.fancycrests.api.DisplaySnapshot
import dev.fancycrests.api.RenderSettings
import dev.fancycrests.mixin.ServerCommonPacketListenerImplAccessor
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityLevelChangeEvents
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.networking.v1.EntityTrackingEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket
import net.minecraft.network.protocol.game.ClientboundBundlePacket
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ChunkMap
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.network.ServerGamePacketListenerImpl
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.phys.Vec3
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Server-side orchestration of the head render channel:
 *
 *  - per-connection team overlays + label state ([ConnectionRenderState]),
 *  - per-target shared label allocation and snapshot cache ([TargetRenderState]),
 *  - lifecycle coverage: join/leave, tracking start/stop, respawn, level
 *    change, world generations, tick polling for sneak state and real
 *    passenger changes, settings hot reload, ordered stop.
 *
 * All state mutations happen on the server main thread (events, tick) or
 * inside the synchronized transform path; every delayed/derived send is
 * guarded by the session stamp and per-connection world generation so stale
 * tasks become no-ops.
 */
internal class RenderService(initialSettings: RenderSettings) {

    @Volatile
    var settings: RenderSettings = initialSettings
        private set

    /** Unique per-service-instance stamp; guards stale async/in-flight work. */
    val sessionStamp: Long = SESSION_STAMPS.incrementAndGet()

    @Volatile
    var running: Boolean = true
        private set

    @Volatile
    var server: MinecraftServer? = null
        private set

    val connections = ConcurrentHashMap<ServerGamePacketListenerImpl, ConnectionRenderState>()

    val targets = ConcurrentHashMap<UUID, TargetRenderState>()

    private val transformer = OutboundTransformer(onObserverStateClosed = { targetUuid ->
        val target = targets[targetUuid] ?: return@OutboundTransformer
        // Guarded decrement: never dip below zero even if a disconnect-side
        // decrement already ran for this relation (both paths may fire for
        // the same observer state in disconnect races).
        val previous = target.observerCount.getAndUpdate { current ->
            if (current > 0) current - 1 else current
        }
        if (previous > 0) {
            RenderDiagnostics.trackingRelations.decrementAndGet()
        }
    })

    private var handlersRegistered = false

    // =================================================================
    // Lifecycle
    // =================================================================

    fun start() {
        registerHandlers()
    }

    private fun registerHandlers() {
        if (handlersRegistered) return
        handlersRegistered = true

        EntityTrackingEvents.START_TRACKING.register { entity, player ->
            if (isCurrent()) onTrackingStart(entity as? ServerPlayer, player)
        }
        EntityTrackingEvents.STOP_TRACKING.register { entity, player ->
            if (isCurrent()) onTrackingStop(entity as? ServerPlayer, player)
        }
        ServerPlayConnectionEvents.JOIN.register { handler, _, server ->
            if (isCurrent()) {
                ensureServer(server)
                attachConnection(handler, flushInit = true)
            }
        }
        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ ->
            if (isCurrent()) onDisconnect(handler)
        }
        ServerPlayerEvents.AFTER_RESPAWN.register { oldPlayer, newPlayer, _ ->
            if (isCurrent()) {
                // Both sides of the same respawn: the respawned player is
                // an observer whose client rebuilt its world view, and (as
                // a target) keeps its entity id (respawn preserves it), so
                // only the allocation sweep runs for it.
                onObserverClientWorldReset(newPlayer)
                onTargetEntityReplaced(oldPlayer)
            }
        }
        ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL.register { player, _, _ ->
            if (isCurrent()) onTargetEntityReplaced(player)
        }
        ServerTickEvents.END_SERVER_TICK.register { server ->
            if (isCurrent()) {
                ensureServer(server)
                onEndTick(server)
            }
        }
    }

    private fun isCurrent(): Boolean = running && RenderPackets.active === this

    private fun ensureServer(server: MinecraftServer) {
        if (this.server == null) this.server = server
    }

    fun updateSettings(newSettings: RenderSettings) {
        if (!running) return
        settings = newSettings
        // Placement changes apply immediately to already-generated entities.
        for ((listener, state) in connections) {
            synchronized(state.lock) {
                for ((_, obs) in state.targets) {
                    targets[obs.targetUuid] ?: continue
                    pushPlacement(listener, obs)
                }
            }
        }
    }

    // =================================================================
    // Snapshot channel
    // =================================================================

    fun publish(snapshot: DisplaySnapshot) {
        if (!running) return
        val server = this.server
        if (server != null && !server.isSameThread) {
            // Contract: publish is main-thread only; defensive skip.
            return
        }
        val target = targets[snapshot.target]
        if (target == null) {
            // New target: bind to the live entity id when available.
            val player = server?.playerList?.getPlayer(snapshot.target)
            val fresh = TargetRenderState(
                uuid = snapshot.target,
                snapshot = snapshot,
                currentEntityId = player?.id ?: -1,
            )
            targets[snapshot.target] = fresh
            RenderDiagnostics.targets.incrementAndGet()
            if (player != null) {
                backfillPairings(fresh, player)
            } else {
                // The live player could not be resolved yet - the channel
                // just started mid-tick (no server binding so far) or the
                // target is offline. Observers that already track the
                // target will never see a START_TRACKING for it, so the
                // end-tick sweep must retry the backfill.
                fresh.pairingBackfillPending = true
            }
            return
        }
        target.snapshot = snapshot
        pushSnapshot(target)
    }

    /**
     * Retro-pairing backfill for a freshly created [TargetRenderState]
     * (render batch E2).
     *
     * Problem: observer->target pairings are normally built only by
     * START_TRACKING. When the channel starts while players are already
     * online (config `channels.headRender` false -> true + reload), every
     * online player is a "new target" at its first publish, but no tracking
     * event ever fires for pairs already inside tracking range: outbound
     * interception immediately hides the vanilla name tags via virtual
     * teams, yet no AddEntity for the label entities is ever sent, so every
     * already-tracking observer sees a nameless player until they leave and
     * re-enter tracking range or relog.
     *
     * Fix: when a target state is first created, run [spawnLabels] for every
     * online observer allowed by [BackfillPolicy.backfillObservers]: same
     * dimension, never the target itself, and — since audit #1 — *only*
     * observers that already track the target on the vanilla tracking layer.
     * spawnLabels is idempotent for existing pairings (early-exit +
     * passenger re-merge only), so overlaps with tracking events that fire
     * for the same pair are harmless.
     *
     * Tracking query (verified with javap/-c against the 26.2 merged jar):
     * `ChunkMap.forEachEntityTrackedBy(ServerPlayer, Consumer<Entity>)` is
     * public and iterates the private `entityMap.values()`, accepting each
     * `TrackedEntity.entity` whose `seenBy` set contains
     * `observer.connection` — the exact set `TrackedEntity.updatePlayer`/
     * `removePlayer` maintain around `ServerEntity.addPairing`/
     * `removePairing` (seenBy.add precedes addPairing, seenBy.remove
     * precedes removePairing), i.e. precisely the window in which our
     * START/STOP_TRACKING handlers run. A backfilled pairing therefore has
     * a real tracking relation and receives its vanilla STOP when it ends.
     * Observers that do not (yet) track the target are skipped here and get
     * labels through the normal START_TRACKING path when they enter range —
     * an untracked target can no longer leave a label frozen at the backfill
     * position (nothing drives position sync for entities a client does not
     * track). Using this public query also avoids accessor mixins into the
     * package-private `ChunkMap$TrackedEntity` (string-target mixin with
     * erased return types — the version-fragile shape the audit feared).
     */
    private fun backfillPairings(target: TargetRenderState, player: ServerPlayer) {
        if (!running) return
        val server = this.server ?: return
        val targetLevel = player.level()
        val chunkMap = targetLevel.getChunkSource().chunkMap
        val candidates = server.playerList.players.map { BackfillCandidate(it, it.uuid, it.level()) }
        val observers = BackfillPolicy.backfillObservers(
            target.uuid,
            targetLevel,
            candidates,
            isTracked = { observer -> isTracking(chunkMap, observer, player.id) },
        )
        for (observer in observers) {
            // The observer's connection may not be attached yet while the
            // channel is starting (no end-tick has run); attaching here also
            // flushes the one-shot virtual-team init, preserving the wire
            // order "teams before label entities".
            val state = attachConnection(observer.connection, flushInit = true)
            synchronized(state.lock) {
                spawnLabels(observer.connection, state, target, player)
            }
        }
    }

    /**
     * Vanilla tracking-membership query: does [observer]'s client currently
     * track the entity with id [targetEntityId] in the dimension of
     * [chunkMap]? Implemented over the public
     * `ChunkMap.forEachEntityTrackedBy`, which consults
     * `TrackedEntity.seenBy` (see [backfillPairings] for the bytecode
     * evidence). The consumer cannot break the iteration early, so the flag
     * simply latches; the scan is linear in the dimension's tracked entities
     * per observer and only runs on rare backfill events (channel start /
     * first publish of a target / pending-target retry).
     */
    private fun isTracking(chunkMap: ChunkMap, observer: ServerPlayer, targetEntityId: Int): Boolean {
        var tracked = false
        chunkMap.forEachEntityTrackedBy(observer) { entity ->
            if (entity.id == targetEntityId) tracked = true
        }
        return tracked
    }

    fun clearTarget(targetUuid: UUID) {
        if (!running) return
        val target = targets.remove(targetUuid) ?: return
        RenderDiagnostics.targets.decrementAndGet()
        val server = this.server
        for ((listener, state) in connections) {
            synchronized(state.lock) {
                val obs = state.targets.remove(targetUuid) ?: return@synchronized
                val realPassengers = server?.playerList?.getPlayer(targetUuid)?.passengers?.map { it.id }
                    ?: obs.lastRealPassengers
                cleanupLabels(listener, obs, target.currentEntityId, realPassengers)
                adjustRelation(target, -1)
            }
        }
        invalidateTargetLabels(target)
    }

    /** Ordered shutdown per spec: labels/mounts, then virtual teams, then source restore. */
    fun stop() {
        if (!running) return
        running = false
        RenderPackets.detach(this)
        val server = this.server

        // 1) Labels and mounts: real-passengers-only list first (only when
        //    the observer is still connected and the target entity still
        //    exists server-side), then remove the virtual entities.
        //    Every send is isolated per connection: one dead/racy listener
        //    must not abort the teardown for the remaining connections
        //    (a stray exception here would leave their virtual teams live
        //    and their name tags suppressed until relogin).
        for ((listener, state) in connections) {
            synchronized(state.lock) {
                for ((_, obs) in state.targets) {
                    try {
                        val realPassengers = server?.playerList?.getPlayer(obs.targetUuid)?.passengers?.map { it.id }
                            ?: obs.lastRealPassengers
                        val targetExists = server?.playerList?.getPlayer(obs.targetUuid) != null
                        if (targetExists) {
                            sendPacket(listener, PassengerPackets.create(obs.targetEntityId, realPassengers.toIntArray()))
                        }
                        sendPacket(listener, ClientboundRemoveEntitiesPacket(*obs.liveLabelIds().toIntArray()))
                    } catch (t: Exception) {
                        RenderDiagnostics.recordError("stop label removal", t)
                    }
                }
                state.targets.clear()

                // 2) Virtual team deletion for every known player.
                val overlays = state.overlays.values.toList()
                for (overlay in overlays) {
                    try {
                        sendPacket(listener, TeamPackets.removeVirtual(overlay.virtualTeamName))
                    } catch (t: Exception) {
                        RenderDiagnostics.recordError("stop virtual team removal", t)
                    }
                }

                // 3) Restore membership to the latest effective source team
                //    (missing source -> stay teamless).
                for (overlay in overlays) {
                    try {
                        val effective = state.ledger.teamOf(overlay.currentName)
                        if (effective != null) {
                            sendPacket(listener, TeamPackets.restoreToSource(effective.first, overlay.currentName))
                        }
                    } catch (t: Exception) {
                        RenderDiagnostics.recordError("stop source restore", t)
                    }
                }

                RenderDiagnostics.virtualTeams.addAndGet(-overlays.size)
                state.overlays.clear()
                state.overlaysByName.clear()
                state.pendingInit = null
            }
        }

        connections.clear()
        for ((_, target) in targets) invalidateTargetLabels(target)
        targets.clear()
        RenderDiagnostics.targets.set(0)
        RenderDiagnostics.virtualEntities.set(0)
        RenderDiagnostics.trackingRelations.set(0)
        RenderDiagnostics.virtualTeams.set(0)
    }

    private fun invalidateTargetLabels(target: TargetRenderState) {
        if (target.labelIds.allocatedFirstLine) {
            RenderDiagnostics.virtualEntities.addAndGet(-2)
        }
        if (target.labelIds.secondLineId != 0) {
            RenderDiagnostics.virtualEntities.decrementAndGet()
        }
        target.labelIds.invalidate()
    }

    // =================================================================
    // Connection attach / detach
    // =================================================================

    internal fun transformForConnection(
        listener: ServerGamePacketListenerImpl,
        packet: net.minecraft.network.protocol.Packet<*>,
    ): List<net.minecraft.network.protocol.Packet<*>>? {
        if (!running) return null
        val existing = connections[listener]
        if (existing != null) {
            synchronized(existing.lock) { return transformer.transform(existing, packet) }
        }
        // Lazy attach on the first outbound game packet. Seeding reads the
        // live server scoreboard, so restrict to the main thread; other
        // threads fall through unchanged until a main-thread attach happens.
        val server = this.server ?: serverOf(listener) ?: return null
        if (!server.isSameThread) return null
        // Never (re)build state for a dead connection. In the disconnect
        // race (onDisconnect removed the state while this send was already
        // on the main thread) a rebuild would create bookkeeping that no
        // event ever clears again, leaking until stop(); the packets of a
        // closed channel are dropped by netty anyway, so pass through.
        if (!isConnectionLive(listener)) return null
        if (connections.containsKey(listener)) {
            // Attached concurrently while we resolved the server.
            val state = connections[listener]!!
            synchronized(state.lock) { return transformer.transform(state, packet) }
        }
        val state = attachConnection(listener, flushInit = false)
        synchronized(state.lock) {
            val init = state.pendingInit
            state.pendingInit = null
            val result = transformer.transform(state, packet)
            return when {
                init.isNullOrEmpty() -> result
                result == null -> listOf(packet) + init
                else -> result + init
            }
        }
    }

    /**
     * Called by [RenderPackets.sendBypassing] when a produced replacement
     * list could not be (fully) delivered. Team creates inside that list
     * were already marked sent while the transformer built it; without this
     * hook the connection would forever believe the virtual teams exist
     * client-side (member ops dropped, name tags unsuppressed) with no path
     * to re-create them. Clearing teamSent makes the next property update
     * take the create branch again - idempotent for teams that did arrive.
     */
    internal fun onOutboundResendFailed(listener: ServerGamePacketListenerImpl) {
        val state = connections[listener] ?: return
        synchronized(state.lock) {
            for (overlay in state.overlays.values) {
                overlay.teamSent = false
            }
        }
    }

    private fun serverOf(listener: ServerGamePacketListenerImpl): MinecraftServer? =
        listener.player.level().server

    /**
     * True when the listener's channel can still carry packets. 26.2 has no
     * `ServerCommonPacketListenerImpl.isDisconnected()`; the authoritative
     * check is `Connection.isConnected()` (channel non-null and open) on the
     * protected `connection` field exposed via accessor mixin. Defaults to
     * "live" when the accessor is somehow absent (fail-open).
     */
    private fun isConnectionLive(listener: ServerGamePacketListenerImpl): Boolean {
        val connection = (listener as? ServerCommonPacketListenerImplAccessor)
            ?.`fancycrests$connection`()
            ?: return true
        return connection.isConnected
    }

    /**
     * Creates (or returns) the per-connection state, seeding the source team
     * ledger from the live scoreboard and preparing virtual team creation
     * packets for every currently online player.
     */
    private fun attachConnection(listener: ServerGamePacketListenerImpl, flushInit: Boolean): ConnectionRenderState {
        val server = this.server ?: serverOf(listener)!!
        ensureServer(server)
        val state = connections.computeIfAbsent(listener) { key ->
            val fresh = ConnectionRenderState(key, sessionStamp)
            seedLedger(fresh, server)
            fresh.pendingInit = buildOverlayInit(fresh, server)
            fresh
        }
        if (flushInit) {
            synchronized(state.lock) {
                val init = state.pendingInit
                state.pendingInit = null
                if (!init.isNullOrEmpty()) {
                    try {
                        RenderPackets.sendAll(listener, init)
                    } catch (t: Exception) {
                        // The init list carries the virtual team creates and
                        // was marked teamSent before the send; on failure let
                        // the next property update lazily re-create them
                        // instead of trusting a create the client never got.
                        RenderDiagnostics.recordError("connection init flush", t)
                        for (overlay in state.overlays.values) overlay.teamSent = false
                    }
                }
            }
        }
        return state
    }

    private fun seedLedger(state: ConnectionRenderState, server: MinecraftServer) {
        val scoreboard = server.scoreboard
        for (team in scoreboard.playerTeams) {
            state.ledger.teamCreated(team.name, SourceTeamProps.from(team), team.players.toList())
        }
    }

    private fun buildOverlayInit(state: ConnectionRenderState, server: MinecraftServer): List<net.minecraft.network.protocol.Packet<*>> {
        val packets = ArrayList<net.minecraft.network.protocol.Packet<*>>()
        for (player in server.playerList.players) {
            val overlay = state.addOverlay(player.uuid, player.gameProfile.name) ?: continue
            overlay.teamSent = true
            val effective = state.ledger.teamOf(overlay.currentName)
            overlay.lastSentSourceTeam = effective?.first
            overlay.lastSentSourceProps = effective?.second
            packets += TeamPackets.createVirtual(overlay.virtualTeamName, overlay.currentName, effective?.second)
            RenderDiagnostics.virtualTeams.incrementAndGet()
        }
        return packets
    }

    private fun onDisconnect(listener: ServerGamePacketListenerImpl) {
        val state = connections.remove(listener) ?: return
        synchronized(state.lock) {
            // Connection closed: cancel pending work; no packets to a dead
            // client. Release relation/target accounting only.
            for ((_, obs) in state.targets) {
                adjustRelation(targets[obs.targetUuid], -1)
            }
            state.targets.clear()
            state.pendingInit = null
            RenderDiagnostics.virtualTeams.addAndGet(-state.overlays.size)
            state.overlays.clear()
            state.overlaysByName.clear()
        }
    }

    // =================================================================
    // Tracking lifecycle
    // =================================================================

    private fun onTrackingStart(targetPlayer: ServerPlayer?, observer: ServerPlayer) {
        if (targetPlayer == null || targetPlayer === observer) return
        val state = connections[observer.connection] ?: return
        val target = targets[targetPlayer.uuid] ?: return
        if (!running) return
        synchronized(state.lock) {
            // Tracking start fires at TAIL of ServerEntity.addPairing: the
            // target's spawn bundle is already on the wire, so ordering
            // "target spawn before label spawn" holds by construction.
            spawnLabels(observer.connection, state, target, targetPlayer)
        }
    }

    private fun onTrackingStop(targetPlayer: ServerPlayer?, observer: ServerPlayer) {
        if (targetPlayer == null || targetPlayer === observer) return
        val state = connections[observer.connection] ?: return
        val target = targets[targetPlayer.uuid] ?: return
        synchronized(state.lock) {
            val obs = state.targets.remove(targetPlayer.uuid) ?: return
            // STOP fires at HEAD of removePairing: the vehicle still exists
            // client-side, so sending the real-passengers-only list first is
            // valid; the vanilla removal follows afterwards.
            val real = targetPlayer.passengers.map { it.id }
            cleanupLabels(observer.connection, obs, targetPlayer.id, real)
            adjustRelation(target, -1)
            // Distance-based stop: keep team overlays of online players.
        }
    }

    private fun onTargetEntityReplaced(player: ServerPlayer) {        val target = targets[player.uuid] ?: return
        if (target.currentEntityId == player.id) return
        // Respawn / dimension change of the target: old entity id (and any
        // labels bound to it) are invalid. Observer states were cleaned by
        // the removal/stop packets flowing through the transform; sweep any
        // stragglers defensively, then reset the allocation cycle.
        for ((_, state) in connections) {
            synchronized(state.lock) {
                val obs = state.targets.remove(player.uuid) ?: continue
                val real = player.passengers.map { it.id }
                cleanupLabels(state.listener, obs, target.currentEntityId, real)
                adjustRelation(target, -1)
            }
        }
        invalidateTargetLabels(target)
        target.currentEntityId = player.id
        target.lastRealPassengers = emptyList()
    }

    private fun onEndTick(server: MinecraftServer) {
        // Attach any connection we have not seen yet (covers channel start
        // with existing connections).
        for (player in server.playerList.players) {
            if (!connections.containsKey(player.connection)) {
                attachConnection(player.connection, flushInit = true)
            }
        }

        // Reverse sweep: release state of connections that left the player
        // list without a DISCONNECT event reaching us yet (disconnect race:
        // a main-thread send already raced past onDisconnect). end-tick runs
        // on the main thread, so touching the connection map is safe here.
        // onDisconnect itself is idempotent (missing state -> no-op).
        if (connections.isNotEmpty()) {
            val live = HashSet<ServerGamePacketListenerImpl>(server.playerList.players.size * 2)
            for (player in server.playerList.players) live.add(player.connection)
            for (listener in connections.keys.toList()) {
                if (listener !in live) onDisconnect(listener)
            }
        }

        // Observer world generation bump: invalidates old-world sends.
        for ((listener, state) in connections) {
            val level = listener.player.level()
            if (state.lastSeenLevel == null) {
                state.lastSeenLevel = level
            } else if (state.lastSeenLevel !== level) {
                state.lastSeenLevel = level
                state.worldGeneration += 1
                // The observer's client rebuilt its world around the level
                // change. Vanilla stop events clean the pairings it knows
                // about - including backfilled ones, which since audit #1
                // only pair real tracking relations and therefore always
                // receive their STOP; the sweep below stays as a defensive
                // catcher for events lost in a dimension-change race -
                // without delete packets, for the same reason as
                // onObserverClientWorldReset: the client discarded these
                // entities together with the old world.
                synchronized(state.lock) {
                    val entries = state.targets.entries.iterator()
                    while (entries.hasNext()) {
                        val obs = entries.next().value
                        if (obs.worldGeneration != state.worldGeneration) {
                            entries.remove()
                            adjustRelation(targets[obs.targetUuid], -1)
                        }
                    }
                }
            }
        }

        // Retro-pairing retry (render batch E2): target states whose first
        // publish could not resolve the live player yet. Runs after the
        // world-generation bump so pairings created here carry the current
        // generation. Offline targets stay pending: their join is covered by
        // tracking events, and the first end-tick after join backfills the
        // remaining observers that already track the target (idempotent for
        // pairs that tracking events already built).
        for ((uuid, target) in targets) {
            if (!target.pairingBackfillPending) continue
            val player = server.playerList.getPlayer(uuid) ?: continue
            target.pairingBackfillPending = false
            backfillPairings(target, player)
        }

        // Target polls: entity id refresh, sneak state, real passengers.
        for ((uuid, target) in targets) {
            val player = server.playerList.getPlayer(uuid) ?: continue
            if (target.currentEntityId != player.id) {
                onTargetEntityReplaced(player)
            }
            if (target.observerCount.get() <= 0) continue
            val sneaking = player.isShiftKeyDown
            val realPassengers = player.passengers.map { it.id }
            val passengersChanged = realPassengers != target.lastRealPassengers
            if (sneaking != target.sneaking || passengersChanged) {
                target.sneaking = sneaking
                target.lastRealPassengers = realPassengers
                for ((listener, state) in connections) {
                    synchronized(state.lock) {
                        val obs = state.targets[uuid] ?: return@synchronized
                        if (obs.worldGeneration != state.worldGeneration) continue
                        if (sneaking != obs.sentSneaking) {
                            obs.sentSneaking = sneaking
                            sendContent(listener, obs, target, layers = LabelLayer.entries)
                        }
                        if (passengersChanged && sendMergedPassengers(listener, obs, realPassengers)) {
                            // Advance the observer cache only on a delivered
                            // mount: it is the fallback base for later
                            // unmounts and must mirror what the client
                            // actually holds (a failed merge is retried by
                            // the next passenger diff or the defensive
                            // re-merge in spawnLabels' early-exit).
                            obs.lastRealPassengers = realPassengers
                        }
                    }
                }
            }
        }
    }

    /**
     * Observer-side respawn handling (the respawned player as an observer).
     *
     * Why this exists: a respawn keeps the same ServerGamePacketListenerImpl
     * (only the ServerPlayer object is replaced), so our per-connection
     * state survives while the client rebuilds its world view around the
     * RespawnPacket. Same-dimension respawn does not swap the ServerLevel
     * instance, so the end-tick world-generation guard never fires either.
     * The old observer label states are therefore meaningless afterwards:
     * if any survived the untrack/retrack storm, the next START_TRACKING
     * would hit spawnLabels' "existing != null && same target entity id"
     * early-exit (respawn even preserves the player's entity id) and only
     * re-merge passengers - never re-sending AddEntity/metadata, leaving
     * labels permanently invisible for this observer.
     *
     * Reset mirrors "client has a fresh world" without sending anything:
     * - state.targets dropped without delete packets: the client either no
     *   longer holds those entities (fresh world) or vanilla's own
     *   RemoveEntities flow already removed them; a delete would reference
     *   entities that may not exist. observerCount is decremented per entry
     *   (virtualEntities is NOT touched: those ids belong to the shared
     *   target-side allocation and stay valid for the next spawn).
     * - overlays keep their identities but teamSent is reset, so the next
     *   property update re-creates the virtual team (idempotent client-side
     *   if it somehow survived).
     * - the source-team ledger is reset and re-seeded from the live server
     *   scoreboard, which is exactly the state the client mirrors.
     *
     * Subsequent START_TRACKING events (vanilla re-registers the new player
     * object with every TrackedEntity) then take the full spawnLabels path.
     */
    private fun onObserverClientWorldReset(player: ServerPlayer) {
        val state = connections[player.connection] ?: return
        val server = this.server ?: return
        synchronized(state.lock) {
            for ((_, obs) in state.targets) {
                adjustRelation(targets[obs.targetUuid], -1)
            }
            state.targets.clear()
            for (overlay in state.overlays.values) {
                overlay.teamSent = false
                overlay.lastSentSourceTeam = null
                overlay.lastSentSourceProps = null
            }
            state.ledger.reset()
            seedLedger(state, server)
        }
    }

    // =================================================================
    // Label spawn / cleanup primitives
    // =================================================================

    private fun spawnLabels(
        listener: ServerGamePacketListenerImpl,
        state: ConnectionRenderState,
        target: TargetRenderState,
        player: ServerPlayer,
    ) {
        val settings = this.settings
        val level: ServerLevel = player.level()
        val targetEntityId = player.id
        target.currentEntityId = targetEntityId

        // (Re)allocate shared label ids on the target side once per cycle.
        if (target.labelIds.allocateFirstLine({ level.getNextEntityId() })) {
            RenderDiagnostics.virtualEntities.addAndGet(2)
        }
        val wantSecondLine = target.snapshot.secondLine != null
        if (wantSecondLine && target.labelIds.allocateSecondLine({ level.getNextEntityId() })) {
            RenderDiagnostics.virtualEntities.incrementAndGet()
        }
        if (!wantSecondLine && target.labelIds.secondLineId != 0) {
            // Second line became empty since allocation; drop the placeholder.
            // (Observer remount is handled by publish diffs; here we only
            // invalidate the id so no future spawn references it.)
            RenderDiagnostics.virtualEntities.decrementAndGet()
            target.labelIds.secondLineId = 0
        }

        val existing = state.targets[target.uuid]
        if (existing != null && existing.targetEntityId == targetEntityId &&
            existing.worldGeneration == state.worldGeneration
        ) {
            // Already spawned on this connection in the *current* client
            // world (e.g. re-track in the same world): just re-merge
            // passengers defensively and return. Entries from an older
            // world generation must not take this path: the client rebuilt
            // its world since they were sent, so falling through to the
            // full spawn is the only way the labels come back.
            val remergeReal = player.passengers.map { it.id }
            if (sendMergedPassengers(listener, existing, remergeReal)) {
                // The cache is the fallback base for later unmounts and
                // must mirror what the client actually holds.
                existing.lastRealPassengers = remergeReal
            }
            return
        }
        if (existing != null) {
            // Stale state for an older target entity id; drop it silently
            // (the old entity is gone from the client).
            state.targets.remove(target.uuid)
            adjustRelation(target, -1)
        }

        val sneaking = target.sneaking
        val position = player.position()
        val spawnPackets = ArrayList<net.minecraft.network.protocol.Packet<*>>()
        val metadataPackets = ArrayList<net.minecraft.network.protocol.Packet<*>>()

        fun addEntity(id: Int, entityUuid: UUID) {
            spawnPackets += ClientboundAddEntityPacket(
                id,
                entityUuid,
                position.x,
                position.y,
                position.z,
                0f,
                0f,
                EntityTypes.TEXT_DISPLAY,
                0,
                Vec3.ZERO,
                0.0,
            )
        }

        addEntity(target.labelIds.foregroundId, target.labelIds.foregroundUuid)
        addEntity(target.labelIds.backgroundId, target.labelIds.backgroundUuid)
        metadataPackets += ClientboundSetEntityDataPacket(
            target.labelIds.foregroundId,
            DisplayMetadata.fullValues(LabelLayer.FIRST_FOREGROUND, target.snapshot, sneaking, settings),
        )
        metadataPackets += ClientboundSetEntityDataPacket(
            target.labelIds.backgroundId,
            DisplayMetadata.fullValues(LabelLayer.FIRST_BACKGROUND, target.snapshot, sneaking, settings),
        )
        if (wantSecondLine) {
            addEntity(target.labelIds.secondLineId, target.labelIds.secondLineUuid)
            metadataPackets += ClientboundSetEntityDataPacket(
                target.labelIds.secondLineId,
                DisplayMetadata.fullValues(LabelLayer.SECOND_LINE, target.snapshot, sneaking, settings),
            )
        }

        // One bundle keeps the init sequence atomic on the wire: adds first,
        // then full metadata, then the merged mount.
        val obs = ObserverTargetState(
            targetUuid = target.uuid,
            targetEntityId = targetEntityId,
            worldGeneration = state.worldGeneration,
            foregroundId = target.labelIds.foregroundId,
            backgroundId = target.labelIds.backgroundId,
            secondLineId = if (wantSecondLine) target.labelIds.secondLineId else 0,
        )
        val realPassengers = player.passengers.map { it.id }
        obs.lastRealPassengers = realPassengers
        obs.sentSneaking = sneaking
        obs.sentFirstLine = target.snapshot.firstLine
        obs.sentSecondLine = target.snapshot.secondLine
        obs.sentFirstLineOffset = settings.firstLineOffset
        obs.sentSecondLineOffset = settings.secondLineOffset
        obs.sentViewRangeBlocks = settings.renderDistance.blocks

        val mount = PassengerPackets.create(
            targetEntityId,
            PassengerMerge.merge(realPassengers, obs.liveLabelIds()).toIntArray(),
        )

        state.targets[target.uuid] = obs
        adjustRelation(target, +1)

        try {
            RenderPackets.sendAll(
                listener,
                spawnPackets + metadataPackets + listOf(mount),
            )
            RenderDiagnostics.entitiesSent.addAndGet(spawnPackets.size.toLong())
        } catch (t: Exception) {
            // Send failed: do not keep the labels recorded as generated.
            RenderDiagnostics.recordError("label spawn", t)
            state.targets.remove(target.uuid)
            adjustRelation(target, -1)
            // The failure may have struck mid-list: some AddEntity packets
            // can already be on the wire while the metadata/mount never
            // arrived, which would leave raw text_display entities (default
            // 200px line width, empty text) riding the player. Best-effort
            // removal of every id this spawn could have delivered makes the
            // failure self-healing; a follow-up tracking start re-spawns
            // cleanly through the full path.
            try {
                sendPacket(
                    listener,
                    ClientboundRemoveEntitiesPacket(
                        *buildList {
                            add(target.labelIds.foregroundId)
                            add(target.labelIds.backgroundId)
                            if (wantSecondLine) add(target.labelIds.secondLineId)
                        }.toIntArray(),
                    ),
                )
            } catch (t2: Exception) {
                RenderDiagnostics.recordError("label spawn rollback", t2)
            }
        }
    }

    /** Unmount (real list first) then remove label entities of one observer state. */
    private fun cleanupLabels(
        listener: ServerGamePacketListenerImpl,
        obs: ObserverTargetState,
        vehicleId: Int,
        realPassengers: List<Int>,
    ) {
        try {
            sendPacket(listener, PassengerPackets.create(vehicleId, realPassengers.toIntArray()))
            sendPacket(listener, ClientboundRemoveEntitiesPacket(*obs.liveLabelIds().toIntArray()))
        } catch (t: Exception) {
            RenderDiagnostics.recordError("label cleanup", t)
        }
    }

    private fun sendPacket(listener: ServerGamePacketListenerImpl, packet: net.minecraft.network.protocol.Packet<*>) {
        RenderPackets.send(listener, packet)
    }

    /**
     * Sends the merged mount (real passengers + live label ids) for one
     * observer state.
     *
     * @return `true` when the packet was delivered; `false` when the send
     *   failed (already recorded in diagnostics). Callers that advance
     *   observer caches on the strength of this mount must treat `false` as
     *   a failed send - swallowing it previously let the second-line ADD
     *   branch record a successful spawn whose mount the client never
     *   received (audit #6: the label entity existed but never followed the
     *   player).
     */
    private fun sendMergedPassengers(
        listener: ServerGamePacketListenerImpl,
        obs: ObserverTargetState,
        realPassengers: List<Int>,
    ): Boolean {
        return try {
            sendPacket(
                listener,
                PassengerPackets.create(
                    obs.targetEntityId,
                    PassengerMerge.merge(realPassengers, obs.liveLabelIds()).toIntArray(),
                ),
            )
            true
        } catch (t: Exception) {
            RenderDiagnostics.recordError("passenger remount", t)
            false
        }
    }

    /**
     * Best-effort rollback of a half-delivered second-line spawn: forget the
     * observer-side id and remove the entity (client-side no-op when the
     * AddEntity never arrived, verified removeEntity). The shared slot stays
     * allocated target-side so the next publish re-spawns through the ADD
     * branch with the same id (the client replaces a pre-existing entity of
     * the same id).
     */
    private fun rollbackSecondLineSpawn(
        listener: ServerGamePacketListenerImpl,
        target: TargetRenderState,
        obs: ObserverTargetState,
    ) {
        obs.secondLineId = 0
        try {
            sendPacket(listener, ClientboundRemoveEntitiesPacket(target.labelIds.secondLineId))
        } catch (t: Exception) {
            RenderDiagnostics.recordError("second line spawn rollback", t)
        }
    }

    /**
     * Sends content (text/plate/opacity/flags) metadata for [layers] and
     * advances the observer's content caches — but only for layers that were
     * actually delivered (`id != 0`).
     *
     * The caches double as the presence signal of the second-line diff (see
     * [SecondLineDiff]): writing [ObserverTargetState.sentSecondLine] from a
     * first-line-only send would make a later diff believe a second-line
     * entity exists on the client that was never spawned — or hide one that
     * still needs removal.
     */
    private fun sendContent(
        listener: ServerGamePacketListenerImpl,
        obs: ObserverTargetState,
        target: TargetRenderState,
        layers: List<LabelLayer>,
    ) {
        var firstLineDelivered = false
        var secondLineDelivered = false
        for (layer in layers) {
            val id = when (layer) {
                LabelLayer.FIRST_FOREGROUND -> obs.foregroundId
                LabelLayer.FIRST_BACKGROUND -> obs.backgroundId
                LabelLayer.SECOND_LINE -> obs.secondLineId
            }
            if (id == 0) continue
            sendPacket(
                listener,
                ClientboundSetEntityDataPacket(id, DisplayMetadata.contentValues(layer, target.snapshot, obs.sentSneaking)),
            )
            when (layer) {
                LabelLayer.FIRST_FOREGROUND, LabelLayer.FIRST_BACKGROUND -> firstLineDelivered = true
                LabelLayer.SECOND_LINE -> secondLineDelivered = true
            }
        }
        if (firstLineDelivered) obs.sentFirstLine = target.snapshot.firstLine
        if (secondLineDelivered) obs.sentSecondLine = target.snapshot.secondLine
    }

    private fun pushPlacement(
        listener: ServerGamePacketListenerImpl,
        obs: ObserverTargetState,
    ) {
        val settings = this.settings
        val firstChanged = obs.sentFirstLineOffset != settings.firstLineOffset ||
            obs.sentSecondLineOffset != settings.secondLineOffset ||
            obs.sentViewRangeBlocks != settings.renderDistance.blocks
        if (!firstChanged) return
        fun placement(layer: LabelLayer): net.minecraft.network.protocol.Packet<*>? {
            val id = when (layer) {
                LabelLayer.FIRST_FOREGROUND -> obs.foregroundId
                LabelLayer.FIRST_BACKGROUND -> obs.backgroundId
                LabelLayer.SECOND_LINE -> obs.secondLineId
            }
            if (id == 0) return null
            return ClientboundSetEntityDataPacket(id, DisplayMetadata.placementValues(layer, settings))
        }
        val packets = listOf(
            placement(LabelLayer.FIRST_FOREGROUND),
            placement(LabelLayer.FIRST_BACKGROUND),
            placement(LabelLayer.SECOND_LINE),
        ).filterNotNull()
        if (packets.isNotEmpty()) {
            try {
                RenderPackets.sendAll(listener, packets)
            } catch (t: Exception) {
                // Sent-caches below are only updated on success, so the
                // next settings pass retries; never let one listener break
                // the whole settings push loop.
                RenderDiagnostics.recordError("placement push", t)
                return
            }
        }
        obs.sentFirstLineOffset = settings.firstLineOffset
        obs.sentSecondLineOffset = settings.secondLineOffset
        obs.sentViewRangeBlocks = settings.renderDistance.blocks
    }

    /**
     * Pushes snapshot diffs to all observers currently holding labels.
     *
     * Second-line lifecycle (batch E1): the per-observer transition is routed
     * by the pure table in [SecondLineDiff] over `(obs.sentSecondLine,
     * target.snapshot.secondLine)`. That cache is the *presence* signal — it
     * must satisfy `sentSecondLine != null <=> secondLineId != 0` (see
     * [SecondLineDiff]) — so every branch advances the entity id and the cache
     * together, only after the packets were actually delivered. A failed send
     * leaves the cache untouched, which makes the next publish retry the very
     * same action instead of silently desynchronizing the client.
     *
     * Target-side slot (`target.labelIds.secondLineId`) is allocated on first
     * need (here or in [spawnLabels]) and freed by the first observer whose
     * removal drops it, balancing the `virtualEntities` gauge exactly once
     * per allocation. Reusing a still-allocated slot id is intentional: the
     * ADD branch always sends a fresh AddEntity, and the client replaces a
     * pre-existing entity of the same id (verified `ClientLevel.addEntity`),
     * so a reused id can never reference an entity any client deleted.
     */
    private fun pushSnapshot(target: TargetRenderState) {
        val settings = this.settings
        for ((listener, state) in connections) {
            synchronized(state.lock) {
                val obs = state.targets[target.uuid] ?: return@synchronized
                if (obs.worldGeneration != state.worldGeneration) return@synchronized
                val firstChanged = obs.sentFirstLine != target.snapshot.firstLine
                val secondBefore = obs.sentSecondLine
                val secondNow = target.snapshot.secondLine
                if (firstChanged) {
                    sendContent(listener, obs, target, listOf(LabelLayer.FIRST_FOREGROUND, LabelLayer.FIRST_BACKGROUND))
                }
                when (SecondLineDiff.actionOf(secondBefore, secondNow)) {
                    SecondLineAction.ADD -> {
                        // Spawn second-line entity, full metadata, then remount.
                        val player = server?.playerList?.getPlayer(target.uuid)
                            ?: return@synchronized // target offline; nothing to mount on
                        val level: ServerLevel = player.level()
                        if (target.labelIds.allocateSecondLine({ level.getNextEntityId() })) {
                            RenderDiagnostics.virtualEntities.incrementAndGet()
                        }
                        val position = player.position()
                        try {
                            sendPacket(
                                listener,
                                ClientboundAddEntityPacket(
                                    target.labelIds.secondLineId,
                                    target.labelIds.secondLineUuid,
                                    position.x,
                                    position.y,
                                    position.z,
                                    0f,
                                    0f,
                                    EntityTypes.TEXT_DISPLAY,
                                    0,
                                    Vec3.ZERO,
                                    0.0,
                                ),
                            )
                            RenderDiagnostics.entitiesSent.incrementAndGet()
                            sendPacket(
                                listener,
                                ClientboundSetEntityDataPacket(
                                    target.labelIds.secondLineId,
                                    DisplayMetadata.fullValues(LabelLayer.SECOND_LINE, target.snapshot, target.sneaking, settings),
                                ),
                            )
                            obs.secondLineId = target.labelIds.secondLineId
                            obs.sentSecondLineOffset = settings.secondLineOffset
                            val live = player.passengers.map { it.id }
                            if (sendMergedPassengers(listener, obs, live)) {
                                // Presence cache advances only after the whole
                                // spawn *including the mount* was delivered; on
                                // failure it stays null so the next publish
                                // retries this branch (with the same slot id).
                                obs.lastRealPassengers = live
                                obs.sentSecondLine = secondNow
                            } else {
                                // Audit #6: the mount packet failed after the
                                // AddEntity was already delivered. Without a
                                // mount the second-line entity never follows
                                // the player, so treat this exactly like a
                                // send failure: roll the spawn back and keep
                                // the presence cache at null for a retry.
                                rollbackSecondLineSpawn(listener, target, obs)
                            }
                        } catch (t: Exception) {
                            RenderDiagnostics.recordError("second line spawn", t)
                            // The failure may have struck mid-sequence: an
                            // AddEntity can already be on the wire with no
                            // metadata/mount behind it. Best-effort removal
                            // of the id; the slot stays allocated target-side
                            // and the next publish re-spawns through this
                            // branch with the same id.
                            rollbackSecondLineSpawn(listener, target, obs)
                        }
                    }
                    SecondLineAction.REMOVE -> {
                        // Remove from passenger list first, then remove entity.
                        val removed = obs.secondLineId
                        try {
                            obs.secondLineId = 0 // liveLabelIds() must exclude it for the remount below
                            if (sendMergedPassengers(listener, obs, obs.lastRealPassengers)) {
                                sendPacket(listener, ClientboundRemoveEntitiesPacket(removed))
                                obs.sentSecondLine = null
                                // The first observer whose removal drops the shared
                                // slot also balances the virtualEntities gauge;
                                // later observers see id 0 != removed and skip.
                                if (target.labelIds.secondLineId == removed) {
                                    target.labelIds.secondLineId = 0
                                    RenderDiagnostics.virtualEntities.decrementAndGet()
                                }
                            } else {
                                // Remount undelivered: keep the id handle and
                                // the presence cache paired so the next publish
                                // retries the removal instead of leaving a dead
                                // passenger id inside the client's mount list.
                                obs.secondLineId = removed
                            }
                        } catch (t: Exception) {
                            RenderDiagnostics.recordError("second line removal", t)
                            // Keep the id handle and the presence cache: the
                            // removal may not have been delivered, and the
                            // next publish must retry this branch rather than
                            // lose the entity id. A retried RemoveEntities is
                            // a client-side no-op when it did arrive.
                            obs.secondLineId = removed
                        }
                    }
                    SecondLineAction.UPDATE -> {
                        sendContent(listener, obs, target, listOf(LabelLayer.SECOND_LINE))
                    }
                    SecondLineAction.NONE -> {}
                }
            }
        }
    }

    // =================================================================
    // Accounting helpers
    // =================================================================

    private fun adjustRelation(target: TargetRenderState?, delta: Int) {
        if (target != null) target.observerCount.addAndGet(delta)
        if (delta != 0) {
            RenderDiagnostics.trackingRelations.addAndGet(delta)
        }
    }

    private companion object {
        val SESSION_STAMPS = AtomicLong(0)
    }
}
