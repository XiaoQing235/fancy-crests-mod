package dev.fancycrests.core.channel

import dev.fancycrests.core.ServerAdapter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import net.minecraft.network.chat.Component

/**
 * Tab-list channel: shows each online player's resolved first line as the
 * tab-list display name (prefixes + effective name + suffixes).
 *
 * 26.2 has no `ServerPlayer.setTabListDisplayName`; the override is applied
 * by broadcasting a `ClientboundPlayerInfoUpdatePacket` with the
 * UPDATE_DISPLAY_NAME action (built in
 * [ServerAdapter.broadcastTabListDisplayName] through the packet's public
 * stream codec). Restoring sends the same action with a null name, which
 * makes clients fall back to the profile name.
 *
 * The channel remembers which targets currently carry an override so a
 * disable/reload can restore exactly those entries. All methods run on the
 * main thread.
 */
class TabListChannel(
    private val adapter: ServerAdapter,
    private val firstLineOf: (UUID) -> Component?,
) {

    @Volatile
    var enabled: Boolean = false
        private set

    private val overridden = ConcurrentHashMap.newKeySet<UUID>()

    /** Enables the channel and applies overrides to everyone online. */
    fun enable() {
        if (enabled) return
        enabled = true
        for (uuid in adapter.onlinePlayerUuids()) {
            applyTo(uuid)
        }
    }

    /** Disables the channel and restores every overridden entry. */
    fun disable() {
        if (!enabled) return
        enabled = false
        for (uuid in overridden.toList()) {
            adapter.broadcastTabListDisplayName(uuid, null)
        }
        overridden.clear()
    }

    /** (Re-)applies the override for [uuid]; clears it when there is no fancy content. */
    fun applyTo(uuid: UUID) {
        if (!enabled) return
        val line = firstLineOf(uuid)
        if (line == null) {
            if (overridden.remove(uuid)) {
                adapter.broadcastTabListDisplayName(uuid, null)
            }
            return
        }
        adapter.broadcastTabListDisplayName(uuid, line)
        overridden.add(uuid)
    }

    /** Forgets a leaving player (clients drop the tab entry themselves). */
    fun drop(uuid: UUID) {
        overridden.remove(uuid)
    }
}
