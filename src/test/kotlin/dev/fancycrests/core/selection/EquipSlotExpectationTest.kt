package dev.fancycrests.core.selection

import dev.fancycrests.api.TitleId
import dev.fancycrests.api.TitleSlot
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
import kotlin.test.assertTrue

/**
 * The equip slot guard: callers that state which slot they are equipping
 * into (menu tab, API expectation) get a [RejectionReason.SLOT_MISMATCH]
 * when the title actually lives in the other slot, instead of the title
 * silently landing in the unexpected list. Callers that pass no expectation
 * keep the previous semantics.
 */
class EquipSlotExpectationTest {

    private val uuid = UUID.randomUUID()
    private val adapter = TestFixtures.FakeAdapter().apply {
        online = listOf(uuid)
        knownNames = mapOf("CatNine" to uuid)
    }
    private val config = TestFixtures.config()
    private lateinit var storage: SqliteStorage
    private lateinit var service: SelectionService

    private fun create() {
        val catalog = CatalogService(config.toTitleDefinitions())
        val builtIn = BuiltInBackend().apply { reload(config.permissions.nodes) }
        val permissions = PermissionManager(adapter, listOf(builtIn))
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

    private fun <T> await(start: ((T) -> Unit) -> Unit): T {
        val future = CompletableFuture<T>()
        start { future.complete(it) }
        return future.get(5, TimeUnit.SECONDS)
    }

    @Test
    fun `a wrong slot expectation is rejected with SLOT_MISMATCH`() {
        create()
        // "helper" is a suffix title; the caller believes it is a prefix.
        val result = await<SelectionResult> { cb ->
            service.equip(uuid, TitleId("helper"), "test", expectedSlot = TitleSlot.PREFIX, callback = cb)
        }
        val rejected = result as SelectionResult.Rejected
        assertEquals(RejectionReason.SLOT_MISMATCH, rejected.reason)
        assertTrue(
            rejected.detail!!.contains("SUFFIX"),
            "the detail must name the real slot, got '${rejected.detail}'",
        )
        assertTrue(service.stateOf(uuid).suffixes.isEmpty(), "nothing may be equipped on a mismatch")
    }

    @Test
    fun `a matching slot expectation equips normally`() {
        create()
        val result = await<SelectionResult> { cb ->
            service.equip(uuid, TitleId("helper"), "test", expectedSlot = TitleSlot.SUFFIX, callback = cb)
        }
        assertTrue(result is SelectionResult.Success, "got $result")
        assertEquals(listOf(TitleId("helper")), service.stateOf(uuid).suffixes)
    }

    @Test
    fun `no expectation keeps the previous semantics`() {
        create()
        val result = await<SelectionResult> { cb ->
            service.equip(uuid, TitleId("helper"), "test", callback = cb)
        }
        assertTrue(result is SelectionResult.Success, "got $result")
        assertEquals(listOf(TitleId("helper")), service.stateOf(uuid).suffixes)
    }
}
