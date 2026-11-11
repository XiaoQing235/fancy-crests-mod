package dev.fancycrests.core.resolve

import dev.fancycrests.api.PlayerTitleState
import dev.fancycrests.core.TestFixtures
import dev.fancycrests.core.catalog.CatalogService
import dev.fancycrests.core.config.LegacyTextParser
import dev.fancycrests.core.config.TemplateSegment
import dev.fancycrests.core.config.TemplateSegmentType
import dev.fancycrests.core.permission.PermissionManager
import dev.fancycrests.core.refresh.PlaceholderServiceImpl
import dev.fancycrests.core.refresh.TemplateEngine
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Audit #10 regression: nicknames may carry legacy colour codes; the name
 * segment of the first line must parse them (a "&6Bob" nickname renders as
 * golden "Bob"), while code-free profile names keep the identical literal
 * component.
 */
class SnapshotResolverNicknameCodesTest {

    private val uuid = UUID.randomUUID()
    private val adapter = TestFixtures.FakeAdapter()

    private fun nameOnlyConfig() = TestFixtures.config().copy(
        firstLineTemplate = listOf(TemplateSegment(TemplateSegmentType.NAME, " ", null)),
    )

    /** Extracts the single name component out of a NAME-only first line. */
    private fun nameComponentOf(line: net.minecraft.network.chat.Component) =
        line.siblings.single().siblings.single()

    @Test
    fun `a nickname with legacy codes renders as a styled component`() {
        val config = nameOnlyConfig()
        val catalog = CatalogService(config.toTitleDefinitions())
        val permissions = PermissionManager(adapter)
        val resolver = SnapshotResolver(catalog, permissions, { config }, TemplateEngine(PlaceholderServiceImpl()))

        val state = PlayerTitleState(uuid, emptyList(), emptyList(), "&6Bob", 0)
        val resolved = resolver.resolve(state, "Bob", "minecraft:overworld")

        assertEquals(LegacyTextParser.parse("&6Bob"), nameComponentOf(resolved.firstLine))
    }

    @Test
    fun `a code-free profile name keeps the identical literal component`() {
        val config = nameOnlyConfig()
        val catalog = CatalogService(config.toTitleDefinitions())
        val permissions = PermissionManager(adapter)
        val resolver = SnapshotResolver(catalog, permissions, { config }, TemplateEngine(PlaceholderServiceImpl()))

        val state = PlayerTitleState(uuid, emptyList(), emptyList(), null, 0)
        val resolved = resolver.resolve(state, "Bob", "minecraft:overworld")

        assertEquals(LegacyTextParser.parse("Bob"), nameComponentOf(resolved.firstLine))
    }
}
