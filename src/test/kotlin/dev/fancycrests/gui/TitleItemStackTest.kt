package dev.fancycrests.gui

import dev.fancycrests.api.PlayerTitleState
import dev.fancycrests.api.TitleDefinition
import dev.fancycrests.api.TitleId
import dev.fancycrests.api.TitleSlot
import dev.fancycrests.core.selection.Eligibility
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import net.minecraft.SharedConstants
import net.minecraft.core.component.DataComponentMap
import net.minecraft.network.chat.Component
import net.minecraft.server.Bootstrap
import net.minecraft.world.item.Items

class TitleItemStackTest {
    private val uuid = UUID.randomUUID()
    private val id = TitleId("test")
    private val definition = TitleDefinition(
        id = id, slot = TitleSlot.PREFIX, displayName = Component.literal("Test"),
        text = Component.literal("[Test]"), permission = null, category = null, iconItem = null, sortOrder = 0,
    )

    @BeforeTest
    fun bindTestItemComponents() {
        SharedConstants.tryDetectVersion()
        Bootstrap.bootStrap()
        for (item in listOf(Items.NAME_TAG, Items.DIAMOND, Items.ENCHANTED_BOOK)) {
            val holder = item.builtInRegistryHolder()
            if (!holder.areComponentsBound()) holder.bindComponents(DataComponentMap.EMPTY)
        }
    }

    @Test
    fun `default icon is a name tag and only equipped titles glint`() {
        val empty = PlayerTitleState(uuid, emptyList(), emptyList(), null, 0)
        val equipped = empty.copy(prefixes = listOf(id))

        val available = titleItem(definition, empty, Eligibility.Public, 1)
        assertEquals(Items.NAME_TAG, available.item)
        assertFalse(available.hasFoil())

        val locked = titleItem(definition, empty, Eligibility.NotEligible, 1)
        assertEquals(Items.NAME_TAG, locked.item)
        assertFalse(locked.hasFoil())

        val selected = titleItem(definition, equipped, Eligibility.Public, 1)
        assertEquals(Items.NAME_TAG, selected.item)
        assertTrue(selected.hasFoil())
    }

    @Test
    fun `custom icon is preserved across equipment states`() {
        val custom = definition.copy(iconItem = "minecraft:diamond")
        val empty = PlayerTitleState(uuid, emptyList(), emptyList(), null, 0)
        val available = titleItem(custom, empty, Eligibility.Public, 1)
        val equipped = titleItem(custom, empty.copy(prefixes = listOf(id)), Eligibility.Public, 1)

        assertEquals(Items.DIAMOND, available.item)
        assertFalse(available.hasFoil())
        assertEquals(Items.DIAMOND, equipped.item)
        assertTrue(equipped.hasFoil())

        val inherentlyGlinting = definition.copy(iconItem = "minecraft:enchanted_book")
        assertFalse(titleItem(inherentlyGlinting, empty, Eligibility.Public, 1).hasFoil())
    }
}
