package dev.fancycrests.render

import dev.fancycrests.api.HeadLine
import dev.fancycrests.api.RenderDistance
import dev.fancycrests.api.RenderOffset
import dev.fancycrests.api.RenderSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import net.minecraft.network.chat.Component

class CustomLineLayoutTest {
    @Test
    fun `per-player toggle only limits lines visible to self`() {
        val lines = listOf(
            HeadLine(Component.literal("public"), false, true),
            HeadLine(Component.literal("others"), true, false),
            HeadLine(Component.empty(), false, true),
        )
        assertEquals(setOf(0, 1), CustomLineLayout.visibleIndices(lines, false, false))
        assertEquals(setOf(0), CustomLineLayout.visibleIndices(lines, true, true))
        assertEquals(emptySet(), CustomLineLayout.visibleIndices(lines, true, false))
    }

    @Test
    fun `255 lines retain top to bottom order and existing offsets`() {
        val settings = RenderSettings(RenderDistance(48.0), RenderOffset(0.0, 0.4, 0.0), RenderOffset(0.0, 0.15, 0.0))
        assertEquals(0.4, CustomLineLayout.offset(0, 2, settings).y, 0.000001)
        assertEquals(0.15, CustomLineLayout.offset(1, 2, settings).y, 0.000001)
        assertEquals(0.15, CustomLineLayout.offset(254, 255, settings).y, 0.000001)
        assertEquals(63.65, CustomLineLayout.offset(0, 255, settings).y, 0.000001)
    }
}
