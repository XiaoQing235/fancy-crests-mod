package dev.fancycrests.core.resolve

import dev.fancycrests.api.DisplaySnapshot
import dev.fancycrests.api.HeadDisplayChannel
import dev.fancycrests.api.PermissionVerdict
import dev.fancycrests.api.PlaceholderRequest
import dev.fancycrests.api.PlayerTitleState
import dev.fancycrests.api.TitleId
import dev.fancycrests.core.ServerAdapter
import dev.fancycrests.core.catalog.CatalogService
import dev.fancycrests.core.config.FancyCrestsConfig
import dev.fancycrests.core.config.LegacyTextParser
import dev.fancycrests.core.config.SecondLineRuleConfig
import dev.fancycrests.core.config.TemplateSegment
import dev.fancycrests.core.permission.PermissionManager
import dev.fancycrests.core.refresh.PlaceholderServiceImpl
import dev.fancycrests.core.refresh.TemplateEngine
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
 * Immutable per-target view of everything published by
 * [SnapshotPublisher]: the player state, the profile name captured at
 * resolve time and the newest snapshot. Read from any thread by the built-in
 * placeholder resolvers and external export adapters.
 */
data class PublishedPlayerView(
    val target: UUID,
    val profileName: String?,
    val state: PlayerTitleState,
    /** Newest published snapshot; null before the first publish. */
    val snapshot: DisplaySnapshot?,
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
 * ascending id; the first rule whose condition matches wins. Conditions
 * are `dimension` and `permission` (AND). No match → null. The rule template
 * is rendered through the shared [TemplateEngine]: static text keeps the P2
 * [LegacyTextParser] semantics, `%fancycrests:key%` tokens resolve through
 * the placeholder service (P3).
 *
 * Effective display name: the nickname, or the current GameProfile name
 * (provided by the caller via [ServerAdapter.profileName]).
 */
class SnapshotResolver(
    private val catalog: CatalogService,
    private val permissions: PermissionManager,
    private val configProvider: () -> FancyCrestsConfig,
    private val templateEngine: TemplateEngine = TemplateEngine(PlaceholderServiceImpl()),
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

    /** The rule that would currently match [uuid], without rendering it. */
    fun matchedRule(uuid: UUID, dimensionKey: String?): SecondLineRuleConfig? {
        for (rule in configProvider().sortedSecondLineRules()) {
            val condition = rule.condition ?: return rule
            if (condition.dimension != null && condition.dimension != dimensionKey) continue
            if (condition.permission != null &&
                permissions.check(uuid, condition.permission) != PermissionVerdict.ALLOW
            ) {
                continue
            }
            return rule
        }
        return null
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
                    // The effective name may be a nickname carrying legacy
                    // colour codes; parse them instead of printing the codes
                    // as plain text (audit #10). Code-free profile names
                    // parse to the identical literal component.
                    listOf(LegacyTextParser.parse(effectiveName))
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

    /** Returns the first matching rule plus its rendered template, or null. */
    internal fun matchSecondLine(
        sortedRules: List<SecondLineRuleConfig>,
        uuid: UUID,
        dimensionKey: String?,
    ): Pair<SecondLineRuleConfig, Component>? {
        for (rule in sortedRules) {
            val condition = rule.condition
            if (condition != null) {
                if (condition.dimension != null && condition.dimension != dimensionKey) continue
                if (condition.permission != null &&
                    permissions.check(uuid, condition.permission) != PermissionVerdict.ALLOW
                ) {
                    continue
                }
            }
            val rendered = templateEngine.withSecondLineResolution(uuid) {
                templateEngine.render(rule.template, PlaceholderRequest(uuid, observer = null))
            }
            return rule to rendered.component
        }
        return null
    }
}

/**
 * Publishes [DisplaySnapshot]s through the injected [HeadDisplayChannel]
 * reference and maintains the immutable [PublishedPlayerView] store consumed
 * by the built-in placeholders, the chat/tab channels and export adapters.
 * The channel is constructed/wired by the integration layer; when it is
 * absent (head channel disabled or renderer not ready) the snapshot is still
 * resolved and stored — chat, tab list and placeholder export keep working —
 * only the head rendering forward is skipped. Snapshot versions increment
 * per target.
 */
class SnapshotPublisher(
    private val resolver: SnapshotResolver,
    private val adapter: ServerAdapter,
    private val configProvider: () -> FancyCrestsConfig,
    private val channelProvider: () -> HeadDisplayChannel?,
) {
    private val versions = ConcurrentHashMap<UUID, Long>()
    private val views = ConcurrentHashMap<UUID, PublishedPlayerView>()

    /** Number of distinct targets that received at least one snapshot. */
    val trackedTargets: Int get() = versions.size

    /** Newest immutable published view of a target, or null when never published. */
    fun latestView(uuid: UUID): PublishedPlayerView? = views[uuid]

    /**
     * Resolves and publishes the current snapshot of [state]. Called on the
     * main thread after every committed state change or recheck.
     */
    fun publish(state: PlayerTitleState) {
        val profileName = currentProfileName(state.uuid)
        // Stage the view first so placeholder resolvers (nickname/prefix/
        // suffix) read the fresh immutable state during the resolve below.
        views[state.uuid] = PublishedPlayerView(state.uuid, profileName, state, views[state.uuid]?.snapshot)
        val resolved = resolver.resolve(state, profileName, adapter.dimensionKey(state.uuid))
        publishResolved(state.uuid, profileName, state, resolved)
    }

    /**
     * Periodic re-resolution used by the refresh scheduler: resolves the
     * current state and publishes only when the resolved content actually
     * changed (first line, second line or matched rule). Returns true when a
     * new snapshot was published.
     */
    fun publishIfChanged(state: PlayerTitleState): Boolean {
        val profileName = currentProfileName(state.uuid)
        views[state.uuid] = PublishedPlayerView(state.uuid, profileName, state, views[state.uuid]?.snapshot)
        val resolved = resolver.resolve(state, profileName, adapter.dimensionKey(state.uuid))
        val previous = views[state.uuid]?.snapshot
        if (previous != null &&
            previous.firstLine == resolved.firstLine &&
            previous.secondLine == resolved.secondLine &&
            previous.matchedSecondLineRuleId == resolved.matchedRuleId
        ) {
            return false
        }
        publishResolved(state.uuid, profileName, state, resolved)
        return true
    }

    /** Drops head rendering for a target (logout, channel disabled). */
    fun clearTarget(uuid: UUID) {
        channelProvider()?.clearTarget(uuid)
    }

    /**
     * Full logout cleanup of one target: head rendering plus the published
     * view and version counter, so players who left never keep entries in
     * the maps. Only this target's keys are removed; every other player's
     * view stays live. Persisted selection state is untouched (it lives in
     * the selection service and the database).
     */
    fun forgetTarget(uuid: UUID) {
        channelProvider()?.clearTarget(uuid)
        views.remove(uuid)
        versions.remove(uuid)
    }

    fun reset() {
        versions.clear()
    }

    private fun currentProfileName(uuid: UUID): String =
        adapter.profileName(uuid) ?: views[uuid]?.profileName ?: "Unknown"

    private fun publishResolved(
        uuid: UUID,
        profileName: String,
        state: PlayerTitleState,
        resolved: ResolvedDisplay,
    ) {
        val version = versions.merge(uuid, 1L, Long::plus) ?: 1L
        val snapshot = DisplaySnapshot(
            target = uuid,
            firstLine = resolved.firstLine,
            secondLine = resolved.secondLine,
            matchedSecondLineRuleId = resolved.matchedRuleId,
            version = version,
            resolvedAtTick = adapter.currentTick(),
        )
        views[uuid] = PublishedPlayerView(uuid, profileName, state, snapshot)
        channelProvider()?.publish(snapshot)
    }
}
