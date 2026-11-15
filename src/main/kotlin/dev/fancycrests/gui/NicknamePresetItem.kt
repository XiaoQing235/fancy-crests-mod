package dev.fancycrests.gui

import dev.fancycrests.core.config.LegacyTextParser
import net.minecraft.ChatFormatting
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.ItemLore

internal fun nicknamePresetItem(text: String, selected: Boolean, enabled: Boolean): ItemStack {
    val stack = ItemStack(Items.NAME_TAG)
    stack.set(DataComponents.CUSTOM_NAME, withoutItalic(LegacyTextParser.parse(text)))
    stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, selected)
    val prompt = when {
        selected -> Component.literal("Click to reset").withStyle(ChatFormatting.RED)
        enabled -> Component.literal("Click to select").withStyle(ChatFormatting.GREEN)
        else -> Component.literal("Nicknames are disabled").withStyle(ChatFormatting.RED)
    }
    stack.set(DataComponents.LORE, ItemLore(listOf(withoutItalic(prompt))))
    return stack
}
