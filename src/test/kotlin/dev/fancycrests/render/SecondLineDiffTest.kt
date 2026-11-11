package dev.fancycrests.render

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The second-line routing table of [RenderService.pushSnapshot] (render batch
 * E1): the (sent, current) content pair must map to exactly the wire action
 * that keeps the observer-side invariant `sentSecondLine != null <=>
 * secondLineId != 0` recoverable. The historical defects were both misroutes
 * caused by the cache lying after an add/remove:
 *
 *  - add A then remove: cache never advanced to A, so the diff saw
 *    (null, null) and never removed the spawned entity (client ghost);
 *  - remove A then restore A: cache never cleared, so the diff saw (A, A)
 *    and never re-spawned the deleted entity (permanently missing line).
 *
 * [SecondLineDiff.actionOf] is generic; production instantiates it with
 * Components, these tests use value-equal strings, which share the required
 * structural-equality semantics.
 */
class SecondLineDiffTest {

    @Test
    fun `absent to content adds`() {
        assertEquals(SecondLineAction.ADD, SecondLineDiff.actionOf<String?>(null, "A"))
    }

    @Test
    fun `content to absent removes`() {
        assertEquals(SecondLineAction.REMOVE, SecondLineDiff.actionOf("A", null))
    }

    @Test
    fun `different content updates`() {
        assertEquals(SecondLineAction.UPDATE, SecondLineDiff.actionOf("A", "B"))
    }

    @Test
    fun `equal content is a no-op`() {
        assertEquals(SecondLineAction.NONE, SecondLineDiff.actionOf("A", "A"))
    }

    @Test
    fun `absent on both sides is a no-op`() {
        assertEquals(SecondLineAction.NONE, SecondLineDiff.actionOf<String?>(null, null))
    }

    @Test
    fun `add then remove then re-add is fully idempotent at the routing level`() {
        // Simulates the observer cache walking the audited scenario with the
        // fixed branch semantics: every successful action updates the cache.
        var sent: String? = null

        // add A
        assertEquals(SecondLineAction.ADD, SecondLineDiff.actionOf(sent, "A"))
        sent = "A" // ADD succeeded -> cache advances
        // remove
        assertEquals(SecondLineAction.REMOVE, SecondLineDiff.actionOf(sent, null))
        sent = null // REMOVE succeeded -> cache clears
        // restore A
        assertEquals(SecondLineAction.ADD, SecondLineDiff.actionOf(sent, "A"))
        sent = "A"
        // remove again — still routes to REMOVE, never to NONE
        assertEquals(SecondLineAction.REMOVE, SecondLineDiff.actionOf(sent, null))
        sent = null
        assertEquals(SecondLineAction.NONE, SecondLineDiff.actionOf(sent, null))
    }

    @Test
    fun `failed actions keep the cache so the next publish retries the same action`() {
        // ADD failed: cache stays null -> next publish re-routes to ADD.
        var sent: String? = null
        assertEquals(SecondLineAction.ADD, SecondLineDiff.actionOf(sent, "A"))
        sent = null // failure path: cache untouched
        assertEquals(SecondLineAction.ADD, SecondLineDiff.actionOf(sent, "A"))

        // REMOVE failed: cache stays non-null -> next publish re-routes to REMOVE.
        sent = "A"
        assertEquals(SecondLineAction.REMOVE, SecondLineDiff.actionOf(sent, null))
        // failure path: cache untouched
        assertEquals(SecondLineAction.REMOVE, SecondLineDiff.actionOf(sent, null))
    }

    @Test
    fun `second line add whose mount failed rolls back and retries as add`() {
        // Audit #6: the ADD branch's mount packet failed after the AddEntity
        // was already on the wire. The branch must roll the spawn back and
        // leave the presence cache at null so the next publish routes to ADD
        // again (a half-delivered entity that never followed the player is
        // removed, not patched into an UPDATE on a ghost).
        var sent: String? = null
        assertEquals(SecondLineAction.ADD, SecondLineDiff.actionOf(sent, "A"))
        sent = null // mount failed -> rollback, presence cache untouched

        // Next publish retries the full ADD (not UPDATE - the client holds
        // no second-line entity after the rollback).
        assertEquals(SecondLineAction.ADD, SecondLineDiff.actionOf(sent, "A"))
        sent = "A" // retry delivered including the mount
        assertEquals(SecondLineAction.NONE, SecondLineDiff.actionOf(sent, "A"))
    }

    @Test
    fun `second line remove whose remount failed keeps entity and cache for a retry`() {
        // The REMOVE branch aborts before the entity removal when its
        // unmount remount is not delivered; the routing consequence is that
        // the observer still holds the entity, so the next publish must
        // still see REMOVE (not NONE).
        var sent: String? = "A"
        assertEquals(SecondLineAction.REMOVE, SecondLineDiff.actionOf(sent, null))
        sent = "A" // remount failed -> cache untouched, entity kept
        assertEquals(SecondLineAction.REMOVE, SecondLineDiff.actionOf(sent, null))
        sent = null // retry delivered
        assertEquals(SecondLineAction.NONE, SecondLineDiff.actionOf<String?>(sent, null))
    }
}
