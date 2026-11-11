package dev.fancycrests.pubapi

import dev.fancycrests.api.PlayerTitleState
import dev.fancycrests.api.TitleId
import dev.fancycrests.core.CoreServices
import dev.fancycrests.core.TestFixtures
import dev.fancycrests.core.event.OperationId
import dev.fancycrests.core.event.TitleChangeEventPost
import dev.fancycrests.core.event.TitleChangeEventPre
import dev.fancycrests.core.event.TitleOperationType
import java.nio.file.Files
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The public API forwarders must isolate listener failures the same way the
 * core bus does: one throwing subscriber may neither starve the remaining
 * subscribers nor leak its exception into the core bus. Verified by firing
 * events through the real [dev.fancycrests.core.event.TitleEventBus] of a
 * started [CoreServices] instance, exactly as the selection pipeline does.
 */
class PublicApiForwarderIsolationTest {

    private val adapter = TestFixtures.FakeAdapter()
    private val configDir = Files.createTempDirectory("fancycrests-pubapi-forwarder-test")
    private val core = CoreServices(configDir, "test") { null }

    init {
        core.start(adapter)
    }

    @AfterTest
    fun tearDown() {
        core.stop()
    }

    @Test
    fun `a throwing post listener does not starve the remaining ones`() {
        val api = FancyCrestsPublicApi(core)
        try {
            val target = UUID.randomUUID()
            val received = mutableListOf<TitleChangePostEvent>()
            api.onPostChange { throw IllegalStateException("first subscriber boom") }
            api.onPostChange { event -> received += event }

            core.eventBus.firePost(
                TitleChangeEventPost(
                    uuid = target,
                    operationId = OperationId("op-test"),
                    type = TitleOperationType.EQUIP,
                    oldRevision = 1,
                    newRevision = 2,
                    reason = "test",
                ),
            )

            assertEquals(1, received.size, "second subscriber must still receive the event")
            assertEquals(target, received[0].target)
            assertEquals(2, received[0].newRevision)
        } finally {
            api.close()
        }
    }

    @Test
    fun `a throwing pre listener cancels the operation but remaining listeners still run`() {
        val api = FancyCrestsPublicApi(core)
        try {
            val target = UUID.randomUUID()
            val current = PlayerTitleState(target, emptyList(), emptyList(), null, revision = 1)
            val candidate = current.copy(prefixes = listOf(TitleId("vip")))
            var secondRan = false
            var cancelledSeenBySecond: Boolean? = null

            api.onPreChange { throw IllegalStateException("first subscriber boom") }
            api.onPreChange { event ->
                secondRan = true
                cancelledSeenBySecond = event.isCancelled
            }

            val cancelled = core.eventBus.firePre(
                TitleChangeEventPre(
                    uuid = target,
                    operationId = OperationId("op-test"),
                    type = TitleOperationType.EQUIP,
                    current = current,
                    candidate = candidate,
                    reason = "test",
                ),
            )

            assertTrue(cancelled, "a throwing pre listener must still abort the operation (core semantics)")
            assertTrue(secondRan, "second subscriber must still be notified")
            assertEquals(true, cancelledSeenBySecond, "the cancel must be visible to later listeners")
        } finally {
            api.close()
        }
    }
}
