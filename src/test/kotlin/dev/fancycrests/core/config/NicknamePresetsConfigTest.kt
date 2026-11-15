package dev.fancycrests.core.config

import com.google.gson.JsonParser
import dev.fancycrests.core.TestFixtures
import java.nio.file.Path
import java.util.concurrent.Executor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class NicknamePresetsConfigTest {
    private val service = ConfigService(Path.of("fancycrests.json"), Executor {})

    @Test
    fun `presets are optional and preserve configured order and formatting`() {
        val old = service.parseConfig(JsonParser.parseString("{}").asJsonObject)
        assertEquals(emptyList(), old.nickname.presets)
        val configured = service.parseConfig(
            JsonParser.parseString("""{"nickname":{"presets":["&aExplorer","&bBuilder"]}}""").asJsonObject,
        )
        assertEquals(listOf("&aExplorer", "&bBuilder"), configured.nickname.presets)
    }

    @Test
    fun `invalid entries and duplicates report their paths`() {
        val failure = assertFailsWith<ConfigService.ConfigParseException> {
            service.parseConfig(JsonParser.parseString("""{"nickname":{"presets":["valid",42]}}""").asJsonObject)
        }
        assertTrue(failure.errors.any { it.path == "nickname.presets[1]" })

        val base = TestFixtures.config()
        val errors = service.validate(base.copy(nickname = base.nickname.copy(presets = listOf("&a", "Name", "Name"))))
        assertTrue(errors.any { it.path == "nickname.presets[0]" })
        assertTrue(errors.any { it.path == "nickname.presets[2]" })
    }
}
