package dev.fancycrests.gui

import dev.fancycrests.api.PlayerTitleState
import dev.fancycrests.api.TitleDefinition
import dev.fancycrests.api.TitleSlot
import dev.fancycrests.core.selection.Eligibility
import net.minecraft.ChatFormatting
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.resources.Identifier
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.ItemLore

/** Remove inherited and explicitly configured italic styles from GUI text only. */
internal fun withoutItalic(component: Component): MutableComponent =
    component.plainCopy().setStyle(component.style.withItalic(false)).also { copy ->
        component.siblings.forEach { copy.append(withoutItalic(it)) }
    }

internal data class TitleItemText(val name: Component, val lore: List<Component>)

internal fun titleItem(
    definition: TitleDefinition,
    state: PlayerTitleState,
    eligibility: Eligibility,
    slotLimit: Int,
): ItemStack {
    val equipped = when (definition.slot) {
        TitleSlot.PREFIX -> state.prefixes
        TitleSlot.SUFFIX -> state.suffixes
    }
    val index = equipped.indexOf(definition.id)
    val locked = eligibility == Eligibility.NotEligible
    val fallback = when {
        locked -> Items.DYE.gray()
        index >= 0 -> Items.NAME_TAG
        else -> Items.PAPER
    }
    val item = definition.iconItem
        ?.let { Identifier.tryParse(it) }
        ?.let { BuiltInRegistries.ITEM.getOptional(it).orElse(null) }
        ?: fallback
    val stack = ItemStack(item)
    val text = titleItemText(definition, state, eligibility, slotLimit)
    stack.set(DataComponents.CUSTOM_NAME, text.name)
    stack.set(DataComponents.LORE, ItemLore(text.lore))
    return stack
}

internal fun titleItemText(
    definition: TitleDefinition,
    state: PlayerTitleState,
    eligibility: Eligibility,
    slotLimit: Int,
): TitleItemText {
    val equipped = when (definition.slot) {
        TitleSlot.PREFIX -> state.prefixes
        TitleSlot.SUFFIX -> state.suffixes
    }
    val index = equipped.indexOf(definition.id)
    val locked = eligibility == Eligibility.NotEligible
    val slotName = if (definition.slot == TitleSlot.PREFIX) "Prefix" else "Suffix"
    val name = withoutItalic(definition.displayName)
    if (locked) name.withStyle(ChatFormatting.GRAY)
    if (index >= 0) name.append(Component.literal(" ($slotName #${index + 1})").withStyle(ChatFormatting.GRAY))

    val lore = mutableListOf<Component>()
    lore += Component.literal("Preview: ").withStyle(ChatFormatting.GRAY).append(definition.text.copy())
    lore += Component.literal(
        "Slot: ${slotName.lowercase()}" + (definition.category?.let { " | Category: $it" } ?: ""),
    ).withStyle(ChatFormatting.DARK_GRAY)
    lore += when {
        index >= 0 -> Component.literal("Click to unequip").withStyle(ChatFormatting.RED)
        locked -> Component.literal(
            if (definition.permission != null) {
                "Locked: requires permission '${definition.permission}' or an admin grant"
            } else {
                "Locked: requires an admin grant"
            },
        ).withStyle(ChatFormatting.RED)
        equipped.size >= slotLimit -> Component.literal("$slotName slots full").withStyle(ChatFormatting.RED)
        else -> Component.literal("Click to equip").withStyle(ChatFormatting.GREEN)
    }
    return TitleItemText(withoutItalic(name), lore.map(::withoutItalic))
}
