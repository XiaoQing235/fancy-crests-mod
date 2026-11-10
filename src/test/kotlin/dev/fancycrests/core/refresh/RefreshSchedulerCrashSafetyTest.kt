package dev.fancycrests.core.refresh

import dev.fancycrests.api.PermissionVerdict
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
import dev.fancycrests.core.permission.PermissionBackend
import dev.fancycrests.core.permission.PermissionManager
import dev.fancycrests.core.resolve.SnapshotPublisher
import dev.fancycrests.core.resolve.SnapshotResolver
import java.util.UUID
import net.minecraft.network.chat.Component
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Crash isolation of the tick-driven refresh: a permission backend throwing
 * inside matchedRule for one player must neither take down the tick handler
 * nor abort the refresh of the other players in the same group.
 */
class RefreshSchedulerCrashSafetyTest {

    private val failing = UUID.randomUUID()
    private val healthy = UUID.randomUUID()

    /** Backend that explodes for one player (simulates a LuckPerms failure). */
    private class ThrowingBackend(private val victim: UUID) : PermissionBackend {
        override val name: String = "throwing"

        override fun explicitVerdict(uuid: UUID, node: String): PermissionVerdict {
            if (uuid == victim) throw IllegalStateException("permission backend down")
            return PermissionVerdict.ALLOW
        }
    }

    /** Adapter whose end-tick handler can be driven manually from the test. */
    private class ManualTickAdapter(private val players: List<UUID>) : ServerAdapter {
        var handler: (() -> Unit)? = null
        private var ticks = 0L

        override fun execute(runnable: Runnable) = runnable.run()
        override fun currentTick(): Long = ticks++
        override fun onlinePlayerUuids(): List<UUID> = players
        override fun profileName(uuid: UUID): String? = "Player-$uuid"
        override fun playerNameExact(name: String): UUID? = null
        override fun operatorLevel(uuid: UUID): Int = 0
        override fun dimensionKey(uuid: UUID): String? = "minecraft:overworld"
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

    private fun dynamicConfig(): FancyCrestsConfig = FancyCrestsConfig(
        channels = ChannelsConfig(headRender = true, chat = false, tabList = false, placeholdersExport = false),
        equipment = EquipmentConfig(3, 3),
        nickname = TestFixtures.config().nickname,
        permissions = PermissionsConfig(emptyMap()),
        firstLineTemplate = listOf(TemplateSegment(TemplateSegmentType.NAME, " ", null)),
        secondLineRules = listOf(
            SecondLineRuleConfig(
                id = "dyn",
                priority = 10,
                condition = SecondLineCondition(dimension = null, permission = "fancycrests.dyn"),
                template = "%fancycrests:belowname%",
                refreshTicks = 20,
            ),
        ),
        render = TestFixtures.config().render,
        titles = emptyList(),
    )

    @Test
    fun `a throwing permission backend does not abort the refresh of other players`() {
        val adapter = ManualTickAdapter(listOf(failing, healthy)) // failing player comes first
        val config = dynamicConfig()
        val catalog = CatalogService(config.toTitleDefinitions())
        val permissions = PermissionManager(adapter, listOf(ThrowingBackend(failing)))
        val engine = TemplateEngine(PlaceholderServiceImpl())
        val resolver = SnapshotResolver(catalog, permissions, { config }, engine)
        val publisher = SnapshotPublisher(resolver, adapter, { config }, { null })
        val scheduler = RefreshScheduler(
            adapter = adapter,
            configProvider = { config },
            resolver = resolver,
            stateOf = { uuid -> PlayerTitleState(uuid, emptyList(), emptyList(), null, 0) },
            publisher = publisher,
        )
        scheduler.rebuild()
        try {
            // refreshTicks = 20: cross the deadline. Any exception escaping
            // the tick handler would fail the test right here.
            repeat(25) { adapter.tick() }
            assertNotNull(publisher.latestView(healthy)?.snapshot, "the healthy player must have been refreshed")
            assertNull(publisher.latestView(failing), "the failing player never publishes")
        } finally {
            scheduler.stop()
        }
    }
}
