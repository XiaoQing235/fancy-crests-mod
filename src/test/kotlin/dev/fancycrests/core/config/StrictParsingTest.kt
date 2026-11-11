package dev.fancycrests.core.config

import com.google.gson.JsonParser
import java.nio.file.Path
import java.util.concurrent.Executor
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Batch J2: every object layer enforces a known-keys whitelist and required
 * members are explicit errors. Gson silently ignoring unknown fields is the
 * root of several real failure modes: `permisson` (typo) degraded a rule
 * condition to unconditional, a misspelled `titles` produced an empty
 * catalog, a segment without `type` was skipped. All must fail loudly with
 * the exact JSON path and the list of legal field names.
 */
class StrictParsingTest {

    private fun service(): ConfigService = ConfigService(Path.of("fancycrests.json"), Executor {})

    private fun errorsFor(json: String): List<ConfigError> = try {
        service().parseConfig(JsonParser.parseString(json).asJsonObject)
        emptyList()
    } catch (e: ConfigService.ConfigParseException) {
        e.errors
    }

    private fun assertError(json: String, path: String, messagePart: String) {
        val errors = errorsFor(json)
        val error = errors.firstOrNull { it.path == path }
        assertTrue(error != null, "expected an error at $path for $json, got $errors")
        assertTrue(
            error.message.contains(messagePart),
            "message at $path should contain '$messagePart', got '${error.message}'",
        )
    }

    @Test
    fun `a misspelled root field is an unknown-field error`() {
        // "titels" would silently produce an empty catalog.
        assertError(
            """{"titels": []}""",
            "titels",
            "unknown field 'titels'",
        )
        val errors = errorsFor("""{"titels": []}""")
        val message = errors.first { it.path == "titels" }.message
        assertTrue(
            listOf("channels", "titles", "firstLineTemplate").all { message.contains(it) },
            "the error must list the allowed field names, got '$message'",
        )
    }

    @Test
    fun `a misspelled condition key is an unknown-field error`() {
        // "permisson" would degrade the condition to unconditional matching.
        assertError(
            """{"secondLineRules": [{"id": "r", "template": "x",
                "condition": {"dimension": "minecraft:overworld", "permisson": "a.b"}}]}""",
            "secondLineRules[0].condition.permisson",
            "unknown field 'permisson'",
        )
    }

    @Test
    fun `a misspelled channel switch is an unknown-field error`() {
        assertError(
            """{"channels": {"hedRender": true}}""",
            "channels.hedRender",
            "unknown field 'hedRender'",
        )
    }

    @Test
    fun `unknown keys are caught in every nested layer`() {
        for ((json, path) in listOf(
            """{"equipment": {"maxPrefix": 3}}""" to "equipment.maxPrefix",
            """{"nickname": {"maxLenght": 16}}""" to "nickname.maxLenght",
            """{"permissions": {"nodes": {"11111111-1111-1111-1111-111111111111": {"alow": []}}}}""" to
                "permissions.nodes[11111111-1111-1111-1111-111111111111].alow",
            """{"firstLineTemplate": [{"type": "name", "seperator": " "}]}""" to
                "firstLineTemplate[0].seperator",
            """{"channels": {"chatTemplate": [{"type": "name", "seperator": " "}]}}""" to
                "channels.chatTemplate[0].seperator",
            """{"secondLineRules": [{"id": "r", "template": "x", "prioity": 1}]}""" to
                "secondLineRules[0].prioity",
            """{"render": {"distance": 48.0}}""" to "render.distance",
            """{"render": {"firstLineOffset": {"x": 0.0, "w": 1.0}}}""" to "render.firstLineOffset.w",
            """{"titles": [{"id": "t", "slot": "prefix", "displayName": "d", "text": "x", "sortorder": 1}]}""" to
                "titles[0].sortorder",
        )) {
            assertError(json, path, "unknown field")
        }
    }

    @Test
    fun `unknown field errors name the allowed fields`() {
        val errors = errorsFor("""{"equipment": {"maxPrefix": 3}}""")
        val message = errors.first { it.path == "equipment.maxPrefix" }.message
        assertTrue(
            message.contains("maxPrefixes") && message.contains("maxSuffixes"),
            "allowed names must be listed for a quick fix, got '$message'",
        )
    }

    @Test
    fun `required members missing is an explicit error`() {
        for ((json, path) in listOf(
            """{"titles": [{"slot": "prefix", "displayName": "d", "text": "x"}]}""" to "titles[0].id",
            """{"titles": [{"id": "t", "slot": "prefix", "text": "x"}]}""" to "titles[0].displayName",
            """{"titles": [{"id": "t", "slot": "prefix", "displayName": "d"}]}""" to "titles[0].text",
            """{"secondLineRules": [{"template": "x"}]}""" to "secondLineRules[0].id",
            """{"secondLineRules": [{"id": "r"}]}""" to "secondLineRules[0].template",
            """{"firstLineTemplate": [{"separator": " "}]}""" to "firstLineTemplate[0].type",
            """{"channels": {"chatTemplate": [{}]}}""" to "channels.chatTemplate[0].type",
        )) {
            val errors = errorsFor(json)
            val error = errors.firstOrNull { it.path == path }
            assertTrue(error != null, "expected a required error at $path for $json, got $errors")
            assertTrue(
                error.message.contains("required"),
                "message at $path must say 'required', got '${error.message}'",
            )
        }
    }

    @Test
    fun `a fully valid document parses without any error`() {
        val json = """
        {
          "channels": {
            "headRender": true, "chat": true, "tabList": false, "placeholdersExport": false,
            "chatTemplate": [{"type": "prefixes", "separator": " ", "separatorStyle": "&7",
                               "segmentSeparator": " · "}]
          },
          "equipment": {"maxPrefixes": 3, "maxSuffixes": 0},
          "nickname": {"maxLength": 16, "minLength": 3, "pattern": "[A-Za-z0-9_]+",
                        "reservedNames": ["server"], "allowSet": true},
          "permissions": {"nodes": {}},
          "firstLineTemplate": [
            {"type": "prefixes", "separator": " ", "separatorStyle": "&7"},
            {"type": "name", "separator": ""},
            {"type": "suffixes", "separator": " "}
          ],
          "secondLineRules": [
            {"id": "r1", "priority": 10, "template": "%fancycrests:belowname%",
              "condition": {"dimension": "the_nether"}, "refreshTicks": 20}
          ],
          "render": {
            "distanceBlocks": 48.0,
            "firstLineOffset": {"x": 0.0, "y": 0.4, "z": 0.0},
            "secondLineOffset": {"x": 0.0, "y": 0.15, "z": 0.0}
          },
          "titles": [
            {"id": "vip", "slot": "prefix", "displayName": "VIP", "text": "[VIP]",
              "permission": "fancycrests.title.vip", "category": "donor",
              "iconItem": "minecraft:diamond", "sortOrder": 5}
          ]
        }
        """.trimIndent()
        val errors = errorsFor(json)
        assertTrue(errors.isEmpty(), "valid document must parse cleanly, got $errors")
    }
}
