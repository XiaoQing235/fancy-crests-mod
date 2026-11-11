package dev.fancycrests.core.channel

import dev.fancycrests.api.PlayerTitleState
import dev.fancycrests.core.ServerAdapter
import dev.fancycrests.core.event.TitleChangeEventPost
import dev.fancycrests.core.event.TitleEventBus
import dev.fancycrests.core.resolve.SnapshotPublisher
import dev.fancycrests.core.resolve.SnapshotResolver
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityLevelChangeEvents
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
 * hooks keep the tab list correct as players come and go; dimension changes
 * re-resolve the mover so dimension-conditioned second-line rules follow
 * them; [stop] tears both channels down (mod shutdown). All methods run on
 * the main thread.
 */
class ChannelManager(
    private val adapter: ServerAdapter,
    private val resolver: SnapshotResolver,
    private val stateOf: (UUID) -> PlayerTitleState,
    eventBus: TitleEventBus,
    /**
     * Publishes re-resolved snapshots when a player changes dimension (the
     * matching second-line rule may flip). Optional so the wiring can land
     * separately; when null the dimension-change republish is skipped.
     */
    private val publisher: SnapshotPublisher? = null,
) {
    private val chat = ChatChannel(::freshFirstLine)
    private val tab = TabListChannel(adapter, ::freshFirstLine)

    /** Deactivates the fabric dimension hook after [stop]; fabric events cannot unregister. */
    private val active = AtomicBoolean(true)

    init {
        // First-line changes are event-driven; refresh the tab override so
        // the list stays in sync with equips/unequips/nicknames/rechecks.
        eventBus.addPostListener { event -> onPostEvent(event) }
        // Dimension conditions of second-line rules must be re-evaluated for
        // the mover (audit #7): re-resolve and publish only real changes
        // (publishIfChanged), never a bare publish. The handler follows the
        // RenderService pattern: guarded by [active], a no-op after stop().
        ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL.register { player, _, _ ->
            if (active.get()) onPlayerChangedDimension(player.uuid)
        }    }

    /** Applies the channel switches of [config]; idempotent. Main thread. */
    fun apply(config: dev.fancycrests.core.config.FancyCrestsConfig) {
        if (config.channels.chat) chat.enable() else chat.disable()
        if (config.channels.tabList) {
            if (tab.enabled) {
                // Already enabled: enable() would short-circuit (`if
                // (enabled) return`) and the tab list would keep the
                // previous title texts/template after a reload (audit #6b).
                tab.reapplyAll()
            } else {
                tab.enable()
            }
        } else {
            tab.disable()
        }
    }

    /** Join hook from the integration layer; applies the tab override. */
    fun onPlayerJoin(uuid: UUID) {
        tab.applyTo(uuid)
        // The joiner's client built its tab list from vanilla state and
        // missed every override broadcast that predates the join: push the
        // other overridden players' entries to (re)reach the new observer
        // (audit #6a).
        tab.resyncOverridden()
    }

    /** Quit hook from the integration layer. */
    fun onPlayerQuit(uuid: UUID) {
        tab.drop(uuid)
    }

    /**
     * Dimension change of [uuid]: the matching second-line rule may have
     * flipped with the dimension, so the player is re-resolved and the
     * snapshot republished only when the content actually changed (audit #7).
     * No-op once stopped. Exposed internal for the thin fabric hook; main
     * thread.
     */
    internal fun onPlayerChangedDimension(uuid: UUID) {
        if (!active.get()) return
        val publisher = this.publisher ?: return
        publisher.publishIfChanged(stateOf(uuid))
    }

    /** Tears both channels down and restores vanilla behaviour. */
    fun stop() {
        active.set(false)
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
