package dev.fancycrests.core.storage

import dev.fancycrests.core.TestFixtures
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.io.TempDir

class NametagPreferenceTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `self visibility survives restart without changing title data`() {
        val path = directory.resolve("test.db")
        val uuid = UUID.randomUUID()
        val first = SqliteStorage(path, TestFixtures.directExecutor)
        first.start()
        val saved = CompletableFuture<Exception?>()
        first.enqueueSavePreference(uuid, false) { saved.complete(it) }
        assertNull(saved.get(10, TimeUnit.SECONDS))
        first.stop()
        val second = SqliteStorage(path, TestFixtures.directExecutor)
        try {
            val loaded = second.start()
            assertEquals(false, loaded.showOwnNametag[uuid])
            assertEquals(emptyMap(), loaded.states)
        } finally { second.stop() }
    }
}
