package dev.fancycrests.core.resolve

import dev.fancycrests.api.DisplaySnapshot
import dev.fancycrests.api.HeadDisplayChannel
import dev.fancycrests.api.HeadLine
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
import dev.fancycrests.core.config.TemplateSegmentConfig
import dev.fancycrests.core.config.TemplateSegmentType
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
    val headLines: List<HeadLine>? = null,
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

    fun resolve(state: PlayerTitleState, profileName: String, dimensionKey: String?): ResolvedDisplay =
        resolveFor(channelTemplate = null, state = state, profileName = profileName, dimensionKey = dimensionKey)

    /**
     * Channel-aware resolution (review IV): [channelTemplate] overrides the
     * global `firstLineTemplate` for one export channel.
     *
     *  - `null` = not configured → the global template is used (identical
     *    to [resolve]);
     *  - an **empty list** = configured empty → the first line is an empty
     *    component: the head channel renders an empty first line (only the
     *    second line, if any, shows), while the chat and tab channels treat
     *    the null-vs-empty distinction upstream (vanilla sender name / no
     *    tab override);
     *  - a non-empty list → rendered with the same rules as the global
     *    template, plus the per-segment [TemplateSegmentConfig.segmentSeparator]:
     *    separators **between segments** use `segmentSeparator ?: separator`
     *    of the *preceding* segment, separators **between titles inside one
     *    segment** always use that segment's `separator` (the global
     *    [TemplateSegment] has no `segmentSeparator`, so its inter-segment
     *    behaviour is unchanged).
     */
    fun resolveFor(
        channelTemplate: List<TemplateSegmentConfig>?,
        state: PlayerTitleState,
        profileName: String,
        dimensionKey: String?,
        includeHeadLines: Boolean = false,
        previousHeadLines: List<HeadLine>? = null,
        refreshHeadLines: Set<Int>? = null,
    ): ResolvedDisplay {
        val config = configProvider()
        val effectiveName = state.nickname ?: profileName
        val firstLine = when (channelTemplate) {
            null -> buildFirstLine(config.firstLineTemplate, state, catalog, effectiveName)
            else -> buildFirstLineFromConfig(channelTemplate, state, catalog, effectiveName)
        }
        val secondLine = matchSecondLine(config.sortedSecondLineRules(), state.uuid, dimensionKey)
        val headLines = if (includeHeadLines) config.nametagLines?.mapIndexed { index, line ->
            val previous = previousHeadLines?.getOrNull(index)
            if (refreshHeadLines != null && index !in refreshHeadLines && previous != null) {
                previous
            } else {
                HeadLine(
                    templateEngine.renderWithOverrides(
                        line.content,
                        PlaceholderRequest(state.uuid, observer = null),
                        mapOf("display" to firstLine, "belowname" to (secondLine?.second ?: Component.empty())),
                    ).component,
                    line.seeThrough,
                    line.selfVisible,
                )
            }
        } else null
        return ResolvedDisplay(
            firstLine = firstLine,
            secondLine = secondLine?.second,
            matchedRuleId = secondLine?.first?.id,
            headLines = headLines,
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

    /** Unified per-segment separator pair: within the segment / after it. */
    private data class SegmentSpec(
        val type: TemplateSegmentType,
        /** Joins the items (titles) *within* the segment. */
        val within: Component?,
        /** Placed between this segment and the next non-empty one. */
        val between: Component?,
    )

    internal fun buildFirstLine(
        template: List<TemplateSegment>,
        state: PlayerTitleState,
        catalog: CatalogService,
        effectiveName: String,
    ): Component = buildFirstLineFromSpecs(
        template.map { segment ->
            val within = separatorComponent(segment.separator, segment.separatorStyle)
            // Global segments have no dedicated inter-segment separator:
            // the within-segment separator is reused (legacy behaviour).
            SegmentSpec(segment.type, within, within)
        },
        state,
        catalog,
        effectiveName,
    )

    /** Channel-override variant of [buildFirstLine] (see [resolveFor]). */
    internal fun buildFirstLineFromConfig(
        template: List<TemplateSegmentConfig>,
        state: PlayerTitleState,
        catalog: CatalogService,
        effectiveName: String,
    ): Component = buildFirstLineFromSpecs(
        template.map { segment ->
            val within = separatorComponent(segment.separator, segment.separatorStyle)
            val between = separatorComponent(segment.segmentSeparator ?: segment.separator, segment.separatorStyle)
            SegmentSpec(segment.type, within, between)
        },
        state,
        catalog,
        effectiveName,
    )

    private fun buildFirstLineFromSpecs(
        specs: List<SegmentSpec>,
        state: PlayerTitleState,
        catalog: CatalogService,
        effectiveName: String,
    ): Component {
        val rendered = mutableListOf<RenderedSegment>()
        for (spec in specs) {
            val items: List<Component> = when (spec.type) {
                TemplateSegmentType.PREFIXES ->
                    equippedTexts(state.prefixes, catalog)
                TemplateSegmentType.NAME ->
                    // The effective name may be a nickname carrying legacy
                    // colour codes; parse them instead of printing the codes
                    // as plain text (audit #10). Code-free profile names
                    // parse to the identical literal component.
                    listOf(LegacyTextParser.parse(effectiveName))
                TemplateSegmentType.SUFFIXES ->
                    equippedTexts(state.suffixes, catalog)
            }
            if (items.isEmpty()) continue

            val joined = Component.empty()
            items.forEachIndexed { index, item ->
                if (index > 0) {
                    spec.within?.let { joined.append(it) }
                }
                joined.append(item)
            }
            rendered += RenderedSegment(joined, spec.between)
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

    /** The separator component for [text], or null for an empty separator string. */
    private fun separatorComponent(text: String, style: String?): Component? {
        if (text.isEmpty()) return null
        if (style == null) return Component.literal(text)
        return LegacyTextParser.parse(style + text)
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
    private val showOwnNametag: (UUID) -> Boolean = { true },
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
     *
     * The head channel resolves with `channels.headRenderTemplate` when
     * configured (null = global template; an explicitly empty list renders
     * an empty first line, review IV — the snapshot is still published so
     * the render layer swaps the previous text for the empty one).
     */
    fun publish(state: PlayerTitleState) {
        val profileName = currentProfileName(state.uuid)
        // Stage the view first so placeholder resolvers (nickname/prefix/
        // suffix) read the fresh immutable state during the resolve below.
        views[state.uuid] = PublishedPlayerView(state.uuid, profileName, state, views[state.uuid]?.snapshot)
        val resolved = resolver.resolveFor(
            configProvider().channels.headRenderTemplate,
            state,
            profileName,
            adapter.dimensionKey(state.uuid),
            includeHeadLines = true,
        )
        publishResolved(state.uuid, profileName, state, resolved)
    }

    /**
     * Periodic re-resolution used by the refresh scheduler: resolves the
     * current state and publishes only when the resolved content actually
     * changed (first line, second line or matched rule). Returns true when a
     * new snapshot was published. Resolves through the head channel's
     * template override like [publish].
     */
    fun publishIfChanged(state: PlayerTitleState, refreshHeadLines: Set<Int>? = null): Boolean {
        val profileName = currentProfileName(state.uuid)
        views[state.uuid] = PublishedPlayerView(state.uuid, profileName, state, views[state.uuid]?.snapshot)
        val resolved = resolver.resolveFor(
            configProvider().channels.headRenderTemplate,
            state,
            profileName,
            adapter.dimensionKey(state.uuid),
            includeHeadLines = true,
            previousHeadLines = views[state.uuid]?.snapshot?.headLines,
            refreshHeadLines = refreshHeadLines,
        )
        val previous = views[state.uuid]?.snapshot
        if (previous != null &&
            previous.firstLine == resolved.firstLine &&
            previous.secondLine == resolved.secondLine &&
            previous.matchedSecondLineRuleId == resolved.matchedRuleId &&
            previous.headLines == resolved.headLines &&
            previous.showOwnNametag == showOwnNametag(state.uuid)
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
            headLines = resolved.headLines,
            showOwnNametag = showOwnNametag(uuid),
        )
        views[uuid] = PublishedPlayerView(uuid, profileName, state, snapshot)
        channelProvider()?.publish(snapshot)
    }
}
