package dev.fancycrests.core.refresh

import dev.fancycrests.api.PlaceholderKey
import dev.fancycrests.api.PlaceholderRequest
import dev.fancycrests.api.PlayerTitleState
import dev.fancycrests.api.StandardPlaceholders
import dev.fancycrests.core.TestFixtures
import dev.fancycrests.core.catalog.CatalogService
import dev.fancycrests.core.config.LegacyTextParser
import dev.fancycrests.core.resolve.PublishedPlayerView
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Audit #10 (placeholder half): the `%fancycrests:nickname%` resolver must
 * parse legacy colour codes in nicknames instead of printing them literally.
 * Profile-name fallback stays code-free and identical.
 */
class PlaceholdersNicknameCodesTest {

    private val uuid = UUID.randomUUID()

    private fun service(
        stateOf: (UUID) -> PlayerTitleState,
        viewOf: (UUID) -> PublishedPlayerView?,
    ): PlaceholderServiceImpl {
        val config = TestFixtures.config()
        val catalog = CatalogService(config.toTitleDefinitions())
        val service = PlaceholderServiceImpl()
        val engine = TemplateEngine(service)
        registerStandardPlaceholders(
            service = service,
            engine = engine,
            stateOf = stateOf,
            viewOf = viewOf,
            catalogOf = { catalog },
            configOf = { config },
        )
        return service
    }

    @Test
    fun `the nickname placeholder parses legacy colour codes`() {
        val state = PlayerTitleState(uuid, emptyList(), emptyList(), "&6Bob", 0)
        val service = service(stateOf = { state }, viewOf = { null })

        val resolved = service.resolve(StandardPlaceholders.NICKNAME, PlaceholderRequest(uuid, observer = null))

        assertEquals(LegacyTextParser.parse("&6Bob"), resolved)
    }

    @Test
    fun `the profile-name fallback keeps its literal shape`() {
        val state = PlayerTitleState(uuid, emptyList(), emptyList(), null, 0)
        val view = PublishedPlayerView(uuid, "Bob", state, snapshot = null)
        val service = service(stateOf = { state }, viewOf = { view })

        val resolved = service.resolve(StandardPlaceholders.NICKNAME, PlaceholderRequest(uuid, observer = null))

        assertEquals(LegacyTextParser.parse("Bob"), resolved)
    }

    @Test
    fun `unknown without nickname or view resolves to null`() {
        val state = PlayerTitleState(uuid, emptyList(), emptyList(), null, 0)
        val service = service(stateOf = { state }, viewOf = { null })
        assertNull(service.resolve(PlaceholderKey("nickname"), PlaceholderRequest(uuid, observer = null)))
    }
}
