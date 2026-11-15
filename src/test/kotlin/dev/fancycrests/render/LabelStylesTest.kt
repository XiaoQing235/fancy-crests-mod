package dev.fancycrests.render

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import net.minecraft.util.Brightness

class LabelStylesTest {
    @Test
    fun `foreground draws after the background without adding a visible plate`() {
        val foregroundPlate = LabelStyles.plateOf(LabelLayer.FIRST_FOREGROUND, sneaking = false)
        assertNotEquals(0, foregroundPlate)
        assertEquals(0, foregroundPlate ushr 24)
        assertEquals(0x40, LabelStyles.plateOf(LabelLayer.FIRST_BACKGROUND, sneaking = false) ushr 24)
        assertEquals((-1).toByte(), LabelStyles.textOpacityOf(LabelLayer.FIRST_FOREGROUND, sneaking = false))
    }

    @Test
    fun `head text uses full sky and block brightness`() {
        val brightness = Brightness.unpack(LabelStyles.FULL_BRIGHTNESS)
        assertEquals(15, brightness.sky())
        assertEquals(15, brightness.block())
    }
}
