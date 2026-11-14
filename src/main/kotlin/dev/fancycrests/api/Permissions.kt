package dev.fancycrests.api

import java.util.UUID

/** Result of a permission check against the active backend chain. */
enum class PermissionVerdict { ALLOW, DENY, UNDEFINED }

/**
 * Permission resolution service. Evaluation order per node: explicit deny,
 * explicit allow, operator access to mod nodes, then the documented default.
 * UNDEFINED means no backend, operator rule or node default made a decision.
 */
interface PermissionService {
    fun check(playerUuid: UUID, node: String): PermissionVerdict
}

/** Standard permission nodes of the mod (fancycrests namespace). */
object Permissions {
    const val MENU = "fancycrests.menu"
    const val EQUIP = "fancycrests.equip"
    const val NICKNAME_SET = "fancycrests.nickname.set"
    const val NICKNAME_BYPASS_RESERVED = "fancycrests.nickname.bypass_reserved"
    const val GRANT = "fancycrests.admin.grant"
    const val REVOKE = "fancycrests.admin.revoke"
    const val INSPECT = "fancycrests.admin.inspect"
    const val PREVIEW = "fancycrests.admin.preview"
    const val RELOAD = "fancycrests.admin.reload"
    const val VALIDATE = "fancycrests.admin.validate"
    const val DIAGNOSE = "fancycrests.admin.diagnose"
}
