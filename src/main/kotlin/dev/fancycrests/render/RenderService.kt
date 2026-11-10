package dev.fancycrests.render

import dev.fancycrests.api.DisplaySnapshot
import dev.fancycrests.api.RenderSettings
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
        ServerPlayerEvents.AFTER_RESPAWN.register { oldPlayer, _, _ ->
            if (isCurrent()) onTargetEntityReplaced(oldPlayer)
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
            targets[snapshot.target] = TargetRenderState(
                uuid = snapshot.target,
                snapshot = snapshot,
                currentEntityId = player?.id ?: -1,
            )
            RenderDiagnostics.targets.incrementAndGet()
            return
        }
        target.snapshot = snapshot
        pushSnapshot(target)
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

    private fun serverOf(listener: ServerGamePacketListenerImpl): MinecraftServer? =
        listener.player.level().server

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
                if (!init.isNullOrEmpty()) RenderPackets.sendAll(listener, init)
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

    private fun onTargetEntityReplaced(player: ServerPlayer) {
        val target = targets[player.uuid] ?: return
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

        // Observer world generation bump: invalidates old-world sends.
        for ((listener, state) in connections) {
            val level = listener.player.level()
            if (state.lastSeenLevel == null) {
                state.lastSeenLevel = level
            } else if (state.lastSeenLevel !== level) {
                state.lastSeenLevel = level
                state.worldGeneration += 1
            }
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
                        if (passengersChanged) {
                            obs.lastRealPassengers = realPassengers
                            sendMergedPassengers(listener, obs, realPassengers)
                        }
                    }
                }
            }
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
        if (existing != null && existing.targetEntityId == targetEntityId) {
            // Already spawned on this connection (e.g. re-track in same
            // world): just re-merge passengers defensively and return.
            existing.lastRealPassengers = player.passengers.map { it.id }
            sendMergedPassengers(listener, existing, existing.lastRealPassengers)
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
        } catch (t: Throwable) {
            // Send failed: do not keep the labels recorded as generated.
            RenderDiagnostics.recordError("label spawn", t)
            state.targets.remove(target.uuid)
            adjustRelation(target, -1)
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
        } catch (t: Throwable) {
            RenderDiagnostics.recordError("label cleanup", t)
        }
    }

    private fun sendPacket(listener: ServerGamePacketListenerImpl, packet: net.minecraft.network.protocol.Packet<*>) {
        RenderPackets.send(listener, packet)
    }

    private fun sendMergedPassengers(listener: ServerGamePacketListenerImpl, obs: ObserverTargetState, realPassengers: List<Int>) {
        try {
            sendPacket(
                listener,
                PassengerPackets.create(
                    obs.targetEntityId,
                    PassengerMerge.merge(realPassengers, obs.liveLabelIds()).toIntArray(),
                ),
            )
        } catch (t: Throwable) {
            RenderDiagnostics.recordError("passenger remount", t)
        }
    }

    private fun sendContent(
        listener: ServerGamePacketListenerImpl,
        obs: ObserverTargetState,
        target: TargetRenderState,
        layers: List<LabelLayer>,
    ) {
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
        }
        obs.sentFirstLine = target.snapshot.firstLine
        obs.sentSecondLine = target.snapshot.secondLine
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
            RenderPackets.sendAll(listener, packets)
        }
        obs.sentFirstLineOffset = settings.firstLineOffset
        obs.sentSecondLineOffset = settings.secondLineOffset
        obs.sentViewRangeBlocks = settings.renderDistance.blocks
    }

    /** Pushes snapshot diffs to all observers currently holding labels. */
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
                when {
                    secondBefore == null && secondNow != null -> {
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
                            obs.lastRealPassengers = live
                            sendMergedPassengers(listener, obs, live)
                        } catch (t: Throwable) {
                            RenderDiagnostics.recordError("second line spawn", t)
                            obs.secondLineId = 0
                        }
                    }
                    secondBefore != null && secondNow == null -> {
                        // Remove from passenger list first, then remove entity.
                        try {
                            val removed = obs.secondLineId
                            obs.secondLineId = 0
                            sendMergedPassengers(listener, obs, obs.lastRealPassengers)
                            sendPacket(listener, ClientboundRemoveEntitiesPacket(removed))
                            if (target.labelIds.secondLineId == removed) {
                                target.labelIds.secondLineId = 0
                            }
                        } catch (t: Throwable) {
                            RenderDiagnostics.recordError("second line removal", t)
                        }
                    }
                    secondBefore != secondNow -> {
                        sendContent(listener, obs, target, listOf(LabelLayer.SECOND_LINE))
                    }
                    else -> {}
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
