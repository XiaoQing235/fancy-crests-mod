package dev.fancycrests.adapter.placeholderapi

import dev.fancycrests.api.PlaceholderService
import dev.fancycrests.pubapi.ResolveChain
import eu.pb4.placeholders.api.Placeholder
import eu.pb4.placeholders.api.Placeholders
import eu.pb4.placeholders.api.PlaceholderResult
import eu.pb4.placeholders.api.ServerPlaceholderContext
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerPlayer
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Text Placeholder API (Patbox, `placeholder-api`) export of the mod's
 * standard placeholders. Registers one server placeholder per
 * [dev.fancycrests.api.StandardPlaceholders] entry as
 * `fancycrests:<key>` — i.e. `%fancycrests:prefix%`, `%fancycrests:suffix%`,
 * `%fancycrests:nickname%`, `%fancycrests:belowname%`.
 *
 * Semantics come exclusively from the injected
 * [dev.fancycrests.api.PlaceholderService] (frozen api contract): joined
 * equipped prefixes/suffixes, effective display name, resolved second line;
 * empty when there is nothing to show. This adapter only translates values
 * and never fabricates data.
 *
 * Empty-state semantics ("empty input renders as empty output"): a
 * placeholder's purpose is text concatenation, so every *normal* empty state
 * — no player bound to the context, nothing equipped, no nickname yet —
 * resolves to `value(Component.empty())`. The engine's default consumers
 * render an `invalid` result as literal diagnostics such as
 * `[fancycrests:prefix: no value]`, which would corrupt the surrounding text;
 * an empty component renders as nothing. `invalid` is reserved for genuinely
 * abnormal states (resolution loop, resolver crash) where surfacing that
 * something is wrong is the more useful behaviour.
 *
 * Safety (plan chapter 5):
 *  - values are read from published immutable snapshots via the service, so
 *    resolution is safe on any thread the engine calls us from;
 *  - every entry goes through [ResolveChain] — a direct re-entry of the same
 *    placeholder (loop "this mod -> external -> this mod") or a chain deeper
 *    than [ResolveChain.MAX_DEPTH] yields an invalid (empty) result;
 *  - resolver exceptions yield an invalid result plus a rate-limited log.
 *
 * Note (verified via javap on placeholder-api 3.1.0-beta.1+26.2): the
 * `Placeholders` registry has no unregister method, so [close] disables our
 * entries by re-registering an always-invalid handler.
 */
internal class PlaceholderApiRegistrations private constructor(
    private val identifiers: List<Identifier>,
) : AutoCloseable {

    companion object {
        private val logger: Logger = LoggerFactory.getLogger("FancyCrests/PlaceholderApi")

        fun register(service: PlaceholderService): PlaceholderApiRegistrations =
            PlaceholderApiRegistrations(
                dev.fancycrests.api.StandardPlaceholders.ALL.map { key -> registerOne(service, key) },
            )

        private fun registerOne(service: PlaceholderService, key: dev.fancycrests.api.PlaceholderKey): Identifier {
            val identifier = Identifier.fromNamespaceAndPath("fancycrests", key.value)
            // The registerServer overload has a phantom <T>; supply it explicitly.
            Placeholders.registerServer<String>(
                identifier,
                Placeholder.Handler<ServerPlaceholderContext, String> { context, _ ->
                    handle(service, key, identifier, context)
                },
            )
            return identifier
        }

        private fun handle(
            service: PlaceholderService,
            key: dev.fancycrests.api.PlaceholderKey,
            identifier: Identifier,
            context: ServerPlaceholderContext,
        ): PlaceholderResult {
            val target = targetUuid(context)
                ?: return PlaceholderResult.value(Component.empty())
            if (!ResolveChain.enter(identifier.toString())) {
                // Loop or runaway nesting: yield an empty value, never recurse.
                GuardedLog.rateLimited(logger, "loop:${identifier}") {
                    "Blocked cyclic placeholder resolution of ${'%'}${identifier}${'%'}"
                }
                return PlaceholderResult.invalid("fancycrests:${key.value}: cyclic resolution blocked")
            }
            return try {
                val component = service.resolve(key, dev.fancycrests.api.PlaceholderRequest(target, observer = null))
                if (component != null) {
                    PlaceholderResult.value(component)
                } else {
                    // Normal empty business state (nothing equipped / no value
                    // yet): render as empty text. invalid would make default
                    // consumers print "[fancycrests:<key>: no value]" into the
                    // surrounding text instead of nothing.
                    PlaceholderResult.value(Component.empty())
                }
            } catch (t: Throwable) {
                GuardedLog.rateLimited(logger, "error:${identifier}", t) {
                    "Placeholder resolver threw for ${identifier}"
                }
                PlaceholderResult.invalid("fancycrests:${key.value}: resolver error")
            } finally {
                ResolveChain.exit(identifier.toString())
            }
        }

        /** Target player of the placeholder, from whichever context shape the engine provided. */
        private fun targetUuid(context: ServerPlaceholderContext): UUID? {
            context.serverPlayer()?.let { player: ServerPlayer -> return player.gameProfile.id }
            context.gameProfile()?.let { profile -> return profile.id }
            context.player()?.let { player -> return player.gameProfile.id }
            return null
        }
    }

    override fun close() {
        for (identifier in identifiers) {
            try {
                Placeholders.registerServer<String>(
                    identifier,
                    Placeholder.Handler<ServerPlaceholderContext, String> { _, _ ->
                        PlaceholderResult.invalid("fancycrests placeholder export disabled")
                    },
                )
            } catch (t: Throwable) {
                logger.debug("Failed to disable placeholder {}", identifier, t)
            }
        }
    }
}

/** Key-limited logging: at most one line per key per [INTERVAL_MILLIS]. */
private object GuardedLog {
    private const val INTERVAL_MILLIS = 10_000L
    private val lastLogAt = ConcurrentHashMap<String, Long>()

    fun rateLimited(logger: Logger, key: String, error: Throwable? = null, message: () -> String) {
        val now = System.currentTimeMillis()
        val previous = lastLogAt.put(key, now)
        if (previous != null && now - previous < INTERVAL_MILLIS) return
        if (error != null) {
            logger.warn("{} [throttled]", message(), error)
        } else {
            logger.warn("{} [throttled]", message())
        }
    }
}
