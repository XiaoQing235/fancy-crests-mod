package dev.fancycrests.core.channel

import dev.fancycrests.api.PlayerTitleState
import dev.fancycrests.core.ServerAdapter
import dev.fancycrests.core.event.TitleChangeEventPost
import dev.fancycrests.core.event.TitleEventBus
import dev.fancycrests.core.resolve.SnapshotResolver
import java.util.UUID
import net.minecraft.network.chat.Component

/**
 * Independent, individually switchable export channels for the resolved
 * display (plan chapter 1 "可选功能的边界"): chat sender decoration and the
 * tab-list display name. The channels never implicitly enable or disable each
 * other, and neither depends on the head-render channel: both read the same
 * shared first-line resolution produced by [SnapshotResolver].
 *
 * Lifecycle: [apply] is called on server start and after every successful
 * config replacement (channel switches take effect on reload); join/quit
 * hooks keep the tab list correct as players come and go; [stop] tears both
 * channels down (mod shutdown). All methods run on the main thread.
 */
class ChannelManager(
    private val adapter: ServerAdapter,
    private val resolver: SnapshotResolver,
    private val stateOf: (UUID) -> PlayerTitleState,
    eventBus: TitleEventBus,
) {
    private val chat = ChatChannel(::freshFirstLine)
    private val tab = TabListChannel(adapter, ::freshFirstLine)

    init {
        // First-line changes are event-driven; refresh the tab override so
        // the list stays in sync with equips/unequips/nicknames/rechecks.
        eventBus.addPostListener { event -> onPostEvent(event) }
    }

    /** Applies the channel switches of [config]; idempotent. Main thread. */
    fun apply(config: dev.fancycrests.core.config.FancyCrestsConfig) {
        if (config.channels.chat) chat.enable() else chat.disable()
        if (config.channels.tabList) tab.enable() else tab.disable()
    }

    /** Join hook from the integration layer; applies the tab override. */
    fun onPlayerJoin(uuid: UUID) {
        tab.applyTo(uuid)
    }

    /** Quit hook from the integration layer. */
    fun onPlayerQuit(uuid: UUID) {
        tab.drop(uuid)
    }

    /** Tears both channels down and restores vanilla behaviour. */
    fun stop() {
        chat.disable()
        tab.disable()
    }

    private fun onPostEvent(event: TitleChangeEventPost) {
        if (!tab.enabled) return
        when (event.type) {
            dev.fancycrests.core.event.TitleOperationType.EQUIP,
            dev.fancycrests.core.event.TitleOperationType.UNEQUIP,
            dev.fancycrests.core.event.TitleOperationType.NICKNAME_SET,
            dev.fancycrests.core.event.TitleOperationType.NICKNAME_RESET,
            dev.fancycrests.core.event.TitleOperationType.RECHECK,
            -> tab.applyTo(event.uuid)
            else -> Unit
        }
    }

    /**
     * Freshly resolves the first line for [uuid], or null when the player has
     * no fancy content (no equipped titles and no nickname) — vanilla display
     * stays untouched in that case.
     */
    private fun freshFirstLine(uuid: UUID): Component? {
        val state = stateOf(uuid)
        if (state.prefixes.isEmpty() && state.suffixes.isEmpty() && state.nickname == null) return null
        val profileName = adapter.profileName(uuid) ?: return null
        val resolved = resolver.resolve(state, profileName, adapter.dimensionKey(uuid))
        return resolved.firstLine
    }
}
