package dev.fancycrests.adapter.luckperms

import dev.fancycrests.api.PermissionVerdict
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * LuckPerms tristate -> PermissionVerdict mapping (plan chapter 1: explicit
 * true -> ALLOW, explicit false -> DENY, undefined -> UNDEFINED so core's
 * chain falls back to the built-in backend / node default). The pure mapping
 * object carries no LuckPerms types, so this runs without the optional
 * dependency on the classpath.
 */
class LuckPermsVerdictMappingTest {

    @Test
    fun `explicit verdicts map directly`() {
        assertEquals(PermissionVerdict.ALLOW, LuckPermsVerdicts.verdictOf(true))
        assertEquals(PermissionVerdict.DENY, LuckPermsVerdicts.verdictOf(false))
    }

    @Test
    fun `undefined stays undefined for core fallback`() {
        assertEquals(PermissionVerdict.UNDEFINED, LuckPermsVerdicts.verdictOf(null))
    }
}
