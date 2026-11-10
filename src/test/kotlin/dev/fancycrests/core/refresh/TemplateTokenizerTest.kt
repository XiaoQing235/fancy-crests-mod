package dev.fancycrests.core.refresh

import dev.fancycrests.core.refresh.TemplateTokenizer.Segment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Pure string-level checks of the template tokenizer (no Minecraft types). */
class TemplateTokenizerTest {

    @Test
    fun `static template stays one static segment`() {
        assertEquals(
            listOf(Segment.Static("§7Welcome")),
            TemplateTokenizer.tokenize("§7Welcome"),
        )
    }

    @Test
    fun `mixed template splits static and placeholder segments`() {
        assertEquals(
            listOf(
                Segment.Static("§7Hello "),
                Segment.Placeholder("nickname"),
                Segment.Static(" §8— "),
                Segment.Placeholder("belowname"),
            ),
            TemplateTokenizer.tokenize("§7Hello %fancycrests:nickname% §8— %fancycrests:belowname%"),
        )
    }

    @Test
    fun `placeholder-only template`() {
        assertEquals(
            listOf(Segment.Placeholder("prefix")),
            TemplateTokenizer.tokenize("%fancycrests:prefix%"),
        )
    }

    @Test
    fun `foreign namespace and malformed tokens stay literal`() {
        // Player or admin text from other namespaces must never become an
        // expression — only fancycrests keys resolve.
        assertEquals(
            listOf(Segment.Static("%other:player% %fancycrests:")),
            TemplateTokenizer.tokenize("%other:player% %fancycrests:"),
        )
        assertFalse(TemplateTokenizer.containsPlaceholders("%other:player% %fancycrests:"))
    }

    @Test
    fun `repeated tokens each resolve`() {
        val segments = TemplateTokenizer.tokenize("A%fancycrests:nickname%B%fancycrests:nickname%C")
        assertEquals(
            listOf(
                Segment.Static("A"),
                Segment.Placeholder("nickname"),
                Segment.Static("B"),
                Segment.Placeholder("nickname"),
                Segment.Static("C"),
            ),
            segments,
        )
        assertTrue(TemplateTokenizer.containsPlaceholders("A%fancycrests:nickname%"))
    }

    @Test
    fun `empty template has no segments`() {
        assertTrue(TemplateTokenizer.tokenize("").isEmpty())
    }
}
