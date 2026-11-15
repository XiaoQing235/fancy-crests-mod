package dev.fancycrests.gui

import dev.fancycrests.api.PermissionVerdict
import dev.fancycrests.api.Permissions
import dev.fancycrests.core.CoreServices
import dev.fancycrests.core.selection.SelectionResult
import java.util.concurrent.atomic.AtomicBoolean
import net.minecraft.ChatFormatting
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.MenuProvider
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.AnvilMenu
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.inventory.ContainerLevelAccess
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.ItemLore

/** Vanilla anvil input with display-only items; no item or experience is consumed. */
internal class NicknameInputMenu(
    containerId: Int,
    inventory: Inventory,
    private val viewer: ServerPlayer,
    private val core: CoreServices,
) : AnvilMenu(containerId, inventory, ContainerLevelAccess.NULL) {
    private var input = ""
    private val submitting = AtomicBoolean(false)

    init {
        val prompt = ItemStack(Items.NAME_TAG)
        prompt.set(DataComponents.CUSTOM_NAME, withoutItalic(Component.empty()))
        inputSlots.setItem(INPUT_SLOT, prompt)
    }

    override fun stillValid(player: Player): Boolean = player.uuid == viewer.uuid

    override fun setItemName(name: String): Boolean {
        if (name.length > MAX_NAME_LENGTH || name == input) return false
        input = name
        createResult()
        return true
    }

    override fun createResult() {
        val confirm = ItemStack(Items.NAME_TAG)
        confirm.set(DataComponents.CUSTOM_NAME, withoutItalic(Component.literal("Click to confirm").withStyle(ChatFormatting.GREEN)))
        confirm.set(
            DataComponents.LORE,
            ItemLore(listOf(withoutItalic(Component.literal(input).withStyle(ChatFormatting.GRAY)))),
        )
        resultSlots.setItem(0, confirm)
        broadcastChanges()
    }

    override fun clicked(slotIndex: Int, button: Int, action: ContainerInput, player: Player) {
        if (player.uuid != viewer.uuid || slotIndex != RESULT_SLOT || action != ContainerInput.PICKUP || button !in 0..1) return
        if (!submitting.compareAndSet(false, true)) return
        if (core.permissionManager.check(viewer.uuid, Permissions.NICKNAME_SET) != PermissionVerdict.ALLOW) {
            submitting.set(false)
            viewer.sendSystemMessage(Component.literal("You lack permission to change your nickname.").withStyle(ChatFormatting.RED))
            return
        }
        val nickname = input
        core.selectionService.setCustomNickname(viewer.uuid, nickname, "menu") { result ->
            try {
                sendNicknameResult(viewer, result, nickname)
                if (result is SelectionResult.Success && viewer.containerMenu === this) {
                    TitleMenuService.open(viewer, core, TitleMenuPage.NICKNAME)
                }
            } finally {
                submitting.set(false)
                if (viewer.containerMenu === this) broadcastChanges()
            }
        }
    }

    override fun quickMoveStack(player: Player, index: Int): ItemStack = ItemStack.EMPTY

    override fun removed(player: Player) {
        inputSlots.clearContent()
        resultSlots.clearContent()
        super.removed(player)
    }

    companion object {
        fun open(viewer: ServerPlayer, core: CoreServices) {
            viewer.openMenu(object : MenuProvider {
                override fun getDisplayName(): Component = Component.literal("Enter nickname")

                override fun createMenu(containerId: Int, inventory: Inventory, player: Player): AbstractContainerMenu =
                    NicknameInputMenu(containerId, inventory, viewer, core)
            })
        }
    }
}

internal fun sendNicknameResult(player: ServerPlayer, result: SelectionResult, nickname: String?) {
    val text = when (result) {
        is SelectionResult.Success -> if (nickname == null) "Nickname reset." else "Nickname set to '$nickname'."
        is SelectionResult.Rejected -> "Nickname could not be changed: ${result.detail ?: result.reason.name}."
        SelectionResult.Conflict -> "The change conflicted with another operation; nothing was modified."
        is SelectionResult.StorageFailure -> "Storage error, nothing was changed: ${result.message}"
        SelectionResult.Cancelled -> "The change was cancelled by an event listener."
    }
    player.sendSystemMessage(Component.literal(text).withStyle(if (result is SelectionResult.Success) ChatFormatting.GREEN else ChatFormatting.RED))
}
