package dev.fancycrests.render

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Target-side label slot lifecycle (render batch E1): ids are allocated from
 * the server's unified allocator once per cycle, reallocation only after
 * [LabelEntityIds.invalidate], and the `virtualEntities` gauge in
 * [RenderService] is balanced against exactly the transitions reported here
 * (`allocateFirstLine`/`allocateSecondLine` returning true, the second-line
 * free paths observing a non-zero id). A drift in this table is a drift in
 * the diagnostics gauge.
 */
class LabelEntityIdsTest {

    private fun allocator(): () -> Int {
        var next = 100
        return { next++ }
    }

    @Test
    fun `first line allocates exactly once per cycle`() {
        val ids = LabelEntityIds()
        assertTrue(ids.allocateFirstLine(allocator()), "first allocation reports change")
        val fg = ids.foregroundId
        val bg = ids.backgroundId
        assertNotEquals(fg, bg, "foreground and background must be distinct ids")

        assertFalse(ids.allocateFirstLine(allocator()), "second call in the same cycle is a no-op")
        assertEquals(fg, ids.foregroundId)
        assertEquals(bg, ids.backgroundId)
        assertTrue(ids.allocatedFirstLine)
    }

    @Test
    fun `second line allocates once and reports true only when newly allocated`() {
        val ids = LabelEntityIds()
        assertTrue(ids.allocateSecondLine(allocator()))
        val id = ids.secondLineId
        val uuid = ids.secondLineUuid
        assertNotEquals(0, id)

        assertFalse(ids.allocateSecondLine(allocator()), "slot already allocated -> reuse, not reallocation")
        assertEquals(id, ids.secondLineId, "reuse keeps the slot id (observers re-AddEntity it)")
        assertEquals(uuid, ids.secondLineUuid, "reuse keeps the entity uuid")
    }

    @Test
    fun `invalidate resets the whole cycle and fresh ids are drawn afterwards`() {
        val alloc = allocator()
        val ids = LabelEntityIds()
        ids.allocateFirstLine(alloc)
        ids.allocateSecondLine(alloc)
        val oldFg = ids.foregroundId
        val oldSecond = ids.secondLineId
        val oldSecondUuid = ids.secondLineUuid

        ids.invalidate()

        assertFalse(ids.allocatedFirstLine)
        assertEquals(0, ids.secondLineId, "invalidate frees the second-line slot")
        assertTrue(ids.allocateFirstLine(alloc), "new cycle re-allocates the first line")
        assertTrue(ids.allocateSecondLine(alloc), "new cycle re-allocates the second line")
        assertNotEquals(oldFg, ids.foregroundId)
        assertNotEquals(oldSecond, ids.secondLineId)
        assertNotEquals(oldSecondUuid, ids.secondLineUuid, "fresh uuid prevents client-side id collisions across cycles")
    }

    @Test
    fun `second line can be dropped independently of the first line cycle`() {
        val ids = LabelEntityIds()
        ids.allocateFirstLine(allocator())
        ids.allocateSecondLine(allocator())
        // RenderService.spawnLabels' placeholder drop writes the field
        // directly when the snapshot lost the second line before any spawn.
        ids.secondLineId = 0

        assertTrue(ids.allocatedFirstLine, "first line cycle is untouched")
        assertTrue(ids.allocateSecondLine(allocator()), "second line re-allocates after the drop")
    }
}
