package dev.fancycrests.core.permission

import dev.fancycrests.api.PermissionVerdict
import dev.fancycrests.api.Permissions
import dev.fancycrests.core.TestFixtures
import dev.fancycrests.core.config.NodeGrants
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Chain semantics: an explicit DENY from any backend beats an explicit ALLOW
 * from any other backend, and only then does the documented node default apply.
 */
class PermissionChainDenyWinsTest {

    private val uuid = UUID.randomUUID()
    private val adapter = TestFixtures.FakeAdapter()

    private class Fixed(
        override val name: String,
        private val verdict: PermissionVerdict,
    ) : PermissionBackend {
        override fun explicitVerdict(uuid: UUID, node: String): PermissionVerdict = verdict
    }

    @Test
    fun `a later backend deny overrides an earlier backend allow`() {
        val manager = PermissionManager(adapter, listOf(Fixed("builtin", PermissionVerdict.ALLOW)))
        manager.registerBackend(Fixed("luckperms", PermissionVerdict.DENY))
        assertEquals(PermissionVerdict.DENY, manager.check(uuid, Permissions.EQUIP))
    }

    @Test
    fun `a built-in allow does not shadow an external deny`() {
        val builtIn = BuiltInBackend().apply {
            reload(mapOf(uuid to NodeGrants(allow = listOf(Permissions.EQUIP), deny = emptyList())))
        }
        val manager = PermissionManager(adapter, listOf(builtIn))
        manager.registerBackend(Fixed("luckperms", PermissionVerdict.DENY))
        assertEquals(PermissionVerdict.DENY, manager.check(uuid, Permissions.EQUIP))
    }

    @Test
    fun `a built-in deny is not overridden by an external allow`() {
        val builtIn = BuiltInBackend().apply {
            reload(mapOf(uuid to NodeGrants(allow = emptyList(), deny = listOf(Permissions.EQUIP))))
        }
        val manager = PermissionManager(adapter, listOf(builtIn))
        manager.registerBackend(Fixed("luckperms", PermissionVerdict.ALLOW))
        assertEquals(PermissionVerdict.DENY, manager.check(uuid, Permissions.EQUIP))
    }

    @Test
    fun `an explicit allow still beats the node default policy`() {
        val builtIn = BuiltInBackend().apply {
            reload(mapOf(uuid to NodeGrants(allow = listOf(Permissions.NICKNAME_SET), deny = emptyList())))
        }
        val manager = PermissionManager(adapter, listOf(builtIn))
        assertEquals(PermissionVerdict.ALLOW, manager.check(uuid, Permissions.NICKNAME_SET))
    }

    @Test
    fun `undefined backends fall through to the documented defaults`() {
        val manager = PermissionManager(adapter, listOf(Fixed("none", PermissionVerdict.UNDEFINED)))
        assertEquals(PermissionVerdict.ALLOW, manager.check(uuid, Permissions.MENU))
        assertEquals(PermissionVerdict.ALLOW, manager.check(uuid, Permissions.EQUIP))
        assertEquals(PermissionVerdict.DENY, manager.check(uuid, Permissions.NICKNAME_SET))
        assertEquals(PermissionVerdict.DENY, manager.check(uuid, Permissions.NICKNAME_BYPASS_RESERVED))
        // Admin nodes are OP_LEVEL 2; the fake adapter reports level 0.
        assertEquals(PermissionVerdict.DENY, manager.check(uuid, Permissions.RELOAD))
    }
}