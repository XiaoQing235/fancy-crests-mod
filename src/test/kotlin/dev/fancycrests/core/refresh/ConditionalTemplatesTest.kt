package dev.fancycrests.core.refresh

import dev.fancycrests.api.PlaceholderRequest
import dev.fancycrests.core.config.ConfigService
import dev.fancycrests.core.config.ConfigYaml
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.Executor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import net.minecraft.network.chat.Component

class ConditionalTemplatesTest {
    private val target = UUID.randomUUID()
    private val request = PlaceholderRequest(target, null)

    @Test
    fun `nested conditions resolve player and foreign placeholders`() {
        val service = ConfigService(Path.of("unused.yaml"), Executor { it.run() })
        try {
            val config = service.parseConfig(ConfigYaml.parse("""
                firstLineTemplate: [{type: name}]
                conditions:
                  nick:
                    conditions: ["%player%=%essentials_nickname%"]
                    true: "%player%"
                    false: "~%essentials_nickname%"
                  uses:
                    conditions: ["%player%=TestPlayer"]
                    true: "&b[Tool] &fUses %player:statistic_raw used netherite_hoe%"
                    false: ""
                  below:
                    conditions: ["1=2"]
                    true: ""
                    false: "%condition:uses%"
            """.trimIndent()).asJsonObject)
            assertTrue(service.validate(config).isEmpty())
            val engine = TemplateEngine(
                PlaceholderServiceImpl(),
                conditions = { config.conditions },
                playerName = { "TestPlayer" },
                external = { token, _ -> when (token) {
                    "essentials_nickname" -> Component.literal("Alias")
                    "player:statistic_raw used netherite_hoe" -> Component.literal("42")
                    else -> null
                } },
            )
            assertEquals("~Alias", engine.render("%condition:nick%", request).component.string)
            assertEquals("[Tool] Uses 42", engine.render("%condition:below%", request).component.string)
        } finally { service.stop() }
    }

    @Test
    fun `unavailable placeholders stay visible and never compare equal`() {
        val config = mapOf("test" to dev.fancycrests.core.config.NamedConditionConfig(
            listOf("%missing:value%=%missing:value%"), false, "wrong", "correct",
        ))
        val engine = TemplateEngine(PlaceholderServiceImpl(), conditions = { config })
        assertEquals("correct", engine.render("%condition:test%", request).component.string)
        assertEquals("%missing:value%", engine.render("%missing:value%", request).component.string)
    }

    @Test
    fun `numeric and permission comparisons work and cyclic configuration fails validation`() {
        assertTrue(ConditionExpressions.compare("10", ">", "2"))
        val config = mapOf("staff" to dev.fancycrests.core.config.NamedConditionConfig(
            listOf("permission:example.staff", "10>=2"), false, "yes", "no",
        ))
        val engine = TemplateEngine(PlaceholderServiceImpl(), conditions = { config }, hasPermission = { _, _ -> true })
        assertEquals("yes", engine.render("%condition:staff%", request).component.string)
        val service = ConfigService(Path.of("unused.yaml"), Executor { it.run() })
        try {
            val parsed = service.parseConfig(ConfigYaml.parse("""
                firstLineTemplate: [{type: name}]
                conditions:
                  a: {conditions: ["1=1"], true: "%condition:b%", false: ""}
                  b: {conditions: ["1=1"], true: "%condition:a%", false: ""}
            """.trimIndent()).asJsonObject)
            assertTrue(service.validate(parsed).any { it.message.contains("cycle") })
        } finally { service.stop() }
    }
}
