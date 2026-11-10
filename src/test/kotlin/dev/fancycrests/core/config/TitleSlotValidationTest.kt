package dev.fancycrests.core.config

import dev.fancycrests.core.TestFixtures
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A title without an explicit slot must be a load error. Silently dropping it
 * would delete the entry from the catalog and then unequip it from every
 * player on the next revalidation.
 */
class TitleSlotValidationTest {

    @Test
    fun `a title without a slot fails the whole configuration`() {
        val dir = Files.createTempDirectory("fancycrests-slot-")
        val file = dir.resolve("fancycrests.json")
        Files.writeString(
            file,
            """
            {
              "firstLineTemplate": [
                { "type": "prefixes" },
                { "type": "name", "separator": "" },
                { "type": "suffixes" }
              ],
              "titles": [
                { "id": "vip", "displayName": "VIP", "text": "[VIP]" }
              ]
            }
            """.trimIndent(),
        )
        val service = ConfigService(file, TestFixtures.directExecutor)
        val result = service.loadOrCreate()
        service.stop()

        assertTrue(result is ConfigLoadResult.Failure, "expected Failure, got " + result)
        val errors = (result as ConfigLoadResult.Failure).errors
        assertTrue(
            errors.any { it.path == "titles[0].slot" && it.message.contains("required") },
            "expected a titles[0].slot error, got " + errors,
        )
    }

    @Test
    fun `a valid slot keeps the title`() {
        val dir = Files.createTempDirectory("fancycrests-slot-ok-")
        val file = dir.resolve("fancycrests.json")
        Files.writeString(
            file,
            """
            {
              "firstLineTemplate": [
                { "type": "prefixes" },
                { "type": "name", "separator": "" },
                { "type": "suffixes" }
              ],
              "titles": [
                { "id": "vip", "slot": "prefix", "displayName": "VIP", "text": "[VIP]" }
              ]
            }
            """.trimIndent(),
        )
        val service = ConfigService(file, TestFixtures.directExecutor)
        val result = service.loadOrCreate()
        service.stop()

        assertTrue(result is ConfigLoadResult.Success, "expected Success, got " + result)
        val config = (result as ConfigLoadResult.Success).config
        assertTrue(config.titles.single().id == "vip")
    }
}