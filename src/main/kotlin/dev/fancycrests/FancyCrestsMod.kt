package dev.fancycrests

import dev.fancycrests.adapter.luckperms.LuckPermsAdapter
import dev.fancycrests.adapter.placeholderapi.PlaceholderApiExport
import dev.fancycrests.command.FancyCrestsCommands
import dev.fancycrests.core.CoreServices
import dev.fancycrests.core.MinecraftServerAdapter
import dev.fancycrests.pubapi.FancyCrestsPublicApi
import dev.fancycrests.render.HeadRenderChannel
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.fabricmc.loader.api.FabricLoader
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Composition root of Fancy Crests (Custom Names).
 *
 * Wires the frozen api contracts together: core domain services (P2), the
 * head-label render channel (P1), GUI/dynamic templates (P3, assembled inside
 * CoreServices) and the optional adapters plus public API (P4). Everything is
 * developed against the unobfuscated names of Minecraft 26.2; the mod runs on
 * the server side only and vanilla clients require nothing.
 */
object FancyCrestsMod : ModInitializer {
    const val MOD_ID = "fancycrests"

    val LOGGER: Logger = LoggerFactory.getLogger("FancyCrests")

    private var core: CoreServices? = null
    private var luckPermsMount: AutoCloseable? = null
    private var placeholderExport: AutoCloseable? = null
    private var publicApi: FancyCrestsPublicApi? = null

    /** Read-only access for other mods after SERVER_STARTED; null before. */
    @JvmStatic
    fun api(): FancyCrestsPublicApi? = publicApi

    override fun onInitialize() {
        val loader = FabricLoader.getInstance()
        val modVersion = loader.getModContainer(MOD_ID)
            .map { it.metadata.version.friendlyString }
            .orElse("0.0.0")

        val headChannel = HeadRenderChannel()
        val services = CoreServices(loader.configDir, modVersion) { headChannel }
        core = services

        // Command trees (/fancycrests, /names, /customnames) self-register.
        FancyCrestsCommands.register(services)

        ServerLifecycleEvents.SERVER_STARTED.register { server ->
            services.start(MinecraftServerAdapter(server))

            // Optional integrations; each mount is guarded by mod presence and
            // never touches core state when its host mod is absent.
            luckPermsMount = LuckPermsAdapter.mount(services)
            placeholderExport = PlaceholderApiExport.mount(services.placeholderService)
            publicApi = FancyCrestsPublicApi(services, services.placeholderService)

            val backends = listOfNotNull(
                "built-in",
                if (luckPermsMount != null) "luckperms" else null,
                if (placeholderExport != null) "placeholder-api" else null,
            )
            LOGGER.info("Fancy Crests started (v$modVersion); active integrations: ${backends.joinToString()}")
        }

        ServerLifecycleEvents.SERVER_STOPPING.register {
            // Teardown order: consumers first, then core, then the head channel
            // (stopped inside core.stop() because core started it).
            runCatching { publicApi?.close() }.onFailure { LOGGER.warn("public api close failed", it) }
            runCatching { placeholderExport?.close() }.onFailure { LOGGER.warn("placeholder export close failed", it) }
            runCatching { luckPermsMount?.close() }.onFailure { LOGGER.warn("luckperms mount close failed", it) }
            runCatching { services.stop() }.onFailure { LOGGER.warn("core stop failed", it) }
            publicApi = null
            placeholderExport = null
            luckPermsMount = null
        }

        // Connection hooks feeding the core join/quit revalidation pipeline.
        ServerPlayConnectionEvents.JOIN.register { handler, _, _ ->
            services.onPlayerJoin(handler.player.uuid)
        }
        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ ->
            services.onPlayerQuit(handler.player.uuid)
        }

        LOGGER.info("Fancy Crests (Custom Names) initialized")
    }
}
