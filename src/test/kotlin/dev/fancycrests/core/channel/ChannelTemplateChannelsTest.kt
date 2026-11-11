package dev.fancycrests.core.channel

import dev.fancycrests.api.DisplaySnapshot
import dev.fancycrests.api.HeadDisplayChannel
import dev.fancycrests.api.PlayerTitleState
import dev.fancycrests.api.RenderSettings
import dev.fancycrests.api.TitleId
import dev.fancycrests.api.TitleSlot
import dev.fancycrests.core.TestFixtures
import dev.fancycrests.core.catalog.CatalogService
import dev.fancycrests.core.config.TemplateSegmentConfig
import dev.fancycrests.core.config.TemplateSegmentType
import dev.fancycrests.core.event.TitleEventBus
import dev.fancycrests.core.permission.PermissionManager
import dev.fancycrests.core.refresh.PlaceholderServiceImpl
import dev.fancycrests.core.refresh.TemplateEngine
import dev.fancycrests.core.resolve.SnapshotPublisher
import dev.fancycrests.core.resolve.SnapshotResolver
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Review IV: the three export channels consume their per-channel template
 * override with the null/empty/override semantics â€?null falls back to the
 * global template, an explicitly empty list renders nothing for that channel
 * (chat keeps the vanilla sender name, tab drops the override), and a hot
 * reload swaps the template in effect immediately.
 */
class ChannelTemplateChannelsTest {

    private val alice = UUID.randomUUID()
    private val adapter = TestFixtures.FakeAdapter().apply {
        online = listOf(alice)
        knownNames = mapOf("Alice" to alice)
    }

    private val states = ConcurrentHashMap<UUID, PlayerTitleState>()
    private val stateOf: (UUID) -> PlayerTitleState = { uuid ->
        states[uuid] ?: PlayerTitleState(uuid, emptyList(), emptyList(), null, 0)
    }

    private class RecordingChannel : HeadDisplayChannel {
        val snapshots = mutableListOf<DisplaySnapshot>()
        var started = 0
        override fun start(settings: RenderSettings) { started++ }
        override fun updateSettings(settings: RenderSettings) = Unit
        override fun publish(snapshot: DisplaySnapshot) { snapshots += snapshot }
        override fun clearTarget(target: UUID) = Unit
        override fun stop() = Unit
    }

    private val channel = RecordingChannel()
    private lateinit var manager: ChannelManager
    private lateinit var publisher: SnapshotPublisher
    private lateinit var resolver: SnapshotResolver

    /** prefix "p" + name template; the channel override reorders to name + suffix. */
    private val globalConfig = TestFixtures.config(
        titles = listOf(
            TestFixtures.title("p", TitleSlot.PREFIX),
            TestFixtures.title("s", TitleSlot.SUFFIX),
        ),
    )

    private val nameSuffixOverride = listOf(
        TemplateSegmentConfig(TemplateSegmentType.NAME, separator = "", segmentSeparator = " ~ "),
        TemplateSegmentConfig(TemplateSegmentType.SUFFIXES, separator = "-"),
    )

    private fun configWith(
        head: List<TemplateSegmentConfig>? = null,
        chat: List<TemplateSegmentConfig>? = null,
        tab: List<TemplateSegmentConfig>? = null,
    ) = globalConfig.copy(
        channels = globalConfig.channels.copy(
            headRenderTemplate = head,
            chatTemplate = chat,
            tabListTemplate = tab,
        ),
    )

    private fun create(config: dev.fancycrests.core.config.FancyCrestsConfig) {
        val catalog = CatalogService(config.toTitleDefinitions())
        val permissions = PermissionManager(adapter)
        var current = config
        resolver = SnapshotResolver(catalog, permissions, { current }, TemplateEngine(PlaceholderServiceImpl()))
        publisher = SnapshotPublisher(resolver, adapter, { current }, { channel })
        manager = ChannelManager(
            adapter = adapter,
            resolver = resolver,
            stateOf = stateOf,
            eventBus = TitleEventBus(TestFixtures.directExecutor),
            publisher = publisher,
        )
        hotReloadTo = { current = it }
    }

    /** Simulates a config replacement: swaps the resolver/publisher config source. */
    private var hotReloadTo: (dev.fancycrests.core.config.FancyCrestsConfig) -> Unit = {}

    private fun equipped(): PlayerTitleState =
        PlayerTitleState(alice, listOf(TitleId("p")), listOf(TitleId("s")), null, 0)

    @AfterTest
    fun tearDown() {
        if (this::manager.isInitialized) manager.stop()
    }

    // ------------------------------------------------------------------
    // Chat channel: freshChatFirstLine three states
    // ------------------------------------------------------------------

    @Test
    fun `chat falls back to the global template when chatTemplate is null`() {
        create(configWith())
        states[alice] = equipped()
        manager.apply(configWith())
        assertEquals("[p] Alice[s]", manager.freshChatFirstLine(alice)?.string)
    }

    @Test
    fun `chat passes through vanilla when chatTemplate is configured empty`() {
        create(configWith(chat = emptyList()))
        states[alice] = equipped()
        manager.apply(configWith(chat = emptyList()))
        assertNull(manager.freshChatFirstLine(alice), "empty chat template means no decoration")
    }

    @Test
    fun `chat uses the override template when configured`() {
        create(configWith(chat = nameSuffixOverride))
        states[alice] = equipped()
        manager.apply(configWith(chat = nameSuffixOverride))
        assertEquals("Alice ~ [s]", manager.freshChatFirstLine(alice)?.string)
    }

    // ------------------------------------------------------------------
    // Tab channel: three states through real broadcasts
    // ------------------------------------------------------------------

    @Test
    fun `tab falls back to the global template when tabListTemplate is null`() {
        create(configWith())
        states[alice] = equipped()
        val tabConfig = configWith().copy(
            channels = configWith().channels.copy(tabList = true),
        )
        manager.apply(tabConfig)
        assertEquals("[p] Alice[s]", adapter.tabBroadcasts.single().second?.string)
    }

    @Test
    fun `tab drops the override entirely when tabListTemplate is configured empty`() {
        create(configWith())
        states[alice] = equipped()
        val withTab = configWith().copy(channels = configWith().channels.copy(tabList = true))
        manager.apply(withTab)
        assertEquals("[p] Alice[s]", adapter.tabBroadcasts.single().second?.string)

        // Reload with an explicitly empty template: the override must be
        // cleared (a null restore broadcast), not kept on stale text.
        adapter.tabBroadcasts.clear()
        val emptied = withTab.copy(channels = withTab.channels.copy(tabListTemplate = emptyList()))
        hotReloadTo(emptied)
        manager.apply(emptied)
        assertNull(manager.freshTabFirstLine(alice))
        assertEquals(null, adapter.tabBroadcasts.single().second, "empty template must restore the vanilla tab name")
    }

    @Test
    fun `tab uses the override template when configured`() {
        create(configWith())
        states[alice] = equipped()
        val tabConfig = configWith().copy(
            channels = configWith().channels.copy(tabList = true, tabListTemplate = nameSuffixOverride),
        )
        manager.apply(tabConfig)
        assertEquals("Alice ~ [s]", adapter.tabBroadcasts.single().second?.string)
    }

    // ------------------------------------------------------------------
    // Head channel: the publisher resolves through headRenderTemplate
    // ------------------------------------------------------------------

    @Test
    fun `head publish falls back to the global template when headRenderTemplate is null`() {
        create(configWith())
        publisher.publish(equipped())
        assertEquals("[p] Alice[s]", channel.snapshots.single().firstLine.string)
    }

    @Test
    fun `head publish carries an empty first line when headRenderTemplate is configured empty`() {
        create(configWith(head = emptyList()))
        publisher.publish(equipped())
        val snapshot = channel.snapshots.single()
        assertEquals("", snapshot.firstLine.string, "empty template = empty first-line component")
        assertTrue(snapshot.firstLine.siblings.isEmpty())
    }

    @Test
    fun `head publish uses the override template when configured`() {
        create(configWith(head = nameSuffixOverride))
        publisher.publish(equipped())
        assertEquals("Alice ~ [s]", channel.snapshots.single().firstLine.string)
    }

    // ------------------------------------------------------------------
    // Hot reload: a replaced template takes effect immediately
    // ------------------------------------------------------------------

    @Test
    fun `a hot reload swaps the effective tab template and refreshes the list`() {
        create(configWith())
        states[alice] = equipped()
        val initial = configWith().copy(channels = configWith().channels.copy(tabList = true))
        manager.apply(initial)
        assertEquals("[p] Alice[s]", adapter.tabBroadcasts.single().second?.string)

        val reloaded = initial.copy(
            channels = initial.channels.copy(tabListTemplate = nameSuffixOverride),
        )
        hotReloadTo(reloaded)
        manager.apply(reloaded)

        // apply() on an already-enabled channel re-applies to everyone
        // (audit #6b), now resolving with the new template.
        assertEquals("Alice ~ [s]", adapter.tabBroadcasts.last().second?.string)
        assertEquals("Alice ~ [s]", manager.freshTabFirstLine(alice)?.string)
    }

    @Test
    fun `a hot reload swaps the effective head template on the next publish`() {
        create(configWith())
        states[alice] = equipped()
        publisher.publishIfChanged(equipped())
        assertEquals("[p] Alice[s]", channel.snapshots.single().firstLine.string)

        val reloaded = configWith(head = nameSuffixOverride)
        hotReloadTo(reloaded)
        assertTrue(publisher.publishIfChanged(equipped()), "the template change must count as a content change")
        assertEquals("Alice ~ [s]", channel.snapshots.last().firstLine.string)
    }
}
