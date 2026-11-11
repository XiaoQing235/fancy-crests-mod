package dev.fancycrests.core.config

import dev.fancycrests.api.TitleSlot
import dev.fancycrests.core.TestFixtures
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Audit #13 regression: the title id "all" is the unequip-all keyword; a
 * catalog entry with that id would shadow the keyword and make "unequip all"
 * ambiguous. Semantic validation must reject it with the exact titles[i].id
 * path.
 */
class ReservedTitleIdTest {

    private val configFile = Files.createTempFile("fancycrests-config-", ".json")
    private val service = ConfigService(configFile, TestFixtures.directExecutor)

    @AfterTest
    fun tearDown() {
        service.stop()
        Files.deleteIfExists(configFile)
    }

    @Test
    fun `title id all is rejected as reserved for unequip`() {
        val config = TestFixtures.config(
            titles = listOf(
                TestFixtures.title("vip", TitleSlot.PREFIX),
                TestFixtures.title("all", TitleSlot.SUFFIX),
            ),
        )
        val errors = service.validate(config)

        val error = errors.singleOrNull { it.path == "titles[1].id" }
        assertTrue(error != null, "expected a titles[1].id error, got $errors")
        assertTrue(
            error.message.contains("reserved"),
            "the message must say the id is reserved (for unequip), got '${error.message}'",
        )
    }

    @Test
    fun `ordinary ids still validate cleanly`() {
        assertEquals(emptyList(), service.validate(TestFixtures.config()))
    }
}
