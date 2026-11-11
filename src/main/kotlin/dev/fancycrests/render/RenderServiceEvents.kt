package dev.fancycrests.render

import net.fabricmc.fabric.api.entity.event.v1.ServerEntityLevelChangeEvents
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.networking.v1.EntityTrackingEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer

/**
 * Module-level Fabric event registration for the head render channel
 * (audit II-5).
 *
 * Fabric `Event`s have no unregister: registering inside
 * [RenderService.start] accumulated one handler closure per start/stop cycle
 * (config toggles `channels.headRender` off and on across reloads), each
 * holding a strong reference to its — long stopped — service instance
 * through the old `isCurrent` guard. This object instead registers every
 * event exactly once, when it is first touched, and forwards to the
 * currently active service only:
 *
 *  - [RenderService.start] publishes itself as active;
 *  - [RenderService.stop] clears the slot (only if it still points at that
 *    instance — a stale stop of a superseded service must not deactivate
 *    the newer one);
 *  - with no active service every callback is a no-op, matching the old
 *    `isCurrent` semantics for stopped services.
 *
 * [HeadRenderChannel] keeps creating a fresh [RenderService] per start; only
 * the Fabric registration moved from the instance into this object.
 * Lifecycle methods (start/stop) and Fabric event callbacks all run on the
 * server main thread; the `active` slot is still `@Volatile` because the
 * transform path on other threads reads the service through
 * [RenderPackets] and class initialization may happen from any thread.
 */
internal object RenderServiceEvents {

    @Volatile
    var active: RenderService? = null
        private set

    init {
        EntityTrackingEvents.START_TRACKING.register { entity, player ->
            active?.onTrackingStart(entity as? ServerPlayer, player)
        }
        EntityTrackingEvents.STOP_TRACKING.register { entity, player ->
            active?.onTrackingStop(entity as? ServerPlayer, player)
        }
        ServerPlayConnectionEvents.JOIN.register { handler, _, server ->
            active?.onPlayConnectionJoin(handler, server)
        }
        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ ->
            active?.onPlayConnectionDisconnect(handler)
        }
        ServerPlayerEvents.AFTER_RESPAWN.register { oldPlayer, newPlayer, _ ->
            // Both sides of the same respawn: the respawned player is
            // an observer whose client rebuilt its world view, and (as
            // a target) keeps its entity id (respawn preserves it), so
            // only the allocation sweep runs for it.
            active?.onObserverClientWorldReset(newPlayer)
            active?.onTargetEntityReplaced(oldPlayer)
        }
        ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL.register { player, _, _ ->
            active?.onTargetEntityReplaced(player)
        }
        ServerTickEvents.END_SERVER_TICK.register { server ->
            active?.onServerTick(server)
        }
    }

    /** Publishes [service] as the receiver of all forwarded events. */
    fun activate(service: RenderService) {
        active = service
    }

    /** Clears the slot when it still points at [service]; a no-op otherwise. */
    fun deactivate(service: RenderService) {
        if (active === service) active = null
    }
}
