package dev.fancycrests.command

import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType
import com.mojang.brigadier.suggestion.SuggestionsBuilder
import dev.fancycrests.api.Permissions
import dev.fancycrests.api.PermissionVerdict
import dev.fancycrests.core.CoreServices
import dev.fancycrests.core.permission.NodeDefaults
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.network.chat.Component

/** Result of resolving a command target (UUID or exact real player name). */
sealed interface TargetResolution {
    data class Resolved(val uuid: java.util.UUID, val displayName: String) : TargetResolution

    data class Failed(val error: Component) : TargetResolution
}

/**
 * Command-side helpers: the permission gate, target resolution and feedback.
 *
 * Permission evaluation for players goes through the core permission service
 * (explicit deny → explicit allow → documented node default, including the
 * op-level-2 fallback for admin nodes). The console is allowed on admin nodes
 * but cannot run player-only commands (menu/equip/nickname).
 */
object CommandSupport {

    private val UNKNOWN_PLAYER = DynamicCommandExceptionType { name ->
        Component.literal("No player matches '$name'. Only exact online player names and UUIDs are accepted; nicknames are never used as targets.")
    }
    private val AMBIGUOUS_PLAYER = DynamicCommandExceptionType { name ->
        Component.literal("'$name' is ambiguous; use the player's UUID instead.")
    }

    // ------------------------------------------------------------------
    // Permissions
    // ------------------------------------------------------------------

    /** True when [source] may use the given node (player UUID or console). */
    fun hasPermission(source: CommandSourceStack, core: CoreServices, node: String): Boolean {
        val player = source.player
        return if (player == null) {
            // Console: admin nodes allowed; player-only nodes need a player.
            NodeDefaults.policyFor(node)?.type == dev.fancycrests.core.permission.DefaultPolicyType.OP_LEVEL
        } else {
            core.permissionManager.check(player.gameProfile.id(), node) == PermissionVerdict.ALLOW
        }
    }

    /** Denies with a clear message when the source lacks [node]. */
    fun requirePermission(source: CommandSourceStack, core: CoreServices, node: String): Boolean {
        if (!hasPermission(source, core, node)) {
            source.sendFailure(
                Component.literal("You lack permission '$node' (explicit denies cannot be overridden by operator status)."),
            )
            return false
        }
        return true
    }

    /** Returns the executing player, or sends a "players only" failure. */
    fun requirePlayer(source: CommandSourceStack): net.minecraft.server.level.ServerPlayer? {
        val player = source.player
        if (player == null) {
            source.sendFailure(Component.literal("Only players can run this command."))
        }
        return player
    }

    // ------------------------------------------------------------------
    // Target resolution
    // ------------------------------------------------------------------

    /**
     * Resolves [input] as a UUID or an exact (case-insensitive) online player
     * name. Nicknames are never consulted; unresolvable input raises a
     * brigadier error.
     */
    fun resolveTargetOrThrow(core: CoreServices, input: String): TargetResolution.Resolved {
        val uuid = try {
            java.util.UUID.fromString(input)
        } catch (e: IllegalArgumentException) {
            null
        }
        if (uuid != null) {
            val name = core.serverAdapter.profileName(uuid) ?: "unknown"
            return TargetResolution.Resolved(uuid, name)
        }
        val byName = core.serverAdapter.playerNameExact(input)
        if (byName == null) {
            throw UNKNOWN_PLAYER.create(input)
        }
        // getPlayerByName matches exactly one online profile per name; the
        // ambiguity guard exists for future multi-match resolvers.
        val known = core.serverAdapter.knownPlayerNames()[input.lowercase()]
        if (known != null && known != byName) {
            throw AMBIGUOUS_PLAYER.create(input)
        }
        return TargetResolution.Resolved(byName, input)
    }

    // ------------------------------------------------------------------
    // Feedback
    // ------------------------------------------------------------------

    /** Suggests [values] (strings) through the vanilla suggestion helper. */
    fun suggestValues(values: Iterable<String>, builder: SuggestionsBuilder) {
        SharedSuggestionProvider.suggest(values, builder)
    }

    /** Standard node list for command docs/feedback. */
    val allNodes: List<String> = listOf(
        Permissions.MENU,
        Permissions.EQUIP,
        Permissions.NICKNAME_SET,
        Permissions.NICKNAME_BYPASS_RESERVED,
        Permissions.GRANT,
        Permissions.REVOKE,
        Permissions.INSPECT,
        Permissions.PREVIEW,
        Permissions.RELOAD,
        Permissions.VALIDATE,
        Permissions.DIAGNOSE,
    )
}

// Top-level feedback extensions (usable unqualified from the command tree).

fun CommandSourceStack.success(text: String): Int {
    sendSuccess({ Component.literal(text) }, false)
    return com.mojang.brigadier.Command.SINGLE_SUCCESS
}

fun CommandSourceStack.success(component: Component): Int {
    sendSuccess({ component }, false)
    return com.mojang.brigadier.Command.SINGLE_SUCCESS
}

fun CommandSourceStack.fail(text: String): Int {
    sendFailure(Component.literal(text))
    return 0
}

fun CommandSourceStack.fail(component: Component): Int {
    sendFailure(component)
    return 0
}
