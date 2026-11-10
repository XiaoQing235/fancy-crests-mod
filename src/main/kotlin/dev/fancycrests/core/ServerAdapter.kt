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
     */
    fun knownPlayerNames(): Map<String, UUID>
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
        val names = HashMap<String, UUID>()
        for (player in server.playerList.players) {
            val profile = player.gameProfile
            names[profile.name().lowercase()] = profile.id()
        }
        return names
    }
}
