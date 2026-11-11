package dev.fancycrests.core.selection

import dev.fancycrests.api.PermissionVerdict
import dev.fancycrests.api.TitleId
import dev.fancycrests.api.TitleSlot
import dev.fancycrests.core.TestFixtures
import dev.fancycrests.core.catalog.CatalogService
import dev.fancycrests.core.event.TitleChangeEventPre
import dev.fancycrests.core.event.TitleEventBus
import dev.fancycrests.core.event.TitleOperationType
import dev.fancycrests.core.permission.PermissionBackend
import dev.fancycrests.core.permission.PermissionManager
import dev.fancycrests.core.refresh.PlaceholderServiceImpl
import dev.fancycrests.core.refresh.TemplateEngine
import dev.fancycrests.core.resolve.SnapshotPublisher
import dev.fancycrests.core.resolve.SnapshotResolver
import dev.fancycrests.core.storage.SqliteStorage
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Audit #3 regression: [SelectionService.revalidatePlayer] checked
 * permissions.isReady only *before* enqueueing. A revalidation that waited in
 * the per-player queue while the backend unloaded the user then executed
 * unprotected — interim UNDEFINED verdicts were persisted as an unequip.
 *
 * The fix re-checks isReady as the first thing inside the queued body and
 * defers exactly like the pre-queue path.
 */
class RevalidationQueueWindowTest {

    private val uuid = UUID.randomUUID()
    private val adapter = TestFixtures.FakeAdapter().apply { online = listOf(uuid) }

    /** Mimics the LuckPerms "user not loaded" window. */
    private class LazyBackend : PermissionBackend {
        override val name = "lazy"
        var loaded = true
        var warmUps = 0

        override fun explicitVerdict(playerUuid: UUID, node: String): PermissionVerdict =
            if (!loaded) PermissionVerdict.UNDEFINED else PermissionVerdict.ALLOW

        override fun isReady(playerUuid: UUID): Boolean = loaded

        override fun warmUp(playerUuid: UUID) {
            warmUps++
        }
    }

    private val backend = LazyBackend()
    private val config = TestFixtures.config(
        titles = listOf(TestFixtures.title("guarded", TitleSlot.PREFIX, permission = "fancycrests.title.guarded")),
    )
    private lateinit var storage: SqliteStorage
    private lateinit var eventBus: TitleEventBus
    private lateinit var service: SelectionService

    private fun create() {
        val catalog = CatalogService(config.toTitleDefinitions())
        val permissions = PermissionManager(adapter, listOf(backend))
        val resolver = SnapshotResolver(catalog, permissions, { config }, TemplateEngine(PlaceholderServiceImpl()))
        val publisher = SnapshotPublisher(resolver, adapter, { config }, { null })
        eventBus = TitleEventBus(TestFixtures.directExecutor)
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
        if (this::storage.isInitialized) storage.stop()
    }

    private fun <T> await(start: ((T) -> Unit) -> Unit): T {
        val future = CompletableFuture<T>()
        start { future.complete(it) }
        return future.get(5, TimeUnit.SECONDS)
    }

    @Test
    fun `a revalidation that waited in the queue defers when the backend unloaded meanwhile`() {
        create()
        await<SelectionResult> { cb -> service.equip(uuid, TitleId("guarded"), "test", callback = cb) }
        assertEquals(listOf(TitleId("guarded")), service.stateOf(uuid).prefixes)

        // Park an operation inside the player's queue slot: its pre event
        // blocks in a listener, so the slot is held but not completed.
        val enteredFirePre = CountDownLatch(1)
        val releaseEquip = CountDownLatch(1)
        eventBus.addPreListener { event: TitleChangeEventPre ->
            if (event.type == TitleOperationType.EQUIP && releaseEquip.count == 1L) {
                enteredFirePre.countDown()
                releaseEquip.await()
            }
        }

        val equipThread = Thread {
            service.equip(uuid, TitleId("helper"), "test", callback = {})
        }
        equipThread.isDaemon = true
        equipThread.start()
        assertTrue(enteredFirePre.await(5, TimeUnit.SECONDS), "equip must reach its pre event")

        // While the queue slot is held: revalidate passes the (still ready)
        // pre-queue check and enqueues behind the parked equip...
        val revalidated = CompletableFuture<SelectionResult>()
        service.revalidatePlayer(uuid, "login") { revalidated.complete(it) }
        // ...then the backend unloads the user during the queue wait.
        backend.loaded = false

        releaseEquip.countDown()
        equipThread.join(5000)
        val result = revalidated.get(5, TimeUnit.SECONDS)

        // The queued body must have deferred, not unequipped on UNDEFINED.
        assertEquals("deferred", (result as SelectionResult.Success).operationId)
        assertEquals(listOf(TitleId("guarded")), service.stateOf(uuid).prefixes)
        assertTrue(backend.warmUps >= 1, "the deferred revalidation must trigger the lazy load")
    }
}
