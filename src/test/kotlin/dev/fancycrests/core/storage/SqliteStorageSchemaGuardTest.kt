package dev.fancycrests.core.storage

import dev.fancycrests.api.TitleId
import dev.fancycrests.core.TestFixtures
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * user_version=0 with pre-existing tables must not be silently accepted:
 * an incompatible layout is refused (StorageSchemaException, data untouched)
 * instead of being stamped as this schema version.
 */
class SqliteStorageSchemaGuardTest {

    @Test
    fun `an incompatible existing table at version 0 is refused and left untouched`() {
        val path = Files.createTempFile("fancycrests-layout-", ".db")
        // A player_state table with a completely different layout, unstamped
        // (user_version stays 0). The naive path would accept it via
        // CREATE TABLE IF NOT EXISTS and stamp version 1 on top.
        DriverManager.getConnection("jdbc:sqlite:" + path).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate("CREATE TABLE player_state (uuid TEXT)")
                statement.executeUpdate("INSERT INTO player_state (uuid) VALUES ('keep-me')")
            }
        }

        val storage = SqliteStorage(path, TestFixtures.directExecutor)
        try {
            var thrown: Exception? = null
            try {
                storage.start()
            } catch (e: Exception) {
                thrown = e
            }
            assertTrue(thrown is StorageSchemaException, "expected StorageSchemaException, got $thrown")
            assertTrue(!storage.usable, "storage must report itself unusable")

            DriverManager.getConnection("jdbc:sqlite:" + path).use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("PRAGMA user_version").use { rows ->
                        rows.next()
                        assertEquals(0, rows.getInt(1), "version must not be stamped on refusal")
                    }
                    statement.executeQuery("SELECT uuid FROM player_state").use { rows ->
                        rows.next()
                        assertEquals("keep-me", rows.getString(1), "no data may be modified on refusal")
                    }
                }
            }
        } finally {
            storage.stop()
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun `a matching existing layout at version 0 is accepted and stamped`() {
        val path = Files.createTempFile("fancycrests-legacy-", ".db")
        val uuid = UUID.randomUUID()
        // Exactly the v1 layout, just without the version stamp (a database
        // written before user_version bookkeeping existed).
        DriverManager.getConnection("jdbc:sqlite:" + path).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    """
                    CREATE TABLE player_state (
                        uuid TEXT PRIMARY KEY,
                        prefixes TEXT NOT NULL,
                        suffixes TEXT NOT NULL,
                        nickname TEXT NULL,
                        revision INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    """
                    CREATE TABLE ownership (
                        uuid TEXT NOT NULL,
                        titleId TEXT NOT NULL,
                        source TEXT NOT NULL,
                        grantedAt INTEGER NOT NULL,
                        PRIMARY KEY (uuid, titleId, source)
                    )
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    "INSERT INTO player_state VALUES ('$uuid', '[\"vip\"]', '[]', NULL, 7)",
                )
                statement.executeUpdate("INSERT INTO ownership VALUES ('$uuid', 'vip', 'local', 42)")
            }
        }

        val storage = SqliteStorage(path, TestFixtures.directExecutor)
        try {
            val loaded = storage.start()
            assertTrue(storage.usable, "a matching layout must be accepted")
            assertEquals(7, loaded.states[uuid]?.revision)
            assertEquals(TitleId("vip"), loaded.ownership.single().titleId)

            DriverManager.getConnection("jdbc:sqlite:" + path).use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("PRAGMA user_version").use { rows ->
                        rows.next()
                        assertEquals(1, rows.getInt(1), "accepted layout must be stamped")
                    }
                }
            }
        } finally {
            storage.stop()
            Files.deleteIfExists(path)
        }
    }
}
