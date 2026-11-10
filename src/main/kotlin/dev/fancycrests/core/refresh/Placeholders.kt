package dev.fancycrests.core.refresh

import dev.fancycrests.api.PlaceholderKey
import dev.fancycrests.api.PlaceholderRequest
import dev.fancycrests.api.PlaceholderResolver
import dev.fancycrests.api.PlaceholderService
import dev.fancycrests.api.PlayerTitleState
import dev.fancycrests.api.StandardPlaceholders
import dev.fancycrests.api.TitleSlot
import dev.fancycrests.core.catalog.CatalogService
import dev.fancycrests.core.config.FancyCrestsConfig
import dev.fancycrests.core.config.LegacyTextParser
import dev.fancycrests.core.config.TemplateSegmentType
import dev.fancycrests.core.resolve.PublishedPlayerView
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import net.minecraft.network.chat.Component

/**
 * Thread-safe registry of built-in placeholders. Register/unregister are
 * documented main-thread operations (snapshot consumers hot-swap them on
 * reload); resolve may be called from any thread and only reads the immutable
 * resolver table.
 */
class PlaceholderServiceImpl : PlaceholderService {
    private val resolvers = ConcurrentHashMap<PlaceholderKey, PlaceholderResolver>()

    override fun register(key: PlaceholderKey, resolver: PlaceholderResolver) {
        resolvers[key] = resolver
    }

    override fun unregister(key: PlaceholderKey) {
        resolvers.remove(key)
    }

    override fun resolve(key: PlaceholderKey, request: PlaceholderRequest): Component? =
        resolvers[key]?.resolve(key, request)

    override fun registeredKeys(): List<PlaceholderKey> =
        resolvers.keys.sortedBy { it.value }
}

/**
 * Registers the four built-in resolvers ([StandardPlaceholders]) into
 * [service]. All resolvers read exclusively from immutable published state:
 *  - `%fancycrests:prefix%` / `%fancycrests:suffix%`: the equipped titles in
 *    equip order, joined with the separator configured for the matching
 *    first-line template segment (null when nothing is equipped);
 *  - `%fancycrests:nickname%`: the nickname, else the current profile name
 *    captured in the published view (null when neither is known);
 *  - `%fancycrests:belowname%`: the last published second line (null while
 *    none exists); returns null while that very line is being resolved to
 *    break the refresh cycle.
 */
fun registerStandardPlaceholders(
    service: PlaceholderService,
    engine: TemplateEngine,
    stateOf: (UUID) -> PlayerTitleState,
    viewOf: (UUID) -> PublishedPlayerView?,
    catalogOf: () -> CatalogService,
    configOf: () -> FancyCrestsConfig,
) {
    fun segmentSeparator(type: TemplateSegmentType): Component? {
        val segment = configOf().firstLineTemplate.firstOrNull { it.type == type }
            ?: return null
        if (segment.separator.isEmpty()) return null
        val stylePrefix = segment.separatorStyle ?: return Component.literal(segment.separator)
        return LegacyTextParser.parse(stylePrefix + segment.separator)
    }

    fun equippedJoined(request: PlaceholderRequest, slot: TitleSlot): Component? {
        val state = stateOf(request.target)
        val ids = when (slot) {
            TitleSlot.PREFIX -> state.prefixes
            TitleSlot.SUFFIX -> state.suffixes
        }
        if (ids.isEmpty()) return null
        val catalog = catalogOf()
        val texts = ids.mapNotNull { id -> catalog.get(id)?.text?.copy() }
        if (texts.isEmpty()) return null
        val separator = segmentSeparator(
            if (slot == TitleSlot.PREFIX) TemplateSegmentType.PREFIXES else TemplateSegmentType.SUFFIXES,
        )
        val root = Component.empty()
        texts.forEachIndexed { index, text ->
            if (index > 0) separator?.let { root.append(it.copy()) }
            root.append(text)
        }
        return if (root.siblings.size == 1) root.siblings[0].copy() else root
    }

    service.register(StandardPlaceholders.PREFIX, PlaceholderResolver { _, request ->
        equippedJoined(request, TitleSlot.PREFIX)
    })
    service.register(StandardPlaceholders.SUFFIX, PlaceholderResolver { _, request ->
        equippedJoined(request, TitleSlot.SUFFIX)
    })
    service.register(StandardPlaceholders.NICKNAME, PlaceholderResolver { _, request ->
        val state = stateOf(request.target)
        state.nickname?.let { return@PlaceholderResolver Component.literal(it) }
        viewOf(request.target)?.profileName?.let { return@PlaceholderResolver Component.literal(it) }
        null
    })
    service.register(StandardPlaceholders.BELOWNAME, PlaceholderResolver { _, request ->
        // Cycle guard: never read (and thereby grow) the line being resolved.
        if (engine.isSecondLineResolving(request.target)) return@PlaceholderResolver null
        viewOf(request.target)?.snapshot?.secondLine?.copy()
    })
}
