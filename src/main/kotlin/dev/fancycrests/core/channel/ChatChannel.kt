package dev.fancycrests.core.channel

import java.util.UUID
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents
import net.minecraft.network.chat.ChatType
import net.minecraft.network.chat.Component

/**
 * Chat channel: decorates the *sender display name* of player chat with the
 * resolved first line (prefixes + effective name + suffixes, per the admin
 * template) while leaving the message text and its signatures untouched.
 *
 * Implementation note (26.2 API reality): the vanilla chat line renders as
 * `<bound-name> content` where the bound name comes from
 * `ChatType.bind(ChatType.CHAT, player)` → `player.getDisplayName()`, and
 * fabric-message-api's [net.fabricmc.fabric.api.message.v1.ServerMessageDecoratorEvent]
 * can only decorate the *content*. Replacing the name therefore uses the
 * message API's allow hook: cancel the vanilla broadcast and re-broadcast the
 * identical [net.minecraft.network.chat.PlayerChatMessage] (signatures,
 * decorated unsigned content and filter state preserved) with a custom
 * `ChatType.Bound` built from the first-line component. `ChatType.Bound` is a
 * public record and `PlayerList.broadcastChatMessage(message, sender, bound)`
 * is public, so no mixin is needed.
 *
 * The hook is registered once; while disabled it passes every message through
 * unchanged ("通道关闭完全不挂钩子"). A re-entrancy guard stops our own
 * re-broadcast from firing the hook again.
 */
class ChatChannel(private val firstLineOf: (UUID) -> Component?) {

    @Volatile
    var enabled: Boolean = false
        private set

    /** Main-thread only; set while our own re-broadcast is in flight. */
    private var rebroadcasting = false

    init {
        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register { message, sender, _ ->
            if (!enabled || rebroadcasting) return@register true
            val firstLine = firstLineOf(sender.gameProfile.id()) ?: return@register true
            val server = sender.server
            val bound = ChatType.bind(ChatType.CHAT, server.registryAccess(), firstLine)
            rebroadcasting = true
            try {
                server.playerList.broadcastChatMessage(message, sender, bound)
            } finally {
                rebroadcasting = false
            }
            // Cancel the vanilla broadcast; ours already delivered the same
            // message with the decorated sender name.
            false
        }
    }

    fun enable() {
        enabled = true
    }

    fun disable() {
        enabled = false
    }
}
