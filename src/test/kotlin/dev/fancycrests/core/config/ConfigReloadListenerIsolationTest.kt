package dev.fancycrests.core.config

import dev.fancycrests.core.TestFixtures
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Hot-reload robustness: one replace listener throwing must not skip the
 * remaining listeners nor swallow the reload callback.
 */
class ConfigReloadListenerIsolationTest {

    private val configFile: Path = Files.createTempFile("fancycrests-reload-", ".json")
    private val service = ConfigService(configFile, TestFixtures.directExecutor)

    init {
        // Smallest valid config: a name-only first-line template.
        Files.writeString(configFile, """{"firstLineTemplate": [{"type": "name"}]}""")
    }

    @AfterTest
    fun tearDown() {
        service.stop()
        Files.deleteIfExists(configFile)
    }

    private fun awaitReload(): ConfigLoadResult {
        val future = CompletableFuture<ConfigLoadResult>()
        service.reloadAsync { result -> future.complete(result) }
        return future.get(5, TimeUnit.SECONDS)
    }

    @Test
    fun `a throwing replace listener does not block the others or the callback`() {
        var secondListenerRan = false
        service.addOnReplaced { _ -> throw IllegalStateException("listener boom") }
        service.addOnReplaced { _ -> secondListenerRan = true }

        val versionBefore = service.version
        val result = awaitReload()

        assertTrue(result is ConfigLoadResult.Success, "reload itself must succeed, got $result")
        assertTrue(secondListenerRan, "the second listener must still run after the first one threw")
        assertEquals(versionBefore + 1, service.version, "the config must still be replaced")
    }

    @Test
    fun `the reload callback fires even for a broken file`() {
        Files.writeString(configFile, "{ not json")
        val result = awaitReload()
        assertTrue(result is ConfigLoadResult.Failure, "broken JSON must report failure, got $result")
    }
}
