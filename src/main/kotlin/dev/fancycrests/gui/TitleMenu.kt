package dev.fancycrests.gui

import dev.fancycrests.api.Permissions
import dev.fancycrests.api.PermissionVerdict
import dev.fancycrests.api.TitleDefinition
import dev.fancycrests.api.TitleId
import dev.fancycrests.api.TitleSlot
import dev.fancycrests.core.CoreServices
import dev.fancycrests.core.selection.Eligibility
import dev.fancycrests.core.selection.RejectionReason
import dev.fancycrests.core.selection.SelectionResult
import dev.fancycrests.core.selection.UnequipTarget
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import net.minecraft.ChatFormatting
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.Container
import net.minecraft.world.SimpleContainer
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.ChestMenu
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.inventory.MenuType
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.ItemLore

/** One clickable menu entry. */
private sealed interface MenuAction {
    /** Equip or unequip the title in [slot] (direction decided from live state). */
    data class ToggleTitle(val titleId: TitleId, val slot: TitleSlot) : MenuAction

    data object PreviousPage : MenuAction

    data object NextPage : MenuAction

    /** Sends the nickname command hint to the viewer. */
    data object NicknameHint : MenuAction
}

/** Identity of the world the menu was built from; mismatch rejects clicks. */
private data class MenuVersion(val catalog: Long, val config: Long, val revision: Long)

/**
 * Server-side chest menu browsing the whole title catalog (plan chapter 6
 * "GUI 一致性").
 *
 * Security model:
 *  - the menu is display-only; no authorization is carried in item names,
 *    NBT or slot numbers — every click re-derives the action from the live
 *    catalog/selection state server-side;
 *  - every click re-checks the catalog version, config version and the
 *    player's state revision captured when the menu was (re)rendered; any
 *    mismatch rejects the click and re-renders from live data ("已过期 → 刷新");
 *  - equip/unequip go exclusively through SelectionService (same serialized
 *    per-player pipeline as commands — the menu is never a second door into
 *    permissions);
 *  - a per-menu debounce flag ignores clicks while a selection operation is
 *    still in flight, so double clicks, rapid clicking and duplicate requests
 *    cannot double-apply (SelectionService's serial queue guarantees
 *    idempotent sequencing on top).
 *
 * Layout: 6-row chest. Slots 0..44 are title entries of the current page
 * (sorted by category, then sortOrder, then id); 45 = previous page,
 * 46 = nickname hint (only when enabled and permitted), 49 = page indicator,
 * 53 = next page.
 */
class TitleMenu private constructor(
    containerId: Int,
    playerInventory: Inventory,
    private val viewer: ServerPlayer,
    private val core: CoreServices,
    private val backing: SimpleContainer,
) : ChestMenu(MenuType.GENERIC_9x6, containerId, playerInventory, backing, ROWS) {

    constructor(
        containerId: Int,
        playerInventory: Inventory,
        viewer: ServerPlayer,
        core: CoreServices,
    ) : this(containerId, playerInventory, viewer, core, SimpleContainer(ROWS * 9))

    private val viewerUuid: UUID = viewer.gameProfile.id()

    /** Version the current rendering is based on. */
    private var renderedVersion: MenuVersion = captureVersion()

    /** True while a selection operation for this menu is in flight. */
    private val clickInFlight = AtomicBoolean(false)

    /** Slot index -> action for the current page. Main thread only. */
    private val actions = HashMap<Int, MenuAction>()

    init {
        renderPage()
    }

    // ------------------------------------------------------------------
    // Vanilla container plumbing
    // ------------------------------------------------------------------

    /** No backing block entity: the menu is valid as long as the viewer is around. */
    override fun stillValid(player: Player): Boolean = player.uuid == viewerUuid

    /** Display items never move. */
    override fun quickMoveStack(player: Player, index: Int): ItemStack = ItemStack.EMPTY

    override fun clicked(slotIndex: Int, button: Int, input: ContainerInput, player: Player) {
        if (slotIndex in 0 until ROWS * 9) {
            // Consume every click in the menu area: items are display-only.
            onMenuClick(slotIndex)
            return
        }
        if (input == ContainerInput.QUICK_MOVE) return
        super.clicked(slotIndex, button, input, player)
    }

    // ------------------------------------------------------------------
    // Click handling (main thread)
    // ------------------------------------------------------------------

    private fun onMenuClick(slot: Int) {
        when (val action = actions[slot]) {
            null -> Unit
            MenuAction.PreviousPage -> {
                if (page > 0) {
                    page -= 1
                    renderPage()
                }
            }
            MenuAction.NextPage -> {
                if (page < pageCount() - 1) {
                    page += 1
                    renderPage()
                }
            }
            MenuAction.NicknameHint -> {
                viewer.sendSystemMessage(
                    Component.literal("Set a nickname with /fancycrests nickname <text>, or reset it with /fancycrests nickname reset.")
                        .withStyle(ChatFormatting.GRAY),
                )
            }
            is MenuAction.ToggleTitle -> handleToggle(action)
        }
    }

    private fun handleToggle(action: MenuAction.ToggleTitle) {
        // Debounce: one in-flight selection operation per menu.
        if (!clickInFlight.compareAndSet(false, true)) return

        // Staleness gate: catalog, config or player state changed since the
        // page was rendered -> reject and re-render from live data.
        if (captureVersion() != renderedVersion) {
            clickInFlight.set(false)
            renderPage()
            viewer.sendSystemMessage(
                Component.literal("The title catalog changed; the menu was refreshed. Please click again.")
                    .withStyle(ChatFormatting.YELLOW),
            )
            return
        }

        val state = core.selectionService.stateOf(viewerUuid)
        val equipped = when (action.slot) {
            TitleSlot.PREFIX -> state.prefixes
            TitleSlot.SUFFIX -> state.suffixes
        }
        val alreadyEquipped = action.titleId in equipped
        val titleName = core.catalogService.get(action.titleId)?.displayName?.string ?: action.titleId.value
        val callback: (SelectionResult) -> Unit = { result ->
            try {
                when (result) {
                    is SelectionResult.Success -> Unit // state change already re-rendered below
                    is SelectionResult.Rejected ->
                        viewer.sendSystemMessage(
                            Component.literal(rejectionText(result, titleName)).withStyle(ChatFormatting.RED),
                        )
                    SelectionResult.Conflict ->
                        viewer.sendSystemMessage(
                            Component.literal("The change conflicted with another operation; nothing was modified.")
                                .withStyle(ChatFormatting.RED),
                        )
                    is SelectionResult.StorageFailure ->
                        viewer.sendSystemMessage(
                            Component.literal("Storage error, nothing was changed: ${result.message}")
                                .withStyle(ChatFormatting.RED),
                        )
                    SelectionResult.Cancelled ->
                        viewer.sendSystemMessage(
                            Component.literal("The change was cancelled by an event listener.").withStyle(ChatFormatting.RED),
                        )
                }
            } finally {
                renderPage()
                clickInFlight.set(false)
            }
        }
        if (alreadyEquipped) {
            core.selectionService.unequip(
                viewerUuid,
                action.slot,
                UnequipTarget.One(action.titleId),
                "menu",
                callback,
            )
        } else {
            core.selectionService.equip(viewerUuid, action.titleId, "menu", callback)
        }
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    private fun captureVersion() = MenuVersion(
        catalog = core.catalogService.version,
        config = core.configService.version,
        revision = core.selectionService.stateOf(viewerUuid).revision,
    )

    private fun sortedTitles(): List<TitleDefinition> =
        core.catalogService.all().sortedWith(
            compareBy({ it.category ?: "" }, { it.sortOrder }, { it.id.value }),
        )

    private fun pageCount(): Int = (sortedTitles().size + PAGE_SIZE - 1) / PAGE_SIZE

    private fun renderPage() {
        renderedVersion = captureVersion()
        val definitions = sortedTitles()
        val pages = maxOf(1, (definitions.size + PAGE_SIZE - 1) / PAGE_SIZE)
        if (page > pages - 1) page = pages - 1
        if (page < 0) page = 0

        val state = core.selectionService.stateOf(viewerUuid)
        actions.clear()
        for (index in 0 until PAGE_SIZE) {
            val definition = definitions.getOrNull(page * PAGE_SIZE + index)
            if (definition == null) {
                backing.setItem(index, ItemStack.EMPTY)
            } else {
                backing.setItem(index, titleItem(definition, state))
                actions[index] = MenuAction.ToggleTitle(definition.id, definition.slot)
            }
        }

        for (index in PAGE_SIZE until ROWS * 9) {
            backing.setItem(index, ItemStack.EMPTY)
        }

        val canPrev = page > 0
        backing.setItem(45,ControlItem(Items.ARROW, "Previous page", canPrev))
        if (canPrev) actions[45] = MenuAction.PreviousPage

        backing.setItem(49,ControlItem(Items.BOOK, "Titles — page ${page + 1}/$pages", true).copyLore {
            +Component.literal("Equipped: ${state.prefixes.size + state.suffixes.size} title(s), " +
                "nickname: ${state.nickname ?: "(profile name)"}").withStyle(ChatFormatting.GRAY)
        })

        val canNext = page < pages - 1
        backing.setItem(53,ControlItem(Items.ARROW, "Next page", canNext))
        if (canNext) actions[53] = MenuAction.NextPage

        // Nickname entry: only when globally allowed and individually permitted.
        val nicknameConfig = core.configService.current.nickname
        if (nicknameConfig.allowSet &&
            core.permissionManager.check(viewerUuid, Permissions.NICKNAME_SET) == PermissionVerdict.ALLOW
        ) {
            backing.setItem(46,ControlItem(Items.NAME_TAG, "Nickname", true).copyLore {
                +Component.literal("Current: ${state.nickname ?: "(profile name)"}").withStyle(ChatFormatting.GRAY)
                +Component.literal("Use /fancycrests nickname <text> to change it.").withStyle(ChatFormatting.GRAY)
                +Component.literal("Use /fancycrests nickname reset to remove it.").withStyle(ChatFormatting.GRAY)
            })
            actions[46] = MenuAction.NicknameHint
        }

        broadcastChanges()
    }

    /** Builds the display stack of one catalog entry for the viewer's state. */
    private fun titleItem(definition: TitleDefinition, state: dev.fancycrests.api.PlayerTitleState): ItemStack {
        val equippedList = when (definition.slot) {
            TitleSlot.PREFIX -> state.prefixes
            TitleSlot.SUFFIX -> state.suffixes
        }
        val equippedIndex = equippedList.indexOf(definition.id)
        val eligibility = core.selectionService.isEligible(viewerUuid, definition.id)
        val locked = eligibility == Eligibility.NotEligible

        val base = when {
            locked -> Items.GRAY_DYE
            equippedIndex >= 0 -> Items.NAME_TAG
            else -> Items.PAPER
        }
        val stack = iconStack(definition, base)
        val name = definition.displayName.copy()
        if (locked) name.withStyle(ChatFormatting.GRAY)
        stack.set(DataComponents.CUSTOM_NAME, name)

        val lore = mutableListOf<Component>()
        lore += Component.literal("Preview: ").withStyle(ChatFormatting.GRAY).append(definition.text.copy())
        lore += Component.literal(
            "Slot: ${if (definition.slot == TitleSlot.PREFIX) "prefix" else "suffix"}" +
                (definition.category?.let { " | Category: $it" } ?: ""),
        ).withStyle(ChatFormatting.DARK_GRAY)
        when {
            equippedIndex >= 0 ->
                lore += Component.literal(
                    "Equipped as ${if (definition.slot == TitleSlot.PREFIX) "prefix" else "suffix"} #${equippedIndex + 1} — click to unequip",
                ).withStyle(ChatFormatting.GREEN)
            locked -> {
                lore += Component.literal(
                    if (definition.permission != null) {
                        "Locked: requires permission '${definition.permission}' or an admin grant"
                    } else {
                        "Locked: requires an admin grant"
                    },
                ).withStyle(ChatFormatting.RED)
            }
            else -> lore += Component.literal("Click to equip").withStyle(ChatFormatting.GREEN)
        }
        stack.set(DataComponents.LORE, ItemLore(lore))
        return stack
    }

    /** The configured icon item when valid, else [fallback]. */
    private fun iconStack(definition: TitleDefinition, fallback: net.minecraft.world.item.Item): ItemStack {
        val item = definition.iconItem
            ?.let { Identifier.tryParse(it) }
            ?.let { BuiltInRegistries.ITEM.getOptional(it).orElse(null) }
            ?: fallback
        return ItemStack(item)
    }

    private fun rejectionText(rejection: SelectionResult.Rejected, titleName: String): String {
        val detail = rejection.detail?.let { ": $it" } ?: ""
        return when (rejection.reason) {
            RejectionReason.TITLE_NOT_FOUND -> "Unknown title '$titleName'$detail"
            RejectionReason.SLOT_MISMATCH -> "'$titleName' belongs to the other slot$detail"
            RejectionReason.ALREADY_EQUIPPED -> "'$titleName' is already equipped$detail"
            RejectionReason.LIMIT_REACHED -> "Equip limit reached$detail"
            RejectionReason.NOT_ELIGIBLE -> "You do not own '$titleName' and lack its permission$detail"
            RejectionReason.EVENT_CANCELLED -> "Cancelled by an event listener$detail"
            else -> "'$titleName' could not be changed$detail"
        }
    }

    companion object {
        const val ROWS = 6

        /** 6 rows * 9 columns minus the control row = 45 title slots. */
        const val PAGE_SIZE = (ROWS - 1) * 9
    }
}

/** Builds one control-row item. */
private fun ControlItem(
    item: net.minecraft.world.item.Item,
    label: String,
    enabled: Boolean,
): ItemStack {
    val stack = ItemStack(item)
    val name = Component.literal(if (enabled) label else "$label (unavailable)")
    stack.set(DataComponents.CUSTOM_NAME, name.withStyle(if (enabled) ChatFormatting.WHITE else ChatFormatting.DARK_GRAY))
    return stack
}

private inline fun ItemStack.copyLore(loreBuilder: MutableList<Component>.() -> Unit): ItemStack {
    val lore = mutableListOf<Component>()
    lore.loreBuilder()
    set(DataComponents.LORE, ItemLore(lore))
    return this
}
