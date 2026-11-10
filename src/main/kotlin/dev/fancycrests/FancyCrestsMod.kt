package dev.fancycrests

import net.fabricmc.api.ModInitializer
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Entry point of Fancy Crests (Custom Names).
 *
 * The mod is developed against the unobfuscated names of Minecraft 26.2 and
 * only runs on the server side; vanilla clients require nothing.
 */
object FancyCrestsMod : ModInitializer {
    const val MOD_ID = "fancycrests"

    val LOGGER: Logger = LoggerFactory.getLogger("FancyCrests")

    override fun onInitialize() {
        LOGGER.info("Fancy Crests (Custom Names) initialized")
    }
}
