package dev.fancycrests.core.refresh

import dev.fancycrests.api.PlaceholderKey
import dev.fancycrests.api.PlaceholderRequest
import dev.fancycrests.api.PlaceholderService
import dev.fancycrests.core.config.LegacyTextParser
import java.util.UUID
import net.minecraft.network.chat.Component

/**
 * Splits a second-line (or export) template into static text and built-in
 * placeholder tokens. Pure string logic — no Minecraft types — so it is unit
 * testable without a server.
 *
 * Only `%fancycrests:key%` tokens are recognized; anything else (including
 * other namespaces such as `%other:x%`) stays literal static text. Player
 * input therefore can never become an expression, script or command.
 */
object TemplateTokenizer {
    private val TOKEN = Regex("%fancycrests:([A-Za-z0-9_-]+)%")

    /** One parsed piece of a template. */
    sealed interface Segment {
        /** Static text; may contain `§` legacy codes handled by [LegacyTextParser]. */
        data class Static(val text: String) : Segment

        /** A `%fancycrests:key%` reference. */
        data class Placeholder(val key: String) : Segment
    }

    fun tokenize(template: String): List<Segment> {
        if (template.isEmpty()) return emptyList()
        val segments = mutableListOf<Segment>()
        var index = 0
        for (match in TOKEN.findAll(template)) {
            val literal = template.substring(index, match.range.first)
            if (literal.isNotEmpty()) segments += Segment.Static(literal)
            segments += Segment.Placeholder(match.groupValues[1])
            index = match.range.last + 1
        }
        if (index < template.length) segments += Segment.Static(template.substring(index))
        return segments
    }

    /** True when [template] contains at least one built-in placeholder token. */
    fun containsPlaceholders(template: String): Boolean = TOKEN.containsMatchIn(template)
}

/**
 * Renders templates that mix static `§`-styled text with built-in
 * `%fancycrests:key%` placeholders resolved through the shared
 * [PlaceholderService].
 *
 * Guarantees (plan chapter 4 "模板契约"):
 *  - static text is parsed with [LegacyTextParser]; a zero-placeholder
 *    template renders exactly like P2 static text (semantics unchanged);
 *  - unknown keys and resolver exceptions degrade to empty output plus a
 *    [Result.missingKeys] entry — never fabricated data, never an exception
 *    escaping to the caller;
 *  - recursion depth, total plain-text length and total resolution steps are
 *    capped (simple counters instead of wall-clock timers); when a cap trips
 *    the render is truncated and flagged;
 *  - the second-line cycle guard ([withSecondLineResolution]) makes
 *    `%fancycrests:belowname%` inside its own rule resolve to empty instead of
 *    recursing or growing the line every refresh cycle.
 *
 * Thread safety: rendering is stateless except for the per-thread second-line
 * marker, so renders from async placeholder adapters are safe as long as the
 * registered resolvers read only published immutable state (they do).
 */
class TemplateEngine(private val placeholders: PlaceholderService) {

    companion object {
        const val NAMESPACE_PREFIX = "fancycrests"
        const val MAX_RENDER_DEPTH = 6
        const val MAX_TOTAL_PLAIN_LENGTH = 2048
        const val MAX_RESOLUTION_STEPS = 10_000
    }

    /** Render outcome; [component] is always usable (possibly empty/truncated). */
    data class Result(
        val component: Component,
        val missingKeys: List<PlaceholderKey>,
        val truncated: Boolean,
    )

    /** Per-thread target whose second line is currently being resolved. */
    private val resolvingSecondLine = ThreadLocal<UUID?>()

    /** True while [target]'s second line is being rendered on this thread. */
    fun isSecondLineResolving(target: UUID): Boolean = resolvingSecondLine.get() == target

    /** Runs [block] with the second-line marker set for [target]. */
    inline fun <T> withSecondLineResolution(target: UUID, block: () -> T): T {
        resolvingSecondLine.set(target)
        try {
            return block()
        } finally {
            resolvingSecondLine.set(null)
        }
    }

    /**
     * Renders [template] for [request]. [depth] is the engine re-entry depth
     * (a placeholder resolver calling back into the engine); it is capped.
     */
    fun render(template: String, request: PlaceholderRequest, depth: Int = 0): Result {
        if (depth > MAX_RENDER_DEPTH) {
            return Result(Component.empty(), emptyList(), truncated = true)
        }
        val segments = TemplateTokenizer.tokenize(template)
        if (segments.isEmpty()) return Result(Component.empty(), emptyList(), truncated = false)

        // Zero-placeholder templates keep the exact P2 rendering path.
        if (segments.all { it is TemplateTokenizer.Segment.Static }) {
            val text = segments.joinToString("") { (it as TemplateTokenizer.Segment.Static).text }
            return Result(LegacyTextParser.parse(text), emptyList(), truncated = false)
        }

        val missing = mutableListOf<PlaceholderKey>()
        var truncated = false
        var totalLength = 0
        var steps = 0
        val root = Component.empty()
        var appended = 0

        for (segment in segments) {
            when (segment) {
                is TemplateTokenizer.Segment.Static -> {
                    steps++
                    totalLength += segment.text.length
                    if (totalLength > MAX_TOTAL_PLAIN_LENGTH || steps > MAX_RESOLUTION_STEPS) {
                        truncated = true
                        break
                    }
                    if (segment.text.isNotEmpty()) {
                        root.append(LegacyTextParser.parse(segment.text))
                        appended++
                    }
                }
                is TemplateTokenizer.Segment.Placeholder -> {
                    steps++
                    if (steps > MAX_RESOLUTION_STEPS) {
                        truncated = true
                        break
                    }
                    val key = PlaceholderKey(segment.key)
                    val resolved = try {
                        placeholders.resolve(key, request)
                    } catch (t: Throwable) {
                        // A misbehaving resolver must not break rendering.
                        null
                    }
                    if (resolved == null) {
                        missing += key
                        continue
                    }
                    totalLength += resolved.string.length
                    if (totalLength > MAX_TOTAL_PLAIN_LENGTH) {
                        truncated = true
                        break
                    }
                    root.append(resolved.copy())
                    appended++
                }
            }
        }

        // Collapse a root holding a single child into that child, matching
        // LegacyTextParser's output shape.
        val component = if (appended == 1 && root.style.isEmpty) {
            root.siblings[0].copy()
        } else {
            root
        }
        return Result(component, missing, truncated)
    }
}
