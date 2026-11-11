package dev.fancycrests.core.channel

import dev.fancycrests.api.DisplaySnapshot
import dev.fancycrests.api.HeadDisplayChannel
import dev.fancycrests.api.PlayerTitleState
import dev.fancycrests.api.RenderOffset
import dev.fancycrests.api.RenderSettings
import dev.fancycrests.core.TestFixtures
import dev.fancycrests.core.catalog.CatalogService
import dev.fancycrests.core.config.ChannelsConfig
import dev.fancycrests.core.config.FancyCrestsConfig
import dev.fancycrests.core.config.SecondLineCondition
import dev.fancycrests.core.config.SecondLineRuleConfig
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * TAB channel correctness as players come and go and the config reloads
 * (audit #6): a new observer must receive the *existing* overrides of other
 * players, and a reload while the channel stays enabled must refresh the
 * displayed text instead of short-circuiting in enable().
 *
 * Also covers the dimension-change republish hook (audit #7): the manager
 * re-resolves the mover and publishes only real changes.
 */
class ChannelManagerTabSyncTest {

    private val alice = UUID.randomUUID()
    private val bob = UUID.randomUUID()
    private val adapter = TestFixtures.FakeAdapter().apply {
        online = listOf(alice)
        knownNames = mapOf("Alice" to alice, "Bob" to bob)
    }

    private val states = ConcurrentHashMap<UUID, PlayerTitleState>()
    private val stateOf: (UUID) -> PlayerTitleState = { uuid ->
        states[uuid] ?: PlayerTitleState(uuid, emptyList(), emptyList(), null, 0)
    }

    /** Records every published snapshot; start/stop/settings are no-ops. */
    private class RecordingChannel : HeadDisplayChannel {
        val snapshots = mutableListOf<DisplaySnapshot>()
        override fun start(settings: RenderSettings) = Unit
        override fun updateSettings(settings: RenderSettings) = Unit
        override fun publish(snapshot: DisplaySnapshot) {
            snapshots += snapshot
        }
        override fun clearTarget(target: UUID) = Unit
        override fun stop() = Unit
    }

    private val channel = RecordingChannel()
    private lateinit var manager: ChannelManager
    private lateinit var publisher: SnapshotPublisher
    private lateinit var resolver: SnapshotResolver

    private fun create(config: FancyCrestsConfig) {
        val catalog = CatalogService(config.toTitleDefinitions())
        val permissions = PermissionManager(adapter)
        resolver = SnapshotResolver(catalog, permissions, { config }, TemplateEngine(PlaceholderServiceImpl()))
        publisher = SnapshotPublisher(resolver, adapter, { config }, { channel })
        manager = ChannelManager(
            adapter = adapter,
            resolver = resolver,
            stateOf = stateOf,
            eventBus = TitleEventBus(TestFixtures.directExecutor),
            publisher = publisher,
        )
    }

    private fun tabConfig(withRule: Boolean = false): FancyCrestsConfig {
        val base = TestFixtures.config()
        return base.copy(
            channels = ChannelsConfig(
                headRender = false,
                chat = false,
                tabList = true,
                placeholdersExport = false,
            ),
            secondLineRules = if (withRule) {
                listOf(
                    SecondLineRuleConfig(
                        id = "nether-info",
                        priority = 10,
                        condition = SecondLineCondition(dimension = "minecraft:the_nether", permission = null),
                        template = "Nether visitor",
                    ),
                )
            } else {
                emptyList()
            },
        )
    }

    @AfterTest
    fun tearDown() {
        if (this::manager.isInitialized) manager.stop()
    }

    @Test
    fun `a joining observer receives the existing overrides of other players`() {
        create(tabConfig())
        states[alice] = PlayerTitleState(alice, emptyList(), emptyList(), "&cAlice", 0)

        manager.apply(tabConfig())
        assertTrue(adapter.tabBroadcasts.isNotEmpty(), "enable must apply Alice's override")

        // Bob joins: his client built the tab list from vanilla state.
        adapter.tabBroadcasts.clear()
        adapter.online = listOf(alice, bob)
        manager.onPlayerJoin(bob)

        // Alice's override must reach the new observer (audit #6a): before
        // the fix only the joiner himself was addressed, and Bob's client
        // kept showing Alice's plain profile name.
        val forAlice = adapter.tabBroadcasts.filter { it.first == alice }
        assertEquals(1, forAlice.size, "Alice's override must be re-broadcast on join, got ${adapter.tabBroadcasts}")
        assertNotNull(forAlice.single().second)
        assertTrue(forAlice.single().second!!.string.contains("Alice"))
    }

    @Test
    fun `reapplying an enabled channel after a reload refreshes the displayed text`() {
        create(tabConfig())
        states[alice] = PlayerTitleState(alice, emptyList(), emptyList(), "Alice", 0)

        val config = tabConfig()
        manager.apply(config)
        val first = adapter.tabBroadcasts.single { it.first == alice }.second
        assertEquals("Alice", first?.string)

        // The nickname changes (equivalently: a reload swapped title texts
        // or the template). enable() alone would short-circuit (`if
        // (enabled) return`) and leave the whole tab list on stale text.
        states[alice] = states[alice]!!.copy(nickname = "AliceTheGreat")
        adapter.tabBroadcasts.clear()
        manager.apply(config)

        val refreshed = adapter.tabBroadcasts.single { it.first == alice }.second
        assertEquals("AliceTheGreat", refreshed?.string)
    }

    @Test
    fun `a dimension change republishes only real content changes`() {
        create(tabConfig(withRule = true))

        // Overworld: no rule matches; the initial publish carries no second line.
        publisher.publish(stateOf(alice))
        assertEquals(null, publisher.latestView(alice)?.snapshot?.secondLine)

        // Alice moves to the nether (the adapter already reports the new
        // dimension when the fabric event fires): the rule now matches and
        // the snapshot must be republished with a second line.
        adapter.dimension = "minecraft:the_nether"
        manager.onPlayerChangedDimension(alice)
        val netherSnapshot = channel.snapshots.last()
        assertEquals(alice, netherSnapshot.target)
        assertNotNull(netherSnapshot.secondLine, "the dimension rule must produce a second line")
        assertEquals("nether-info", netherSnapshot.matchedSecondLineRuleId)

        // Moving back clears the line again...
        adapter.dimension = "minecraft:overworld"
        manager.onPlayerChangedDimension(alice)
        assertNull(channel.snapshots.last().secondLine, "leaving the dimension must clear the second line")
        assertNull(channel.snapshots.last().matchedSecondLineRuleId)

        // ...and an idempotent re-fire without a change publishes nothing.
        val published = channel.snapshots.size
        manager.onPlayerChangedDimension(alice)
        assertEquals(published, channel.snapshots.size, "publishIfChanged must not re-send unchanged content")
    }

    @Test
    fun `after stop the dimension hook is a no-op`() {
        create(tabConfig(withRule = true))
        publisher.publish(stateOf(alice))
        manager.stop()
        adapter.dimension = "minecraft:the_nether"
        val published = channel.snapshots.size
        manager.onPlayerChangedDimension(alice)
        assertEquals(published, channel.snapshots.size, "a stopped manager must not publish")
    }
}
