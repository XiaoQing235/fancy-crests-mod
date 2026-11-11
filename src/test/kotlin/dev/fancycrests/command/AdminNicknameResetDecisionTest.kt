package dev.fancycrests.command

import dev.fancycrests.core.permission.DefaultPolicyType
import dev.fancycrests.core.permission.NodeDefaults
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Decision logic behind `/fancycrests nickname reset <player>` (audit II-4)
 * that is testable without a [net.minecraft.commands.CommandSourceStack]:
 * the non-player permission gate for the new admin node and the pure
 * target-resolution decision (UUID or exact online name; nicknames are
 * never targets).
 *
 * Not unit-testable here (needs a live server / brigadier source): the
 * literal command tree wiring, executor feedback delivery and the online
 * name tab suggestions.
 */
class AdminNicknameResetDecisionTest {
    private val node = CommandSupport.ADMIN_NICKNAME_RESET

    // ------------------------------------------------------------------
    // Permission gate
    // ------------------------------------------------------------------

    @Test
    fun `the admin reset node is a standard node with the level 2 admin default`() {
        assertTrue(NodeDefaults.isStandardNode(node))
        val policy = NodeDefaults.policyFor(node)
        assertEquals(DefaultPolicyType.OP_LEVEL, policy?.type)
        assertEquals(NodeDefaults.ADMIN_OP_LEVEL, policy?.opLevel)
    }

    @Test
    fun `non-player sources at or above level 2 may run the reset`() {
        // Console/RCON (4) and command blocks / datapack functions (2) pass;
        // the node string must match the NodeDefaults entry exactly.
        assertTrue(nonPlayerAllowed(2, node))
        assertTrue(nonPlayerAllowed(4, node))
    }

    @Test
    fun `non-player sources below level 2 are rejected`() {
        assertFalse(nonPlayerAllowed(0, node))
        assertFalse(nonPlayerAllowed(1, node))
    }

    @Test
    fun `a typo in the node string does not silently grant access`() {
        assertFalse(nonPlayerAllowed(4, "fancycrests.admin.nickname-reset"))
        assertFalse(nonPlayerAllowed(4, "fancycrests.admin.nickname_reset "))
    }

    // ------------------------------------------------------------------
    // Target resolution (pure decision)
    // ------------------------------------------------------------------

    @Test
    fun `uuid input resolves directly and keeps the profile display name`() {
        val uuid = UUID.randomUUID()
        val hit = targetLookup(
            input = uuid.toString(),
            profileNameForUuid = { if (it == uuid) "Steve" else null },
            exactOnlineUuid = { null },
            knownUuidForLowercase = { null },
        )
        val resolved = assertIs<TargetLookup.Hit>(hit).resolved
        assertEquals(uuid, resolved.uuid)
        assertEquals("Steve", resolved.displayName)
    }

    @Test
    fun `uuid input for an offline profile resolves with display name unknown`() {
        val uuid = UUID.randomUUID()
        val hit = targetLookup(
            input = uuid.toString(),
            profileNameForUuid = { null },
            exactOnlineUuid = { null },
            knownUuidForLowercase = { null },
        )
        assertEquals("unknown", assertIs<TargetLookup.Hit>(hit).resolved.displayName)
    }

    @Test
    fun `an exact online name resolves to its uuid`() {
        val uuid = UUID.randomUUID()
        val hit = targetLookup(
            input = "Alex",
            profileNameForUuid = { null },
            exactOnlineUuid = { if (it.equals("Alex", ignoreCase = true)) uuid else null },
            knownUuidForLowercase = { if (it == "alex") uuid else null },
        )
        val resolved = assertIs<TargetLookup.Hit>(hit).resolved
        assertEquals(uuid, resolved.uuid)
        assertEquals("Alex", resolved.displayName)
    }

    @Test
    fun `names absent from the exact online table never resolve`() {
        // Even when the known-name table has an entry: that table alone is
        // not a resolver, so nicknames cannot be used as command targets.
        val lookup = targetLookup(
            input = "NotOnline",
            profileNameForUuid = { null },
            exactOnlineUuid = { null },
            knownUuidForLowercase = { if (it == "notonline") UUID.randomUUID() else null },
        )
        assertIs<TargetLookup.UnknownPlayer>(lookup)
    }

    @Test
    fun `a disagreement with the known-name table is ambiguous`() {
        val exact = UUID.randomUUID()
        val other = UUID.randomUUID()
        val lookup = targetLookup(
            input = "Twin",
            profileNameForUuid = { null },
            exactOnlineUuid = { if (it.equals("Twin", ignoreCase = true)) exact else null },
            knownUuidForLowercase = { if (it == "twin") other else null },
        )
        assertIs<TargetLookup.AmbiguousName>(lookup)
    }

    @Test
    fun `non-uuid garbage input is unknown rather than an error`() {
        val lookup = targetLookup(
            input = "%%%not a uuid%%%   ",
            profileNameForUuid = { null },
            exactOnlineUuid = { null },
            knownUuidForLowercase = { null },
        )
        assertIs<TargetLookup.UnknownPlayer>(lookup)
    }
}
