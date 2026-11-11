package dev.fancycrests.core.selection

import dev.fancycrests.api.TitleId
import dev.fancycrests.core.TestFixtures
import dev.fancycrests.core.catalog.CatalogService
import dev.fancycrests.core.event.TitleEventBus
import dev.fancycrests.core.permission.BuiltInBackend
import dev.fancycrests.core.permission.PermissionManager
import dev.fancycrests.core.refresh.PlaceholderServiceImpl
import dev.fancycrests.core.refresh.TemplateEngine
import dev.fancycrests.core.resolve.SnapshotPublisher
import dev.fancycrests.core.resolve.SnapshotResolver
import dev.fancycrests.core.storage.SqliteStorage
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Logout hygiene: quitting drops the per-session bookkeeping (published
 * views, snapshot versions, drained op queue) while the persisted selection
 * state survives for the next login.
 */
class SelectionServiceQuitCleanupTest {

    private val uuid = UUID.randomUUID()
    private val adapter = TestFixtures.FakeAdapter().apply {
        online = listOf(uuid)
        knownNames = mapOf("CatNine" to uuid)
    }

    private val config = TestFixtures.config()
    private val storage: SqliteStorage
    private val publisher: SnapshotPublisher
    private val service: SelectionService

    init {
        val catalog = CatalogService(config.toTitleDefinitions())
        val builtIn = BuiltInBackend().apply { reload(config.permissions.nodes) }
        val permissions = PermissionManager(adapter, listOf(builtIn))
        val resolver = SnapshotResolver(catalog, permissions, { config }, TemplateEngine(PlaceholderServiceImpl()))
        publisher = SnapshotPublisher(resolver, adapter, { config }, { null })
        val eventBus = TitleEventBus(TestFixtures.directExecutor)
        storage = SqliteStorage(null, TestFixtures.directExecutor)
        storage.start()
        service = SelectionService(
            adapter = adapter,
            storage = storage,
            catalog = catalog,
            permissions = permissions,
            configProvider = { config },
            eventBus = eventBus,
            publisher = publisher,
            initialStates = emptyMap(),
            initialOwnership = emptyList(),
        )
    }

    @AfterTest
    fun tearDown() {
        storage.stop()
    }

    private fun <T> await(start: ((T) -> Unit) -> Unit): T {
        val future = CompletableFuture<T>()
        start { value -> future.complete(value) }
        return future.get(5, TimeUnit.SECONDS)
    }

    @Test
    fun `quit drops published views but keeps persisted state`() {
        val result = await<SelectionResult> { cb ->
            service.equip(uuid, TitleId("vip"), "test", callback = cb)
        }
        assertTrue(result is SelectionResult.Success)
        assertNotNull(publisher.latestView(uuid), "the view must exist while online")
        assertEquals(1, publisher.trackedTargets)

        service.handlePlayerQuit(uuid)

        assertNull(publisher.latestView(uuid), "the view must be dropped on quit")
        assertEquals(0, publisher.trackedTargets, "the version entry must be dropped on quit")
        // Persisted state survives the logout (persistence semantics).
        assertEquals(listOf(TitleId("vip")), service.stateOf(uuid).prefixes)
    }

    @Test
    fun `operations after a rejoin still work`() {
        await<SelectionResult> { cb -> service.equip(uuid, TitleId("vip"), "test", callback = cb) }
        service.handlePlayerQuit(uuid)
        // Rejoin: the queue is recreated on demand and publishes again.
        val result = await<SelectionResult> { cb ->
            service.equip(uuid, TitleId("helper"), "test", callback = cb)
        }
        assertTrue(result is SelectionResult.Success, "operations must work after rejoin, got $result")
        assertNotNull(publisher.latestView(uuid))
    }
}
