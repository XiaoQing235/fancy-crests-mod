package dev.fancycrests.core.storage

import dev.fancycrests.api.PlayerTitleState
import dev.fancycrests.core.TestFixtures
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Lifecycle guarantees of the write path: once the store stopped, later
 * enqueue* calls must be rejected through the same unified unavailable path
 * as a store that never opened.
 */
class SqliteStorageLifecycleTest {

    private val storage = SqliteStorage(null, TestFixtures.directExecutor)

    @AfterTest
    fun tearDown() {
        storage.stop()
    }

    @Test
    fun `enqueue after stop is rejected through the unified path`() {
        storage.start()
        storage.stop()
        val future = CompletableFuture<StateSaveResult>()
        storage.enqueueSaveState(PlayerTitleState(UUID.randomUUID(), emptyList(), emptyList(), null, 0), 0L) {
            future.complete(it)
        }
        val result = future.get(5, TimeUnit.SECONDS)
        assertTrue(result is StateSaveResult.Error, "expected Error after stop, got $result")
        assertTrue(
            result.exception.message?.contains("unavailable") == true,
            "rejection should name the unavailability, got ${result.exception.message}",
        )
    }
}
