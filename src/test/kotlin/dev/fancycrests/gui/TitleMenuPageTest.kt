package dev.fancycrests.gui

import dev.fancycrests.api.TitleDefinition
import dev.fancycrests.api.TitleId
import dev.fancycrests.api.TitleSlot
import kotlin.test.Test
import kotlin.test.assertEquals
import net.minecraft.network.chat.Component

class TitleMenuPageTest {
    private fun title(id: String, slot: TitleSlot, category: String, order: Int) = TitleDefinition(
        id = TitleId(id), slot = slot, displayName = Component.literal(id), text = Component.literal(id),
        permission = null, category = category, iconItem = null, sortOrder = order,
    )

    @Test
    fun `prefix and suffix pages show only their own titles in catalog order`() {
        val titles = listOf(
            title("suffix", TitleSlot.SUFFIX, "A", 0),
            title("later", TitleSlot.PREFIX, "B", 0),
            title("second", TitleSlot.PREFIX, "A", 2),
            title("first", TitleSlot.PREFIX, "A", 1),
        )
        assertEquals(listOf("first", "second", "later"), titlesForPage(titles, TitleMenuPage.PREFIX).map { it.id.value })
        assertEquals(listOf("suffix"), titlesForPage(titles, TitleMenuPage.SUFFIX).map { it.id.value })
        assertEquals(emptyList(), titlesForPage(titles, TitleMenuPage.NICKNAME))
    }

    @Test
    fun `two and a half pages use four rows without losing or repeating titles`() {
        val titles = (1..90).map { title("title_$it", TitleSlot.PREFIX, "Test", it) }
        val pages = (0..2).map { titlesOnPage(titles, it) }

        assertEquals(listOf(36, 36, 18), pages.map { it.size })
        assertEquals(titles, pages.flatten())
        assertEquals(emptyList(), titlesOnPage(titles, 3))
    }
}
