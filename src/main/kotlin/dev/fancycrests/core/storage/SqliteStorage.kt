package dev.fancycrests.core.storage

import com.google.gson.Gson
import com.google.gson.JsonParser
import dev.fancycrests.api.PlayerTitleState
import dev.fancycrests.api.TitleId
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** A stored ownership grant: (uuid, titleId, source) is unique. */
data class OwnershipRecord(
    val uuid: UUID,
    val titleId: TitleId,
    /** Origin of the grant, e.g. "local" for admin command grants. */
    val source: String,
    /** Epoch millis at grant time. */
    val grantedAt: Long,
)

/** Result of a player_state save with optimistic revision check. */
sealed interface StateSaveResult {
    /** Committed; the row now carries [newRevision]. */
    data class Saved(val newRevision: Long) : StateSaveResult

    /**
     * The row's revision was not the expected one (concurrent writer won).
     * Nothing was written; in-memory state must stay on the old revision.
     */
    data object RevisionConflict : StateSaveResult

    data class Error(val exception: Exception) : StateSaveResult
}

/** Result of an ownership write. */
sealed interface OwnershipWriteResult {
    data class Written(val rowsChanged: Int) : OwnershipWriteResult

    data class Error(val exception: Exception) : OwnershipWriteResult
}

/** Raised when the database schema version is not supported. No data is modified. */
class StorageSchemaException(message: String) : Exception(message)

/**
 * SQLite persistence for player state and ownership.
 *
 * - Database file: `config/fancycrests-data.db` (or :memory: for tests).
 * - `PRAGMA journal_mode=WAL`.
 * - Single background write thread; every statement is its own transaction.
 * - player_state saves use optimistic concurrency: the UPDATE only applies
 *   when `revision` still equals the expected value; 0 affected rows is
 *   reported as [StateSaveResult.RevisionConflict] and nothing is written.
 * - Schema version lives in `PRAGMA user_version`. An unsupported version
 *   refuses to load (data is never rewritten).
 * - Read path: one full load at start into immutable in-memory state; after
 *   that only the write queue touches the connection.
 */
class SqliteStorage(
    private val databasePath: Path?,
    private val mainExecutor: Executor,
) {
    companion object {
        const val SCHEMA_VERSION = 1
        private const val MEMORY_URL = "jdbc:sqlite::memory:"
        private const val UNAVAILABLE_PREFIX = "persistence is unavailable: "
        private const val UNAVAILABLE_FALLBACK = "storage was not started"
    }

    private val gson = Gson()
    private val writeExecutor: ExecutorService =
        Executors.newSingleThreadExecutor { thread -> Thread(thread, "fancycrests-storage").apply { isDaemon = true } }

    /** Confined to the write thread after [start] completes. */
    private lateinit var connection: Connection

    @Volatile
    private var stopped = false

    /**
     * True only after [start] verified the schema and loaded the data. Every
     * write path checks it: a store that failed to open must never be used,
     * even when the file happens to keep a compatible table layout.
     */
    @Volatile
    var usable: Boolean = false
        private set

    /** Why [usable] is false (diagnostics and write rejections). */
    @Volatile
    var unavailableReason: String? = null
        private set

    // ----------------------------------------------------------------------
    // Lifecycle
    // ----------------------------------------------------------------------

    /**
     * Opens the database, verifies/creates the schema and loads everything.
     * Must be called once before any enqueue*. The connection is handed over
     * to the write thread afterwards.
     */
    fun start(): LoadedData {
        val url = databasePath?.let { "jdbc:sqlite:$it" } ?: MEMORY_URL
        databasePath?.let { path -> path.toAbsolutePath().parent?.let { java.nio.file.Files.createDirectories(it) } }
        return try {
            connection = DriverManager.getConnection(url)
            connection.createStatement().use { statement ->
                statement.execute("PRAGMA journal_mode=WAL")
                statement.execute("PRAGMA foreign_keys=ON")
            }
            verifyOrCreateSchema()
            val data = loadAll()
            usable = true
            unavailableReason = null
            data
        } catch (e: Exception) {
            // The connection may already be open when verification fails:
            // close it right here instead of leaking it until stop().
            if (this::connection.isInitialized) {
                try {
                    connection.close()
                } catch (_: Exception) {
                }
            }
            usable = false
            unavailableReason = e.message ?: e.javaClass.simpleName
            throw e
        }
    }

    /** Closes the write queue and the connection. Queued writes finish first. */
    fun stop() {
        if (stopped) return
        stopped = true
        // Route later enqueue* calls into the unified rejection path.
        usable = false
        writeExecutor.execute {
            try {
                connection.close()
            } catch (_: Exception) {
            }
        }
        writeExecutor.shutdown()
        try {
            writeExecutor.awaitTermination(10, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    data class LoadedData(
        val states: Map<UUID, PlayerTitleState>,
        val ownership: List<OwnershipRecord>,
    )

    private fun verifyOrCreateSchema() {
        connection.createStatement().use { statement ->
            val version = queryUserVersion(statement)
            if (version == 0) {
                // Pre-versioning database: CREATE TABLE IF NOT EXISTS would
                // silently accept any incompatible layout and then stamp it
                // as this schema version; verify the layout of every table
                // that already exists and refuse (without touching any data)
                // on mismatch.
                if (tableExists(statement, "player_state")) {
                    verifyTableLayout(statement, "player_state", EXPECTED_PLAYER_STATE_COLUMNS)
                }
                if (tableExists(statement, "ownership")) {
                    verifyTableLayout(statement, "ownership", EXPECTED_OWNERSHIP_COLUMNS)
                }
                // Then create whatever is missing (a v0 database may hold
                // only one of the two tables — audit #5: skipping creation
                // here left the missing table absent forever while the
                // version was stamped, so loadAll failed with no self-heal).
                statement.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS player_state (
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
                    CREATE TABLE IF NOT EXISTS ownership (
                        uuid TEXT NOT NULL,
                        titleId TEXT NOT NULL,
                        source TEXT NOT NULL,
                        grantedAt INTEGER NOT NULL,
                        PRIMARY KEY (uuid, titleId, source)
                    )
                    """.trimIndent(),
                )
                statement.executeUpdate("PRAGMA user_version = $SCHEMA_VERSION")
            } else if (version != SCHEMA_VERSION) {
                val file = databasePath?.toString() ?: ":memory:"
                throw StorageSchemaException(
                    "unsupported database schema version $version in $file " +
                        "(this build supports $SCHEMA_VERSION); refusing to load or modify the data. " +
                        "Restore a matching database backup or remove the file to start fresh.",
                )
            }
        }
    }

    private fun tableExists(statement: java.sql.Statement, table: String): Boolean =
        statement.executeQuery(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name = '$table'",
        ).use { resultSet -> resultSet.next() }

    /** (column name, declared type, NOT NULL, position in the primary key; 0 = not part). */
    private data class ExpectedColumn(val name: String, val type: String, val notNull: Boolean, val pkPosition: Int)

    private val EXPECTED_PLAYER_STATE_COLUMNS = listOf(
        ExpectedColumn("uuid", "TEXT", notNull = false, pkPosition = 1),
        ExpectedColumn("prefixes", "TEXT", notNull = true, pkPosition = 0),
        ExpectedColumn("suffixes", "TEXT", notNull = true, pkPosition = 0),
        ExpectedColumn("nickname", "TEXT", notNull = false, pkPosition = 0),
        ExpectedColumn("revision", "INTEGER", notNull = true, pkPosition = 0),
    )

    private val EXPECTED_OWNERSHIP_COLUMNS = listOf(
        ExpectedColumn("uuid", "TEXT", notNull = true, pkPosition = 1),
        ExpectedColumn("titleId", "TEXT", notNull = true, pkPosition = 2),
        ExpectedColumn("source", "TEXT", notNull = true, pkPosition = 3),
        ExpectedColumn("grantedAt", "INTEGER", notNull = true, pkPosition = 0),
    )

    private fun verifyTableLayout(statement: java.sql.Statement, table: String, expected: List<ExpectedColumn>) {
        data class ActualColumn(val name: String, val type: String, val notNull: Boolean, val pkPosition: Int)

        val actual = statement.executeQuery("PRAGMA table_info($table)").use { resultSet ->
            val columns = mutableListOf<ActualColumn>()
            while (resultSet.next()) {
                columns += ActualColumn(
                    name = resultSet.getString("name"),
                    type = resultSet.getString("type")?.uppercase() ?: "",
                    notNull = resultSet.getInt("notnull") != 0,
                    pkPosition = resultSet.getInt("pk"),
                )
            }
            columns
        }
        val matches = actual.size == expected.size && actual.zip(expected).all { (found, want) ->
            found.name == want.name && found.type == want.type &&
                found.notNull == want.notNull && found.pkPosition == want.pkPosition
        }
        if (!matches) {
            val found = actual.joinToString(", ") { "${it.name} ${it.type}" }
            val wanted = expected.joinToString(", ") { "${it.name} ${it.type}" }
            val file = databasePath?.toString() ?: ":memory:"
            throw StorageSchemaException(
                "existing table '$table' in $file does not match schema version $SCHEMA_VERSION " +
                    "(found [$found], expected [$wanted]); refusing to load or modify the data. " +
                    "Restore a matching database backup or remove the file to start fresh.",
            )
        }
    }

    private fun queryUserVersion(statement: java.sql.Statement): Int =
        statement.executeQuery("PRAGMA user_version").use { resultSet ->
            if (resultSet.next()) resultSet.getInt(1) else 0
        }

    // ----------------------------------------------------------------------
    // Initial load (called on the starting thread, before writes begin)
    // ----------------------------------------------------------------------

    private fun loadAll(): LoadedData {
        val states = LinkedHashMap<UUID, PlayerTitleState>()
        connection.createStatement().use { statement ->
            statement.executeQuery(
                "SELECT uuid, prefixes, suffixes, nickname, revision FROM player_state",
            ).use { resultSet ->
                while (resultSet.next()) {
                    val uuid = UUID.fromString(resultSet.getString("uuid"))
                    states[uuid] = PlayerTitleState(
                        uuid = uuid,
                        prefixes = decodeIds(resultSet.getString("prefixes")),
                        suffixes = decodeIds(resultSet.getString("suffixes")),
                        nickname = resultSet.getString("nickname"),
                        revision = resultSet.getLong("revision"),
                    )
                }
            }
        }
        val ownership = mutableListOf<OwnershipRecord>()
        connection.createStatement().use { statement ->
            statement.executeQuery(
                "SELECT uuid, titleId, source, grantedAt FROM ownership",
            ).use { resultSet ->
                while (resultSet.next()) {
                    ownership += OwnershipRecord(
                        uuid = UUID.fromString(resultSet.getString("uuid")),
                        titleId = TitleId(resultSet.getString("titleId")),
                        source = resultSet.getString("source"),
                        grantedAt = resultSet.getLong("grantedAt"),
                    )
                }
            }
        }
        return LoadedData(states, ownership)
    }

    private fun decodeIds(json: String): List<TitleId> {
        if (json.isBlank()) return emptyList()
        val array = JsonParser.parseString(json).asJsonArray
        return array.map { TitleId(it.asString) }
    }

    private fun encodeIds(ids: List<TitleId>): String {
        val array = com.google.gson.JsonArray()
        ids.forEach { array.add(it.value) }
        return gson.toJson(array)
    }

    // ----------------------------------------------------------------------
    // Write queue
    // ----------------------------------------------------------------------

    /**
     * Persists the candidate state with optimistic concurrency. [expectedRevision]
     * is the revision the caller read; the row moves to `expectedRevision + 1`
     * only when it still carries [expectedRevision] (or is new and the caller
     * expected 0).
     */
    fun enqueueSaveState(state: PlayerTitleState, expectedRevision: Long, callback: (StateSaveResult) -> Unit) {
        if (!usable) {
            val reason = UNAVAILABLE_PREFIX + (unavailableReason ?: UNAVAILABLE_FALLBACK)
            mainExecutor.execute { callback(StateSaveResult.Error(IllegalStateException(reason))) }
            return
        }
        writeExecutor.execute {
            val result = try {
                val rows = connection.prepareStatement(
                    """
                    INSERT INTO player_state (uuid, prefixes, suffixes, nickname, revision)
                    VALUES (?, ?, ?, ?, ?)
                    ON CONFLICT(uuid) DO UPDATE SET
                        prefixes = excluded.prefixes,
                        suffixes = excluded.suffixes,
                        nickname = excluded.nickname,
                        revision = player_state.revision + 1
                    WHERE player_state.revision = ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, state.uuid.toString())
                    statement.setString(2, encodeIds(state.prefixes))
                    statement.setString(3, encodeIds(state.suffixes))
                    statement.setString(4, state.nickname)
                    // Both the INSERT branch (row deleted out-of-band) and the
                    // UPDATE branch must land on expectedRevision + 1, or the
                    // reported new revision and the stored row diverge and the
                    // next save conflicts forever.
                    statement.setLong(5, expectedRevision + 1)
                    statement.setLong(6, expectedRevision)
                    statement.executeUpdate()
                }
                if (rows == 1) {
                    StateSaveResult.Saved(expectedRevision + 1)
                } else {
                    StateSaveResult.RevisionConflict
                }
            } catch (e: Exception) {
                StateSaveResult.Error(e)
            }
            mainExecutor.execute { callback(result) }
        }
    }

    fun enqueueUpsertOwnership(record: OwnershipRecord, callback: (OwnershipWriteResult) -> Unit) {
        if (!usable) {
            val reason = UNAVAILABLE_PREFIX + (unavailableReason ?: UNAVAILABLE_FALLBACK)
            mainExecutor.execute { callback(OwnershipWriteResult.Error(IllegalStateException(reason))) }
            return
        }
        writeExecutor.execute {
            val result = try {
                val rows = connection.prepareStatement(
                    """
                    INSERT INTO ownership (uuid, titleId, source, grantedAt)
                    VALUES (?, ?, ?, ?)
                    ON CONFLICT(uuid, titleId, source) DO UPDATE SET grantedAt = excluded.grantedAt
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, record.uuid.toString())
                    statement.setString(2, record.titleId.value)
                    statement.setString(3, record.source)
                    statement.setLong(4, record.grantedAt)
                    statement.executeUpdate()
                }
                OwnershipWriteResult.Written(rows)
            } catch (e: Exception) {
                OwnershipWriteResult.Error(e)
            }
            mainExecutor.execute { callback(result) }
        }
    }

    fun enqueueDeleteOwnership(
        uuid: UUID,
        titleId: TitleId,
        source: String,
        callback: (OwnershipWriteResult) -> Unit,
    ) {
        if (!usable) {
            val reason = UNAVAILABLE_PREFIX + (unavailableReason ?: UNAVAILABLE_FALLBACK)
            mainExecutor.execute { callback(OwnershipWriteResult.Error(IllegalStateException(reason))) }
            return
        }
        writeExecutor.execute {
            val result = try {
                val rows = connection.prepareStatement(
                    "DELETE FROM ownership WHERE uuid = ? AND titleId = ? AND source = ?",
                ).use { statement ->
                    statement.setString(1, uuid.toString())
                    statement.setString(2, titleId.value)
                    statement.setString(3, source)
                    statement.executeUpdate()
                }
                OwnershipWriteResult.Written(rows)
            } catch (e: Exception) {
                OwnershipWriteResult.Error(e)
            }
            mainExecutor.execute { callback(result) }
        }
    }

    // ----------------------------------------------------------------------
    // Internals
    // ----------------------------------------------------------------------
}
