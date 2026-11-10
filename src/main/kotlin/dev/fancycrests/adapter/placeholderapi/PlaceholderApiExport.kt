package dev.fancycrests.adapter.placeholderapi

import dev.fancycrests.api.PlaceholderService
import net.fabricmc.loader.api.FabricLoader
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Entry point for the optional Text Placeholder API integration. Contains no
 * `eu.pb4.placeholders` types, so loading this class without the
 * `placeholder-api` mod is safe; the typed registration lives in this package
 * only and is first touched after the mod-present guard.
 *
 * Usage (integration layer, after CoreServices.start and once the core
 * PlaceholderService implementation exists):
 *
 * ```kotlin
 * val export = PlaceholderApiExport.mount(placeholderServiceImpl)
 * // ... on SERVER_STOPPING:
 * export?.close()
 * ```
 */
object PlaceholderApiExport {
    /** Fabric mod id of Text Placeholder API (verified in its fabric.mod.json). */
    const val MOD_ID = "placeholder-api"

    private val logger: Logger = LoggerFactory.getLogger("FancyCrests/PlaceholderApi")

    fun isAvailable(): Boolean = FabricLoader.getInstance().isModLoaded(MOD_ID)

    /**
     * Registers `%fancycrests:prefix%`, `%fancycrests:suffix%`,
     * `%fancycrests:nickname%` and `%fancycrests:belowname%` when the engine
     * is loaded. Returns null when either the mod or the placeholder service
     * is absent — the rest of the mod is unaffected either way.
     */
    fun mount(placeholders: PlaceholderService?): AutoCloseable? {
        if (placeholders == null) {
            logger.info("Text Placeholder API export skipped: PlaceholderService is not wired")
            return null
        }
        if (!isAvailable()) {
            logger.info("Text Placeholder API not detected; placeholder export stays disabled")
            return null
        }
        return try {
            val registration = PlaceholderApiRegistrations.register(placeholders)
            logger.info(
                "Exported placeholders {} to Text Placeholder API",
                dev.fancycrests.api.StandardPlaceholders.ALL.joinToString { key -> "%fancycrests:${key.value}%" },
            )
            registration
        } catch (t: Throwable) {
            logger.warn("Text Placeholder API detected but the export could not be registered", t)
            null
        }
    }
}
