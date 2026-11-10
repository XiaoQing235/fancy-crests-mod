package dev.fancycrests.core.config

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import java.nio.file.Path
import java.util.concurrent.Executor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Refresh-period validation of second-line rules: the plan requires 1..72000
 * inclusive and rejection of 0, 72001 and non-integers with the exact path.
 */
class SecondLineRefreshTicksTest {

    private fun service(): ConfigService = ConfigService(Path.of("fancycrests.json"), Executor {})

    private fun rootWithRefreshTicks(value: JsonPrimitive?): JsonObject {
        val rule = JsonObject()
        rule.addProperty("id", "r1")
        rule.addProperty("priority", 10)
        rule.addProperty("template", "%fancycrests:belowname%")
        if (value != null) rule.add("refreshTicks", value)
        val root = JsonObject()
        val rules = JsonArray()
        rules.add(rule)
        root.add("secondLineRules", rules)
        return root
    }

    private fun parseErrors(value: JsonPrimitive?): List<ConfigError> {
        return try {
            service().parseConfig(rootWithRefreshTicks(value))
            emptyList()
        } catch (e: ConfigService.ConfigParseException) {
            e.errors
        }
    }

    @Test
    fun `missing refreshTicks uses the default`() {
        val config = service().parseConfig(rootWithRefreshTicks(null))
        assertEquals(SecondLineRuleConfig.DEFAULT_REFRESH_TICKS, config.secondLineRules.single().refreshTicks)
    }

    @Test
    fun `boundaries 1 and 72000 are accepted`() {
        assertTrue(parseErrors(JsonPrimitive(1)).isEmpty())
        assertTrue(parseErrors(JsonPrimitive(72000)).isEmpty())
        val config = service().parseConfig(rootWithRefreshTicks(JsonPrimitive(72000)))
        assertEquals(72000, config.secondLineRules.single().refreshTicks)
    }

    @Test
    fun `zero and 72001 are rejected with the exact path`() {
        val errors = parseErrors(JsonPrimitive(0))
        assertTrue(errors.any { it.path == "secondLineRules[0].refreshTicks" }, errors.toString())
        assertTrue(parseErrors(JsonPrimitive(72001)).any { it.path == "secondLineRules[0].refreshTicks" })
    }

    @Test
    fun `non-integer values are rejected with the exact path`() {
        for (bad in listOf(JsonPrimitive(1.5), JsonPrimitive("20"), JsonPrimitive(true))) {
            val errors = parseErrors(bad)
            assertTrue(
                errors.any { it.path == "secondLineRules[0].refreshTicks" },
                "expected rejection for $bad, got $errors",
            )
        }
    }

    @Test
    fun `negative values are rejected`() {
        assertTrue(parseErrors(JsonPrimitive(-5)).any { it.path == "secondLineRules[0].refreshTicks" })
    }}
