package dev.fancycrests.core

import java.util.UUID

/**
 * Core-side abstraction over the running Minecraft server.
 *
 * Core services never touch MinecraftServer directly; the integration layer
 * injects an implementation (see [MinecraftServerAdapter]) when the server
 * starts. Unit-level code can supply a fake implementation.
 *
 * All methods may be called from any thread unless noted otherwise.
 */
interface ServerAdapter {
    /**
     * Runs [runnable] on the server main thread. If the caller is already on
     * the main thread the runnable may be executed immediately.
     */
    fun execute(runnable: Runnable)

    /** Current server tick count (monotonic). */
    fun currentTick(): Long

    /** UUIDs of all online players. */
    fun onlinePlayerUuids(): List<UUID>

    /**
     * Current GameProfile name of the given player, or null when the player is
     * not online / unknown. Used as the fallback display name.
     */
    fun profileName(uuid: UUID): String?

    /**
     * Resolves an exact (case-insensitive) online player name to its UUID, or
     * null when no online player matches. Nicknames are never consulted.
     */
    fun playerNameExact(name: String): UUID?

    /**
     * Vanilla operator level of the player (0..4), 0 when unknown/offline.
     * Level 2 corresponds to PermissionLevel.GAMEMASTERS.
     */
    fun operatorLevel(uuid: UUID): Int

    /**
     * Dimension key of the player's current level (for example
     * "minecraft:the_nether"), or null when offline.
     */
    fun dimensionKey(uuid: UUID): String?

    /**
     * All player names currently known to the server, mapped from the
     * lowercase name to the UUID. Used by nickname collision checks ("must not
     * occupy another known player's real name").
     *
     * 26.2 limitation (verified with javap against minecraft-merged.jar):
     * this covers *online* players only. The vanilla name cache is reachable
     * (MinecraftServer.services().nameToIdCache(): UserNameToIdResolver), but
     * it has no public enumeration, and its only name-keyed read,
     * get(String), is neither read-only nor cheap: it removes expired entries
     * and, on a miss, performs a *blocking* Mojang API lookup — with offline
     * profile synthesis enabled it would even report every candidate
     * nickname as occupied. get(UUID) is local but UUID-keyed, which cannot
     * answer "does anyone own this name". A nickname can therefore be set to
     * the real name of a player who is offline at that moment. No reflection
     * is used to work around this.
     */
    fun knownPlayerNames(): Map<String, UUID>

    /**
     * Sends a system chat message to the online player [uuid]; a noop when
     * the player is offline. Any thread (dispatched to the main thread).
     */
    fun sendSystemMessage(uuid: UUID, message: net.minecraft.network.chat.Component)

    /**
     * Broadcasts a tab-list display-name override for [uuid] to every online
     * client; `null` restores the default profile name. Main thread.
     */
    fun broadcastTabListDisplayName(uuid: UUID, displayName: net.minecraft.network.chat.Component?)

    /**
     * Registers a callback executed at the end of every server tick. The
     * returned handle deactivates the callback when closed (fabric events
     * cannot unregister, so the registration stays but becomes a noop).
     */
    fun registerEndTickHandler(handler: () -> Unit): AutoCloseable
}

/**
 * Production [ServerAdapter] over a live [net.minecraft.server.MinecraftServer].
 * Constructed by the integration layer inside ServerLifecycleEvents.SERVER_STARTED.
 */
class MinecraftServerAdapter(private val server: net.minecraft.server.MinecraftServer) : ServerAdapter {

    override fun execute(runnable: Runnable) {
        server.executeIfPossible(runnable)
    }

    override fun currentTick(): Long = server.tickCount.toLong()

    override fun onlinePlayerUuids(): List<UUID> =
        server.playerList.players.map { it.gameProfile.id() }

    override fun profileName(uuid: UUID): String? =
        server.playerList.getPlayer(uuid)?.gameProfile?.name()

    override fun playerNameExact(name: String): UUID? =
        server.playerList.getPlayerByName(name)?.gameProfile?.id()

    override fun operatorLevel(uuid: UUID): Int {
        val player = server.playerList.getPlayer(uuid) ?: return 0
        val permissions = player.permissions()
        return when {
            permissions == net.minecraft.server.permissions.PermissionSet.ALL_PERMISSIONS -> 4
            permissions is net.minecraft.server.permissions.LevelBasedPermissionSet ->
                permissions.level().id()
            else -> 0
        }
    }

    override fun dimensionKey(uuid: UUID): String? {
        val player = server.playerList.getPlayer(uuid) ?: return null
        return player.level().dimension().identifier().toString()
    }

    override fun knownPlayerNames(): Map<String, UUID> {
        // Online players only: the vanilla user cache
        // (MinecraftServer.services().nameToIdCache()) cannot supplement this
        // map — see the interface KDoc for the 26.2 evidence (no public
        // enumeration; get(String) mutates the cache and blocks on the Mojang
        // API for misses, synthesizing offline profiles on offline servers).
        val names = HashMap<String, UUID>()
        for (player in server.playerList.players) {
            val profile = player.gameProfile
            names[profile.name().lowercase()] = profile.id()
        }
        return names
    }

    override fun sendSystemMessage(uuid: UUID, message: net.minecraft.network.chat.Component) {
        execute { server.playerList.getPlayer(uuid)?.sendSystemMessage(message) }
    }

    override fun broadcastTabListDisplayName(
        uuid: UUID,
        displayName: net.minecraft.network.chat.Component?,
    ) {
        val player = server.playerList.getPlayer(uuid) ?: return
        // 26.2 exposes no ServerPlayer tab-list display name setter and the
        // player-info packet constructors only accept live ServerPlayers (the
        // entry then reads getTabListDisplayName() == null). The public record
        // Entry constructor allows custom display names, but the packet's own
        // entries field is private, so the packet is rebuilt through its
        // public STREAM_CODEC: hand-write the wire format (enum set, entry
        // count, UUID, nullable component) and decode it into a packet.
        val buf = net.minecraft.network.RegistryFriendlyByteBuf(
            io.netty.buffer.Unpooled.buffer(),
            server.registryAccess(),
        )
        try {
            buf.writeEnumSet(
                java.util.EnumSet.of(
                    net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME,
                ),
                net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket.Action::class.java,
            )
            buf.writeVarInt(1) // one entry
            buf.writeUUID(player.gameProfile.id())
            net.minecraft.network.FriendlyByteBuf.writeNullable(
                buf,
                displayName,
                net.minecraft.network.chat.ComponentSerialization.TRUSTED_STREAM_CODEC,
            )
            val packet = net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket.STREAM_CODEC.decode(buf)
            server.playerList.broadcastAll(packet)
        } finally {
            buf.release()
        }
    }

    override fun registerEndTickHandler(handler: () -> Unit): AutoCloseable {
        val active = java.util.concurrent.atomic.AtomicBoolean(true)
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register { _ ->
            if (active.get()) handler()
        }
        return AutoCloseable { active.set(false) }
    }
}
