package dev.fancycrests.core.config

import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent

/**
 * Minimal legacy text parser: converts the vanilla `§` colour/format codes
 * into structured [Component]s. There is no MiniMessage in 26.2; the config
 * accepts plain text plus these codes for title/rule styling.
 *
 * Supported codes: 0-9 a-f (colours), k (obfuscated), l (bold),
 * m (strikethrough), n (underline), o (italic), r (reset).
 * Unknown codes are dropped.
 */
object LegacyTextParser {

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

        while (index < input.length) {
            val c = input[index]
            if (c == '§' && index + 1 < input.length) {
                flushPlain(index)
                val code = input[index + 1].lowercaseChar()
                when {
                    code == 'r' -> {
                        color = null
                        activeDecorations.clear()
                    }
                    colors.containsKey(code) -> {
                        color = colors[code]
                        activeDecorations.clear()
                    }
                    decorations.containsKey(code) -> activeDecorations.add(decorations[code]!!)
                    else -> {
                        // Unknown code: emit it literally so nothing is silently lost.
                        val literal = Component.literal(input.substring(index, index + 2))
                        color?.let { literal.withStyle(it) }
                        root.append(literal)
                    }
                }
                index += 2
                plainStart = index
            } else {
                index += 1
            }
        }
        flushPlain(input.length)

        // Collapse a root that only holds one child into that child.
        val siblings = root.siblings
        return if (siblings.size == 1 && root.style.isEmpty) siblings[0].copy() else root
    }

    /** Strips all `§` codes (the code char plus its code) from [input]. */
    fun strip(input: String): String {
        if (!input.contains('§')) return input
        val sb = StringBuilder(input.length)
        var index = 0
        while (index < input.length) {
            val c = input[index]
            if (c == '§' && index + 1 < input.length) {
                index += 2
            } else {
                sb.append(c)
                index += 1
            }
        }
        return sb.toString()
    }

    /** True when [input] contains any `§` format code. */
    fun containsLegacyCodes(input: String): Boolean = input.indexOf('§') >= 0
}
