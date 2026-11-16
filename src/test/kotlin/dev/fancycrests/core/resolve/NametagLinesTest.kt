package dev.fancycrests.core.resolve

import dev.fancycrests.core.TestFixtures
import dev.fancycrests.core.catalog.CatalogService
import dev.fancycrests.core.config.ConfigService
import dev.fancycrests.core.config.ConfigYaml
import dev.fancycrests.core.config.NametagLineConfig
import dev.fancycrests.core.config.SecondLineRuleConfig
import dev.fancycrests.core.permission.PermissionManager
import dev.fancycrests.core.refresh.PlaceholderServiceImpl
import dev.fancycrests.core.refresh.TemplateEngine
import dev.fancycrests.core.refresh.RefreshScheduler
import dev.fancycrests.core.ServerAdapter
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.Executor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import net.minecraft.network.chat.Component

class NametagLinesTest {
    @Test
    fun `configured line can reuse current legacy display and belowname`() {
        val uuid = UUID.randomUUID()
        val adapter = TestFixtures.FakeAdapter()
        val config = TestFixtures.config().copy(
            secondLineRules = listOf(SecondLineRuleConfig("below", 1, null, "now")),
            nametagLines = listOf(NametagLineConfig("%fancycrests:display%/%fancycrests:belowname%", false, 20, true)),
        )
        val resolver = SnapshotResolver(
            CatalogService(config.toTitleDefinitions()), PermissionManager(adapter, emptyList()),
            { config }, TemplateEngine(PlaceholderServiceImpl()),
        )
        val state = dev.fancycrests.api.PlayerTitleState(uuid, emptyList(), emptyList(), null, 0)
        val display = resolver.resolveFor(null, state, "TestPlayer", null, includeHeadLines = true)
        assertEquals("TestPlayer/now", display.headLines!!.single().text.string)
    }

    @Test
    fun `scheduler refreshes each line at its configured period`() {
        val uuid = UUID.randomUUID()
        val fake = TestFixtures.FakeAdapter().apply {
            knownNames = mapOf("TestPlayer" to uuid)
            online = listOf(uuid)
        }
        val adapter = object : ServerAdapter by fake {
            var tick = 0L
            var handler: () -> Unit = {}
            override fun currentTick(): Long = tick
            override fun registerEndTickHandler(handler: () -> Unit): AutoCloseable {
                this.handler = handler
                return AutoCloseable {}
            }
        }
        val config = TestFixtures.config().copy(
            channels = TestFixtures.config().channels.copy(headRender = true),
            nametagLines = listOf(
                NametagLineConfig("%example:a%", false, 5, false),
                NametagLineConfig("%example:b%", false, 20, false),
            ),
        )
        var value = "first"
        val engine = TemplateEngine(PlaceholderServiceImpl(), external = { _, _ -> Component.literal(value) })
        val resolver = SnapshotResolver(CatalogService(config.toTitleDefinitions()), PermissionManager(adapter, emptyList()), { config }, engine)
        val publisher = SnapshotPublisher(resolver, adapter, { config }, { null })
        val state = dev.fancycrests.api.PlayerTitleState(uuid, emptyList(), emptyList(), null, 0)
        val scheduler = RefreshScheduler(adapter, { config }, resolver, { state }, publisher)
        try {
            publisher.publish(state)
            scheduler.rebuild()
            value = "second"
            adapter.tick = 5
            adapter.handler()
            assertEquals(listOf("second", "first"), publisher.latestView(uuid)!!.snapshot!!.headLines!!.map { it.text.string })
            adapter.tick = 20
            adapter.handler()
            assertEquals(listOf("second", "second"), publisher.latestView(uuid)!!.snapshot!!.headLines!!.map { it.text.string })
        } finally { scheduler.stop() }
    }

    @Test
    fun `each line resolves its own settings and only due lines refresh`() {
        val uuid = UUID.randomUUID()
        val adapter = TestFixtures.FakeAdapter().apply { knownNames = mapOf("TestPlayer" to uuid) }
        val config = TestFixtures.config().copy(nametagLines = listOf(
            NametagLineConfig("%example:a%", true, 5, true),
            NametagLineConfig("%example:b%", false, 40, false),
        ))
        var value = "first"
        val engine = TemplateEngine(PlaceholderServiceImpl(), external = { token, _ -> Component.literal("$token:$value") })
        val resolver = SnapshotResolver(CatalogService(config.toTitleDefinitions()), PermissionManager(adapter, emptyList()), { config }, engine)
        val state = dev.fancycrests.api.PlayerTitleState(uuid, emptyList(), emptyList(), null, 0)
        val initial = resolver.resolveFor(null, state, "TestPlayer", null, includeHeadLines = true).headLines!!
        value = "second"
        val updated = resolver.resolveFor(null, state, "TestPlayer", null, true, initial, setOf(0)).headLines!!
        assertEquals("example:a:second", updated[0].text.string)
        assertEquals("example:b:first", updated[1].text.string)
        assertTrue(updated[0].seeThrough)
        assertTrue(updated[0].selfVisible)
        assertFalse(updated[1].seeThrough)
        assertFalse(updated[1].selfVisible)
    }

    @Test
    fun `configuration allows at most 255 independently configured lines`() {
        val service = ConfigService(Path.of("unused.yaml"), Executor { it.run() })
        try {
            fun load(count: Int) = service.parseConfig(ConfigYaml.parse(
                "firstLineTemplate: [{type: name}]\nnametag:\n  lines:\n" +
                    "    - {content: 'line', refreshTicks: 20}\n".repeat(count),
            ).asJsonObject)
            assertTrue(service.validate(load(255)).none { it.path == "nametag.lines" })
            assertTrue(service.validate(load(256)).any { it.path == "nametag.lines" })
        } finally { service.stop() }
    }
}
