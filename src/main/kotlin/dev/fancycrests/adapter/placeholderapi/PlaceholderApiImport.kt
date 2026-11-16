package dev.fancycrests.adapter.placeholderapi

import eu.pb4.placeholders.api.Placeholders
import eu.pb4.placeholders.api.ServerPlaceholderContext
import dev.fancycrests.core.config.LegacyTextParser
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerPlayer

/** Only loaded after Fabric confirms that Text Placeholder API is installed. */
internal object PlaceholderApiImport {
    fun resolve(player: ServerPlayer, token: String): Component? {
        val pieces = token.split(' ', limit = 2)
        val context = ServerPlaceholderContext.of(player)
        val identifier = if (':' in pieces[0]) Identifier.tryParse(pieces[0]) else null
        val result = when {
            identifier != null && Placeholders.getServerPlaceholder(identifier) != null ->
                Placeholders.parseServerPlaceholder(identifier, pieces.getOrNull(1), context)
            identifier != null && Placeholders.getCommonPlaceholder(identifier) != null ->
                Placeholders.parseCommonPlaceholder(identifier, pieces.getOrNull(1), context)
            else -> null
        }
        if (result != null && result.isValid) {
            // TAB's Fabric expansion returns legacy codes inside a literal
            // string; other providers may return structured, styled Components.
            return if (pieces[0].startsWith("tab:")) LegacyTextParser.parse(result.component().string)
                else result.component()
        }

        // TAB exposes its own variable registry as this Fabric placeholder.
        // It returns the original token when its providers cannot resolve it.
        val tab = Identifier.tryParse("tab:placeholder") ?: return null
        if (Placeholders.getServerPlaceholder(tab) == null) return null
        val tabResult = Placeholders.parseServerPlaceholder(tab, token, context)
        if (!tabResult.isValid) return null
        val text = tabResult.component().string
        return if (text == "%$token%") null else LegacyTextParser.parse(text)
    }
}
