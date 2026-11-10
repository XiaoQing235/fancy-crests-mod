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
    fun `containsLegacyCodes detects both prefixes`() {
        assertTrue(LegacyTextParser.containsLegacyCodes("&6Gold"))
        assertTrue(LegacyTextParser.containsLegacyCodes("§6Gold"))
        assertFalse(LegacyTextParser.containsLegacyCodes("plain text"))
    }
}
