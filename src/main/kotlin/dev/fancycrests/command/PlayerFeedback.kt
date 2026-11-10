package dev.fancycrests.command

import dev.fancycrests.api.TitleId
import dev.fancycrests.core.CoreServices
import dev.fancycrests.core.event.TitleChangeEventPre
import dev.fancycrests.core.event.TitleOperationType
import java.util.concurrent.ConcurrentHashMap
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Player-side feedback bridge (P3 minimal feedback):
 *  - when a recheck unequips titles (lost eligibility, config reload,
 *    ownership revoked, login recheck), the affected player is told which
 *    titles were taken off ("lost eligibility -> unequipped X");
 *  - every committed selection/ownership operation is logged with the
 *    player UUID; identical (uuid, type) log lines are rate-limited so a
 *    burst of operations cannot flood the log (plan chapter 6 "输入校验").
 *
 * Attached once from [FancyCrestsCommands.register]; listeners run on the
 * main thread via the event bus. The unequip diff is captured from the
 * pre-commit candidate (before persistence) and only messaged after the
 * post-commit event confirms the write.
 */
object PlayerFeedback {
    private val logger: Logger = LoggerFactory.getLogger("FancyCrests/AdminOps")

    /** Minimum interval between identical (uuid, type) log entries. */
    private const val LOG_INTERVAL_MS = 5_000L

    @Volatile
    private var attached = false

    /** opId -> titles removed by that pending recheck. */
    private val pendingRecheckRemovals = ConcurrentHashMap<String, List<Pair<TitleId, dev.fancycrests.api.TitleSlot>>>()

    private val lastLoggedAt = ConcurrentHashMap<String, Long>()

    fun attach(core: CoreServices) {
        if (attached) return
        attached = true

        // Commands register during mod init, but the event bus only exists
        // after CoreServices.start() on SERVER_STARTED; defer the wiring and
        // rely on the integrator registering its start listener first.
        if (core.isStarted) {
            wire(core)
        } else {
            ServerLifecycleEvents.SERVER_STARTED.register { wire(core) }
        }
    }

    private fun wire(core: CoreServices) {
        core.eventBus.addPreListener { event -> onPre(event) }
        core.eventBus.addPostListener { event -> onPost(event, core) }
    }

    private fun onPre(event: TitleChangeEventPre) {
        if (event.type != TitleOperationType.RECHECK) return
        val removedPrefixes = event.current.prefixes - event.candidate.prefixes.toSet()
        val removedSuffixes = event.current.suffixes - event.candidate.suffixes.toSet()
        if (removedPrefixes.isEmpty() && removedSuffixes.isEmpty()) return
        val removed = removedPrefixes.map { it to dev.fancycrests.api.TitleSlot.PREFIX } +
            removedSuffixes.map { it to dev.fancycrests.api.TitleSlot.SUFFIX }
        pendingRecheckRemovals[event.operationId.value] = removed
    }

    private fun onPost(event: dev.fancycrests.core.event.TitleChangeEventPost, core: CoreServices) {
        if (event.type == TitleOperationType.RECHECK) {
            val removed = pendingRecheckRemovals.remove(event.operationId.value).orEmpty()
            if (removed.isEmpty()) return // nothing changed; nothing to report
            val names = removed.mapNotNull { (id, _) ->
                core.catalogService.get(id)?.displayName?.string ?: id.value
            }
            if (names.isNotEmpty()) {
                core.serverAdapter.sendSystemMessage(
                    event.uuid,
                    Component.literal("Lost eligibility; unequipped: ${names.joinToString(", ")}.")
                        .withStyle(ChatFormatting.YELLOW),
                )
            }
            throttledLog(
                event.uuid,
                "recheck-unequip",
                "uuid=${event.uuid} unequipped=${names.joinToString(",")} reason=${event.reason}",
            )
            return
        }
        throttledLog(
            event.uuid,
            event.type.name.lowercase(),
            "uuid=${event.uuid} op=${event.type} revision=${event.oldRevision}->${event.newRevision} reason=${event.reason}",
        )
    }

    /** Logs [line] at INFO unless the same key fired within the throttle window. */
    private fun throttledLog(uuid: java.util.UUID, key: String, line: String) {
        val mapKey = "$uuid:$key"
        val now = System.currentTimeMillis()
        val last = lastLoggedAt[mapKey] ?: 0L
        if (now - last < LOG_INTERVAL_MS) return
        lastLoggedAt[mapKey] = now
        logger.info("$line (op log)")
    }
}
