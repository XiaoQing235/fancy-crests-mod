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
import kotlin.test.assertTrue

/**
 * Audit #5 regression: a version-0 database that held only *one* of the two
 * tables passed the layout check, skipped table creation entirely (the old
 * code only created tables when *neither* existed) and still stamped
 * user_version=1 — leaving the missing table absent forever with loadAll
 * failing on every start and no self-heal. Creation must run for whatever
 * is missing after the existing layouts are verified.
 */
class SqliteStoragePartialSchemaTest {

    @Test
    fun `a v0 database with only player_state gets ownership backfilled and becomes writable`() {
        val path = Files.createTempFile("fancycrests-partial-", ".db")
        val uuid = UUID.randomUUID()
        // Pre-versioning database with only the player_state table present.
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
            }
        }

        val storage = SqliteStorage(path, TestFixtures.directExecutor)
        try {
            // Before the fix this threw "no such table: ownership" and left
            // the file stamped as version 1 — permanently unusable.
            val loaded = storage.start()
            assertTrue(storage.usable, "start must succeed on a partially present v0 schema")
            assertEquals(7, loaded.states[uuid]?.revision, "existing data must survive the heal")

            // The missing table must exist now and accept writes.
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
                        assertEquals(1, rows.getInt(1), "healed database must be stamped as schema version 1")
                    }
                    statement.executeQuery("SELECT COUNT(*) FROM ownership").use { rows ->
                        rows.next()
                        assertEquals(1, rows.getInt(1), "the grant must be persisted in the backfilled table")
                    }
                }
            }
        } finally {
            storage.stop()
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun `a v0 database with only ownership gets player_state backfilled`() {
        val path = Files.createTempFile("fancycrests-partial2-", ".db")
        val uuid = UUID.randomUUID()
        DriverManager.getConnection("jdbc:sqlite:" + path).use { connection ->
            connection.createStatement().use { statement ->
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
                statement.executeUpdate("INSERT INTO ownership VALUES ('$uuid', 'vip', 'local', 42)")
            }
        }

        val storage = SqliteStorage(path, TestFixtures.directExecutor)
        try {
            val loaded = storage.start()
            assertTrue(storage.usable)
            assertEquals(TitleId("vip"), loaded.ownership.single().titleId, "existing ownership must survive")

            val saved = CompletableFuture<StateSaveResult>()
            storage.enqueueSaveState(
                PlayerTitleState(uuid, emptyList(), emptyList(), null, 0),
                expectedRevision = 0,
                callback = saved::complete,
            )
            assertTrue(saved.get(5, TimeUnit.SECONDS) is StateSaveResult.Saved)
        } finally {
            storage.stop()
            Files.deleteIfExists(path)
        }
    }
}
