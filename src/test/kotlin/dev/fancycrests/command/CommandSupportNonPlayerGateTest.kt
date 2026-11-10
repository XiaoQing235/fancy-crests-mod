package dev.fancycrests.command

import dev.fancycrests.api.Permissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import net.minecraft.server.permissions.LevelBasedPermissionSet
import net.minecraft.server.permissions.PermissionSet

/**
 * Non-player permission gating (pure decision; a real CommandSourceStack
 * cannot be constructed in a unit test). Vanilla 26.2 replaced
 * hasPermission(int) with PermissionSet, so the source's level comes from
 * [permissionLevel] and the policy decision from [nonPlayerAllowed]:
 * a non-player source may use an admin node only when its own level meets
 * that node's documented operator level; player-only nodes never accept
 * non-player sources.
 */
class CommandSupportNonPlayerGateTest {

    @Test
    fun `level 4 sources pass admin nodes`() {
        // Console/RCON run at level 4 — behaviour unchanged for them.
        assertTrue(nonPlayerAllowed(4, Permissions.GRANT))
        assertTrue(nonPlayerAllowed(4, Permissions.REVOKE))
        assertTrue(nonPlayerAllowed(4, Permissions.RELOAD))
        assertTrue(nonPlayerAllowed(4, Permissions.VALIDATE))
    }

    @Test
    fun `sources below the node's level are rejected`() {
        // Command blocks / datapack functions default to level 2; a source
        // configured below the node's documented level must not slip through
        // (the old gate let every non-player source run every admin node).
        assertFalse(nonPlayerAllowed(1, Permissions.GRANT))
        assertFalse(nonPlayerAllowed(0, Permissions.RELOAD))
        assertFalse(nonPlayerAllowed(1, Permissions.DIAGNOSE))
    }

    @Test
    fun `a level 2 source passes the current level 2 admin nodes`() {
        assertTrue(nonPlayerAllowed(2, Permissions.GRANT))
        assertTrue(nonPlayerAllowed(2, Permissions.RELOAD))
    }

    @Test
    fun `player-only and deny-by-default nodes reject non-player sources`() {
        assertFalse(nonPlayerAllowed(4, Permissions.MENU))
        assertFalse(nonPlayerAllowed(4, Permissions.EQUIP))
        assertFalse(nonPlayerAllowed(4, Permissions.NICKNAME_SET))
        assertFalse(nonPlayerAllowed(4, Permissions.NICKNAME_BYPASS_RESERVED))
    }

    @Test
    fun `unknown nodes stay denied`() {
        assertFalse(nonPlayerAllowed(4, "fancycrests.custom.unknown"))
    }

    @Test
    fun `vanilla permission sets map to numeric levels`() {
        assertEquals(0, permissionLevel(PermissionSet.NO_PERMISSIONS))
        assertEquals(4, permissionLevel(PermissionSet.ALL_PERMISSIONS))
        assertEquals(2, permissionLevel(LevelBasedPermissionSet.GAMEMASTER))
        assertEquals(4, permissionLevel(LevelBasedPermissionSet.OWNER))
    }
}
