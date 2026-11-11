package dev.fancycrests.core.config

import com.google.gson.JsonParser
import java.nio.file.Path
import java.util.concurrent.Executor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Batch J3: validation semantics that used to be silent no-ops.
 *
 * Three classes of mistakes used to slip through: a dimension shorthand
 * (`overworld`) never matched the runtime's full key, numeric fields were
 * parsed through `Double` (rounding large values and saturating at the Int
 * bounds), and the equipment limits refused `0` even though `0` is the
 * documented way to disable a slot. Every case below is an error at load
 * time with the exact JSON path, never a silently clamped value.
 */
class ValidationSemanticsTest {

    private fun service(): ConfigService = ConfigService(Path.of("fancycrests.json"), Executor {})

    private fun parse(json: String): FancyCrestsConfig =
        service().parseConfig(JsonParser.parseString(json).asJsonObject)

    private fun errorsFor(json: String): List<ConfigError> = try {
        parse(json)
        emptyList()
    } catch (e: ConfigService.ConfigParseException) {
        e.errors
    }

    /**
     * Parses (unless a template is already present) and then runs the
     * semantic validator, which is the stage that enforces the cross-field
     * rules. Parse failures are returned directly.
     */
    private fun validateJson(json: String): List<ConfigError> {
        val root = JsonParser.parseString(json).asJsonObject
        if (!root.has("firstLineTemplate")) {
            root.add("firstLineTemplate", JsonParser.parseString("""[{"type": "name"}]"""))
        }
        val config = try {
            service().parseConfig(root)
        } catch (e: ConfigService.ConfigParseException) {
            return e.errors
        }
        return service().validate(config)
    }

    private fun assertParseError(json: String, path: String) {
        val errors = errorsFor(json)
        assertTrue(errors.any { it.path == path }, "expected a parse error at $path for $json, got $errors")
    }

    // ---- dimension shorthand -------------------------------------------------

    @Test
    fun `a dimension shorthand gains the vanilla namespace`() {
        val config = parse(
            """{"secondLineRules": [{"id": "r", "template": "x", "condition": {"dimension": "overworld"}}]}""",
        )
        assertEquals("minecraft:overworld", config.secondLineRules.single().condition?.dimension)
    }

    @Test
    fun `a namespaced dimension keeps its own namespace`() {
        val vanilla = parse(
            """{"secondLineRules": [{"id": "r", "template": "x", "condition": {"dimension": "the_nether"}}]}""",
        )
        assertEquals("minecraft:the_nether", vanilla.secondLineRules.single().condition?.dimension)

        val modded = parse(
            """{"secondLineRules": [{"id": "r", "template": "x", "condition": {"dimension": "mymod:dimension"}}]}""",
        )
        assertEquals("mymod:dimension", modded.secondLineRules.single().condition?.dimension)
    }

    @Test
    fun `an invalid dimension is reported with the normalized value`() {
        val config = parse(
            """{"secondLineRules": [{"id": "r", "template": "x", "condition": {"dimension": "Over world"}}]}""",
        )
        val error = service().validate(config).firstOrNull {
            it.path == "secondLineRules[0].condition.dimension"
        }
        assertTrue(error != null, "expected a dimension error for 'Over world'")
        assertTrue(
            error.message.contains("minecraft:Over world"),
            "the error must show the normalized identifier, got '${error.message}'",
        )
    }

    // ---- equipment limits ----------------------------------------------------

    @Test
    fun `equipment limits accept zero and reject negatives or over-cap values`() {
        assertEquals(
            0,
            parse("""{"equipment": {"maxPrefixes": 0, "maxSuffixes": 0}}""").equipment.maxPrefixes,
        )
        assertEquals(10, parse("""{"equipment": {"maxPrefixes": 10}}""").equipment.maxPrefixes)
        assertParseError("""{"equipment": {"maxPrefixes": -1}}""", "equipment.maxPrefixes")
        assertParseError("""{"equipment": {"maxSuffixes": 11}}""", "equipment.maxSuffixes")
    }

    @Test
    fun `zero limits survive semantic validation`() {
        assertEquals(
            emptyList(),
            validateJson("""{"equipment": {"maxPrefixes": 0, "maxSuffixes": 0}}"""),
        )
    }

    // ---- nickname lengths ----------------------------------------------------

    @Test
    fun `nickname lengths stay inside one to sixty-four`() {
        assertParseError("""{"nickname": {"minLength": 0}}""", "nickname.minLength")
        assertParseError("""{"nickname": {"maxLength": 0}}""", "nickname.maxLength")
        assertParseError("""{"nickname": {"maxLength": 65}}""", "nickname.maxLength")
        assertEquals(64, parse("""{"nickname": {"maxLength": 64}}""").nickname.maxLength)
    }

    @Test
    fun `minLength must not exceed maxLength`() {
        val errors = validateJson("""{"nickname": {"minLength": 10, "maxLength": 5}}""")
        assertTrue(
            errors.any { it.path == "nickname.minLength" && it.message.contains("must not exceed") },
            errors.toString(),
        )
    }

    // ---- rule and title numbers ---------------------------------------------

    @Test
    fun `rule and title numbers are bounded`() {
        assertEquals(
            1000,
            parse("""{"secondLineRules": [{"id": "r", "template": "x", "priority": 1000}]}""")
                .secondLineRules.single().priority,
        )
        assertParseError(
            """{"secondLineRules": [{"id": "r", "template": "x", "priority": 1001}]}""",
            "secondLineRules[0].priority",
        )
        assertParseError(
            """{"secondLineRules": [{"id": "r", "template": "x", "priority": -1}]}""",
            "secondLineRules[0].priority",
        )
        assertParseError(
            """{"secondLineRules": [{"id": "r", "template": "x", "refreshTicks": 0}]}""",
            "secondLineRules[0].refreshTicks",
        )
        assertEquals(
            10000,
            parse(
                """{"titles": [{"id": "t", "slot": "prefix", "displayName": "d", "text": "x", "sortOrder": 10000}]}""",
            ).titles.single().sortOrder,
        )
        assertParseError(
            """{"titles": [{"id": "t", "slot": "prefix", "displayName": "d", "text": "x", "sortOrder": 10001}]}""",
            "titles[0].sortOrder",
        )
    }

    // ---- render distance -----------------------------------------------------

    @Test
    fun `render distance stays inside eight to one hundred twenty-eight blocks`() {
        assertParseError("""{"render": {"distanceBlocks": 7.9}}""", "render.distanceBlocks")
        assertParseError("""{"render": {"distanceBlocks": 128.1}}""", "render.distanceBlocks")
        assertEquals(8.0, parse("""{"render": {"distanceBlocks": 8.0}}""").render.distanceBlocks)
        assertEquals(128.0, parse("""{"render": {"distanceBlocks": 128}}""").render.distanceBlocks)
    }

    // ---- exact integer parsing ----------------------------------------------

    @Test
    fun `exponent notation is rejected instead of evaluated`() {
        assertParseError("""{"equipment": {"maxPrefixes": 1e5}}""", "equipment.maxPrefixes")
        assertParseError("""{"nickname": {"maxLength": 1E2}}""", "nickname.maxLength")
    }

    @Test
    fun `values beyond the Int range are rejected instead of saturated`() {
        assertParseError(
            """{"equipment": {"maxPrefixes": 99999999999999999999}}""",
            "equipment.maxPrefixes",
        )
        assertParseError("""{"nickname": {"maxLength": 1e400}}""", "nickname.maxLength")
    }
}
