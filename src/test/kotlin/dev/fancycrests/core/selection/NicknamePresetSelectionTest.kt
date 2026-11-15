package dev.fancycrests.core.selection

import dev.fancycrests.api.PermissionVerdict
import dev.fancycrests.api.Permissions
import dev.fancycrests.core.TestFixtures
import dev.fancycrests.core.catalog.CatalogService
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NicknamePresetSelectionTest {
    private val uuid = UUID.randomUUID()
    private val adapter = TestFixtures.FakeAdapter().apply { online = listOf(uuid) }
    @Volatile private var config = TestFixtures.config().let { it.copy(nickname = it.nickname.copy(presets = listOf("&aExplorer", "server"))) }
    private val backend = BuiltInBackend()
    private lateinit var permissions: PermissionManager
    private lateinit var storage: SqliteStorage
    private lateinit var events: TitleEventBus
    private lateinit var service: SelectionService

    private fun create() {
        val catalog = CatalogService(config.toTitleDefinitions())
        permissions = PermissionManager(adapter, listOf(backend))
        val resolver = SnapshotResolver(catalog, permissions, { config }, TemplateEngine(PlaceholderServiceImpl()))
        val publisher = SnapshotPublisher(resolver, adapter, { config }, { null })
        events = TitleEventBus(TestFixtures.directExecutor)
        storage = SqliteStorage(null, TestFixtures.directExecutor)
        storage.start()
        service = SelectionService(
            adapter = adapter, storage = storage, catalog = catalog, permissions = permissions,
            configProvider = { config }, eventBus = events, publisher = publisher,
            initialStates = emptyMap(), initialOwnership = emptyList(),
        )
    }

    @AfterTest
    fun tearDown() {
        if (this::storage.isInitialized) storage.stop()
    }

    private fun select(text: String): SelectionResult {
        val result = CompletableFuture<SelectionResult>()
        service.selectNicknamePreset(uuid, text, "test") { result.complete(it) }
        return result.get(5, TimeUnit.SECONDS)
    }

    private fun custom(text: String): SelectionResult {
        val result = CompletableFuture<SelectionResult>()
        service.setCustomNickname(uuid, text, "test") { result.complete(it) }
        return result.get(5, TimeUnit.SECONDS)
    }

    @Test
    fun `preset selection does not grant arbitrary nickname permission`() {
        create()
        assertEquals(PermissionVerdict.DENY, permissions.check(uuid, Permissions.NICKNAME_SET))
        assertTrue(select("&aExplorer") is SelectionResult.Success)
        assertEquals("&aExplorer", service.stateOf(uuid).nickname)
        assertEquals(RejectionReason.NOT_ELIGIBLE, (select("Unconfigured") as SelectionResult.Rejected).reason)
        assertEquals(RejectionReason.NICKNAME_RESERVED, (select("server") as SelectionResult.Rejected).reason)
    }

    @Test
    fun `selection rechecks menu permission and global nickname setting`() {
        create()
        backend.reload(TestFixtures.config(deny = mapOf(uuid to listOf(Permissions.MENU))).permissions.nodes)
        assertEquals(RejectionReason.MISSING_PERMISSION, (select("&aExplorer") as SelectionResult.Rejected).reason)
        backend.reload(emptyMap())
        config = config.copy(nickname = config.nickname.copy(allowSet = false))
        assertEquals(RejectionReason.NICKNAME_DISABLED, (select("&aExplorer") as SelectionResult.Rejected).reason)
    }

    @Test
    fun `custom nickname requires its own node independently of preset access`() {
        create()
        assertEquals(RejectionReason.MISSING_PERMISSION, (custom("Custom") as SelectionResult.Rejected).reason)
        backend.reload(TestFixtures.config(allow = mapOf(uuid to listOf(Permissions.NICKNAME_SET))).permissions.nodes)
        assertTrue(custom("Custom") is SelectionResult.Success)
        backend.reload(TestFixtures.config(deny = mapOf(uuid to listOf(Permissions.NICKNAME_SET))).permissions.nodes)
        assertEquals(RejectionReason.MISSING_PERMISSION, (custom("Another") as SelectionResult.Rejected).reason)
        assertTrue(select("&aExplorer") is SelectionResult.Success)
    }

    @Test
    fun `a preset removed while the request waits cannot be selected`() {
        create()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        events.addPreListener { event: TitleChangeEventPre ->
            if (event.type == TitleOperationType.NICKNAME_SET && release.count == 1L) {
                entered.countDown()
                release.await()
            }
        }
        val first = Thread { service.setNickname(uuid, "First", "test") {} }.apply { isDaemon = true; start() }
        val result = CompletableFuture<SelectionResult>()
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            service.selectNicknamePreset(uuid, "&aExplorer", "test") { result.complete(it) }
            config = config.copy(nickname = config.nickname.copy(presets = emptyList()))
        } finally {
            release.countDown()
        }
        first.join(5000)
        assertEquals(RejectionReason.NOT_ELIGIBLE, (result.get(5, TimeUnit.SECONDS) as SelectionResult.Rejected).reason)
        assertEquals("First", service.stateOf(uuid).nickname)
    }

    @Test
    fun `custom permission revoked while a request waits is rechecked`() {
        create()
        backend.reload(TestFixtures.config(allow = mapOf(uuid to listOf(Permissions.NICKNAME_SET))).permissions.nodes)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        events.addPreListener { event: TitleChangeEventPre ->
            if (event.type == TitleOperationType.NICKNAME_SET && release.count == 1L) {
                entered.countDown()
                release.await()
            }
        }
        val first = Thread { service.setNickname(uuid, "First", "test") {} }.apply { isDaemon = true; start() }
        val result = CompletableFuture<SelectionResult>()
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            service.setCustomNickname(uuid, "Custom", "test") { result.complete(it) }
            backend.reload(TestFixtures.config(deny = mapOf(uuid to listOf(Permissions.NICKNAME_SET))).permissions.nodes)
        } finally {
            release.countDown()
        }
        first.join(5000)
        assertEquals(RejectionReason.MISSING_PERMISSION, (result.get(5, TimeUnit.SECONDS) as SelectionResult.Rejected).reason)
        assertEquals("First", service.stateOf(uuid).nickname)
    }
}
