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
 *
 * Fabric event-chain contract: the allow hook is invoked from
 * fabric-message-api's PlayerListMixin, which injects at the *HEAD* of
 * PlayerList.broadcastChatMessage(message, sender, bound) and cancels it when
 * a handler returns false. Re-broadcasting synchronously inside the handler
 * would therefore nest a complete second ALLOW/CHAT event chain while the
 * outer one is still undecided: handlers registered before this one would
 * observe the message twice, and a false returned only in the nested pass
 * would drop the message for everyone (the outer pass would still cancel the
 * vanilla broadcast). The re-broadcast is instead deferred to the next
 * main-thread task (server.execute), by which time the outer chain has fully
 * returned and the nested broadcast is a fresh, independent invocation.
 * MinecraftServer.execute enqueues rather than runs inline while a drained
 * task is executing — and the chat path is always a drained task, because
 * ServerGamePacketListenerImpl.tryHandleChat dispatches through
 * server.execute. Same-tick tasks run in enqueue order, so multiple decorated
 * messages keep their order.
 *
 * Residual limitation (inherent to the cancel-and-replay pattern): handlers
 * registered before this one still see every decorated message twice — once
 * in the original pass (which we cancel) and once in our re-broadcast pass.
 * Handlers registered after this one only see the re-broadcast pass, because
 * the original pass ends with our cancellation. Any mod that *consumes* chat
 * in an ALLOW handler must therefore be idempotent; this is the documented
 * cost of name decoration via the message API's allow hook.
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
            val server = sender.level().getServer()
            val bound = ChatType.bind(ChatType.CHAT, server.registryAccess(), firstLine)
            // Cancel first, replay later: the deferral guarantees the outer
            // event chain has concluded before our broadcast re-enters it.
            // The guard is set inside the task because the deferred
            // re-broadcast itself fires the event chain again (freshly), and
            // our own handler must pass it through untouched.
            server.execute {
                rebroadcasting = true
                try {
                    server.playerList.broadcastChatMessage(message, sender, bound)
                } finally {
                    rebroadcasting = false
                }
            }
            // Cancel the vanilla broadcast; the deferred task delivers the
            // same message (signatures untouched) with the decorated sender
            // name.
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
