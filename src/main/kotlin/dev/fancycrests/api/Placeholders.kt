package dev.fancycrests.api

import java.util.UUID
import net.minecraft.network.chat.Component

/** Placeholder key within the fancycrests namespace, e.g. "prefix". */
@JvmInline
value class PlaceholderKey(val value: String) {
    override fun toString(): String = value
}

/**
 * A placeholder resolution request. `observer` must never influence the
 * resolved text (uniform content per target); it exists only for future
 * protocol bookkeeping and must be ignored by resolvers.
 */
data class PlaceholderRequest(
    val target: UUID,
    val observer: UUID?,
)

/**
 * Resolves one placeholder to structured text. Implementations must:
 * - read only published immutable snapshots,
 * - be safe to call from async threads,
 * - never execute commands or scripts,
 * - never block on I/O,
 * - return null for unknown keys or missing values (no fabricated data).
 */
interface PlaceholderResolver {
    fun resolve(key: PlaceholderKey, request: PlaceholderRequest): Component?
}

/**
 * Registry of built-in placeholders, shared by the internal template engine
 * (second-line rules) and external export adapters (Text Placeholder API, ...).
 * Register/unregister happen on the main thread; resolve may be called async.
 */
interface PlaceholderService {
    fun register(key: PlaceholderKey, resolver: PlaceholderResolver)
    fun unregister(key: PlaceholderKey)
    fun resolve(key: PlaceholderKey, request: PlaceholderRequest): Component?
    fun registeredKeys(): List<PlaceholderKey>
}

/** Standard placeholder keys (rendered as %fancycrests:<key>%). */
object StandardPlaceholders {
    /** All equipped prefixes joined by configured separators; empty if none. */
    val PREFIX = PlaceholderKey("prefix")

    /** All equipped suffixes joined by configured separators; empty if none. */
    val SUFFIX = PlaceholderKey("suffix")

    /** Effective display name: nickname if set, else current GameProfile name. */
    val NICKNAME = PlaceholderKey("nickname")

    /** Resolved second line from admin rules; empty if none. */
    val BELOWNAME = PlaceholderKey("belowname")

    val ALL = listOf(PREFIX, SUFFIX, NICKNAME, BELOWNAME)
}
