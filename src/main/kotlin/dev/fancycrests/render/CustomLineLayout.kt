package dev.fancycrests.render

import dev.fancycrests.api.HeadLine
import dev.fancycrests.api.RenderOffset
import dev.fancycrests.api.RenderSettings

/** Pure selection and positioning shared by the per-observer render path. */
internal object CustomLineLayout {
    fun visibleIndices(lines: List<HeadLine>, isSelf: Boolean, showOwnNametag: Boolean): Set<Int> =
        lines.indices.filter { index ->
            val line = lines[index]
            line.text.string.isNotEmpty() && (!isSelf || showOwnNametag && line.selfVisible)
        }.toSet()

    fun offset(index: Int, size: Int, settings: RenderSettings): RenderOffset {
        val first = settings.firstLineOffset
        val second = settings.secondLineOffset
        val y = if (size == 1) first.y else second.y + (size - 1 - index) * (first.y - second.y)
        return RenderOffset(first.x, y, first.z)
    }
}
