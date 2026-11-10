package dev.fancycrests.pubapi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Loop/depth protection of the resolution source chain (plan chapter 5). */
class ResolveChainTest {

    @Test
    fun `direct reentry of an active id is blocked`() {
        assertTrue(ResolveChain.enter("fancycrests:prefix"))
        try {
            // "this mod -> external -> this mod" for the same source: blocked.
            assertFalse(ResolveChain.enter("fancycrests:prefix"))
            // A different id may still enter once (not a direct loop).
            assertTrue(ResolveChain.enter("fancycrests:nickname"))
        } finally {
            ResolveChain.exit("fancycrests:nickname")
            ResolveChain.exit("fancycrests:prefix")
        }
        assertEquals(0, ResolveChain.depth())
        assertTrue(ResolveChain.activeIds().isEmpty())
    }

    @Test
    fun `depth limit aborts runaway chains`() {
        // Nest distinct ids up to the limit; the next entry is refused.
        var entered = 0
        try {
            for (i in 0 until ResolveChain.MAX_DEPTH) {
                check(ResolveChain.enter("id-$i")) { "entry $i should be allowed" }
                entered++
            }
            assertFalse(ResolveChain.enter("id-overflow"))
            assertEquals(ResolveChain.MAX_DEPTH, ResolveChain.depth())
        } finally {
            repeat(entered) { ResolveChain.exit("id-$it") }
        }
        assertEquals(0, ResolveChain.depth())
    }

    @Test
    fun `exit restores reentry`() {
        ResolveChain.enter("fancycrests:belowname")
        ResolveChain.exit("fancycrests:belowname")
        assertTrue(ResolveChain.enter("fancycrests:belowname"))
        ResolveChain.exit("fancycrests:belowname")
        assertEquals(0, ResolveChain.depth())
    }
}
