package dev.fancycrests.core.config

import dev.fancycrests.api.TitleId
import dev.fancycrests.api.TitleSlot
import dev.fancycrests.core.TestFixtures
import dev.fancycrests.core.catalog.CatalogService
import dev.fancycrests.core.event.TitleEventBus
import dev.fancycrests.core.permission.BuiltInBackend
import dev.fancycrests.core.permission.PermissionManager
import dev.fancycrests.core.refresh.PlaceholderServiceImpl
import dev.fancycrests.core.refresh.TemplateEngine
import dev.fancycrests.core.resolve.SnapshotPublisher
import dev.fancycrests.core.resolve.SnapshotResolver
import dev.fancycrests.core.selection.RejectionReason
import dev.fancycrests.core.selection.SelectionResult
import dev.fancycrests.core.selection.SelectionService
import dev.fancycrests.core.selection.UnequipTarget
import dev.fancycrests.core.storage.SqliteStorage
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Batch J1: startup failure protection. A corrupt configuration must never
 * translate into "run with empty defaults" — that path unequips every
 * player's persisted equipment on login (permanent data loss). The matrix:
 *
 *  (1) corrupt config + usable last-good  -> run on last-good, data intact
 *  (2) corrupt config + no last-good      -> PROTECTED, no unequip/write,
 *                                           data fully recovered once fixed
 *  (3) corrupt config + corrupt last-good -> PROTECTED
 *  (4) valid config with `titles: []`     -> NOT protection (intentional
 *                                           empty catalog still unequips)
 *  (5) PROTECTED                          -> equip/unequip/nickname/grant
 *                                           rejected with CONFIG_PROTECTED
 *  (6) last-good write failure            -> existing copy untouched
 *
 * Each "run" is a real ConfigService + real SqliteStorage over a real temp
 * directory, started and stopped like a server lifecycle.
 */
class StartupProtectionTest {

    private val uuid: UUID = UUID.randomUUID()
    private val dirs = mutableListOf<Path>()

    @AfterTest
    fun tearDown() {
        for (dir in dirs) {
            deleteRecursively(dir)
        }
    }

    private fun newDir(): Path = Files.createTempDirectory("fancycrests-protect-").also { dirs.add(it) }

    /** One simulated server lifetime over the same config dir + database. */
    private inner class ServerRun(val dir: Path) {
        val configFile: Path = dir.resolve("fancycrests.json")
        val lastGoodFile: Path = configFile.resolveSibling("fancycrests.json.last-good")
        val configService = ConfigService(configFile, TestFixtures.directExecutor)
        val adapter = TestFixtures.FakeAdapter().apply {
            online = listOf(uuid)
            knownNames = mapOf("CatNine" to uuid)
        }
        lateinit var storage: SqliteStorage
        lateinit var selection: SelectionService

        fun boot(): ConfigLoadResult {
            val load = configService.loadOrCreate()
            storage = SqliteStorage(dir.resolve("fancycrests-data.db"), TestFixtures.directExecutor)
            val loaded = storage.start()
            val catalog = CatalogService(configService.current.toTitleDefinitions())
            val builtIn = BuiltInBackend().apply { reload(configService.current.permissions.nodes) }
            val permissions = PermissionManager(adapter, listOf(builtIn))
            val resolver = SnapshotResolver(
                catalog,
                permissions,
                { configService.current },
                TemplateEngine(PlaceholderServiceImpl()),
            )
            val publisher = SnapshotPublisher(resolver, adapter, { configService.current }, { null })
            selection = SelectionService(
                adapter = adapter,
                storage = storage,
                catalog = catalog,
                permissions = permissions,
                configProvider = { configService.current },
                configHealthy = { !configService.protectionActive },
                eventBus = TitleEventBus(TestFixtures.directExecutor),
                publisher = publisher,
                initialStates = loaded.states,
                initialOwnership = loaded.ownership,
            )
            return load
        }

        fun shutdown() {
            storage.stop()
            configService.stop()
        }
    }

    private fun validConfig(titles: Boolean = true): String =
        if (titles) {
            """
            {
              "channels": {"headRender": false},
              "firstLineTemplate": [
                {"type": "prefixes"},
                {"type": "name", "separator": ""},
                {"type": "suffixes"}
              ],
              "titles": [
                {"id": "vip", "slot": "prefix", "displayName": "VIP", "text": "[VIP]"},
                {"id": "pro", "slot": "suffix", "displayName": "Pro", "text": "[Pro]"}
              ]
            }
            """.trimIndent()
        } else {
            """
            {
              "channels": {"headRender": false},
              "firstLineTemplate": [
                {"type": "prefixes"},
                {"type": "name", "separator": ""},
                {"type": "suffixes"}
              ],
              "titles": []
            }
            """.trimIndent()
        }

    private fun <T> await(start: ((T) -> Unit) -> Unit): T {
        val future = CompletableFuture<T>()
        start { future.complete(it) }
        return future.get(10, TimeUnit.SECONDS)
    }

    private fun equip(run: ServerRun, id: String): SelectionResult =
        await { cb -> run.selection.equip(uuid, TitleId(id), "test", callback = cb) }

    private fun revalidate(run: ServerRun): SelectionResult =
        await { cb -> run.selection.revalidatePlayer(uuid, "login", cb) }

    // ------------------------------------------------------------------
    // (1) corrupt config + last-good present
    // ------------------------------------------------------------------

    @Test
    fun `corrupt config falls back to last-good and keeps equipment`() {
        val dir = newDir()
        val run1 = ServerRun(dir)
        Files.writeString(run1.configFile, validConfig())
        val load1 = run1.boot()
        assertTrue(load1 is ConfigLoadResult.Success, "first boot must succeed, got $load1")
        assertEquals(LoadState.OK, run1.configService.loadState)
        assertTrue(equip(run1, "vip") is SelectionResult.Success, "equip on a healthy config must work")
        assertEquals(listOf(TitleId("vip")), run1.selection.stateOf(uuid).prefixes)
        run1.shutdown()
        assertTrue(Files.isRegularFile(run1.lastGoodFile), "last-good copy must exist after a good load")

        Files.writeString(run1.configFile, "{ this is not json")
        val run2 = ServerRun(dir)
        val load2 = run2.boot()
        assertTrue(load2 is ConfigLoadResult.Success, "fallback load must succeed, got $load2")
        assertEquals(LoadState.FALLBACK_LAST_GOOD, run2.configService.loadState)
        assertFalse(run2.configService.protectionActive)
        assertEquals(2, run2.configService.current.titles.size, "last-good catalog is active")

        val result = revalidate(run2)
        assertTrue(result is SelectionResult.Success, "revalidate on last-good must run, got $result")
        assertEquals("recheck", (result as SelectionResult.Success).operationId)
        assertEquals(listOf(TitleId("vip")), run2.selection.stateOf(uuid).prefixes, "equipment untouched")
        run2.shutdown()
    }

    // ------------------------------------------------------------------
    // (2) corrupt config + no last-good -> PROTECTED, data survives
    // ------------------------------------------------------------------

    @Test
    fun `no last-good enters protection and never unequips persisted equipment`() {
        val dir = newDir()
        val run1 = ServerRun(dir)
        Files.writeString(run1.configFile, validConfig())
        run1.boot()
        equip(run1, "vip")
        equip(run1, "pro")
        val revisionAfterEquip = run1.selection.stateOf(uuid).revision
        run1.shutdown()
        // Simulate "no last-good ever written" (e.g. a server predating the feature).
        Files.deleteIfExists(run1.lastGoodFile)

        Files.writeString(run1.configFile, "{ broken")
        val run2 = ServerRun(dir)
        val load2 = run2.boot()
        assertTrue(load2 is ConfigLoadResult.Failure, "corrupt config must fail, got $load2")
        assertEquals(LoadState.PROTECTED, run2.configService.loadState)
        assertTrue(run2.configService.protectionActive)

        val result = revalidate(run2)
        val success = result as SelectionResult.Success
        assertEquals("config-protection", success.operationId, "revalidation must defer, got $result")
        assertEquals(
            listOf(TitleId("vip")),
            run2.selection.stateOf(uuid).prefixes,
            "protection must not unequip prefixes",
        )
        assertEquals(
            listOf(TitleId("pro")),
            run2.selection.stateOf(uuid).suffixes,
            "protection must not unequip suffixes",
        )
        assertEquals(revisionAfterEquip, run2.selection.stateOf(uuid).revision, "nothing may be committed")
        run2.shutdown()

        // Fix the configuration and "restart": the persisted equipment is intact.
        Files.writeString(run2.configFile, validConfig())
        val run3 = ServerRun(dir)
        val load3 = run3.boot()
        assertTrue(load3 is ConfigLoadResult.Success && load3.state == LoadState.OK, "got $load3")
        assertEquals(
            listOf(TitleId("vip")),
            run3.selection.stateOf(uuid).prefixes,
            "equipment must be fully recovered from the database",
        )
        assertEquals(listOf(TitleId("pro")), run3.selection.stateOf(uuid).suffixes)
        val result3 = revalidate(run3)
        assertTrue(result3 is SelectionResult.Success, "got $result3")
        assertEquals(listOf(TitleId("vip")), run3.selection.stateOf(uuid).prefixes, "still equipped after recheck")
        run3.shutdown()
    }

    // ------------------------------------------------------------------
    // (3) last-good itself corrupted
    // ------------------------------------------------------------------

    @Test
    fun `a corrupted last-good copy also leads to protection`() {
        val dir = newDir()
        val run1 = ServerRun(dir)
        Files.writeString(run1.configFile, validConfig())
        run1.boot()
        equip(run1, "vip")
        run1.shutdown()
        assertTrue(Files.isRegularFile(run1.lastGoodFile))

        Files.writeString(run1.configFile, "{ broken")
        Files.writeString(run1.lastGoodFile, "also { broken")
        val run2 = ServerRun(dir)
        val load2 = run2.boot()
        assertTrue(load2 is ConfigLoadResult.Failure, "got $load2")
        assertEquals(LoadState.PROTECTED, run2.configService.loadState)

        val result = revalidate(run2)
        assertEquals("config-protection", (result as SelectionResult.Success).operationId)
        assertEquals(listOf(TitleId("vip")), run2.selection.stateOf(uuid).prefixes)
        run2.shutdown()
    }

    // ------------------------------------------------------------------
    // (4) intentionally empty catalog is NOT protection
    // ------------------------------------------------------------------

    @Test
    fun `an empty but valid titles list still unequips on revalidation`() {
        val dir = newDir()
        val run1 = ServerRun(dir)
        Files.writeString(run1.configFile, validConfig())
        run1.boot()
        equip(run1, "vip")
        run1.shutdown()

        Files.writeString(run1.configFile, validConfig(titles = false))
        val run2 = ServerRun(dir)
        val load2 = run2.boot()
        assertTrue(load2 is ConfigLoadResult.Success, "got $load2")
        assertEquals(LoadState.OK, run2.configService.loadState)
        assertFalse(run2.configService.protectionActive, "empty-by-choice is not protection")

        val result = revalidate(run2)
        assertTrue(result is SelectionResult.Success, "got $result")
        assertTrue((result as SelectionResult.Success).operationId != "config-protection")
        assertEquals(emptyList(), run2.selection.stateOf(uuid).prefixes, "documented semantics: removed")
        run2.shutdown()

        // And the removal is persisted (deliberate, documented behaviour).
        val run3 = ServerRun(dir)
        run3.boot()
        assertEquals(emptyList(), run3.selection.stateOf(uuid).prefixes)
        run3.shutdown()
    }

    // ------------------------------------------------------------------
    // (5) PROTECTED refuses state changes
    // ------------------------------------------------------------------

    @Test
    fun `protection rejects equip unequip nickname and grant`() {
        val dir = newDir()
        Files.writeString(dir.resolve("fancycrests.json"), "{ broken")
        val run = ServerRun(dir)
        val load = run.boot()
        assertTrue(load is ConfigLoadResult.Failure, "got $load")
        assertEquals(LoadState.PROTECTED, run.configService.loadState)

        val equipResult = await { cb -> run.selection.equip(uuid, TitleId("vip"), "test", callback = cb) }
        assertEquals(RejectionReason.CONFIG_PROTECTED, (equipResult as SelectionResult.Rejected).reason)

        val unequipResult = await { cb ->
            run.selection.unequip(uuid, TitleSlot.PREFIX, UnequipTarget.All, "test", cb)
        }
        assertEquals(RejectionReason.CONFIG_PROTECTED, (unequipResult as SelectionResult.Rejected).reason)

        val nicknameResult = await { cb -> run.selection.setNickname(uuid, "CatNine", "test", cb) }
        assertEquals(RejectionReason.CONFIG_PROTECTED, (nicknameResult as SelectionResult.Rejected).reason)

        val grantResult = await { cb -> run.selection.grant(uuid, TitleId("vip"), "local", "test", cb) }
        assertEquals(RejectionReason.CONFIG_PROTECTED, (grantResult as SelectionResult.Rejected).reason)

        assertEquals(0, run.selection.ownershipCount, "no grant may be recorded")
        run.shutdown()
    }

    // ------------------------------------------------------------------
    // (6) last-good write failure is not fatal and not destructive
    // ------------------------------------------------------------------

    @Test
    fun `a failed last-good write leaves the previous copy intact`() {
        val dir = newDir()
        val run1 = ServerRun(dir)
        Files.writeString(run1.configFile, validConfig())
        val load1 = run1.boot()
        assertTrue(load1 is ConfigLoadResult.Success, "got $load1")
        assertTrue(Files.isRegularFile(run1.lastGoodFile))
        run1.shutdown()

        // The previous copy must survive a failing refresh: block the temp
        // path with a non-empty directory so both the copy and the move fail.
        Files.writeString(run1.lastGoodFile, "SENTINEL")
        val tmp = run1.lastGoodFile.resolveSibling("fancycrests.json.last-good.tmp")
        Files.createDirectories(tmp)
        Files.writeString(tmp.resolve("blocker"), "x")

        Files.writeString(run1.configFile, validConfig()) // still fully valid content
        val run2 = ServerRun(dir)
        val load2 = run2.boot()
        assertTrue(load2 is ConfigLoadResult.Success, "a failed last-good write must not fail the load: $load2")
        assertEquals(LoadState.OK, run2.configService.loadState)
        assertEquals(
            "SENTINEL",
            Files.readString(run2.lastGoodFile),
            "the existing last-good file must not be damaged by a failed write",
        )
        run2.shutdown()
    }

    private fun deleteRecursively(path: Path) {
        if (!Files.exists(path)) return
        if (Files.isDirectory(path)) {
            Files.list(path).use { stream ->
                stream.forEach { deleteRecursively(it) }
            }
        }
        try {
            Files.deleteIfExists(path)
        } catch (_: Exception) {
            // Best effort on Windows file locks.
        }
    }
}
