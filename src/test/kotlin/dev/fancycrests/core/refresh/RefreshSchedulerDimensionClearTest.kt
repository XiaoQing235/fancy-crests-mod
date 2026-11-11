package dev.fancycrests.core.refresh

import dev.fancycrests.api.PlayerTitleState
import dev.fancycrests.core.ServerAdapter
import dev.fancycrests.core.TestFixtures
import dev.fancycrests.core.catalog.CatalogService
import dev.fancycrests.core.config.ChannelsConfig
import dev.fancycrests.core.config.EquipmentConfig
import dev.fancycrests.core.config.FancyCrestsConfig
import dev.fancycrests.core.config.PermissionsConfig
import dev.fancycrests.core.config.SecondLineCondition
import dev.fancycrests.core.config.SecondLineRuleConfig
import dev.fancycrests.core.config.TemplateSegment
import dev.fancycrests.core.config.TemplateSegmentType
import dev.fancycrests.core.permission.PermissionManager
import dev.fancycrests.core.resolve.SnapshotPublisher
import dev.fancycrests.core.resolve.SnapshotResolver
import java.util.UUID
import net.minecraft.network.chat.Component
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Audit #7 regression (scheduler half): refreshGroup skipped players whose
 * current dimension matches *no* rule (`matchedRule(...) ?: continue`), so
 * the second line published under the old dimension's rule survived the
 * dimension switch forever. The refresh must now take the publish path for
 * such players (re-resolve + publishIfChanged clears the stale line), while
 * players with no published view at all stay skipped (no busy work).
 */
class RefreshSchedulerDimensionClearTest {

    private val mover = UUID.randomUUID()
    private val bystander = UUID.randomUUID()

    /** Manual-tick adapter with per-player dimensions. */
    private class DimensionAdapter(
        val players: List<UUID>,
        val dimensions: MutableMap<UUID, String?>,
    ) : ServerAdapter {
        var handler: (() -> Unit)? = null
        private var ticks = 0L

        override fun execute(runnable: Runnable) = runnable.run()
        override fun currentTick(): Long = ticks++
        override fun onlinePlayerUuids(): List<UUID> = players
        override fun profileName(uuid: UUID): String? = "Player-$uuid"
        override fun playerNameExact(name: String): UUID? = null
        override fun operatorLevel(uuid: UUID): Int = 0
        override fun dimensionKey(uuid: UUID): String? = dimensions[uuid]
        override fun knownPlayerNames(): Map<String, UUID> = emptyMap()
        override fun sendSystemMessage(uuid: UUID, message: Component) = Unit
        override fun broadcastTabListDisplayName(uuid: UUID, displayName: Component?) = Unit
        override fun registerEndTickHandler(handler: () -> Unit): AutoCloseable {
            this.handler = handler
            return AutoCloseable { this.handler = null }
        }

        fun tick() {
            handler?.invoke() ?: error("no tick handler registered")
        }
    }

    private val dimensions = mutableMapOf<UUID, String?>(
        mover to "minecraft:the_nether",
        bystander to "minecraft:overworld",
    )
    private val adapter = DimensionAdapter(listOf(mover, bystander), dimensions)

    /** A dynamic nether-only rule (placeholder template → scheduled refresh). */
    private fun config(): FancyCrestsConfig = FancyCrestsConfig(
        channels = ChannelsConfig(headRender = true, chat = false, tabList = false, placeholdersExport = false),
        equipment = EquipmentConfig(3, 3),
        nickname = TestFixtures.config().nickname,
        permissions = PermissionsConfig(emptyMap()),
        firstLineTemplate = listOf(TemplateSegment(TemplateSegmentType.NAME, " ", null)),
        secondLineRules = listOf(
            SecondLineRuleConfig(
                id = "nether-info",
                priority = 10,
                condition = SecondLineCondition(dimension = "minecraft:the_nether", permission = null),
                template = "%fancycrests:belowname% Nether",
                refreshTicks = 20,
            ),
        ),
        render = TestFixtures.config().render,
        titles = emptyList(),
    )

    private lateinit var scheduler: RefreshScheduler

    private fun create(): SnapshotPublisher {
        val config = config()
        val catalog = CatalogService(config.toTitleDefinitions())
        val permissions = PermissionManager(adapter)
        val resolver = SnapshotResolver(catalog, permissions, { config }, TemplateEngine(PlaceholderServiceImpl()))
        val publisher = SnapshotPublisher(resolver, adapter, { config }, { null })
        scheduler = RefreshScheduler(
            adapter = adapter,
            configProvider = { config },
            resolver = resolver,
            stateOf = { uuid -> PlayerTitleState(uuid, emptyList(), emptyList(), null, 0) },
            publisher = publisher,
        )
        scheduler.rebuild()
        return publisher
    }

    @AfterTest
    fun tearDown() {
        if (this::scheduler.isInitialized) scheduler.stop()
    }

    @Test
    fun `leaving the rule's dimension clears the stale second line on the next refresh`() {
        val publisher = create()

        // In the nether the rule matches: the first cycle publishes it.
        repeat(25) { adapter.tick() }
        val netherSnapshot = publisher.latestView(mover)?.snapshot
        assertNotNull(netherSnapshot, "the matching rule must be published")
        assertEquals("nether-info", netherSnapshot.matchedSecondLineRuleId)
        assertNotNull(netherSnapshot.secondLine)

        // Dimension switch to a world no rule covers.
        dimensions[mover] = "minecraft:overworld"
        repeat(25) { adapter.tick() }

        val cleared = publisher.latestView(mover)?.snapshot
        assertNotNull(cleared, "the player keeps a published view")
        assertNull(cleared.secondLine, "the stale second line must be cleared, got ${cleared.secondLine}")
        assertNull(cleared.matchedSecondLineRuleId)
    }

    @Test
    fun `players without a published view and without a match are not resolved`() {
        val publisher = create()
        // The bystander sits in the overworld from the very start: no rule
        // matches and nothing was ever published — the refresh must skip it
        // instead of re-resolving it every period.
        repeat(50) { adapter.tick() }
        assertNull(publisher.latestView(bystander), "no view may be created for a never-matching player")
    }
}
