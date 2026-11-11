package dev.fancycrests.core.channel

import dev.fancycrests.api.PlayerTitleState
import dev.fancycrests.core.TestFixtures
import dev.fancycrests.core.catalog.CatalogService
import dev.fancycrests.core.event.TitleEventBus
import dev.fancycrests.core.permission.PermissionManager
import dev.fancycrests.core.refresh.PlaceholderServiceImpl
import dev.fancycrests.core.refresh.TemplateEngine
import dev.fancycrests.core.resolve.SnapshotResolver
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * Audit II-5: Fabric listeners are registered once per process by the
 * module-level forwarders and forwarded to the *active* instance. These
 * tests pin the activation lifecycle: newest instance wins, stop clears the
 * slot (and only the matching one).
 */
class ChannelEventForwardersTest {

    private val uuid = UUID.randomUUID()
    private val adapter = TestFixtures.FakeAdapter().apply {
        online = listOf(uuid)
        knownNames = mapOf("Alice" to uuid)
    }

    private var manager: ChannelManager? = null

    private fun newManager(): ChannelManager {
        val config = TestFixtures.config()
        val resolver = SnapshotResolver(
            CatalogService(config.toTitleDefinitions()),
            PermissionManager(adapter),
            { config },
            TemplateEngine(PlaceholderServiceImpl()),
        )
        return ChannelManager(
            adapter = adapter,
            resolver = resolver,
            stateOf = { PlayerTitleState(it, emptyList(), emptyList(), null, 0) },
            eventBus = TitleEventBus(TestFixtures.directExecutor),
            publisher = null,
        ).also { manager = it }
    }

    @AfterTest
    fun tearDown() {
        manager?.stop()
    }

    @Test
    fun `constructing a chat channel makes it the active receiver`() {
        val chat = ChatChannel { null }
        assertSame(chat, activeChatChannel())
    }

    @Test
    fun `the newest chat channel replaces the previous one`() {
        val first = ChatChannel { null }
        val second = ChatChannel { null }
        assertSame(second, activeChatChannel(), "the newest instance must be the receiver")
        assertEquals(false, first.enabled)
        assertEquals(false, second.enabled)
    }

    @Test
    fun `a stopped manager clears the dimension hook slot`() {
        val stopped = newManager()
        assertSame(stopped, activeChannelManager())
        stopped.stop()
        assertNull(activeChannelManager(), "stop must release the forwarded reference")
    }

    @Test
    fun `a stale stop does not deactivate a newer manager`() {
        val stale = newManager()
        val current = newManager()
        assertSame(current, activeChannelManager())
        // The old instance's stop must not clear the slot of the new one.
        stale.stop()
        assertSame(current, activeChannelManager())
        current.stop()
        assertNull(activeChannelManager())
    }
}
