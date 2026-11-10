package dev.fancycrests.adapter.luckperms

import dev.fancycrests.core.ServerAdapter
import dev.fancycrests.core.TestFixtures
import dev.fancycrests.core.permission.PermissionManager
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Recheck routing for LuckPerms events (pure decision + execution shape).
 *
 * The pure functions carry no LuckPerms types (the LP api is compileOnly), so
 * these run without the optional dependency. The LuckPerms-typed extraction
 * (uuidOfHolder / uuidOfSubject) is covered by compilation; what matters
 * here is that:
 *  1. user-attributable changes scope to that user, everything else
 *     (group edits, non-user context subjects) conservatively scopes to all
 *     online players, and
 *  2. the all-online scope never reads the player list on the calling
 *     (LuckPerms async event) thread — the snapshot and recheck happen inside
 *     the main-thread task, because 26.2 backs playerList.players with a
 *     plain ArrayList that only the main thread may iterate.
 */
class LuckPermsRecheckRoutingTest {

    @Test
    fun `user-attributable changes scope to that user`() {
        val uuid = UUID.randomUUID()
        assertEquals(RecheckScope.One(uuid), recheckScopeFor(uuid))
    }

    @Test
    fun `null subject scopes to everyone online`() {
        assertEquals(RecheckScope.AllOnline, recheckScopeFor(null))
    }

    @Test
    fun `single-user scope rechecks through a main-thread task`() {
        val adapter = DeferredAdapter(TestFixtures.FakeAdapter())
        val permissions = PermissionManager(adapter)
        val rechecked = mutableListOf<UUID>()
        permissions.onPermissionChanged = { rechecked += it }
        val uuid = UUID.randomUUID()

        applyRecheckScope(adapter, permissions, RecheckScope.One(uuid))

        // markDirty may happen on the calling thread, the recheck must not.
        assertTrue(rechecked.isEmpty(), "recheckNow must wait for the main-thread task")
        adapter.drain()
        assertEquals(listOf(uuid), rechecked)
    }

    @Test
    fun `all-online scope snapshots the player list inside the main-thread task`() {
        val base = TestFixtures.FakeAdapter().apply { online = listOf(PLAYER_A, PLAYER_B) }
        val adapter = DeferredAdapter(base)
        val permissions = PermissionManager(adapter)
        val rechecked = mutableListOf<UUID>()
        permissions.onPermissionChanged = { rechecked += it }

        applyRecheckScope(adapter, permissions, RecheckScope.AllOnline)

        // While still "on the LP event thread": no list read, no recheck.
        assertTrue(adapter.onlineProbes.isEmpty(), "player list must not be read off the main thread")
        assertTrue(rechecked.isEmpty(), "recheckNow must wait for the main-thread task")

        adapter.drain()

        assertEquals(listOf(true), adapter.onlineProbes, "player list must be read inside the main-thread task")
        assertEquals(setOf(PLAYER_A, PLAYER_B), rechecked.toSet())
    }

    private companion object {
        val PLAYER_A = UUID.randomUUID()
        val PLAYER_B = UUID.randomUUID()
    }
}

/**
 * [ServerAdapter] decorator that queues [execute] like a real server loop
 * instead of running inline, and records whether [onlinePlayerUuids] was
 * called from inside a drained task ("main thread") or from the caller.
 */
private class DeferredAdapter(private val delegate: ServerAdapter) : ServerAdapter by delegate {
    val onlineProbes = mutableListOf<Boolean>()
    private val queue = ArrayDeque<Runnable>()
    private var onMainThread = false

    override fun execute(runnable: Runnable) {
        queue.addLast(runnable)
    }

    override fun onlinePlayerUuids(): List<UUID> {
        onlineProbes.add(onMainThread)
        return delegate.onlinePlayerUuids()
    }

    fun drain() {
        while (!queue.isEmpty()) {
            onMainThread = true
            try {
                queue.removeFirst().run()
            } finally {
                onMainThread = false
            }
        }
    }
}
