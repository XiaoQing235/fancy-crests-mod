package dev.fancycrests.adapter.luckperms

import dev.fancycrests.api.PermissionVerdict

/**
 * Pure LuckPerms-tristate -> [PermissionVerdict] mapping, kept free of
 * LuckPerms types so it is verifiable without the optional dependency on
 * the classpath (unit tests, servers without LuckPerms).
 *
 * LuckPerms `Tristate` maps to `Boolean?` as:
 * TRUE -> true, FALSE -> false, UNDEFINED -> null.
 */
internal object LuckPermsVerdicts {
    /**
     * Explicit LP verdict: true -> ALLOW, false -> DENY, undefined -> no
     * opinion (core then consults the next backend / node default policy).
     */
    fun verdictOf(defined: Boolean?): PermissionVerdict = when (defined) {
        true -> PermissionVerdict.ALLOW
        false -> PermissionVerdict.DENY
        null -> PermissionVerdict.UNDEFINED
    }
}
