package dev.fancycrests.gui

import dev.fancycrests.api.PlayerTitleState
import dev.fancycrests.api.TitleDefinition
import dev.fancycrests.api.TitleId
import dev.fancycrests.api.TitleSlot
import dev.fancycrests.core.config.LegacyTextParser
import dev.fancycrests.core.selection.Eligibility
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.TextColor
import net.minecraft.world.item.component.ItemLore

class TitleItemTest {
    private val uuid = UUID.randomUUID()

    private fun definition(slot: TitleSlot = TitleSlot.PREFIX) = TitleDefinition(
        id = TitleId("builder"),
        slot = slot,
        displayName = LegacyTextParser.parse("&oBuilder"),
        text = LegacyTextParser.parse("&a&o[BUILD]"),
        permission = null,
        category = "Test",
        iconItem = null,
        sortOrder = 0,
    )

    private fun state(prefixes: List<TitleId> = emptyList(), suffixes: List<TitleId> = emptyList()) =
        PlayerTitleState(uuid, prefixes, suffixes, null, 0)

    private fun assertUpright(component: Component) {
        for (part in component.toFlatList()) assertFalse(part.style.isItalic, "italic text: ${part.string}")
    }

    @Test
    fun `equipped position is in the name and unequip is bright red`() {
        val item = titleItemText(
            definition(), state(prefixes = listOf(TitleId("vip"), TitleId("builder"))), Eligibility.Public, 2,
        )
        val name = item.name
        val lore = ItemLore(item.lore).styledLines()
        assertEquals("Builder (Prefix #2)", name.string)
        assertEquals("Click to unequip", lore.last().string)
        assertEquals(TextColor.fromLegacyFormat(ChatFormatting.RED), lore.last().style.color)
        assertUpright(name)
        lore.forEach(::assertUpright)
    }

    @Test
    fun `full slot replaces equip prompt without blocking unequip`() {
        val full = state(prefixes = listOf(TitleId("vip")))
        val item = titleItemText(definition(), full, Eligibility.Public, 1)
        assertEquals("Prefix slots full", ItemLore(item.lore).styledLines().last().string)

        val equipped = titleItemText(definition(), state(prefixes = listOf(TitleId("builder"))), Eligibility.Public, 1)
        assertEquals("Click to unequip", ItemLore(equipped.lore).styledLines().last().string)
    }

    @Test
    fun `suffix uses its own capacity and position`() {
        val item = titleItemText(
            definition(TitleSlot.SUFFIX), state(prefixes = listOf(TitleId("vip"))), Eligibility.Public, 1,
        )
        assertEquals("Click to equip", ItemLore(item.lore).styledLines().last().string)
        val equipped = titleItemText(
            definition(TitleSlot.SUFFIX), state(suffixes = listOf(TitleId("builder"))), Eligibility.Public, 1,
        )
        assertEquals("Builder (Suffix #1)", equipped.name.string)
    }
}
