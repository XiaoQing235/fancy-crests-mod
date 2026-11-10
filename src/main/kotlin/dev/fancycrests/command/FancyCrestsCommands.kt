package dev.fancycrests.command

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.builder.RequiredArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.arguments.StringArgumentType
import dev.fancycrests.api.Permissions
import dev.fancycrests.api.TitleId
import dev.fancycrests.api.TitleSlot
import dev.fancycrests.core.CoreServices
import dev.fancycrests.core.config.ConfigLoadResult
import dev.fancycrests.core.selection.RejectionReason
import dev.fancycrests.core.selection.SelectionResult
import dev.fancycrests.core.selection.UnequipTarget
import dev.fancycrests.gui.TitleMenuService
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component

/**
 * Command tree of Fancy Crests, registered under three roots
 * (/fancycrests, /names, /customnames — one shared tree).
 *
 * Player commands: menu, equip <prefix|suffix> <id>,
 * unequip <prefix|suffix> <id|all>, nickname <text>, nickname reset.
 * Admin commands (op >= 2 by default, console always allowed):
 * grant/revoke <player> <id>, inspect <player>, preview <player>,
 * reload, validate, diagnose.
 *
 * Targets are UUIDs or exact online player names; nicknames are never
 * accepted. Permission checks run through the core permission service so
 * explicit denies cannot be bypassed by operator status.
 */
object FancyCrestsCommands {

    /** Registers the three roots once; call from the mod initializer. */
    fun register(core: CoreServices) {
        CommandRegistrationCallback.EVENT.register { dispatcher: CommandDispatcher<CommandSourceStack>, _, _ ->
            dispatcher.register(build(core, "fancycrests"))
            dispatcher.register(build(core, "names"))
            dispatcher.register(build(core, "customnames"))
        }
        // Player-side feedback (eligibility loss notices, admin op logging).
        PlayerFeedback.attach(core)
    }

    /** Builds one root literal with the full subtree. */
    fun build(core: CoreServices, rootName: String): LiteralArgumentBuilder<CommandSourceStack> {
        val root = Commands.literal(rootName)

        root.then(Commands.literal("menu").executes { ctx -> runMenu(ctx, core) })

        for ((literal, slot) in listOf("prefix" to TitleSlot.PREFIX, "suffix" to TitleSlot.SUFFIX)) {
            root.then(
                Commands.literal("equip")
                    .then(
                        Commands.literal(literal)
                            .then(
                                Commands.argument("titleId", StringArgumentType.greedyString())
                                    .suggests { _, builder ->
                                        CommandSupport.suggestValues(
                                            core.catalogService.idsForSlot(slot).map { it.value },
                                            builder,
                                        )
                                        builder.buildFuture()
                                    }
                                    .executes { ctx -> runEquip(ctx, core, slot) },
                            ),
                    ),
            )
            root.then(
                Commands.literal("unequip")
                    .then(
                        Commands.literal(literal)
                            .then(
                                Commands.literal("all").executes { ctx ->
                                    runUnequip(ctx, core, slot, UnequipTarget.All)
                                },
                            )
                            .then(
                                Commands.argument("titleId", StringArgumentType.greedyString())
                                    .suggests { ctx, builder ->
                                        val player = ctx.source.player
                                        val equipped = if (player == null) {
                                            emptyList()
                                        } else {
                                            val state = core.selectionService.stateOf(player.gameProfile.id())
                                            when (slot) {
                                                TitleSlot.PREFIX -> state.prefixes
                                                TitleSlot.SUFFIX -> state.suffixes
                                            }.map { it.value }
                                        }
                                        CommandSupport.suggestValues(equipped + "all", builder)
                                        builder.buildFuture()
                                    }
                                    .executes { ctx ->
                                        runUnequip(ctx, core, slot, UnequipTarget.One(TitleId(StringArgumentType.getString(ctx, "titleId"))))
                                    },
                            ),
                    ),
            )
        }

        root.then(
            Commands.literal("nickname")
                .then(Commands.literal("reset").executes { ctx -> runNickname(ctx, core, null) })
                .then(
                    Commands.argument("text", StringArgumentType.greedyString())
                        .executes { ctx -> runNickname(ctx, core, StringArgumentType.getString(ctx, "text")) },
                ),
        )

        root.then(
            Commands.literal("grant")
                .then(
                    Commands.argument("player", StringArgumentType.word())
                        .suggests { ctx, builder ->
                            CommandSupport.suggestValues(ctx.source.onlinePlayerNames, builder)
                            builder.buildFuture()
                        }
                        .then(
                            Commands.argument("titleId", StringArgumentType.greedyString())
                                .suggests { _, builder ->
                                    CommandSupport.suggestValues(
                                        core.catalogService.all().map { it.id.value },
                                        builder,
                                    )
                                    builder.buildFuture()
                                }
                                .executes { ctx -> runGrantRevoke(ctx, core, grant = true) },
                        ),
                ),
        )

        root.then(
            Commands.literal("revoke")
                .then(
                    Commands.argument("player", StringArgumentType.word())
                        .suggests { ctx, builder ->
                            CommandSupport.suggestValues(ctx.source.onlinePlayerNames, builder)
                            builder.buildFuture()
                        }
                        .then(
                            Commands.argument("titleId", StringArgumentType.greedyString())
                                .suggests { _, builder ->
                                    CommandSupport.suggestValues(
                                        core.catalogService.all().map { it.id.value },
                                        builder,
                                    )
                                    builder.buildFuture()
                                }
                                .executes { ctx -> runGrantRevoke(ctx, core, grant = false) },
                        ),
                ),
        )

        root.then(
            Commands.literal("inspect")
                .then(
                    Commands.argument("player", StringArgumentType.word())
                        .suggests { ctx, builder ->
                            CommandSupport.suggestValues(ctx.source.onlinePlayerNames, builder)
                            builder.buildFuture()
                        }
                        .executes { ctx -> runInspect(ctx, core) },
                ),
        )

        root.then(
            Commands.literal("preview")
                .then(
                    Commands.argument("player", StringArgumentType.word())
                        .suggests { ctx, builder ->
                            CommandSupport.suggestValues(ctx.source.onlinePlayerNames, builder)
                            builder.buildFuture()
                        }
                        .executes { ctx -> runPreview(ctx, core) },
                ),
        )

        root.then(Commands.literal("reload").executes { ctx -> runReload(ctx, core) })
        root.then(Commands.literal("validate").executes { ctx -> runValidate(ctx, core) })
        root.then(Commands.literal("diagnose").executes { ctx -> runDiagnose(ctx, core) })

        return root
    }

    // ----------------------------------------------------------------------
    // Player commands
    // ----------------------------------------------------------------------

    private fun runMenu(ctx: CommandContext<CommandSourceStack>, core: CoreServices): Int {
        val source = ctx.source
        val player = CommandSupport.requirePlayer(source) ?: return 0
        if (!CommandSupport.requirePermission(source, core, Permissions.MENU)) return 0
        return if (TitleMenuService.open(player, core)) Command.SINGLE_SUCCESS else 0
    }

    private fun runEquip(ctx: CommandContext<CommandSourceStack>, core: CoreServices, slot: TitleSlot): Int {
        val source = ctx.source
        val player = CommandSupport.requirePlayer(source) ?: return 0
        if (!CommandSupport.requirePermission(source, core, Permissions.EQUIP)) return 0
        val titleId = TitleId(StringArgumentType.getString(ctx, "titleId"))
        val uuid = player.gameProfile.id()
        core.selectionService.equip(uuid, titleId, "command") { result ->
            reportResult(source, result, successText = "Equipped ${titleId.value} as ${slot.name.lowercase()}.")
        }
        return Command.SINGLE_SUCCESS
    }

    private fun runUnequip(ctx: CommandContext<CommandSourceStack>, core: CoreServices, slot: TitleSlot, target: UnequipTarget): Int {
        val source = ctx.source
        val player = CommandSupport.requirePlayer(source) ?: return 0
        if (!CommandSupport.requirePermission(source, core, Permissions.EQUIP)) return 0
        val uuid = player.gameProfile.id()
        core.selectionService.unequip(uuid, slot, target, "command") { result ->
            val label = when (target) {
                is UnequipTarget.All -> "all ${slot.name.lowercase()}es"
                is UnequipTarget.One -> target.titleId.value
            }
            reportResult(source, result, successText = "Unequipped $label.")
        }
        return Command.SINGLE_SUCCESS
    }

    private fun runNickname(ctx: CommandContext<CommandSourceStack>, core: CoreServices, text: String?): Int {
        val source = ctx.source
        val player = CommandSupport.requirePlayer(source) ?: return 0
        if (!CommandSupport.requirePermission(source, core, Permissions.NICKNAME_SET)) return 0
        val uuid = player.gameProfile.id()
        core.selectionService.setNickname(uuid, text, "command") { result ->
            val success = if (text == null) {
                "Nickname reset; you are shown as your profile name again."
            } else {
                "Nickname set to '$text'."
            }
            reportResult(source, result, successText = success)
        }
        return Command.SINGLE_SUCCESS
    }

    // ----------------------------------------------------------------------
    // Admin commands
    // ----------------------------------------------------------------------

    private fun runGrantRevoke(ctx: CommandContext<CommandSourceStack>, core: CoreServices, grant: Boolean): Int {
        val source = ctx.source
        val node = if (grant) Permissions.GRANT else Permissions.REVOKE
        if (!CommandSupport.requirePermission(source, core, node)) return 0
        val target = CommandSupport.resolveTargetOrThrow(core, StringArgumentType.getString(ctx, "player"))
        val titleId = TitleId(StringArgumentType.getString(ctx, "titleId"))
        val callback: (SelectionResult) -> Unit = { result ->
            reportResult(
                source,
                result,
                successText = if (grant) {
                    "Granted '${titleId.value}' to ${target.displayName} (${target.uuid})."
                } else {
                    "Revoked local grant of '${titleId.value}' from ${target.displayName} (${target.uuid})."
                },
            )
            // Player-side notice for the affected player (online only).
            if (result is SelectionResult.Success) {
                val notice = Component.literal(
                    if (grant) {
                        "An administrator granted you the title '${titleId.value}'."
                    } else {
                        "An administrator revoked your local grant of '${titleId.value}'."
                    },
                )
                core.serverAdapter.sendSystemMessage(target.uuid, notice)
            }
        }
        if (grant) {
            core.selectionService.grant(target.uuid, titleId, "local", "command", callback)
        } else {
            core.selectionService.revoke(target.uuid, titleId, "local", "command", callback)
        }
        return Command.SINGLE_SUCCESS
    }

    private fun runInspect(ctx: CommandContext<CommandSourceStack>, core: CoreServices): Int {
        val source = ctx.source
        if (!CommandSupport.requirePermission(source, core, Permissions.INSPECT)) return 0
        val target = CommandSupport.resolveTargetOrThrow(core, StringArgumentType.getString(ctx, "player"))
        val state = core.selectionService.stateOf(target.uuid)
        val ownership = core.selectionService.ownershipOf(target.uuid)
        val knownName = core.serverAdapter.profileName(target.uuid) ?: "unknown (offline)"

        source.success("=== Fancy Crests: ${target.displayName} ===")
        source.success("UUID: ${target.uuid}")
        source.success("Profile name: $knownName")
        source.success("Nickname: ${state.nickname ?: "(none)"}")
        source.success("Revision: ${state.revision}")
        source.success(
            "Equipped prefixes: " + (state.prefixes.joinToString(", ") { it.value }.ifEmpty { "(none)" }),
        )
        source.success(
            "Equipped suffixes: " + (state.suffixes.joinToString(", ") { it.value }.ifEmpty { "(none)" }),
        )
        source.success(
            "Ownership: " + (
                ownership.joinToString(", ") { "${it.titleId.value} [${it.source}]" }.ifEmpty { "(none)" }
                ),
        )
        return Command.SINGLE_SUCCESS
    }

    private fun runPreview(ctx: CommandContext<CommandSourceStack>, core: CoreServices): Int {
        val source = ctx.source
        if (!CommandSupport.requirePermission(source, core, Permissions.PREVIEW)) return 0
        val target = CommandSupport.resolveTargetOrThrow(core, StringArgumentType.getString(ctx, "player"))
        val state = core.selectionService.stateOf(target.uuid)
        val profileName = core.serverAdapter.profileName(target.uuid) ?: "Unknown"
        val dimension = core.serverAdapter.dimensionKey(target.uuid)
        val resolved = core.snapshotResolver.resolve(state, profileName, dimension)

        source.success("Preview for ${target.displayName} (${target.uuid}):")
        source.success(
            Component.literal("  First line: ").append(resolved.firstLine.copy()),
        )
        if (resolved.secondLine != null) {
            source.success(
                Component.literal("  Second line (rule '${resolved.matchedRuleId}'): ")
                    .append(resolved.secondLine.copy()),
            )
        } else {
            source.success("  Second line: (no rule matched)")
        }
        source.success("  Nickname in use: ${state.nickname ?: "(profile name)"}")
        return Command.SINGLE_SUCCESS
    }

    private fun runReload(ctx: CommandContext<CommandSourceStack>, core: CoreServices): Int {
        val source = ctx.source
        if (!CommandSupport.requirePermission(source, core, Permissions.RELOAD)) return 0
        core.reloadAsync { result ->
            when (result) {
                is ConfigLoadResult.Success ->
                    source.success(
                        "Configuration reloaded (version ${core.configService.version}, " +
                            "${result.config.titles.size} titles); online players were revalidated.",
                    )
                is ConfigLoadResult.Failure ->
                    source.fail(
                        "Reload failed; previous configuration stays active. Errors: " +
                            result.errors.joinToString("; ") { it.toString() },
                    )
            }
        }
        return Command.SINGLE_SUCCESS
    }

    private fun runValidate(ctx: CommandContext<CommandSourceStack>, core: CoreServices): Int {
        val source = ctx.source
        if (!CommandSupport.requirePermission(source, core, Permissions.VALIDATE)) return 0
        core.configService.validateFileAsync { errors ->
            if (errors.isEmpty()) {
                source.success("Configuration is valid (currently ${core.configService.current.titles.size} titles).")
            } else {
                source.fail("Configuration has ${errors.size} problem(s):")
                for (error in errors) {
                    source.fail("  - $error")
                }
            }
        }
        return Command.SINGLE_SUCCESS
    }

    private fun runDiagnose(ctx: CommandContext<CommandSourceStack>, core: CoreServices): Int {
        val source = ctx.source
        if (!CommandSupport.requirePermission(source, core, Permissions.DIAGNOSE)) return 0
        val info = core.diagnostics()
        source.success("=== Fancy Crests ${info.modVersion} diagnostics ===")
        source.success(
            "Channels: headRender=${info.channels.headRender}, chat=${info.channels.chat}, " +
                "tabList=${info.channels.tabList}, placeholdersExport=${info.channels.placeholdersExport}",
        )
        source.success("Permission backends (order): ${info.permissionBackends.joinToString(" -> ")}")
        source.success(
            "Catalog: version=${info.catalogVersion}, titles=${info.catalogSize}",
        )
        source.success(
            "State: trackedPlayers=${info.trackedPlayers}, ownershipRecords=${info.ownershipRecords}, " +
                "snapshotTargets=${info.publishedSnapshotTargets}",
        )
        source.success(
            if (info.storageAvailable) {
                "Storage: ok (SQLite, schema ${dev.fancycrests.core.storage.SqliteStorage.SCHEMA_VERSION})"
            } else {
                "Storage: DISABLED — ${info.storageError ?: "unknown error"}"
            },
        )
        source.success("Renderer internals: owned by the render module (placeholder read point).")
        return Command.SINGLE_SUCCESS
    }

    // ----------------------------------------------------------------------
    // Shared result reporting
    // ----------------------------------------------------------------------

    /** Translates a domain [SelectionResult] into command feedback. */
    private fun reportResult(source: CommandSourceStack, result: SelectionResult, successText: String) {
        when (result) {
            is SelectionResult.Success -> source.success(successText)
            is SelectionResult.Rejected -> source.fail(rejectionText(result))
            SelectionResult.Conflict ->
                source.fail("The change conflicted with a concurrent write; nothing was modified. Try again.")
            is SelectionResult.StorageFailure ->
                source.fail("Storage error, nothing was changed: ${result.message}")
            SelectionResult.Cancelled ->
                source.fail("The change was cancelled by an event listener; nothing was written.")
        }
    }

    private fun rejectionText(rejection: SelectionResult.Rejected): String {
        val detail = rejection.detail?.let { ": $it" } ?: ""
        return when (rejection.reason) {
            RejectionReason.TITLE_NOT_FOUND -> "Unknown title${detail}"
            RejectionReason.SLOT_MISMATCH -> "Title belongs to the other slot${detail}"
            RejectionReason.ALREADY_EQUIPPED -> "Already equipped${detail}"
            RejectionReason.LIMIT_REACHED -> "Equip limit reached${detail}"
            RejectionReason.NOT_ELIGIBLE -> "Not eligible${detail}"
            RejectionReason.MISSING_PERMISSION -> "Missing permission${detail}"
            RejectionReason.NICKNAME_DISABLED -> "Nicknames are disabled${detail}"
            RejectionReason.NICKNAME_TOO_SHORT -> "Nickname too short${detail}"
            RejectionReason.NICKNAME_TOO_LONG -> "Nickname too long${detail}"
            RejectionReason.NICKNAME_PATTERN -> "Nickname not allowed${detail}"
            RejectionReason.NICKNAME_RESERVED -> "Nickname reserved${detail}"
            RejectionReason.NICKNAME_OCCUPIED -> "Nickname occupies another player's name${detail}"
            RejectionReason.EVENT_CANCELLED -> "Cancelled by an event listener${detail}"
            RejectionReason.PLAYER_OFFLINE -> "Player offline${detail}"
        }
    }
}
