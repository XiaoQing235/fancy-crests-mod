package dev.fancycrests.core.config

import com.google.gson.JsonParser
import java.nio.file.Path
import java.util.concurrent.Executor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Optional per-channel first-line templates: null (absent or explicit null)
 * means "fall back to the global template", an empty array means "this
 * channel renders no first line", and a populated array parses with the
 * same segment rules as the global template plus the optional
 * segmentSeparator.
 */
class ChannelTemplateConfigTest {

    private fun service(): ConfigService = ConfigService(Path.of("fancycrests.json"), Executor {})

    private fun parseChannels(json: String): ChannelsConfig {
        val root = JsonParser.parseString(json).asJsonObject
        return service().parseConfig(root).channels
    }

    private fun errorsFor(json: String): List<ConfigError> = try {
        service().parseConfig(JsonParser.parseString(json).asJsonObject)
        emptyList()
    } catch (e: ConfigService.ConfigParseException) {
        e.errors
    }

    @Test
    fun `absent template members are null`() {
        val channels = parseChannels("""{"channels": {"chat": true}}""")
        assertNull(channels.headRenderTemplate)
        assertNull(channels.chatTemplate)
        assertNull(channels.tabListTemplate)
    }

    @Test
    fun `explicit null template members are null`() {
        val channels = parseChannels(
            """{"channels": {"chat": true, "chatTemplate": null, "tabListTemplate": null}}""",
        )
        assertNull(channels.chatTemplate)
        assertNull(channels.tabListTemplate)
    }

    @Test
    fun `an empty template array is configured-empty, not fallback`() {
        val channels = parseChannels("""{"channels": {"chat": true, "chatTemplate": []}}""")
        assertEquals(emptyList(), channels.chatTemplate)
    }

    @Test
    fun `segments parse with separator style and segment separator`() {
        val channels = parseChannels(
            """
            {"channels": {"chat": true, "chatTemplate": [
              {"type": "prefixes", "separator": " | ", "separatorStyle": "&7", "segmentSeparator": " · "},
              {"type": "name"},
              {"type": "suffixes", "separator": " "}
            ]}}
            """.trimIndent(),
        )
        val segments = channels.chatTemplate.orEmpty()
        assertEquals(3, segments.size)
        assertEquals(TemplateSegmentType.PREFIXES, segments[0].type)
        assertEquals(" | ", segments[0].separator)
        assertEquals("&7", segments[0].separatorStyle)
        assertEquals(" · ", segments[0].segmentSeparator)
        assertEquals(" ", segments[1].separator, "separator defaults to a single space")
        assertNull(segments[1].separatorStyle)
        assertNull(segments[1].segmentSeparator, "segmentSeparator falls back to separator when absent")
    }

    @Test
    fun `a segment without a type is a precise error`() {
        val errors = errorsFor(
            """{"channels": {"chat": true, "chatTemplate": [{"separator": " "}]}}""",
        )
        assertTrue(
            errors.any { it.path == "channels.chatTemplate[0].type" && it.message.contains("required") },
            "expected a missing-type error, got $errors",
        )
    }

    @Test
    fun `a non-array template is an error`() {
        val errors = errorsFor("""{"channels": {"chat": true, "chatTemplate": {"type": "name"}}}""")
        assertTrue(
            errors.any { it.path == "channels.chatTemplate" && it.message.contains("array") },
            "expected an array error, got $errors",
        )
    }
}
