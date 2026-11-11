package dev.fancycrests.render

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * L4: the head render channel exposes a read-only, all-string diagnostics
 * snapshot for the integration layer's provider (the api
 * [dev.fancycrests.api.HeadDisplayChannel] contract stays untouched).
 */
class HeadRenderChannelSnapshotTest {

    @AfterTest
    fun resetCounters() {
        RenderDiagnostics.targets.set(0)
        RenderDiagnostics.entitiesSent.set(0)
    }

    @Test
    fun `a never-started channel reports zeroed counters and no error`() {
        val snapshot = HeadRenderChannel().snapshot()
        assertEquals("false", snapshot["running"])
        assertEquals("0", snapshot["connections"])
        assertEquals("0", snapshot["targets"])
        assertEquals("0", snapshot["virtualEntities"])
        assertEquals("0", snapshot["trackingRelations"])
        assertEquals("0", snapshot["virtualTeams"])
        assertEquals("0", snapshot["entitiesSent"])
        assertEquals("0", snapshot["bundlesProcessed"])
        assertEquals("0", snapshot["teamPacketsRewritten"])
        assertEquals("0", snapshot["teamPacketsDropped"])
        assertEquals("0", snapshot["passengerPacketsRewritten"])
        // lastError is process-global; only require the key to exist.
        assert(snapshot.containsKey("lastError"))
    }

    @Test
    fun `counters are stringified and reflect the diagnostics state`() {
        RenderDiagnostics.targets.incrementAndGet()
        RenderDiagnostics.entitiesSent.addAndGet(3)
        try {
            val snapshot = HeadRenderChannel().snapshot()
            assertEquals("1", snapshot["targets"])
            assertEquals("3", snapshot["entitiesSent"])
        } finally {
            RenderDiagnostics.targets.decrementAndGet()
            RenderDiagnostics.entitiesSent.addAndGet(-3)
        }
    }
}
