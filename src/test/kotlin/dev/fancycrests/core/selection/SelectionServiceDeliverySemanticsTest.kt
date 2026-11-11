package dev.fancycrests.core.selection

import dev.fancycrests.api.DisplaySnapshot
import dev.fancycrests.api.HeadDisplayChannel
import dev.fancycrests.api.PermissionVerdict
import dev.fancycrests.api.RenderSettings
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
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Audit #2 regression: completion semantics of the per-player queue.
 *
 * (a) Once the storage layer reports the write committed (revision
 * advanced), a failure in the post-commit cascade (publisher/channel/post
 * event) must NOT rewrite the business result into
 * Rejected(INTERNAL_ERROR) claiming "safe to retry" — the operation IS
 * committed, and a retry would duplicate grants or hit ALREADY_EQUIPPED.
 * The committed result is delivered; the display failure is only logged.
 *
 * (b) The caller's completion callback fires at most once per operation
 * (delivery latch): a user callback that throws after receiving its
 * result must not be re-entered through the crash path with a second,
 * contradictory result.
 *
 * Pre-commit crashes (validation, permission backend) keep the honest
 * INTERNAL_ERROR "state unchanged, safe to retry" answer.
 */
class SelectionServiceDeliverySemanticsTest {

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

    /** Display channel whose publish can be told to explode. */
    private class FlakyChannel : HeadDisplayChannel {
        var throwOnPublish = false

        override fun start(settings: RenderSettings) {}
        override fun updateSettings(settings: RenderSettings) {}
        override fun publish(snapshot: DisplaySnapshot) {
            if (throwOnPublish) throw IllegalStateException("display channel down")
        }
        override fun clearTarget(target: UUID) {}
        override fun stop() {}
    }

    private val flaky = FlakyBackend()
    private val channel = FlakyChannel()
    private val config = TestFixtures.config()
    private lateinit var storage: SqliteStorage
    private lateinit var service: SelectionService

    private fun create() {
        val catalog = CatalogService(config.toTitleDefinitions())
        val permissions = PermissionManager(adapter, listOf(flaky))
        val resolver = SnapshotResolver(catalog, permissions, { config }, TemplateEngine(PlaceholderServiceImpl()))
        val publisher = SnapshotPublisher(resolver, adapter, { config }, { channel })
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

    // ------------------------------------------------------------------
    // (a) post-commit failure must deliver the committed result
    // ------------------------------------------------------------------

    @Test
    fun `a committed equip whose publish throws still delivers the committed Success`() {
        create()
        channel.throwOnPublish = true
        val result = await<SelectionResult> { cb -> service.equip(uuid, TitleId("vip"), "test", callback = cb) }

        // Before the fix this (or a retry-hung caller) surfaced as
        // INTERNAL_ERROR "safe to retry" even though the row was committed.
        assertTrue(result is SelectionResult.Success, "committed result must be delivered, got $result")
        assertEquals(1L, service.stateOf(uuid).revision, "the committed revision must be applied in memory")
        assertEquals(listOf(TitleId("vip")), service.stateOf(uuid).prefixes)

        // The queue slot was released: the next operation still runs, and a
        // retry of the same title reports the truth (already equipped).
        val retry = await<SelectionResult> { cb -> service.equip(uuid, TitleId("vip"), "test", callback = cb) }
        assertTrue(
            retry is SelectionResult.Rejected && retry.reason == RejectionReason.ALREADY_EQUIPPED,
            "a retry must see the committed state, got $retry",
        )
        val next = await<SelectionResult> { cb -> service.equip(uuid, TitleId("helper"), "test", callback = cb) }
        assertTrue(next is SelectionResult.Success, "queue must keep working, got $next")
    }

    @Test
    fun `a committed nickname whose publish throws still delivers the committed Success`() {
        create()
        channel.throwOnPublish = true
        val result = await<SelectionResult> { cb -> service.setNickname(uuid, "CatNine", "test", cb) }
        assertTrue(result is SelectionResult.Success, "committed result must be delivered, got $result")
        assertEquals("CatNine", service.stateOf(uuid).nickname)
    }

    @Test
    fun `a committed grant whose publish throws still delivers the committed Success`() {
        create()
        channel.throwOnPublish = true
        val result = await<SelectionResult> { cb -> service.grant(uuid, TitleId("vip"), "local", "test", cb) }
        assertTrue(result is SelectionResult.Success, "committed result must be delivered, got $result")
        assertTrue(service.hasOwnership(uuid, TitleId("vip"), "local"), "the grant must be recorded in memory")

        // A blind retry (what INTERNAL_ERROR invites) must not silently
        // duplicate work — here it is idempotent thanks to the key, but the
        // caller must still learn the first grant went through.
        channel.throwOnPublish = false
        val second = await<SelectionResult> { cb -> service.grant(uuid, TitleId("helper"), "local", "test", cb) }
        assertTrue(second is SelectionResult.Success, "queue must keep working, got $second")
    }

    // ------------------------------------------------------------------
    // (b) the completion callback fires at most once
    // ------------------------------------------------------------------

    @Test
    fun `a throwing callback on a synchronous rejection is answered exactly once`() {
        create()
        var calls = 0
        val results = mutableListOf<SelectionResult>()
        // The callback throws inside the operation body (synchronous
        // rejection path); the crash completion used to re-enter it with a
        // second, contradictory INTERNAL_ERROR.
        runCatching {
            service.equip(uuid, TitleId("missing"), "test") { result ->
                calls++
                results += result
                throw IllegalStateException("callback boom")
            }
        }
        assertEquals(1, calls, "the callback must fire exactly once, got $calls")
        assertEquals(
            RejectionReason.TITLE_NOT_FOUND,
            (results.single() as SelectionResult.Rejected).reason,
            "the first (real) result must be the delivered one",
        )

        val next = await<SelectionResult> { cb -> service.equip(uuid, TitleId("vip"), "test", callback = cb) }
        assertTrue(next is SelectionResult.Success, "queue must keep working, got $next")
    }

    @Test
    fun `a throwing callback on a committed result is answered exactly once`() {
        create()
        var calls = 0
        // Async completion path: the callback runs on the storage thread and
        // throws after receiving the committed Success.
        service.equip(uuid, TitleId("vip"), "test") { _ ->
            calls++
            throw IllegalStateException("callback boom")
        }
        await<SelectionResult> { cb ->
            service.unequip(uuid, TitleSlot.PREFIX, UnequipTarget.One(TitleId("vip")), "test", cb)
        }
        assertEquals(1, calls, "the callback must fire exactly once, got $calls")

        val next = await<SelectionResult> { cb -> service.equip(uuid, TitleId("helper"), "test", callback = cb) }
        assertTrue(next is SelectionResult.Success, "queue must keep working, got $next")
    }

    // ------------------------------------------------------------------
    // pre-commit crashes keep the honest INTERNAL_ERROR answer
    // ------------------------------------------------------------------

    @Test
    fun `a pre-commit crash reports INTERNAL_ERROR exactly once and keeps state untouched`() {
        create()
        flaky.throwFor = uuid
        val results = mutableListOf<SelectionResult>()
        service.equip(uuid, TitleId("vip"), "test") { results += it }
        flaky.throwFor = null

        val rejected = results.single() as SelectionResult.Rejected
        assertEquals(RejectionReason.INTERNAL_ERROR, rejected.reason)
        assertTrue(
            rejected.detail!!.contains("permission backend down"),
            "the crash cause must surface in the detail, got '${rejected.detail}'",
        )
        assertEquals(emptyList(), service.stateOf(uuid).prefixes, "nothing may be equipped on a pre-commit crash")

        val next = await<SelectionResult> { cb -> service.equip(uuid, TitleId("vip"), "test", callback = cb) }
        assertTrue(next is SelectionResult.Success, "queue must keep working, got $next")
    }

    private fun <T> await(start: ((T) -> Unit) -> Unit): T {
        val future = CompletableFuture<T>()
        start { value -> future.complete(value) }
        return future.get(5, TimeUnit.SECONDS)
    }
}
