package dev.fancycrests.core.resolve

import dev.fancycrests.api.PlayerTitleState
import dev.fancycrests.api.TitleId
import dev.fancycrests.api.TitleSlot
import dev.fancycrests.core.TestFixtures
import dev.fancycrests.core.catalog.CatalogService
import dev.fancycrests.core.config.LegacyTextParser
import dev.fancycrests.core.config.TemplateSegment
import dev.fancycrests.core.config.TemplateSegmentConfig
import dev.fancycrests.core.config.TemplateSegmentType
import dev.fancycrests.core.permission.PermissionManager
import dev.fancycrests.core.refresh.PlaceholderServiceImpl
import dev.fancycrests.core.refresh.TemplateEngine
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Review IV: per-channel first-line templates ([SnapshotResolver.resolveFor])
 * and the two-level separator semantics — titles *within* a segment are
 * joined by `separator`, *between* segments by the preceding segment's
 * `segmentSeparator ?: separator`.
 */
class ChannelTemplateResolveTest {

    private val uuid = UUID.randomUUID()
    private val adapter = TestFixtures.FakeAdapter()

    private fun config() = TestFixtures.config(
        titles = listOf(
            TestFixtures.title("p1", TitleSlot.PREFIX),
            TestFixtures.title("p2", TitleSlot.PREFIX),
            TestFixtures.title("s1", TitleSlot.SUFFIX),
        ),
    ).copy(
        firstLineTemplate = listOf(
            TemplateSegment(TemplateSegmentType.PREFIXES, ",", null),
            TemplateSegment(TemplateSegmentType.NAME, "", null),
            TemplateSegment(TemplateSegmentType.SUFFIXES, ";", null),
        ),
    )

    private fun resolverOf(cfg: dev.fancycrests.core.config.FancyCrestsConfig) = SnapshotResolver(
        CatalogService(cfg.toTitleDefinitions()),
        PermissionManager(adapter),
        { cfg },
        TemplateEngine(PlaceholderServiceImpl()),
    )

    private fun state(nickname: String? = null) =
        PlayerTitleState(uuid, listOf(TitleId("p1"), TitleId("p2")), listOf(TitleId("s1")), nickname, 0)

    // ------------------------------------------------------------------
    // null / empty / override — the three template states
    // ------------------------------------------------------------------

    @Test
    fun `null channel template falls back to the global template`() {
        val cfg = config()
        val resolver = resolverOf(cfg)
        val viaChannel = resolver.resolveFor(null, state(), "Bob", "minecraft:overworld")
        val global = resolver.resolve(state(), "Bob", "minecraft:overworld")
        assertEquals(global.firstLine, viaChannel.firstLine)
        assertEquals("[p1],[p2],Bob[s1]", viaChannel.firstLine.string)
    }

    @Test
    fun `an empty channel template renders an empty first line component`() {
        val cfg = config()
        val resolver = resolverOf(cfg)
        val resolved = resolver.resolveFor(emptyList(), state(), "Bob", "minecraft:overworld")
        // Empty component: no siblings, empty plain text — the head channel
        // renders a blank first line (only the second line, if any).
        assertEquals("", resolved.firstLine.string)
        assertTrue(resolved.firstLine.siblings.isEmpty())
        assertEquals(net.minecraft.network.chat.Component.empty(), resolved.firstLine)
    }

    @Test
    fun `a non-empty channel template fully overrides the global one`() {
        val cfg = config()
        val resolver = resolverOf(cfg)
        // Reordered: suffixes first, then the name; no prefixes at all.
        val override = listOf(
            TemplateSegmentConfig(TemplateSegmentType.SUFFIXES, separator = "+"),
            TemplateSegmentConfig(TemplateSegmentType.NAME, separator = ""),
        )
        val resolved = resolver.resolveFor(override, state(), "Bob", "minecraft:overworld")
        assertEquals("[s1]+Bob", resolved.firstLine.string)
    }

    // ------------------------------------------------------------------
    // Two-level separator matrix
    // ------------------------------------------------------------------

    private fun matrixConfig(
        prefixSep: String,
        prefixSegmentSep: String?,
        nameSegmentSep: String?,
    ) = listOf(
        TemplateSegmentConfig(
            TemplateSegmentType.PREFIXES,
            separator = prefixSep,
            segmentSeparator = prefixSegmentSep,
        ),
        TemplateSegmentConfig(
            TemplateSegmentType.NAME,
            separator = "",
            segmentSeparator = nameSegmentSep,
        ),
        TemplateSegmentConfig(TemplateSegmentType.SUFFIXES, separator = "/"),
    )

    @Test
    fun `null segmentSeparator falls back to the segment separator between segments`() {
        val resolver = resolverOf(config())
        val template = matrixConfig(prefixSep = ",", prefixSegmentSep = null, nameSegmentSep = null)
        val resolved = resolver.resolveFor(template, state(), "Bob", "minecraft:overworld")
        // prefixes->name uses PREFIXES' separator (fallback), name->suffixes
        // uses NAME's separator "" (i.e. none); trailing separators of the
        // last segment are never emitted.
        assertEquals("[p1],[p2],Bob[s1]", resolved.firstLine.string)
    }

    @Test
    fun `segmentSeparator replaces the separator between segments only`() {
        val resolver = resolverOf(config())
        val template = matrixConfig(prefixSep = ",", prefixSegmentSep = " | ", nameSegmentSep = " # ")
        val resolved = resolver.resolveFor(template, state(), "Bob", "minecraft:overworld")
        // Within the prefixes segment: ",". Between segments: the dedicated
        // segment separators.
        assertEquals("[p1],[p2] | Bob # [s1]", resolved.firstLine.string)
    }

    @Test
    fun `empty segment segments produce no stray separators`() {
        val cfg = config()
        val resolver = resolverOf(cfg)
        // No prefixes equipped: the PREFIXES segment renders nothing, so no
        // separator may appear where it would have stood.
        val template = matrixConfig(prefixSep = ",", prefixSegmentSep = " | ", nameSegmentSep = " # ")
        val noPrefixes = PlayerTitleState(uuid, emptyList(), listOf(TitleId("s1")), null, 0)
        val resolved = resolver.resolveFor(template, noPrefixes, "Bob", "minecraft:overworld")
        assertEquals("Bob # [s1]", resolved.firstLine.string)
    }

    @Test
    fun `separatorStyle styles both separator kinds`() {
        val cfg = config()
        val resolver = resolverOf(cfg)
        val template = listOf(
            TemplateSegmentConfig(
                TemplateSegmentType.PREFIXES,
                separator = ",",
                separatorStyle = "&6",
                segmentSeparator = "&7 | ",
            ),
            TemplateSegmentConfig(TemplateSegmentType.NAME, separator = ""),
        )
        val resolved = resolver.resolveFor(template, state(), "Bob", "minecraft:overworld")
        assertEquals("[p1],[p2] | Bob", resolved.firstLine.string)
        val prefixPart = resolved.firstLine.siblings[0]
        // Both separator components were built by the same
        // separatorStyle + text parse the within-segment separator always
        // uses; the dedicated segment separator accepts the style prefix
        // too (and may embed its own codes that override it).
        val within = prefixPart.siblings[1] // between [p1] and [p2]
        assertEquals(LegacyTextParser.parse("&6,"), within)
        assertEquals(LegacyTextParser.parse("&6&7 | "), resolved.firstLine.siblings[1])
    }
}
