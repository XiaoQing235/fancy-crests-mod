package dev.fancycrests.core.storage

import dev.fancycrests.api.PlayerTitleState
import dev.fancycrests.api.TitleId
import dev.fancycrests.core.TestFixtures
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Optimistic-revision semantics of the save path, including the INSERT
 * branch: a row deleted out-of-band is recreated with expectedRevision + 1,
 * not with a hardcoded 1.
 */
class SqliteStorageRevisionTest {

    private val storage = SqliteStorage(null, TestFixtures.directExecutor)

    @AfterTest
    fun tearDown() {
        storage.stop()
    }

    private fun save(state: PlayerTitleState, expected: Long): StateSaveResult {
        val future = CompletableFuture<StateSaveResult>()
        storage.enqueueSaveState(state, expected) { future.complete(it) }
        return future.get(5, TimeUnit.SECONDS)
    }

    @Test
    fun `a new row with a positive expected revision lands on expected plus one`() {
        storage.start()
        val uuid = UUID.randomUUID()
        // Simulate the row having been removed out-of-band while the server
        // already tracked revision 5: the INSERT branch runs.
        val state = PlayerTitleState(uuid, listOf(TitleId("vip")), emptyList(), null, 5)
        assertEquals(StateSaveResult.Saved(6), save(state, 5))

        // The stored row must now carry revision 6; otherwise every future
        // save with the reported revision would conflict forever.
        assertEquals(StateSaveResult.Saved(7), save(state.copy(revision = 6), 6))
    }
}
