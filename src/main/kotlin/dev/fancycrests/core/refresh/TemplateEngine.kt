package dev.fancycrests.core.refresh

import dev.fancycrests.api.PlaceholderKey
import dev.fancycrests.api.PlaceholderRequest
import dev.fancycrests.api.PlaceholderService
import dev.fancycrests.core.config.LegacyTextParser
import dev.fancycrests.core.config.NamedConditionConfig
import java.util.UUID
import net.minecraft.network.chat.Component

/**
 * Splits a second-line (or export) template into static text and built-in
 * placeholder tokens. Pure string logic — no Minecraft types — so it is unit
 * testable without a server.
 *
 * Placeholder text is only resolved through registered providers; it is never
 * interpreted as a command or script.
 */
object TemplateTokenizer {
    private val TOKEN = Regex("%([A-Za-z0-9_][A-Za-z0-9_:./ -]{0,255})%")

    /** One parsed piece of a template. */
    sealed interface Segment {
        /** Static text; may contain `§` legacy codes handled by [LegacyTextParser]. */
        data class Static(val text: String) : Segment

        /** A `%fancycrests:key%` reference. */
        data class Placeholder(val key: String) : Segment

        /** A named condition, built-in player value, or external provider token. */
        data class External(val token: String) : Segment
    }

    fun tokenize(template: String): List<Segment> {
        if (template.isEmpty()) return emptyList()
        val segments = mutableListOf<Segment>()
        var index = 0
        for (match in TOKEN.findAll(template)) {
            val literal = template.substring(index, match.range.first)
            if (literal.isNotEmpty()) segments += Segment.Static(literal)
            val token = match.groupValues[1]
            segments += if (token.startsWith("fancycrests:") &&
                Regex("[A-Za-z0-9_-]+").matches(token.removePrefix("fancycrests:"))) {
                Segment.Placeholder(token.removePrefix("fancycrests:"))
            } else Segment.External(token)
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
class TemplateEngine(
    private val placeholders: PlaceholderService,
    private val conditions: () -> Map<String, NamedConditionConfig> = { emptyMap() },
    private val playerName: (UUID) -> String? = { null },
    private val external: (String, UUID) -> Component? = { _, _ -> null },
    private val hasPermission: (UUID, String) -> Boolean = { _, _ -> false },
) {

    companion object {
        const val NAMESPACE_PREFIX = "fancycrests"
        const val MAX_RENDER_DEPTH = 16
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

    /**
     * Per-thread engine re-entry counter: a resolver that calls back into the
     * engine increments it no matter which template it renders, capping
     * recursive placeholder chains.
     */
    private val renderDepth = ThreadLocal.withInitial { 0 }
    private val resolvingConditions = ThreadLocal.withInitial { mutableSetOf<String>() }
    private val scopedPlaceholders = ThreadLocal<Map<String, Component>?>()

    /** True while [target]'s second line is being rendered on this thread. */
    fun isSecondLineResolving(target: UUID): Boolean = resolvingSecondLine.get() == target

    /** Runs [block] with the second-line marker set for [target]. */
    fun <T> withSecondLineResolution(target: UUID, block: () -> T): T {
        resolvingSecondLine.set(target)
        try {
            return block()
        } finally {
            resolvingSecondLine.set(null)
        }
    }

    /**
     * Renders [template] for [request]. Engine re-entry (a resolver rendering
     * another template on the same thread) is capped by [MAX_RENDER_DEPTH];
     * output length and resolution steps are capped by simple counters.
     */
    fun render(template: String, request: PlaceholderRequest): Result {
        val depth = renderDepth.get()
        if (depth > MAX_RENDER_DEPTH) {
            return Result(Component.empty(), emptyList(), truncated = true)
        }
        renderDepth.set(depth + 1)
        try {
            return renderInner(template, request)
        } finally {
            renderDepth.set(depth)
        }
    }

    /** Render with already resolved values for the current head snapshot. */
    fun renderWithOverrides(template: String, request: PlaceholderRequest, overrides: Map<String, Component>): Result {
        val previous = scopedPlaceholders.get()
        scopedPlaceholders.set(overrides)
        return try {
            render(template, request)
        } finally {
            scopedPlaceholders.set(previous)
        }
    }

    private fun renderInner(template: String, request: PlaceholderRequest): Result {
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
                        scopedPlaceholders.get()?.get(segment.key) ?: placeholders.resolve(key, request)
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
                is TemplateTokenizer.Segment.External -> {
                    steps++
                    if (steps > MAX_RESOLUTION_STEPS) { truncated = true; break }
                    val token = segment.token
                    val conditionName = token.removePrefix("condition:")
                    val nested = if (token.startsWith("condition:")) {
                        renderCondition(conditionName, request)
                    } else null
                    val resolved = when {
                        token.startsWith("condition:") -> nested?.component
                        token == "player" -> playerName(request.target)?.let { Component.literal(it) }
                        else -> try { external(token, request.target) } catch (_: Exception) { null }
                    }
                    if (nested != null) {
                        missing += nested.missingKeys
                        truncated = truncated || nested.truncated
                    }
                    val output = resolved ?: Component.literal("%$token%")
                    if (resolved == null) missing += PlaceholderKey(token)
                    totalLength += output.string.length
                    if (totalLength > MAX_TOTAL_PLAIN_LENGTH) { truncated = true; break }
                    root.append(output.copy())
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

    private fun renderCondition(name: String, request: PlaceholderRequest): Result? {
        val condition = conditions()[name] ?: return null
        val stack = resolvingConditions.get()
        if (!stack.add(name)) return Result(Component.empty(), listOf(PlaceholderKey("condition:$name")), true)
        try {
            val matches = condition.conditions.map { expression ->
                val permission = ConditionExpressions.permission(expression)
                if (permission != null) {
                    hasPermission(request.target, permission)
                } else {
                    val comparison = ConditionExpressions.parse(expression) ?: return@map false
                    val left = render(comparison.left, request)
                    val right = render(comparison.right, request)
                    if (left.truncated || right.truncated || left.missingKeys.isNotEmpty() || right.missingKeys.isNotEmpty()) {
                        false
                    } else ConditionExpressions.compare(left.component.string, comparison.operator, right.component.string)
                }
            }
            val matched = if (condition.any) matches.any { it } else matches.all { it }
            return render(if (matched) condition.whenTrue else condition.whenFalse, request)
        } finally {
            stack.remove(name)
        }
    }
}

/** The deliberately small, non-executable subset of TAB-style condition expressions. */
object ConditionExpressions {
    data class Comparison(val left: String, val operator: String, val right: String)
    private val comparison = Regex("^\\s*(.*?)\\s*(==|!=|>=|<=|=|>|<)\\s*(.*?)\\s*$")
    fun permission(expression: String): String? = expression.trim().removePrefix("permission:")
        .takeIf { expression.trim().startsWith("permission:") && it.isNotBlank() }
    fun parse(expression: String): Comparison? = comparison.matchEntire(expression)?.let {
        Comparison(it.groupValues[1], it.groupValues[2], it.groupValues[3])
    }
    fun isValid(expression: String): Boolean = permission(expression) != null || parse(expression) != null
    fun compare(left: String, op: String, right: String): Boolean = when (op) {
        "=", "==" -> left == right
        "!=" -> left != right
        else -> {
            val l = left.toBigDecimalOrNull()
            val r = right.toBigDecimalOrNull()
            if (l == null || r == null) false else when (op) {
                ">" -> l > r
                ">=" -> l >= r
                "<" -> l < r
                "<=" -> l <= r
                else -> false
            }
        }
    }
}
