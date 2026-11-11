package dev.fancycrests.core.selection

import dev.fancycrests.api.PermissionVerdict
import dev.fancycrests.api.TitleId
import dev.fancycrests.api.TitleSlot
import dev.fancycrests.core.TestFixtures
import dev.fancycrests.core.catalog.CatalogService
import dev.fancycrests.core.event.TitleEventBus
import dev.fancycrests.core.permission.PermissionBackend
import dev.fancycrests.core.permission.PermissionManager
import dev.fancycrests.core.refresh.PlaceholderServiceImpl
import dev.fancycrests.core.refresh.TemplateEngine
import dev.fancycrests.core.resolve.SnapshotPublisher
import dev.fancycrests.core.resolve.SnapshotResolver
import dev.fancycrests.core.storage.SqliteStorage
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Audit #4 regression: runGuarded released the queue slot on a crash but
 * never answered the operation's own callback — API futures and GUI debounce
 * latches hung forever. Every enqueue point (equip/unequip/nickname/
 * revalidate/grant/revoke) must deliver Rejected(INTERNAL_ERROR) when its
 * body crashes, with a detail carrying the crash cause.
 */
class SelectionServiceCrashCallbackTest {

    private val uuid = UUID.randomUUID()
    private val adapter = TestFixtures.FakeAdapter().apply { online = listOf(uuid) }

    /** Backend that simulates LuckPerms blowing up for a chosen player. */
    private class FlakyBackend : PermissionBackend {
        override val name = "flaky"
        var throwFor: UUID? = null

        override fun explicitVerdict(playerUuid: UUID, node: String): PermissionVerdict {
            if (playerUuid == throwFor) throw IllegalStateException("permission backend down")
            return PermissionVerdict.ALLOW
        }
    }

    private val flaky = FlakyBackend()
    private val config = TestFixtures.config(
        titles = listOf(
            TestFixtures.title("guarded", TitleSlot.PREFIX, permission = "fancycrests.title.guarded"),
        ),
    )
    private lateinit var storage: SqliteStorage
    private lateinit var service: SelectionService

    private fun create() {
        val catalog = CatalogService(config.toTitleDefinitions())
        val permissions = PermissionManager(adapter, listOf(flaky))
        val resolver = SnapshotResolver(catalog, permissions, { config }, TemplateEngine(PlaceholderServiceImpl()))
        val publisher = SnapshotPublisher(resolver, adapter, { config }, { null })
        storage = SqliteStorage(null, TestFixtures.directExecutor)
        storage.start()
        service = SelectionService(
            adapter = adapter,
            storage = storage,
            catalog = catalog,
            permissions = permissions,
            configProvider = { config },
            eventBus = TitleEventBus(TestFixtures.directExecutor),
            publisher = publisher,
            initialStates = emptyMap(),
            initialOwnership = emptyList(),
        )
    }

    @AfterTest
    fun tearDown() {
        if (this::storage.isInitialized) storage.stop()
    }

    @Test
    fun `a crashing equip answers its callback with INTERNAL_ERROR and keeps state untouched`() {
        create()
        flaky.throwFor = uuid
        val results = mutableListOf<SelectionResult>()
        service.equip(uuid, TitleId("guarded"), "test", callback = results::add)
        flaky.throwFor = null

        val rejected = results.single() as SelectionResult.Rejected
        assertEquals(RejectionReason.INTERNAL_ERROR, rejected.reason)
        assertTrue(
            rejected.detail!!.contains("permission backend down"),
            "the crash cause must surface in the detail, got '${rejected.detail}'",
        )
        assertEquals(emptyList(), service.stateOf(uuid).prefixes, "nothing may be equipped on a crash")
    }

    @Test
    fun `a crashing revalidation also reports INTERNAL_ERROR instead of hanging`() {
        create()
        await<SelectionResult> { cb -> service.equip(uuid, TitleId("guarded"), "test", callback = cb) }
        flaky.throwFor = uuid
        val results = mutableListOf<SelectionResult>()
        service.revalidatePlayer(uuid, "login", results::add)
        flaky.throwFor = null

        val rejected = results.single() as SelectionResult.Rejected
        assertEquals(RejectionReason.INTERNAL_ERROR, rejected.reason)
        // The crash struck before anything was written: the equipment stays.
        assertEquals(listOf(TitleId("guarded")), service.stateOf(uuid).prefixes)
    }

    @Test
    fun `after a crash the queue still serves the next operation`() {
        create()
        flaky.throwFor = uuid
        service.equip(uuid, TitleId("guarded"), "test", callback = {})
        flaky.throwFor = null
        val result = await<SelectionResult> { cb ->
            service.equip(uuid, TitleId("guarded"), "test", callback = cb)
        }
        assertTrue(result is SelectionResult.Success, "queue must keep working, got $result")
    }

    private fun <T> await(start: ((T) -> Unit) -> Unit): T {
        val latch = java.util.concurrent.CountDownLatch(1)
        var value: T? = null
        start {
            @Suppress("UNCHECKED_CAST")
            value = it as T
            latch.countDown()
        }
        assertTrue(latch.await(5, java.util.concurrent.TimeUnit.SECONDS), "operation never completed")
        return value!!
    }
}
