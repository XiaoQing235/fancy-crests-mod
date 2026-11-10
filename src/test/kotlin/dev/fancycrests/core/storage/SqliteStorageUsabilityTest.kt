package dev.fancycrests.core.storage

import dev.fancycrests.api.TitleId
import dev.fancycrests.core.TestFixtures
import java.nio.file.Files
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A store that failed to open must never be written to, even when the file
 * still carries a table layout this build understands.
 */
class SqliteStorageUsabilityTest {

    @Test
    fun `an unusable store rejects writes and leaves the file untouched`() {
        val path = Files.createTempFile("fancycrests-schema-", ".db")
        // Unsupported schema version, but with a compatible ownership table:
        // exactly the case where a naive write path would succeed silently.
        DriverManager.getConnection("jdbc:sqlite:" + path).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    "CREATE TABLE ownership (uuid TEXT NOT NULL, titleId TEXT NOT NULL, " +
                        "source TEXT NOT NULL, grantedAt INTEGER NOT NULL, " +
                        "PRIMARY KEY (uuid, titleId, source))",
                )
                statement.execute("PRAGMA user_version = 99")
            }
        }

        val storage = SqliteStorage(path, TestFixtures.directExecutor)
        var thrown: Exception? = null
        try {
            storage.start()
        } catch (e: Exception) {
            thrown = e
        }
        assertTrue(thrown is StorageSchemaException, "expected StorageSchemaException, got " + thrown)
        assertFalse(storage.usable, "storage must report itself unusable")
        assertTrue(storage.unavailableReason != null, "failure reason must be recorded")

        val latch = CountDownLatch(1)
        var result: OwnershipWriteResult? = null
        storage.enqueueUpsertOwnership(
            OwnershipRecord(UUID.randomUUID(), TitleId("vip"), "local", 1L),
        ) { written ->
            result = written
            latch.countDown()
        }
        assertTrue(latch.await(5, TimeUnit.SECONDS), "write callback never fired")
        assertTrue(result is OwnershipWriteResult.Error, "expected Error, got " + result)

        DriverManager.getConnection("jdbc:sqlite:" + path).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM ownership").use { rows ->
                    rows.next()
                    assertEquals(0, rows.getInt(1), "rejected write must not reach the file")
                }
            }
        }

        storage.stop()
        Files.deleteIfExists(path)
    }
}