package dev.fancycrests.core.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Semantics of the legacy code parser: `&` is the primary prefix, `§` stays
 * accepted, doubled prefixes escape, and unknown codes never eat text.
 */
class LegacyTextParserTest {

    @Test
    fun `ampersand codes style exactly like section codes`() {
        assertEquals(LegacyTextParser.parse("§6Knight"), LegacyTextParser.parse("&6Knight"))
        assertEquals(LegacyTextParser.parse("§7a§lbold§r plain"), LegacyTextParser.parse("&7a&lbold&r plain"))
        // Different colours must still produce different components, so the
        // equality above is not vacuous.
        assertNotEquals(LegacyTextParser.parse("&6Knight"), LegacyTextParser.parse("&7Knight"))
    }

    @Test
    fun `plain text with an ampersand is left intact`() {
        assertEquals("Tom & Jerry", LegacyTextParser.parse("Tom & Jerry").string)
        // "& " is an unknown code pair: emitted literally.
        assertTrue(LegacyTextParser.parse("Tom & Jerry").string.contains("& "))
    }

    @Test
    fun `doubled prefixes escape into a literal prefix character`() {
        assertEquals("R&D", LegacyTextParser.parse("R&&D").string)
        assertEquals("a§b", LegacyTextParser.parse("a§§b").string)
        // Escapes survive a surrounding style.
        assertEquals("R&D", LegacyTextParser.parse("&6R&&D").string)
    }

    @Test
    fun `a lone trailing prefix stays literal`() {
        assertEquals("abc&", LegacyTextParser.parse("abc&").string)
        assertEquals("abc§", LegacyTextParser.parse("abc§").string)
    }

    @Test
    fun `codes apply styles instead of remaining in the text`() {
        assertEquals("Knight", LegacyTextParser.parse("&6Knight").string)
        assertEquals("Knightx", LegacyTextParser.parse("&6Knight§7x").string)
    }

    @Test
    fun `strip removes pairs of both prefixes and collapses escapes`() {
        assertEquals("Knightx", LegacyTextParser.strip("&6Knight§7x"))
        assertEquals("R&D", LegacyTextParser.strip("R&&D"))
        assertEquals("Tom Jerry", LegacyTextParser.strip("Tom & Jerry"))
        assertEquals("abc&", LegacyTextParser.strip("abc&"))
        assertEquals("plain", LegacyTextParser.strip("plain"))
    }

    @Test
    fun `known-codes-only strip keeps unknown pairs as visible characters`() {
        // Audit #5: an unknown pair renders literally (parse emits both
        // characters), so the known-codes-only mode must keep it too — the
        // default mode collapses "Bo&zb" to "Bob" and let nicknames bypass
        // length and pattern checks.
        assertEquals("Bo&zb", LegacyTextParser.strip("Bo&zb", knownCodesOnly = true))
        assertEquals("Bob", LegacyTextParser.strip("Bo&zb"))
        assertEquals("Tom &zJ", LegacyTextParser.strip("Tom &zJ", knownCodesOnly = true))
        assertEquals("Tom J", LegacyTextParser.strip("Tom &zJ"))
        // Known pairs still collapse in both modes, with either prefix and
        // either case.
        assertEquals("Bob", LegacyTextParser.strip("&6Bob", knownCodesOnly = true))
        assertEquals("ab", LegacyTextParser.strip("a&Fb", knownCodesOnly = true))
        assertEquals("ab", LegacyTextParser.strip("a&rb", knownCodesOnly = true))
        assertEquals("xy", LegacyTextParser.strip("x§7y", knownCodesOnly = true))
        assertEquals("CatNine", LegacyTextParser.strip("&cCat&lNine", knownCodesOnly = true))
        // Escapes and lone prefixes behave as in the default mode.
        assertEquals("Bob&D", LegacyTextParser.strip("Bob&&D", knownCodesOnly = true))
        assertEquals("abc&", LegacyTextParser.strip("abc&", knownCodesOnly = true))
    }

    @Test
    fun `containsLegacyCodes detects both prefixes`() {
        assertTrue(LegacyTextParser.containsLegacyCodes("&6Gold"))
        assertTrue(LegacyTextParser.containsLegacyCodes("§6Gold"))
        assertFalse(LegacyTextParser.containsLegacyCodes("plain text"))
    }
}
