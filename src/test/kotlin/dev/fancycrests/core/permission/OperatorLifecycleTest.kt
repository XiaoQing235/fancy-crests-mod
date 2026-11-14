package dev.fancycrests.core.permission

import dev.fancycrests.api.PermissionVerdict
import dev.fancycrests.api.TitleId
import dev.fancycrests.core.CoreServices
import dev.fancycrests.core.TestFixtures
import dev.fancycrests.core.config.ConfigLoadResult
import dev.fancycrests.core.event.TitleOperationType
import dev.fancycrests.core.selection.SelectionResult
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class OperatorLifecycleTest {
    @TempDir
    lateinit var directory: Path

    private val uuid = UUID.randomUUID()
    private val adapter = TestFixtures.FakeAdapter().apply {
        online = listOf(uuid)
        knownNames = mapOf("TestPlayer" to uuid)
        operators = mapOf(uuid to 0)
    }
    private lateinit var core: CoreServices

    private fun start() {
        Files.writeString(directory.resolve("fancycrests.json"), config("example.title.vip"))
        core = CoreServices(directory, "test")
        core.start(adapter)
    }

    @AfterTest
    fun stop() {
        if (this::core.isInitialized) core.stop()
    }

    private fun config(titlePermission: String) = """
        {
          "channels": {"headRender": false, "chat": false, "tabList": false},
          "firstLineTemplate": [{"type": "name"}],
          "titles": [{"id": "vip", "slot": "prefix", "displayName": "VIP", "text": "VIP", "permission": "$titlePermission"}],
          "secondLineRules": [{"id": "staff", "priority": 10, "condition": {"permission": "example.rule.staff"}, "template": "STAFF"}]
        }
    """.trimIndent()

    private fun <T> await(start: ((T) -> Unit) -> Unit): T {
        val result = CompletableFuture<T>()
        start { result.complete(it) }
        return result.get(10, TimeUnit.SECONDS)
    }

    @Test
    fun `actual configuration supplies title and rule nodes and updates on reload`() {
        start()
        assertEquals(PermissionVerdict.ALLOW, core.permissionManager.check(uuid, "example.title.vip"))
        assertEquals(PermissionVerdict.ALLOW, core.permissionManager.check(uuid, "example.rule.staff"))
        assertEquals(PermissionVerdict.UNDEFINED, core.permissionManager.check(uuid, "example.unrelated"))

        Files.writeString(directory.resolve("fancycrests.json"), config("example.title.new"))
        val result = await<ConfigLoadResult> { core.reloadAsync(it) }
        assertTrue(result is ConfigLoadResult.Success)
        assertEquals(PermissionVerdict.UNDEFINED, core.permissionManager.check(uuid, "example.title.vip"))
        assertEquals(PermissionVerdict.ALLOW, core.permissionManager.check(uuid, "example.title.new"))
    }

    @Test
    fun `operator changes republish rules and revoke only equipment without ownership`() {
        start()
        val equipped = await<SelectionResult> { core.selectionService.equip(uuid, TitleId("vip"), "test", callback = it) }
        assertTrue(equipped is SelectionResult.Success)
        assertEquals("STAFF", core.snapshotPublisher.latestView(uuid)?.snapshot?.secondLine?.string)

        val rechecked = CountDownLatch(1)
        core.eventBus.addPostListener { if (it.uuid == uuid && it.type == TitleOperationType.RECHECK) rechecked.countDown() }
        adapter.operators = emptyMap()
        core.onOperatorChanged(uuid)
        assertTrue(rechecked.await(10, TimeUnit.SECONDS), "revocation should persist and publish")
        assertEquals(emptyList(), core.selectionService.stateOf(uuid).prefixes)
        assertEquals(null, core.snapshotPublisher.latestView(uuid)?.snapshot?.secondLine)

        val grant = await<SelectionResult> { core.selectionService.grant(uuid, TitleId("vip"), "local", "test", it) }
        assertTrue(grant is SelectionResult.Success)
        val ownedEquip = await<SelectionResult> { core.selectionService.equip(uuid, TitleId("vip"), "test", callback = it) }
        assertTrue(ownedEquip is SelectionResult.Success)
        core.onOperatorChanged(uuid)
        assertEquals(listOf(TitleId("vip")), core.selectionService.stateOf(uuid).prefixes)
    }

    @Test
    fun `operator gain refreshes a permission gated static second line`() {
        start()
        adapter.operators = emptyMap()
        core.onOperatorChanged(uuid)
        assertEquals(null, core.snapshotPublisher.latestView(uuid)?.snapshot?.secondLine)
        adapter.operators = mapOf(uuid to 0)
        core.onOperatorChanged(uuid)
        assertEquals("STAFF", core.snapshotPublisher.latestView(uuid)?.snapshot?.secondLine?.string)
    }
}
