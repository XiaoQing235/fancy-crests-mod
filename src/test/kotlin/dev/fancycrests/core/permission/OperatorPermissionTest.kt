package dev.fancycrests.core.permission

import dev.fancycrests.api.PermissionVerdict
import dev.fancycrests.api.Permissions
import dev.fancycrests.command.CommandSupport
import dev.fancycrests.core.TestFixtures
import dev.fancycrests.core.config.NodeGrants
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OperatorPermissionTest {
    private val uuid = UUID.randomUUID()
    private val adapter = TestFixtures.FakeAdapter()

    private class Backend(var verdict: PermissionVerdict = PermissionVerdict.UNDEFINED) : PermissionBackend {
        override val name = "external"
        var ready = true
        var warmUps = 0

        override fun explicitVerdict(uuid: UUID, node: String): PermissionVerdict =
            if (ready) verdict else PermissionVerdict.UNDEFINED

        override fun isReady(uuid: UUID): Boolean = ready
        override fun warmUp(uuid: UUID) { warmUps++ }
    }

    @Test
    fun `every registered mod node is available to an operator at any level`() {
        val manager = PermissionManager(adapter)
        for (level in 0..4) {
            adapter.operators = mapOf(uuid to level)
            for (node in CommandSupport.allNodes) {
                assertEquals(PermissionVerdict.ALLOW, manager.check(uuid, node), "level $level: $node")
            }
        }
    }

    @Test
    fun `a mod configured node is allowed while an unrelated node stays undefined`() {
        adapter.operators = mapOf(uuid to 0)
        val configured = mutableSetOf("example.title.vip", "example.rule.staff")
        val manager = PermissionManager(adapter, isConfiguredNode = { it in configured })
        assertEquals(PermissionVerdict.ALLOW, manager.check(uuid, "example.title.vip"))
        assertEquals(PermissionVerdict.ALLOW, manager.check(uuid, "example.rule.staff"))
        assertEquals(PermissionVerdict.UNDEFINED, manager.check(uuid, "example.other"))

        configured.remove("example.title.vip")
        assertEquals(PermissionVerdict.UNDEFINED, manager.check(uuid, "example.title.vip"))
    }

    @Test
    fun `explicit denies from either backend beat OP and the other backend allow`() {
        adapter.operators = mapOf(uuid to 4)
        val builtIn = BuiltInBackend().apply {
            reload(mapOf(uuid to NodeGrants(allow = listOf(Permissions.NICKNAME_SET), deny = emptyList())))
        }
        val external = Backend(PermissionVerdict.DENY)
        val manager = PermissionManager(adapter, listOf(builtIn, external))
        assertEquals(PermissionVerdict.DENY, manager.check(uuid, Permissions.NICKNAME_SET))

        external.verdict = PermissionVerdict.ALLOW
        builtIn.reload(mapOf(uuid to NodeGrants(allow = emptyList(), deny = listOf("fancycrests.nickname.*"))))
        assertEquals(PermissionVerdict.DENY, manager.check(uuid, Permissions.NICKNAME_SET))
        assertEquals(PermissionVerdict.DENY, manager.check(uuid, Permissions.NICKNAME_BYPASS_RESERVED))

        builtIn.reload(emptyMap())
        external.verdict = PermissionVerdict.UNDEFINED
        assertEquals(PermissionVerdict.ALLOW, manager.check(uuid, Permissions.NICKNAME_SET))
    }

    @Test
    fun `OP access waits for an unloaded backend even when another backend allows`() {
        adapter.operators = mapOf(uuid to 4)
        val builtIn = BuiltInBackend().apply {
            reload(mapOf(uuid to NodeGrants(allow = listOf(Permissions.NICKNAME_SET), deny = emptyList())))
        }
        val external = Backend().apply { ready = false }
        val manager = PermissionManager(adapter, listOf(builtIn, external))
        assertEquals(PermissionVerdict.DENY, manager.check(uuid, Permissions.NICKNAME_SET))
        assertTrue(external.warmUps > 0)

        external.ready = true
        external.verdict = PermissionVerdict.DENY
        assertEquals(PermissionVerdict.DENY, manager.check(uuid, Permissions.NICKNAME_SET))
        external.verdict = PermissionVerdict.UNDEFINED
        assertEquals(PermissionVerdict.ALLOW, manager.check(uuid, Permissions.NICKNAME_SET))
    }

    @Test
    fun `ordinary player defaults remain unchanged`() {
        val manager = PermissionManager(adapter, isConfiguredNode = { it == "example.title.vip" })
        assertEquals(PermissionVerdict.ALLOW, manager.check(uuid, Permissions.MENU))
        assertEquals(PermissionVerdict.DENY, manager.check(uuid, Permissions.NICKNAME_SET))
        assertEquals(PermissionVerdict.DENY, manager.check(uuid, Permissions.NICKNAME_BYPASS_RESERVED))
        assertEquals(PermissionVerdict.DENY, manager.check(uuid, Permissions.RELOAD))
        assertEquals(PermissionVerdict.UNDEFINED, manager.check(uuid, "example.title.vip"))
    }
}
