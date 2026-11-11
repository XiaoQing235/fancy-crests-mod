package dev.fancycrests.core.channel

import net.fabricmc.fabric.api.entity.event.v1.ServerEntityLevelChangeEvents
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents

/**
 * Module-level Fabric event registration for the export channels (audit
 * II-5). Fabric `Event`s have no unregister: registering inside
 * [ChatChannel]'s constructor (or [ChannelManager]'s init) accumulated one
 * handler closure per instance across server start/stop cycles, each holding
 * a strong reference to its instance through the `enabled`/`active` guards.
 *
 * These objects register their event exactly once, when first touched, and
 * forward to the currently active instance only. Instances publish
 * themselves on construction and clear the slot on [ChannelManager.stop];
 * the chat channel additionally passes every message through unchanged
 * while disabled (`enabled == false`), so an activated-but-disabled channel
 * behaves exactly like an absent one.
 *
 * All callbacks and lifecycle transitions run on the server main thread;
 * `active` is `@Volatile` purely for safe publication at class-init time.
 */
private object ChannelEventForwarders {

    /** Forwards ALLOW_CHAT_MESSAGE to the active [ChatChannel]. */
    object Chat {
        @Volatile
        var active: ChatChannel? = null

        init {
            ServerMessageEvents.ALLOW_CHAT_MESSAGE.register { message, sender, _ ->
                val channel = active ?: return@register true
                channel.allowChatMessage(message, sender)
            }
        }
    }

    /** Forwards AFTER_PLAYER_CHANGE_LEVEL to the active [ChannelManager]. */
    object LevelChange {
        @Volatile
        var active: ChannelManager? = null

        init {
            ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL.register { player, _, _ ->
                active?.onPlayerChangedDimension(player.uuid)
            }
        }
    }
}

/** Publication slot for the chat channel's allow hook (see [ChannelEventForwarders]). */
internal fun activateChatChannel(channel: ChatChannel) {
    ChannelEventForwarders.Chat.active = channel
}

/** The chat channel currently receiving forwarded allow-chat events. */
internal fun activeChatChannel(): ChatChannel? = ChannelEventForwarders.Chat.active

/** Publication slot for the channel manager's dimension hook (see [ChannelEventForwarders]). */
internal fun activateChannelManager(manager: ChannelManager) {
    ChannelEventForwarders.LevelChange.active = manager
}

/** The channel manager currently receiving forwarded level-change events. */
internal fun activeChannelManager(): ChannelManager? = ChannelEventForwarders.LevelChange.active

/** Clears the manager slot when it still points at [manager]; a no-op otherwise. */
internal fun deactivateChannelManager(manager: ChannelManager) {
    if (ChannelEventForwarders.LevelChange.active === manager) {
        ChannelEventForwarders.LevelChange.active = null
    }
}
