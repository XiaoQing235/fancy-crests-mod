package dev.fancycrests.core.config

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import java.nio.file.Path
import java.util.concurrent.Executor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Semantic integer fields must never be silently truncated: a fractional
 * value (3.9) is an error reported with its exact JSON path, while legal
 * integers — including integral floats like 3.0 — keep parsing unchanged.
 */
class StrictIntegerFieldsTest {

    private fun service(): ConfigService = ConfigService(Path.of("fancycrests.json"), Executor {})

    private fun errorsFor(json: String): List<ConfigError> = try {
        service().parseConfig(JsonParser.parseString(json).asJsonObject)
        emptyList()
    } catch (e: ConfigService.ConfigParseException) {
        e.errors
    }

    private fun parseField(member: JsonPrimitive?): Int? {
        val equipment = JsonObject()
        if (member != null) equipment.add("maxPrefixes", member)
        val root = JsonObject()
        root.add("equipment", equipment)
        val config = service().parseConfig(root)
        return config.equipment.maxPrefixes
    }

    @Test
    fun `fractional values are rejected with the exact path`() {
        for ((json, path) in listOf(
            """{"equipment": {"maxPrefixes": 3.9}}""" to "equipment.maxPrefixes",
            """{"nickname": {"maxLength": 16.5}}""" to "nickname.maxLength",
            """{"nickname": {"minLength": 1.25}}""" to "nickname.minLength",
            """{"secondLineRules": [{"id": "r", "priority": 0.5, "template": "x"}]}""" to
                "secondLineRules[0].priority",
            """{"titles": [{"id": "t", "slot": "prefix", "displayName": "d", "text": "x", "sortOrder": 2.75}]}""" to
                "titles[0].sortOrder",
        )) {
            val errors = errorsFor(json)
            assertTrue(
                errors.any { it.path == path },
                "expected a rejection at $path for $json, got $errors",
            )
        }
    }

    @Test
    fun `legal integers keep their exact value`() {
        assertEquals(5, parseField(JsonPrimitive(5)))
        assertEquals(1, parseField(JsonPrimitive(1)))
        // Integral floats are indistinguishable from integers; accept them
        // (backward compatible with Gson's number handling).
        assertEquals(3, parseField(JsonPrimitive(3.0)))
        // Missing member keeps the default.
        assertEquals(3, parseField(null))
    }

    @Test
    fun `non-numbers are still rejected`() {
        val errors = errorsFor("""{"equipment": {"maxPrefixes": "3"}}""")
        assertTrue(errors.any { it.path == "equipment.maxPrefixes" }, errors.toString())
    }
}
