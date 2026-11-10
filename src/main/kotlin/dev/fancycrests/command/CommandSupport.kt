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
import net.minecraft.server.permissions.LevelBasedPermissionSet
import net.minecraft.server.permissions.PermissionSet

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
 * op-level-2 fallback for admin nodes). Non-player sources (console/RCON,
 * command blocks, datapack functions) are held to the vanilla convention:
 * admin nodes require the source's own permission level to meet the node's
 * documented operator level — console/RCON run at level 4, command blocks
 * and datapack functions default to level 2. Player-only commands
 * (menu/equip/nickname) never accept non-player sources.
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
            // Non-player source: admin nodes only, and only when the source's
            // own permission level meets the node's documented default level.
            nonPlayerAllowed(permissionLevel(source.permissions()), node)
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

/**
 * Pure gate for non-player sources (unit-testable without a
 * [CommandSourceStack]): an OP_LEVEL node is available when the source's
 * own permission level meets *that node's* documented operator level (per
 * [NodeDefaults], not a fixed number); player-only and deny-by-default nodes
 * are never available to non-player sources, and unknown nodes stay denied.
 * Console/RCON sources run at level 4 and pass every admin node; command
 * blocks and datapack functions default to level 2.
 */
internal fun nonPlayerAllowed(sourceLevel: Int, node: String): Boolean {
    val policy = NodeDefaults.policyFor(node) ?: return false
    return policy.type == dev.fancycrests.core.permission.DefaultPolicyType.OP_LEVEL &&
        sourceLevel >= policy.opLevel
}

/**
 * Maps a vanilla permission set to its numeric operator level (0..4);
 * 26.2 replaced hasPermission(int) with [PermissionSet] (ALL_PERMISSIONS
 * means 4, level-based sets carry their [LevelBasedPermissionSet.level] id).
 * Mirrors MinecraftServerAdapter.operatorLevel.
 */
internal fun permissionLevel(permissions: PermissionSet): Int = when {
    permissions == PermissionSet.ALL_PERMISSIONS -> 4
    permissions is LevelBasedPermissionSet -> permissions.level().id()
    else -> 0
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
