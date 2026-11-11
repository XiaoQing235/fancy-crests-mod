package dev.fancycrests.core.selection

import dev.fancycrests.api.Permissions
import dev.fancycrests.api.TitleId
import dev.fancycrests.api.TitleSlot
import dev.fancycrests.core.TestFixtures
import dev.fancycrests.core.catalog.CatalogService
import dev.fancycrests.core.config.FancyCrestsConfig
import dev.fancycrests.core.event.TitleChangeEventPre
import dev.fancycrests.core.event.TitleEventBus
import dev.fancycrests.core.event.TitleOperationType
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
import kotlin.test.assertTrue

/**
 * Service-level guarantees the UI and the command layer rely on: permission
 * gating, ownership bookkeeping, limit enforcement and the cancellable
 * pre-commit gate for grants.
 */
class SelectionServiceTest {

    private val uuid = UUID.randomUUID()
    private val adapter = TestFixtures.FakeAdapter().apply {
        online = listOf(uuid)
        knownNames = mapOf("CatNine" to uuid)
    }

    private var config: FancyCrestsConfig = TestFixtures.config()
    private lateinit var storage: SqliteStorage
    private lateinit var eventBus: TitleEventBus
    private lateinit var builtIn: BuiltInBackend
    private lateinit var service: SelectionService

    private val prefixTitles = listOf(
        TestFixtures.title("alpha", TitleSlot.PREFIX),
        TestFixtures.title("beta", TitleSlot.PREFIX),
        TestFixtures.title("gamma", TitleSlot.PREFIX),
    )

    private fun create(cfg: FancyCrestsConfig) {
        config = cfg
        val catalog = CatalogService(cfg.toTitleDefinitions())
        builtIn = BuiltInBackend().apply { reload(cfg.permissions.nodes) }
        val permissions = PermissionManager(adapter, listOf(builtIn))
        val placeholders = PlaceholderServiceImpl()
        val engine = TemplateEngine(placeholders)
        val resolver = SnapshotResolver(catalog, permissions, { config }, engine)
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
        start { value -> future.complete(value) }
        return future.get(5, TimeUnit.SECONDS)
    }

    @Test
    fun `a denied equip permission blocks the service itself`() {
        create(TestFixtures.config(deny = mapOf(uuid to listOf(Permissions.EQUIP))))
        val result = await<SelectionResult> { cb ->
            service.equip(uuid, TitleId("vip"), "test", callback = cb)
        }
        val rejected = result as SelectionResult.Rejected
        assertEquals(RejectionReason.MISSING_PERMISSION, rejected.reason)
        assertTrue(service.stateOf(uuid).prefixes.isEmpty(), "nothing may be equipped")
    }

    @Test
    fun `a denied equip permission blocks unequip as well`() {
        create(TestFixtures.config())
        await<SelectionResult> { cb -> service.equip(uuid, TitleId("vip"), "test", callback = cb) }
        config = TestFixtures.config(deny = mapOf(uuid to listOf(Permissions.EQUIP)))
        builtIn.reload(config.permissions.nodes)
        assertEquals(listOf(TitleId("vip")), service.stateOf(uuid).prefixes)
        val result = await<SelectionResult> { cb ->
            service.unequip(uuid, TitleSlot.PREFIX, UnequipTarget.All, "test", cb)
        }
        assertTrue(result is SelectionResult.Rejected)
        assertEquals(1, service.stateOf(uuid).prefixes.size)
    }

    @Test
    fun `repeated grants keep a single ownership record`() {
        create(TestFixtures.config())
        await<SelectionResult> { cb -> service.grant(uuid, TitleId("vip"), "local", "test", cb) }
        await<SelectionResult> { cb -> service.grant(uuid, TitleId("vip"), "local", "test", cb) }
        assertEquals(1, service.ownershipOf(uuid).size)
        assertEquals(1, service.ownershipCount)
    }

    @Test
    fun `lowering the equip limit trims existing equipment in equip order`() {
        create(TestFixtures.config(titles = prefixTitles, maxPrefixes = 3))
        for (id in listOf("alpha", "beta", "gamma")) {
            await<SelectionResult> { cb -> service.equip(uuid, TitleId(id), "test", callback = cb) }
        }
        assertEquals(3, service.stateOf(uuid).prefixes.size)

        config = TestFixtures.config(titles = prefixTitles, maxPrefixes = 1)
        await<SelectionResult> { cb -> service.revalidatePlayer(uuid, "config-reloaded", cb) }

        assertEquals(listOf(TitleId("alpha")), service.stateOf(uuid).prefixes)
        assertTrue(adapter.messages.isNotEmpty(), "the trim must be reported to the player")
    }

    @Test
    fun `a cancelled pre event aborts a grant before anything is written`() {
        create(TestFixtures.config())
        val seen = mutableListOf<TitleChangeEventPre>()
        eventBus.addPreListener { event ->
            seen += event
            if (event.type == TitleOperationType.GRANT) event.cancel()
        }
        val result = await<SelectionResult> { cb ->
            service.grant(uuid, TitleId("vip"), "local", "test", cb)
        }
        assertEquals(SelectionResult.Cancelled, result)
        assertEquals(0, service.ownershipCount, "a cancelled grant must not be recorded")
        val pre = seen.single()
        assertEquals(TitleOperationType.GRANT, pre.type)
        assertEquals(listOf(TitleId("vip")), pre.candidateOwnership?.map { it.titleId })
    }
}