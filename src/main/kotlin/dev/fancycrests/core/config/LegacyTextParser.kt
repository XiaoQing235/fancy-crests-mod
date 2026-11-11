package dev.fancycrests.core.config

import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent

/**
 * Minimal legacy text parser: converts classic colour/format codes into
 * structured [Component]s. There is no MiniMessage in 26.2; the config
 * accepts plain text plus these codes for title/rule styling.
 *
 * Two prefix characters are accepted:
 *  - `&` — the primary, recommended form (easy to type in chat and config);
 *  - `§` — the vanilla character, still accepted for compatibility with
 *    existing configs and stored data.
 *
 * Supported codes: 0-9 a-f (colours), k (obfuscated), l (bold),
 * m (strikethrough), n (underline), o (italic), r (reset).
 * Unknown codes are emitted literally. A doubled prefix (`&&` / `§§`)
 * escapes into a single literal prefix character.
 */
object LegacyTextParser {

    private const val PRIMARY_PREFIX = '&'
    private const val LEGACY_PREFIX = '§'

    private fun isPrefix(c: Char): Boolean = c == PRIMARY_PREFIX || c == LEGACY_PREFIX

    private val colors: Map<Char, ChatFormatting> = buildMap {
        put('0', ChatFormatting.BLACK)
        put('1', ChatFormatting.DARK_BLUE)
        put('2', ChatFormatting.DARK_GREEN)
        put('3', ChatFormatting.DARK_AQUA)
        put('4', ChatFormatting.DARK_RED)
        put('5', ChatFormatting.DARK_PURPLE)
        put('6', ChatFormatting.GOLD)
        put('7', ChatFormatting.GRAY)
        put('8', ChatFormatting.DARK_GRAY)
        put('9', ChatFormatting.BLUE)
        put('a', ChatFormatting.GREEN)
        put('b', ChatFormatting.AQUA)
        put('c', ChatFormatting.RED)
        put('d', ChatFormatting.LIGHT_PURPLE)
        put('e', ChatFormatting.YELLOW)
        put('f', ChatFormatting.WHITE)
    }

    private val decorations: Map<Char, ChatFormatting> = buildMap {
        put('k', ChatFormatting.OBFUSCATED)
        put('l', ChatFormatting.BOLD)
        put('m', ChatFormatting.STRIKETHROUGH)
        put('n', ChatFormatting.UNDERLINE)
        put('o', ChatFormatting.ITALIC)
    }

    /**
     * Parses [input] into a structured component. Sibling segments carry
     * independent styles, so a colour/decoration never leaks into the next
     * fragment. Plain input without codes yields a single literal component.
     */
    fun parse(input: String): Component {
        if (input.isEmpty()) return Component.empty()

        var color: ChatFormatting? = null
        val activeDecorations = LinkedHashSet<ChatFormatting>()
        val root: MutableComponent = Component.empty()
        var index = 0
        var plainStart = 0

        fun flushPlain(end: Int) {
            if (end > plainStart) {
                val literal = Component.literal(input.substring(plainStart, end))
                color?.let { literal.withStyle(it) }
                if (activeDecorations.isNotEmpty()) {
                    literal.withStyle(*activeDecorations.toTypedArray())
                }
                root.append(literal)
            }
        }

        fun appendLiteral(literalStart: Int, literalEnd: Int) {
            flushPlain(literalStart)
            val literal = Component.literal(input.substring(literalStart, literalEnd))
            color?.let { literal.withStyle(it) }
            if (activeDecorations.isNotEmpty()) {
                literal.withStyle(*activeDecorations.toTypedArray())
            }
            root.append(literal)
        }

        while (index < input.length) {
            val c = input[index]
            if (isPrefix(c) && index + 1 < input.length) {
                val next = input[index + 1]
                when {
                    // Doubled prefix: escaped literal prefix character.
                    next == c -> appendLiteral(index, index + 1)
                    next.lowercaseChar() == 'r' -> {
                        flushPlain(index)
                        color = null
                        activeDecorations.clear()
                    }
                    colors.containsKey(next.lowercaseChar()) -> {
                        flushPlain(index)
                        color = colors[next.lowercaseChar()]
                        activeDecorations.clear()
                    }
                    decorations.containsKey(next.lowercaseChar()) -> {
                        flushPlain(index)
                        activeDecorations.add(decorations[next.lowercaseChar()]!!)
                    }
                    else -> {
                        // Unknown code: emit it literally so nothing is
                        // silently lost ("Tom & Jerry" keeps its ampersand).
                        appendLiteral(index, index + 2)
                    }
                }
                index += 2
                plainStart = index
            } else {
                // Lone prefix at the very end stays as plain text.
                index += 1
            }
        }
        flushPlain(input.length)

        // Collapse a root that only holds one child into that child.
        val siblings = root.siblings
        return if (siblings.size == 1 && root.style.isEmpty) siblings[0].copy() else root
    }

    /**
     * Removes colour/format code pairs (either prefix) from [input] so the
     * result reflects the visible text. Doubled prefixes collapse into a
     * single literal prefix character. A lone trailing prefix is kept.
     *
     * Two modes:
     *  - default (`knownCodesOnly = false`): every `&X` / `§X` pair is
     *    removed, whatever X is. Config validation relies on this
     *    aggressive form to treat text made of arbitrary pairs as blank.
     *  - `knownCodesOnly = true`: only pairs with a known code (colours
     *    0-9a-f, decorations k-o, reset r — either case, either prefix) are
     *    removed, i.e. exactly the pairs [parse] consumes. Unknown pairs
     *    remain as the two literal characters they render as, so length and
     *    pattern checks see what players actually see: "Bo&zb" keeps its
     *    unknown pair instead of collapsing to "Bob". Nickname validation
     *    uses this mode.
     */
    fun strip(input: String, knownCodesOnly: Boolean = false): String {
        if (!containsLegacyCodes(input)) return input
        val sb = StringBuilder(input.length)
        var index = 0
        while (index < input.length) {
            val c = input[index]
            if (isPrefix(c) && index + 1 < input.length) {
                when {
                    input[index + 1] == c -> {
                        // Doubled prefix: escaped literal prefix character.
                        sb.append(c)
                        index += 2
                    }
                    knownCodesOnly && !isKnownCode(input[index + 1]) -> {
                        // Unknown pair: parse renders it literally, so the
                        // visible text keeps both characters.
                        sb.append(c)
                        sb.append(input[index + 1])
                        index += 2
                    }
                    else -> index += 2
                }
            } else {
                sb.append(c)
                index += 1
            }
        }
        return sb.toString()
    }

    /** True when [code] (either case) is one of the codes [parse] consumes. */
    private fun isKnownCode(code: Char): Boolean {
        val lower = code.lowercaseChar()
        return lower == 'r' || colors.containsKey(lower) || decorations.containsKey(lower)
    }

    /** True when [input] contains a `&` or `§` prefix character. */
    fun containsLegacyCodes(input: String): Boolean =
        input.indexOf(PRIMARY_PREFIX) >= 0 || input.indexOf(LEGACY_PREFIX) >= 0
}
