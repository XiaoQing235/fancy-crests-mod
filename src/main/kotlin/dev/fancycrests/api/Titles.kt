package dev.fancycrests.api

import java.util.UUID
import net.minecraft.network.chat.Component

/**
 * Stable identifier of a title in the catalog. Never derived from display text;
 * survives renames and reloads.
 */
@JvmInline
value class TitleId(val value: String) {
    override fun toString(): String = value
}

/** Slot a title occupies in the first-line template. */
enum class TitleSlot { PREFIX, SUFFIX }

/**
 * Immutable catalog entry for one title. Loaded from configuration by the core
 * module; consumed by menus, resolution and adapters.
 */
data class TitleDefinition(
    val id: TitleId,
    val slot: TitleSlot,
    val displayName: Component,
    val text: Component,
    /** Required permission node; null means the title is public. */
    val permission: String?,
    val category: String?,
    /** Item id used as the menu icon; may be null. */
    val iconItem: String?,
    val sortOrder: Int,
)

/** Immutable per-player selection state. Lists keep equip order, no duplicates. */
data class PlayerTitleState(
    val uuid: UUID,
    val prefixes: List<TitleId>,
    val suffixes: List<TitleId>,
    val nickname: String?,
    val revision: Long,
)
