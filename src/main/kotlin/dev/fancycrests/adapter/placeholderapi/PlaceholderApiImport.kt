package dev.fancycrests.adapter.placeholderapi

import eu.pb4.placeholders.api.Placeholders
import eu.pb4.placeholders.api.ServerPlaceholderContext
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerPlayer

/** Only loaded after Fabric confirms that Text Placeholder API is installed. */
internal object PlaceholderApiImport {
    fun resolve(player: ServerPlayer, token: String): Component? {
        val pieces = token.split(' ', limit = 2)
        if (':' !in pieces[0]) return null
        val identifier = Identifier.tryParse(pieces[0]) ?: return null
        val argument = pieces.getOrNull(1)
        val context = ServerPlaceholderContext.of(player)
        val result = when {
            Placeholders.getServerPlaceholder(identifier) != null ->
                Placeholders.parseServerPlaceholder(identifier, argument, context)
            Placeholders.getCommonPlaceholder(identifier) != null ->
                Placeholders.parseCommonPlaceholder(identifier, argument, context)
            else -> return null
        }
        return if (result.isValid) result.component() else null
    }
}
