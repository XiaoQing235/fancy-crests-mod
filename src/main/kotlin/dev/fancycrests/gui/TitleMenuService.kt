package dev.fancycrests.gui

import dev.fancycrests.api.Permissions
import dev.fancycrests.api.PermissionVerdict
import dev.fancycrests.core.CoreServices
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.MenuProvider
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu

/**
 * Opens the native chest title menu for a player (P3 GUI entry point).
 *
 * Permission is re-checked server-side on every open (never trusting any
 * client state); the menu itself only displays domain state and routes every
 * click back through the core selection service.
 */
object TitleMenuService {

    /** Opens the menu for [player]; returns true when a menu was opened. */
    fun open(player: ServerPlayer, core: CoreServices): Boolean = open(player, core, TitleMenuPage.PREFIX)

    internal fun open(player: ServerPlayer, core: CoreServices, page: TitleMenuPage): Boolean {
        if (!canOpen(player, core)) return false
        player.openMenu(TitleMenuProvider(player, core, page))
        return true
    }

    /** Recheck access before opening or switching menu pages. */
    internal fun canOpen(player: ServerPlayer, core: CoreServices): Boolean {
        if (!core.isStarted) {
            player.sendSystemMessage(
                Component.literal("Fancy Crests is still starting; try again in a moment.")
                    .withStyle(ChatFormatting.RED),
            )
            return false
        }
        val uuid = player.gameProfile.id()
        if (core.permissionManager.check(uuid, Permissions.MENU) != PermissionVerdict.ALLOW) {
            player.sendSystemMessage(
                Component.literal("You lack permission '${Permissions.MENU}' to open the title menu.")
                    .withStyle(ChatFormatting.RED),
            )
            return false
        }
        return true
    }
}

/** MenuProvider constructing a [TitleMenu] on the server. */
private class TitleMenuProvider(
    private val viewer: ServerPlayer,
    private val core: CoreServices,
    private val page: TitleMenuPage,
) : MenuProvider {

    override fun getDisplayName(): Component = Component.literal("Fancy Crests")

    override fun createMenu(containerId: Int, playerInventory: Inventory, player: Player): AbstractContainerMenu =
        TitleMenu(containerId, playerInventory, viewer, core, page)
}
