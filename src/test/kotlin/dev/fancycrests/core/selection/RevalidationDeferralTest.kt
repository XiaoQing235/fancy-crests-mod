package dev.fancycrests.core.selection

import dev.fancycrests.api.PermissionVerdict
import dev.fancycrests.api.TitleId
import dev.fancycrests.api.TitleSlot
import dev.fancycrests.core.TestFixtures
import dev.fancycrests.core.catalog.CatalogService
import dev.fancycrests.core.permission.PermissionBackend
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
import kotlin.test.assertTrue

/**
 * Audit M-2 regression: a permission backend that has not loaded a user yet
 * must defer revalidation, never turn the interim UNDEFINED verdicts into a
 * persisted unequip. Once the backend is ready the revalidation resumes and
 * applies the real verdicts.
 */
class RevalidationDeferralTest {

    private val uuid = UUID.randomUUID()
    private val adapter = TestFixtures.FakeAdapter().apply { online = listOf(uuid) }

    /** Mimics the LuckPerms "user not loaded" window. */
    private class LazyBackend : PermissionBackend {
        override val name = "lazy"
        var loaded = true
        var allowGuarded = true
        var warmUps = 0

        override fun explicitVerdict(playerUuid: UUID, node: String): PermissionVerdict =
            when {
                !loaded -> PermissionVerdict.UNDEFINED
                node == "fancycrests.title.guarded" ->
                    if (allowGuarded) PermissionVerdict.ALLOW else PermissionVerdict.DENY
                else -> PermissionVerdict.UNDEFINED
            }

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
    private lateinit var service: SelectionService

    private fun create() {
        val catalog = CatalogService(config.toTitleDefinitions())
        val permissions = PermissionManager(adapter, listOf(backend))
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
            eventBus = dev.fancycrests.core.event.TitleEventBus(TestFixtures.directExecutor),
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
    fun `revalidation defers while a backend is not ready and keeps equipment`() {
        create()
        await<SelectionResult> { cb -> service.equip(uuid, TitleId("guarded"), "test", callback = cb) }
        assertEquals(listOf(TitleId("guarded")), service.stateOf(uuid).prefixes)

        backend.loaded = false
        val result = await<SelectionResult> { cb -> service.revalidatePlayer(uuid, "login", cb) }
        assertEquals("deferred", (result as SelectionResult.Success).operationId)
        assertEquals(listOf(TitleId("guarded")), service.stateOf(uuid).prefixes)
        // The deferred revalidation must still kick off the backend's lazy
        // load (smoke-test regression: nothing else triggers it for players
        // outside the vanilla login path).
        assertEquals(1, backend.warmUps)
    }

    @Test
    fun `revalidation resumes and applies real verdicts once ready`() {
        create()
        await<SelectionResult> { cb -> service.equip(uuid, TitleId("guarded"), "test", callback = cb) }

        backend.loaded = false
        await<SelectionResult> { cb -> service.revalidatePlayer(uuid, "login", cb) }

        backend.loaded = true
        backend.allowGuarded = false // provider now says the node is denied
        val result = await<SelectionResult> { cb -> service.revalidatePlayer(uuid, "recheck", cb) }
        assertTrue((result as SelectionResult.Success).operationId != "deferred")
        assertEquals(emptyList(), service.stateOf(uuid).prefixes)
    }
}
