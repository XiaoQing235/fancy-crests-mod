package dev.fancycrests.core.resolve

import dev.fancycrests.api.DisplaySnapshot
import dev.fancycrests.api.HeadDisplayChannel
import dev.fancycrests.api.PermissionVerdict
import dev.fancycrests.api.PlayerTitleState
import dev.fancycrests.api.TitleId
import dev.fancycrests.core.ServerAdapter
import dev.fancycrests.core.catalog.CatalogService
import dev.fancycrests.core.config.FancyCrestsConfig
import dev.fancycrests.core.config.LegacyTextParser
import dev.fancycrests.core.config.SecondLineRuleConfig
import dev.fancycrests.core.config.TemplateSegment
import dev.fancycrests.core.permission.PermissionManager
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import net.minecraft.network.chat.Component

/** Structured resolution result for one target (no per-observer text). */
data class ResolvedDisplay(
    val firstLine: Component,
    val secondLine: Component?,
    val matchedRuleId: String?,
)

/**
 * Assembles the observer-independent display of one player.
 *
 * First line: walks the admin template segments in order. Empty segments
 * produce nothing (no stray separators); every fragment keeps its own style —
 * titles come from the catalog as independent Components and separators are
 * separate components, so styles never bleed between fragments. Nothing is
 * flattened to plain text on the way.
 *
 * Second line: rules sorted by priority descending, ties broken by stable
 * ascending id; the first rule whose condition matches wins. P2 conditions
 * are `dimension` and `permission` (AND). No match → null.
 *
 * Effective display name: the nickname, or the current GameProfile name
 * (provided by the caller via [ServerAdapter.profileName]).
 */
class SnapshotResolver(
    private val catalog: CatalogService,
    private val permissions: PermissionManager,
    private val configProvider: () -> FancyCrestsConfig,
) {

    fun resolve(state: PlayerTitleState, profileName: String, dimensionKey: String?): ResolvedDisplay {
        val config = configProvider()
        val effectiveName = state.nickname ?: profileName
        val firstLine = buildFirstLine(config.firstLineTemplate, state, catalog, effectiveName)
        val secondLine = matchSecondLine(config.sortedSecondLineRules(), state.uuid, dimensionKey)
        return ResolvedDisplay(
            firstLine = firstLine,
            secondLine = secondLine?.second,
            matchedRuleId = secondLine?.first?.id,
        )
    }

    // ------------------------------------------------------------------
    // First line
    // ------------------------------------------------------------------

    private data class RenderedSegment(val component: Component, val separator: Component?)

    internal fun buildFirstLine(
        template: List<TemplateSegment>,
        state: PlayerTitleState,
        catalog: CatalogService,
        effectiveName: String,
    ): Component {
        val rendered = mutableListOf<RenderedSegment>()
        for (segment in template) {
            val items: List<Component> = when (segment.type) {
                dev.fancycrests.core.config.TemplateSegmentType.PREFIXES ->
                    equippedTexts(state.prefixes, catalog)
                dev.fancycrests.core.config.TemplateSegmentType.NAME ->
                    listOf(Component.literal(effectiveName))
                dev.fancycrests.core.config.TemplateSegmentType.SUFFIXES ->
                    equippedTexts(state.suffixes, catalog)
            }
            if (items.isEmpty()) continue

            val joined = Component.empty()
            items.forEachIndexed { index, item ->
                if (index > 0) {
                    separatorComponent(segment)?.let { joined.append(it) }
                }
                joined.append(item)
            }
            rendered += RenderedSegment(joined, separatorComponent(segment))
        }

        val root = Component.empty()
        var lastSeparator: Component? = null
        for ((index, segment) in rendered.withIndex()) {
            if (index > 0) {
                lastSeparator?.let { root.append(it) }
            }
            root.append(segment.component)
            lastSeparator = segment.separator
        }
        return root
    }

    /** Catalog text of each equipped id; unknown ids are skipped. */
    private fun equippedTexts(ids: List<TitleId>, catalog: CatalogService): List<Component> =
        ids.mapNotNull { id -> catalog.get(id)?.text?.copy() }

    /** The separator component of a segment, or null for an empty separator string. */
    private fun separatorComponent(segment: TemplateSegment): Component? {
        if (segment.separator.isEmpty()) return null
        val stylePrefix = segment.separatorStyle ?: return Component.literal(segment.separator)
        return LegacyTextParser.parse(stylePrefix + segment.separator)
    }

    // ------------------------------------------------------------------
    // Second line
    // ------------------------------------------------------------------

    /** Returns the first matching rule plus its parsed template, or null. */
    internal fun matchSecondLine(
        sortedRules: List<SecondLineRuleConfig>,
        uuid: UUID,
        dimensionKey: String?,
    ): Pair<SecondLineRuleConfig, Component>? {
        for (rule in sortedRules) {
            val condition = rule.condition ?: return rule to LegacyTextParser.parse(rule.template)
            if (condition.dimension != null && condition.dimension != dimensionKey) continue
            if (condition.permission != null &&
                permissions.check(uuid, condition.permission) != PermissionVerdict.ALLOW
            ) {
                continue
            }
            return rule to LegacyTextParser.parse(rule.template)
        }
        return null
    }
}

/**
 * Publishes [DisplaySnapshot]s through the injected [HeadDisplayChannel]
 * reference. The channel is constructed/wired by the integration layer; when
 * it is absent (head channel disabled or renderer not ready) publishing is a
 * noop. Snapshot versions increment per target.
 */
class SnapshotPublisher(
    private val resolver: SnapshotResolver,
    private val adapter: ServerAdapter,
    private val configProvider: () -> FancyCrestsConfig,
    private val channelProvider: () -> HeadDisplayChannel?,
) {
    private val versions = ConcurrentHashMap<UUID, Long>()

    /** Number of distinct targets that received at least one snapshot. */
    val trackedTargets: Int get() = versions.size

    /**
     * Resolves and publishes the current snapshot of [state]. Called on the
     * main thread after every committed state change or recheck.
     */
    fun publish(state: PlayerTitleState) {
        val channel = channelProvider() ?: return
        val profileName = adapter.profileName(state.uuid) ?: "Unknown"
        val dimensionKey = adapter.dimensionKey(state.uuid)
        val resolved = resolver.resolve(state, profileName, dimensionKey)
        val version = versions.merge(state.uuid, 1L, Long::plus) ?: 1L
        channel.publish(
            DisplaySnapshot(
                target = state.uuid,
                firstLine = resolved.firstLine,
                secondLine = resolved.secondLine,
                matchedSecondLineRuleId = resolved.matchedRuleId,
                version = version,
                resolvedAtTick = adapter.currentTick(),
            ),
        )
    }

    /** Drops rendering for a target (logout, channel disabled). */
    fun clearTarget(uuid: UUID) {
        channelProvider()?.clearTarget(uuid)
    }

    fun reset() {
        versions.clear()
    }
}
