package dev.fancycrests.adapter.luckperms

import dev.fancycrests.core.CoreServices
import net.fabricmc.loader.api.FabricLoader
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Entry point for the optional LuckPerms integration. Contains no LuckPerms
 * types, so loading this class on a server without LuckPerms is safe; the
 * LP-typed implementation lives in this package only and is first touched
 * after the mod-present guard below (plan chapter 5: adapters must not
 * trigger class loading failures when the external mod is absent).
 *
 * Usage (integration layer, inside ServerLifecycleEvents.SERVER_STARTED,
 * after CoreServices.start):
 *
 * ```kotlin
 * val lpMount = LuckPermsAdapter.mount(core)
 * // ... on SERVER_STOPPING:
 * lpMount?.close()
 * ```
 *
 * On success the backend registers itself into CoreServices.permissionManager
 * (after the built-in backend, so LP verdicts win per core's evaluation
 * order: first explicit verdict in consultation order). No core file is
 * modified.
 */
object LuckPermsAdapter {
    /** Fabric mod id of the LuckPerms server plugin/mod. */
    const val MOD_ID = "luckperms"

    private val logger: Logger = LoggerFactory.getLogger("FancyCrests/LuckPerms")

    fun isAvailable(): Boolean = FabricLoader.getInstance().isModLoaded(MOD_ID)

    /**
     * Mounts the adapter when LuckPerms is loaded and its provider responds.
     * Returns null (and logs at info level) when absent; returns null (and
     * logs at warn level) when present but unusable — the mod then keeps
     * running on the built-in backend only. Subscription failure is treated
     * as "adapter unavailable", never as a silently-stale backend.
     */
    fun mount(core: CoreServices): LuckPermsMount? {
        if (!isAvailable()) {
            logger.info("LuckPerms not detected; permission resolution stays on the built-in backend")
            return null
        }
        return try {
            val mount = LuckPermsMount.create(core)
            core.permissionManager.registerBackend(mount.backend)
            logger.info(
                "LuckPerms adapter mounted as permission backend '{}'; revocation latency: {}",
                mount.backendName,
                mount.revocationLatency,
            )
            mount
        } catch (t: Throwable) {
            // Covers LuckPermsProvider.get() when the plugin has not
            // registered yet (IllegalStateException — NotLoadedException is
            // package-private in api 5.5) and any linkage problem.
            logger.warn("LuckPerms detected but the adapter could not be mounted; staying on the built-in backend", t)
            null
        }
    }
}
