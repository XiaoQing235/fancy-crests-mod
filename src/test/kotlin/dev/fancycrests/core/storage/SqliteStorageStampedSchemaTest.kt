package dev.fancycrests.core.storage

import dev.fancycrests.api.PlayerTitleState
import dev.fancycrests.api.TitleId
import dev.fancycrests.core.TestFixtures
import java.nio.file.Files
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Audit #3 regression: an older build's bug could stamp user_version=1 on a
 * database whose tables were never fully created (its missing-table
 * recovery only ran at version 0). A version-1 database with a missing
 * table must self-heal — the missing table holds no data, so it is
 * recreated with CREATE TABLE IF NOT EXISTS — while tables that do exist
 * keep the layout check and a mismatch still refuses to load.
 */
class SqliteStorageStampedSchemaTest {

    @Test
    fun `a v1 database with only player_state gets ownership recreated and becomes writable`() {
        val path = Files.createTempFile("fancycrests-stamped-partial-", ".db")
        val uuid = UUID.randomUUID()
        // Old-bug damage: the version was stamped as 1 while the ownership
        // table was never created. Before the fix every start failed with
        // "no such table: ownership" and nothing repaired the file.
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
                    "INSERT INTO player_state VALUES ('$uuid', '[\"vip\"]', '[]', NULL, 7)",
                )
                statement.executeUpdate("PRAGMA user_version = 1")
            }
        }

        val storage = SqliteStorage(path, TestFixtures.directExecutor)
        try {
            val loaded = storage.start()
            assertTrue(storage.usable, "a v1 database missing one table must self-heal, not fail")
            assertEquals(7, loaded.states[uuid]?.revision, "existing data must survive the heal")
            assertTrue(loaded.ownership.isEmpty(), "the recreated table starts empty")

            // Both tables accept writes afterwards.
            val grant = OwnershipRecord(uuid, TitleId("vip"), "local", 42)
            val written = CompletableFuture<OwnershipWriteResult>()
            storage.enqueueUpsertOwnership(grant) { written.complete(it) }
            assertTrue(written.get(5, TimeUnit.SECONDS) is OwnershipWriteResult.Written)

            val saved = CompletableFuture<StateSaveResult>()
            storage.enqueueSaveState(
                PlayerTitleState(uuid, listOf(TitleId("vip")), emptyList(), null, 7),
                expectedRevision = 7,
                callback = saved::complete,
            )
            assertTrue(saved.get(5, TimeUnit.SECONDS) is StateSaveResult.Saved)

            DriverManager.getConnection("jdbc:sqlite:" + path).use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("PRAGMA user_version").use { rows ->
                        rows.next()
                        assertEquals(1, rows.getInt(1), "the version stamp must stay at 1")
                    }
                    statement.executeQuery("SELECT COUNT(*) FROM ownership").use { rows ->
                        rows.next()
                        assertEquals(1, rows.getInt(1), "the grant must be persisted in the recreated table")
                    }
                }
            }
        } finally {
            storage.stop()
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun `a complete v1 database is left untouched`() {
        val path = Files.createTempFile("fancycrests-stamped-full-", ".db")
        val uuid = UUID.randomUUID()
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
                    "INSERT INTO player_state VALUES ('$uuid', '[]', '[\"helper\"]', 'Nick', 3)",
                )
                statement.executeUpdate("INSERT INTO ownership VALUES ('$uuid', 'vip', 'local', 42)")
                statement.executeUpdate("PRAGMA user_version = 1")
            }
        }

        val schemaBefore = schemaSnapshot(path)
        val storage = SqliteStorage(path, TestFixtures.directExecutor)
        try {
            val loaded = storage.start()
            assertTrue(storage.usable, "a complete v1 database must load as-is")
            assertEquals("Nick", loaded.states[uuid]?.nickname, "state data must survive untouched")
            assertEquals(TitleId("vip"), loaded.ownership.single().titleId, "ownership data must survive untouched")
            assertEquals(schemaBefore, schemaSnapshot(path), "the schema (DDL) must not be rewritten")
        } finally {
            storage.stop()
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun `a v1 database with a mismatched existing table is still refused`() {
        val path = Files.createTempFile("fancycrests-stamped-bad-", ".db")
        DriverManager.getConnection("jdbc:sqlite:" + path).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate("CREATE TABLE player_state (uuid TEXT)")
                statement.executeUpdate("INSERT INTO player_state (uuid) VALUES ('keep-me')")
                statement.executeUpdate("PRAGMA user_version = 1")
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
            assertFalse(storage.usable, "storage must report itself unusable")

            DriverManager.getConnection("jdbc:sqlite:" + path).use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("PRAGMA user_version").use { rows ->
                        rows.next()
                        assertEquals(1, rows.getInt(1), "the version must stay untouched")
                    }
                    statement.executeQuery("SELECT uuid FROM player_state").use { rows ->
                        rows.next()
                        assertEquals("keep-me", rows.getString(1), "no data may be modified on refusal")
                    }
                    assertFalse(
                        tableExists(statement, "ownership"),
                        "the missing table must not be created when an existing table is refused",
                    )
                }
            }
        } finally {
            storage.stop()
            Files.deleteIfExists(path)
        }
    }

    /** name → DDL of both expected tables, to detect any schema rewrite. */
    private fun schemaSnapshot(path: java.nio.file.Path): Map<String, String> =
        DriverManager.getConnection("jdbc:sqlite:" + path).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT name, sql FROM sqlite_master WHERE type = 'table' AND name IN ('player_state', 'ownership')",
                ).use { rows ->
                    val snapshot = mutableMapOf<String, String>()
                    while (rows.next()) {
                        snapshot[rows.getString("name")] = rows.getString("sql")
                    }
                    snapshot
                }
            }
        }

    private fun tableExists(statement: java.sql.Statement, table: String): Boolean =
        statement.executeQuery(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name = '$table'",
        ).use { rows -> rows.next() }
}
