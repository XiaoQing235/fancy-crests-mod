package dev.fancycrests.core.selection

import dev.fancycrests.api.Permissions
import dev.fancycrests.core.TestFixtures
import dev.fancycrests.core.catalog.CatalogService
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
 * The nickname validation matrix. Every check must hold on the RAW input as
 * well as the code-stripped visible text: the stored and rendered value is
 * the raw string, so anything forbidden must be rejected before a `&`/`§`
 * pair collapses it away (audit H-1 regression).
 */
class NicknameValidationTest {

    private val uuid = UUID.randomUUID()
    private val other = UUID.randomUUID()
    private val adapter = TestFixtures.FakeAdapter().apply {
        online = listOf(uuid, other)
        knownNames = mapOf("CatNine" to uuid)
    }

    private var config = TestFixtures.config()
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

    private fun nickname(text: String?): SelectionResult {
        val future = CompletableFuture<SelectionResult>()
        service.setNickname(uuid, text, "test") { future.complete(it) }
        return future.get(5, TimeUnit.SECONDS)
    }

    @Test
    fun `a bidi override hidden behind a code pair is rejected`() {
        create()
        val result = nickname("Bob&\u202E")
        assertEquals(RejectionReason.NICKNAME_PATTERN, (result as SelectionResult.Rejected).reason)
    }

    @Test
    fun `a control character hidden behind the legacy prefix is rejected`() {
        create()
        val result = nickname("Bob§\n")
        assertEquals(RejectionReason.NICKNAME_PATTERN, (result as SelectionResult.Rejected).reason)
    }

    @Test
    fun `the raw storage bound is enforced beyond the visible limit`() {
        create()
        // Visible text is only "abc" (3 chars, pattern-valid), but 36 code
        // pairs push the raw length past the cap (16*4+8 = 72).
        val text = "&l".repeat(36) + "abc"
        assertTrue(text.length > 72)
        val result = nickname(text)
        assertEquals(RejectionReason.NICKNAME_TOO_LONG, (result as SelectionResult.Rejected).reason)
    }

    @Test
    fun `a formatted nickname within both bounds is accepted`() {
        create()
        val result = nickname("Cat&cNine")
        assertTrue(result is SelectionResult.Success, "expected success, got $result")
        assertEquals("Cat&cNine", service.stateOf(uuid).nickname)
    }

    @Test
    fun `codes cannot disguise a reserved name`() {
        create()
        val result = nickname("&cserver")
        assertEquals(RejectionReason.NICKNAME_RESERVED, (result as SelectionResult.Rejected).reason)
    }

    @Test
    fun `codes cannot disguise the real name of another player`() {
        create()
        val future = CompletableFuture<SelectionResult>()
        service.setNickname(other, "&cCatNine", "test") { future.complete(it) }
        val result = future.get(5, TimeUnit.SECONDS)
        assertEquals(RejectionReason.NICKNAME_OCCUPIED, (result as SelectionResult.Rejected).reason)
    }

    @Test
    fun `the bypass node skips reserved and occupied checks only`() {
        create()
        config = TestFixtures.config(allow = mapOf(uuid to listOf(Permissions.NICKNAME_BYPASS_RESERVED)))
        create()
        val result = nickname("&cserver")
        assertTrue(result is SelectionResult.Success, "bypass should allow the reserved name, got $result")
        // Forbidden characters stay rejected even with the bypass node.
        val bidi = nickname("&cser&\u202Ever")
        assertEquals(RejectionReason.NICKNAME_PATTERN, (bidi as SelectionResult.Rejected).reason)
    }

    @Test
    fun `unknown code pairs count as visible characters`() {
        create()
        // Old bug: validation collapsed the unknown "&z" pair away
        // ("Bo&zb" → "Bob", 3 chars, pattern-clean) while rendering shows
        // all five characters, bypassing both the length cap and the
        // pattern.
        val result = nickname("Bo&zb")
        assertEquals(RejectionReason.NICKNAME_PATTERN, (result as SelectionResult.Rejected).reason)

        // Known codes still collapse: the visible text is "CatNine".
        val ok = nickname("&6CatNine")
        assertTrue(ok is SelectionResult.Success, "known codes must still strip for validation, got $ok")
        assertEquals("&6CatNine", service.stateOf(uuid).nickname)
    }
}
