package dev.fancycrests.core.selection

import dev.fancycrests.api.PermissionVerdict
import dev.fancycrests.api.TitleId
import dev.fancycrests.api.TitleSlot
import dev.fancycrests.core.TestFixtures
import dev.fancycrests.core.catalog.CatalogService
import dev.fancycrests.core.config.FancyCrestsConfig
import dev.fancycrests.core.event.TitleEventBus
import dev.fancycrests.core.permission.BuiltInBackend
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
 * Crash-safety of the per-player operation queue: a throwing callback,
 * listener or permission backend must never permanently wedge the player's
 * inFlight slot — the next operation still executes.
 */
class SelectionServiceCrashSafetyTest {

    private val uuid = UUID.randomUUID()
    private val adapter = TestFixtures.FakeAdapter().apply {
        online = listOf(uuid)
        knownNames = mapOf("CatNine" to uuid)
    }

    /** Backend that simulates LuckPerms blowing up for a chosen player. */
    private class FlakyBackend : PermissionBackend {
        override val name: String = "flaky"
        var throwFor: UUID? = null

        override fun explicitVerdict(uuid: UUID, node: String): PermissionVerdict {
            if (uuid == throwFor) throw IllegalStateException("permission backend down")
            return PermissionVerdict.UNDEFINED
        }
    }

    private lateinit var storage: SqliteStorage
    private lateinit var service: SelectionService

    private fun create(flaky: FlakyBackend?) {
        val config: FancyCrestsConfig = TestFixtures.config()
        val catalog = CatalogService(config.toTitleDefinitions())
        val builtIn = BuiltInBackend().apply { reload(config.permissions.nodes) }
        val backends = listOfNotNull(builtIn, flaky)
        val permissions = PermissionManager(adapter, backends)
        val engine = TemplateEngine(PlaceholderServiceImpl())
        val resolver = SnapshotResolver(catalog, permissions, { config }, engine)
        val publisher = SnapshotPublisher(resolver, adapter, { config }, { null })
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
        if (this::storage.isInitialized) storage.stop()
    }

    private fun <T> await(start: ((T) -> Unit) -> Unit): T {
        val future = CompletableFuture<T>()
        start { value -> future.complete(value) }
        return future.get(5, TimeUnit.SECONDS)
    }

    @Test
    fun `a throwing operation callback does not wedge the player queue`() {
        create(null)
        // The first operation completes, but its user callback throws inside
        // the storage completion (on the write thread). The completion signal
        // must still be released, or this player could never operate again.
        service.equip(uuid, TitleId("vip"), "test") { _ -> throw IllegalStateException("callback boom") }
        val second = await<SelectionResult> { cb ->
            service.equip(uuid, TitleId("helper"), "test", cb)
        }
        assertTrue(second is SelectionResult.Success, "queue must keep working, got $second")
        assertEquals(listOf(TitleId("vip")), service.stateOf(uuid).prefixes)
        assertEquals(listOf(TitleId("helper")), service.stateOf(uuid).suffixes)
    }

    @Test
    fun `a throwing operation body does not wedge the player queue`() {
        val flaky = FlakyBackend()
        create(flaky)
        flaky.throwFor = uuid
        // Crashes inside the body (here: the permission backend) before any
        // storage write is scheduled. The queue slot must be released.
        service.equip(uuid, TitleId("vip"), "test") { _ -> }
        flaky.throwFor = null
        val result = await<SelectionResult> { cb ->
            service.equip(uuid, TitleId("vip"), "test", cb)
        }
        assertTrue(result is SelectionResult.Success, "queue must keep working, got $result")
        assertEquals(listOf(TitleId("vip")), service.stateOf(uuid).prefixes)
    }

    @Test
    fun `a throwing callback on a rejected unequip does not wedge the player queue`() {
        create(null)
        // Synchronous rejection path: the callback throws before done() runs.
        runCatching {
            service.unequip(uuid, TitleSlot.PREFIX, UnequipTarget.One(TitleId("vip")), "test") { _ ->
                throw IllegalStateException("callback boom")
            }
        }
        val result = await<SelectionResult> { cb ->
            service.equip(uuid, TitleId("vip"), "test", cb)
        }
        assertTrue(result is SelectionResult.Success, "queue must keep working, got $result")
    }
}
