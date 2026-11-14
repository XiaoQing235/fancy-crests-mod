package dev.fancycrests.gui

import dev.fancycrests.api.Permissions
import dev.fancycrests.api.PermissionVerdict
import dev.fancycrests.api.TitleDefinition
import dev.fancycrests.api.TitleId
import dev.fancycrests.api.TitleSlot
import dev.fancycrests.core.CoreServices
import dev.fancycrests.core.selection.RejectionReason
import dev.fancycrests.core.selection.SelectionResult
import dev.fancycrests.core.selection.UnequipTarget
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import net.minecraft.ChatFormatting
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.SimpleContainer
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.ChestMenu
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.inventory.MenuType
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.ItemLore

/** One clickable menu entry. */
private sealed interface MenuAction {
    /** Equip or unequip the title in [slot] (direction decided from live state). */
    data class ToggleTitle(val titleId: TitleId, val slot: TitleSlot) : MenuAction

    data object PreviousPage : MenuAction

    data object NextPage : MenuAction

    data class OpenPage(val page: TitleMenuPage) : MenuAction

    data object EditNickname : MenuAction

    data object ResetNickname : MenuAction
}

internal enum class TitleMenuPage(val label: String, val slot: TitleSlot?) {
    PREFIX("Prefixes", TitleSlot.PREFIX),
    SUFFIX("Suffixes", TitleSlot.SUFFIX),
    NICKNAME("Nickname", null),
}

/** Identity of the world the menu was built from; mismatch rejects clicks. */
private data class MenuVersion(val catalog: Long, val config: Long, val revision: Long)

/**
 * Server-side chest menu for one title slot or the nickname page.
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
 * Slots 0..44 contain the current page; the bottom row switches pages and
 * navigates the current title list.
 */
class TitleMenu private constructor(
    containerId: Int,
    playerInventory: Inventory,
    private val viewer: ServerPlayer,
    private val core: CoreServices,
    private val menuPage: TitleMenuPage,
    private val backing: SimpleContainer,
) : ChestMenu(MenuType.GENERIC_9x6, containerId, playerInventory, backing, ROWS) {

    internal constructor(
        containerId: Int,
        playerInventory: Inventory,
        viewer: ServerPlayer,
        core: CoreServices,
        menuPage: TitleMenuPage,
    ) : this(containerId, playerInventory, viewer, core, menuPage, SimpleContainer(ROWS * 9))

    private val viewerUuid: UUID = viewer.gameProfile.id()

    /** Currently displayed page (0-based). */
    private var page = 0

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

    override fun canTakeItemForPickAll(stack: ItemStack, slot: Slot): Boolean =
        slot.container !== backing && super.canTakeItemForPickAll(stack, slot)

    override fun canDragTo(slot: Slot): Boolean = slot.container !== backing && super.canDragTo(slot)

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
            is MenuAction.OpenPage -> TitleMenuService.open(viewer, core, action.page)
            MenuAction.EditNickname -> openNicknameInput()
            MenuAction.ResetNickname -> resetNickname()
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
                // Release the debounce flag BEFORE re-rendering: renderPage
                // touches live server state and can throw, and a stuck flag
                // would swallow every future click (same ordering as the
                // staleness path above).
                clickInFlight.set(false)
                (viewer.containerMenu as? TitleMenu)?.renderPage()
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
            // The menu lists titles per slot, so the action's slot is the
            // natural expectation; a catalog swap mid-session cannot equip a
            // title into the wrong slot unnoticed (SLOT_MISMATCH instead).
            core.selectionService.equip(
                viewerUuid,
                action.titleId,
                "menu",
                expectedSlot = action.slot,
                callback = callback,
            )
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

    private fun sortedTitles(): List<TitleDefinition> = titlesForPage(core.catalogService.all(), menuPage)

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
                val limit = when (definition.slot) {
                    TitleSlot.PREFIX -> core.configService.current.equipment.maxPrefixes
                    TitleSlot.SUFFIX -> core.configService.current.equipment.maxSuffixes
                }
                backing.setItem(index, titleItem(definition, state, core.selectionService.isEligible(viewerUuid, definition.id), limit))
                actions[index] = MenuAction.ToggleTitle(definition.id, definition.slot)
            }
        }

        for (index in PAGE_SIZE until ROWS * 9) {
            backing.setItem(index, ItemStack.EMPTY)
        }

        if (menuPage == TitleMenuPage.NICKNAME) renderNicknamePage(state)

        if (menuPage != TitleMenuPage.NICKNAME) {
            val canPrev = page > 0
            backing.setItem(45, ControlItem(Items.ARROW, "Previous page", canPrev))
            if (canPrev) actions[45] = MenuAction.PreviousPage

            backing.setItem(49, ControlItem(Items.BOOK, "${menuPage.label} — page ${page + 1}/$pages", true))

            val canNext = page < pages - 1
            backing.setItem(53, ControlItem(Items.ARROW, "Next page", canNext))
            if (canNext) actions[53] = MenuAction.NextPage
        }

        val navigation = listOf(
            Triple(46, TitleMenuPage.PREFIX, Items.GOLD_INGOT),
            Triple(47, TitleMenuPage.SUFFIX, Items.DIAMOND),
            Triple(48, TitleMenuPage.NICKNAME, Items.NAME_TAG),
        )
        for ((index, target, item) in navigation) {
            backing.setItem(index, ControlItem(item, target.label, true).copyLore { lore ->
                lore += Component.literal(if (target == menuPage) "Current menu" else "Click to open")
                    .withStyle(if (target == menuPage) ChatFormatting.GRAY else ChatFormatting.GREEN)
            })
            if (target != menuPage) actions[index] = MenuAction.OpenPage(target)
        }
        broadcastChanges()
    }

    private fun renderNicknamePage(state: dev.fancycrests.api.PlayerTitleState) {
        val permitted = core.permissionManager.check(viewerUuid, Permissions.NICKNAME_SET) == PermissionVerdict.ALLOW
        val canEdit = permitted && core.configService.current.nickname.allowSet
        val canReset = permitted && state.nickname != null
        backing.setItem(20, ControlItem(Items.NAME_TAG, "Set nickname", canEdit).copyLore { lore ->
            lore += Component.literal("Current: ${state.nickname ?: "(profile name)"}").withStyle(ChatFormatting.GRAY)
            lore += Component.literal(
                when {
                    !permitted -> "You lack permission to change your nickname"
                    !core.configService.current.nickname.allowSet -> "Nicknames are disabled"
                    else -> "Click to enter a nickname"
                },
            ).withStyle(if (canEdit) ChatFormatting.GREEN else ChatFormatting.RED)
            if (canEdit && core.configService.current.nickname.maxLength > net.minecraft.world.inventory.AnvilMenu.MAX_NAME_LENGTH) {
                lore += Component.literal("For names over 50 characters, use /fancycrests nickname.").withStyle(ChatFormatting.GRAY)
            }
        })
        if (canEdit) actions[20] = MenuAction.EditNickname

        backing.setItem(24, ControlItem(Items.BARRIER, "Reset nickname", canReset).copyLore { lore ->
            lore += Component.literal(
                when {
                    !permitted -> "You lack permission to change your nickname"
                    state.nickname == null -> "No nickname to reset"
                    else -> "Click to restore your profile name"
                },
            ).withStyle(if (canReset) ChatFormatting.RED else ChatFormatting.GRAY)
        })
        if (canReset) actions[24] = MenuAction.ResetNickname
    }

    private fun openNicknameInput() {
        if (!nicknameAllowed(requireEnabled = true)) return
        NicknameInputMenu.open(viewer, core)
    }

    private fun resetNickname() {
        if (!clickInFlight.compareAndSet(false, true)) return
        if (!nicknameAllowed(requireEnabled = false)) {
            clickInFlight.set(false)
            return
        }
        core.selectionService.setNickname(viewerUuid, null, "menu") { result ->
            try {
                sendNicknameResult(viewer, result, null)
            } finally {
                clickInFlight.set(false)
                (viewer.containerMenu as? TitleMenu)?.renderPage()
            }
        }
    }

    private fun nicknameAllowed(requireEnabled: Boolean): Boolean {
        if (captureVersion() != renderedVersion) {
            renderPage()
            viewer.sendSystemMessage(Component.literal("The menu changed; please click again.").withStyle(ChatFormatting.YELLOW))
            return false
        }
        if (core.permissionManager.check(viewerUuid, Permissions.NICKNAME_SET) != PermissionVerdict.ALLOW) {
            viewer.sendSystemMessage(Component.literal("You lack permission to change your nickname.").withStyle(ChatFormatting.RED))
            return false
        }
        if (requireEnabled && !core.configService.current.nickname.allowSet) {
            viewer.sendSystemMessage(Component.literal("Nicknames are disabled.").withStyle(ChatFormatting.RED))
            return false
        }
        return true
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

internal fun titlesForPage(definitions: Collection<TitleDefinition>, page: TitleMenuPage): List<TitleDefinition> =
    definitions.filter { it.slot == page.slot }.sortedWith(
        compareBy({ it.category ?: "" }, { it.sortOrder }, { it.id.value }),
    )

/** Builds one control-row item. */
private fun ControlItem(
    item: net.minecraft.world.item.Item,
    label: String,
    enabled: Boolean,
): ItemStack {
    val stack = ItemStack(item)
    val name = Component.literal(if (enabled) label else "$label (unavailable)")
    stack.set(DataComponents.CUSTOM_NAME, withoutItalic(name.withStyle(if (enabled) ChatFormatting.WHITE else ChatFormatting.DARK_GRAY)))
    return stack
}

private inline fun ItemStack.copyLore(loreBuilder: (MutableList<Component>) -> Unit): ItemStack {
    val lore = mutableListOf<Component>()
    loreBuilder(lore)
    set(DataComponents.LORE, ItemLore(lore.map(::withoutItalic)))
    return this
}
