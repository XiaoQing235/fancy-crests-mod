package dev.fancycrests.core.selection

import dev.fancycrests.core.TestFixtures
import dev.fancycrests.core.catalog.CatalogService
import dev.fancycrests.core.config.FancyCrestsConfig
import dev.fancycrests.core.config.NicknameConfig
import dev.fancycrests.core.event.TitleEventBus
import dev.fancycrests.core.permission.BuiltInBackend
import dev.fancycrests.core.permission.PermissionManager
import dev.fancycrests.core.refresh.PlaceholderServiceImpl
import dev.fancycrests.core.refresh.TemplateEngine
import dev.fancycrests.core.resolve.SnapshotPublisher
import dev.fancycrests.core.resolve.SnapshotResolver
import dev.fancycrests.core.storage.SqliteStorage
import java.util.UUID
import java.util.regex.Pattern
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Batch J3(b): the nickname is stored and rendered verbatim, so leading or
 * trailing whitespace must be rejected instead of silently trimmed — trimming
 * would change what the player asked for and hide the mistake.
 */
class NicknameWhitespaceTest {

    private val uuid = UUID.randomUUID()
    private val adapter = TestFixtures.FakeAdapter().apply { online = listOf(uuid) }
    private var config: FancyCrestsConfig = TestFixtures.config()
    private lateinit var storage: SqliteStorage
    private lateinit var service: SelectionService

    private fun create(cfg: FancyCrestsConfig = TestFixtures.config()) {
        config = cfg
        val catalog = CatalogService(cfg.toTitleDefinitions())
        val builtIn = BuiltInBackend().apply { reload(cfg.permissions.nodes) }
        val permissions = PermissionManager(adapter, listOf(builtIn))
        val placeholders = PlaceholderServiceImpl()
        val engine = TemplateEngine(placeholders)
        val resolver = SnapshotResolver(catalog, permissions, { config }, engine)
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
    fun `leading and trailing whitespace is rejected`() {
        create()
        for (text in listOf(" Bob", "Bob ", "\tBob", "Bob\t", "\nBob")) {
            val rejected = service.validateNickname(uuid, text, config)
            assertTrue(rejected != null, "expected '$text' to be rejected")
            assertEquals(RejectionReason.NICKNAME_PATTERN, rejected.reason)
            assertTrue(
                rejected.detail.orEmpty().contains("whitespace"),
                "the rejection must explain the whitespace rule, got '${rejected.detail}'",
            )
        }
    }

    @Test
    fun `a clean nickname still passes`() {
        create()
        assertNull(service.validateNickname(uuid, "Bob", config))
        assertNull(service.validateNickname(uuid, "Bob_1", config))
    }

    @Test
    fun `the raw storage cap follows the configured visible length`() {
        create(
            TestFixtures.config(
                nickname = NicknameConfig(
                    maxLength = 64,
                    minLength = 3,
                    pattern = Pattern.compile("[A-Za-z0-9_]+"),
                    reservedNames = listOf("server"),
                    allowSet = true,
                ),
            ),
        )
        // 64 visible characters * 4 + 8 headroom = 264 raw characters.
        val rejected = service.validateNickname(uuid, "A".repeat(265), config)
        assertTrue(rejected != null, "265 raw characters must be rejected")
        assertEquals(RejectionReason.NICKNAME_TOO_LONG, rejected.reason)
        assertTrue(rejected.detail.orEmpty().contains("raw"), "got '${rejected.detail}'")
    }
}
